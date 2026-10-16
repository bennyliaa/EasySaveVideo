package com.easysavevideo.app.vault

import com.easysavevideo.core.model.*
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** Fixed public arrays and fake ports only. These tests never execute production crypto, entropy, Keystore or SQL. */
class VaultRecoveryRuntimeTest {
    private val publicCode = CharArray(43) { 'A' }
    private fun code() = VaultRecoveryInput(publicCode)
    private fun password() = VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-password-fixture".toCharArray())
    private fun cipher(value: Int = 1) = VaultCiphertext(byteArrayOf(value.toByte()))
    private fun initial(active: Boolean) = VaultStoredSnapshot(
        VaultPersistentIdentity("vault", "install", "operation", "esv.vault.v1.operation.device", "esv.vault.v1.operation.biometric"),
        VaultStorePhase.READY, 3, VaultStagedData(
            listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(), true)) +
                if (active) listOf(VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, cipher(2), true)) else emptyList(),
            cipher(), cipher(), if (active) 3 else 1, cipher(), VaultRetryLedger(0, 0, -1, null), cipher(),
            if (active) VaultRecoveryState.ACTIVE else VaultRecoveryState.NONE))
    private class Clock : VaultMonotonicClock {
        var now = 10L; var reads = 0; var expireAtRead = -1; var expiry = 0L
        override fun elapsedMillis(): Long { reads++; if (reads == expireAtRead) now = expiry; return now }
    }
    private class Lane : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun one() = queue.removeFirst().run()
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(++count < 300); one() } }
    }
    private class Timers : VaultWakeScheduler {
        class Entry(val task: () -> Unit) { var cancelled = false }
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation {
            val entry = Entry(task); entries += entry
            return VaultWakeCancellation { entry.cancelled = true }
        }
    }
    private class View : VaultPresentationPort {
        var status = VaultRuntimeStatus.LOADING; var empty = 0
        var state = VaultRecoveryState.NONE; var notice = VaultRecoveryNotice.NONE
        override fun cover(generation: Long, onActuallyHidden: () -> Unit) = onActuallyHidden()
        override fun presentEmpty(catalogRevision: Long) { empty++ }
        override fun presentStatus(status: VaultRuntimeStatus) { this.status = status }
        override fun presentRecoveryState(state: VaultRecoveryState, pendingPassword: Boolean, notice: VaultRecoveryNotice) {
            this.state = state; this.notice = notice
        }
    }
    private inner class Auth : VaultAuthenticationPort {
        var roots = 0; var rootCloses = 0; var enrollmentCalls = 0; var pendingChecks = 0
        var activationCalls = 0; var resetPreparations = 0; var resetChecks = 0
        var rejectCode = false
        inner class Root : VaultRootCapability { var live = true; override fun close() { if (live) { live = false; rootCloses++ } } }
        inner class Mutation(val before: VaultStoredSnapshot, val proposal: VaultCredentialMutationProposal,
            val operation: VaultOperationContext) : VaultCredentialMutation {
            var live = true
            override fun close() { live = false; registry.remove(this) }
        }
        val registry = IdentityHashMap<VaultCredentialMutation, Mutation>()
        private fun mutation(before: VaultStoredSnapshot, kind: VaultCredentialMutationKind, target: VaultStagedData,
            context: VaultOperationContext, attempt: VaultAttemptIdentity? = null): VaultCredentialMutation =
            Mutation(before, VaultCredentialMutationProposal(kind, target, attempt, "public-${kind.name}-${before.storeRevision}"), context)
                .also { registry[it] = it }
        private fun verifyCode(input: VaultRecoveryInput) {
            val chars = input.copyCharacters()
            try { if (rejectCode || !chars.contentEquals(publicCode)) throw VaultOperationException(VaultFailure.REJECTED) }
            finally { chars.fill('\u0000') }
        }
        override fun validatePasswordInput(input: VaultCredentialInput) { input.copyCharacters().also { require(it.size >= 15); it.fill('\u0000') } }
        override fun hasExistingVaultAliases(context: VaultOperationContext) = false
        override fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput, context: VaultOperationContext) = initial(false).data!!
        override fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, context: VaultOperationContext) =
            object : VaultStagedVerification { override fun requireMatches(snapshot: VaultStoredSnapshot) = Unit; override fun close() = Unit }
        override fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification) = Unit
        override fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext) = snapshot.data!!.retryLedger
        override fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger, context: VaultOperationContext) = cipher(ledger.ledgerRevision.toInt())
        override fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultRootCapability {
            context.checkActive(); roots++; return Root()
        }
        override fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultEmptyCatalog {
            check(root is Root && root.live); return VaultEmptyCatalog(snapshot.data!!.catalogRevision)
        }
        override fun prepareRecoveryEnrollment(root: VaultRootCapability, snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultRecoveryEnrollment {
            enrollmentCalls++; check(root is Root && root.live)
            val before = snapshot.data!!; check(before.recoveryState != VaultRecoveryState.ACTIVE)
            val next = before.catalogRevision + 1
            val slot = VaultSlotRecord("recovery-v1", next, VaultSlotKind.RECOVERY, cipher(next.toInt()), true)
            val data = before.copy(slots = before.slots.filter { it.kind != VaultSlotKind.RECOVERY } + slot,
                catalogRevision = next, catalogEnvelope = cipher(next.toInt()), recoveryState = VaultRecoveryState.PENDING)
            return VaultRecoveryEnrollment(mutation(snapshot, VaultCredentialMutationKind.STAGE_RECOVERY, data, context), code())
        }
        override fun verifyPendingRecovery(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput, context: VaultOperationContext) {
            pendingChecks++; check(snapshot.data!!.recoveryState == VaultRecoveryState.PENDING); verifyCode(input)
        }
        override fun prepareRecoveryActivation(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput, context: VaultOperationContext): VaultCredentialMutation {
            activationCalls++; check(snapshot.data!!.recoveryState == VaultRecoveryState.PENDING); verifyCode(input)
            return mutation(snapshot, VaultCredentialMutationKind.ACTIVATE_RECOVERY,
                snapshot.data.copy(recoveryState = VaultRecoveryState.ACTIVE, catalogRevision = snapshot.data.catalogRevision + 1,
                    catalogEnvelope = cipher(5)), context)
        }
        override fun preparePasswordReset(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput, password: VaultCredentialInput,
            attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultCredentialMutation {
            resetPreparations++; check(snapshot.data!!.recoveryState == VaultRecoveryState.ACTIVE); verifyCode(input)
            val data = snapshot.data; val slot = data.slots.first { it.kind == VaultSlotKind.PASSWORD }
                .copy(generation = data.catalogRevision + 1, envelope = cipher(9))
            return mutation(snapshot, VaultCredentialMutationKind.STAGE_PASSWORD_RESET,
                data.copy(catalogRevision = data.catalogRevision + 1, catalogEnvelope = cipher(6),
                    pendingPassword = VaultPendingPassword(slot, attempt, data.retryLedger)), context, attempt)
        }
        override fun verifyPendingPasswordReset(snapshot: VaultStoredSnapshot, password: VaultCredentialInput,
            attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultCredentialMutation {
            resetChecks++; val data = snapshot.data!!; val pending = data.pendingPassword!!
            check(pending.attempt == attempt && pending.reservation == data.retryLedger)
            return mutation(snapshot, VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET,
                data.copy(slots = listOf(pending.slot), recoveryState = VaultRecoveryState.NONE, pendingPassword = null,
                    catalogRevision = data.catalogRevision + 1, catalogEnvelope = cipher(7)), context, attempt)
        }
        override fun validateCredentialMutation(snapshot: VaultStoredSnapshot, mutation: VaultCredentialMutation,
            context: VaultOperationContext): VaultCredentialMutationProposal {
            val owned = registry[mutation] ?: throw VaultOperationException(VaultFailure.REJECTED)
            owned.operation.checkActive(); context.checkActive()
            check(owned.live && owned.before == snapshot && owned.operation.sharesOperation(context) &&
                context.deadlineElapsedMillis <= owned.operation.deadlineElapsedMillis)
            return owned.proposal
        }
        override fun close() = Unit
    }
    private inner class Store(val auth: Auth, var value: VaultStoredSnapshot) : VaultStorePort {
        var reads = 0; var credentialWrites = 0; var ordinaryResets = 0; var credentialResets = 0
        var unknownAfterCredential = false; var afterCredential: (() -> Unit)? = null
        override fun inspect(context: VaultOperationContext): VaultStoreObservation { context.checkActive(); reads++; return VaultStoreObservation.Present(value) }
        override fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext) = error("not used")
        override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext) = error("not used")
        override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext) = error("not used")
        override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite, authenticatedLedger: VaultCiphertext,
            context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); check(value == expected)
            if (write.kind == VaultRetryWriteKind.RESET) ordinaryResets++
            value = expected.copy(storeRevision = expected.storeRevision + 1,
                data = expected.data!!.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger))
            return value
        }
        override fun compareAndSwapCredentials(expected: VaultStoredSnapshot, mutation: VaultCredentialMutation,
            context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); check(value == expected)
            val proposal = auth.validateCredentialMutation(expected, mutation, context)
            check(proposal.kind != VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
            credentialWrites++
            value = expected.copy(storeRevision = expected.storeRevision + 1, data = proposal.data)
            afterCredential?.invoke()
            if (unknownAfterCredential) throw VaultOperationException(VaultFailure.INCOMPLETE)
            return value
        }
        override fun compareAndSwapCredentialReset(expected: VaultStoredSnapshot, write: VaultRetryWrite,
            authenticatedLedger: VaultCiphertext, mutation: VaultCredentialMutation, context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); check(value == expected)
            val proposal = auth.validateCredentialMutation(expected, mutation, context)
            check(proposal.kind == VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET && proposal.attempt == write.identity.attempt)
            credentialResets++
            value = expected.copy(storeRevision = expected.storeRevision + 1,
                data = proposal.data.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger))
            return value
        }
        override fun close() = Unit
    }
    private inner class Fixture(snapshot: VaultStoredSnapshot = initial(false)) {
        val clock = Clock(); val work = Lane(); val ui = Lane(); val timers = Timers(); val view = View(); val auth = Auth()
        val store = Store(auth, snapshot)
        val runtime = VaultRuntime(store, auth, clock, work, ui, timers, view, 1)
        fun pump() { work.drain(); ui.drain() }
        fun start() { runtime.observePresence(true, true); runtime.start(); pump() }
        fun enroll(): VaultRecoveryWorkflow {
            val flow = requireNotNull(runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL))
            runtime.unlockForRecovery(password(), flow); pump(); return flow
        }
        fun confirmation(flow: VaultRecoveryWorkflow) {
            val display = requireNotNull(runtime.takeRecoveryDisplay(flow)); display.close()
            assertTrue(runtime.advanceRecoveryConfirmation(flow)); assertTrue(runtime.coverForRecoveryHandoff(flow)); pump()
        }
    }
    @Test fun enrollmentPersistsPendingBeforeDisplayThenRequiresDistinctReentry() {
        val f = Fixture(); f.start(); val flow = f.enroll()
        assertEquals(VaultRecoveryState.PENDING, f.store.value.data!!.recoveryState)
        assertEquals(1, f.auth.pendingChecks); assertEquals(1, f.auth.rootCloses)
        assertEquals(VaultRuntimeStatus.RECOVERY_CODE_READY, f.view.status); assertEquals(0, f.auth.activationCalls)
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(0, f.view.empty)
        f.confirmation(flow); f.runtime.confirmRecovery(flow, code()); f.pump()
        assertEquals(VaultRecoveryState.ACTIVE, f.store.value.data!!.recoveryState)
        assertEquals(VaultRecoveryNotice.CODE_ENABLED, f.view.notice); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
    }
    @Test fun cancellingPendingDisplayLeavesPrimaryUsableAndNeverClaimsEnabled() {
        val f = Fixture(); f.start(); val flow = f.enroll()
        val primary = f.store.value.data!!.slots.first()
        f.runtime.cancelRecoveryWorkflow(flow); f.pump()
        assertEquals(VaultRecoveryState.PENDING, f.store.value.data!!.recoveryState)
        assertEquals(primary, f.store.value.data!!.slots.first()); assertEquals(VaultRecoveryNotice.NONE, f.view.notice)
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun staleConfirmationQueuedThenCoveredDoesNotReadOrActivate() {
        val f = Fixture(); f.start(); val flow = f.enroll(); f.confirmation(flow)
        val reads = f.store.reads; val code = code()
        f.runtime.confirmRecovery(flow, code); f.runtime.surfaceCovered(); f.pump()
        assertEquals(reads, f.store.reads); assertEquals(0, f.auth.activationCalls)
        assertThrows(VaultOperationException::class.java) { code.copyCharacters() }
        assertEquals(VaultRecoveryState.PENDING, f.store.value.data!!.recoveryState)
    }
    @Test fun queuedEnrollmentThenExternalCoverLocksAndPrimaryCanUnlockAgain() {
        val f = Fixture(); f.start()
        val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL))
        f.runtime.unlockForRecovery(password(), flow)
        f.work.one(); f.work.one(); f.work.one() // Enrollment itself has not entered its worker.
        f.runtime.surfaceCovered(); f.pump()
        assertEquals(0, f.auth.enrollmentCalls); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertEquals(1, f.auth.rootCloses)
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun externalCoverImmediatelyAfterStagePreservesDurablePendingButRevokesOldRoot() {
        val f = Fixture(); f.start(); f.store.afterCredential = { f.runtime.surfaceCovered() }
        f.enroll(); f.store.afterCredential = null
        assertEquals(VaultRecoveryState.PENDING, f.store.value.data!!.recoveryState)
        assertEquals(1, f.auth.rootCloses); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertNull(f.runtime.currentRecoveryDisplayWorkflow())
        f.runtime.unlockPassword(password()); f.pump(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun contextExpiryBetweenAdmissionAndSubmissionClosesReentry() {
        val f = Fixture(); f.start(); val flow = f.enroll(); f.confirmation(flow)
        f.clock.expireAtRead = f.clock.reads + 2; f.clock.expiry = flow.deadlineElapsedMillis
        val code = code(); f.runtime.confirmRecovery(flow, code); f.pump()
        assertThrows(VaultOperationException::class.java) { code.copyCharacters() }
        assertEquals(0, f.auth.activationCalls)
    }
    @Test fun cancelledOldWorkflowTimerCannotRetireNewWorkflow() {
        val f = Fixture(); f.start()
        val old = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL))
        val oldTimer = f.timers.entries.last()
        f.runtime.cancelRecoveryWorkflow(old); f.pump()
        val next = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL))
        oldTimer.task(); f.pump()
        assertTrue(f.runtime.recoveryWorkflowCurrent(next))
    }
    @Test fun displayAndConfirmationShareOriginalDeadlineAndCodeIsSingleTransfer() {
        val f = Fixture(); f.start(); val flow = f.enroll()
        val deadline = f.runtime.recoveryPromptDeadline(flow)
        requireNotNull(f.runtime.takeRecoveryDisplay(flow)).close(); assertNull(f.runtime.takeRecoveryDisplay(flow))
        f.clock.now += 20; assertTrue(f.runtime.advanceRecoveryConfirmation(flow))
        assertEquals(deadline, f.runtime.recoveryPromptDeadline(flow))
        f.clock.now = deadline; val code = code(); f.runtime.confirmRecovery(flow, code); f.pump()
        assertEquals(0, f.auth.activationCalls); assertThrows(VaultOperationException::class.java) { code.copyCharacters() }
    }
    @Test fun resetStagesActualPendingPasswordThenAtomicallyReplacesAndStaysLocked() {
        val f = Fixture(initial(true)); f.start()
        val old = f.store.value.data!!.slots
        val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
        f.runtime.resetPassword(flow, code(), password())
        f.work.one() // Reservation ACK schedules only the restricted recovery worker.
        f.runtime.revealEmptyCatalog(); assertEquals(0, f.view.empty)
        f.work.one() // Stage, persisted re-read and new-password verification; final RESET is queued.
        assertEquals(old, f.store.value.data!!.slots); assertNotNull(f.store.value.data!!.pendingPassword)
        assertEquals(1L, f.store.value.data!!.retryLedger.reservedAttemptCount)
        f.runtime.revealEmptyCatalog(); f.pump()
        assertEquals(1, f.store.credentialResets); assertEquals(0, f.store.ordinaryResets)
        assertEquals(VaultRecoveryState.NONE, f.store.value.data!!.recoveryState); assertNull(f.store.value.data!!.pendingPassword)
        assertEquals(0L, f.store.value.data!!.retryLedger.reservedAttemptCount)
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status); assertEquals(0, f.auth.roots); assertEquals(0, f.view.empty)
        assertEquals(VaultRecoveryNotice.PASSWORD_RESET, f.view.notice)
    }
    @Test fun cancellationBeforeFinalResetNeverFallsBackToOrdinaryLedgerReset() {
        val f = Fixture(initial(true)); f.start()
        val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
        f.runtime.resetPassword(flow, code(), password()); f.work.one(); f.work.one()
        f.runtime.cancelRecoveryWorkflow(flow); f.pump()
        assertEquals(0, f.store.ordinaryResets); assertEquals(0, f.store.credentialResets)
        assertEquals(VaultRecoveryState.ACTIVE, f.store.value.data!!.recoveryState)
        assertEquals(1L, f.store.value.data!!.retryLedger.reservedAttemptCount); assertEquals(0, f.view.empty)
    }
    @Test fun rejectedRecoveryConsumesReservationButNeverCreatesPasswordCandidate() {
        val f = Fixture(initial(true)); f.start(); f.auth.rejectCode = true
        val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
        f.runtime.resetPassword(flow, code(), password()); f.pump()
        assertEquals(1L, f.store.value.data!!.retryLedger.reservedAttemptCount)
        assertNull(f.store.value.data!!.pendingPassword); assertEquals(0, f.store.credentialResets)
    }
    @Test fun restartWithPendingPasswordCanRetryViaStillActiveRecoveryWithoutOldPassword() {
        val f = Fixture(initial(true)); f.start()
        val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
        f.runtime.resetPassword(flow, code(), password()); f.work.one(); f.work.one()
        val staged = f.store.value
        f.runtime.cancelRecoveryWorkflow(flow); f.pump()
        val restarted = Fixture(staged); restarted.start()
        val next = requireNotNull(restarted.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
        restarted.runtime.resetPassword(next, code(), password()); restarted.pump()
        assertEquals(1, restarted.store.credentialResets); assertEquals(0, restarted.auth.roots)
        assertEquals(VaultRecoveryNotice.PASSWORD_RESET, restarted.view.notice)
    }
    @Test fun unknownPendingCommitNeverClaimsEnabledAndRestartUsesPrimary() {
        val f = Fixture(); f.start(); f.store.unknownAfterCredential = true
        f.enroll()
        assertEquals(VaultRuntimeStatus.UNAVAILABLE, f.view.status)
        assertEquals(VaultRecoveryState.PENDING, f.store.value.data!!.recoveryState)
        assertNotEquals(VaultRecoveryNotice.CODE_ENABLED, f.view.notice)
        val restarted = Fixture(f.store.value); restarted.start(); restarted.runtime.unlockPassword(password()); restarted.pump()
        assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, restarted.view.status)
    }
}
