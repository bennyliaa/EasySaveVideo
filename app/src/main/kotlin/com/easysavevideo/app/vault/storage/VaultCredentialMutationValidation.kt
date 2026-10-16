package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultRetryWrite
import com.easysavevideo.core.model.VaultRetryWriteKind

/** Necessary finite shape checks only. The injected auth registry is the sole proof authority. */
internal object VaultCredentialMutationValidation {
    fun requireValid(before: VaultStoredSnapshot, proposal: VaultCredentialMutationProposal,
                     confirmedReservation: VaultRetryWrite? = null): VaultStoredSnapshot {
        VaultSnapshotCodec.validate(before)
        require(before.phase == VaultStorePhase.READY)
        val old = requireNotNull(before.data)
        require(before.storeRevision < Long.MAX_VALUE - 1 && old.catalogRevision < Long.MAX_VALUE - 1)
        val after = VaultSnapshotCodec.freeze(before.copy(storeRevision = before.storeRevision + 1, data = proposal.data))
        val data = requireNotNull(after.data)
        require(data.catalogRevision == old.catalogRevision + 1)
        require(data.catalogKeysetEnvelope == old.catalogKeysetEnvelope && data.installationMarker == old.installationMarker)
        require(data.catalogEnvelope != old.catalogEnvelope)
        // Even the activation proof retains these exact old bytes. Only the separately validated
        // existing RESET write may change them when the store freezes the final whole record.
        require(data.retryLedger == old.retryLedger && data.retryLedgerEnvelope == old.retryLedgerEnvelope)
        // Media progress is independently AEAD-authenticated and included in whole-snapshot CAS.
        // Credential work preserves it byte-for-byte and can never retire a target pin.
        require(data.mediaJournalEnvelope == old.mediaJournalEnvelope && data.mediaJournalRevision == old.mediaJournalRevision)
        require(proposal.operationId.length in 1..128 && proposal.operationId.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        })
        when (proposal.kind) {
            VaultCredentialMutationKind.STAGE_RECOVERY -> {
                require(proposal.attempt == null)
                require(old.recoveryState == VaultRecoveryState.NONE || old.recoveryState == VaultRecoveryState.PENDING)
                require(data.recoveryState == VaultRecoveryState.PENDING)
                require(old.pendingPassword == null && data.pendingPassword == null)
                require(nonRecovery(old) == nonRecovery(data))
                val slot = data.slots.single { it.kind == VaultSlotKind.RECOVERY }
                require(slot.generation == data.catalogRevision)
                require(old.slots.filter { it.kind == VaultSlotKind.RECOVERY }.all { it.generation < slot.generation })
            }
            VaultCredentialMutationKind.ACTIVATE_RECOVERY -> {
                require(proposal.attempt == null)
                require(old.recoveryState == VaultRecoveryState.PENDING && data.recoveryState == VaultRecoveryState.ACTIVE)
                require(old.slots == data.slots && old.pendingPassword == null && data.pendingPassword == null)
            }
            VaultCredentialMutationKind.STAGE_PASSWORD_RESET -> {
                require(old.recoveryState == VaultRecoveryState.ACTIVE && data.recoveryState == VaultRecoveryState.ACTIVE)
                require(old.slots == data.slots)
                val reservation = requireReservation(old, proposal, confirmedReservation)
                val pending = requireNotNull(data.pendingPassword)
                require(pending.attempt == proposal.attempt && pending.reservation == reservation.proposedLedger)
                require(pending.slot.generation == data.catalogRevision)
                require(old.pendingPassword == null || old.pendingPassword.slot.generation < pending.slot.generation)
            }
            VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET -> {
                require(old.recoveryState == VaultRecoveryState.ACTIVE && data.recoveryState == VaultRecoveryState.NONE)
                val reservation = requireReservation(old, proposal, confirmedReservation)
                val pending = requireNotNull(old.pendingPassword)
                require(pending.attempt == proposal.attempt && pending.reservation == reservation.proposedLedger)
                require(data.pendingPassword == null)
                val unchanged = old.slots.filter { it.kind != VaultSlotKind.PASSWORD && it.kind != VaultSlotKind.PIN && it.kind != VaultSlotKind.RECOVERY }
                require(data.slots == (unchanged + pending.slot).sortedBy { it.kind.ordinal })
                // Equality includes the previously persisted and password-verified slot envelope.
                require(data.slots.single { it.kind == VaultSlotKind.PASSWORD } == pending.slot)
            }
        }
        return after
    }

    private fun nonRecovery(data: VaultStagedData) = data.slots.filter { it.kind != VaultSlotKind.RECOVERY }
    private fun requireReservation(data: VaultStagedData, proposal: VaultCredentialMutationProposal,
                                   confirmedReservation: VaultRetryWrite?): VaultRetryWrite {
        val attempt = requireNotNull(proposal.attempt)
        VaultSnapshotCodec.validateAttempt(attempt)
        val reservation = requireNotNull(confirmedReservation)
        require(reservation.kind == VaultRetryWriteKind.RESERVATION && reservation.identity.attempt == attempt)
        require(reservation.proposedLedger == data.retryLedger)
        require(data.retryLedger.outstandingReservationId == reservation.identity.writeId)
        return reservation
    }
}

