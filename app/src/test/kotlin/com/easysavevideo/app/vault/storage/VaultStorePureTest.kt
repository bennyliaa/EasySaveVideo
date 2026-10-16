package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.domain.VaultSessionReducer
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Fixed synthetic ciphertext only. Does not execute Android, SQLite, a DB emulator, or credentials. */
class VaultStorePureTest {
    private val identity = VaultPersistentIdentity("vault-one", "installation-one", "init-one",
        "esv.vault.v1.init-one.device", "esv.vault.v1.init-one.biometric")
    private val zeroLedger = VaultRetryLedger(0, 0, -1, null)
    private fun blob(seed: Int = 7, size: Int = 32, max: Int = VaultRuntimeLimits.ENVELOPE_BYTES) =
        VaultCiphertext(ByteArray(size) { (seed + it % 17).toByte() }, max)
    private fun slot(kind: VaultSlotKind = VaultSlotKind.PASSWORD, id: String = "password") =
        VaultSlotRecord(id, 1, kind, blob(), true)
    private fun staged(slots: List<VaultSlotRecord> = listOf(slot())) = VaultStagedData(
        slots, blob(11), blob(19), 1, blob(23), zeroLedger, blob(29))
    private fun snapshot(phase: VaultStorePhase = VaultStorePhase.STAGED, data: VaultStagedData? = staged()) =
        VaultStoredSnapshot(identity, phase, if (phase == VaultStorePhase.INITIALIZING) 1 else if (phase == VaultStorePhase.STAGED) 2 else 3,
            if (phase == VaultStorePhase.INITIALIZING) null else data)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected bounded rejection") } catch (_: IllegalArgumentException) { }
        catch (_: VaultStoreFault) { } catch (_: java.io.IOException) { }
    }

    @Test fun roundTripAllThreePhasesPreservesExactTechnicalAndEncryptedBytes() {
        VaultStorePhase.entries.forEach { phase ->
            val source = snapshot(phase)
            assertEquals(source, VaultSnapshotCodec.decode(VaultSnapshotCodec.encode(source)))
        }
    }
    @Test fun requiredMissingCatalogCannotBecomeEmpty() {
        rejected { VaultSnapshotCodec.encode(snapshot(VaultStorePhase.READY, null)) }
        rejected { VaultSnapshotCodec.encode(snapshot().copy(phase = VaultStorePhase.INITIALIZING, storeRevision = 1)) }
    }
    @Test fun noEmptyOrOversizedPayloadAllocation() {
        rejected { VaultSnapshotCodec.decode(byteArrayOf()) }
        rejected { VaultSnapshotCodec.decode(ByteArray(VaultSqliteRecord.MAX_PAYLOAD_BYTES + 1)) }
        rejected { VaultSqliteRecord(1, "init", 1, ByteArray(VaultSqliteRecord.MAX_PAYLOAD_BYTES + 1)) }
    }
    @Test fun envelopeBoundBeforeCopyAndFieldSpecificLimits() {
        rejected { blob(size = VaultRuntimeLimits.ENVELOPE_BYTES + 1) }
        rejected { VaultCiphertext(ByteArray(1), Int.MAX_VALUE) }
        val catalogSized = blob(size = VaultRuntimeLimits.ENVELOPE_BYTES + 1, max = VaultRuntimeLimits.EMPTY_CATALOG_BYTES)
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged().copy(catalogKeysetEnvelope = catalogSized))) }
        assertEquals(VaultRuntimeLimits.EMPTY_CATALOG_BYTES,
            VaultSnapshotCodec.decode(VaultSnapshotCodec.encode(snapshot(data = staged().copy(
                catalogEnvelope = blob(size = VaultRuntimeLimits.EMPTY_CATALOG_BYTES, max = VaultRuntimeLimits.EMPTY_CATALOG_BYTES)
            )))).data!!.catalogEnvelope.size)
    }
    @Test fun everyTruncationAndTrailingByteIsRejected() {
        val bytes = VaultSnapshotCodec.encode(snapshot())
        for (cut in bytes.indices) rejected { VaultSnapshotCodec.decode(bytes.copyOf(cut)) }
        rejected { VaultSnapshotCodec.decode(bytes + 0) }
    }
    @Test fun impossibleLengthPrefixRejectedBeforeTextAllocation() {
        val bytes = VaultSnapshotCodec.encode(snapshot())
        ByteBuffer.wrap(bytes).putInt(8, Int.MAX_VALUE)
        rejected { VaultSnapshotCodec.decode(bytes) }
    }
    @Test fun unknownWireVersionAndMagicRejected() {
        val first = VaultSnapshotCodec.encode(snapshot())
        first[0] = 0
        rejected { VaultSnapshotCodec.decode(first) }
        val second = VaultSnapshotCodec.encode(snapshot())
        ByteBuffer.wrap(second).putInt(4, 3)
        rejected { VaultSnapshotCodec.decode(second) }
    }
    @Test fun identityAndExactAliasAreBoundedAndFixed() {
        listOf(identity.copy(vaultId = "a".repeat(65)), identity.copy(vaultId = "../vault"),
            identity.copy(installationId = "私人"), identity.copy(rootVersion = 2), identity.copy(schemaVersion = 2),
            identity.copy(deviceKeyAlias = "another.device"), identity.copy(biometricKeyAlias = identity.deviceKeyAlias)
        ).forEach { bad -> rejected { VaultSnapshotCodec.encode(snapshot().copy(identity = bad)) } }
    }
    @Test fun oneCredentialSlotAndNoDuplicateOrDisabledEnabledSlot() {
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged(listOf(slot(), slot(VaultSlotKind.PIN, "pin"))))) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged(listOf(slot(VaultSlotKind.RECOVERY, "recovery"))))) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged(listOf(slot().copy(required = false))))) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged(listOf(slot().copy(generation = 0))))) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged(listOf(slot(), slot(VaultSlotKind.PASSWORD, "second"))))) }
    }
    @Test fun optionalSlotOptOutIsAbsenceAndPresentSlotOrderIsCanonical() {
        val slots = listOf(slot(VaultSlotKind.RECOVERY, "recovery"), slot())
        val decoded = VaultSnapshotCodec.decode(VaultSnapshotCodec.encode(snapshot(VaultStorePhase.READY,
            staged(slots).copy(catalogRevision = 2, recoveryState = VaultRecoveryState.PENDING))))
        assertEquals(listOf(VaultSlotKind.PASSWORD, VaultSlotKind.RECOVERY), decoded.data!!.slots.map { it.kind })
        assertEquals(1, VaultSnapshotCodec.decode(VaultSnapshotCodec.encode(snapshot())).data!!.slots.size)
    }
    @Test fun cipherAndSlotListDefensiveCopies() {
        val bytes = ByteArray(20) { 5 }
        val cipher = VaultCiphertext(bytes)
        bytes.fill(0)
        assertEquals(5, cipher.copyBytes()[0].toInt())
        cipher.copyBytes().fill(0)
        assertEquals(5, cipher.copyBytes()[0].toInt())
        val slots = mutableListOf(slot())
        val data = staged(slots)
        slots.clear()
        assertEquals(1, data.slots.size)
        val frozen = VaultSnapshotCodec.freeze(snapshot(data = data))
        assertEquals(1, frozen.data!!.slots.size)
    }
    @Test fun phaseRevisionsAndEmptyCatalogVersionAreStrict() {
        rejected { VaultSnapshotCodec.encode(snapshot().copy(storeRevision = 1)) }
        rejected { VaultSnapshotCodec.encode(snapshot(VaultStorePhase.INITIALIZING).copy(storeRevision = 2)) }
        rejected { VaultSnapshotCodec.encode(snapshot().copy(storeRevision = Long.MAX_VALUE)) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged().copy(catalogRevision = 2))) }
        rejected { VaultSnapshotCodec.encode(snapshot(data = staged().copy(retryLedger = VaultRetryLedger(1, 1, 0, 9)))) }
    }
    @Test fun malformedRetryLedgerCannotEnterReady() {
        val bad = listOf(VaultRetryLedger(-1, 0, -1, null), VaultRetryLedger(1, -1, -1, null),
            VaultRetryLedger(1, 1, -1, 3), VaultRetryLedger(1, 0, -1, 3), VaultRetryLedger(1, 1, 0, null))
        bad.forEach { rejected { VaultSnapshotCodec.encode(snapshot(VaultStorePhase.READY, staged().copy(retryLedger = it))) } }
    }
    @Test fun retryLadderUsesFrozenR2Tiers() {
        val policy = VaultRuntimeLimits.retryPolicy()
        assertEquals(listOf(0L, 0L, 0L, 1_000L, 5_000L, 30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L), policy.delaysMillis)
        assertEquals(3_600_000L, policy.delayFor(100))
        assertEquals(10_000L, policy.persistenceTimeoutMillis)
    }
    @Test fun exactUnknownCommitReadbackRequiresEveryByteAndTechnicalIdentity() {
        val old = VaultSqliteRecord(2, "init", 2, byteArrayOf(1, 2))
        val next = VaultSqliteRecord(3, "init", 3, byteArrayOf(3, 4))
        val mutation = VaultSqliteMutation(old, next)
        assertEquals(VaultSqliteReadback.EXACT_PROPOSED, classifyVaultReadback(mutation, next))
        assertEquals(VaultSqliteReadback.EXACT_EXPECTED, classifyVaultReadback(mutation, old))
        listOf(null, VaultSqliteRecord(3, "other", 3, byteArrayOf(3, 4)),
            VaultSqliteRecord(3, "init", 2, byteArrayOf(3, 4)), VaultSqliteRecord(3, "init", 3, byteArrayOf(3, 5)),
            VaultSqliteRecord(4, "init", 3, byteArrayOf(3, 4))).forEach {
            assertEquals(VaultSqliteReadback.INCONSISTENT, classifyVaultReadback(mutation, it))
        }
    }
    @Test fun missingInitializationIsNotConfirmedRollbackOrCreatePermission() {
        val mutation = VaultSqliteMutation(null, VaultSqliteRecord(1, "init", 1, byteArrayOf(1)))
        assertEquals(VaultSqliteReadback.INCONSISTENT, classifyVaultReadback(mutation, null))
    }
    @Test fun mutationSnapshotAndDigestAreDefensiveCopies() {
        val bytes = byteArrayOf(1, 2, 3)
        val record = VaultSqliteRecord(1, "init", 1, bytes)
        bytes.fill(0); record.payload().fill(0); record.fingerprint().fill(0)
        assertTrue(record.sameBytes(VaultSqliteRecord(1, "init", 1, byteArrayOf(1, 2, 3))))
    }
    @Test fun mutationRevisionCannotSkipOrOverflow() {
        val old = VaultSqliteRecord(2, "init", 2, byteArrayOf(1))
        rejected { VaultSqliteMutation(old, VaultSqliteRecord(4, "init", 3, byteArrayOf(2))) }
        rejected { VaultSqliteMutation(null, old) }
        rejected { VaultSqliteRecord(Long.MAX_VALUE, "init", 1, byteArrayOf(1)) }
    }
    @Test fun realReducerReservationAndResetRequireConfirmedWriteIdBinding() {
        val reducer = VaultSessionReducer(VaultRuntimeLimits.retryPolicy())
        val now = VaultTick(41, 0)
        var state = reducer.start(41, now, VaultLedgerLoad.TrustedFirstUse).state
        state = reducer.reduce(state, VaultEvent.CleanupCompleted(state.currentCleanup!!), now).state
        state = reducer.reduce(state, VaultEvent.ForegroundEntered, now).state
        state = reducer.reduce(state, VaultEvent.UnlockRequested, now).state
        val reservation = state.pendingRetryWrite!!
        assertNotEquals(reservation.identity.attempt.attemptId, reservation.identity.writeId)
        assertEquals(reservation.identity.writeId, reservation.proposedLedger.outstandingReservationId)
        VaultRetryMutationValidation.requireValid(zeroLedger, reservation)
        rejected { VaultRetryMutationValidation.requireValid(zeroLedger, reservation.copy(proposedLedger =
            reservation.proposedLedger.copy(outstandingReservationId = reservation.identity.attempt.attemptId))) }
        state = reducer.reduce(state, VaultEvent.RetryWriteStored(reservation.identity,
            reservation.expectedLedgerRevision, reservation.proposedLedger.ledgerRevision), now).state
        state = reducer.reduce(state, VaultEvent.AuthenticationResult(reservation.identity.attempt,
            VaultAuthenticationOutcome.SESSION_ESTABLISHED), now).state
        val reset = state.pendingRetryWrite!!
        VaultRetryMutationValidation.requireValid(reservation.proposedLedger, reset, reservation)
        rejected { VaultRetryMutationValidation.requireValid(reservation.proposedLedger, reset) }
        val wrongAttempt = reservation.copy(identity = reservation.identity.copy(attempt = reservation.identity.attempt.copy(authEpoch = 99)))
        rejected { VaultRetryMutationValidation.requireValid(reservation.proposedLedger, reset, wrongAttempt) }
        rejected { VaultRetryMutationValidation.requireValid(reservation.proposedLedger, reset.copy(expectedLedgerRevision = 0), reservation) }
        val before = snapshot(VaultStorePhase.READY, staged().copy(retryLedger = reservation.proposedLedger)).copy(storeRevision = 4)
        val encryptedReset = blob(47)
        val after = before.copy(storeRevision = 5, data = before.data!!.copy(
            retryLedger = reset.proposedLedger, retryLedgerEnvelope = encryptedReset))
        val target = VaultSqliteRecord(5, "retry_exact_reset", 3, VaultSnapshotCodec.encode(after))
        val receipt = VaultCompletedResetReceipt(before, reset, encryptedReset, target)
        assertTrue(receipt.matches(before, reset, encryptedReset))
        assertTrue(receipt.proposed.sameBytes(target))
        assertFalse(receipt.matches(before, reset.copy(identity = reset.identity.copy(writeId = reset.identity.writeId + 1)), encryptedReset))
        assertFalse(receipt.matches(before, reset, blob(48)))
        assertFalse(receipt.matches(before.copy(storeRevision = 3), reset, encryptedReset))

    }
    @Test fun sqliteHeaderRejectsWalBeforeOpenAndDoesNotTreatMissingHeaderAsFreshSetup() {
        val header = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[16] = 0x10; header[17] = 0; header[18] = 1; header[19] = 1
        VaultSqliteHeaderPolicy.requireRollbackHeader(header)
        rejected { VaultSqliteHeaderPolicy.requireRollbackHeader(header.copyOf(99)) }
        rejected { VaultSqliteHeaderPolicy.requireRollbackHeader(header.copyOf().also { it[18] = 2 }) }
        rejected { VaultSqliteHeaderPolicy.requireRollbackHeader(header.copyOf().also { it[19] = 2 }) }
        rejected { VaultSqliteHeaderPolicy.requireRollbackHeader(header.copyOf().also { it[0] = 0 }) }
        rejected { VaultSqliteHeaderPolicy.requireRollbackHeader(header.copyOf().also { it[16] = 3 }) }
    }

    @Test fun operationContextExactDeadlineAndSharedCancellationCannotBeExtended() {
        var tick = 100L
        val context = VaultOperationContext(1_000, VaultMonotonicClock { tick })
        val bounded = context.bounded(50)
        assertEquals(150, bounded.deadlineElapsedMillis)
        tick = 149; bounded.checkActive()
        tick = 150
        try { bounded.checkActive(); fail() } catch (e: VaultOperationException) { assertEquals(VaultFailure.DEADLINE, e.category) }
        context.cancel()
        try { context.checkActive(); fail() } catch (e: VaultOperationException) { assertEquals(VaultFailure.CANCELLED, e.category) }
    }
}
