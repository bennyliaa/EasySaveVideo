package com.easysavevideo.app.vault

import com.easysavevideo.core.domain.VaultSessionReducer
import com.easysavevideo.core.model.*
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import com.easysavevideo.library.export.ExportRelease

/** The owner, not UI, acknowledges this exact covered presentation after actual composition/retirement. */
internal interface VaultPresentationPort {
    fun cover(generation: Long, onActuallyHidden: () -> Unit)
    fun presentMedia(view: VaultMediaView) = Unit
    fun presentPlayback(view: VaultPlaybackView) = Unit
    fun presentCatalog(revision: Long, items: List<VaultMediaRow>) = Unit
    fun requestPhoneFileSelection(request: VaultPhoneFileRequest) = Unit
    fun presentEmpty(catalogRevision: Long)
    fun presentStatus(status: VaultRuntimeStatus)
    fun presentFailure(reason: VaultFailureReason) = presentStatus(VaultRuntimeStatus.UNAVAILABLE)
    fun presentBiometricState(state: VaultBiometricState, notice: VaultBiometricNotice, availability: VaultBiometricAvailability?) = Unit
    fun presentRecoveryState(state: VaultRecoveryState, pendingPassword: Boolean, notice: VaultRecoveryNotice) = Unit
}
internal enum class VaultRuntimeStatus { LOADING, SETUP_REQUIRED, LOCKED, WORKING, READY_TO_REVEAL, EMPTY, CATALOG, RECOVERY_CODE_READY, RECOVERY_REQUIRED, UNAVAILABLE, DISABLED }
internal fun interface VaultWakeCancellation { fun cancel() }
internal fun interface VaultWakeScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation
}

/**
 * Sole effect executor. The gate contains only bounded state/identity work: never KDF, SQL or prompt.
 * Worker tasks remain registered until their finally block actually returns, even after timeout.
 */
