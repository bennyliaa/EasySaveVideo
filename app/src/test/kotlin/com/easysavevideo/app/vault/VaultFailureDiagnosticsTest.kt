package com.easysavevideo.app.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.easysavevideo.app.vault.crypto.AndroidVaultAuthentication
import com.easysavevideo.app.vault.crypto.VaultAadPurpose
import com.easysavevideo.app.vault.crypto.VaultCryptoWire
import com.easysavevideo.core.model.*
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

/** Actual auth ledger + runtime code, fake existing-key/Aead/SQL/presentation platform.
 * Public fixed bytes only: no KeyStore, entropy, setup, password KDF, recovery unwrap or device execution.
 */
class VaultFailureDiagnosticsTest {
    private enum class Fault(val category: VaultFailure, val reason: VaultFailureReason) {
        MISSING_KEY(VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailureReason.DEVICE_KEY_UNAVAILABLE),
        LOOKUP_INVALIDATED(VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailureReason.DEVICE_KEY_UNAVAILABLE),
        MARKER_KEY_INVALIDATED(VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailureReason.DEVICE_KEY_UNAVAILABLE),
        LEDGER_KEY_INVALIDATED(VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailureReason.DEVICE_KEY_UNAVAILABLE),
        MARKER_TAG(VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT, VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT),
        MARKER_MISMATCH(VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT, VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT),
        LEDGER_TAG(VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT, VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT),
        LEDGER_MISMATCH(VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT, VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT),
        UNKNOWN_LOOKUP(VaultFailure.UNAVAILABLE, VaultFailureReason.UNKNOWN),
        UNKNOWN_MARKER(VaultFailure.UNAVAILABLE, VaultFailureReason.UNKNOWN),
        UNKNOWN_LEDGER(VaultFailure.UNAVAILABLE, VaultFailureReason.UNKNOWN),
    }
    private fun cipher(value: Int) = VaultCiphertext(ByteArray(28) { value.toByte() })
    private fun initial() = VaultStoredSnapshot(
        VaultPersistentIdentity("vault", "install", "operation", "esv.vault.v1.operation.device", "esv.vault.v1.operation.biometric"),
        VaultStorePhase.READY, 3, VaultStagedData(listOf(
            VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true),
            VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, cipher(2), true)),
            cipher(3), cipher(4), 3, cipher(5), VaultRetryLedger(0, 0, -1, null), cipher(6), VaultRecoveryState.ACTIVE))
    private fun password() = VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-password-fixture".toCharArray())
    private fun code() = VaultRecoveryInput(CharArray(43) { 'A' })
    private class Clock : VaultMonotonicClock { var now = 10L; override fun elapsedMillis() = now }
    private class Lane : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(++count < 300); queue.removeFirst().run() } }
    }
    private class Timers : VaultWakeScheduler {
        override fun schedule(delayMillis: Long, task: () -> Unit) = VaultWakeCancellation { }
    }
    private class View : VaultPresentationPort {
        var rendered = VaultRenderedState(VaultRuntimeStatus.LOADING)
        var reveals = 0
        override fun cover(generation: Long, onActuallyHidden: () -> Unit) {
            // Same cover contract as the production owner: previous rendered metadata is discarded.
            rendered = VaultRenderedState(VaultRuntimeStatus.LOCKED, coverGeneration = generation)
            onActuallyHidden()
        }
        override fun presentEmpty(catalogRevision: Long) { reveals++ }
        override fun presentCatalog(revision: Long, items: List<VaultMediaRow>) { reveals++ }
        override fun presentStatus(status: VaultRuntimeStatus) { rendered = rendered.copy(status = status, failureReason = null) }
        override fun presentFailure(reason: VaultFailureReason) {
            rendered = rendered.copy(status = VaultRuntimeStatus.UNAVAILABLE, revision = null, items = emptyList(), failureReason = reason)
        }
        override fun presentRecoveryState(state: VaultRecoveryState, pendingPassword: Boolean, notice: VaultRecoveryNotice) {
            rendered = rendered.copy(recoveryState = state, pendingPassword = pendingPassword, recoveryNotice = notice)
        }
    }
    private inner class FakeDevice(private val snapshot: () -> VaultStoredSnapshot) {
        var fault: Fault? = null
        var lookupCategory: VaultFailure? = null
        var lookups = 0; var markers = 0; var ledgers = 0; var seals = 0
        val returned = mutableListOf<ByteArray>()
        fun existing(alias: String, requireHardware: Boolean): Aead {
            assertEquals(snapshot().identity.deviceKeyAlias, alias); assertFalse(requireHardware); lookups++
            lookupCategory?.let { throw VaultOperationException(it) }
            when (fault) {
                Fault.MISSING_KEY -> throw VaultOperationException(VaultFailure.DEVICE_KEY_UNAVAILABLE)
                Fault.LOOKUP_INVALIDATED -> throw KeyPermanentlyInvalidatedException()
                Fault.UNKNOWN_LOOKUP -> throw GeneralSecurityException("device key missing: untrusted diagnostic text")
                else -> Unit
            }
            return object : Aead {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                    seals++; return cipher(6).copyBytes()
                }
                override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                    val current = snapshot()
                    val marker = associatedData.contentEquals(VaultCryptoWire.aad(current.identity, VaultAadPurpose.INSTALLATION_MARKER))
                    if (marker) {
                        markers++
                        when (fault) {
                            Fault.MARKER_KEY_INVALIDATED -> throw KeyPermanentlyInvalidatedException()
                            Fault.MARKER_TAG -> throw AEADBadTagException("untrusted marker diagnostic")
                            Fault.UNKNOWN_MARKER -> throw GeneralSecurityException("marker-integrity")
                            else -> Unit
                        }
                        return (if (fault == Fault.MARKER_MISMATCH) byteArrayOf(0) else VaultCryptoWire.marker(current.identity))
                            .also { returned += it }
                    }
                    assertArrayEquals(VaultCryptoWire.aad(current.identity, VaultAadPurpose.RETRY_LEDGER,
                        revision = current.data!!.retryLedger.ledgerRevision), associatedData)
                    ledgers++
                    when (fault) {
                        Fault.LEDGER_KEY_INVALIDATED -> throw KeyPermanentlyInvalidatedException()
                        Fault.LEDGER_TAG -> throw AEADBadTagException("untrusted ledger diagnostic")
                        Fault.UNKNOWN_LEDGER -> throw GeneralSecurityException("ledger-integrity")
                        else -> Unit
                    }
                    return (if (fault == Fault.LEDGER_MISMATCH) byteArrayOf(0) else VaultCryptoWire.ledger(current.data.retryLedger))
                        .also { returned += it }
                }
            }
        }
    }
    private inner class Store(var value: VaultStoredSnapshot) : VaultStorePort {
        var observation: VaultStoreObservation? = null
        var retries = 0; var mutations = 0; var initializations = 0
        var afterRetry: (() -> Unit)? = null
        var failClose = false
        override fun inspect(context: VaultOperationContext) = observation ?: VaultStoreObservation.Present(value)
        override fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext): VaultStoredSnapshot {
            initializations++; error("No setup allowed")
        }
        override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext): VaultStoredSnapshot = error("No setup allowed")
        override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext): VaultStoredSnapshot = error("No setup allowed")
        override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite, authenticatedLedger: VaultCiphertext,
            context: VaultOperationContext): VaultStoredSnapshot {
            context.checkActive(); assertEquals(value, expected); retries++
            value = expected.copy(storeRevision = expected.storeRevision + 1,
                data = expected.data!!.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger))
            afterRetry?.invoke(); return value
        }
        override fun compareAndSwapCredentials(expected: VaultStoredSnapshot, mutation: VaultCredentialMutation,
            context: VaultOperationContext): VaultStoredSnapshot { mutations++; error("No repair/mutation allowed") }
        override fun compareAndSwapCredentialReset(expected: VaultStoredSnapshot, write: VaultRetryWrite,
            authenticatedLedger: VaultCiphertext, mutation: VaultCredentialMutation,
            context: VaultOperationContext): VaultStoredSnapshot { mutations++; error("No reset allowed") }
        override fun close() { if (failClose) throw IllegalStateException("cleanup platform text") }
    }
    private inner class Fixture {
        val clock = Clock(); val work = Lane(); val ui = Lane(); val view = View(); val store = Store(initial())
        val device = FakeDevice { store.value }
        val auth = AndroidVaultAuthentication(deviceAead = device::existing)
        val runtime = VaultRuntime(store, auth, clock, work, ui, Timers(), view, 1)
        fun pump() { repeat(3) { work.drain(); ui.drain() }; assertTrue(work.queue.isEmpty()); assertTrue(ui.queue.isEmpty()) }
        fun start() { runtime.observePresence(true, true); runtime.start(); pump() }
        fun assertBlocked(reason: VaultFailureReason) {
            assertEquals(VaultRuntimeStatus.UNAVAILABLE, view.rendered.status)
            assertEquals(reason, view.rendered.failureReason)
            assertEquals(VaultRecoveryState.ACTIVE, view.rendered.recoveryState)
            assertTrue(view.rendered.items.isEmpty()); assertNull(view.rendered.revision)
            assertNull(runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
            assertNull(runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL))
            val before = store.value; val lookups = device.lookups
            runtime.unlockPassword(password()); runtime.revealEmptyCatalog(); runtime.lock(); runtime.observePresence(false, true)
            runtime.observePresence(true, true); pump()
            assertEquals(before, store.value); assertEquals(lookups, device.lookups)
            assertEquals(reason, view.rendered.failureReason); assertEquals(0, view.reveals)
            assertEquals(0, store.mutations); assertEquals(0, store.initializations)
            assertTrue(device.returned.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test fun startupActualLedgerFailuresReachRenderedStateAndPreserveActiveRecoveryAndCiphertext() {
        Fault.entries.forEach { fault ->
            val f = Fixture(); val before = f.store.value; f.device.fault = fault; f.start()
            f.assertBlocked(fault.reason)
            assertEquals(before, f.store.value); assertEquals(0, f.store.retries); assertEquals(0, f.device.seals)
            if (fault in setOf(Fault.MISSING_KEY, Fault.LOOKUP_INVALIDATED, Fault.UNKNOWN_LOOKUP)) {
                assertEquals(0, f.device.markers); assertEquals(0, f.device.ledgers)
            }
            if (fault in setOf(Fault.MARKER_TAG, Fault.MARKER_MISMATCH, Fault.MARKER_KEY_INVALIDATED, Fault.UNKNOWN_MARKER))
                assertEquals(0, f.device.ledgers)
        }
    }
    @Test fun failureAfterHealthyStartupDuringReservationReachesProtectedReasonWithoutWriting() {
        Fault.entries.filter { it.category.confirmedPrimaryFailure() }.forEach { fault ->
            val f = Fixture(); f.start(); assertEquals(VaultRuntimeStatus.LOCKED, f.view.rendered.status)
            val before = f.store.value; f.device.fault = fault
            f.runtime.unlockPassword(password()); f.pump(); f.assertBlocked(fault.reason)
            assertEquals(before, f.store.value); assertEquals(0, f.store.retries); assertEquals(0, f.device.seals)
        }
    }
    @Test fun failureAfterReservationInActualPasswordAuthenticationIsNotDowngradedToCancellation() {
        Fault.entries.filter { it.category.confirmedPrimaryFailure() }.forEach { fault ->
            val f = Fixture(); f.start(); f.store.afterRetry = { f.device.fault = fault }
            f.runtime.unlockPassword(password()); f.pump(); f.assertBlocked(fault.reason)
            assertEquals(1, f.store.retries); assertEquals(1L, f.store.value.data!!.retryLedger.reservedAttemptCount)
            assertEquals(1, f.device.seals) // Only the already committed reservation, never a reset.
        }
    }
    @Test fun activeRecoveryResetFindsPrimaryFailureAfterReservationAndCannotMutateCredentials() {
        Fault.entries.filter { it.category.confirmedPrimaryFailure() }.forEach { fault ->
            val f = Fixture(); f.start()
            val flow = requireNotNull(f.runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD))
            f.store.afterRetry = { f.device.fault = fault }
            f.runtime.resetPassword(flow, code(), password()); f.pump(); f.assertBlocked(fault.reason)
            assertEquals(1, f.store.retries); assertEquals(1L, f.store.value.data!!.retryLedger.reservedAttemptCount)
            assertNull(f.store.value.data!!.pendingPassword); assertEquals(0, f.store.mutations)
        }
    }
    @Test fun startupFailureRemainsExplainedAfterLifecycleCoverWithOldUiWorkReordered() {
        val f = Fixture(); f.device.fault = Fault.MARKER_TAG
        f.runtime.observePresence(true, true); f.runtime.start(); f.work.drain()
        f.runtime.lock(); f.runtime.observePresence(false, false); f.runtime.observePresence(true, true)
        assertTrue(f.ui.queue.isNotEmpty()); f.ui.queue.removeLast().run(); f.pump()
        f.assertBlocked(VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT)
    }
    @Test fun lifecycleBeforeStartupNeverInventsAProtectedFailure() {
        val f = Fixture()
        f.runtime.lock(); f.runtime.observePresence(false, false); f.runtime.observePresence(true, true); f.pump()
        assertNull(f.view.rendered.failureReason); assertEquals(0, f.device.lookups)
        f.start(); assertEquals(VaultRuntimeStatus.LOCKED, f.view.rendered.status)
        assertNull(f.view.rendered.failureReason)
    }
    @Test fun firstReasonSurvivesCoverQueuedStatusAndLaterUnknownCloseFailure() {
        val f = Fixture(); f.device.fault = Fault.LEDGER_TAG
        f.runtime.observePresence(true, true); f.runtime.start(); f.work.drain()
        // Leave old startup/status work queued, then deliver another cover and a failing close.
        f.runtime.lock(); f.store.failClose = true; f.runtime.close(); f.pump()
        assertEquals(VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT, f.view.rendered.failureReason)
        assertEquals(VaultRuntimeStatus.UNAVAILABLE, f.view.rendered.status)
        assertEquals(0, f.view.reveals); assertEquals(0, f.store.retries)
    }
    @Test fun unknownDiagnosticMayRefineToFirstConfirmedReasonButNeverReplacesIt() {
        val f = Fixture(); f.start()
        val report = VaultRuntime::class.java.getDeclaredMethod("failure", VaultFailure::class.java).apply { isAccessible = true }
        report.invoke(f.runtime, VaultFailure.RELEASE_UNKNOWN); f.pump()
        assertEquals(VaultFailureReason.UNKNOWN, f.view.rendered.failureReason)
        report.invoke(f.runtime, VaultFailure.DEVICE_KEY_UNAVAILABLE); f.pump()
        assertEquals(VaultFailureReason.DEVICE_KEY_UNAVAILABLE, f.view.rendered.failureReason)
        report.invoke(f.runtime, VaultFailure.DEADLINE); f.pump()
        assertEquals(VaultFailureReason.DEVICE_KEY_UNAVAILABLE, f.view.rendered.failureReason)
        report.invoke(f.runtime, VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT); f.pump()
        f.assertBlocked(VaultFailureReason.DEVICE_KEY_UNAVAILABLE)
        assertEquals(0, f.store.retries); assertFalse(f.runtime.hostCleanupComplete())
    }
    @Test fun ordinaryCancellationDeadlineRejectionAndUnknownProviderKeepExistingAttemptSemantics() {
        listOf(VaultFailure.CANCELLED, VaultFailure.DEADLINE, VaultFailure.REJECTED, VaultFailure.UNAVAILABLE).forEach { category ->
            val f = Fixture(); f.start(); f.store.afterRetry = { f.device.lookupCategory = category }
            f.runtime.unlockPassword(password()); f.pump()
            assertNull(f.view.rendered.failureReason)
            assertEquals(VaultRuntimeStatus.LOCKED, f.view.rendered.status)
            assertEquals(1, f.store.retries); assertEquals(0, f.store.mutations); assertEquals(0, f.view.reveals)
        }
    }
    @Test fun incompleteAndUnreadableStoresRemainClosedWithoutDeviceLookup() {
        listOf(VaultFailure.CORRUPT to VaultFailureReason.STORE_UNREADABLE,
            VaultFailure.UNAVAILABLE to VaultFailureReason.STORE_UNREADABLE,
            VaultFailure.INCOMPLETE to VaultFailureReason.STORE_INCOMPLETE).forEach { (category, reason) ->
            val f = Fixture(); f.store.observation = VaultStoreObservation.Unavailable(category); f.start()
            assertEquals(reason, f.view.rendered.failureReason); assertEquals(VaultRuntimeStatus.UNAVAILABLE, f.view.rendered.status)
            assertEquals(0, f.device.lookups); assertEquals(0, f.store.retries); assertEquals(0, f.store.mutations)
        }
        listOf(VaultStorePhase.INITIALIZING, VaultStorePhase.STAGED).forEach { phase ->
            val f = Fixture(); f.store.value = f.store.value.copy(phase = phase); f.start()
            assertEquals(VaultFailureReason.STORE_INCOMPLETE, f.view.rendered.failureReason)
            assertEquals(0, f.device.lookups); assertEquals(0, f.store.initializations)
        }
    }
    @Test fun malformedRecordSizeIsClassifiedAtTheExactRecordWithoutCallingItsDecrypt() {
        val marker = Fixture(); marker.store.value = marker.store.value.copy(data = marker.store.value.data!!.copy(installationMarker = VaultCiphertext(byteArrayOf(5))))
        marker.start(); marker.assertBlocked(VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT)
        assertEquals(0, marker.device.markers); assertEquals(0, marker.device.ledgers)
        val ledger = Fixture(); ledger.store.value = ledger.store.value.copy(data = ledger.store.value.data!!.copy(retryLedgerEnvelope = VaultCiphertext(byteArrayOf(6))))
        ledger.start(); ledger.assertBlocked(VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT)
        assertEquals(1, ledger.device.markers); assertEquals(0, ledger.device.ledgers)
    }
    @Test fun healthyLedgerAndCancellationDoNotInventDamageAndTemporaryBuffersAreCleared() {
        val f = Fixture(); val context = VaultOperationContext(100, f.clock)
        assertEquals(f.store.value.data!!.retryLedger, f.auth.authenticateLedger(f.store.value, context))
        assertEquals(1, f.device.markers); assertEquals(1, f.device.ledgers)
        assertTrue(f.device.returned.all { bytes -> bytes.all { it == 0.toByte() } })
        context.cancel()
        assertEquals(VaultFailure.CANCELLED, assertThrows(VaultOperationException::class.java) {
            f.auth.authenticateLedger(f.store.value, context)
        }.category)
        assertEquals(1, f.device.lookups)
    }
    @Test fun unhealthyLedgerPrecedesPasswordInputUseAndRecoveryCodeUseInActualAdapter() {
        Fault.entries.forEach { fault ->
            val f = Fixture(); f.device.fault = fault
            val attempt = VaultAttemptIdentity(1, 1, 0)
            f.store.value = f.store.value.copy(data = f.store.value.data!!.copy(retryLedger = VaultRetryLedger(1, 1, 0, 1)))
            val context = VaultOperationContext(100, f.clock)
            val closedPassword = password().also { it.close() }
            assertEquals(fault.category, assertThrows(VaultOperationException::class.java) {
                f.auth.unlockPassword(f.store.value, closedPassword, attempt, context)
            }.category)
            val closedCode = code().also { it.close() }
            password().use { newPassword ->
                assertEquals(fault.category, assertThrows(VaultOperationException::class.java) {
                    f.auth.preparePasswordReset(f.store.value, closedCode, newPassword, attempt, context)
                }.category)
            }
            assertEquals(0, f.device.seals)
        }
    }
}
