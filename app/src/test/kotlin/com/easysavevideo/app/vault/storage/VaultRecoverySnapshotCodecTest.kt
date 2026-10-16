package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

/** Public synthetic ciphertext and metadata only; no Android database or credential generation. */
class VaultRecoverySnapshotCodecTest {
    private val identity = VaultPersistentIdentity("vault-one", "installation-one", "init-one",
        "esv.vault.v1.init-one.device", "esv.vault.v1.init-one.biometric")
    private val zero = VaultRetryLedger(0, 0, -1, null)
    private val attempt = VaultAttemptIdentity(41, 3, 2)
    private val reservation = VaultRetryLedger(1, 1, 0, 4)
    private fun blob(seed: Int) = VaultCiphertext(ByteArray(32) { (seed + it % 17).toByte() })
    private fun slot(id: String = "password", kind: VaultSlotKind = VaultSlotKind.PASSWORD, generation: Long = 1) =
        VaultSlotRecord(id, generation, kind, blob(7), true)
    private fun original() = VaultStoredSnapshot(identity, VaultStorePhase.READY, 3,
        VaultStagedData(listOf(slot()), blob(11), blob(19), 1, blob(23), zero, blob(29)))
    private fun extended(state: VaultRecoveryState = VaultRecoveryState.ACTIVE, pending: Boolean = false): VaultStoredSnapshot {
        val old = original()
        val data = old.data!!
        return old.copy(storeRevision = 7, data = data.copy(catalogRevision = 4,
            slots = data.slots + if (state == VaultRecoveryState.NONE) emptyList() else listOf(slot("recovery", VaultSlotKind.RECOVERY, 2)),
            recoveryState = state, retryLedger = reservation,
            pendingPassword = if (pending) VaultPendingPassword(slot("next-password", generation = 4), attempt, reservation) else null))
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected bounded rejection") } catch (_: IllegalArgumentException) { }
        catch (_: VaultStoreFault) { } catch (_: java.io.IOException) { }
    }
    private fun roundTrip(value: VaultStoredSnapshot): VaultStoredSnapshot {
        val encoded = VaultSnapshotCodec.encode(value)
        val decoded = VaultSnapshotCodec.decode(encoded)
        assertEquals(value, decoded)
        assertArrayEquals(encoded, VaultSnapshotCodec.encode(decoded))
        return decoded
    }

