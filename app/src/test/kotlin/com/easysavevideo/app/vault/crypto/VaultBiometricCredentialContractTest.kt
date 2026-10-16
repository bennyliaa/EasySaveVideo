package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.app.vault.storage.*
import com.easysavevideo.core.model.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportRelease
import com.google.crypto.tink.KeysetHandle
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Public fixed fixtures only; never creates keys, uses credentials, or invokes Android services. */
class VaultBiometricCredentialContractTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private val attempt = VaultAttemptIdentity(41, 3, 2)
    private val zero = VaultRetryLedger(0, 0, -1, null)
    private val reserved = VaultRetryLedger(1, 1, 0, 4)
    private fun blob(n: Int) = VaultCiphertext(ByteArray(32) { (n + it).toByte() })
    private fun slot(kind: VaultSlotKind, generation: Long = 1, seed: Int = kind.ordinal) = VaultSlotRecord(
        when (kind) { VaultSlotKind.BIOMETRIC -> "biometric-v1"; VaultSlotKind.RECOVERY -> "recovery-v1"; else -> "password-v1" },
        generation, kind, blob(seed), true)
    private fun snapshot(state: VaultBiometricState, recovery: VaultRecoveryState = VaultRecoveryState.ACTIVE,
        journal: Boolean = true, pending: Boolean = false): VaultStoredSnapshot {
        val slots = listOf(slot(VaultSlotKind.PASSWORD)) +
            (if (state == VaultBiometricState.NONE) emptyList() else listOf(slot(VaultSlotKind.BIOMETRIC, 2))) +
            (if (recovery == VaultRecoveryState.NONE) emptyList() else listOf(slot(VaultSlotKind.RECOVERY, 3)))
        return VaultStoredSnapshot(identity, VaultStorePhase.READY, 9, VaultStagedData(slots, blob(10), blob(20), 5,
            blob(30), if (pending) reserved else zero, blob(40), recovery,
            if (pending) VaultPendingPassword(slot(VaultSlotKind.PASSWORD, 5, 70), attempt, reserved) else null,
            if (journal) blob(50) else null, if (journal) 1 else 0, state))
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        catch (_: VaultStoreFault) { } catch (_: java.io.IOException) { }
        catch (_: VaultOperationException) { }
    }
    private fun entries() = listOf(VaultMediaEntry(VaultMediaObjectIdentity("public-object", 1), 1,
        ExportEvidence(2, "0".repeat(64)), VaultCiphertextEvidence(58, "1".repeat(64)), blob(90),
        "public.mp4", 5, 2, 2, VaultMediaOperation("public-move", 1, VaultMediaKind.MOVE_IN),
        MediaReadRef("public-source", 1, 1), sourceCleanupPending = true))

    @Test fun allBiometricStatesRoundTripWithRecoveryJournalAndPendingPassword() {
        for (state in VaultBiometricState.entries) for (recovery in VaultRecoveryState.entries)
            for (journal in listOf(false, true)) for (pending in listOf(false, true)) {
                if (pending && recovery != VaultRecoveryState.ACTIVE) continue
                val value = snapshot(state, recovery, journal, pending)
                VaultCryptoWire.requireCredentialShape(value.data!!)
                val encoded = VaultSnapshotCodec.encode(value)
                assertEquals(if (state == VaultBiometricState.NONE) { if (journal) 3 else 2 } else 4,
                    ByteBuffer.wrap(encoded).getInt(4))
                assertEquals(value, VaultSnapshotCodec.decode(encoded))
                assertArrayEquals(encoded, VaultSnapshotCodec.encode(VaultSnapshotCodec.decode(encoded)))
            }
    }
    @Test fun missingStateAndDuplicateOrUnboundSlotsReject() {
        val value = snapshot(VaultBiometricState.ACTIVE)
        val data = value.data!!
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(biometricState = VaultBiometricState.NONE))) }
        rejected { VaultCryptoWire.requireCredentialShape(data.copy(biometricState = VaultBiometricState.NONE)) }
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(slots = data.slots.filter { it.kind != VaultSlotKind.BIOMETRIC }))) }
        rejected { VaultCryptoWire.requireCredentialShape(data.copy(slots = data.slots.filter { it.kind != VaultSlotKind.PASSWORD })) }
        val biometric = data.slots.single { it.kind == VaultSlotKind.BIOMETRIC }
        for (bad in listOf(biometric.copy(required = false), biometric.copy(generation = 6), biometric.copy(slotId = "foreign-bio"))) {
            val changed = data.copy(slots = data.slots.map { if (it.kind == VaultSlotKind.BIOMETRIC) bad else it })
            rejected { VaultSnapshotCodec.encode(value.copy(data = changed)) }
            rejected { VaultCryptoWire.requireCredentialShape(changed) }
        }
        rejected { data.copy(slots = data.slots + biometric.copy(slotId = "duplicate")) }
    }
    @Test fun allV4TruncationsStateTrailerAndDowngradeReject() {
        val bytes = VaultSnapshotCodec.encode(snapshot(VaultBiometricState.PENDING, journal = false))
        bytes.indices.forEach { rejected { VaultSnapshotCodec.decode(bytes.copyOf(it)) } }
        rejected { VaultSnapshotCodec.decode(bytes + 0) }
        rejected { VaultSnapshotCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }) }
        rejected { VaultSnapshotCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 5, 0) }) }
        rejected { VaultSnapshotCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 5, 99) }) }
    }
    @Test fun stateEnvelopeGenerationAndIdentityAreAuthenticatedBindings() {
        val value = snapshot(VaultBiometricState.ACTIVE)
        val original = VaultCryptoWire.snapshotFingerprint(value)
        val plain = VaultMediaCatalogWire.encode(identity, value.data!!, entries())
        val data = value.data!!
        val changes = listOf(data.copy(biometricState = VaultBiometricState.REVOKED),
            data.copy(slots = data.slots.map { if (it.kind == VaultSlotKind.BIOMETRIC) it.copy(envelope = blob(99)) else it }),
            data.copy(slots = data.slots.map { if (it.kind == VaultSlotKind.BIOMETRIC) it.copy(generation = 4) else it }))
        changes.forEach { changed ->
            assertFalse(original.contentEquals(VaultCryptoWire.snapshotFingerprint(value.copy(data = changed))))
            rejected { VaultMediaCatalogWire.decode(identity, changed, plain) }
        }
        rejected { VaultMediaCatalogWire.decode(identity.copy(installationId = "other"), data, plain) }
    }
    @Test fun credentialResealsPreserveEveryAuthenticatedMediaFieldAndJournalAcrossVersions() {
        val before = snapshot(VaultBiometricState.NONE)
        val after = before.data!!.copy(slots = (before.data!!.slots + slot(VaultSlotKind.BIOMETRIC, 6)).sortedBy { it.kind.ordinal },
            catalogRevision = 6, biometricState = VaultBiometricState.PENDING)
        val legacyMedia = VaultMediaCatalogWire.encode(identity, before.data!!, entries())
        assertEquals(3, ByteBuffer.wrap(legacyMedia).getInt(4))
        val newMedia = VaultMediaCatalogWire.resealPlaintext(identity, before.data!!, after, legacyMedia)
        assertEquals(4, ByteBuffer.wrap(newMedia).getInt(4))
        assertEquals(entries(), VaultMediaCatalogWire.decode(identity, after, newMedia).entries)
        val active = after.copy(catalogRevision = 7, biometricState = VaultBiometricState.ACTIVE)
        assertEquals(entries(), VaultMediaCatalogWire.decode(identity, active,
            VaultMediaCatalogWire.resealPlaintext(identity, after, active, newMedia)).entries)
        rejected { VaultMediaCatalogWire.resealPlaintext(identity, after, active.copy(mediaJournalEnvelope = blob(99)), newMedia) }
    }
    private fun proposal(before: VaultStoredSnapshot, state: VaultBiometricState): VaultCredentialMutationProposal {
        val old = before.data!!
        val kind = when (state) {
            VaultBiometricState.PENDING -> VaultCredentialMutationKind.STAGE_BIOMETRIC
            VaultBiometricState.ACTIVE -> VaultCredentialMutationKind.ACTIVATE_BIOMETRIC
            VaultBiometricState.REVOKED -> VaultCredentialMutationKind.REVOKE_BIOMETRIC
            else -> error("fixture-state")
        }
        val data = old.copy(catalogRevision = old.catalogRevision + 1, catalogEnvelope = blob(100 + old.catalogRevision.toInt()), catalogKeysetEnvelope = blob(150 + old.catalogRevision.toInt()),
            slots = if (state == VaultBiometricState.PENDING)
                (old.slots.filter { it.kind != VaultSlotKind.BIOMETRIC } + slot(VaultSlotKind.BIOMETRIC, old.catalogRevision + 1)).sortedBy { it.kind.ordinal }
                else old.slots, biometricState = state)
        return VaultCredentialMutationProposal(kind, data, null, "public-biometric-operation")
    }
    @Test fun stageActivateRevokeShapePreservesPrimaryRecoveryLedgerAndJournal() {
        for (recovery in VaultRecoveryState.entries) for (initial in listOf(VaultBiometricState.NONE, VaultBiometricState.REVOKED)) {
            val before = snapshot(initial, recovery)
            val stage = VaultCredentialMutationValidation.requireValid(before, proposal(before, VaultBiometricState.PENDING))
            val active = VaultCredentialMutationValidation.requireValid(stage, proposal(stage, VaultBiometricState.ACTIVE))
            val revoked = VaultCredentialMutationValidation.requireValid(active, proposal(active, VaultBiometricState.REVOKED))
            assertEquals(stage.data!!.slots, active.data!!.slots)
            assertEquals(active.data!!.slots, revoked.data!!.slots)
            for (value in listOf(stage, active, revoked)) {
                assertEquals(before.data!!.slots.filter { it.kind != VaultSlotKind.BIOMETRIC }, value.data!!.slots.filter { it.kind != VaultSlotKind.BIOMETRIC })
                assertEquals(before.data!!.recoveryState, value.data!!.recoveryState)
                assertEquals(before.data!!.retryLedgerEnvelope, value.data!!.retryLedgerEnvelope)
                assertEquals(before.data!!.mediaJournalEnvelope, value.data!!.mediaJournalEnvelope)
            }
        }
    }
    @Test fun biometricMutationsCannotChangeUnrelatedSlotsStateOrLedger() {
        val before = snapshot(VaultBiometricState.NONE)
        val good = proposal(before, VaultBiometricState.PENDING)
        for (bad in listOf(good.data.copy(recoveryState = VaultRecoveryState.PENDING),
            good.data.copy(retryLedgerEnvelope = blob(99)), good.data.copy(mediaJournalEnvelope = blob(99)),
            good.data.copy(slots = good.data.slots.map { if (it.kind == VaultSlotKind.PASSWORD) it.copy(envelope = blob(99)) else it }))) {
            rejected { VaultCredentialMutationValidation.requireValid(before, VaultCredentialMutationProposal(good.kind, bad, null, good.operationId)) }
        }
        rejected { VaultCredentialMutationValidation.requireValid(snapshot(VaultBiometricState.ACTIVE), proposal(snapshot(VaultBiometricState.ACTIVE), VaultBiometricState.PENDING)) }
        rejected { VaultCredentialMutationValidation.requireValid(before.copy(storeRevision = Long.MAX_VALUE - 1), good) }
    }
    @Test fun foreignRootsOperationsMutationsAndAliasPermissionsHaveNoAuthority() {
        val owner = AndroidVaultAuthentication()
        val context = VaultOperationContext(395_000, VaultMonotonicClock { 0 })
        val host = Any(); val platform = Any(); val workflow = Any()
        fun binding(purpose: VaultBiometricPurpose) = VaultBiometricBinding(purpose, host, platform, 1, workflow) { true }
        val root = object : VaultRootCapability { override fun close() {} }
        val operation = object : VaultBiometricOperation {
            override val context = context
            override fun onDrain(listener: () -> Unit) {}
            override fun observeDrain() = ExportRelease.RELEASED
            override fun close() {}
        }
        val mutation = object : VaultCredentialMutation { override fun close() {} }
        val permit = object : VaultBiometricAliasRetirement { override fun close() {} }
        val none = snapshot(VaultBiometricState.NONE)
        try {
            rejected { owner.prepareBiometricEnrollment(root, none, attempt, binding(VaultBiometricPurpose.ENROLL_WRAP), context) }
            rejected { owner.prepareBiometricRevocation(root, snapshot(VaultBiometricState.ACTIVE), attempt, binding(VaultBiometricPurpose.RETIRE_ALIAS), context) }
            rejected { owner.prepareBiometricAliasRetirement(root, none, attempt, binding(VaultBiometricPurpose.RETIRE_ALIAS), context) }
            rejected { owner.retireBiometricAlias(none, permit, context) }
            rejected { owner.prepareBiometricActivation(snapshot(VaultBiometricState.PENDING), operation, binding(VaultBiometricPurpose.ENROLL_VERIFY), context) }
            rejected { owner.validateCredentialMutation(none, mutation, context) }
            rejected { owner.startBiometric(operation) { fail("foreign operation callback") } }
            assertEquals(ExportRelease.RELEASED, owner.observeBiometricDrain())
        } finally { owner.close() }
    }
    @Test fun pendingAndRevokedCannotUseOrdinaryBiometricUnlockEvenWithReservation() {
        val owner = AndroidVaultAuthentication()
        val context = VaultOperationContext(1000, VaultMonotonicClock { 0 })
        try { for (state in listOf(VaultBiometricState.NONE, VaultBiometricState.PENDING, VaultBiometricState.REVOKED)) {
            val before = snapshot(state)
            rejected { owner.prepareBiometricUnlock(before.copy(data = before.data!!.copy(retryLedger = reserved)), attempt,
                VaultBiometricBinding(VaultBiometricPurpose.UNLOCK, Any(), Any(), 1, null) { true }, context) }
        } } finally { owner.close() }
    }
    @Test fun childDeadlineCannotExtendAndCancellationSharesOriginalLineage() {
        var now = 0L
        val root = VaultOperationContext(395_000, VaultMonotonicClock { now })
        val first = root.bounded(120_000)
        now = 119_000
        val sql = first.bounded(10_000)
        assertEquals(120_000, sql.deadlineElapsedMillis)
        now = 121_000
        val second = root.bounded(120_000)
        assertEquals(241_000, second.deadlineElapsedMillis)
        assertTrue(first.sharesOperation(second))
        assertFalse(first.sharesOperation(VaultOperationContext(241_000, VaultMonotonicClock { now })))
        rejected { first.checkActive() }
        root.cancel(); rejected { second.checkActive() }
    }
}
