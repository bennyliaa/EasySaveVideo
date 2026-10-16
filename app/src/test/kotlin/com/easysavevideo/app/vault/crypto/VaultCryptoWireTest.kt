package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultRetryLedger
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class VaultCryptoWireTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public.device", "esv.vault.v1.public.biometric")
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun fixedPasswordEnvelopeRejectsUnboundedSizeTrailingAndProfileChanges() {
        val salt = ByteArray(16) { it.toByte() }
        val bytes = VaultCryptoWire.passwordEnvelope(salt, byteArrayOf(1, 2, 3))
        val parsed = VaultCryptoWire.parsePasswordEnvelope(bytes)
        assertArrayEquals(salt, parsed.salt)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.encryptedKeyset)
        for (bad in listOf(bytes + byteArrayOf(0), bytes.copyOf(bytes.size - 1), ByteArray(16_385),
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(8, 1) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(32, Int.MAX_VALUE) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(32, -1) })) rejects { VaultCryptoWire.parsePasswordEnvelope(bad) }
    }
    @Test fun aadBindsEveryIdentityAndSlotDimensionAndHasDistinctPurposes() {
        val salt = ByteArray(16)
        fun aad(id: VaultPersistentIdentity = identity, kind: VaultSlotKind = VaultSlotKind.PASSWORD,
            slot: String = "password-v1", generation: Long = 1, revision: Long = 1, s: ByteArray = salt,
            purpose: VaultAadPurpose = VaultAadPurpose.PASSWORD_ROOT) =
            VaultCryptoWire.aad(id, purpose, kind, slot, generation, revision, s)
        val baseline = aad()
        val changes = listOf(aad(id = identity.copy(vaultId = "other")), aad(id = identity.copy(installationId = "other")),
            aad(id = identity.copy(initializationOperationId = "other")), aad(kind = VaultSlotKind.PIN),
            aad(slot = "other"), aad(generation = 2), aad(revision = 2), aad(s = ByteArray(16) { 1 }),
            aad(purpose = VaultAadPurpose.DEVICE_PASSWORD_OUTER))
        changes.forEach { assertFalse(baseline.contentEquals(it)) }
        assertEquals(VaultAadPurpose.entries.size, VaultAadPurpose.entries.map { aad(purpose = it).toList() }.distinct().size)
        rejects { aad(id = identity.copy(rootVersion = 2)) }
        rejects { aad(id = identity.copy(schemaVersion = 2)) }
    }
    @Test fun retrySerializationRejectsInconsistentTierOrReservation() {
        VaultCryptoWire.ledger(VaultRetryLedger(0, 0, -1, null))
        VaultCryptoWire.ledger(VaultRetryLedger(1, 1, 0, 2))
        rejects { VaultCryptoWire.ledger(VaultRetryLedger(0, 1, 0, null)) }
        rejects { VaultCryptoWire.ledger(VaultRetryLedger(0, 0, -1, 1)) }
        rejects { VaultCryptoWire.ledger(VaultRetryLedger(0, 4, 0, 1)) }
        rejects { VaultCryptoWire.ledger(VaultRetryLedger(-1, 0, -1, null)) }
    }
    @Test fun rejectedFinalAcceptanceClosesUnreturnedRootAndProof() {
        var rootClosed = false
        var proofClosed = false
        val root = object : VaultRootCapability { override fun close() { rootClosed = true } }
        val proof = object : VaultStagedVerification {
            override fun requireMatches(snapshot: VaultStoredSnapshot) { }
            override fun close() { proofClosed = true }
        }
        val expired = VaultOperationContext(100, VaultMonotonicClock { 100 })
        try { finishVaultCryptoResult(root, expired) { }; fail("Expiry") }
        catch (_: VaultOperationException) { }
        val active = VaultOperationContext(100, VaultMonotonicClock { 0 })
        try { finishVaultCryptoResult(proof, active) { throw VaultOperationException(VaultFailure.CANCELLED) }; fail("Close") }
        catch (_: VaultOperationException) { }
        assertTrue(rootClosed); assertTrue(proofClosed)
    }
    @Test fun foreignProofAndRootCannotSelfAuthorizeAndPreflightDoesNotTouchAndroid() {
        val owner = AndroidVaultAuthentication()
        val ctx = VaultOperationContext(100, VaultMonotonicClock { 0 })
        val snapshot = VaultStoredSnapshot(identity, VaultStorePhase.STAGED, 1, null)
        val forged = object : VaultStagedVerification { override fun requireMatches(snapshot: VaultStoredSnapshot) { }
            override fun close() { } }
        try { owner.validateStagedVerification(snapshot, forged); fail("Foreign proof") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
        val forgedRoot = object : VaultRootCapability { override fun close() { } }
        try { owner.readEmptyCatalog(forgedRoot, snapshot, ctx); fail("Foreign root") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
        VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-password-123".toCharArray()).use(owner::validatePasswordInput)
        VaultCredentialInput(VaultCredentialKind.PIN, "123".toCharArray()).use {
            try { owner.validatePasswordInput(it); fail("Short PIN") }
            catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
        }
        owner.close()
        try { owner.validateStagedVerification(snapshot, forged); fail("Closed owner") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.CANCELLED, failure.category) }
    }
}
