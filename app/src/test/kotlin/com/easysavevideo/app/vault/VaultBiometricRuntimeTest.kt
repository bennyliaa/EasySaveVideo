package com.easysavevideo.app.vault

import com.easysavevideo.core.model.*
import com.easysavevideo.library.export.ExportRelease
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** Fixed fake owner/store/host callbacks only. No platform prompt, entropy, key, SQL or device work. */
class VaultBiometricRuntimeTest {
    private fun cipher(n: Int = 1) = VaultCiphertext(byteArrayOf(n.toByte()))
    private fun password() = VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-fixture-password".toCharArray())
    private fun snapshot(state: VaultBiometricState) = VaultStoredSnapshot(
        VaultPersistentIdentity("vault", "install", "init", "device", "biometric"), VaultStorePhase.READY, 4,
        VaultStagedData(listOf(VaultSlotRecord("password", 1, VaultSlotKind.PASSWORD, cipher(), true),
            VaultSlotRecord("recovery", 2, VaultSlotKind.RECOVERY, cipher(2), true)) +
            if (state == VaultBiometricState.NONE) emptyList() else listOf(VaultSlotRecord("biometric", 3, VaultSlotKind.BIOMETRIC, cipher(3), true)),
            cipher(), cipher(), 4, cipher(), VaultRetryLedger(0, 0, -1, null), cipher(), VaultRecoveryState.ACTIVE,
            biometricState = state))
    private class Clock : VaultMonotonicClock { var now = 10L; override fun elapsedMillis() = now }
    private class Lane : Executor {
        val queue = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) { if (reject) throw java.util.concurrent.RejectedExecutionException(); queue += command }
        fun one() = queue.removeFirst().run()
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(count++ < 300); one() } }
    }
    private class Timers : VaultWakeScheduler {
        class Timer(val action: () -> Unit) { var cancelled = false }
        val entries = mutableListOf<Timer>()
        override fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation {
            val timer = Timer(task); entries += timer; return VaultWakeCancellation { timer.cancelled = true }
        }
    }
    private class View : VaultPresentationPort {
        var status = VaultRuntimeStatus.LOADING; var shown = 0
        var biometric = VaultBiometricState.NONE; var notice = VaultBiometricNotice.NONE
        override fun cover(generation: Long, onActuallyHidden: () -> Unit) = onActuallyHidden()
        override fun presentStatus(status: VaultRuntimeStatus) { this.status = status }
        override fun presentEmpty(catalogRevision: Long) { shown++ }
        override fun presentBiometricState(state: VaultBiometricState, notice: VaultBiometricNotice, availability: VaultBiometricAvailability?) {
            biometric = state; this.notice = notice
        }
    }
    private inner class Auth : VaultAuthenticationPort {
        val events = mutableListOf<String>(); val operations = mutableListOf<Op>()
        var roots = 0; var rootCloses = 0; var passwordCalls = 0; var synchronous = false
        var aliasUnknown = false; var aliasCalls = 0
        inner class Root(val operation: VaultOperationContext, val attempt: VaultAttemptIdentity) : VaultRootCapability {
            var closed = false
            override fun close() { if (!closed) { closed = true; rootCloses++ } }
        }
        inner class Mutation(val before: VaultStoredSnapshot, val state: VaultBiometricState, val operation: VaultOperationContext) : VaultCredentialMutation {
            var closed = false
            override fun close() { closed = true }
        }
        inner class Op(val before: VaultStoredSnapshot, val binding: VaultBiometricBinding,
            val workflowContext: VaultOperationContext, val attempt: VaultAttemptIdentity?, override val context: VaultOperationContext) : VaultBiometricOperation {
            var callback: ((VaultBiometricResult) -> Unit)? = null
            val listeners = mutableListOf<() -> Unit>(); var started = false; var drained = true; var closed = false; var platformSubmitted = false
            override fun onDrain(listener: () -> Unit) { listeners += listener }
            override fun ownsSystemPromptFocus() = started && platformSubmitted && !drained && !closed
            override fun observeDrain() = if (drained) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            override fun close() { closed = true }
            fun result(): VaultBiometricResult = when (binding.purpose) {
                VaultBiometricPurpose.UNLOCK -> { roots++; VaultBiometricResult.Unlocked(Root(context, checkNotNull(attempt))) }
                VaultBiometricPurpose.ENROLL_WRAP -> VaultBiometricResult.Mutation(Mutation(before, VaultBiometricState.PENDING, context))
                VaultBiometricPurpose.ENROLL_VERIFY -> VaultBiometricResult.Mutation(Mutation(before, VaultBiometricState.ACTIVE, context))
                else -> error("not a prompt")
            }
            fun emit(value: VaultBiometricResult = result(), drain: Boolean = true) {
                checkNotNull(callback)(value)
                if (drain) terminal()
            }
            fun terminal() { drained = true; listeners.toList().forEach { it() } }
        }
        private fun op(snapshot: VaultStoredSnapshot, binding: VaultBiometricBinding, context: VaultOperationContext,
            attempt: VaultAttemptIdentity?) = Op(snapshot, binding, context, attempt, context.bounded(VaultRuntimeLimits.PROMPT_MILLIS)).also { operations += it }
        override fun observeBiometricDrain() = if (operations.any { !it.drained }) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
        override fun prepareBiometricUnlock(snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity, binding: VaultBiometricBinding,
            context: VaultOperationContext): VaultBiometricOperation {
            binding.requireCurrent(); check(snapshot.data!!.retryLedger.outstandingReservationId != null)
            events += "prepare-unlock"; return op(snapshot, binding, context, attempt)
        }
        override fun prepareBiometricEnrollment(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
            binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricOperation {
            check(root is Root && !root.closed && root.attempt == attempt && root.operation.sharesOperation(context))
            check(snapshot.data!!.retryLedger.reservedAttemptCount == 0L)
            binding.requireCurrent(); events += "prepare-wrap"; return op(snapshot, binding, context, attempt)
        }
        override fun prepareBiometricActivation(snapshot: VaultStoredSnapshot, enrollment: VaultBiometricOperation,
            binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricOperation {
            val prior = enrollment as Op
            check(prior.drained && !prior.closed && prior.workflowContext.sharesOperation(context))
            check(snapshot.data!!.biometricState == VaultBiometricState.PENDING && prior.binding.sameWorkflow(binding))
            binding.requireCurrent(); events += "prepare-verify"; return op(snapshot, binding, context, prior.attempt)
        }
        override fun startBiometric(operation: VaultBiometricOperation, completed: (VaultBiometricResult) -> Unit) {
            val op = operation as Op
            check(!op.started); op.started = true; op.drained = false; op.platformSubmitted = true; op.callback = completed; events += "start-${op.binding.purpose}"
            if (synchronous) op.emit()
        }
        override fun prepareBiometricRevocation(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
            binding: VaultBiometricBinding, context: VaultOperationContext): VaultCredentialMutation {
            check(root is Root && !root.closed && root.attempt == attempt && root.operation.sharesOperation(context))
            binding.requireCurrent(); events += "revoke"; return Mutation(snapshot, VaultBiometricState.REVOKED, context)
        }
        override fun prepareBiometricAliasRetirement(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
            binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricAliasRetirement {
            check(root is Root && !root.closed && root.attempt == attempt && root.operation.sharesOperation(context))
            binding.requireCurrent(); events += "permit"; return object : VaultBiometricAliasRetirement { override fun close() = Unit }
        }
        override fun retireBiometricAlias(snapshot: VaultStoredSnapshot, permission: VaultBiometricAliasRetirement,
            context: VaultOperationContext): VaultBiometricAliasRetirementResult {
            context.checkActive(); aliasCalls++; events += "retire"
            return if (aliasUnknown) VaultBiometricAliasRetirementResult.UNKNOWN else VaultBiometricAliasRetirementResult.ABSENT
        }
        override fun validatePasswordInput(input: VaultCredentialInput) = Unit
        override fun hasExistingVaultAliases(context: VaultOperationContext) = false
        override fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput, context: VaultOperationContext) = error("unused")
        override fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, context: VaultOperationContext): VaultStagedVerification = error("unused")
        override fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification) = Unit
        override fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext) = snapshot.data!!.retryLedger
        override fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger, context: VaultOperationContext) = cipher(ledger.ledgerRevision.toInt())
        override fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, attempt: VaultAttemptIdentity,
            context: VaultOperationContext): VaultRootCapability {
            context.checkActive(); passwordCalls++; roots++; events += "password"; return Root(context, attempt)
        }
        override fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot, context: VaultOperationContext) = VaultEmptyCatalog(snapshot.data!!.catalogRevision)
        override fun close() = Unit
    }
    private inner class Store(val auth: Auth, var value: VaultStoredSnapshot) : VaultStorePort {
        var reservations = 0; var resets = 0; var writes = 0; var reads = 0
        var failReserve = false; var failReset = false; var unknownWrite = false
        var afterWrite: (() -> Unit)? = null
        override fun inspect(context: VaultOperationContext): VaultStoreObservation {
            context.checkActive(); reads++; auth.events += "read"; return VaultStoreObservation.Present(value)
        }
        override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite, authenticatedLedger: VaultCiphertext,
            context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); check(value == expected)
            if (write.kind == VaultRetryWriteKind.RESERVATION) { if (failReserve) throw VaultOperationException(VaultFailure.UNAVAILABLE); reservations++; auth.events += "reserve" }
            else { if (failReset) throw VaultOperationException(VaultFailure.UNAVAILABLE); resets++; auth.events += "reset" }
            value = value.copy(storeRevision = value.storeRevision + 1, data = value.data!!.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger))
            return value
        }
        override fun compareAndSwapCredentials(expected: VaultStoredSnapshot, mutation: VaultCredentialMutation, context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); check(value == expected)
            val m = mutation as Auth.Mutation
            check(!m.closed && m.before == expected && m.operation.sharesOperation(context) && context.deadlineElapsedMillis <= m.operation.deadlineElapsedMillis)
            val data = expected.data!!; val revision = data.catalogRevision + 1
            val slots = if (m.state == VaultBiometricState.PENDING) data.slots.filter { it.kind != VaultSlotKind.BIOMETRIC } +
                VaultSlotRecord("biometric", revision, VaultSlotKind.BIOMETRIC, cipher(revision.toInt()), true) else data.slots
            value = expected.copy(storeRevision = expected.storeRevision + 1, data = data.copy(slots = slots,
                catalogRevision = revision, catalogEnvelope = cipher(revision.toInt()), catalogKeysetEnvelope = cipher((revision + 1).toInt()), biometricState = m.state))
            writes++; auth.events += "write-${m.state}"; afterWrite?.invoke()
            if (unknownWrite) throw VaultOperationException(VaultFailure.INCOMPLETE)
            return value
        }
        override fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext): VaultStoredSnapshot = error("unused")
        override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext): VaultStoredSnapshot = error("unused")
        override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext): VaultStoredSnapshot = error("unused")
        override fun close() = Unit
    }
    private inner class Fixture(state: VaultBiometricState = VaultBiometricState.ACTIVE) {
        val clock = Clock(); val work = Lane(); val ui = Lane(); val timers = Timers(); val view = View(); val auth = Auth()
        val store = Store(auth, snapshot(state)); val runtime = VaultRuntime(store, auth, clock, work, ui, timers, view, 1)
        val host = Any(); val activity = Any(); var hostCurrent = true
        val factory: VaultBiometricBindingFactory = { purpose, flow -> VaultBiometricBinding(purpose, host, activity, 1, flow) { hostCurrent } }
        fun pump() { work.drain(); ui.drain() }
        fun start() { runtime.observePresence(true, true); runtime.start(); pump() }
        fun unlock() { runtime.unlockBiometric(factory(VaultBiometricPurpose.UNLOCK, null)); pump() }
        fun workflow(kind: VaultBiometricWorkflowKind): VaultBiometricWorkflow {
            val flow = checkNotNull(runtime.beginBiometricWorkflow(kind)); runtime.unlockForBiometric(password(), flow, factory); pump(); return flow
        }
    }
    @Test fun unlockCoverSurvivesReservationToQueuedPromptPreparationGap() {
        val f = Fixture(); f.start()
        f.runtime.unlockBiometric(f.factory(VaultBiometricPurpose.UNLOCK, null))
        assertTrue(f.runtime.biometricPrivateCoverRequired())
        f.work.one() // Durable reservation completed; prompt preparation has only been queued.
        assertTrue(f.auth.operations.isEmpty()); assertTrue(f.runtime.biometricPrivateCoverRequired())
        f.pump(); assertTrue(f.runtime.biometricPrivateCoverRequired())
    }
    @Test fun privateCoverStartsAfterPasswordEntryAndSurvivesPromptDrain() {
        val f = Fixture(VaultBiometricState.NONE); f.start()
        val flow = checkNotNull(f.runtime.beginBiometricWorkflow(VaultBiometricWorkflowKind.ENROLL))
        assertFalse(f.runtime.biometricPrivateCoverRequired())
        f.runtime.unlockForBiometric(password(), flow, f.factory)
        assertTrue(f.runtime.biometricPrivateCoverRequired()); f.pump()
        f.runtime.cancelBiometricWorkflow(flow); f.pump(); assertTrue(f.runtime.biometricPrivateCoverRequired())
        f.auth.operations.single().terminal(); f.pump(); assertFalse(f.runtime.biometricPrivateCoverRequired())
    }
    @Test fun focusRequiresExactCurrentSubmittedNonterminalPrompt() {
        val f = Fixture(); f.start(); f.unlock(); val op = f.auth.operations.single()
        assertTrue(f.runtime.systemPromptOwnsFocus(f.host, f.activity, 1))
        assertFalse(f.runtime.systemPromptOwnsFocus(Any(), f.activity, 1))
        op.platformSubmitted = false; assertFalse(f.runtime.systemPromptOwnsFocus(f.host, f.activity, 1))
        op.platformSubmitted = true; op.terminal(); assertFalse(f.runtime.systemPromptOwnsFocus(f.host, f.activity, 1))
    }
    @Test fun unlockReservesBeforePromptAndNeedsResetBeforeReveal() {
        val f = Fixture(); f.start(); f.unlock()
        assertEquals(1, f.store.reservations); assertEquals(0, f.store.resets); assertEquals(0, f.auth.roots)
        assertTrue(f.auth.events.indexOf("reserve") < f.auth.events.indexOf("prepare-unlock"))
        f.auth.operations.single().emit(); f.pump()
        assertEquals(1, f.store.resets); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status); assertEquals(0, f.view.shown)
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(1, f.view.shown)
    }
    @Test fun pendingOrRevokedCannotStartBiometricUnlock() {
        for (state in listOf(VaultBiometricState.NONE, VaultBiometricState.PENDING, VaultBiometricState.REVOKED)) {
            val f = Fixture(state); f.start(); f.unlock(); assertTrue(f.auth.operations.isEmpty()); assertEquals(0, f.store.reservations)
        }
    }
    @Test fun reservationFailureNeverStartsPrompt() {
        val f = Fixture(); f.start(); f.store.failReserve = true; f.unlock(); assertTrue(f.auth.operations.isEmpty())
    }
    @Test fun resetFailureNeverPublishesRoot() {
        val f = Fixture(); f.start(); f.unlock(); f.store.failReset = true; f.auth.operations.single().emit(); f.pump()
        assertNotEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status); assertEquals(1, f.auth.rootCloses); assertEquals(0, f.view.shown)
    }
    @Test fun cancelWithoutTerminalRetainsSameOwnerAndBlocksPassword() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.lock(); f.pump()
        assertFalse(f.runtime.biometricCleanupComplete()); assertFalse(f.runtime.hostCleanupComplete())
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(0, f.auth.passwordCalls)
        f.auth.operations.single().terminal(); f.pump()
        assertTrue(f.runtime.biometricCleanupComplete()); f.runtime.unlockPassword(password()); f.pump(); assertEquals(1, f.auth.passwordCalls)
    }
    @Test fun terminalWithoutCallbackReturnCannotConsumeResult() {
        val f = Fixture(); f.start(); f.unlock(); val pending = f.auth.operations.single()
        pending.emit(drain = false); f.pump(); assertEquals(0, f.store.resets); assertFalse(f.runtime.hostCleanupComplete())
        pending.terminal(); f.pump(); assertEquals(1, f.store.resets)
    }
    @Test fun hostReplacementRejectsLateSuccessAndRetiresOutput() {
        val f = Fixture(); f.start(); f.unlock(); f.hostCurrent = false; f.auth.operations.single().emit(); f.pump()
        assertEquals(0, f.store.resets); assertEquals(1, f.auth.rootCloses); assertEquals(0, f.view.shown)
    }
    @Test fun changedStoreRejectsLateSuccess() {
        val f = Fixture(); f.start(); f.unlock(); f.store.value = f.store.value.copy(storeRevision = f.store.value.storeRevision + 1)
        f.auth.operations.single().emit(); f.pump(); assertEquals(0, f.store.resets); assertEquals(1, f.auth.rootCloses)
    }
    @Test fun slotUnavailableNeverMakesGlobalKeyInvalidated() {
        val f = Fixture(); f.start(); f.unlock()
        f.auth.operations.single().emit(VaultBiometricResult.Failed(VaultFailure.UNAVAILABLE, VaultBiometricAvailability.KEY_UNAVAILABLE)); f.pump()
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status); f.runtime.unlockPassword(password()); f.pump()
        assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun enrollmentAuthenticatesPasswordStagesReadsVerifiesThenActivatesLocked() {
        val f = Fixture(VaultBiometricState.NONE); f.start(); val original = f.store.value.data!!.slots
        f.workflow(VaultBiometricWorkflowKind.ENROLL)
        assertEquals(1, f.auth.passwordCalls); assertEquals(1, f.store.resets); assertEquals(1, f.auth.aliasCalls); assertEquals(0, f.store.writes)
        f.auth.operations[0].emit(); f.pump()
        assertEquals(VaultBiometricState.PENDING, f.store.value.data!!.biometricState); assertEquals(2, f.auth.operations.size)
        assertEquals(1, f.auth.rootCloses); assertEquals(0, f.view.shown)
        assertTrue(f.auth.events.indexOf("write-PENDING") < f.auth.events.indexOf("prepare-verify"))
        f.auth.operations[1].emit(); f.pump()
        assertEquals(VaultBiometricState.ACTIVE, f.store.value.data!!.biometricState); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertEquals(VaultBiometricNotice.ENABLED, f.view.notice); assertEquals(original, f.store.value.data!!.slots.filter { it.kind != VaultSlotKind.BIOMETRIC })
        assertEquals(VaultRecoveryState.ACTIVE, f.store.value.data!!.recoveryState); assertEquals(2, f.store.writes)
    }
    @Test fun cancelledVerifyLeavesPendingAndPasswordUsableAfterDrain() {
        val f = Fixture(VaultBiometricState.NONE); f.start(); val flow = f.workflow(VaultBiometricWorkflowKind.ENROLL)
        f.auth.operations[0].emit(); f.pump(); f.runtime.cancelBiometricWorkflow(flow); f.auth.operations[1].terminal(); f.pump()
        assertEquals(VaultBiometricState.PENDING, f.store.value.data!!.biometricState); assertEquals(1, f.store.writes)
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun revokeUsesFreshPasswordAndKeepsEveryNonBiometricSlot() {
        val f = Fixture(); f.start(); val original = f.store.value.data!!.slots
        f.workflow(VaultBiometricWorkflowKind.REVOKE)
        assertEquals(1, f.auth.passwordCalls); assertTrue(f.auth.operations.isEmpty()); assertEquals(0, f.auth.aliasCalls)
        assertEquals(VaultBiometricState.REVOKED, f.store.value.data!!.biometricState); assertEquals(original, f.store.value.data!!.slots)
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
    }
    @Test fun orphanUnknownRetainsIntentBlocksEnrollmentButKeepsPassword() {
        val f = Fixture(VaultBiometricState.NONE); f.start(); f.auth.aliasUnknown = true; f.workflow(VaultBiometricWorkflowKind.ENROLL)
        assertEquals(1, f.auth.aliasCalls); assertTrue(f.auth.operations.isEmpty()); assertEquals(0, f.store.writes)
        assertEquals(VaultBiometricNotice.RETIREMENT_PENDING, f.view.notice)
        assertNull(f.runtime.beginBiometricWorkflow(VaultBiometricWorkflowKind.ENROLL))
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun synchronousCallbackBeforeStartReturnsIsRetainedAndHandledOnce() {
        val f = Fixture(); f.start(); f.auth.synchronous = true; f.unlock()
        assertEquals(1, f.store.resets); assertEquals(1, f.auth.roots); assertTrue(f.runtime.biometricCleanupComplete())
    }
    @Test fun secondPromptGetsNewBudgetWithinOriginalWorkflowDeadline() {
        val f = Fixture(VaultBiometricState.NONE); f.start(); val flow = f.workflow(VaultBiometricWorkflowKind.ENROLL)
        val first = f.auth.operations.single(); f.clock.now += 90_000; first.emit(); f.pump()
        val second = f.auth.operations[1]
        assertTrue(second.context.deadlineElapsedMillis > first.context.deadlineElapsedMillis)
        assertTrue(second.context.deadlineElapsedMillis <= flow.deadlineElapsedMillis)
        assertTrue(first.context.sharesOperation(second.context))
    }
    @Test fun expiryRejectsSuccessAtExactPromptDeadline() {
        val f = Fixture(); f.start(); f.unlock(); val op = f.auth.operations.single(); f.clock.now = op.context.deadlineElapsedMillis
        op.emit(); f.pump(); assertEquals(0, f.store.resets); assertEquals(1, f.auth.rootCloses)
    }
    @Test fun unknownStageWriteDoesNotStartVerifyOrClaimEnabled() {
        val f = Fixture(VaultBiometricState.NONE); f.start(); f.workflow(VaultBiometricWorkflowKind.ENROLL); f.store.unknownWrite = true
        f.auth.operations.single().emit(); f.pump(); assertEquals(1, f.store.writes); assertEquals(1, f.auth.operations.size)
        assertNotEquals(VaultBiometricNotice.ENABLED, f.view.notice); assertEquals(VaultBiometricState.PENDING, f.store.value.data!!.biometricState)
    }
    @Test fun lateDuplicateSuccessIsRetiredWithoutSecondReset() {
        val f = Fixture(); f.start(); f.unlock(); val op = f.auth.operations.single(); op.emit(); f.pump()
        op.emit(); f.pump(); assertEquals(1, f.store.resets); assertEquals(1, f.auth.rootCloses)
    }
}