internal class VaultRuntime(
    private val store: VaultStorePort,
    private val authentication: VaultAuthenticationPort,
    private val clock: VaultMonotonicClock,
    private val work: Executor,
    private val ui: Executor,
    private val scheduler: VaultWakeScheduler,
    private val presentation: VaultPresentationPort,
    private val processInstance: Long,
    mediaSource: VaultMediaSourcePort? = null,
    phoneFiles: VaultPhoneFilesPort? = null,
    playbackFactory: VaultPlaybackPlatformFactory? = null,
    playbackHost: VaultPlaybackHostPort? = null,
    thumbnailFactory: VaultThumbnailPlatformFactory? = null,
) : AutoCloseable {
    private val gate = Any()
    private val reducer = VaultSessionReducer(VaultRuntimeLimits.retryPolicy())
    private var state: VaultState? = null
    private var configuredAutoLockPolicy = VaultAutoLockPolicy.IMMEDIATE
    private var autoLockPolicyInitialized = false
    private var snapshot: VaultStoredSnapshot? = null
    private var trustedAbsent = false
    private var closed = false
    private var started = false
    private var jobs = 0
    private var effectsInFlight = 0
    private var confirmedReservation: VaultRetryWrite? = null
    private val contexts = mutableSetOf<VaultOperationContext>()
    private var credential: VaultCredentialInput? = null
    private var pendingRoot: Pair<VaultAttemptIdentity, VaultRootCapability>? = null
    private val retiringRoots = IdentityHashMap<VaultRootCapability, Unit>()
    private var activeRoot: VaultRootCapability? = null
    private var activeRootEpoch: Long? = null
    private var presentationGeneration = 0L
    private var hiddenGeneration = -1L
    private var readLease: ReadLease? = null
    private var revealedPlaybackRefs: List<VaultMediaReadRef> = emptyList()
    private var wake: VaultWakeCancellation? = null
    private var wakeGeneration = 0L
    private var failureSticky = false
    private var failureReason: VaultFailureReason? = null
    private var setupRunning = false
    private var route = false
    private var foreground = false
    private enum class RecoveryPhase { PASSWORD_PROMPT, AUTHENTICATING, PREPARING, DISPLAY_READY, DISPLAY, CONFIRM_PROMPT, CONFIRMING, RESET_PROMPT, RESETTING }
    private var recoveryWorkflow: VaultRecoveryWorkflow? = null
    private var recoveryPhase: RecoveryPhase? = null
    private var recoveryWake: VaultWakeCancellation? = null
    private var recoveryDisplay: VaultRecoveryInput? = null
    private var recoveryDisplayDeadline = 0L
    private var recoveryPendingSnapshot: VaultStoredSnapshot? = null
    private var recoveryInput: VaultRecoveryInput? = null
    private var recoveryNotice = VaultRecoveryNotice.NONE
    private enum class BiometricPhase { PASSWORD_PROMPT, AUTHENTICATING, PREPARING, WRAPPING, VERIFYING, REVOKING }
    private var biometricWorkflow: VaultBiometricWorkflow? = null
    private var biometricPhase: BiometricPhase? = null
    private var biometricWake: VaultWakeCancellation? = null
    private var biometricBindingFactory: VaultBiometricBindingFactory? = null
    private var biometricAttempt: VaultAttemptIdentity? = null
    private var biometricUnlockBinding: VaultBiometricBinding? = null
    private var biometricNotice = VaultBiometricNotice.NONE
    private var biometricAvailability: VaultBiometricAvailability? = null
    private val biometricOperations = IdentityHashMap<VaultBiometricOperation, BiometricPending>()
    private class BiometricPending(val operation: VaultOperationContext, val binding: VaultBiometricBinding,
        val before: VaultStoredSnapshot, val consume: (VaultBiometricResult) -> Unit) {
        var startedReturned = false
        var result: VaultBiometricResult? = null
        var resultReceived = false
        var continuationQueued = false
        var retainedEnrollment = false
        var deadlineWake: VaultWakeCancellation? = null
    }
    private val retainedBiometricPermissions = IdentityHashMap<VaultBiometricAliasRetirement, Unit>()
    private data class PendingReset(val attempt: VaultAttemptIdentity, val mutation: VaultCredentialMutation,
        val operation: VaultOperationContext)
    private var pendingReset: PendingReset? = null
    private val retiringMutations = IdentityHashMap<VaultCredentialMutation, Unit>()
    private class ReadLease(val generation: Long, val authEpoch: Long, val presentationEpoch: Long,
        val revision: Long, val root: VaultRootCapability)

    private val media: VaultMediaRuntime? = if (mediaSource != null && phoneFiles != null) VaultMediaRuntime(gate, authentication,
        store, mediaSource, phoneFiles, clock, ::mediaSession,
        { operation, failed, task -> submit(operation, failed, task) },
        { before, after -> synchronized(gate) {
            if (snapshot != before) throw VaultOperationException(VaultFailure.CONFLICT)
            snapshot = after
        } }, ::lock,
        { view -> ui.execute { presentation.presentMedia(view) } },
        ::dispatchMediaPicker, ::tryAcknowledgeCleanup) else null
    private val playback: VaultPlaybackRuntime? = if (playbackFactory != null && playbackHost != null)
        VaultPlaybackRuntime(gate, authentication, store, clock, work, ui, scheduler,
            playbackFactory, playbackHost, ::playbackSession,
            { view -> presentation.presentPlayback(view) }, ::tryAcknowledgeCleanup, thumbnailFactory) else null

    /** Called by the playback leaf while holding this same gate, before its reservation escapes. */
    private fun playbackSession(ref: VaultMediaReadRef, host: VaultPlaybackHostToken): VaultPlaybackSession? = synchronized(gate) {
        val current = state ?: return null
        val durable = snapshot ?: return null
        val lease = readLease ?: return null
        val root = activeRoot ?: return null
        if (closed || failureSticky || !route || !foreground || !host.isCurrent() || jobs != 0 || effectsInFlight != 0 ||
            recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() || pendingReset != null || credential != null || setupRunning ||
            media?.pending() == true || media?.active() == true || media?.cleanupComplete() == false ||
            retiringRoots.isNotEmpty() || retiringMutations.isNotEmpty() ||
            authentication.observeMediaDrain() != com.easysavevideo.library.export.ExportRelease.RELEASED ||
            store.observeMediaDrain() != com.easysavevideo.library.export.ExportRelease.RELEASED ||
            root !== lease.root || activeRootEpoch != lease.authEpoch || presentationGeneration != lease.generation ||
            ref !in revealedPlaybackRefs || ref.vaultId != durable.identity.vaultId || ref.catalogRevision != lease.revision ||
            !current.permitsPresentation(tick(), lease.authEpoch, lease.presentationEpoch)) return null
        VaultPlaybackSession(root, durable, ref) { synchronized(gate) {
            !closed && !failureSticky && route && foreground && host.isCurrent() &&
                readLease === lease && activeRoot === root && activeRootEpoch == lease.authEpoch &&
                snapshot == durable && presentationGeneration == lease.generation &&
                recoveryWorkflow == null && biometricWorkflow == null && biometricOperations.isEmpty() && pendingReset == null &&
                state?.permitsPresentation(tick(), lease.authEpoch, lease.presentationEpoch) == true
        } }.also { it.revealIdentity = lease }
    }
    fun openPlayback(ref: VaultMediaReadRef, host: VaultPlaybackHostToken): Boolean = playback?.open(ref, host) == true
    fun openThumbnail(ref: VaultMediaReadRef, host: VaultThumbnailHostToken,
        completed: VaultThumbnailCompletion): VaultThumbnailTicket? = playback?.openThumbnail(ref, host, completed)
    fun closeThumbnail(ticket: VaultThumbnailTicket) { playback?.closeThumbnail(ticket) }
    fun revokeThumbnails(host: VaultThumbnailHostToken? = null) { playback?.revokeThumbnails(host) }
    fun thumbnailHostCovered(host: VaultThumbnailHostToken) { playback?.thumbnailHostCovered(host) }
    fun thumbnailCleanupComplete(): Boolean = playback?.thumbnailCleanupComplete() != false
    fun thumbnailWorkComplete(): Boolean = playback?.thumbnailWorkComplete() != false
    fun thumbnailHostProgressed() { playback?.hostProgressed(); tryAcknowledgeCleanup() }
    fun playPlayback(ticket: VaultPlaybackTicket) { playback?.play(ticket) }
    fun pausePlayback(ticket: VaultPlaybackTicket) { playback?.pause(ticket) }
    fun seekPlayback(ticket: VaultPlaybackTicket, targetMillis: Long) { playback?.seek(ticket, targetMillis) }
    fun closePlayback(ticket: VaultPlaybackTicket) { playback?.close(ticket) }
    /** Output revocation is independent from the existing account auto-lock reducer. */
    fun revokePlayback() { playback?.revokeAll() }
    fun playbackHostProgressed() { playback?.hostProgressed(); tryAcknowledgeCleanup() }
    fun playbackCleanupComplete(): Boolean = playback?.playbackCleanupComplete() != false

    private fun dispatchMediaPicker(request: VaultPhoneFileRequest) = ui.execute {
        if (media?.admitPicker(request) == true) presentation.requestPhoneFileSelection(request)
        else media?.retireUndispatchedPicker(request)
    }

    private fun mediaSession(): VaultMediaSession? = synchronized(gate) {
        val current = state ?: return null
        val durable = snapshot ?: return null
        val root = activeRoot ?: return null
        if (closed || failureSticky || playback?.occupied() == true || recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() || pendingReset != null || jobs != 0 ||
            !route || !foreground || activeRootEpoch != current.authEpoch ||
            !current.permitsPresentation(tick(), current.authEpoch, current.presentationEpoch)) return null
        val generation = presentationGeneration
        val auth = current.authEpoch; val epoch = current.presentationEpoch
        VaultMediaSession(root, durable) { synchronized(gate) {
            !closed && !failureSticky && playback?.occupied() != true && route && foreground && recoveryWorkflow == null && biometricWorkflow == null && biometricOperations.isEmpty() && pendingReset == null &&
                activeRoot === root && activeRootEpoch == auth && presentationGeneration == generation &&
                state?.permitsPresentation(tick(), auth, epoch) == true
        } }
    }
    fun queueMoveToVault(ref: com.easysavevideo.library.MediaReadRef): Boolean = synchronized(gate) {
        playback?.occupied() != true && media?.queueMove(ref) == true
    }
    fun confirmMoveToVault() { synchronized(gate) { if (playback?.occupied() != true) media?.confirmMove() } }
    fun copyMediaToPhone(ref: VaultMediaReadRef) { synchronized(gate) { if (playback?.occupied() != true) media?.beginCopy(ref) } }
    fun retirePhoneSelection(request: VaultPhoneFileRequest): Boolean = media?.retireUndispatchedPicker(request) == true
    fun acceptPhoneFile(request: VaultPhoneFileRequest, target: VaultPhoneFileTarget?) { media?.acceptTarget(request, target) }
    fun confirmCopyToPhone() { synchronized(gate) { if (playback?.occupied() != true) media?.confirmCopy() } }
    fun copyMediaBatchToPhone(refs: List<VaultMediaReadRef>) { synchronized(gate) { if (playback?.occupied() != true) media?.beginBatchCopy(refs) } }
    fun acceptPhoneSelection(request: VaultPhoneFileRequest, selection: VaultPhoneSelection?) { media?.acceptSelection(request, selection) }
    fun confirmBatchCopy(token: Any? = null) { synchronized(gate) { if (playback?.occupied() != true) media?.confirmBatchCopy(token) } }
    fun continueBatchCopy(token: Any? = null) { synchronized(gate) { if (playback?.occupied() != true) media?.continueBatchCopy(token) } }
    fun keepCurrentBatchOutputAndContinue(token: Any? = null) { synchronized(gate) { if (playback?.occupied() != true) media?.keepCurrentBatchOutputAndContinue(token) } }
    fun keepCurrentSingleOutput(token: Any? = null) { synchronized(gate) { if (playback?.occupied() != true) media?.keepCurrentSingleOutput(token) } }
    fun reconcileMedia() { synchronized(gate) { if (playback?.occupied() != true) media?.recover() } }
    fun cancelMedia() { media?.cancel() }
    init { require(processInstance > 0) }
    private fun tick() = VaultTick(processInstance, clock.elapsedMillis())
    private fun context(budget: Long, deadline: Long? = null): VaultOperationContext {
        val now = clock.elapsedMillis()
        if (now < 0 || now > Long.MAX_VALUE - budget) throw VaultOperationException(VaultFailure.DEADLINE)
        return VaultOperationContext(minOf(now + budget, deadline ?: Long.MAX_VALUE), clock)
    }

    fun start() {
        synchronized(gate) { if (started || closed) return; started = true }
        protectPresentation()
        val operation = context(VaultRuntimeLimits.DATABASE_MILLIS)
        submit(operation) {
            val observed = store.inspect(operation)
            val loaded = when (observed) {
                VaultStoreObservation.Absent -> {
                    if (authentication.hasExistingVaultAliases(operation)) throw VaultOperationException(VaultFailure.INCOMPLETE)
                    synchronized(gate) { trustedAbsent = true }
                    VaultLedgerLoad.TrustedFirstUse
                }
                is VaultStoreObservation.Present -> {
                    synchronized(gate) { snapshot = observed.snapshot }
                    if (observed.snapshot.phase != VaultStorePhase.READY) throw VaultOperationException(VaultFailure.INCOMPLETE)
                    VaultLedgerLoad.Restored(authentication.authenticateLedger(observed.snapshot, operation))
                }
                is VaultStoreObservation.Unavailable -> throw VaultOperationException(when (observed.category) {
                    VaultFailure.CORRUPT, VaultFailure.UNAVAILABLE -> VaultFailure.STORE_UNREADABLE
                    else -> observed.category
                })
            }
            operation.checkActive()
            val transition = synchronized(gate) {
                if (closed) return@submit
                val initial = reducer.start(processInstance, tick(), loaded)
                val configured = reducer.reduce(initial.state, VaultEvent.PolicyChanged(configuredAutoLockPolicy), tick())
                VaultTransition(configured.state, initial.effects + configured.effects, configured.outcome)
                    .also { state = it.state; effectsInFlight++ }
            }
            executeEffects(transition.effects)
            transition(VaultEvent.LifecycleObserved(route, foreground))
            showStatus()
        }
    }

    /** Explicit recovery choice is retained while the primary password is established independently. */
    fun setupPassword(input: VaultCredentialInput, recoveryChoice: VaultRecoveryChoice) {
        try { authentication.validatePasswordInput(input) }
        catch (_: Exception) { input.close(); return }
        val allowed = synchronized(gate) {
            val permitted = !closed && !failureSticky && playback?.occupied() != true && trustedAbsent && !setupRunning && jobs == 0 &&
                state?.cleanupStatus == VaultCleanupStatus.COMPLETE && route && foreground &&
                recoveryChoice in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN)
            if (permitted) { setupRunning = true; trustedAbsent = false }
            permitted
        }
        if (!allowed) { input.close(); return }
        transition(VaultEvent.RecoveryChoiceRecorded(recoveryChoice))
        val operation = context(VaultRuntimeLimits.AUTHENTICATION_MILLIS)
        submit(operation, onFailure = { input.close(); synchronized(gate) { setupRunning = false }; failure(VaultFailure.INCOMPLETE) }) {
            try {
                // Identifiers are non-secret metadata. No key is created by this runtime constructor.
                val id = UUID.randomUUID().toString()
                val identity = VaultPersistentIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), id,
                    "esv.vault.v1.$id.device", "esv.vault.v1.$id.biometric")
                val initializing = store.beginInitialization(identity, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS))
                val prepared = authentication.preparePasswordSetup(identity, input, operation.bounded(VaultRuntimeLimits.KDF_MILLIS))
                store.stage(initializing, prepared, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS))
                val reread = store.inspect(operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) as? VaultStoreObservation.Present
                    ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
                val proof = authentication.verifyStaged(reread.snapshot, input, operation.bounded(VaultRuntimeLimits.KDF_MILLIS))
                val ready = try {
                    authentication.validateStagedVerification(reread.snapshot, proof)
                    store.markReady(reread.snapshot, proof, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS))
                } finally { proof.close() }
                operation.checkActive()
                synchronized(gate) { snapshot = ready; recoveryNotice = if (recoveryChoice == VaultRecoveryChoice.OPTED_IN)
                    VaultRecoveryNotice.PASSWORD_CREATED else VaultRecoveryNotice.NONE }
                // Setup never hands its root to a session. A normal reserved unlock follows separately.
                transition(VaultEvent.ExplicitLock)
            } finally { input.close(); synchronized(gate) { setupRunning = false } }
        }
        showStatus()
    }

    fun unlockPassword(input: VaultCredentialInput) = requestPasswordUnlock(input, null, null)

    private fun requestPasswordUnlock(input: VaultCredentialInput, workflow: VaultRecoveryWorkflow?, biometric: VaultBiometricWorkflow?) {
        val accepted = synchronized(gate) {
            val current = state
            if (closed || failureSticky || playback?.occupied() == true || credential != null || jobs != 0 || snapshot?.phase != VaultStorePhase.READY ||
                biometricOperations.isNotEmpty() || authentication.observeBiometricDrain() != ExportRelease.RELEASED ||
                (biometric == null && biometricWorkflow != null || biometric != null &&
                    (!biometricCurrentLocked(biometric) || biometricPhase != BiometricPhase.PASSWORD_PROMPT)) ||
                (workflow == null && recoveryWorkflow != null || workflow != null &&
                    (!workflowCurrentLocked(workflow) || recoveryPhase != RecoveryPhase.PASSWORD_PROMPT)) ||
                current?.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE ||
                current.retryStatus != VaultRetryStatus.READY || !route || !foreground) false
            else { credential = input
                if (workflow != null) recoveryPhase = RecoveryPhase.AUTHENTICATING
                if (biometric != null) biometricPhase = BiometricPhase.AUTHENTICATING
                true }
        }
        if (!accepted) { input.close(); return }
        transition(VaultEvent.UnlockRequested)
    }

    private fun biometricCurrentLocked(value: VaultBiometricWorkflow): Boolean = !closed && !failureSticky &&
        biometricWorkflow === value && route && foreground && clock.elapsedMillis().let { it >= 0 && it < value.deadlineElapsedMillis }
    fun biometricWorkflowCurrent(value: VaultBiometricWorkflow): Boolean = synchronized(gate) { biometricCurrentLocked(value) }
    fun beginBiometricWorkflow(kind: VaultBiometricWorkflowKind): VaultBiometricWorkflow? {
        val value = synchronized(gate) {
            val current = state ?: return null
            val data = snapshot?.data ?: return null
            if (closed || failureSticky || !route || !foreground || biometricWorkflow != null || recoveryWorkflow != null ||
                biometricOperations.isNotEmpty() || authentication.observeBiometricDrain() != ExportRelease.RELEASED ||
                retainedBiometricPermissions.isNotEmpty() || playback?.occupied() == true || media?.blocksCredentialRecovery() == true ||
                jobs != 0 || effectsInFlight != 0 || credential != null || data.pendingPassword != null ||
                current.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE || current.retryStatus != VaultRetryStatus.READY ||
                (kind == VaultBiometricWorkflowKind.ENROLL && data.biometricState !in setOf(VaultBiometricState.NONE, VaultBiometricState.REVOKED)) ||
                (kind == VaultBiometricWorkflowKind.REVOKE && data.biometricState !in setOf(VaultBiometricState.PENDING, VaultBiometricState.ACTIVE))) return null
            val operation = try { context(VaultRuntimeLimits.RECOVERY_WORKFLOW_MILLIS) } catch (_: Exception) { return null }
            VaultBiometricWorkflow(kind, operation).also {
                biometricWorkflow = it; biometricPhase = BiometricPhase.PASSWORD_PROMPT; biometricNotice = VaultBiometricNotice.NONE
            }
        }
        try {
            val timer = scheduler.schedule((value.deadlineElapsedMillis - clock.elapsedMillis()).coerceAtLeast(0)) {
                cancelBiometricWorkflow(value)
            }
            synchronized(gate) { if (biometricWorkflow === value) biometricWake = timer else timer.cancel() }
        } catch (_: Exception) { cancelBiometricWorkflow(value); return null }
        return value
    }
    fun coverForBiometricHandoff(value: VaultBiometricWorkflow): Boolean {
        if (!synchronized(gate) { biometricCurrentLocked(value) && jobs == 0 && biometricPhase == BiometricPhase.PASSWORD_PROMPT }) return false
        transition(VaultEvent.SurfaceCovered, preserveBiometric = value, expectedBiometric = value)
        return biometricWorkflowCurrent(value)
    }
    fun unlockForBiometric(input: VaultCredentialInput, value: VaultBiometricWorkflow, binding: VaultBiometricBindingFactory) {
        val valid = synchronized(gate) {
            if (!biometricCurrentLocked(value) || biometricPhase != BiometricPhase.PASSWORD_PROMPT) false
            else { biometricBindingFactory = binding; true }
        }
        if (!valid) { input.close(); return }
        requestPasswordUnlock(input, null, value)
    }
    fun cancelBiometricWorkflow(value: VaultBiometricWorkflow) {
        transition(VaultEvent.ExplicitLock, expectedBiometric = value)
    }
    private fun clearBiometricWorkflowLocked() {
        biometricWake?.cancel(); biometricWake = null
        biometricWorkflow?.context?.cancel()
        biometricWorkflow = null; biometricPhase = null; biometricBindingFactory = null; biometricAttempt = null
    }
    private fun requireBiometricCurrent(value: VaultBiometricWorkflow, operation: VaultOperationContext) {
        synchronized(gate) {
            operation.checkActive()
            if (!biometricCurrentLocked(value) || !value.context.sharesOperation(operation))
                throw VaultOperationException(VaultFailure.CANCELLED)
        }
    }
    private fun bindBiometric(host: VaultBiometricBinding, before: VaultStoredSnapshot,
        workflow: VaultBiometricWorkflow?, attempt: VaultAttemptIdentity?): VaultBiometricBinding = VaultBiometricBinding(
        host.purpose, host.hostIdentity, host.platformHost, host.hostGeneration, host.workflowIdentity) {
        try {
            host.requireCurrent()
            synchronized(gate) {
                !closed && !failureSticky && route && foreground && snapshot == before &&
                    (workflow == null || biometricCurrentLocked(workflow)) &&
                    (attempt == null || state?.activeAttempt?.identity == attempt && state?.mode == VaultMode.AUTHENTICATING)
            }
        } catch (_: Exception) { false }
    }
    /** Explicit protected-host choice only. This goes through the same reservation and RESET reducer. */
    fun unlockBiometric(host: VaultBiometricBinding) {
        val admitted = synchronized(gate) {
            val current = state
            val data = snapshot?.data
            try { host.requireCurrent() } catch (_: Exception) { return }
            if (host.purpose != VaultBiometricPurpose.UNLOCK || host.workflowIdentity != null || closed || failureSticky ||
                !route || !foreground || jobs != 0 || effectsInFlight != 0 || credential != null || biometricUnlockBinding != null ||
                recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() ||
                authentication.observeBiometricDrain() != ExportRelease.RELEASED || playback?.occupied() == true ||
                media?.blocksCredentialRecovery() == true || data?.biometricState != VaultBiometricState.ACTIVE ||
                current?.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE || current.retryStatus != VaultRetryStatus.READY) false
            else { biometricUnlockBinding = host; biometricNotice = VaultBiometricNotice.NONE; true }
        }
        if (admitted) transition(VaultEvent.UnlockRequested)
    }
    private fun authenticateBiometric(attempt: VaultAttempt) {
        val captured = synchronized(gate) {
            val host = biometricUnlockBinding ?: return
            val before = snapshot ?: return
            val reservation = confirmedReservation ?: return
            if (attempt.purpose != VaultAttemptPurpose.UNLOCK || reservation.identity.attempt != attempt.identity ||
                reservation.proposedLedger != before.data?.retryLedger || state?.activeAttempt?.identity != attempt.identity ||
                state?.mode != VaultMode.AUTHENTICATING) return
            // Keep this exact intent as the cover hold until registered pending atomically replaces it.
            before to bindBiometric(host, before, null, attempt.identity)
        }
        val operation = try { context(VaultRuntimeLimits.PROMPT_MILLIS, attempt.deadline.elapsedMillis) }
            catch (_: Exception) { transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return }
        submit(operation, onFailure = { category -> biometricAttemptFailed(attempt.identity, category) }) {
            captured.second.requireCurrent()
            val actual = inspectExact(captured.first, operation)
            val pending = authentication.prepareBiometricUnlock(actual, attempt.identity, captured.second, operation)
            beginBiometricOperation(pending, captured.second, actual) { result ->
                when (result) {
                    is VaultBiometricResult.Unlocked -> {
                        var accepted = false
                        try {
                            pending.context.checkActive(); captured.second.requireCurrent(); inspectExact(actual, pending.context)
                            accepted = synchronized(gate) {
                                if (closed || failureSticky || state?.activeAttempt?.identity != attempt.identity ||
                                    state?.mode != VaultMode.AUTHENTICATING || snapshot != actual || !route || !foreground) false
                                else { pendingRoot = attempt.identity to result.root; true }
                            }
                            if (!accepted) throw VaultOperationException(VaultFailure.CANCELLED)
                            transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
                        } finally { if (!accepted) releaseRoot(result.root) }
                    }
                    is VaultBiometricResult.Failed -> {
                        synchronized(gate) { biometricAvailability = result.availability; biometricNotice = VaultBiometricNotice.UNAVAILABLE }
                        biometricAttemptFailed(attempt.identity, result.failure)
                    }
                    is VaultBiometricResult.Mutation -> { releaseMutation(result.mutation); biometricAttemptFailed(attempt.identity, VaultFailure.REJECTED) }
                }
            }
        }
    }
    private fun biometricAttemptFailed(attempt: VaultAttemptIdentity, category: VaultFailure) {
        if (category.confirmedPrimaryFailure()) failure(category)
        else transition(VaultEvent.AuthenticationResult(attempt, if (category == VaultFailure.REJECTED)
            VaultAuthenticationOutcome.REJECTED else VaultAuthenticationOutcome.CANCELLED))
    }
    private fun inspectExact(before: VaultStoredSnapshot, operation: VaultOperationContext): VaultStoredSnapshot {
        val actual = (store.inspect(operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) as? VaultStoreObservation.Present)?.snapshot
            ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (actual != before || synchronized(gate) { snapshot != before }) throw VaultOperationException(VaultFailure.CONFLICT)
        operation.checkActive()
        return actual
    }
    private fun prepareBiometricWorkflow(value: VaultBiometricWorkflow) {
        data class Captured(val root: VaultRootCapability, val before: VaultStoredSnapshot, val attempt: VaultAttemptIdentity,
            val factory: VaultBiometricBindingFactory)
        val captured = synchronized(gate) {
            if (!biometricCurrentLocked(value) || biometricPhase != BiometricPhase.AUTHENTICATING) return
            Captured(activeRoot ?: return, snapshot ?: return, biometricAttempt ?: return, biometricBindingFactory ?: return).also {
                biometricPhase = BiometricPhase.PREPARING
            }
        }
        submit(value.context, onFailure = { category ->
            if (category.confirmedPrimaryFailure()) failure(category) else {
                synchronized(gate) { biometricNotice = VaultBiometricNotice.UNFINISHED }
                cancelBiometricWorkflow(value)
            }
        }) {
            requireBiometricCurrent(value, value.context)
            inspectExact(captured.before, value.context)
            if (value.kind == VaultBiometricWorkflowKind.REVOKE) {
                synchronized(gate) { biometricPhase = BiometricPhase.REVOKING }
                val binding = bindBiometric(captured.factory(VaultBiometricPurpose.RETIRE_ALIAS, value), captured.before, value, null)
                binding.requireCurrent()
                val mutation = authentication.prepareBiometricRevocation(captured.root, captured.before, captured.attempt, binding, value.context)
                val committed = try { commitBiometricMutation(captured.before, mutation, value.context) } finally { mutation.close() }
                requireBiometricCurrent(value, value.context)
                synchronized(gate) { snapshot = committed; biometricNotice = VaultBiometricNotice.REVOKED }
                // The durable block is sufficient. A later explicit enrollment with fresh password retires the exact alias.
                transition(VaultEvent.ExplicitLock, expectedBiometric = value)
                return@submit
            }
            val retirementBinding = bindBiometric(captured.factory(VaultBiometricPurpose.RETIRE_ALIAS, value), captured.before, value, null)
            retirementBinding.requireCurrent()
            val permission = authentication.prepareBiometricAliasRetirement(captured.root, captured.before, captured.attempt,
                retirementBinding, value.context)
            synchronized(gate) { retainedBiometricPermissions[permission] = Unit }
            // No media, credential or alias writer can be admitted while this exact workflow holds the runtime gate.
            val exact = inspectExact(captured.before, value.context)
            val retired = authentication.retireBiometricAlias(exact, permission, value.context)
            if (retired != VaultBiometricAliasRetirementResult.ABSENT) {
                synchronized(gate) { biometricNotice = VaultBiometricNotice.RETIREMENT_PENDING }
                cancelBiometricWorkflow(value)
                return@submit // Retain this original permission; no blind new intent or create retry.
            }
            permission.close(); synchronized(gate) { retainedBiometricPermissions.remove(permission) }
            requireBiometricCurrent(value, value.context)
            val binding = bindBiometric(captured.factory(VaultBiometricPurpose.ENROLL_WRAP, value), captured.before, value, null)
            val pending = authentication.prepareBiometricEnrollment(captured.root, captured.before, captured.attempt, binding, value.context)
            synchronized(gate) { biometricPhase = BiometricPhase.WRAPPING }
            beginBiometricOperation(pending, binding, captured.before) { result ->
                when (result) {
                    is VaultBiometricResult.Mutation -> {
                        val staged = try {
                            requireBiometricCurrent(value, pending.context)
                            commitBiometricMutation(captured.before, result.mutation, pending.context)
                        } finally { result.mutation.close() }
                        // Catalog reseal retires the old root; it is never rebound to the new revision.
                        retireRoots()
                        requireBiometricCurrent(value, value.context)
                        val verifyBinding = bindBiometric(captured.factory(VaultBiometricPurpose.ENROLL_VERIFY, value), staged, value, null)
                        val verify = authentication.prepareBiometricActivation(staged, pending, verifyBinding, value.context)
                        synchronized(gate) { biometricPhase = BiometricPhase.VERIFYING }
                        beginBiometricOperation(verify, verifyBinding, staged) { verified ->
                            when (verified) {
                                is VaultBiometricResult.Mutation -> {
                                    try {
                                        requireBiometricCurrent(value, verify.context)
                                        commitBiometricMutation(staged, verified.mutation, verify.context)
                                        requireBiometricCurrent(value, value.context)
                                        synchronized(gate) { biometricNotice = VaultBiometricNotice.ENABLED; biometricAvailability = VaultBiometricAvailability.AVAILABLE }
                                        transition(VaultEvent.ExplicitLock, expectedBiometric = value)
                                    } finally { verified.mutation.close() }
                                }
                                else -> failBiometricWorkflowResult(value, verified)
                            }
                        }
                    }
                    else -> failBiometricWorkflowResult(value, result)
                }
            }
        }
    }
    private fun commitBiometricMutation(before: VaultStoredSnapshot, mutation: VaultCredentialMutation,
        operation: VaultOperationContext): VaultStoredSnapshot {
        inspectExact(before, operation)
        val stored = try { store.compareAndSwapCredentials(before, mutation, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) }
        catch (error: Exception) {
            // Unknown credential publication is store-owner evidence, never a retry with a new proposal.
            val category = (error as? VaultOperationException)?.category ?: VaultFailure.UNAVAILABLE
            if (category !in setOf(VaultFailure.CANCELLED, VaultFailure.DEADLINE)) failure(category)
            throw error
        }
        synchronized(gate) { snapshot = stored } // Durable evidence survives cancellation; no alternate proposal retry.
        return inspectExact(stored, operation)
    }
    private fun failBiometricWorkflowResult(value: VaultBiometricWorkflow, result: VaultBiometricResult) {
        if (result is VaultBiometricResult.Unlocked) releaseRoot(result.root)
        if (result is VaultBiometricResult.Mutation) releaseMutation(result.mutation)
        if (result is VaultBiometricResult.Failed && result.failure.confirmedPrimaryFailure()) { failure(result.failure); return }
        synchronized(gate) {
            biometricNotice = VaultBiometricNotice.UNFINISHED
            if (result is VaultBiometricResult.Failed) biometricAvailability = result.availability
        }
        cancelBiometricWorkflow(value)
    }
    /** Registers before start so synchronous callbacks and submit-return races cannot escape cleanup. */
    private fun beginBiometricOperation(pending: VaultBiometricOperation, binding: VaultBiometricBinding,
        before: VaultStoredSnapshot, consume: (VaultBiometricResult) -> Unit) {
        val record = BiometricPending(pending.context, binding, before, consume)
        synchronized(gate) {
            biometricOperations[pending] = record; contexts += pending.context; jobs++
            if (binding.purpose == VaultBiometricPurpose.UNLOCK) biometricUnlockBinding = null
        }
        try {
            record.deadlineWake = scheduler.schedule((pending.context.deadlineElapsedMillis - clock.elapsedMillis()).coerceAtLeast(0)) {
                if (synchronized(gate) { biometricOperations[pending] === record }) {
                    pending.context.cancel(); pending.close(); transition(VaultEvent.ExplicitLock, externalProtection = true)
                    biometricProgressed(pending)
                }
            }
            pending.onDrain { biometricProgressed(pending) }
            authentication.startBiometric(pending) { result ->
                val accepted = synchronized(gate) {
                    if (biometricOperations[pending] !== record || record.resultReceived) false
                    else { record.resultReceived = true; record.result = result; true }
                }
                if (!accepted) retireBiometricResult(result)
                biometricProgressed(pending)
            }
        } catch (error: Exception) {
            synchronized(gate) {
                if (!record.resultReceived) {
                    record.resultReceived = true
                    record.result = VaultBiometricResult.Failed((error as? VaultOperationException)?.category ?: VaultFailure.UNAVAILABLE)
                }
            }
            try { pending.close() } catch (_: Exception) { /* Retain unconfirmed start until actual drain. */ }
        } finally {
            synchronized(gate) { record.startedReturned = true }
            biometricProgressed(pending)
        }
    }
    private fun retireBiometricResult(result: VaultBiometricResult) {
        when (result) {
            is VaultBiometricResult.Unlocked -> releaseRoot(result.root)
            is VaultBiometricResult.Mutation -> releaseMutation(result.mutation)
            is VaultBiometricResult.Failed -> Unit
        }
    }
    private fun biometricProgressed(pending: VaultBiometricOperation) {
        // Cross-owner observation is outside the runtime gate; exact record identity is checked again below.
        val observed = synchronized(gate) { biometricOperations[pending] } ?: return
        if (pending.observeDrain() != ExportRelease.RELEASED) return
        val record = synchronized(gate) {
            val current = biometricOperations[pending] ?: return
            if (current !== observed || !current.startedReturned || current.continuationQueued) return
            current.continuationQueued = true
            current
        }
        try {
            work.execute {
                var consumed = false
                val result = synchronized(gate) { record.result ?: VaultBiometricResult.Failed(VaultFailure.CANCELLED) }
                try {
                    record.operation.checkActive(); record.binding.requireCurrent()
                    consumed = true
                    record.consume(result)
                } catch (error: Exception) {
                    if (!consumed) retireBiometricResult(result)
                    val category = (error as? VaultOperationException)?.category ?: VaultFailure.UNAVAILABLE
                    if (category.confirmedPrimaryFailure()) failure(category)
                    else {
                        synchronized(gate) { biometricNotice = VaultBiometricNotice.UNFINISHED }
                        transition(VaultEvent.ExplicitLock, externalProtection = true)
                    }
                } finally {
                    record.deadlineWake?.cancel()
                    try { pending.close() } catch (_: Exception) { /* Auth drain still gates future work. */ }
                    synchronized(gate) {
                        if (biometricOperations.remove(pending) != null) { jobs--; contexts -= record.operation }
                    }
                    tryAcknowledgeCleanup(); showStatus()
                }
            }
        } catch (_: Exception) {
            // Queue rejection does not establish result retirement. Keep exact pending/result and block new owner admission.
            synchronized(gate) { biometricNotice = VaultBiometricNotice.UNAVAILABLE }
            record.operation.cancel()
            try { pending.close() } catch (_: Exception) { }
            showStatus()
        }
    }
    fun biometricPrivateCoverRequired(): Boolean = synchronized(gate) {
        biometricUnlockBinding != null || biometricOperations.isNotEmpty() ||
            biometricWorkflow != null && biometricPhase != BiometricPhase.PASSWORD_PROMPT
    }
    /** Does not authorize display. PREPARED, unknown submission and terminal/drain-only work cannot explain focus loss. */
    fun systemPromptOwnsFocus(host: Any, activity: Any, generation: Long): Boolean {
        val records = synchronized(gate) { biometricOperations.entries.map { it.key to it.value } }
        return records.any { (pending, record) ->
            if (record.binding.hostIdentity !== host || record.binding.platformHost !== activity ||
                record.binding.hostGeneration != generation || !pending.ownsSystemPromptFocus()) false
            else try {
                record.operation.checkActive(); record.binding.requireCurrent()
                synchronized(gate) { !closed && route && foreground && biometricOperations[pending] === record }
            } catch (_: Exception) { false }
        }
    }
    fun biometricCleanupComplete(): Boolean {
        if (authentication.observeBiometricDrain() != ExportRelease.RELEASED) return false
        return synchronized(gate) { biometricOperations.isEmpty() }
    }

    private fun workflowCurrentLocked(value: VaultRecoveryWorkflow): Boolean = !closed && !failureSticky &&
        recoveryWorkflow === value && clock.elapsedMillis() < value.deadlineElapsedMillis && route && foreground
    fun recoveryWorkflowCurrent(value: VaultRecoveryWorkflow): Boolean = synchronized(gate) {
        workflowCurrentLocked(value) && (recoveryDisplayDeadline == 0L || clock.elapsedMillis() < recoveryDisplayDeadline)
    }
    fun beginRecoveryWorkflow(kind: VaultRecoveryWorkflowKind): VaultRecoveryWorkflow? {
        val value = synchronized(gate) {
            val current = state
            val data = snapshot?.data ?: return null
            if (closed || failureSticky || recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() ||
                authentication.observeBiometricDrain() != ExportRelease.RELEASED || playback?.occupied() == true || media?.blocksCredentialRecovery() == true || jobs != 0 || credential != null ||
                current?.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE ||
                current.retryStatus != VaultRetryStatus.READY || !route || !foreground ||
                (kind == VaultRecoveryWorkflowKind.ENROLL && data.recoveryState == VaultRecoveryState.ACTIVE) ||
                (kind == VaultRecoveryWorkflowKind.RESET_PASSWORD && data.recoveryState != VaultRecoveryState.ACTIVE)) return null
            val now = clock.elapsedMillis()
            if (now < 0 || now > Long.MAX_VALUE - VaultRuntimeLimits.RECOVERY_WORKFLOW_MILLIS) return null
            VaultRecoveryWorkflow(kind, now + VaultRuntimeLimits.RECOVERY_WORKFLOW_MILLIS).also {
                recoveryWorkflow = it
                recoveryPhase = if (kind == VaultRecoveryWorkflowKind.ENROLL) RecoveryPhase.PASSWORD_PROMPT else RecoveryPhase.RESET_PROMPT
                recoveryNotice = VaultRecoveryNotice.NONE; recoveryDisplayDeadline = 0
            }
        }
        installRecoveryWake(value, value.deadlineElapsedMillis)
        return value
    }
    fun recoveryPromptDeadline(value: VaultRecoveryWorkflow): Long = synchronized(gate) {
        if (recoveryWorkflow !== value) 0L else if (recoveryDisplayDeadline > 0) recoveryDisplayDeadline else value.deadlineElapsedMillis
    }
    fun currentRecoveryDisplayWorkflow(): VaultRecoveryWorkflow? = synchronized(gate) {
        recoveryWorkflow?.takeIf { workflowCurrentLocked(it) && recoveryPhase == RecoveryPhase.DISPLAY_READY }
    }
    fun coverForRecoveryHandoff(value: VaultRecoveryWorkflow): Boolean {
        val valid = synchronized(gate) { workflowCurrentLocked(value) && jobs == 0 &&
            recoveryPhase in setOf(RecoveryPhase.PASSWORD_PROMPT, RecoveryPhase.RESET_PROMPT,
                RecoveryPhase.DISPLAY_READY, RecoveryPhase.CONFIRM_PROMPT) }
        if (!valid) return false
        transition(VaultEvent.SurfaceCovered, value, expectedRecovery = value)
        return recoveryWorkflowCurrent(value)
    }
    fun unlockForRecovery(input: VaultCredentialInput, value: VaultRecoveryWorkflow) = requestPasswordUnlock(input, value, null)
    fun takeRecoveryDisplay(value: VaultRecoveryWorkflow): VaultRecoveryInput? = synchronized(gate) {
        if (!workflowCurrentLocked(value) || recoveryPhase != RecoveryPhase.DISPLAY_READY || jobs != 0 ||
            state?.cleanupStatus != VaultCleanupStatus.COMPLETE || clock.elapsedMillis() >= recoveryDisplayDeadline) return null
        recoveryDisplay?.also { recoveryDisplay = null; recoveryPhase = RecoveryPhase.DISPLAY }
    }
    fun advanceRecoveryConfirmation(value: VaultRecoveryWorkflow): Boolean = synchronized(gate) {
        if (!workflowCurrentLocked(value) || recoveryPhase != RecoveryPhase.DISPLAY ||
            clock.elapsedMillis() >= recoveryDisplayDeadline) return false
        recoveryPhase = RecoveryPhase.CONFIRM_PROMPT
        true
    }
    fun cancelRecoveryWorkflow(value: VaultRecoveryWorkflow) {
        // Identity check and core cleanup fencing occur in one critical section. Old timers cannot cancel a new flow.
        transition(VaultEvent.ExplicitLock, expectedRecovery = value)
    }
    private fun installRecoveryWake(value: VaultRecoveryWorkflow, deadline: Long) {
        val timer = scheduler.schedule((deadline - clock.elapsedMillis()).coerceAtLeast(0)) { cancelRecoveryWorkflow(value) }
        synchronized(gate) {
            if (recoveryWorkflow === value) { recoveryWake?.cancel(); recoveryWake = timer } else timer.cancel()
        }
    }
    private fun clearRecoveryWorkflowLocked() {
        recoveryWake?.cancel(); recoveryWake = null
        recoveryDisplay?.close(); recoveryDisplay = null
        recoveryInput?.close(); recoveryInput = null
        recoveryWorkflow = null; recoveryPhase = null; recoveryDisplayDeadline = 0; recoveryPendingSnapshot = null
    }
    private fun workflowContext(value: VaultRecoveryWorkflow, budget: Long, deadline: Long = value.deadlineElapsedMillis): VaultOperationContext {
        synchronized(gate) { if (!workflowCurrentLocked(value)) throw VaultOperationException(VaultFailure.CANCELLED) }
        return context(budget, minOf(value.deadlineElapsedMillis, deadline))
    }
    private fun requireWorkflowOperation(value: VaultRecoveryWorkflow, operation: VaultOperationContext,
        phase: RecoveryPhase, attempt: VaultAttemptIdentity? = null) {
        synchronized(gate) {
            operation.checkActive()
            if (operation !in contexts || !workflowCurrentLocked(value) || recoveryPhase != phase ||
                (attempt != null && state?.activeAttempt?.identity != attempt)) throw VaultOperationException(VaultFailure.CANCELLED)
        }
    }
    fun confirmRecovery(value: VaultRecoveryWorkflow, input: VaultRecoveryInput) {
        val before = synchronized(gate) {
            if (!workflowCurrentLocked(value) || recoveryPhase != RecoveryPhase.CONFIRM_PROMPT || jobs != 0 ||
                state?.cleanupStatus != VaultCleanupStatus.COMPLETE || clock.elapsedMillis() >= recoveryDisplayDeadline) null
            else recoveryPendingSnapshot?.also { recoveryPhase = RecoveryPhase.CONFIRMING }
        }
        if (before == null) { input.close(); return }
        val operation = try { workflowContext(value, VaultRuntimeLimits.AUTHENTICATION_MILLIS, recoveryDisplayDeadline) }
            catch (_: Exception) { input.close(); cancelRecoveryWorkflow(value); return }
        submit(operation, onFailure = { input.close(); if (it in setOf(VaultFailure.REJECTED, VaultFailure.CANCELLED, VaultFailure.DEADLINE)) cancelRecoveryWorkflow(value) else failure(it) }) {
            try {
                requireWorkflowOperation(value, operation, RecoveryPhase.CONFIRMING)
                val actual = (store.inspect(operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) as? VaultStoreObservation.Present)?.snapshot
                    ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
                if (actual != before) throw VaultOperationException(VaultFailure.CONFLICT)
                val mutation = authentication.prepareRecoveryActivation(actual, input, operation)
                val committed = try { store.compareAndSwapCredentials(actual, mutation, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) }
                    finally { mutation.close() }
                synchronized(gate) { snapshot = committed } // Durable fact, even if UI acceptance is subsequently cancelled.
                operation.checkActive()
                synchronized(gate) {
                    if (!workflowCurrentLocked(value)) throw VaultOperationException(VaultFailure.CANCELLED)
                    snapshot = committed; recoveryNotice = VaultRecoveryNotice.CODE_ENABLED
                    clearRecoveryWorkflowLocked()
                }
                transition(VaultEvent.RecoveryChoiceRecorded(VaultRecoveryChoice.OPTED_IN))
                transition(VaultEvent.RecoveryMaterialStatusObserved(VaultRecoveryMaterialStatus.CONFIRMED_AVAILABLE))
                transition(VaultEvent.ExplicitLock)
            } finally { input.close() }
        }
        showStatus()
    }
    fun resetPassword(value: VaultRecoveryWorkflow, input: VaultRecoveryInput, password: VaultCredentialInput) {
        try { authentication.validatePasswordInput(password) }
        catch (_: Exception) { input.close(); password.close(); return }
        val accepted = synchronized(gate) {
            val current = state
            if (!workflowCurrentLocked(value) || value.kind != VaultRecoveryWorkflowKind.RESET_PASSWORD ||
                recoveryPhase != RecoveryPhase.RESET_PROMPT || jobs != 0 || credential != null || recoveryInput != null ||
                current?.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE ||
                current.retryStatus != VaultRetryStatus.READY || snapshot?.data?.recoveryState != VaultRecoveryState.ACTIVE) false
            else { credential = password; recoveryInput = input; recoveryPhase = RecoveryPhase.RESETTING; true }
        }
        if (!accepted) { input.close(); password.close(); return }
        transition(VaultEvent.RecoveryResetRequested)
    }

    fun observePresence(inVaultRoute: Boolean, inForeground: Boolean) {
        synchronized(gate) { route = inVaultRoute; foreground = inForeground }
        transition(VaultEvent.LifecycleObserved(inVaultRoute, inForeground),
            externalProtection = !inVaultRoute || !inForeground)
    }
    fun surfaceCovered() { transition(VaultEvent.SurfaceCovered, externalProtection = true) }
    fun lock() { transition(VaultEvent.ExplicitLock, externalProtection = true) }
    fun deviceLocked() { transition(VaultEvent.DeviceLocked, externalProtection = true) }
    /** Activity reloads cannot overwrite an already accepted application-owner choice. */
    fun initializeAutoLockPolicy(loaded: VaultAutoLockPolicy): VaultAutoLockPolicy? =
        acceptAutoLockPolicy(loaded, initializeOnly = true)

    /** Null is intentional: an unknown/closed owner must never authorize a preference write. */
    fun acceptedAutoLockPolicy(): VaultAutoLockPolicy? = synchronized(gate) {
        configuredAutoLockPolicy.takeIf { !closed && autoLockPolicyInitialized }
    }

    fun changePolicy(policy: VaultAutoLockPolicy) { acceptAutoLockPolicy(policy, initializeOnly = false) }

    private fun acceptAutoLockPolicy(policy: VaultAutoLockPolicy, initializeOnly: Boolean): VaultAutoLockPolicy? {
        val result = synchronized(gate) {
            if (closed) return null
            if (initializeOnly && autoLockPolicyInitialized) return configuredAutoLockPolicy
            configuredAutoLockPolicy = policy
            autoLockPolicyInitialized = true
            // Recording the choice and reducing it are one ordered operation, including startup races.
            state?.let { current -> reducer.reduce(current, VaultEvent.PolicyChanged(policy), tick())
                .also { state = it.state; effectsInFlight++ } }
        }
        if (result != null) {
            executeEffects(result.effects)
            showStatus()
        }
        return acceptedAutoLockPolicy()
    }
    /** Explicit foreground reveal intent. Returning to a route does not invoke this automatically. */
    fun revealEmptyCatalog() {
        if (synchronized(gate) { readLease != null || recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() || pendingReset != null || playback?.occupied() == true }) return
        transition(VaultEvent.SurfaceVisible)
        val captured = synchronized(gate) {
            val current = state ?: return
            val durable = snapshot ?: return
            val root = activeRoot ?: return
            if (closed || failureSticky || playback?.occupied() == true || recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() || pendingReset != null || jobs != 0 || activeRootEpoch != current.authEpoch ||
                !current.permitsPresentation(tick(), current.authEpoch, current.presentationEpoch)) return
            val lease = ReadLease(presentationGeneration, current.authEpoch, current.presentationEpoch,
                durable.data?.catalogRevision ?: return, root)
            readLease = lease
            lease to durable
        }
        val operation = context(VaultRuntimeLimits.DATABASE_MILLIS)
        submit(operation, onFailure = { if (it != VaultFailure.CANCELLED) failure(it) }) {
            requireCurrentRead(captured.first, operation)
            val observed = store.inspect(operation) as? VaultStoreObservation.Present
                ?: throw VaultOperationException(VaultFailure.CORRUPT)
            if (observed.snapshot != captured.second) throw VaultOperationException(VaultFailure.CONFLICT)
            requireCurrentRead(captured.first, operation)
            val catalog = if (media != null || playback != null) authentication.authenticateCatalog(captured.first.root, observed.snapshot, operation) else null
            val decoded = if (catalog == null) authentication.readEmptyCatalog(captured.first.root, observed.snapshot, operation) else null
            val revision = catalog?.catalogRevision ?: checkNotNull(decoded).catalogRevision
            val rows = catalog?.entries?.map { entry -> VaultMediaRow(VaultMediaReadRef(observed.snapshot.identity.vaultId,
                entry.identity.objectId, entry.identity.generation, revision), entry.displayName, entry.plaintext.bytes) } ?: emptyList()
            operation.checkActive()
            ui.execute {
                synchronized(gate) {
                    val current = state
                    if (!closed && !failureSticky && readLease === captured.first &&
                        presentationGeneration == captured.first.generation && activeRoot === captured.first.root &&
                        current?.permitsPresentation(tick(), captured.first.authEpoch, captured.first.presentationEpoch) == true &&
                        revision == captured.first.revision) {
                        revealedPlaybackRefs = rows.map { it.ref }
                        if (rows.isEmpty()) presentation.presentEmpty(revision) else presentation.presentCatalog(revision, rows)
                        media?.refreshPresentation()
                    }
                }
            }
        }
    }

    private fun requireCurrentRead(lease: ReadLease, operation: VaultOperationContext) {
        synchronized(gate) {
            operation.checkActive()
            val current = state
            if (closed || failureSticky || !route || !foreground || readLease !== lease ||
                activeRoot !== lease.root || presentationGeneration != lease.generation ||
                current?.permitsPresentation(tick(), lease.authEpoch, lease.presentationEpoch) != true) {
                throw VaultOperationException(VaultFailure.CANCELLED)
            }
        }
    }

    private fun transition(event: VaultEvent, preserveRecovery: VaultRecoveryWorkflow? = null,
        expectedRecovery: VaultRecoveryWorkflow? = null, externalProtection: Boolean = false,
        preserveBiometric: VaultBiometricWorkflow? = null, expectedBiometric: VaultBiometricWorkflow? = null) {
        val result = synchronized(gate) {
            if (closed || expectedRecovery != null && recoveryWorkflow !== expectedRecovery ||
                expectedBiometric != null && biometricWorkflow !== expectedBiometric) return
            if (state == null) return@synchronized null
            // External loss of a recovery modal must revoke its root, not leave ordinary grace authority.
            // The exact workflow check and core cleanup fence precede releasing this gate.
            val first = if (externalProtection && (recoveryWorkflow != null || biometricWorkflow != null))
                reducer.reduce(requireNotNull(state), VaultEvent.ExplicitLock, tick()) else null
            val next = reducer.reduce(first?.state ?: requireNotNull(state), event, tick())
            VaultTransition(next.state, first?.effects.orEmpty() + next.effects, next.outcome)
                .also { state = it.state; effectsInFlight++ }
        }
        if (externalProtection) protectPresentation()
        if (result == null) {
            // Startup can fail before reducer state exists. A later cover must not erase its explanation.
            if (synchronized(gate) { failureSticky }) showStatus()
            return
        }
        executeEffects(result.effects, preserveRecovery, preserveBiometric)
        showStatus()
    }
    private fun executeEffects(effects: List<VaultEffect>, preserveRecovery: VaultRecoveryWorkflow? = null,
        preserveBiometric: VaultBiometricWorkflow? = null) {
        try { effects.forEach { effect -> when (effect) {
            VaultEffect.MaskPrivatePresentation -> protectPresentation(preserveRecovery = preserveRecovery, preserveBiometric = preserveBiometric)
            VaultEffect.StopPrivatePlaybackAndReaders -> { playback?.revokeAll(); media?.revoke() }
            VaultEffect.ClearPrivateMemory -> synchronized(gate) { credential?.close(); credential = null; recoveryInput?.close(); recoveryInput = null }
            is VaultEffect.RevokeExternalSession -> retireRoots()
            is VaultEffect.DiscardAttemptResult -> retirePending(effect.identity)
            is VaultEffect.RequestOwnedPlaintextCleanup -> tryAcknowledgeCleanup()
            is VaultEffect.PersistRetryWrite -> persistRetry(effect.write)
            is VaultEffect.BeginExternalAuthentication -> authenticate(effect.attempt)
            VaultEffect.CancelWake -> synchronized(gate) { wakeGeneration++; wake?.cancel(); wake = null }
            is VaultEffect.ScheduleWake -> {
                val delay = (effect.deadline.elapsedMillis - clock.elapsedMillis()).coerceAtLeast(0)
                synchronized(gate) {
                    val expectedWake = ++wakeGeneration
                    wake = scheduler.schedule(delay) {
                        if (protectPresentation(expectedWake)) transition(VaultEvent.ClockObserved)
                    }
                }
            }
            is VaultEffect.RequireUserRecovery, is VaultEffect.ReportTechnicalCategory -> Unit
        } } } finally {
            synchronized(gate) { effectsInFlight-- }
            tryAcknowledgeCleanup()
        }
    }
    private fun persistRetry(write: VaultRetryWrite) {
        data class WriteAdmission(val before: VaultStoredSnapshot, val workflow: VaultRecoveryWorkflow?,
            val reset: PendingReset?, val purpose: VaultAttemptPurpose, val biometric: VaultBiometricWorkflow?)
        val admission = synchronized(gate) {
            val current = state
            val attempt = current?.activeAttempt
            val before = snapshot
            if (closed || failureSticky || attempt?.identity != write.identity.attempt ||
                current.pendingRetryWrite != write || before == null) null
            else {
                val reset = pendingReset?.takeIf { it.attempt == write.identity.attempt && write.kind == VaultRetryWriteKind.RESET }
                if (write.kind == VaultRetryWriteKind.RESET &&
                    ((attempt.purpose == VaultAttemptPurpose.RECOVERY_RESET) != (reset != null))) null
                else WriteAdmission(before, recoveryWorkflow, reset, attempt.purpose, biometricWorkflow)
            }
        } ?: run { transition(VaultEvent.RetryWriteFailed(write.identity)); return }
        val before = admission.before; val workflow = admission.workflow; val reset = admission.reset
        val operation = try {
            reset?.operation?.bounded(VaultRuntimeLimits.DATABASE_MILLIS, write.deadline.elapsedMillis)
                ?: admission.biometric?.context?.bounded(VaultRuntimeLimits.DATABASE_MILLIS, write.deadline.elapsedMillis)
                ?: context(VaultRuntimeLimits.DATABASE_MILLIS,
                    minOf(write.deadline.elapsedMillis, workflow?.deadlineElapsedMillis ?: Long.MAX_VALUE))
        } catch (_: Exception) { transition(VaultEvent.RetryWriteFailed(write.identity)); return }
        submit(operation, onFailure = { category ->
            if (category.confirmedPrimaryFailure()) failure(category)
            else transition(VaultEvent.RetryWriteFailed(write.identity))
        }) {
            admission.biometric?.let { requireBiometricCurrent(it, operation) }
            if (workflow != null) requireWorkflowOperation(workflow, operation,
                if (workflow.kind == VaultRecoveryWorkflowKind.RESET_PASSWORD) RecoveryPhase.RESETTING else RecoveryPhase.AUTHENTICATING,
                write.identity.attempt)
            synchronized(gate) {
                operation.checkActive()
                val current = state
                val activeAttempt = current?.activeAttempt
                if (operation !in contexts || activeAttempt?.identity != write.identity.attempt ||
                    activeAttempt.purpose != admission.purpose || current.pendingRetryWrite != write ||
                    (admission.purpose == VaultAttemptPurpose.RECOVERY_RESET && write.kind == VaultRetryWriteKind.RESET &&
                        (reset == null || pendingReset !== reset))) throw VaultOperationException(VaultFailure.CANCELLED)
            }
            val actualLedger = authentication.authenticateLedger(before, operation)
            if (actualLedger.ledgerRevision != write.expectedLedgerRevision) throw VaultOperationException(VaultFailure.CONFLICT)
            val envelope = authentication.sealLedger(before.identity, write.proposedLedger, operation)
            val stored = if (reset == null) store.compareAndSwapRetry(before, write, envelope, operation)
                else store.compareAndSwapCredentialReset(before, write, envelope, reset.mutation, operation)
            synchronized(gate) {
                snapshot = stored
                if (write.kind == VaultRetryWriteKind.RESERVATION) confirmedReservation = write
            }
            operation.checkActive()
            if (reset != null) {
                synchronized(gate) {
                    if (pendingReset === reset) pendingReset = null
                    recoveryNotice = VaultRecoveryNotice.PASSWORD_RESET
                    clearRecoveryWorkflowLocked()
                }
                reset.mutation.close()
            }
            transition(VaultEvent.RetryWriteStored(write.identity, write.expectedLedgerRevision, stored.data!!.retryLedger.ledgerRevision))
            if (write.kind == VaultRetryWriteKind.RESET && reset == null) {
                synchronized(gate) {
                    val pending = pendingRoot
                    if (state?.mode == VaultMode.SESSION_OPEN && pending?.first == write.identity.attempt &&
                        pending.first.authEpoch == state?.authEpoch && !closed && !failureSticky) {
                        activeRoot = pending.second; activeRootEpoch = pending.first.authEpoch; pendingRoot = null
                    }
                }
                val workflow = synchronized(gate) { recoveryWorkflow?.takeIf { workflowCurrentLocked(it) &&
                    it.kind == VaultRecoveryWorkflowKind.ENROLL && recoveryPhase == RecoveryPhase.AUTHENTICATING && activeRoot != null } }
                if (workflow != null) prepareRecoveryEnrollment(workflow)
                synchronized(gate) { biometricWorkflow?.takeIf { biometricCurrentLocked(it) &&
                    biometricPhase == BiometricPhase.AUTHENTICATING && activeRoot != null } }?.let(::prepareBiometricWorkflow)
            }
        }
    }
    private fun prepareRecoveryEnrollment(value: VaultRecoveryWorkflow) {
        val captured = synchronized(gate) {
            if (!workflowCurrentLocked(value) || recoveryPhase != RecoveryPhase.AUTHENTICATING) return
            val root = activeRoot ?: return
            val durable = snapshot ?: return
            recoveryPhase = RecoveryPhase.PREPARING
            root to durable
        }
        val operation = try { workflowContext(value, VaultRuntimeLimits.AUTHENTICATION_MILLIS) }
            catch (_: Exception) { cancelRecoveryWorkflow(value); return }
        submit(operation, onFailure = { category ->
            if (category == VaultFailure.CANCELLED || category == VaultFailure.DEADLINE) cancelRecoveryWorkflow(value)
            else failure(category)
        }) {
            requireWorkflowOperation(value, operation, RecoveryPhase.PREPARING)
            val enrollment = authentication.prepareRecoveryEnrollment(captured.first, captured.second, operation)
            var display: VaultRecoveryInput? = null
            try {
                val staged = store.compareAndSwapCredentials(captured.second, enrollment.mutation,
                    operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS))
                synchronized(gate) { snapshot = staged }
                val actual = (store.inspect(operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) as? VaultStoreObservation.Present)?.snapshot
                    ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
                if (actual != staged) throw VaultOperationException(VaultFailure.CONFLICT)
                val shown = enrollment.takeDisplay()
                display = shown
                authentication.verifyPendingRecovery(actual, shown, operation)
                operation.checkActive()
                synchronized(gate) {
                    if (!workflowCurrentLocked(value)) throw VaultOperationException(VaultFailure.CANCELLED)
                    snapshot = actual; recoveryPendingSnapshot = actual
                    recoveryDisplay = display; display = null; recoveryPhase = RecoveryPhase.DISPLAY_READY
                    recoveryDisplayDeadline = minOf(value.deadlineElapsedMillis, clock.elapsedMillis() + VaultRuntimeLimits.PROMPT_MILLIS)
                }
                installRecoveryWake(value, synchronized(gate) { recoveryDisplayDeadline })
                // Controlled handoff keeps only ciphertext intent + the single display holder; actual root retires.
                transition(VaultEvent.ExplicitLock, value, expectedRecovery = value)
            } finally { display?.close(); enrollment.close() }
        }
    }
    private fun authenticate(attempt: VaultAttempt) {
        if (synchronized(gate) { biometricUnlockBinding != null }) { authenticateBiometric(attempt); return }
        if (attempt.purpose == VaultAttemptPurpose.RECOVERY_RESET) { authenticateRecoveryReset(attempt); return }
        data class PasswordInput(val input: VaultCredentialInput, val durable: VaultStoredSnapshot, val workflow: VaultRecoveryWorkflow?, val biometric: VaultBiometricWorkflow?)
        val values = synchronized(gate) {
            val input = credential ?: return@synchronized null
            val durable = snapshot ?: return@synchronized null
            val reservation = confirmedReservation ?: return@synchronized null
            if (reservation.identity.attempt != attempt.identity || reservation.proposedLedger != durable.data?.retryLedger ||
                state?.activeAttempt?.identity != attempt.identity || state?.mode != VaultMode.AUTHENTICATING) return@synchronized null
            credential = null
            PasswordInput(input, durable, recoveryWorkflow, biometricWorkflow)
        } ?: run { transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return }
        val operation = try { if (values.biometric != null)
            values.biometric.context.bounded(VaultRuntimeLimits.KDF_MILLIS, attempt.deadline.elapsedMillis)
            else if (values.workflow != null)
            workflowContext(values.workflow, VaultRuntimeLimits.KDF_MILLIS, attempt.deadline.elapsedMillis)
            else context(VaultRuntimeLimits.KDF_MILLIS, attempt.deadline.elapsedMillis) }
        catch (_: Exception) {
            values.input.close(); transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return
        }
        submit(operation, onFailure = { category ->
            values.input.close()
            if (category.confirmedPrimaryFailure()) failure(category)
            else transition(VaultEvent.AuthenticationResult(attempt.identity, if (category == VaultFailure.REJECTED)
                VaultAuthenticationOutcome.REJECTED else VaultAuthenticationOutcome.CANCELLED))
        }) {
            if (values.workflow != null) requireWorkflowOperation(values.workflow, operation, RecoveryPhase.AUTHENTICATING, attempt.identity)
            synchronized(gate) {
                operation.checkActive()
                if (operation !in contexts || state?.activeAttempt?.identity != attempt.identity ||
                    state?.activeAttempt?.purpose != VaultAttemptPurpose.UNLOCK || state?.mode != VaultMode.AUTHENTICATING)
                    throw VaultOperationException(VaultFailure.CANCELLED)
            }
            val root = try { authentication.unlockPassword(values.durable, values.input, attempt.identity, operation) }
                finally { values.input.close() }
            val accepted = synchronized(gate) {
                val current = state
                if (closed || failureSticky || current?.activeAttempt?.identity != attempt.identity ||
                    current.mode != VaultMode.AUTHENTICATING || !route || !foreground ||
                    values.workflow != null && !workflowCurrentLocked(values.workflow) ||
                    values.biometric != null && !biometricCurrentLocked(values.biometric)) false
                else { pendingRoot = attempt.identity to root; if (values.biometric != null) biometricAttempt = attempt.identity; true }
            }
            if (!accepted) { root.close(); return@submit }
            try { operation.checkActive() }
            catch (failure: VaultOperationException) { retirePending(attempt.identity); throw failure }
            transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
        }
    }

    private fun authenticateRecoveryReset(attempt: VaultAttempt) {
        data class ResetInput(val password: VaultCredentialInput, val code: VaultRecoveryInput,
            val durable: VaultStoredSnapshot, val workflow: VaultRecoveryWorkflow)
        val values = synchronized(gate) {
            val input = credential ?: return@synchronized null
            val code = recoveryInput ?: return@synchronized null
            val durable = snapshot ?: return@synchronized null
            val workflow = recoveryWorkflow ?: return@synchronized null
            val reservation = confirmedReservation ?: return@synchronized null
            if (!workflowCurrentLocked(workflow) || workflow.kind != VaultRecoveryWorkflowKind.RESET_PASSWORD ||
                recoveryPhase != RecoveryPhase.RESETTING || reservation.identity.attempt != attempt.identity ||
                reservation.proposedLedger != durable.data?.retryLedger || state?.activeAttempt?.identity != attempt.identity ||
                state?.mode != VaultMode.AUTHENTICATING) return@synchronized null
            credential = null; recoveryInput = null
            ResetInput(input, code, durable, workflow)
        } ?: run { transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return }
        val operation = try { workflowContext(values.workflow, VaultRuntimeLimits.AUTHENTICATION_MILLIS, attempt.deadline.elapsedMillis) }
            catch (_: Exception) {
                values.password.close(); values.code.close()
                transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return
            }
        submit(operation, onFailure = { category ->
            values.password.close(); values.code.close()
            if (category.confirmedPrimaryFailure()) failure(category)
            else transition(VaultEvent.AuthenticationResult(attempt.identity, if (category == VaultFailure.REJECTED)
                VaultAuthenticationOutcome.REJECTED else VaultAuthenticationOutcome.CANCELLED))
        }) {
            var activation: VaultCredentialMutation? = null
            try {
                requireWorkflowOperation(values.workflow, operation, RecoveryPhase.RESETTING, attempt.identity)
                val stage = authentication.preparePasswordReset(values.durable, values.code, values.password, attempt.identity, operation)
                val staged = try { store.compareAndSwapCredentials(values.durable, stage, operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) }
                    finally { stage.close() }
                synchronized(gate) { snapshot = staged }
                val actual = (store.inspect(operation.bounded(VaultRuntimeLimits.DATABASE_MILLIS)) as? VaultStoreObservation.Present)?.snapshot
                    ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
                if (actual != staged) throw VaultOperationException(VaultFailure.CONFLICT)
                activation = authentication.verifyPendingPasswordReset(actual, values.password, attempt.identity, operation)
                operation.checkActive()
                synchronized(gate) {
                    if (!workflowCurrentLocked(values.workflow) || state?.activeAttempt?.identity != attempt.identity ||
                        state?.mode != VaultMode.AUTHENTICATING) throw VaultOperationException(VaultFailure.CANCELLED)
                    snapshot = actual
                    pendingReset = PendingReset(attempt.identity, checkNotNull(activation), operation)
                    activation = null
                }
                transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.RECOVERY_RESET_VERIFIED))
            } finally { activation?.close(); values.password.close(); values.code.close() }
        }
    }

    private fun protectPresentation(expectedWake: Long? = null, expectedOperation: VaultOperationContext? = null,
        preserveRecovery: VaultRecoveryWorkflow? = null, preserveBiometric: VaultBiometricWorkflow? = null): Boolean {
        val generation = synchronized(gate) {
            if (expectedWake != null && (closed || expectedWake != wakeGeneration)) return false
            if (expectedOperation != null && (closed || expectedOperation !in contexts)) return false
            readLease = null; revealedPlaybackRefs = emptyList()
            if (preserveRecovery == null || recoveryWorkflow !== preserveRecovery) clearRecoveryWorkflowLocked()
            if (preserveBiometric == null || biometricWorkflow !== preserveBiometric) clearBiometricWorkflowLocked()
            biometricUnlockBinding = null
            contexts.forEach { it.cancel() }
            if (presentationGeneration == Long.MAX_VALUE) { failureSticky = true } else presentationGeneration++
            presentationGeneration
        }
        // Fast acceptance revocation precedes cancellation; close never claims platform drain.
        synchronized(gate) { biometricOperations.keys.toList() }.forEach { operation ->
            try { operation.close() } catch (_: Exception) { /* Retained until an actual drain receipt. */ }
            biometricProgressed(operation)
        }
        playback?.revokeAll()
        media?.revoke()
        presentation.cover(generation) {
            synchronized(gate) { if (generation == presentationGeneration) hiddenGeneration = generation }
            tryAcknowledgeCleanup()
        }
        return true
    }
    private fun retirePending(identity: VaultAttemptIdentity) {
        val mutation = synchronized(gate) { pendingReset?.takeIf { it.attempt == identity }?.also { pendingReset = null } }
        mutation?.mutation?.let(::releaseMutation)
        val root = synchronized(gate) {
            pendingRoot?.takeIf { it.first == identity }?.second.also {
                if (it != null) pendingRoot = null
            }
        } ?: return
        releaseRoot(root)
    }
    private fun retireRoots() {
        val mutation = synchronized(gate) { pendingReset.also { pendingReset = null } }
        mutation?.mutation?.let(::releaseMutation)
        val roots = synchronized(gate) {
            listOfNotNull(activeRoot, pendingRoot?.second).also {
                activeRoot = null; activeRootEpoch = null; pendingRoot = null
            }
        }
        roots.forEach(::releaseRoot)
    }
    private fun releaseMutation(mutation: VaultCredentialMutation) {
        synchronized(gate) { retiringMutations[mutation] = Unit }
        submit(null, onFailure = { failure(VaultFailure.RELEASE_UNKNOWN) }) {
            mutation.close()
            synchronized(gate) { retiringMutations.remove(mutation) }
        }
    }
    private fun releaseRoot(root: VaultRootCapability) {
        // Failed closes retain the exact handle; a timeout/exception is not a release receipt.
        synchronized(gate) { retiringRoots[root] = Unit }
        submit(null, onFailure = { failure(VaultFailure.RELEASE_UNKNOWN) }) {
            root.close()
            synchronized(gate) { retiringRoots.remove(root) }
        }
    }
    private fun tryAcknowledgeCleanup() {
        val binding = synchronized(gate) {
            val current = state
            if (closed || failureSticky || jobs != 0 || effectsInFlight != 0 || biometricOperations.isNotEmpty() ||
                authentication.observeBiometricDrain() != ExportRelease.RELEASED || retiringRoots.isNotEmpty() || retiringMutations.isNotEmpty() || credential != null || recoveryInput != null || pendingReset != null || pendingRoot != null ||
                readLease != null || playback?.cleanupComplete() == false || media?.cleanupComplete() == false || (activeRoot != null && activeRootEpoch != current?.authEpoch) ||
                hiddenGeneration != presentationGeneration ||
                current?.cleanupStatus != VaultCleanupStatus.PENDING) return
            current.currentCleanup ?: return
        }
        if (clock.elapsedMillis() < binding.deadline.elapsedMillis) transition(VaultEvent.CleanupCompleted(binding))
        else transition(VaultEvent.CleanupFailed(binding))
    }
    private fun submit(operation: VaultOperationContext?, onFailure: (VaultFailure) -> Unit = ::failure, task: () -> Unit) {
        synchronized(gate) { jobs++; if (operation != null) contexts += operation }
        var deadlineWake: VaultWakeCancellation? = null
        try {
            deadlineWake = operation?.let { current ->
            scheduler.schedule((current.deadlineElapsedMillis - clock.elapsedMillis()).coerceAtLeast(0)) {
                if (protectPresentation(expectedOperation = current)) {
                    // Cancelling acceptance does not decrement jobs or release the actual worker.
                    if (synchronized(gate) { state == null }) failure(VaultFailure.DEADLINE)
                    else transition(VaultEvent.ExplicitLock)
                }
            }
            }
            work.execute {
                try { operation?.checkActive(); task() }
                catch (error: VaultOperationException) { onFailure(error.category) }
                catch (_: Exception) { onFailure(VaultFailure.UNAVAILABLE) }
                finally {
                    deadlineWake?.cancel()
                    synchronized(gate) { jobs--; if (operation != null) contexts -= operation }
                    tryAcknowledgeCleanup(); showStatus()
                }
            }
        } catch (_: RuntimeException) {
            deadlineWake?.cancel()
            synchronized(gate) { jobs--; if (operation != null) contexts -= operation }
            onFailure(VaultFailure.UNAVAILABLE)
        }
    }
    private fun failure(category: VaultFailure) {
        synchronized(gate) {
            // Diagnostic refinement only: a late trusted leaf may explain an earlier unknown failure.
            // The first confirmed reason wins; cancellation/cleanup outcomes and authority never change.
            val reason = category.safeReason()
            if (failureReason == null || failureReason == VaultFailureReason.UNKNOWN && reason != VaultFailureReason.UNKNOWN)
                failureReason = reason
            failureSticky = true; credential?.close(); credential = null
        }
        protectPresentation()
        retireRoots()
        transition(VaultEvent.IntegrityFailureObserved)
        showStatus()
    }
    private fun showStatus() {
        val generation = synchronized(gate) { presentationGeneration }
        ui.execute {
            synchronized(gate) {
                if (generation != presentationGeneration || readLease != null) return@execute
                presentation.presentBiometricState(snapshot?.data?.biometricState ?: VaultBiometricState.NONE,
                    biometricNotice, biometricAvailability)
                presentation.presentRecoveryState(snapshot?.data?.recoveryState ?: VaultRecoveryState.NONE,
                    snapshot?.data?.pendingPassword != null, recoveryNotice)
                val status = when {
                    closed || failureSticky -> VaultRuntimeStatus.UNAVAILABLE
                    state == null -> VaultRuntimeStatus.LOADING
                    trustedAbsent -> VaultRuntimeStatus.SETUP_REQUIRED
                    state?.mode == VaultMode.RECOVERY_REQUIRED -> VaultRuntimeStatus.RECOVERY_REQUIRED
                    state?.mode == VaultMode.UNAVAILABLE -> VaultRuntimeStatus.UNAVAILABLE
                    recoveryPhase == RecoveryPhase.DISPLAY_READY && jobs == 0 && state?.cleanupStatus == VaultCleanupStatus.COMPLETE -> VaultRuntimeStatus.RECOVERY_CODE_READY
                    setupRunning || state?.activeAttempt != null || recoveryWorkflow != null || biometricWorkflow != null || biometricOperations.isNotEmpty() -> VaultRuntimeStatus.WORKING
                    state?.mode == VaultMode.SESSION_OPEN && state?.cleanupStatus == VaultCleanupStatus.COMPLETE && activeRoot != null && jobs == 0 -> VaultRuntimeStatus.READY_TO_REVEAL
                    else -> VaultRuntimeStatus.LOCKED
                }
                if (failureSticky) presentation.presentFailure(failureReason ?: VaultFailureReason.UNKNOWN)
                else presentation.presentStatus(status)
            }
        }
    }
    /** Read-only host release check. It never grants authentication or presentation authority. */
    fun hostCleanupComplete(): Boolean = synchronized(gate) {
        !closed && !failureSticky && jobs == 0 && effectsInFlight == 0 && biometricOperations.isEmpty() &&
            authentication.observeBiometricDrain() == ExportRelease.RELEASED && retiringRoots.isEmpty() && retiringMutations.isEmpty() &&
            credential == null && recoveryInput == null && pendingReset == null && pendingRoot == null && readLease == null && playback?.cleanupComplete() != false && media?.cleanupComplete() != false && state?.cleanupStatus == VaultCleanupStatus.COMPLETE
    }
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true; wake?.cancel(); wake = null; credential?.close(); credential = null
        }
        protectPresentation(); retireRoots()
        // Keep the app-scoped owners until this lane actually returns; don't create a replacement on timeout.
        submit(null) { try { authentication.close() } finally { store.close() } }
    }
}