    @Test fun exactPublicLegacyPasswordV1FixtureRemainsByteIdentical() {
        // Fixed independently encoded V1 snapshot, SHA-256 565df6bb55535c24a178ccbe759823a898a1faa064e1c122389e129e037765f6.
        val expected = Base64.getDecoder().decode("RVNWMQAAAAEAAAAJdmF1bHQtb25lAAAAEGluc3RhbGxhdGlvbi1vbmUAAAAIaW5pdC1vbmUAAAAcZXN2LnZhdWx0LnYxLmluaXQtb25lLmRldmljZQAAAB9lc3YudmF1bHQudjEuaW5pdC1vbmUuYmlvbWV0cmljAAAAAQAAAAEAAAACAAAAAAAAAAMBAAAAAQAAAAhwYXNzd29yZAAAAAAAAAABAAAAAQEAAAAgBwgJCgsMDQ4PEBESExQVFhcHCAkKCwwNDg8QERITFBUAAAAgCwwNDg8QERITFBUWFxgZGhsLDA0ODxAREhMUFRYXGBkAAAAgExQVFhcYGRobHB0eHyAhIiMTFBUWFxgZGhscHR4fICEAAAAAAAAAAQAAACAXGBkaGxwdHh8gISIjJCUmJxcYGRobHB0eHyAhIiMkJQAAAAAAAAAAAAAAAAAAAAD/////AAAAACAdHh8gISIjJCUmJygpKissLR0eHyAhIiMkJSYnKCkqKw==")
        assertEquals(original(), VaultSnapshotCodec.decode(expected))
        assertArrayEquals(expected, VaultSnapshotCodec.encode(original()))
        assertEquals(VaultRecoveryState.NONE, VaultSnapshotCodec.decode(expected).data!!.recoveryState)
    }
    @Test fun explicitNonePendingActiveAndPendingPasswordUseCanonicalV2() {
        VaultRecoveryState.entries.forEach { state ->
            val value = extended(state)
            assertEquals(2, ByteBuffer.wrap(VaultSnapshotCodec.encode(value)).getInt(4))
            roundTrip(value)
        }
        roundTrip(extended(pending = true))
    }
    @Test fun staleCreatingReservationIsDecodedEvenAfterNewAttemptOrOrdinaryReset() {
        val source = extended(pending = true)
        val creating = source.data!!.pendingPassword!!
        listOf(VaultRetryLedger(2, 2, 1, 8), VaultRetryLedger(3, 0, -1, null)).forEach { current ->
            val decoded = roundTrip(source.copy(storeRevision = 9,
                data = source.data!!.copy(retryLedger = current, retryLedgerEnvelope = blob(47))))
            assertEquals(creating, decoded.data!!.pendingPassword)
            assertEquals(source.data!!.slots, decoded.data!!.slots)
        }
    }
    @Test fun invalidStateSlotPresenceAndPendingKindAreRejected() {
        val source = extended(pending = true)
        val data = source.data!!
        rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(recoveryState = VaultRecoveryState.NONE))) }
        rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(recoveryState = VaultRecoveryState.PENDING))) }
        rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(slots = data.slots.filter { it.kind != VaultSlotKind.RECOVERY }))) }
        val pending = data.pendingPassword!!
        listOf(pending.slot.copy(kind = VaultSlotKind.PIN), pending.slot.copy(required = false),
            pending.slot.copy(slotId = "recovery"), pending.slot.copy(generation = 5), pending.slot.copy(generation = Long.MAX_VALUE)).forEach {
            rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(pendingPassword = pending.copy(slot = it)))) }
        }
    }
    @Test fun pendingAttemptAndCreatingReservationAreBoundedButNotCurrentLedgerBound() {
        val source = extended(pending = true)
        val data = source.data!!
        val pending = data.pendingPassword!!
        listOf(attempt.copy(processInstance = 0), attempt.copy(attemptId = -1), attempt.copy(authEpoch = -1),
            attempt.copy(attemptId = Long.MAX_VALUE)).forEach {
            rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(pendingPassword = pending.copy(attempt = it)))) }
        }
        listOf(zero, reservation.copy(ledgerRevision = 0), reservation.copy(outstandingReservationId = null),
            reservation.copy(delayTier = -1)).forEach {
            rejected { VaultSnapshotCodec.encode(source.copy(data = data.copy(pendingPassword = pending.copy(reservation = it)))) }
        }
    }
    @Test fun allV2TruncationsTrailingBytesAndUnknownStateOrVersionReject() {
        val bytes = VaultSnapshotCodec.encode(extended(pending = true))
        bytes.indices.forEach { rejected { VaultSnapshotCodec.decode(bytes.copyOf(it)) } }
        rejected { VaultSnapshotCodec.decode(bytes + 0) }
        rejected { VaultSnapshotCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 3) }) }
        val withoutPending = VaultSnapshotCodec.encode(extended())
        rejected { VaultSnapshotCodec.decode(withoutPending.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 5, 3) }) }
        rejected { VaultSnapshotCodec.decode(withoutPending.copyOf().also { it[it.lastIndex] = 2 }) }
    }
    @Test fun aLegacyShapeHasOneCanonicalVersionAndRecoveryCannotBeStrippedToV1() {
        val legacy = VaultSnapshotCodec.encode(original())
        val fakeV2 = (legacy + ByteArray(5)).also { ByteBuffer.wrap(it).putInt(4, 2) }
        rejected { VaultSnapshotCodec.decode(fakeV2) }
        val recovery = VaultSnapshotCodec.encode(extended())
        val stripped = recovery.copyOf(recovery.size - 5).also { ByteBuffer.wrap(it).putInt(4, 1) }
        rejected { VaultSnapshotCodec.decode(stripped) }
    }
    @Test fun generationsAndRevisionsRemainPositiveBoundedAndAdvanceBeyondOne() {
        val source = extended(VaultRecoveryState.NONE)
        roundTrip(source.copy(data = source.data!!.copy(slots = listOf(slot(generation = 4)))))
        rejected { VaultSnapshotCodec.encode(source.copy(data = source.data!!.copy(catalogRevision = Long.MAX_VALUE))) }
        rejected { VaultSnapshotCodec.encode(source.copy(data = source.data!!.copy(slots = listOf(slot(generation = 5))))) }
    }
    @Test fun pendingSlotNeverJoinsTheOrdinaryActiveSlotList() {
        val decoded = roundTrip(extended(pending = true))
        assertEquals(listOf("password", "recovery"), decoded.data!!.slots.map { it.slotId })
        assertEquals("next-password", decoded.data!!.pendingPassword!!.slot.slotId)
        assertTrue(decoded.data!!.pendingPassword.toString().contains("REDACTED"))
    }
}
