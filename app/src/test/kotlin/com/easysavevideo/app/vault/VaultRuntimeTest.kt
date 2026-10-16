package com.easysavevideo.app.vault

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Public deterministic metadata only; doubles are never accepted as production crypto evidence. */
class VaultRuntimeTest {
    private class Clock : VaultMonotonicClock { var now = 10L; override fun elapsedMillis() = now }
    private class Lane : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun one() { queue.removeFirst().run() }
        fun drain() { var bound = 0; while (queue.isNotEmpty()) { check(++bound < 200); one() } }
    }
    private class Timers : VaultWakeScheduler {
        class Entry(val task: () -> Unit) { var cancelled = false }
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation {
            val value = Entry(task); entries += value
            return VaultWakeCancellation { value.cancelled = true }
        }
        fun current() = entries.last { !it.cancelled }.task()
    }
    private class View : VaultPresentationPort {
        var status = VaultRuntimeStatus.LOADING
        var emptyCount = 0
        var revision: Long? = null
        var automaticCover = true
        var hidden: (() -> Unit)? = null
        override fun cover(generation: Long, onActuallyHidden: () -> Unit) {
            revision = null; hidden = onActuallyHidden
            if (automaticCover) onActuallyHidden()
        }
        override fun presentEmpty(catalogRevision: Long) { emptyCount++; revision = catalogRevision; status = VaultRuntimeStatus.EMPTY }
        override fun presentStatus(status: VaultRuntimeStatus) { this.status = status }
    }
    private fun ciphertext(size: Int = 1) = VaultCiphertext(ByteArray(size) { 7 })
    private fun data() = VaultStagedData(listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, ciphertext(), true)),
        ciphertext(), ciphertext(), 1, ciphertext(), VaultRetryLedger(0, 0, -1, null), ciphertext())
    private fun identity() = VaultPersistentIdentity("vault", "install", "operation", "esv.vault.v1.operation.device", "esv.vault.v1.operation.biometric")
    private inner class Store(initial: Boolean) : VaultStorePort {
        var value: VaultStoredSnapshot? = if (initial) VaultStoredSnapshot(identity(), VaultStorePhase.READY, 3, data()) else null
        var begins = 0; var retryWrites = 0; var failReset = false; var inspections = 0; var onInspect: (() -> Unit)? = null
        override fun inspect(context: VaultOperationContext): VaultStoreObservation {
            inspections++; onInspect?.invoke()
            return value?.let(VaultStoreObservation::Present) ?: VaultStoreObservation.Absent
        }
        override fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext): VaultStoredSnapshot {
            check(value == null); begins++
            return VaultStoredSnapshot(identity, VaultStorePhase.INITIALIZING, 1, null).also { value = it }
        }
        override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext): VaultStoredSnapshot {
            check(value == expected)
            return expected.copy(phase = VaultStorePhase.STAGED, storeRevision = 2, data = prepared).also { value = it }
        }
        override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext): VaultStoredSnapshot {
            verification.requireMatches(expected)
            return expected.copy(phase = VaultStorePhase.READY, storeRevision = 3).also { value = it }
        }
        override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite, authenticatedLedger: VaultCiphertext,
            context: VaultOperationContext): VaultStoredSnapshot {
            check(value == expected)
            if (write.kind == VaultRetryWriteKind.RESET && failReset) throw VaultOperationException(VaultFailure.UNAVAILABLE)
            retryWrites++
            return expected.copy(storeRevision = expected.storeRevision + 1,
                data = expected.data!!.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger)).also { value = it }
        }
        override fun close() = Unit
    }
    private inner class Auth : VaultAuthenticationPort {
        var aliases = false; var unlocks = 0; var closes = 0; var reads = 0; var prepared = 0; var aliasQueries = 0; var ledgerReads = 0
        var reject = false; var ledgerCorrupt = false; var onRead: (() -> Unit)? = null
        private inner class Root : VaultRootCapability { var live = true; override fun close() { if (live) { live = false; closes++ } } }
        override fun validatePasswordInput(input: VaultCredentialInput) {
            val chars = input.copyCharacters()
            try { if (chars.size < 15) throw VaultOperationException(VaultFailure.REJECTED) } finally { chars.fill('\u0000') }
        }
        override fun hasExistingVaultAliases(context: VaultOperationContext): Boolean { aliasQueries++; return aliases }
        override fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput, context: VaultOperationContext): VaultStagedData {
            prepared++; return data()
        }
        override fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, context: VaultOperationContext): VaultStagedVerification =
            object : VaultStagedVerification { override fun requireMatches(snapshot: VaultStoredSnapshot) = Unit; override fun close() = Unit }
        override fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification) = verification.requireMatches(snapshot)
        override fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultRetryLedger {
            ledgerReads++
            if (ledgerCorrupt) throw VaultOperationException(VaultFailure.CORRUPT)
            return snapshot.data!!.retryLedger
        }
        override fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger, context: VaultOperationContext) = ciphertext()
        override fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultRootCapability {
            unlocks++
            assertTrue(snapshot.data!!.retryLedger.reservedAttemptCount > 0)
            assertNotEquals(attempt.attemptId, snapshot.data.retryLedger.outstandingReservationId)
            if (reject) throw VaultOperationException(VaultFailure.REJECTED)
            return Root()
        }
        override fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultEmptyCatalog {
            check(root is Root && root.live); reads++; onRead?.invoke(); return VaultEmptyCatalog(snapshot.data!!.catalogRevision)
        }
        override fun close() = Unit
    }
    private inner class Fixture(initial: Boolean = true) {
        val clock = Clock(); val lane = Lane(); val ui = Lane(); val timers = Timers(); val view = View()
        val store = Store(initial); val auth = Auth()
        val runtime = VaultRuntime(store, auth, clock, lane, ui, timers, view, 1)
        fun pump() { lane.drain(); ui.drain() }
        fun start() { runtime.start(); runtime.observePresence(true, true); pump() }
        fun unlock() { runtime.unlockPassword(input()); pump() }
    }
    private fun input() = VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-test-only".toCharArray())

    @Test fun realConsumerRequiresReservedAuthenticationAndResetThenRead() {
        val f = Fixture(); f.start(); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        f.unlock(); assertEquals(2, f.store.retryWrites); assertEquals(1, f.auth.unlocks)
        assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status); assertEquals(0, f.view.emptyCount)
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(1, f.view.emptyCount); assertEquals(1L, f.view.revision)
    }
    @Test fun noRowsNeverBecomeEmpty() {
        val f = Fixture(false); f.start(); f.runtime.revealEmptyCatalog(); f.pump()
        assertEquals(VaultRuntimeStatus.SETUP_REQUIRED, f.view.status); assertEquals(0, f.view.emptyCount)
    }
    @Test fun setupReadsPersistedStageAndEndsLocked() {
        val f = Fixture(false); f.start(); f.runtime.setupPassword(input(), VaultRecoveryChoice.DECLINED); f.pump()
        assertEquals(VaultStorePhase.READY, f.store.value!!.phase); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertEquals(0, f.auth.unlocks); f.unlock(); f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(1, f.view.emptyCount)
    }
    @Test fun invalidSetupInputCreatesNoDurableStateAndCanRetry() {
        val f = Fixture(false); f.start()
        f.runtime.setupPassword(VaultCredentialInput(VaultCredentialKind.PASSWORD, "short".toCharArray()), VaultRecoveryChoice.DECLINED); f.pump()
        assertEquals(0, f.store.begins); assertEquals(0, f.auth.prepared)
        f.runtime.setupPassword(input(), VaultRecoveryChoice.DECLINED); f.pump(); assertEquals(1, f.store.begins)
    }
    @Test fun recoveryOptInCommitsIndependentPasswordWithoutClaimingCodeEnabled() {
        val f = Fixture(false); f.start(); f.runtime.setupPassword(input(), VaultRecoveryChoice.OPTED_IN); f.pump()
        assertEquals(1, f.store.begins); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertEquals(VaultStorePhase.READY, f.store.value!!.phase)
        assertEquals(VaultRecoveryState.NONE, f.store.value!!.data!!.recoveryState)
        f.unlock(); assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status)
    }
    @Test fun existingAliasBlocksFirstUse() {
        val f = Fixture(false); f.auth.aliases = true; f.start()
        f.runtime.setupPassword(input(), VaultRecoveryChoice.DECLINED); f.pump(); assertEquals(0, f.store.begins)
    }
    @Test fun stagedRestartDoesNotCreateNewRoot() {
        val f = Fixture(); f.store.value = f.store.value!!.copy(phase = VaultStorePhase.STAGED); f.start()
        assertEquals(VaultRuntimeStatus.UNAVAILABLE, f.view.status); assertEquals(0, f.auth.prepared)
    }
    @Test fun missingAuthenticatedLedgerNeverTriesPassword() {
        val f = Fixture(); f.auth.ledgerCorrupt = true; f.start(); f.unlock(); assertEquals(0, f.auth.unlocks)
    }
    @Test fun resetFailureDiscardsQuarantinedRoot() {
        val f = Fixture(); f.store.failReset = true; f.start(); f.unlock()
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(0, f.view.emptyCount); assertEquals(1, f.auth.closes)
    }
    @Test fun rejectedCredentialStillConsumesReservation() {
        val f = Fixture(); f.auth.reject = true; f.start(); f.unlock()
        assertEquals(1L, f.store.value!!.data!!.retryLedger.reservedAttemptCount); assertEquals(0, f.view.emptyCount)
    }
    @Test fun queuedReadCannotStartAfterPresentationRevocation() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        f.runtime.revealEmptyCatalog(); f.runtime.observePresence(false, true); f.pump()
        assertEquals(0, f.auth.reads); assertEquals(0, f.view.emptyCount); assertEquals(0, f.auth.closes)
    }
    @Test fun lateUiReadCannotPublishAfterLock() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.revealEmptyCatalog(); f.lane.drain()
        f.runtime.lock(); f.pump(); assertEquals(0, f.view.emptyCount); assertEquals(1, f.auth.closes)
    }
    @Test fun lockDuringReadRejectsItsResultAndRetainsWorkerUntilReturned() {
        val f = Fixture(); f.start(); f.unlock()
        f.auth.onRead = { f.runtime.lock(); assertEquals(0, f.auth.closes) }
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(0, f.view.emptyCount); assertEquals(1, f.auth.closes)
    }
    @Test fun gracePreservesRootButNeverRevivesOldPresentationLease() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(1, f.view.emptyCount)
        f.runtime.observePresence(false, true); f.pump(); assertNull(f.view.revision); assertEquals(0, f.auth.closes)
        f.runtime.observePresence(true, true); f.pump(); assertEquals(1, f.view.emptyCount)
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(2, f.view.emptyCount)
    }
    @Test fun staleCancelledTimerCannotCancelNewAuthentication() {
        val f = Fixture(); f.start(); val old = f.timers.entries.first(); assertTrue(old.cancelled)
        f.runtime.unlockPassword(input()); old.task(); f.pump()
        assertEquals(VaultRuntimeStatus.READY_TO_REVEAL, f.view.status); assertEquals(1, f.auth.unlocks)
    }
    @Test fun actualCoverAcknowledgementIsRequired() {
        val f = Fixture(); f.view.automaticCover = false; f.start(); f.unlock(); assertEquals(0, f.auth.unlocks)
        f.view.hidden!!.invoke(); f.pump(); f.unlock(); assertEquals(1, f.auth.unlocks)
    }
    @Test fun cleanupDeadlineDoesNotTurnCancellationIntoRelease() {
        val f = Fixture(); f.start(); f.unlock()
        f.auth.onRead = {
            f.runtime.lock(); f.clock.now += VaultRuntimeLimits.CLEANUP_MILLIS + 1
            f.timers.current(); assertEquals(0, f.auth.closes)
        }
        f.runtime.revealEmptyCatalog(); f.pump(); assertEquals(VaultRuntimeStatus.UNAVAILABLE, f.view.status)
        f.runtime.unlockPassword(input()); f.pump(); assertEquals(1, f.auth.unlocks)
    }
    @Test fun immutableEnvelopeAndSlotsDoNotAcceptCallerMutation() {
        val bytes = byteArrayOf(1); val envelope = VaultCiphertext(bytes); bytes[0] = 9; assertEquals(1, envelope.copyBytes()[0].toInt())
        val slots = mutableListOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, ciphertext(), true))
        val value = data().copy(slots = slots); slots.clear(); assertEquals(1, value.slots.size)
    }
    @Test fun contextsNeverExtendParentDeadlineAndShareCancellation() {
        val clock = Clock(); val parent = VaultOperationContext(100, clock); val child = parent.bounded(1_000)
        assertEquals(100, child.deadlineElapsedMillis); parent.cancel()
        assertThrows(VaultOperationException::class.java) { child.checkActive() }
    }
    @Test fun oversizedInputsRejectedBeforeDefensiveCopies() {
        assertThrows(IllegalArgumentException::class.java) { VaultCredentialInput(VaultCredentialKind.PIN, CharArray(13)) }
        assertThrows(IllegalArgumentException::class.java) { VaultCredentialInput(VaultCredentialKind.PASSWORD, CharArray(257)) }
        assertThrows(IllegalArgumentException::class.java) { VaultCiphertext(byteArrayOf(1), Int.MAX_VALUE) }
    }

    @Test fun policySelectionBeforeStartDoesNoStoreOrAuthenticationWork() {
        val f = Fixture(false)
        assertNull(f.runtime.acceptedAutoLockPolicy())
        f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_5_MINUTES)
        f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES, f.runtime.acceptedAutoLockPolicy())
        assertEquals(0, f.store.inspections); assertEquals(0, f.store.begins); assertEquals(0, f.store.retryWrites)
        assertEquals(0, f.auth.aliasQueries); assertEquals(0, f.auth.ledgerReads)
        assertEquals(0, f.auth.prepared); assertEquals(0, f.auth.unlocks); assertEquals(0, f.timers.entries.size)
    }
    @Test fun successfulLoadBeforeStartRetainsPolicyAndStartupStaysLocked() {
        val f = Fixture()
        assertEquals(VaultAutoLockPolicy.AFTER_5_MINUTES,
            f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_5_MINUTES))
        f.start(); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        assertEquals(VaultAutoLockPolicy.AFTER_5_MINUTES, f.runtime.acceptedAutoLockPolicy())
        assertEquals(0, f.auth.unlocks); assertEquals(0, f.auth.reads); assertEquals(0, f.store.retryWrites)
        f.unlock(); f.runtime.observePresence(false, true); f.pump()
        f.clock.now += 60_000; f.runtime.observePresence(false, true); f.pump(); assertEquals(0, f.auth.closes)
        f.clock.now += 240_000; f.runtime.observePresence(true, true); f.pump(); assertEquals(1, f.auth.closes)
    }
    @Test fun loadDuringPendingInspectAndEditDuringStartupUseLatestAcceptedPolicy() {
        val f = Fixture(); f.runtime.start()
        f.store.onInspect = {
            f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
            f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        }
        f.runtime.observePresence(true, true); f.pump(); f.store.onInspect = null
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES, f.runtime.acceptedAutoLockPolicy())
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        f.unlock(); f.runtime.observePresence(false, true); f.pump()
        f.clock.now += 300_000; f.runtime.observePresence(false, true); f.pump(); assertEquals(0, f.auth.closes)
    }
    @Test fun editBeforeStartAndReplayedActivityLoadCannotRollBack() {
        val f = Fixture(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES,
            f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.IMMEDIATE))
        f.start(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_5_MINUTES)
        assertEquals(VaultAutoLockPolicy.AFTER_5_MINUTES,
            f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_1_MINUTE))
    }
    @Test fun lateSuccessfulLoadInitializesExistingStateWithoutUnlocking() {
        val f = Fixture(); f.start(); assertNull(f.runtime.acceptedAutoLockPolicy())
        f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_1_MINUTE); f.pump()
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status); assertEquals(0, f.auth.unlocks)
        assertEquals(1, f.store.inspections); assertEquals(1, f.auth.ledgerReads); assertEquals(0, f.store.retryWrites)
    }
    @Test fun lateLoadCannotReviveSessionLockedByUninitializedImmediateDefault() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.observePresence(false, true); f.pump()
        assertEquals(1, f.auth.closes)
        f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        f.runtime.observePresence(true, true); f.runtime.revealEmptyCatalog(); f.pump()
        assertEquals(VaultRuntimeStatus.LOCKED, f.view.status); assertEquals(0, f.auth.reads)
    }
    @Test fun processRestartRestoresOnlyEnumAndRequiresNewUnlock() {
        val old = Fixture(); old.start(); old.unlock(); old.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        val restored = checkNotNull(old.runtime.acceptedAutoLockPolicy())
        val fresh = Fixture(); fresh.runtime.initializeAutoLockPolicy(restored); fresh.start()
        fresh.runtime.revealEmptyCatalog(); fresh.pump()
        assertEquals(VaultRuntimeStatus.LOCKED, fresh.view.status)
        assertEquals(0, fresh.auth.unlocks); assertEquals(0, fresh.auth.reads); assertEquals(0, fresh.store.retryWrites)
    }
    @Test fun repeatedSelectionWhilePresentDoesNotInspectAuthenticateOrStartAbsentTimer() {
        val f = Fixture(); f.start(); f.unlock()
        val inspections = f.store.inspections; val writes = f.store.retryWrites
        val aliases = f.auth.aliasQueries; val ledgers = f.auth.ledgerReads; val timers = f.timers.entries.size
        repeat(3) { f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_5_MINUTES) }; f.pump()
        assertEquals(inspections, f.store.inspections); assertEquals(writes, f.store.retryWrites)
        assertEquals(aliases, f.auth.aliasQueries); assertEquals(ledgers, f.auth.ledgerReads)
        assertEquals(1, f.auth.unlocks); assertEquals(0, f.auth.prepared); assertEquals(0, f.store.begins)
        assertEquals(timers, f.timers.entries.size)
    }
    @Test fun shorteningRunningWindowPastOriginalDeadlineLocksImmediately() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        f.runtime.observePresence(false, true); f.pump(); f.clock.now += 60_001
        f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE); f.pump()
        assertEquals(1, f.auth.closes); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
    }
    @Test fun relaxationAtOrAfterOldExpiryNeverRevivesSession() {
        for (elapsed in listOf(60_000L, 60_001L)) {
            val f = Fixture(); f.start(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
            f.runtime.observePresence(false, true); f.pump(); f.clock.now += elapsed
            f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES); f.pump()
            assertEquals(1, f.auth.closes); assertEquals(VaultRuntimeStatus.LOCKED, f.view.status)
        }
    }
    @Test fun selectedGraceCancelsOnlyBeforeExactDeadlineReturn() {
        for (elapsed in listOf(59_999L, 60_000L)) {
            val f = Fixture(); f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
            f.start(); f.unlock(); f.runtime.observePresence(true, false); f.pump(); f.clock.now += elapsed
            f.runtime.observePresence(true, true); f.pump()
            assertEquals(if (elapsed < 60_000) 0 else 1, f.auth.closes)
            if (elapsed < 60_000) {
                f.clock.now += 900_000; f.runtime.observePresence(true, true); f.pump(); assertEquals(0, f.auth.closes)
            }
        }
    }
    @Test fun closedOwnerCannotAuthorizePersistenceOrAcceptSelection() {
        val f = Fixture(); f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        f.runtime.close(); f.pump(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_15_MINUTES)
        assertNull(f.runtime.acceptedAutoLockPolicy()); assertNull(f.runtime.initializeAutoLockPolicy(VaultAutoLockPolicy.IMMEDIATE))
    }

    @Test fun relaxingBeforeExpiryKeepsOriginalDepartureAnchor() {
        val f = Fixture(); f.start(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        f.runtime.observePresence(false, true); f.pump(); f.clock.now += 30_000
        f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_5_MINUTES); f.pump()
        f.clock.now += 269_999; f.runtime.observePresence(false, true); f.pump(); assertEquals(0, f.auth.closes)
        f.clock.now += 1; f.runtime.observePresence(true, true); f.pump(); assertEquals(1, f.auth.closes)
    }
}
