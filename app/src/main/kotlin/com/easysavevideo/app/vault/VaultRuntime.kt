package com.easysavevideo.app.vault

import com.easysavevideo.core.domain.VaultSessionReducer
import com.easysavevideo.core.model.*
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** The owner, not UI, acknowledges this exact covered presentation after actual composition/retirement. */
internal interface VaultPresentationPort {
    fun cover(generation: Long, onActuallyHidden: () -> Unit)
    fun presentMedia(view: VaultMediaView) = Unit
    fun presentCatalog(revision: Long, items: List<VaultMediaRow>) = Unit
    fun requestPhoneFileSelection(request: VaultPhoneFileRequest) = Unit
    fun presentEmpty(catalogRevision: Long)
    fun presentStatus(status: VaultRuntimeStatus)
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
) : AutoCloseable {
    private val gate = Any()
    private val reducer = VaultSessionReducer(VaultRuntimeLimits.retryPolicy())
    private var state: VaultState? = null
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
    private var wake: VaultWakeCancellation? = null
    private var wakeGeneration = 0L
    private var failureSticky = false
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
    private fun dispatchMediaPicker(request: VaultPhoneFileRequest) = ui.execute {
        if (media?.admitPicker(request) == true) presentation.requestPhoneFileSelection(request)
        else media?.retireUndispatchedPicker(request)
    }

    private fun mediaSession(): VaultMediaSession? = synchronized(gate) {
        val current = state ?: return null
        val durable = snapshot ?: return null
        val root = activeRoot ?: return null
        if (closed || failureSticky || recoveryWorkflow != null || pendingReset != null || jobs != 0 ||
            !route || !foreground || activeRootEpoch != current.authEpoch ||
            !current.permitsPresentation(tick(), current.authEpoch, current.presentationEpoch)) return null
        val generation = presentationGeneration
        val auth = current.authEpoch; val epoch = current.presentationEpoch
        VaultMediaSession(root, durable) { synchronized(gate) {
            !closed && !failureSticky && route && foreground && recoveryWorkflow == null && pendingReset == null &&
                activeRoot === root && activeRootEpoch == auth && presentationGeneration == generation &&
                state?.permitsPresentation(tick(), auth, epoch) == true
        } }
    }
    fun queueMoveToVault(ref: com.easysavevideo.library.MediaReadRef): Boolean = media?.queueMove(ref) == true
    fun confirmMoveToVault() { media?.confirmMove() }
    fun copyMediaToPhone(ref: VaultMediaReadRef) { media?.beginCopy(ref) }
    fun retirePhoneSelection(request: VaultPhoneFileRequest): Boolean = media?.retireUndispatchedPicker(request) == true
    fun acceptPhoneFile(request: VaultPhoneFileRequest, target: VaultPhoneFileTarget?) { media?.acceptTarget(request, target) }
    fun confirmCopyToPhone() { media?.confirmCopy() }
    fun copyMediaBatchToPhone(refs: List<VaultMediaReadRef>) { media?.beginBatchCopy(refs) }
    fun acceptPhoneSelection(request: VaultPhoneFileRequest, selection: VaultPhoneSelection?) { media?.acceptSelection(request, selection) }
    fun confirmBatchCopy(token: Any? = null) { media?.confirmBatchCopy(token) }
    fun continueBatchCopy(token: Any? = null) { media?.continueBatchCopy(token) }
    fun keepCurrentBatchOutputAndContinue(token: Any? = null) { media?.keepCurrentBatchOutputAndContinue(token) }
    fun keepCurrentSingleOutput(token: Any? = null) { media?.keepCurrentSingleOutput(token) }
    fun reconcileMedia() { media?.recover() }
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
                is VaultStoreObservation.Unavailable -> throw VaultOperationException(observed.category)
            }
            operation.checkActive()
            val transition = synchronized(gate) {
                if (closed) return@submit
                reducer.start(processInstance, tick(), loaded).also { state = it.state; effectsInFlight++ }
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
            val permitted = !closed && !failureSticky && trustedAbsent && !setupRunning && jobs == 0 &&
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

    fun unlockPassword(input: VaultCredentialInput) = requestPasswordUnlock(input, null)

    private fun requestPasswordUnlock(input: VaultCredentialInput, workflow: VaultRecoveryWorkflow?) {
        val accepted = synchronized(gate) {
            val current = state
            if (closed || failureSticky || credential != null || jobs != 0 || snapshot?.phase != VaultStorePhase.READY ||
                (workflow == null && recoveryWorkflow != null || workflow != null &&
                    (!workflowCurrentLocked(workflow) || recoveryPhase != RecoveryPhase.PASSWORD_PROMPT)) ||
                current?.mode != VaultMode.LOCKED || current.cleanupStatus != VaultCleanupStatus.COMPLETE ||
                current.retryStatus != VaultRetryStatus.READY || !route || !foreground) false
            else { credential = input; if (workflow != null) recoveryPhase = RecoveryPhase.AUTHENTICATING; true }
        }
        if (!accepted) { input.close(); return }
        transition(VaultEvent.UnlockRequested)
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
            if (closed || failureSticky || recoveryWorkflow != null || media?.blocksCredentialRecovery() == true || jobs != 0 || credential != null ||
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
    fun unlockForRecovery(input: VaultCredentialInput, value: VaultRecoveryWorkflow) = requestPasswordUnlock(input, value)
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
    fun changePolicy(policy: VaultAutoLockPolicy) { transition(VaultEvent.PolicyChanged(policy)) }
    /** Explicit foreground reveal intent. Returning to a route does not invoke this automatically. */
    fun revealEmptyCatalog() {
        if (synchronized(gate) { recoveryWorkflow != null || pendingReset != null }) return
        transition(VaultEvent.SurfaceVisible)
        val captured = synchronized(gate) {
            val current = state ?: return
            val durable = snapshot ?: return
            val root = activeRoot ?: return
            if (closed || failureSticky || recoveryWorkflow != null || pendingReset != null || jobs != 0 || activeRootEpoch != current.authEpoch ||
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
            val catalog = if (media != null) authentication.authenticateCatalog(captured.first.root, observed.snapshot, operation) else null
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
        expectedRecovery: VaultRecoveryWorkflow? = null, externalProtection: Boolean = false) {
        val result = synchronized(gate) {
            if (closed || expectedRecovery != null && recoveryWorkflow !== expectedRecovery) return
            if (state == null) return@synchronized null
            // External loss of a recovery modal must revoke its root, not leave ordinary grace authority.
            // The exact workflow check and core cleanup fence precede releasing this gate.
            val first = if (externalProtection && recoveryWorkflow != null)
                reducer.reduce(requireNotNull(state), VaultEvent.ExplicitLock, tick()) else null
            val next = reducer.reduce(first?.state ?: requireNotNull(state), event, tick())
            VaultTransition(next.state, first?.effects.orEmpty() + next.effects, next.outcome)
                .also { state = it.state; effectsInFlight++ }
        }
        if (externalProtection) protectPresentation()
        if (result == null) return
        executeEffects(result.effects, preserveRecovery)
        showStatus()
    }
    private fun executeEffects(effects: List<VaultEffect>, preserveRecovery: VaultRecoveryWorkflow? = null) {
        try { effects.forEach { effect -> when (effect) {
            VaultEffect.MaskPrivatePresentation -> protectPresentation(preserveRecovery = preserveRecovery)
            VaultEffect.StopPrivatePlaybackAndReaders -> media?.revoke() ?: Unit
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
            val reset: PendingReset?, val purpose: VaultAttemptPurpose)
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
                else WriteAdmission(before, recoveryWorkflow, reset, attempt.purpose)
            }
        } ?: run { transition(VaultEvent.RetryWriteFailed(write.identity)); return }
        val before = admission.before; val workflow = admission.workflow; val reset = admission.reset
        val operation = try {
            reset?.operation?.bounded(VaultRuntimeLimits.DATABASE_MILLIS, write.deadline.elapsedMillis)
                ?: context(VaultRuntimeLimits.DATABASE_MILLIS,
                    minOf(write.deadline.elapsedMillis, workflow?.deadlineElapsedMillis ?: Long.MAX_VALUE))
        } catch (_: Exception) { transition(VaultEvent.RetryWriteFailed(write.identity)); return }
        submit(operation, onFailure = { transition(VaultEvent.RetryWriteFailed(write.identity)) }) {
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
        if (attempt.purpose == VaultAttemptPurpose.RECOVERY_RESET) { authenticateRecoveryReset(attempt); return }
        data class PasswordInput(val input: VaultCredentialInput, val durable: VaultStoredSnapshot, val workflow: VaultRecoveryWorkflow?)
        val values = synchronized(gate) {
            val input = credential ?: return@synchronized null
            val durable = snapshot ?: return@synchronized null
            val reservation = confirmedReservation ?: return@synchronized null
            if (reservation.identity.attempt != attempt.identity || reservation.proposedLedger != durable.data?.retryLedger ||
                state?.activeAttempt?.identity != attempt.identity || state?.mode != VaultMode.AUTHENTICATING) return@synchronized null
            credential = null
            PasswordInput(input, durable, recoveryWorkflow)
        } ?: run { transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return }
        val operation = try { if (values.workflow != null)
            workflowContext(values.workflow, VaultRuntimeLimits.KDF_MILLIS, attempt.deadline.elapsedMillis)
            else context(VaultRuntimeLimits.KDF_MILLIS, attempt.deadline.elapsedMillis) }
        catch (_: Exception) {
            values.input.close(); transition(VaultEvent.AuthenticationResult(attempt.identity, VaultAuthenticationOutcome.CANCELLED)); return
        }
        submit(operation, onFailure = { category ->
            values.input.close()
            transition(VaultEvent.AuthenticationResult(attempt.identity, if (category == VaultFailure.REJECTED)
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
                    values.workflow != null && !workflowCurrentLocked(values.workflow)) false
                else { pendingRoot = attempt.identity to root; true }
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
            transition(VaultEvent.AuthenticationResult(attempt.identity, if (category == VaultFailure.REJECTED)
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
        preserveRecovery: VaultRecoveryWorkflow? = null): Boolean {
        val generation = synchronized(gate) {
            if (expectedWake != null && (closed || expectedWake != wakeGeneration)) return false
            if (expectedOperation != null && (closed || expectedOperation !in contexts)) return false
            readLease = null
            if (preserveRecovery == null || recoveryWorkflow !== preserveRecovery) clearRecoveryWorkflowLocked()
            contexts.forEach { it.cancel() }
            if (presentationGeneration == Long.MAX_VALUE) { failureSticky = true } else presentationGeneration++
            presentationGeneration
        }
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
            if (closed || failureSticky || jobs != 0 || effectsInFlight != 0 || retiringRoots.isNotEmpty() || retiringMutations.isNotEmpty() || credential != null || recoveryInput != null || pendingReset != null || pendingRoot != null ||
                readLease != null || media?.cleanupComplete() == false || (activeRoot != null && activeRootEpoch != current?.authEpoch) ||
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
        synchronized(gate) { failureSticky = true; credential?.close(); credential = null }
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
                presentation.presentRecoveryState(snapshot?.data?.recoveryState ?: VaultRecoveryState.NONE,
                    snapshot?.data?.pendingPassword != null, recoveryNotice)
                val status = when {
                    closed || failureSticky -> VaultRuntimeStatus.UNAVAILABLE
                    state == null -> VaultRuntimeStatus.LOADING
                    trustedAbsent -> VaultRuntimeStatus.SETUP_REQUIRED
                    state?.mode == VaultMode.RECOVERY_REQUIRED -> VaultRuntimeStatus.RECOVERY_REQUIRED
                    state?.mode == VaultMode.UNAVAILABLE -> VaultRuntimeStatus.UNAVAILABLE
                    recoveryPhase == RecoveryPhase.DISPLAY_READY && jobs == 0 && state?.cleanupStatus == VaultCleanupStatus.COMPLETE -> VaultRuntimeStatus.RECOVERY_CODE_READY
                    setupRunning || state?.activeAttempt != null || recoveryWorkflow != null -> VaultRuntimeStatus.WORKING
                    state?.mode == VaultMode.SESSION_OPEN && state?.cleanupStatus == VaultCleanupStatus.COMPLETE && activeRoot != null && jobs == 0 -> VaultRuntimeStatus.READY_TO_REVEAL
                    else -> VaultRuntimeStatus.LOCKED
                }
                presentation.presentStatus(status)
            }
        }
    }
    /** Read-only host release check. It never grants authentication or presentation authority. */
    fun hostCleanupComplete(): Boolean = synchronized(gate) {
        !closed && !failureSticky && jobs == 0 && effectsInFlight == 0 && retiringRoots.isEmpty() && retiringMutations.isEmpty() &&
            credential == null && recoveryInput == null && pendingReset == null && pendingRoot == null && readLease == null && media?.cleanupComplete() != false && state?.cleanupStatus == VaultCleanupStatus.COMPLETE
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
