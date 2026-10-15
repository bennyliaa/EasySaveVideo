package com.easysavevideo.save

import com.easysavevideo.core.domain.TaskStateMachine
import com.easysavevideo.core.model.*
import com.easysavevideo.recording.RecordingSnapshot

/**
 * Bounded identity-owned task executions per journal. execute is synchronous and MUST run on a background worker.
 * No transfer or native verification runs under the monitor; final journal/index publish CAS is fenced with cancel.
 * Admission and pool binding monitors never enclose this monitor or storage work.
 * Background suspension waits for the active port to release resources before publishing WAITING_USER.
 */
class SaveCoordinator(
    private val journal: SaveJournal,
    private val transfer: DirectTransferPort,
    private val verifier: ArtifactVerifier,
    private val mediaStore: PrivateMediaStore,
    private val clock: EpochClock,
    private val limits: SaveLimits,
    private val retryDelay: SaveRetryDelay = ThreadSaveRetryDelay(clock),
    private val stream: StreamSavePort = UnsupportedStreamSave,
    private val recording: RecordSavePort = UnsupportedRecordSave,
    private val networkGate: SaveNetworkGate = SaveNetworkGate {},
    private val admission: SaveExecutionAdmission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes,
        resourcesUnreleased = { transfer.requiresProcessRecovery() || stream.requiresProcessRecovery() ||
            recording.requiresProcessRecovery() || verifier.requiresProcessRecovery() }),
    private val controlledDirect: ControlledDirectPipeline? = null,
    private val privateSourceDetachmentObserver: PrivateSourceDetachmentObserver? = null,
) : TaskController {
    init { require(controlledDirect == null || journal is ControlledDirectJournal) { "CONTROLLED_DIRECT_JOURNAL_REQUIRED" } }
    private fun portFor(method: TaskMethod): DirectTransferPort? = when (method) {
        TaskMethod.DIRECT -> transfer
        TaskMethod.STREAM -> stream.takeIf { it.availability().available }
        else -> null
    }
    private fun resourcesUnreleased() = admission.recoveryRequired || transfer.requiresProcessRecovery() || stream.requiresProcessRecovery() || recording.requiresProcessRecovery() || verifier.requiresProcessRecovery() || controlledDirect?.storage?.requiresProcessRecovery() == true
    private val directLocalGate = controlledDirect?.localGate ?: DirectLocalProcessingGate(clock, ::resourcesUnreleased)

    private val lock = Any()
    /** Core-test scheduling seam only; never issues, replaces or validates execution authority. */
    internal var acceptedAttemptInstallationObserver: ((afterInstall: Boolean, attempt: AcceptedDirectAttempt) -> Unit)? = null
    private val alternativeOwner = Any()
    private class Runtime(val original: OperationContext, val method: TaskMethod, resource: SelectedMediaResource?, val sourceExpiresAt: Long, val resumeOperation: OperationContext? = null,
        val resumedFrom: SaveRecord? = null, val priorRuntimeInProcess: Boolean = false) {
        @Volatile private var ordinaryResource = resource?.takeIf { it.privateSource == null }
        val privateSource = (resource?.privateSource as? PrivateNetworkSourceBinding)?.let { PrivateRuntimeSource(it, resource) }
        var resource: SelectedMediaResource?
            get() = privateSource?.resource() ?: ordinaryResource
            set(value) {
                if (privateSource != null) { require(value == null); privateSource.revokeSource() }
                else ordinaryResource = value
            }
        val cancel = CancellationSource()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var acceptedAttempt: AcceptedDirectAttempt? = null
        fun stop() { stopped.set(true); acceptedAttempt?.revoke(); privateSource?.clearCheckpoint(); cancel.cancel() }
        @Volatile var pause = false
        @Volatile var userCancel = false
        @Volatile var executing = false
        @Volatile var exited = false
        @Volatile var lastRecord: SaveRecord? = null
        var reservedBytes = 0L
        var claim: SaveExecutionAdmission.Claim? = null
        var directOwner: DirectTransferPort? = null
        var context = original.copy(cancelToken = object : CancellationToken {
            override val isCancelled: Boolean get() = cancel.isCancelled || original.cancelToken.isCancelled
        })
    }
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, Runtime>()
    private val observers = mutableMapOf<String, MutableSet<TaskObserver>>()
    private val cachedSnapshots = java.util.concurrent.ConcurrentHashMap<String, TaskSnapshot>()
    private val activeExecutions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Runtime, Boolean>())
    private val detachedPrivateSources = java.util.concurrent.ConcurrentHashMap<PrivateNetworkSourceBinding, String>()
    private val pendingCleanup = java.util.concurrent.ConcurrentHashMap<String, SaveRecord>()
    private val terminalCleanupSweeping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var terminalCleanupCursor = 0L // Accessed only by the successful sweep CAS owner.
    private val pendingCleanupDraining = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pendingCleanupCursor: String? = null // Separate fair scan of same-process generic residues.
    private val pendingLegacyRetirements = java.util.concurrent.ConcurrentHashMap<SaveExecutionAdmission.Claim, Runtime>()

    /** External reservations remain owned by the outer worker, including accepted-before-entry. */
    fun submit(request: SaveRequest, context: OperationContext, claim: SaveExecutionAdmission.Claim,
        preparationBytes: Long = 0): SaveSubmission = admission.prepare(claim, request.method,
            request.requestId, context.operationId) {
        synchronized(lock) {
            val result = submitLocked(request, context, preparationBytes)
            val runtime = if (result is SaveSubmission.Accepted && !result.existing)
                runtimes[request.requestId]?.takeIf { it.original.operationId == context.operationId } else null
            runtime?.claim = claim
            result to runtime
        }
    } ?: SaveSubmission.Rejected(SaveError.BUSY)

    fun resume(taskId: String, operation: OperationContext, claim: SaveExecutionAdmission.Claim,
        requestContextRef: RequestContextRef? = null, resource: SelectedMediaResource? = null): SaveRecord? {
        val method = record(taskId)?.request?.method ?: return null
        return admission.prepare(claim, method, taskId, operation.operationId) {
            synchronized(lock) {
                val result = resume(taskId, operation, requestContextRef, resource)
                val runtime = runtimes[taskId]?.takeIf { it.resumeOperation === operation &&
                    it.claim == null && result?.task?.state == TaskState.RUNNING && result.operationId == operation.operationId }
                runtime?.claim = claim
                result to runtime
            }
        } ?: record(taskId)
    }

    fun submit(request: SaveRequest, context: OperationContext, preparationBytes: Long = 0): SaveSubmission = synchronized(lock) {
        submitLocked(request, context, preparationBytes)
    }

    /** The only insertion path; callers must hold the Coordinator monitor. */
    private fun submitLocked(request: SaveRequest, context: OperationContext, preparationBytes: Long): SaveSubmission {
        return try {
            if (request.input is SaveInput.PrivateNetwork || request.networkResource?.durableReference == true)
                return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            request.networkResource?.privateSource?.let {
                if (it !is PrivateNetworkSourceBinding || it.taskId != request.requestId || !it.isLive())
                    return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            }
            if (request.origin != CommandOrigin.USER) return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            if (if (request.method == TaskMethod.RECORD) !recording.availability().available else portFor(request.method) == null) return SaveSubmission.Rejected(SaveError.METHOD_UNSUPPORTED)
            if (preparationBytes < 0 || (request.method != TaskMethod.STREAM && preparationBytes != 0L))
                return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            if (preparationBytes > minOf(limits.maxTotalTransferBytes, context.resourceBudget.maxBytes ?: 0))
                return SaveSubmission.Rejected(SaveError.BYTE_LIMIT_EXCEEDED)
            val durableRequest = request.asDurableReference()
            journal.findByIdempotencyKey(request.idempotencyKey)?.let {
                return if (it.request == durableRequest.withTaskId(it.request.requestId)) { publish(it); SaveSubmission.Accepted(it, true) }
                else SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            }
            if (resourcesUnreleased()) return SaveSubmission.Rejected(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            stopError(context)?.let { return SaveSubmission.Rejected(it) }
            if (context.resourceBudget.maxBytes == null || context.resourceBudget.maxConcurrentWork != 1)
                return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            if (request.networkResource?.expiresAtEpochMillis?.let { clock.nowMillis() >= it } == true)
                return SaveSubmission.Rejected(SaveError.EXPIRED_RESOURCE)
            val record = SaveRecord(durableRequest, TaskSnapshot(request.requestId, 0, TaskState.QUEUED, request.method),
                commitId = request.requestId, operationId = context.operationId,
                deadlineEpochMillis = context.deadlineEpochMillis, retryBudget = if (request.method == TaskMethod.RECORD) 0 else context.retryBudget,
                attempt = if (request.method == TaskMethod.RECORD) 0 else context.attempt, bytesTransferred = preparationBytes)
            val inserted = journal.insert(record, limits.maxJournalEntries)
            if (inserted.request != durableRequest.withTaskId(inserted.request.requestId)) return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
            if (inserted == record) {
                val runtime = Runtime(if (request.method == TaskMethod.RECORD) context.copy(retryBudget = 0, attempt = 0) else context,
                    request.method, request.networkResource, sourceExpiry(context, request.networkResource))
                runtimes.putIfAbsent(request.requestId, runtime)
                // Source retirement can race the pre-insertion check while the app registration
                // is already known. Never leave a newly installed Runtime executable in that gap.
                runtime.privateSource?.takeIf { !it.binding.isLive() }?.let {
                    it.revokeSource(); runtime.userCancel = true; runtime.stop()
                }
            }
            publish(inserted)
            SaveSubmission.Accepted(inserted, inserted != record)
        } catch (_: Exception) { SaveSubmission.Rejected(SaveError.JOURNAL_UNAVAILABLE) }
    }

    /**
     * Call on the existing worker only after actual successful HLS preparation. The adapter supplies
     * its current inspection generation and actual prepared source/operation, never a DOM capability.
     * This reads the journal but does not mutate either task or acquire a resource claim.
     */
    fun prepareStreamAlternative(
        failed: SaveFailedIdentity,
        inspectionGeneration: Long,
        preparedSource: SelectedMediaResource,
        context: OperationContext,
        expiresAtEpochMillis: Long,
    ): SaveAlternativePreparation = synchronized(lock) {
        try {
            if (preparedSource.privateSource != null &&
                (preparedSource.privateSource !is PrivateNetworkSourceSeed || !preparedSource.privateSource.isLive()))
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.SOURCE_RESELECTION_REQUIRED)
            if (SaveMethodAlternatives.review(failed) != SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE ||
                inspectionGeneration < 0 || preparedSource.durableReference)
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST)
            val old = journal.read(failed.taskId)
            if (old == null || old.request.method != TaskMethod.DIRECT || old.task.method != old.request.method ||
                SaveFailedIdentity.from(old) != failed || context.operationId == old.operationId)
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST)
            if (!stream.availability().available)
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.METHOD_UNSUPPORTED)
            if (resourcesUnreleased())
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            stopError(context)?.let { return@synchronized SaveAlternativePreparation.Rejected(it) }
            if (context.resourceBudget.maxBytes == null || context.resourceBudget.maxConcurrentWork != 1)
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST)
            val expiry = minOf(expiresAtEpochMillis, context.deadlineEpochMillis,
                preparedSource.expiresAtEpochMillis ?: Long.MAX_VALUE,
                saturatedAdd(clock.nowMillis(), limits.sourceContextLifetimeMillis))
            if (clock.nowMillis() >= expiry)
                return@synchronized SaveAlternativePreparation.Rejected(SaveError.EXPIRED_RESOURCE)
            SaveAlternativePreparation.Ready(StreamAlternativeProposal(alternativeOwner, failed,
                old.request.idempotencyKey, inspectionGeneration, preparedSource.candidateId,
                preparedSource.sourceIdentity, preparedSource.requestContextRef,
                preparedSource.expiresAtEpochMillis, preparedSource.expectedAudio, context, expiry,
                preparedSource.privateSource as? PrivateNetworkSourceSeed))
        } catch (_: Exception) { SaveAlternativePreparation.Rejected(SaveError.JOURNAL_UNAVAILABLE) }
    }

    /**
     * One explicit confirmation fixes the complete immutable final request (including variant).
     * Consume before admission, so rejection/uncertainty cannot be retried with another request.
     * Admission's prepare fence is always entered OUTSIDE the Coordinator monitor. No execute,
     * credential acquisition, automatic retry or recovery writer is introduced here.
     */
    fun confirmStreamAlternative(
        proposal: StreamAlternativeProposal,
        inspectionGeneration: Long,
        request: SaveRequest,
        context: OperationContext,
        claim: SaveExecutionAdmission.Claim,
        preparationBytes: Long = 0,
    ): SaveAlternativeConfirmation {
        proposal.consume(alternativeOwner)?.let { return SaveAlternativeConfirmation.Rejected(it) }
        return admission.prepare(claim, TaskMethod.STREAM, request.requestId, context.operationId) {
            synchronized(lock) {
                val result = confirmStreamAlternativeLocked(proposal, inspectionGeneration, request, context, preparationBytes)
                val runtime = if (result is SaveAlternativeConfirmation.Accepted)
                    runtimes[request.requestId]?.takeIf { it.original == context } else null
                runtime?.claim = claim
                result to runtime
            }
        } ?: SaveAlternativeConfirmation.Rejected(SaveAlternativeRejection.ADMISSION_REJECTED, SaveError.BUSY)
    }

    private fun confirmStreamAlternativeLocked(
        proposal: StreamAlternativeProposal,
        inspectionGeneration: Long,
        request: SaveRequest,
        context: OperationContext,
        preparationBytes: Long,
    ): SaveAlternativeConfirmation {
        fun reject(reason: SaveAlternativeRejection, error: SaveError? = null) =
            SaveAlternativeConfirmation.Rejected(reason, error)
        try {
            if (proposal.isRevoked) return reject(SaveAlternativeRejection.REVOKED)
            if (clock.nowMillis() >= proposal.expiresAtEpochMillis)
                return reject(SaveAlternativeRejection.EXPIRED, SaveError.EXPIRED_RESOURCE)
            val source = request.networkResource
            if (inspectionGeneration != proposal.inspectionGeneration || context != proposal.operation ||
                request.method != TaskMethod.STREAM || request.origin != CommandOrigin.USER || source == null ||
                source.durableReference || source.candidateId != proposal.candidateId ||
                source.sourceIdentity != proposal.sourceIdentity || source.requestContextRef != proposal.requestContextRef ||
                source.expiresAtEpochMillis != proposal.sourceExpiresAtEpochMillis ||
                (proposal.privateSeed != null && (source.privateSource !is PrivateNetworkSourceBinding ||
                    !source.privateSource.isFrom(proposal.privateSeed) || !source.privateSource.isLive())) ||
                (proposal.privateSeed == null && source.privateSource != null) ||
                (proposal.expectedAudio && !source.expectedAudio) ||
                request.requestId in setOf(proposal.failed.taskId, proposal.oldIdempotencyKey) ||
                request.idempotencyKey in setOf(proposal.failed.taskId, proposal.oldIdempotencyKey))
                return reject(SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            val old = journal.read(proposal.failed.taskId)
            if (old == null || old.request.method != TaskMethod.DIRECT || old.task.method != old.request.method ||
                SaveFailedIdentity.from(old) != proposal.failed || old.request.idempotencyKey != proposal.oldIdempotencyKey)
                return reject(SaveAlternativeRejection.STALE_FAILURE, SaveError.INVALID_REQUEST)
            // This is a new user attempt, never idempotency replay of an unrelated existing task.
            if (journal.read(request.requestId) != null || journal.findByIdempotencyKey(request.idempotencyKey) != null)
                return reject(SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            if (proposal.isRevoked) return reject(SaveAlternativeRejection.REVOKED)
            return when (val submission = submitLocked(request, context, preparationBytes)) {
                is SaveSubmission.Accepted -> if (!submission.existing && submission.record.request == request.asDurableReference())
                    SaveAlternativeConfirmation.Accepted(submission.record)
                    else reject(SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
                is SaveSubmission.Rejected -> if (submission.error == SaveError.JOURNAL_UNAVAILABLE)
                    SaveAlternativeConfirmation.OutcomeUnknown(request.requestId)
                    else reject(SaveAlternativeRejection.SUBMISSION_REJECTED, submission.error)
            }
        } catch (_: Exception) {
            // A journal adapter may write and then throw. The token stays consumed and the fixed
            // task identity is retained for result inspection; existing startup recovery owns repair.
            return SaveAlternativeConfirmation.OutcomeUnknown(request.requestId)
        }
    }

    /** A new explicit user command, not an internal retry. It does not silently reacquire credentials. */
    fun resume(taskId: String, operation: OperationContext, requestContextRef: RequestContextRef? = null, resource: SelectedMediaResource? = null): SaveRecord? = synchronized(lock) {
        val record = journal.read(taskId) ?: return@synchronized null
        if (record.task.state != TaskState.WAITING_USER) return@synchronized record
        if (clock.nowMillis() >= record.task.waitingUser!!.expiresAtEpochMillis) return@synchronized cancelRecord(record)
        if (stopError(operation) != null || operation.resourceBudget.maxBytes == null || operation.resourceBudget.maxConcurrentWork != 1)
            return@synchronized record
        if (operation.operationId == record.operationId) return@synchronized record
        val priorRuntime = runtimes[taskId]
        if (priorRuntime?.executing == true) return@synchronized record
        // Process restart never replays a capture or reacquires system consent. Only a finalized
        // durable artifact can enter the shared local verify/commit continuation.
        if (record.request.method == TaskMethod.RECORD && (record.transfer == null || resource != null || requestContextRef != null))
            return@synchronized record
        val liveResource = if (record.request.isPrivateNetwork && record.transfer != null) {
            if (resource != null || requestContextRef != null) return@synchronized record
            null // Existing local-only recovery never reintroduces private network authority.
        } else resource ?: sourceForTask(taskId)
        if (liveResource != null && (liveResource.durableReference ||
                try { liveResource.toDurableInput(taskId) != record.request.input } catch (_: Exception) { true })) return@synchronized record
        if (record.request.isPrivateNetwork && record.transfer == null) {
            val original = priorRuntime?.resource ?: return@synchronized record
            val live = liveResource ?: return@synchronized record
            if (live.privateSource !== original.privateSource || live.privateSource?.isLive() != true ||
                live.copy(requestContextRef = original.requestContextRef) != original) return@synchronized record
        }
        if (record.transfer == null && liveResource == null) return@synchronized record
        if (resourcesUnreleased()) return@synchronized record
        if (record.waitReason == SaveWaitReason.AUTHENTICATION && requestContextRef == null) return@synchronized record
        val running = changeState(record, TaskState.RUNNING).copy(operationId = operation.operationId,
            deadlineEpochMillis = operation.deadlineEpochMillis, retryBudget = if (record.request.method == TaskMethod.RECORD) 0 else operation.retryBudget,
            attempt = if (record.request.method == TaskMethod.RECORD) 0 else operation.attempt,
            error = null, phase = if (controlledDirect != null && record.request.method == TaskMethod.DIRECT && record.transfer != null)
                record.phase else SavePhase.PREPARING)
        // A complete controlled raw must retain its real durable pre-resume phase. A crash after
        // this control CAS must not disguise an old VERIFYING/COMMITTING execution as fresh raw.
        val sourceExpiresAt = if (resource != null) sourceExpiry(operation, liveResource)
            else priorRuntime?.sourceExpiresAt ?: sourceExpiry(operation, null)
        val nextRuntime = Runtime(if (record.request.method == TaskMethod.RECORD) operation.copy(retryBudget = 0, attempt = 0) else operation,
            record.request.method, liveResource?.copy(requestContextRef = if (record.request.isPrivateNetwork)
                requestContextRef ?: liveResource.requestContextRef else requestContextRef), sourceExpiresAt, operation, record, priorRuntime != null).also { it.lastRecord = running }
        runtimes[taskId] = nextRuntime
        try { save(record, running) }
        catch (error: Exception) {
            if (priorRuntime == null) runtimes.remove(taskId, nextRuntime) else runtimes[taskId] = priorRuntime
            throw error
        }
        running
    }

    /**
     * Project-internal compensation for a proven unaccepted explicit Resume. The caller owns the
     * admission fence; this method never signals a worker, cleans up, or starts media work.
     * preparedWaiting must retain the exact proposal even when the CAS result is ambiguous.
     */
    fun recoverUnadmittedResume(
        expected: SaveRecord,
        operation: OperationContext,
        expectedWaiting: SaveRecord?,
        reason: SaveWaitReason,
        mayRepair: () -> Boolean,
        preparedWaiting: (SaveRecord) -> Unit,
    ): Boolean = synchronized(lock) {
        val runtime = runtimes[expected.task.taskId] ?: return@synchronized false
        if (runtime.resumeOperation !== operation || runtime.executing ||
            expected.operationId != operation.operationId || expected.task.state != TaskState.RUNNING)
            return@synchronized false
        if (expectedWaiting != null) {
            val wait = expectedWaiting.task.waitingUser ?: return@synchronized false
            val validReason = when (expectedWaiting.waitReason) {
                SaveWaitReason.BACKGROUND -> expected.request.method != TaskMethod.RECORD && expectedWaiting.error == null && wait.resumeAction == ResumeAction.RETRY
                SaveWaitReason.NETWORK -> expected.needsNetworkTransfer() && expectedWaiting.error == SaveError.WIFI_REQUIRED && wait.resumeAction == ResumeAction.RETRY
                SaveWaitReason.RECOVERY -> expectedWaiting.error in setOf(SaveError.RECOVERY_REQUIRED, SaveError.RESOURCE_RELEASE_UNCONFIRMED) && wait.resumeAction == ResumeAction.REVIEW_RECOVERY
                else -> false
            }
            if (!validReason || expectedWaiting != changeState(expected, TaskState.WAITING_USER, expectedWaiting.error, wait)
                    .copy(waitReason = expectedWaiting.waitReason)) return@synchronized false
        }
        val current = journal.read(expected.task.taskId) ?: return@synchronized false
        // A write may have committed before throwing. lastRecord need not have been published yet.
        if (expectedWaiting != null && current == expectedWaiting) {
            publish(current)
            return@synchronized true
        }
        if (current != expected || runtime.lastRecord != expected ||
            runtime.userCancel || operation.cancelToken.isCancelled) return@synchronized false
        val unreleased = resourcesUnreleased()
        val waitReason = when {
            unreleased -> SaveWaitReason.RECOVERY
            expected.request.method == TaskMethod.RECORD -> SaveWaitReason.RECOVERY
            runtime.pause || reason == SaveWaitReason.BACKGROUND -> SaveWaitReason.BACKGROUND
            reason == SaveWaitReason.NETWORK && expected.needsNetworkTransfer() -> SaveWaitReason.NETWORK
            else -> SaveWaitReason.RECOVERY
        }
        val error = when {
            unreleased -> SaveError.RESOURCE_RELEASE_UNCONFIRMED
            waitReason == SaveWaitReason.NETWORK -> SaveError.WIFI_REQUIRED
            waitReason == SaveWaitReason.BACKGROUND -> null
            else -> SaveError.RECOVERY_REQUIRED
        }
        // Reuse the first proposal after an authoritative read of the unchanged RUNNING record.
        // In particular a write-then-throw can never extend its WAITING_USER deadline.
        val next = expectedWaiting ?: changeState(expected, TaskState.WAITING_USER, error,
            UserWait(saturatedAdd(clock.nowMillis(), limits.waitingUserLifetimeMillis),
                if (waitReason == SaveWaitReason.RECOVERY) ResumeAction.REVIEW_RECOVERY else ResumeAction.RETRY))
            .copy(waitReason = waitReason)
        preparedWaiting(next)
        // Linearization point relative to synchronous ticket retirement/Cancel. A transaction
        // admitted here may finish before the subsequent durable Cancel, never after CANCELLED.
        if (runtime.userCancel || operation.cancelToken.isCancelled || !mayRepair()) return@synchronized false
        save(expected, next)
        true
    }

    /** Backward-compatible entry point: only this path acquires and releases its own claim. */
    fun execute(taskId: String): SaveRecord? {
        // A legacy caller has no outer Runtime retirement poll. Retry only its completed claims
        // at the next explicit execution, and only after the original resource gates confirm release.
        pendingLegacyRetirements.forEach { (claim, priorRuntime) ->
            if (!resourcesUnreleased() && admission.release(claim)) {
                clearLegacyClaim(priorRuntime, claim)
                pendingLegacyRetirements.remove(claim, priorRuntime)
            }
        }
        val runtime = synchronized(lock) {
            val current = journal.read(taskId) ?: return null
            if (current.task.state.isTerminal || current.task.state in setOf(TaskState.WAITING_USER, TaskState.INTERRUPTED)) return current
            val value = runtimes[taskId] ?: return current
            if (value.executing || value.claim != null) return current
            // A failed bookkeeping return cannot revive the exited invocation or take another
            // claim. Keep its owned material and require a new explicit Resume/Runtime.
            if (value.exited) return waitForUser(current, SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED)
            value
        }
        val claim = if (runtime.method == TaskMethod.DIRECT) admission.tryAcquireDirect() else admission.tryAcquireExclusive()
        if (claim == null) {
            // Keep legacy resource-recovery behavior; admission BUSY alone never changes a row.
            return synchronized(lock) {
                val current = journal.read(taskId) ?: return@synchronized null
                if (!runtime.executing && runtimes[taskId] === runtime && resourcesUnreleased() &&
                    current.task.state in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
                    val running = if (current.task.state == TaskState.QUEUED) save(current, changeState(current, TaskState.RUNNING)) else current
                    waitForUser(running, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                } else current
            }
        }
        try { return executeOwned(taskId, runtime, claim, legacy = true) }
        finally {
            if (!resourcesUnreleased() && admission.release(claim)) clearLegacyClaim(runtime, claim)
            else pendingLegacyRetirements[claim] = runtime
        }
    }

    private fun clearLegacyClaim(runtime: Runtime, claim: SaveExecutionAdmission.Claim) = synchronized(lock) {
        if (!runtime.executing && runtime.claim === claim) {
            runtime.claim = null
            runtime.directOwner = null
        }
    }

    /** Duplicate/stale entry never releases an externally-owned claim. */
    fun execute(taskId: String, claim: SaveExecutionAdmission.Claim): SaveRecord? {
        val runtime = runtimes[taskId] ?: return record(taskId)
        return executeOwned(taskId, runtime, claim, legacy = false)
    }

    private fun executeOwned(taskId: String, runtime: Runtime, claim: SaveExecutionAdmission.Claim, legacy: Boolean): SaveRecord? {
        val started = admission.enter(claim, taskId, runtime.original.operationId, runtime, legacyAttach = legacy) {
            synchronized(lock) {
                val record = journal.read(taskId) ?: return@synchronized false
                if (runtimes[taskId] !== runtime || runtime.executing || runtime.exited ||
                    (if (legacy) runtime.claim != null else runtime.claim !== claim) ||
                    record.task.state !in setOf(TaskState.QUEUED, TaskState.RUNNING)) return@synchronized false
                if (resourcesUnreleased()) {
                    val running = if (record.task.state == TaskState.QUEUED) save(record, changeState(record, TaskState.RUNNING)) else record
                    waitForUser(running, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    return@synchronized false
                }
                runtime.claim = claim
                activeExecutions.add(runtime); runtime.executing = true
                try {
                    if (record.task.state == TaskState.QUEUED) save(record, changeState(record, TaskState.RUNNING).copy(phase = SavePhase.PREPARING))
                } catch (error: Exception) {
                    activeExecutions.remove(runtime); runtime.executing = false
                    if (legacy) runtime.claim = null
                    throw error
                }
                true
            }
        }
        if (started != true) {
            if (started == false) admission.exit(claim, runtime)
            return record(taskId)
        }
        try {
            while (true) {
                try {
                    if (runtime.method == TaskMethod.DIRECT && runtime.acceptedAttempt?.attempt != runtime.context.attempt)
                        installAcceptedAttempt(taskId, runtime, claim)
                    runAttempt(taskId, runtime); break
                }
                catch (error: Exception) {
                    val retry = resolveAttemptFailure(taskId, runtime, SaveErrors.classify(error), allowRetry = true)
                    drainPendingCleanup(taskId)
                    if (!retry) break
                    try {
                        retryDelay.await(if (runtime.context.attempt == 1) 1_000 else 3_000, runtime.context)
                        checkActive(taskId, runtime)
                        if (runtime.method == TaskMethod.DIRECT) installAcceptedAttempt(taskId, runtime, claim)
                    } catch (retryError: Exception) {
                        synchronized(lock) {
                            journal.read(taskId)?.takeUnless { it.task.state.isTerminal }?.let {
                                if (resourcesUnreleased()) waitForUser(it, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                                else if (runtime.pause && !runtime.userCancel && !runtime.original.cancelToken.isCancelled) waitForUser(it, SaveWaitReason.BACKGROUND, null)
                                else finishFailure(it, stopError(runtime.context) ?: SaveErrors.classify(retryError))
                            }
                        }
                        drainPendingCleanup(taskId)
                        break
                    }
                }
            }
        } finally {
            runtime.exited = true
            runtime.stopped.set(true)
            runtime.acceptedAttempt?.retire()
            runtime.stop()
            try {
                synchronized(lock) {
                    runtime.executing = false
                    activeExecutions.remove(runtime)
                    journal.read(taskId)?.takeIf { it.task.state.isTerminal }?.let {
                        if (runtime.privateSource != null && !runtime.privateSource.isDetached()) runtime.resource = null
                        runtimes.remove(taskId, runtime)
                    }
                }
            } finally {
                // Never take admission while holding the Coordinator monitor. Pool binding only,
                // no transfer/native call, survives every retry and ends once per whole execution.
                try { (transfer as? FixedDirectTransferPool)?.release(runtime) }
                finally { admission.exit(claim, runtime) }
            }
        }
        return journal.read(taskId)
    }

    private fun runAttempt(taskId: String, runtime: Runtime) {
        checkActive(taskId, runtime)
        if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        var record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        if (record.request.method == TaskMethod.DIRECT) {
            when (controlledDirect?.storage?.inspectBeforeNetwork(taskId, record.artifact, record.commitId)) {
                DirectWorkspacePresence.UNREADABLE -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                DirectWorkspacePresence.PRESENT -> if (record.artifact == null || record.transfer == null)
                    throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                else -> Unit
            }
        }
        if (record.needsNetworkTransfer()) networkGate.check()
        var artifact = record.artifact
        if (artifact == null) {
            setPhase(taskId, runtime, SavePhase.PREPARING)
            artifact = mediaStore.prepare(taskId, record.commitId, runtime.context)
            if (artifact.commitId != record.commitId) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            val attached = synchronized(lock) {
                record = journal.read(taskId)!!
                if (runtimes[taskId] === runtime && !record.task.state.isTerminal) {
                    update(record) { it.copy(artifact = artifact) }; true
                } else false
            }
            // An unattached prepare result is not a terminal cleanup plan. Preserve the exact
            // catalog-owned material for recovery rather than deleting under a stale Runtime.
            if (!attached) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            checkActive(taskId, runtime)
        }
        val ownedArtifact = artifact
        record = journal.read(taskId)!!
        var result = record.transfer
        if (result == null) {
            if (record.request.networkResource?.expiresAtEpochMillis?.let { clock.nowMillis() >= it } == true)
                throw SaveFailure(SaveError.EXPIRED_RESOURCE)
            setPhase(taskId, runtime, SavePhase.EXECUTING)
            result = if (record.request.method == TaskMethod.RECORD) {
                recording.record(taskId, requireNotNull(record.request.captureDescriptor), ownedArtifact, runtime.context, limits)
            } else {
                val source = sourceForTask(taskId) ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                val executor = if (record.request.method == TaskMethod.DIRECT) {
                    runtime.directOwner ?: (if (transfer is FixedDirectTransferPool) transfer.acquire(runtime) else transfer)
                        ?.also { runtime.directOwner = it } ?: throw SaveFailure(SaveError.BUSY)
                } else portFor(record.request.method) ?: throw SaveFailure(SaveError.METHOD_UNSUPPORTED)
                // A retry retains its transport owner, but must not bypass a newly observed
                // global poison merely because it no longer needs to acquire a pool binding.
                if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                val attemptContext = runtime.context
                val transferContext = runtime.privateSource?.let { private -> attemptContext.copy(cancelToken = object : CancellationToken {
                    override val isCancelled: Boolean get() = attemptContext.cancelToken.isCancelled || !private.isSourceLive()
                }) } ?: attemptContext
                executor.transfer(source, ownedArtifact,
                    if (record.request.isPrivateNetwork) runtime.privateSource?.checkpoint() else record.checkpoint, transferContext, limits, object : TransferProgress {
                        override fun onBytes(delta: Long) = synchronized(lock) {
                            checkActive(taskId, runtime)
                            if (delta <= 0) throw SaveFailure(SaveError.INVALID_REQUEST)
                            val current = journal.read(taskId)!!
                            if (runtime.context !== attemptContext || current.transfer != null || current.phase != SavePhase.EXECUTING)
                                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            val maxBytes = minOf(limits.maxTotalTransferBytes, runtime.context.resourceBudget.maxBytes!!)
                            if (delta > runtime.reservedBytes) {
                                val required = delta - runtime.reservedBytes
                                val remaining = maxBytes - current.bytesTransferred
                                if (required > remaining) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                                // Reserve before accepting bytes: one journal write per MiB, conservative across crashes.
                                val charge = maxOf(required, minOf(1024L * 1024, remaining))
                                update(current) { it.copy(bytesTransferred = it.bytesTransferred + charge) }
                                runtime.reservedBytes += charge
                            }
                            runtime.reservedBytes -= delta; Unit
                        }
                        override fun onRestart() = synchronized(lock) {
                            checkActive(taskId, runtime)
                            val current = journal.read(taskId)!!
                            if (runtime.context !== attemptContext || current.transfer != null || current.phase != SavePhase.EXECUTING)
                                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            runtime.privateSource?.clearCheckpoint()
                            update(current) { it.copy(checkpoint = null, transfer = null, verification = null) }; Unit
                        }
                        override fun onCheckpoint(checkpoint: DownloadCheckpoint) = synchronized(lock) {
                            checkActive(taskId, runtime)
                            val current = journal.read(taskId)!!
                            if (runtime.context !== attemptContext || current.transfer != null || current.phase != SavePhase.EXECUTING)
                                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            if (checkpoint.artifactId != ownedArtifact.artifactId || checkpoint.offset > limits.maxArtifactBytes ||
                                checkpoint.totalBytes > limits.maxArtifactBytes) throw SaveFailure(SaveError.INVALID_RANGE)
                            if (current.request.isPrivateNetwork) {
                                val private = runtime.privateSource ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                                private.checkpoint(checkpoint)
                            } else update(current) { it.copy(checkpoint = checkpoint) }
                            Unit
                        }
                    })
            }
            synchronized(lock) {
                checkActive(taskId, runtime)
                if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                if (result.artifactId != ownedArtifact.artifactId || result.bytesOnDisk <= 0 || result.bytesOnDisk > minOf(limits.maxArtifactBytes, runtime.context.resourceBudget.maxBytes!!))
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                if (record.request.method == TaskMethod.DIRECT && journal.read(taskId)!!.bytesTransferred < result.bytesOnDisk)
                    throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                if (!result.matchesMethod(record.request))
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                if (record.request.method == TaskMethod.STREAM && result.streamExpectation?.conservation == null)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                if (record.request.method == TaskMethod.RECORD && result.recordingExpectation?.resumeBoundaries == null)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                if (result.expectedTotalBytes != null && result.bytesOnDisk != result.expectedTotalBytes)
                    throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                val acceptedMime = if (record.request.method == TaskMethod.DIRECT && controlledDirect != null)
                    controlledDirect.acceptsRawMime(result.responseMimeType)
                else result.responseMimeType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT) in
                    setOf("video/mp4", "application/mp4", "application/octet-stream")
                if (!acceptedMime) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            }
            val completed = result
            val proposal = synchronized(lock) {
                checkActive(taskId, runtime)
                val prior = journal.read(taskId)!!
                nextRevision(prior) { it.copy(transfer = completed) }
            }
            val persistedRaw = persistExact(proposal) {
                checkActive(taskId, runtime)
                journal.compareAndSet(proposal.task.revision - 1, proposal)
            }
            runtime.privateSource?.let { source ->
                if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                if (!source.detach()) throw SaveFailure(SaveError.CANCELLED)
                detachedPrivateSources[source.binding] = taskId
                try { privateSourceDetachmentObserver?.onPrivateSourceDetached(taskId, runtime.context.operationId, source.binding) }
                catch (_: Exception) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
            }
            if (runtime.method == TaskMethod.DIRECT) {
                val execution = runtime.acceptedAttempt ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                execution.recordTransferCompletion(persistedRaw)
                try { controlledDirect?.storage?.onTransferReleased(execution) }
                catch (_: Exception) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
            }
        }
        val completedTransfer = result
        // Recovery must enforce the same method-specific evidence gate as fresh transfers.
        if (!completedTransfer.matchesMethod(record.request))
            throw SaveFailure(SaveError.INVALID_MEDIA)
        if (record.request.method == TaskMethod.STREAM && completedTransfer.streamExpectation?.conservation == null)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (record.request.method == TaskMethod.RECORD && completedTransfer.recordingExpectation?.resumeBoundaries == null)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (record.request.method == TaskMethod.DIRECT) {
            directLocalGate.withBoundPermit(runtime, runtime.context) { permit ->
                try {
                    accepted(runtime).requireLive(runtime.context)
                    if (controlledDirect == null) runTransferredLocal(taskId, runtime, ownedArtifact, completedTransfer, permit)
                    else runControlledLocal(taskId, runtime, permit, controlledDirect)
                } catch (error: Exception) {
                    // A complete raw artifact is never downloaded again because local work failed.
                    val code = SaveErrors.classify(error).let {
                        if (it == SaveError.INCOMPLETE_TRANSFER) SaveError.RESOURCE_CHANGED else it
                    }
                    resolveAttemptFailure(taskId, runtime, code, allowRetry = false)
                    drainPendingCleanup(taskId, permit, runtime.context)
                }
            }
        } else runTransferredLocal(taskId, runtime, ownedArtifact, completedTransfer)
    }

    private fun runTransferredLocal(taskId: String, runtime: Runtime, ownedArtifact: StagingArtifact,
        completedTransfer: TransferResult, permit: DirectLocalProcessingGate.LocalPermit? = null,
        pipeline: ControlledDirectPipeline? = null) {
        var record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        if (record.hasOwnedDirectOutputEvidence()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        setPhase(taskId, runtime, SavePhase.VERIFYING)
        val verificationContext = runtime.context.copy(deadlineEpochMillis = minOf(runtime.context.deadlineEpochMillis,
            saturatedAdd(clock.nowMillis(), limits.verificationTimeoutMillis)))
        val evidence = if (pipeline != null && permit != null) {
            val current = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            pipeline.storage.verifyTransferred(current, ArtifactOutputDescriptor.Transferred(completedTransfer),
                accepted(runtime), permit, verificationContext, verifier)
        } else verifier.verify(ownedArtifact, completedTransfer, verificationContext)
        if (verificationContext.stopReason(clock.nowMillis()) != null) throw SaveFailure(stopError(verificationContext)!!)
        val verified = synchronized(lock) {
            checkActive(taskId, runtime)
            if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            record = journal.read(taskId)!!
            if (evidence.encrypted) throw SaveFailure(SaveError.PROTECTED_MEDIA)
            if (!evidence.isValidFor(ownedArtifact, completedTransfer, record.request.networkSourceMetadata?.expectedAudio ?: false))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val detachedEvidence = evidence.copy(decodedPositions = java.util.Collections.unmodifiableSet(LinkedHashSet(evidence.decodedPositions)))
            record = update(record) { it.copy(verification = detachedEvidence, phase = SavePhase.COMMITTING) }
            VerifiedArtifact(ownedArtifact, detachedEvidence)
        }
        // These potentially long operations only prepare an INVISIBLE provisional file/index.
        // Their completion must never publish media or a terminal task on its own.
        val receipt = mediaStore.commit(verified, runtime.context)
        if (!receipt.matches(verified)) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        val inspection = mediaStore.inspectCommitted(record.commitId, runtime.context)
        if (inspection?.confirms(receipt) != true) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        synchronized(lock) {
            // Single short cancel-versus-publish decision. A cancellation after this point loses.
            checkActive(taskId, runtime)
            if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            record = journal.read(taskId)!!
            val success = changeState(record.copy(receipt = receipt), TaskState.SUCCEEDED, progress = 100).copy(phase = SavePhase.CLEANUP)
            save(record, success) // Journal atomically publishes the matching provisional index.
        }
        journal.read(taskId)?.let { pendingCleanup[taskId] = it }
        drainPendingCleanup(taskId, permit, runtime.context)
    }

    private fun installAcceptedAttempt(taskId: String, runtime: Runtime, claim: SaveExecutionAdmission.Claim) {
        // Never capture admission under the Coordinator monitor. Both independent owners must
        // acknowledge the exact live invocation; a reserved/prepared claim cannot issue a view.
        val entered = admission.captureEntered(claim, runtime) ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        synchronized(lock) {
            checkActive(taskId, runtime)
            if (runtime.claim !== claim || runtime.stopped.get()) throw SaveFailure(SaveError.CANCELLED)
            runtime.acceptedAttempt?.retire()
            val candidate = AcceptedDirectAttempt.issue(entered, runtime, runtime.stopped, runtime.context,
                runtime.resumedFrom, runtime.priorRuntimeInProcess)
            acceptedAttemptInstallationObserver?.invoke(false, candidate)
            runtime.acceptedAttempt = candidate
            acceptedAttemptInstallationObserver?.invoke(true, candidate)
            // Cancel can observe null/the previous cell on either side of the assignment. The
            // monotonic latch is shared by every cell, so neither installation can resurrect it.
            if (runtime.stopped.get() || runtime.context.cancelToken.isCancelled) {
                candidate.revoke(); throw SaveFailure(SaveError.CANCELLED)
            }
            candidate.assertRuntime(runtime)
        }
    }
    private fun accepted(runtime: Runtime): AcceptedDirectAttempt =
        (runtime.acceptedAttempt ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)).also { it.assertRuntime(runtime) }

    private fun resolveAttemptFailure(taskId: String, runtime: Runtime, code: SaveError,
        allowRetry: Boolean): Boolean = synchronized(lock) {
        var record = journal.read(taskId) ?: return@synchronized false
        if (record.task.state.isTerminal) {
            // A commit can have succeeded even when its first exact readback was unavailable.
            // Observe that durable terminal result without repeating native work or publication.
            publish(record)
            if (record.artifact != null) pendingCleanup[taskId] = record
            return@synchronized false
        }
        if (runtimes[taskId] !== runtime) return@synchronized false
        if (resourcesUnreleased()) { waitForUser(record, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED); return@synchronized false }
        if (runtime.pause && !runtime.userCancel && !runtime.original.cancelToken.isCancelled) {
            waitForUser(record, SaveWaitReason.BACKGROUND, null); return@synchronized false
        }
        val stopped = stopError(runtime.context)
        if (record.phase == SavePhase.COMMITTING && stopped != SaveError.CANCELLED) {
            waitForUser(record, SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED); return@synchronized false
        }
        if (stopped != null) { finishFailure(record, stopped); return@synchronized false }
        val policy = SaveErrors.policy(code)
        val retryable = allowRetry && record.transfer == null && runtime.method != TaskMethod.RECORD && policy.retryable
        val next = if (retryable) runtime.context.nextAttempt(clock.nowMillis()) else null
        if (next != null) {
            runtime.acceptedAttempt?.retire()
            if (runtime.stopped.get() || next.cancelToken.isCancelled) {
                finishFailure(record, SaveError.CANCELLED); return@synchronized false
            }
            runtime.context = next
            record = update(record) { it.copy(attempt = next.attempt, error = code) }
            true
        } else {
            if (policy.waitReason != null) waitForUser(record, policy.waitReason, code)
            else finishFailure(record, if (retryable) SaveError.RETRY_EXHAUSTED else code)
            false
        }
    }

    private fun runControlledLocal(taskId: String, runtime: Runtime,
        permit: DirectLocalProcessingGate.LocalPermit, pipeline: ControlledDirectPipeline) {
        val storage = pipeline.storage
        val execution = accepted(runtime)
        checkActive(taskId, runtime); execution.requireLive(runtime.context)
        var record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        // The private switch routes work; all actual authority still comes from the issued owners.
        if (record.directConversion != null && !pipeline.conversionExecutionEnabled)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val artifact = record.artifact ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val raw = record.transfer ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val presence = storage.inspectBeforeNetwork(taskId, artifact, record.commitId)
        val action = when (presence) {
            DirectWorkspacePresence.ABSENT -> if (record.hasDirectOutputProof())
                throw SaveFailure(SaveError.RECOVERY_REQUIRED) else DirectRecoveryAction.RAW_COMPLETE
            DirectWorkspacePresence.UNREADABLE -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            DirectWorkspacePresence.PRESENT -> storage.inspectRecovery(record, execution, permit, runtime.context)
        }
        when (action) {
            DirectRecoveryAction.REVIEW -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            DirectRecoveryAction.RAW_COMPLETE -> {
                if (record.hasDirectOutputProof()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                storage.selectRaw(record, execution, permit, runtime.context).use { selection ->
                    execution.requireLive(runtime.context); checkActive(taskId, runtime)
                    if (selection.kind == DirectRawKind.ALREADY_MP4) {
                        if (presence != DirectWorkspacePresence.ABSENT) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                        // Close the selector before granting a legacy reader, without discarding the
                        // adapter's exact completed-selector/legacy-branch provenance record.
                        return@use
                    }
                    when (selection.kind) {
                        DirectRawKind.STRICT_MOV -> if (!pipeline.movAdmissionEnabled) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                        DirectRawKind.WEBM_CANDIDATE -> if (!pipeline.conversionExecutionEnabled) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                        DirectRawKind.ALREADY_MP4 -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                    }
                    setPhase(taskId, runtime, SavePhase.NORMALIZING)
                    record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                    if (selection.kind == DirectRawKind.WEBM_CANDIDATE) {
                        storage.convert(selection, record, execution, permit, runtime.context, limits).use { completion ->
                            val proof = completion.proof
                            val source = record.request.networkSourceMetadata ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            if (!proof.isCurrentPolicy() || !proof.matchesSource(taskId, source.sourceIdentity, artifact, raw))
                                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            // Requested audio must be present; detected audio stays in its current-policy proof.
                            if (source.expectedAudio && proof.expectation.audio == null)
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            val typedJournal = journal as? ControlledDirectJournal ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            val proposal = synchronized(lock) {
                                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                                val prior = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                                if (prior != record || prior.hasDirectOutputProof() || prior.phase != SavePhase.NORMALIZING)
                                    throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                                nextRevision(prior) { it.copy(directConversion = proof, checkpoint = null) }
                            }
                            // The exact completion and its outer Selection stay live through readback.
                            persistExact(proposal) {
                                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                                typedJournal.compareAndSetConversionProof(proposal.task.revision - 1, proposal, completion)
                            }
                        }
                    } else {
                        storage.normalize(selection, record, execution, permit, runtime.context, limits).use { completion ->
                            val proof = completion.proof
                            if (!proof.isCurrentPolicy()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            if (record.request.networkSourceMetadata?.expectedAudio == true && proof.conservation.audio == null)
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            if (!proof.matchesTransfer(raw) || proof.taskId != taskId || proof.artifactId != artifact.artifactId ||
                                proof.commitId != artifact.commitId ||
                                proof.sourceIdentity != record.request.networkSourceMetadata?.sourceIdentity)
                                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            val typedJournal = journal as? ControlledDirectJournal ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                            val proposal = synchronized(lock) {
                                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                                val prior = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                                if (prior != record || prior.transfer != raw || prior.artifact != artifact || prior.hasDirectOutputProof() ||
                                    prior.phase != SavePhase.NORMALIZING) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                                // One copy, one revision, one CAS: a normalized record can NEVER retain
                                // a raw download checkpoint, even as an intermediate Kotlin value.
                                nextRevision(prior) { it.copy(directNormalization = proof, checkpoint = null) }
                            }
                            persistExact(proposal) {
                                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                                typedJournal.compareAndSetProof(proposal.task.revision - 1, proposal, completion)
                            }
                        }
                    }
                }
                record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                if (!record.hasDirectOutputProof()) {
                    runTransferredLocal(taskId, runtime, artifact, raw, permit, pipeline)
                    return
                }
            }
            DirectRecoveryAction.ADOPT, DirectRecoveryAction.VERIFY, DirectRecoveryAction.COMMIT -> {
                if (!record.hasDirectOutputProof() || record.checkpoint != null)
                    throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            }
        }
        // Recovered data never overrides current active policy or the closed private gate.
        when (val active = record.selectedOutputDescriptor()) {
            is ArtifactOutputDescriptor.NormalizedDirect -> if (!active.normalizationProof.isCurrentPolicy() ||
                (record.request.networkSourceMetadata?.expectedAudio == true && active.normalizationProof.conservation.audio == null))
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            is ArtifactOutputDescriptor.ConvertedDirect -> if (!pipeline.conversionExecutionEnabled ||
                !active.conversionProof.isCurrentPolicy() ||
                (record.request.networkSourceMetadata?.expectedAudio == true && active.conversionProof.expectation.audio == null))
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            else -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        if (action == DirectRecoveryAction.RAW_COMPLETE || action == DirectRecoveryAction.ADOPT) {
            setPhase(taskId, runtime, SavePhase.ADOPTING)
            record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            storage.adopt(record, execution, permit, runtime.context)
        }
        if (action != DirectRecoveryAction.COMMIT) {
            setPhase(taskId, runtime, SavePhase.VERIFYING)
            record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            val selected = record.selectedOutputDescriptor() ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            val verificationContext = runtime.context.copy(deadlineEpochMillis = minOf(runtime.context.deadlineEpochMillis,
                saturatedAdd(clock.nowMillis(), limits.verificationTimeoutMillis)))
            val verification = when (selected) {
                is ArtifactOutputDescriptor.NormalizedDirect -> storage.verifyNormalized(record, selected, execution, permit, verificationContext, verifier)
                is ArtifactOutputDescriptor.ConvertedDirect -> storage.verifyNormalized(record, selected, execution, permit, verificationContext, verifier)
                is ArtifactOutputDescriptor.Transferred -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            }
            verification.use { completion ->
                if (verificationContext.stopReason(clock.nowMillis()) != null) throw SaveFailure(stopError(verificationContext)!!)
                val evidence = completion.evidence
                if (evidence.encrypted) throw SaveFailure(SaveError.PROTECTED_MEDIA)
                if (!evidence.isValidFor(artifact, selected, record.request.networkSourceMetadata?.expectedAudio ?: false))
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                val typedJournal = journal as? ControlledDirectJournal ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                val proposal = synchronized(lock) {
                    checkActive(taskId, runtime); execution.requireLive(verificationContext)
                    if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    val current = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                    if (current != record || current.selectedOutputDescriptor() != selected)
                        throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                    nextRevision(current) { it.copy(verification = evidence.copy(decodedPositions = java.util.Collections.unmodifiableSet(LinkedHashSet(evidence.decodedPositions))),
                        phase = SavePhase.COMMITTING) }
                }
                persistExact(proposal) {
                    checkActive(taskId, runtime); execution.requireLive(runtime.context)
                    typedJournal.compareAndSetVerification(proposal.task.revision - 1, proposal, completion)
                }
            }
        } else setPhase(taskId, runtime, SavePhase.COMMITTING)
        record = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        val output = record.selectedOutputDescriptor() ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val evidence = record.verification ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (!evidence.isValidFor(artifact, output, record.request.networkSourceMetadata?.expectedAudio ?: false))
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val commitHandle = when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> storage.beginNormalizedCommit(record, output, execution, permit, runtime.context)
            is ArtifactOutputDescriptor.ConvertedDirect -> storage.beginNormalizedCommit(record, output, execution, permit, runtime.context)
            is ArtifactOutputDescriptor.Transferred -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        commitHandle.use { commit ->
            val publication = when (output) {
                is ArtifactOutputDescriptor.NormalizedDirect -> storage.inspectNormalizedCommit(commit, record, output, execution, permit, runtime.context)
                is ArtifactOutputDescriptor.ConvertedDirect -> storage.inspectNormalizedCommit(commit, record, output, execution, permit, runtime.context)
                is ArtifactOutputDescriptor.Transferred -> throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            }
            val receipt = publication.receipt
            if (!receipt.matches(VerifiedArtifact(artifact, evidence))) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            val typedJournal = journal as? ControlledDirectJournal ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            val proposal = synchronized(lock) {
                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                val current = journal.read(taskId) ?: throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                if (current != record) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                changeState(current.copy(receipt = receipt), TaskState.SUCCEEDED, progress = 100).copy(phase = SavePhase.CLEANUP)
            }
            persistExact(proposal) {
                checkActive(taskId, runtime); execution.requireLive(runtime.context)
                if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                typedJournal.compareAndSetNormalized(proposal.task.revision - 1, proposal, publication)
            }
            pendingCleanup[taskId] = proposal
        }
        journal.read(taskId)?.let { pendingCleanup[taskId] = it }
        drainPendingCleanup(taskId, permit, runtime.context)
    }

    private fun nextRevision(record: SaveRecord, mutation: (SaveRecord) -> SaveRecord): SaveRecord {
        if (record.task.revision == Long.MAX_VALUE) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        return mutation(record).copy(task = record.task.copy(revision = record.task.revision + 1))
    }

    /** A fixed proposal survives write-then-throw. All uncertain readback is outside monitors. */
    private fun persistExact(proposal: SaveRecord, transaction: () -> Boolean): SaveRecord {
        try {
            synchronized(lock) {
                if (!transaction()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                publish(proposal)
            }
            return proposal
        } catch (_: Exception) {
            val current = try { journal.read(proposal.task.taskId) } catch (_: Exception) { null }
            if (current != proposal) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            synchronized(lock) { publish(proposal) }
            return proposal
        }
    }

    /** No caller may hold Coordinator/admission/storage monitors while invoking this method. */
    private fun drainPendingCleanup(taskId: String, permit: DirectLocalProcessingGate.LocalPermit? = null,
        invocationContext: OperationContext? = null, cleanupContext: OperationContext? = null) {
        val pending = pendingCleanup[taskId] ?: return
        val ioContext = cleanupContext ?: OperationContext("cleanup-${pending.operationId}", saturatedAdd(clock.nowMillis(),
            minOf(limits.verificationTimeoutMillis, 10_000L)), CancellationSource(),
            resourceBudget = ResourceBudget(maxBytes = limits.maxArtifactBytes))
        if (cleanupTerminalRecord(pending, ioContext, permit, invocationContext))
            pendingCleanup.remove(taskId, pending)
    }

    /** Shared first-pass/restart cleanup; snapshots are expectations, never deletion authority. */
    private fun cleanupTerminalRecord(current: SaveRecord, ioContext: OperationContext,
        permit: DirectLocalProcessingGate.LocalPermit? = null, invocationContext: OperationContext? = null,
        typedOnly: Boolean = false): Boolean {
        if (!current.task.state.isTerminal) return false
        val taskId = current.task.taskId
        fun clean(local: DirectLocalProcessingGate.LocalPermit?, bound: OperationContext?): Boolean {
            if (ioContext.stopReason(clock.nowMillis()) != null || resourcesUnreleased() || journal.read(taskId) != current)
                return false
            val presence = if (current.request.method == TaskMethod.DIRECT)
                controlledDirect?.storage?.inspectBeforeNetwork(taskId, current.artifact, current.commitId) else null
            if (presence == DirectWorkspacePresence.UNREADABLE || (typedOnly && presence != DirectWorkspacePresence.PRESENT))
                return false
            if (presence == DirectWorkspacePresence.PRESENT || current.hasDirectOutputProof()) {
                val storage = controlledDirect?.storage ?: return false
                storage.cleanupTerminal(current, local ?: return false, bound ?: return false, ioContext)
            } else {
                // Generic commit recovery owns invisible INTENT/INDEXED. Never infer deletion
                // permission for an ordinary failed COMMITTING task from a terminal state alone.
                if (current.task.state != TaskState.SUCCEEDED && current.phase == SavePhase.COMMITTING) return false
                current.artifact?.let { mediaStore.cleanup(it, current.task.state == TaskState.SUCCEEDED) }
            }
            return true
        }
        return try {
            if (ioContext.stopReason(clock.nowMillis()) != null || resourcesUnreleased()) return false
            if (current.request.method == TaskMethod.DIRECT && permit == null)
                directLocalGate.withBoundPermit(Any(), ioContext) { clean(it, ioContext) }
            else clean(permit, invocationContext)
        } catch (_: Exception) { false } // Exact residue remains pending; success stays success.
    }

    /**
     * One bounded ledger page on the existing control worker. This never creates a Runtime,
     * execution Claim, source lease, native binding or network request. Failed/skipped keys remain
     * durable and are revisited next cycle; a cursor is only fair scan progress, not a cleanup ack.
     */
    fun sweepTerminalCleanup(context: OperationContext) {
        val storage = controlledDirect?.storage ?: return
        if (!terminalCleanupSweeping.compareAndSet(false, true)) return
        try {
            val bounded = boundedCleanupContext(context)
            if (bounded.stopReason(clock.nowMillis()) != null || resourcesUnreleased()) return
            val limit = minOf(limits.maxRecoveryEntries, 32)
            val page = try { storage.terminalCleanupPage(terminalCleanupCursor, limit) }
                catch (_: Exception) { DirectTerminalCleanupPage.Unreadable }
            if (page !is DirectTerminalCleanupPage.Readable) return
            if (page.entries.size > limit) return
            val entries = page.entries.toList()
            if (entries.size > limit || (entries.isEmpty() && page.hasMore)) return
            var prior = terminalCleanupCursor
            for (entry in entries) {
                if (entry.cursor <= prior) return
                prior = entry.cursor
            }
            for (entry in entries) {
                if (bounded.stopReason(clock.nowMillis()) != null || resourcesUnreleased()) return
                entry.record?.takeIf { it.request.method == TaskMethod.DIRECT && it.task.state.isTerminal && it.artifact != null }
                    ?.let { cleanupTerminalRecord(it, bounded, typedOnly = true) }
                terminalCleanupCursor = entry.cursor
            }
            if (!page.hasMore) terminalCleanupCursor = 0L
        } finally { terminalCleanupSweeping.set(false) }
    }

    private fun boundedCleanupContext(context: OperationContext): OperationContext = context.copy(
        deadlineEpochMillis = minOf(context.deadlineEpochMillis,
            saturatedAdd(clock.nowMillis(), minOf(limits.verificationTimeoutMillis, 10_000L))))

    private fun drainPendingCleanup(context: OperationContext) {
        if (!pendingCleanupDraining.compareAndSet(false, true)) return
        try {
            val bounded = boundedCleanupContext(context)
            val after = pendingCleanupCursor
            val keys = pendingCleanup.keys.sorted().filter { after == null || it > after }
            val page = keys.take(minOf(limits.maxRecoveryEntries, 32))
            for (taskId in page) {
                if (bounded.stopReason(clock.nowMillis()) != null) return
                drainPendingCleanup(taskId, cleanupContext = bounded)
                pendingCleanupCursor = taskId
            }
            if (page.size == keys.size) pendingCleanupCursor = null
        } finally { pendingCleanupDraining.set(false) }
    }

    /** Memory-only exact-binding exit; a queued source-exit callback cannot cancel detached output. */
    fun signalPrivateSourceExit(taskId: String, binding: PrivateNetworkSourceBinding): PrivateSourceExitResult {
        if (binding.taskId != taskId) return PrivateSourceExitResult.NOT_OWNED
        binding.revoke()
        if (detachedPrivateSources[binding] == taskId) return PrivateSourceExitResult.OUTPUT_ONLY
        val runtime = runtimes[taskId] ?: return PrivateSourceExitResult.NOT_OWNED
        val source = runtime.privateSource?.takeIf { it.binding === binding } ?: return PrivateSourceExitResult.NOT_OWNED
        if (!source.revokeSource()) return PrivateSourceExitResult.OUTPUT_ONLY
        runtime.userCancel = true; runtime.stop()
        return PrivateSourceExitResult.STOP_REQUESTED
    }

    /** Historical source release only, never an execution/native authority or whole-owner drain. */
    fun isPrivateSourceDetached(taskId: String, binding: PrivateNetworkSourceBinding): Boolean =
        detachedPrivateSources[binding] == taskId || runtimes[taskId]?.privateSource?.let {
            it.binding === binding && it.isDetached()
        } == true

    /** Immediate memory-only lifecycle fence; never depends on a control queue or journal IO. */
    fun signalBackgroundPauseAll() {
        runtimes.values.filter { it.method != TaskMethod.RECORD }.forEach { runtime -> runtime.pause = true; runtime.stop() }
    }
    /** Immediate user cancellation signal, safe before an asynchronously queued durable command. */
    fun signalUserCancel(taskId: String) {
        runtimes[taskId]?.let { runtime ->
            runtime.userCancel = true; runtime.privateSource?.revokeSource(); runtime.stop()
            if (runtime.method == TaskMethod.RECORD) recording.cancel(taskId)
        }
    }

    /** Tab navigation must not call this; use only the app's real foreground/background boundary. */
    fun pauseForBackground(taskId: String): SaveRecord? {
        val runtime = runtimes[taskId]
        if (runtime?.method == TaskMethod.RECORD) return runtime.lastRecord
        if (runtime?.executing == true) {
            runtime.pause = true; runtime.stop(); return runtime.lastRecord
        }
        return synchronized(lock) {
            val currentRuntime = runtimes[taskId]
            if (currentRuntime?.method == TaskMethod.RECORD) return@synchronized currentRuntime.lastRecord
            if (currentRuntime?.executing == true) {
                currentRuntime.pause = true; currentRuntime.stop(); return@synchronized currentRuntime.lastRecord
            }
            val record = journal.read(taskId) ?: return@synchronized null
            if (record.request.method == TaskMethod.RECORD || record.task.state.isTerminal || record.task.state == TaskState.WAITING_USER) return@synchronized record
            val running = if (record.task.state == TaskState.QUEUED) save(record, changeState(record, TaskState.RUNNING)) else record
            waitForUser(running, SaveWaitReason.BACKGROUND, null)
        }
    }

    /** An unstarted network task waits for policy, never masquerading as a background pause. */
    fun deferForNetwork(taskId: String): SaveRecord? = synchronized(lock) {
        val record = journal.read(taskId) ?: return@synchronized null
        if (!record.needsNetworkTransfer() || record.task.state.isTerminal || record.task.state == TaskState.WAITING_USER)
            return@synchronized record
        val runtime = runtimes[taskId]
        if (runtime?.executing == true) return@synchronized record
        if (runtime?.userCancel == true || runtime?.original?.cancelToken?.isCancelled == true)
            return@synchronized if (resourcesUnreleased()) record else finishFailure(record, SaveError.CANCELLED)
        val running = if (record.task.state == TaskState.QUEUED) save(record, changeState(record, TaskState.RUNNING)) else record
        if (resourcesUnreleased()) waitForUser(running, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        else waitForUser(running, SaveWaitReason.NETWORK, SaveError.WIFI_REQUIRED)
    }

    /** A rejected local-recovery schedule must not strand a finalized recording in RUNNING. */
    fun deferFinalizedRecording(taskId: String): SaveRecord? = synchronized(lock) {
        val record = journal.read(taskId) ?: return@synchronized null
        val finalized = record.transfer
        if (record.request.method != TaskMethod.RECORD || runtimes[taskId]?.executing == true ||
            finalized?.recordingExpectation?.finalized != true || !finalized.matchesMethod(record.request) ||
            record.task.state !in setOf(TaskState.QUEUED, TaskState.RUNNING)) return@synchronized record
        // No capture command, cancellation signal or publication: retain the closed artifact for
        // another explicit local-only resume. Repeated deferrals leave the existing wait intact.
        val running = if (record.task.state == TaskState.QUEUED) save(record, changeState(record, TaskState.RUNNING)) else record
        waitForUser(running, SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED)
    }

    override fun cancel(taskId: String): TaskSnapshot? {
        // Signal before any monitor, journal or storage IO; long hashes must not delay cancellation.
        val runtime = runtimes[taskId]
        runtime?.userCancel = true
        runtime?.privateSource?.revokeSource()
        runtime?.stop()
        if (runtime?.method == TaskMethod.RECORD) recording.cancel(taskId)
        if (runtime?.executing == true) return runtime.lastRecord?.task
        val result = synchronized(lock) {
            val currentRuntime = runtimes[taskId]
            currentRuntime?.userCancel = true; currentRuntime?.stop()
            if (currentRuntime?.executing == true) return@synchronized currentRuntime.lastRecord?.task
            val record = journal.read(taskId) ?: return@synchronized null
            if (record.task.state.isTerminal || resourcesUnreleased()) return@synchronized record.task
            finishFailure(record, SaveError.CANCELLED).task
        }
        drainPendingCleanup(taskId)
        return result
    }
    /** Capture pause preserves the same session; background download suspension never calls this. */
    fun recordingSnapshot(taskId: String): RecordingSnapshot? = if (isActiveCapture(taskId)) recording.snapshot(taskId) else null
    fun pauseRecording(taskId: String): RecordingSnapshot? = if (isActiveCapture(taskId)) recording.pause(taskId) else null
    fun resumeRecording(taskId: String): RecordingSnapshot? = if (isActiveCapture(taskId)) recording.resume(taskId) else null
    /** Normal finalization may publish after independent verification; cancellation never does. */
    fun stopAndSave(taskId: String): RecordingSnapshot? = if (isActiveCapture(taskId)) recording.stopAndSave(taskId) else null
    private fun isActiveCapture(taskId: String): Boolean = runtimes[taskId]?.let {
        it.method == TaskMethod.RECORD && it.executing && !it.userCancel && !it.cancel.isCancelled
    } == true

    /** Nonblocking memory-only projection. submit, record, recover and execution populate it. */
    override fun snapshot(taskId: String): TaskSnapshot? = cachedSnapshots[taskId]
    /** Volatile in-memory subphase; never waits behind verification, commit or journal IO. */
    fun phaseSnapshot(taskId: String): SavePhase? = runtimes[taskId]?.lastRecord?.phase
    /** Durable read: call on a background/control worker, never the UI thread. */
    fun record(taskId: String): SaveRecord? = synchronized(lock) { journal.read(taskId)?.also { cachedSnapshots[taskId] = it.task } }
    /** Short-lived source context only. A durable origin-only reference must never be used for downloading. */
    fun sourceForTask(taskId: String): SelectedMediaResource? {
        val runtime = runtimes[taskId] ?: return null
        if (clock.nowMillis() >= runtime.sourceExpiresAt) { runtime.resource = null; return null }
        return runtime.resource
    }
    private fun sourceExpiry(operation: OperationContext, resource: SelectedMediaResource?): Long = minOf(
        operation.deadlineEpochMillis, saturatedAdd(clock.nowMillis(), limits.sourceContextLifetimeMillis),
        resource?.expiresAtEpochMillis ?: Long.MAX_VALUE)
    override fun observe(taskId: String, observer: TaskObserver): TaskObservation {
        synchronized(lock) {
            observers.getOrPut(taskId) { mutableSetOf() }.add(observer)
            cachedSnapshots[taskId]?.let { try { observer.onSnapshot(it) } catch (_: Exception) {} }
        }
        return TaskObservation { synchronized(lock) { observers[taskId]?.remove(observer); Unit } }
    }

    /** Expire only due waits; leave live sources and nonexpired tasks alone. Safe for bounded foreground polling. */
    fun expireWaitingUsers(context: OperationContext): List<SaveRecord> {
        val results = synchronized(lock) {
        val expired = mutableListOf<SaveRecord>()
        for (record in journal.listUnfinished(limits.maxRecoveryEntries)) {
            if (context.stopReason(clock.nowMillis()) != null) break
            if (runtimes[record.task.taskId]?.executing == true) continue
            if (record.task.state == TaskState.WAITING_USER && clock.nowMillis() >= record.task.waitingUser!!.expiresAtEpochMillis) {
                val result = cancelRecord(record)
                if (result.task.state == TaskState.CANCELLED) expired.add(result)
            }
        }
        // Eligibility expires even while the user is idle; no durable source URL is created.
        runtimes.keys.forEach { sourceForTask(it) }
        expired
    }
        drainPendingCleanup(context)
        return results
    }

    /** Bounded recovery never starts network work and never deletes unknown or committed files. */
    fun recover(context: OperationContext): List<SaveRecord> {
        val results = synchronized(lock) {
        val recovered = mutableListOf<SaveRecord>()
        for (original in journal.listUnfinished(limits.maxRecoveryEntries)) {
            if (context.stopReason(clock.nowMillis()) != null) break
            val liveRuntime = runtimes[original.task.taskId]
            if (liveRuntime?.executing == true || (liveRuntime?.claim?.released == false &&
                    original.task.state in setOf(TaskState.QUEUED, TaskState.RUNNING))) continue
            var record = original
            if (record.task.state.isTerminal) continue
            if (record.request.isPrivateNetwork && record.transfer == null &&
                (liveRuntime?.resource?.privateSource?.isLive() != true)) {
                record = finishFailure(record, SaveError.SOURCE_RESELECTION_REQUIRED)
            } else if (record.schemaVersion != 1) {
                record = save(record, changeState(record, TaskState.FAILED, SaveError.RECOVERY_REQUIRED))
            } else if (record.task.state == TaskState.WAITING_USER) {
                if (clock.nowMillis() >= record.task.waitingUser!!.expiresAtEpochMillis) record = cancelRecord(record)
            } else {
                if (record.task.state != TaskState.INTERRUPTED)
                    record = save(record, changeState(record, TaskState.INTERRUPTED, SaveError.RECOVERY_REQUIRED))
                record = waitForUser(record, SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED)
            }
            runtimes.remove(record.task.taskId)
            recovered.add(record)
        }
        recovered
    }
        drainPendingCleanup(context)
        return results
    }

    private fun checkActive(taskId: String, runtime: Runtime) = synchronized(lock) {
        if (runtimes[taskId] !== runtime || !runtime.executing || !activeExecutions.contains(runtime)) throw SaveFailure(SaveError.CANCELLED)
        stopError(runtime.context)?.let { throw SaveFailure(it) }
        val state = journal.read(taskId)?.task?.state
        if (state != TaskState.RUNNING) throw SaveFailure(SaveError.CANCELLED)
        Unit
    }
    private fun setPhase(taskId: String, runtime: Runtime, phase: SavePhase) {
        val proposal = synchronized(lock) {
            checkActive(taskId, runtime)
            nextRevision(journal.read(taskId)!!) { it.copy(phase = phase, error = null) }
        }
        persistExact(proposal) {
            checkActive(taskId, runtime)
            journal.compareAndSet(proposal.task.revision - 1, proposal)
        }
    }
    private fun stopError(context: OperationContext): SaveError? = when (context.stopReason(clock.nowMillis())) {
        OperationStopReason.CANCELLED -> SaveError.CANCELLED
        OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
        OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
        null -> null
    }
    private fun cancelRecord(record: SaveRecord): SaveRecord = if (resourcesUnreleased()) record else finishFailure(record, SaveError.CANCELLED)
    private fun finishFailure(record: SaveRecord, code: SaveError): SaveRecord {
        val result = save(record, changeState(record, if (code == SaveError.CANCELLED) TaskState.CANCELLED else TaskState.FAILED, code))
        if (record.artifact != null) pendingCleanup[record.task.taskId] = result
        runtimes[record.task.taskId]?.takeUnless { it.executing }?.let { runtimes.remove(record.task.taskId, it) }
        return result
    }
    private fun waitForUser(record: SaveRecord, reason: SaveWaitReason, error: SaveError?): SaveRecord {
        val wait = UserWait(saturatedAdd(clock.nowMillis(), limits.waitingUserLifetimeMillis),
            if (reason == SaveWaitReason.RECOVERY) ResumeAction.REVIEW_RECOVERY else ResumeAction.RETRY)
        return save(record, changeState(record, TaskState.WAITING_USER, error, wait).copy(waitReason = reason))
    }
    private fun changeState(record: SaveRecord, next: TaskState, error: SaveError? = null, wait: UserWait? = null, progress: Int? = record.task.progressPercent): SaveRecord {
        val machine = TaskStateMachine(record.task)
        if (!machine.transition(record.task.revision, next, error?.let(SaveErrors::coreCode), wait, progress))
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        return record.copy(task = machine.snapshot, error = error,
            waitReason = if (next == TaskState.WAITING_USER) record.waitReason ?: SaveWaitReason.RECOVERY else null)
    }
    private fun update(record: SaveRecord, mutation: (SaveRecord) -> SaveRecord): SaveRecord {
        if (record.task.revision == Long.MAX_VALUE) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        return save(record, mutation(record).copy(task = record.task.copy(revision = record.task.revision + 1)))
    }
    private fun save(previous: SaveRecord, next: SaveRecord): SaveRecord {
        if (!journal.compareAndSet(previous.task.revision, next)) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        publish(next); return next
    }
    private fun publish(record: SaveRecord) {
        cachedSnapshots[record.task.taskId] = record.task
        runtimes[record.task.taskId]?.let { runtime ->
            runtime.lastRecord = record
            if (record.task.state !in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
                runtime.stopped.set(true); runtime.acceptedAttempt?.revoke()
            }
        }
        observers[record.task.taskId]?.toList()?.forEach { try { it.onSnapshot(record.task) } catch (_: Exception) {} }
    }
}

private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Compare an idempotent capture against the original request identity without manufacturing a URL. */
private fun SaveRequest.withTaskId(taskId: String): SaveRequest = when (val source = input) {
    is SaveInput.Network -> copy(requestId = taskId)
    is SaveInput.PrivateNetwork -> { require(taskId == requestId); this }
    is SaveInput.Capture -> copy(requestId = taskId, input = source.copy(descriptor = source.descriptor.copy(sessionId = taskId)))
}
