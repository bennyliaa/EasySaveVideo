package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed public metadata/ciphertext only. No SQLite substitute, device, KDF or real credentials. */
class VaultCredentialMutationValidationTest {
    private val identity = VaultPersistentIdentity("vault", "installation", "init",
        "esv.vault.v1.init.device", "esv.vault.v1.init.biometric")
    private val attempt = VaultAttemptIdentity(41, 3, 2)
    private val reserved = VaultRetryLedger(1, 1, 0, 4)
    private val reservation = VaultRetryWrite(VaultRetryWriteKind.RESERVATION, VaultRetryWriteIdentity(attempt, 4),
        0, reserved, VaultTick(41, 10_000))
    private val reset = VaultRetryWrite(VaultRetryWriteKind.RESET, VaultRetryWriteIdentity(attempt, 5),
        1, VaultRetryLedger(2, 0, -1, null), VaultTick(41, 20_000))
    private fun blob(n: Int) = VaultCiphertext(ByteArray(16) { (n + it).toByte() })
    private fun slot(kind: VaultSlotKind, generation: Long, n: Int = 1) = VaultSlotRecord(
        if (kind == VaultSlotKind.RECOVERY) "recovery-v1" else "password-v1", generation, kind, blob(n), true)
    private fun before(state: VaultRecoveryState = VaultRecoveryState.NONE, pending: Boolean = false): VaultStoredSnapshot {
        val revision = if (state == VaultRecoveryState.NONE) 1L else if (pending) 4L else 3L
        return VaultStoredSnapshot(identity, VaultStorePhase.READY, 8, VaultStagedData(
            listOf(slot(VaultSlotKind.PASSWORD, 1)) + if (state == VaultRecoveryState.NONE) emptyList() else listOf(slot(VaultSlotKind.RECOVERY, 2, 2)),
            blob(3), blob(4), revision, blob(5), reserved, blob(6), state,
            if (pending) VaultPendingPassword(slot(VaultSlotKind.PASSWORD, 4, 7), attempt, reserved) else null))
    }
    private fun proposal(before: VaultStoredSnapshot, kind: VaultCredentialMutationKind, replacement: VaultStagedData? = null): VaultCredentialMutationProposal {
        val old = before.data!!
        val revision = old.catalogRevision + 1
        val changed = when (kind) {
            VaultCredentialMutationKind.STAGE_RECOVERY -> old.copy(slots = old.slots.filter { it.kind != VaultSlotKind.RECOVERY } +
                slot(VaultSlotKind.RECOVERY, revision, 8), recoveryState = VaultRecoveryState.PENDING)
            VaultCredentialMutationKind.ACTIVATE_RECOVERY -> old.copy(recoveryState = VaultRecoveryState.ACTIVE)
            VaultCredentialMutationKind.STAGE_PASSWORD_RESET -> old.copy(pendingPassword = VaultPendingPassword(
                slot(VaultSlotKind.PASSWORD, revision, 9), attempt, reserved))
            VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET -> old.copy(slots = listOf(old.pendingPassword!!.slot),
                recoveryState = VaultRecoveryState.NONE, pendingPassword = null)
            else -> error("recovery-fixture-kind")
        }
        return VaultCredentialMutationProposal(kind, replacement ?: changed.copy(catalogRevision = revision,
            catalogKeysetEnvelope = blob(30 + revision.toInt()), catalogEnvelope = blob(10 + revision.toInt())),
            if (kind == VaultCredentialMutationKind.STAGE_PASSWORD_RESET || kind == VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET) attempt else null,
            "fixed-operation-${kind.ordinal}")
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        catch (_: VaultOperationException) { }
    }
    @Test fun recoveryStagesAndActivationPreservePrimaryLedgerAndVerifiedEnvelope() {
        listOf(VaultRecoveryState.NONE, VaultRecoveryState.PENDING).forEach { state ->
            val old = before(state)
            val staged = VaultCredentialMutationValidation.requireValid(old, proposal(old, VaultCredentialMutationKind.STAGE_RECOVERY))
            assertEquals(old.data!!.slots.first(), staged.data!!.slots.first())
            assertEquals(old.data!!.retryLedgerEnvelope, staged.data!!.retryLedgerEnvelope)
            val activated = VaultCredentialMutationValidation.requireValid(staged, proposal(staged, VaultCredentialMutationKind.ACTIVATE_RECOVERY))
            assertEquals(staged.data!!.slots, activated.data!!.slots)
            assertEquals(VaultRecoveryState.ACTIVE, activated.data!!.recoveryState)
        }
    }
    @Test fun healthyActiveRecoveryCannotBeReplacedByEnrollment() {
        val old = before(VaultRecoveryState.ACTIVE)
        rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, VaultCredentialMutationKind.STAGE_RECOVERY)) }
    }
    @Test fun stagePasswordUsesRealFixedIdAndCanReplaceStalePendingAfterFreshReservation() {
        val old = before(VaultRecoveryState.ACTIVE, pending = true)
        val stale = old.copy(data = old.data!!.copy(pendingPassword = old.data!!.pendingPassword!!.copy(
            attempt = attempt.copy(attemptId = 1), reservation = reserved.copy(outstandingReservationId = 2))))
        val staged = VaultCredentialMutationValidation.requireValid(stale, proposal(stale, VaultCredentialMutationKind.STAGE_PASSWORD_RESET), reservation)
        assertEquals("password-v1", staged.data!!.pendingPassword!!.slot.slotId)
        assertEquals("password-v1", staged.data!!.slots.first().slotId)
        assertEquals(5L, staged.data!!.pendingPassword!!.slot.generation)
        assertEquals(stale.data!!.slots, staged.data!!.slots)
        assertEquals(reserved, staged.data!!.pendingPassword!!.reservation)
    }
    @Test fun finalResetCredentialProposalKeepsLedgerForIndependentExactResetAndOneRecord() {
        val old = before(VaultRecoveryState.ACTIVE, pending = true)
        val verified = proposal(old, VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
        val afterCredentials = VaultCredentialMutationValidation.requireValid(old, verified, reservation)
        VaultRetryMutationValidation.requireValid(reserved, reset, reservation)
        val final = VaultSnapshotCodec.freeze(afterCredentials.copy(data = afterCredentials.data!!.copy(
            retryLedger = reset.proposedLedger, retryLedgerEnvelope = blob(11))))
        assertEquals(old.storeRevision + 1, final.storeRevision)
        assertEquals(old.data!!.pendingPassword!!.slot, final.data!!.slots.single())
        assertEquals(VaultRecoveryState.NONE, final.data!!.recoveryState)
        assertNull(final.data!!.pendingPassword)
        assertEquals(reset.proposedLedger, final.data!!.retryLedger)
        val oldRecord = VaultSqliteRecord(old.storeRevision, "old", 3, VaultSnapshotCodec.encode(old))
        val finalRecord = VaultSqliteRecord(final.storeRevision, "reset", 3, VaultSnapshotCodec.encode(final))
        val mutation = VaultSqliteMutation(oldRecord, finalRecord)
        assertEquals(VaultSqliteReadback.EXACT_PROPOSED, classifyVaultReadback(mutation, finalRecord))
        val partial = final.copy(data = final.data!!.copy(retryLedger = reserved, retryLedgerEnvelope = old.data!!.retryLedgerEnvelope))
        assertEquals(VaultSqliteReadback.INCONSISTENT, classifyVaultReadback(mutation,
            VaultSqliteRecord(final.storeRevision, "reset", 3, VaultSnapshotCodec.encode(partial))))
    }
    @Test fun resetRequiresExactCurrentAttemptReservationAndPersistedPendingBytes() {
        val old = before(VaultRecoveryState.ACTIVE, pending = true)
        val valid = proposal(old, VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
        rejected { VaultCredentialMutationValidation.requireValid(old, valid) }
        rejected { VaultCredentialMutationValidation.requireValid(old, valid,
            reservation.copy(identity = reservation.identity.copy(attempt = attempt.copy(authEpoch = 3)))) }
        val stale = old.copy(data = old.data!!.copy(pendingPassword = old.data!!.pendingPassword!!.copy(attempt = attempt.copy(attemptId = 1))))
        rejected { VaultCredentialMutationValidation.requireValid(stale, proposal(stale, valid.kind), reservation) }
        val changed = valid.data.copy(slots = listOf(valid.data.slots.single().copy(envelope = blob(12))))
        rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, valid.kind, changed), reservation) }
    }
    @Test fun everyStageRejectsLedgerMarkerJournalAndRevisionMutation() {
        val cases = listOf(before() to VaultCredentialMutationKind.STAGE_RECOVERY,
            before(VaultRecoveryState.PENDING) to VaultCredentialMutationKind.ACTIVATE_RECOVERY,
            before(VaultRecoveryState.ACTIVE) to VaultCredentialMutationKind.STAGE_PASSWORD_RESET,
            before(VaultRecoveryState.ACTIVE, true) to VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
        cases.forEach { (old, kind) ->
            val valid = proposal(old, kind)
            listOf(valid.data.copy(retryLedger = reset.proposedLedger), valid.data.copy(retryLedgerEnvelope = blob(13)),
                valid.data.copy(installationMarker = blob(15)),
                valid.data.copy(mediaJournalEnvelope = blob(14), mediaJournalRevision = 1),
                valid.data.copy(catalogRevision = valid.data.catalogRevision + 1), valid.data.copy(catalogEnvelope = old.data!!.catalogEnvelope)).forEach {
                rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, kind, it), reservation) }
            }
        }
    }
    @Test fun nonResetStagesCannotReplacePrimaryOrPromoteUnconfirmedSlot() {
        val old = before()
        val stage = proposal(old, VaultCredentialMutationKind.STAGE_RECOVERY)
        rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, stage.kind,
            stage.data.copy(slots = stage.data.slots.map { if (it.kind == VaultSlotKind.PASSWORD) it.copy(envelope = blob(17)) else it }))) }
        rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, stage.kind,
            stage.data.copy(recoveryState = VaultRecoveryState.ACTIVE))) }
        val pending = before(VaultRecoveryState.PENDING)
        val activation = proposal(pending, VaultCredentialMutationKind.ACTIVATE_RECOVERY)
        rejected { VaultCredentialMutationValidation.requireValid(pending, proposal(pending, activation.kind,
            activation.data.copy(slots = activation.data.slots.map { if (it.kind == VaultSlotKind.RECOVERY) it.copy(envelope = blob(18)) else it }))) }
    }
    @Test fun countersFailClosedWithoutWrappingOrSkipping() {
        val old = before().copy(storeRevision = Long.MAX_VALUE - 1)
        rejected { VaultCredentialMutationValidation.requireValid(old, proposal(old, VaultCredentialMutationKind.STAGE_RECOVERY)) }
        val exhausted = before().copy(data = before().data!!.copy(catalogRevision = Long.MAX_VALUE - 1))
        rejected { VaultCredentialMutationValidation.requireValid(exhausted,
            proposal(exhausted, VaultCredentialMutationKind.STAGE_RECOVERY)) }
    }
}
