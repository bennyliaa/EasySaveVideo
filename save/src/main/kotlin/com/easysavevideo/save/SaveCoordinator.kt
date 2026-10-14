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
) : TaskController {
    private fun portFor(method: TaskMethod): DirectTransferPort? = when (method) {
        TaskMethod.DIRECT -> transfer
        TaskMethod.STREAM -> stream.takeIf { it.availability().available }
        else -> null
    }
    private fun resourcesUnreleased() = admission.recoveryRequired || transfer.requiresProcessRecovery() || stream.requiresProcessRecovery() || recording.requiresProcessRecovery() || verifier.requiresProcessRecovery()

    private val lock = Any()
    private val alternativeOwner = Any()
    private class Runtime(val original: OperationContext, val method: TaskMethod, @Volatile var resource: SelectedMediaResource?, val sourceExpiresAt: Long, val resumeOperation: OperationContext? = null) {
        val cancel = CancellationSource()
        @Volatile var pause = false
        @Volatile var userCancel = false
        @Volatile var executing = false
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
            if (request.networkResource?.durableReference == true) return SaveSubmission.Rejected(SaveError.INVALID_REQUEST)
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
            if (inserted == record) runtimes.putIfAbsent(request.requestId, Runtime(if (request.method == TaskMethod.RECORD) context.copy(retryBudget = 0, attempt = 0) else context,
                request.method, request.networkResource, sourceExpiry(context, request.networkResource)))
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
                preparedSource.expiresAtEpochMillis, preparedSource.expectedAudio, context, expiry))
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
        val liveResource = resource ?: sourceForTask(taskId)
        if (liveResource != null && (liveResource.durableReference || liveResource.asDurableReference() != record.request.networkResource)) return@synchronized record
        if (record.transfer == null && liveResource == null) return@synchronized record
        if (resourcesUnreleased()) return@synchronized record
        if (record.waitReason == SaveWaitReason.AUTHENTICATION && requestContextRef == null) return@synchronized record
        val running = changeState(record, TaskState.RUNNING).copy(operationId = operation.operationId,
            deadlineEpochMillis = operation.deadlineEpochMillis, retryBudget = if (record.request.method == TaskMethod.RECORD) 0 else operation.retryBudget,
            attempt = if (record.request.method == TaskMethod.RECORD) 0 else operation.attempt,
            error = null, phase = SavePhase.PREPARING)
        val sourceExpiresAt = if (resource != null) sourceExpiry(operation, liveResource)
            else priorRuntime?.sourceExpiresAt ?: sourceExpiry(operation, null)
        val nextRuntime = Runtime(if (record.request.method == TaskMethod.RECORD) operation.copy(retryBudget = 0, attempt = 0) else operation,
            record.request.method, liveResource?.copy(requestContextRef = requestContextRef), sourceExpiresAt, operation).also { it.lastRecord = running }
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
            if (admission.release(claim)) {
                clearLegacyClaim(priorRuntime, claim)
                pendingLegacyRetirements.remove(claim, priorRuntime)
            }
        }
        val runtime = synchronized(lock) {
            val current = journal.read(taskId) ?: return null
            if (current.task.state.isTerminal || current.task.state in setOf(TaskState.WAITING_USER, TaskState.INTERRUPTED)) return current
            val value = runtimes[taskId] ?: return current
            if (value.executing || value.claim != null) return current
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
            if (admission.release(claim)) clearLegacyClaim(runtime, claim)
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
                if (runtimes[taskId] !== runtime || runtime.executing ||
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
                try { runAttempt(taskId, runtime); break }
                catch (error: Exception) {
                    val code = SaveErrors.classify(error)
                    val retry = synchronized(lock) {
                        var record = journal.read(taskId) ?: return null
                        if (record.task.state.isTerminal || runtimes[taskId] !== runtime) return@synchronized false
                        if (resourcesUnreleased()) { waitForUser(record, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED); return@synchronized false }
                        if (runtime.pause && !runtime.userCancel && !runtime.original.cancelToken.isCancelled) { waitForUser(record, SaveWaitReason.BACKGROUND, null); return@synchronized false }
                        if (record.phase == SavePhase.COMMITTING && stopError(runtime.context) != SaveError.CANCELLED) { waitForUser(record, SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED); return@synchronized false }
                        val stopped = stopError(runtime.context)
                        if (stopped != null) { finishFailure(record, stopped); return@synchronized false }
                        val policy = SaveErrors.policy(code)
                        val next = if (runtime.method != TaskMethod.RECORD && policy.retryable) runtime.context.nextAttempt(clock.nowMillis()) else null
                        if (next != null) {
                            runtime.context = next
                            record = update(record) { it.copy(attempt = next.attempt, error = code) }
                            true
                        } else {
                            if (policy.waitReason != null) waitForUser(record, policy.waitReason, code)
                            else finishFailure(record, if (runtime.method != TaskMethod.RECORD && policy.retryable) SaveError.RETRY_EXHAUSTED else code)
                            false
                        }
                    }
                    if (!retry) break
                    try {
                        retryDelay.await(if (runtime.context.attempt == 1) 1_000 else 3_000, runtime.context)
                        checkActive(taskId, runtime)
                    } catch (retryError: Exception) {
                        synchronized(lock) {
                            journal.read(taskId)?.takeUnless { it.task.state.isTerminal }?.let {
                                if (resourcesUnreleased()) waitForUser(it, SaveWaitReason.RECOVERY, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                                else if (runtime.pause && !runtime.userCancel && !runtime.original.cancelToken.isCancelled) waitForUser(it, SaveWaitReason.BACKGROUND, null)
                                else finishFailure(it, stopError(runtime.context) ?: SaveErrors.classify(retryError))
                            }
                        }
                        break
                    }
                }
            }
        } finally {
            try {
                synchronized(lock) {
                    runtime.executing = false
                    activeExecutions.remove(runtime)
                    journal.read(taskId)?.takeIf { it.task.state.isTerminal }?.let { runtimes.remove(taskId, runtime) }
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
        if (record.needsNetworkTransfer()) networkGate.check()
        var artifact = record.artifact
        if (artifact == null) {
            setPhase(taskId, runtime, SavePhase.PREPARING)
            artifact = mediaStore.prepare(taskId, record.commitId, runtime.context)
            if (artifact.commitId != record.commitId) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            synchronized(lock) {
                record = journal.read(taskId)!!
                if (runtimes[taskId] === runtime && !record.task.state.isTerminal) update(record) { it.copy(artifact = artifact) }
                else { try { mediaStore.cleanup(artifact, false) } catch (_: Exception) {} }
                checkActive(taskId, runtime)
            }
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
                executor.transfer(source, ownedArtifact,
                    record.checkpoint, runtime.context, limits, object : TransferProgress {
                        override fun onBytes(delta: Long) = synchronized(lock) {
                            checkActive(taskId, runtime)
                            if (delta <= 0) throw SaveFailure(SaveError.INVALID_REQUEST)
                            val current = journal.read(taskId)!!
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
                            update(journal.read(taskId)!!) { it.copy(checkpoint = null, transfer = null, verification = null) }; Unit
                        }
                        override fun onCheckpoint(checkpoint: DownloadCheckpoint) = synchronized(lock) {
                            checkActive(taskId, runtime)
                            val current = journal.read(taskId)!!
                            if (checkpoint.artifactId != ownedArtifact.artifactId || checkpoint.offset > limits.maxArtifactBytes ||
                                checkpoint.totalBytes > limits.maxArtifactBytes) throw SaveFailure(SaveError.INVALID_RANGE)
                            update(current) { it.copy(checkpoint = checkpoint) }; Unit
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
                if (result.responseMimeType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT) !in setOf("video/mp4", "application/mp4", "application/octet-stream"))
                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                update(journal.read(taskId)!!) { it.copy(transfer = result) }
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
        if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        setPhase(taskId, runtime, SavePhase.VERIFYING)
        val verificationContext = runtime.context.copy(deadlineEpochMillis = minOf(runtime.context.deadlineEpochMillis,
            saturatedAdd(clock.nowMillis(), limits.verificationTimeoutMillis)))
        val evidence = verifier.verify(ownedArtifact, completedTransfer, verificationContext)
        if (verificationContext.stopReason(clock.nowMillis()) != null) throw SaveFailure(stopError(verificationContext)!!)
        val verified = synchronized(lock) {
            checkActive(taskId, runtime)
            if (resourcesUnreleased()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            record = journal.read(taskId)!!
            if (evidence.encrypted) throw SaveFailure(SaveError.PROTECTED_MEDIA)
            if (!evidence.isValidFor(ownedArtifact, completedTransfer, record.request.networkResource?.expectedAudio ?: false))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val detachedEvidence = evidence.copy(decodedPositions = evidence.decodedPositions.toSet())
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
        try { mediaStore.cleanup(ownedArtifact, true) } catch (_: Exception) { /* Durable success remains success. */ }
    }

    /** Immediate memory-only lifecycle fence; never depends on a control queue or journal IO. */
    fun signalBackgroundPauseAll() {
        runtimes.values.filter { it.method != TaskMethod.RECORD }.forEach { runtime -> runtime.pause = true; runtime.cancel.cancel() }
    }
    /** Immediate user cancellation signal, safe before an asynchronously queued durable command. */
    fun signalUserCancel(taskId: String) {
        runtimes[taskId]?.let { runtime ->
            runtime.userCancel = true; runtime.cancel.cancel()
            if (runtime.method == TaskMethod.RECORD) recording.cancel(taskId)
        }
    }

    /** Tab navigation must not call this; use only the app's real foreground/background boundary. */
    fun pauseForBackground(taskId: String): SaveRecord? {
        val runtime = runtimes[taskId]
        if (runtime?.method == TaskMethod.RECORD) return runtime.lastRecord
        if (runtime?.executing == true) {
            runtime.pause = true; runtime.cancel.cancel(); return runtime.lastRecord
        }
        return synchronized(lock) {
            val currentRuntime = runtimes[taskId]
            if (currentRuntime?.method == TaskMethod.RECORD) return@synchronized currentRuntime.lastRecord
            if (currentRuntime?.executing == true) {
                currentRuntime.pause = true; currentRuntime.cancel.cancel(); return@synchronized currentRuntime.lastRecord
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
        runtime?.cancel?.cancel()
        if (runtime?.method == TaskMethod.RECORD) recording.cancel(taskId)
        if (runtime?.executing == true) return runtime.lastRecord?.task
        return synchronized(lock) {
            val currentRuntime = runtimes[taskId]
            currentRuntime?.userCancel = true; currentRuntime?.cancel?.cancel()
            if (currentRuntime?.executing == true) return@synchronized currentRuntime.lastRecord?.task
            val record = journal.read(taskId) ?: return@synchronized null
            if (record.task.state.isTerminal || resourcesUnreleased()) return@synchronized record.task
            finishFailure(record, SaveError.CANCELLED).task
        }
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
    fun expireWaitingUsers(context: OperationContext): List<SaveRecord> = synchronized(lock) {
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

    /** Bounded recovery never starts network work and never deletes unknown or committed files. */
    fun recover(context: OperationContext): List<SaveRecord> = synchronized(lock) {
        val recovered = mutableListOf<SaveRecord>()
        for (original in journal.listUnfinished(limits.maxRecoveryEntries)) {
            if (context.stopReason(clock.nowMillis()) != null) break
            val liveRuntime = runtimes[original.task.taskId]
            if (liveRuntime?.executing == true || (liveRuntime?.claim?.released == false &&
                    original.task.state in setOf(TaskState.QUEUED, TaskState.RUNNING))) continue
            var record = original
            if (record.task.state.isTerminal) continue
            if (record.schemaVersion != 1) {
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

    private fun checkActive(taskId: String, runtime: Runtime) = synchronized(lock) {
        if (runtimes[taskId] !== runtime || !runtime.executing || !activeExecutions.contains(runtime)) throw SaveFailure(SaveError.CANCELLED)
        stopError(runtime.context)?.let { throw SaveFailure(it) }
        val state = journal.read(taskId)?.task?.state
        if (state != TaskState.RUNNING) throw SaveFailure(SaveError.CANCELLED)
        Unit
    }
    private fun setPhase(taskId: String, runtime: Runtime, phase: SavePhase) = synchronized(lock) {
        checkActive(taskId, runtime); update(journal.read(taskId)!!) { it.copy(phase = phase, error = null) }; Unit
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
        if (record.phase != SavePhase.COMMITTING && !resourcesUnreleased()) record.artifact?.let { try { mediaStore.cleanup(it, false) } catch (_: Exception) {} }
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
        runtimes[record.task.taskId]?.lastRecord = record
        observers[record.task.taskId]?.toList()?.forEach { try { it.onSnapshot(record.task) } catch (_: Exception) {} }
    }
}

private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Compare an idempotent capture against the original request identity without manufacturing a URL. */
private fun SaveRequest.withTaskId(taskId: String): SaveRequest = when (val source = input) {
    is SaveInput.Network -> copy(requestId = taskId)
    is SaveInput.Capture -> copy(requestId = taskId, input = source.copy(descriptor = source.descriptor.copy(sessionId = taskId)))
}