/** One exact credential request and its original SQL budget; contains no root or plaintext input. */
internal class VaultCredentialWriteReceipt(
    private val mutation: VaultCredentialMutation,
    before: VaultStoredSnapshot,
    val proposed: VaultSqliteRecord,
    private val retryWrite: VaultRetryWrite?,
    val context: VaultOperationContext,
) {
    private val expectedBytes = VaultSnapshotCodec.encode(before)
    private var retired = false
    private var expectedRecord: VaultSqliteRecord? = null

    /** Bound once to the actual existing row immediately before the existing CAS path. */
    fun bindMutation(value: VaultSqliteMutation) {
        val expected = requireNotNull(value.expected)
        require(proposed.sameBytes(value.proposed) && expectedBytes.contentEquals(expected.payload()))
        val previous = expectedRecord
        require(previous == null || previous.sameBytes(expected))
        expectedRecord = expected
    }

    /** Only a confirmed old row and this original terminal SQL budget can retire unknown work. */
    fun retireConfirmedOld(value: VaultSqliteMutation, result: VaultSqliteWriteResult): Boolean {
        if (result != VaultSqliteWriteResult.CONFIRMED_OLD) return false
        val expected = expectedRecord ?: return false
        if (value.expected == null || !expected.sameBytes(value.expected) || !proposed.sameBytes(value.proposed)) return false
        try {
            context.checkActive()
            return false
        } catch (failure: VaultOperationException) {
            if (failure.category != VaultFailure.CANCELLED && failure.category != VaultFailure.DEADLINE) throw failure
        }
        retire() // Caller may clear only after this same-owner revocation succeeds.
        return true
    }

    fun retire() {
        if (retired) return
        // Never silently accept replacement if same-owner capability retirement fails.
        mutation.close()
        retired = true
    }
    fun owns(candidate: VaultCredentialMutation): Boolean = candidate === mutation
    fun matches(candidate: VaultCredentialMutation, before: VaultStoredSnapshot, record: VaultSqliteRecord,
                write: VaultRetryWrite?): Boolean = !retired && owns(candidate) && retryWrite == write &&
        proposed.sameBytes(record) && expectedBytes.contentEquals(VaultSnapshotCodec.encode(before))
    override fun toString(): String = "VaultCredentialWriteReceipt(REDACTED)"

    companion object {
        /** The adapter must validate candidate through its injected auth owner before admission. */
        fun admit(previous: VaultCredentialWriteReceipt?, candidate: VaultCredentialMutation,
                  before: VaultStoredSnapshot, proposed: VaultSqliteRecord, reset: VaultRetryWrite?,
                  context: VaultOperationContext, hasPendingMutation: Boolean): VaultCredentialWriteReceipt {
            if (previous != null && previous.owns(candidate)) {
                if (!previous.matches(candidate, before, proposed, reset)) throw VaultOperationException(VaultFailure.INCOMPLETE)
                previous.context.checkActive()
                context.checkActive()
                return previous
            }
            if (hasPendingMutation) throw VaultOperationException(VaultFailure.INCOMPLETE)
            val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
            // A bounded one-receipt store must permanently retire P when Q replaces it, so P
            // cannot later return and acquire a fresh SQL budget after a pre-write read failure.
            previous?.retire()
            bounded.checkActive()
            return VaultCredentialWriteReceipt(candidate, before, proposed, reset, bounded)
        }
    }
}
