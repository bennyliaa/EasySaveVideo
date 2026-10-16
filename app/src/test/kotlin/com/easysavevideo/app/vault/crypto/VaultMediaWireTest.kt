package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Bounded public metadata only. Does not instantiate or execute production crypto. */
class VaultMediaWireTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private fun cipher(n: Int) = VaultCiphertext(byteArrayOf(n.toByte()))
    private val primary = VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true)
    private fun base() = VaultStagedData(listOf(primary), cipher(2), cipher(3), 1, cipher(4),
        VaultRetryLedger(0, 0, -1, null), cipher(5))
    private val source = MediaReadRef("public-source", 1, 1)
    private val operation = VaultMediaOperation("public-move", 1, VaultMediaKind.MOVE_IN)
    private val objectId = VaultMediaObjectIdentity("public-object", 1)
    private val plain = ExportEvidence(2, "0".repeat(64))
    private val encrypted = VaultCiphertextEvidence(58, "1".repeat(64))
    private fun entry(name: String = "public.mp4") = VaultMediaEntry(objectId, 1, plain, encrypted,
        cipher(8), name, 5, 2, 2, operation, source)
    private fun snapshot(data: VaultStagedData) = VaultStoredSnapshot(identity, VaultStorePhase.READY, 9, data)
    private fun journal() = VaultMediaJournal(operation, VaultMediaPhase.PREPARED, 1, source, objectId)
    private fun rejects(body: () -> Unit) {
        try { body(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { } catch (_: com.easysavevideo.library.LibraryFailure) { }
    }

    @Test fun mediaAadSeparatesPurposeVaultObjectGenerationAndAuthenticatedExtentDigest() {
        val aad = VaultMediaCatalogWire.aad(identity, objectId, plain, 1)
        val changes = listOf(VaultMediaCatalogWire.aad(identity, objectId, plain, 2),
            VaultMediaCatalogWire.aad(identity.copy(vaultId = "other"), objectId, plain, 1),
            VaultMediaCatalogWire.aad(identity, objectId.copy(objectId = "other"), plain, 1),
            VaultMediaCatalogWire.aad(identity, objectId.copy(generation = 2), plain, 1),
            VaultMediaCatalogWire.aad(identity, objectId, plain.copy(bytes = 3), 1),
            VaultMediaCatalogWire.aad(identity, objectId, plain.copy(sha256 = "f".repeat(64)), 1))
        changes.forEach { assertFalse(aad.contentEquals(it)) }
        rejects { VaultMediaCatalogWire.aad(identity, objectId, plain, 3) }
        rejects { VaultMediaCatalogWire.aad(identity.copy(rootVersion = 2), objectId, plain, 1) }
        rejects { VaultMediaCatalogWire.aad(identity, objectId.copy(generation = Long.MAX_VALUE), plain, 1) }
    }
    @Test fun journalAadSeparatesHeaderPayloadRevisionAndEncryptedOperationBinding() {
        val binding = VaultMediaJournalWire.binding(journal())
        val aad = VaultMediaJournalWire.aad(identity, 1, 2, binding)
        assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity, 1, 1)))
        assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity, 2, 2, binding)))
        assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity.copy(vaultId = "other"), 1, 2, binding)))
        for (changed in listOf(journal().copy(operation = operation.copy(operationId = "other")),
            journal().copy(operation = operation.copy(sequence = 2)), journal().copy(expectedCatalogRevision = 2),
            journal().copy(objectIdentity = objectId.copy(generation = 2)))) {
            assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity, 1, 2, VaultMediaJournalWire.binding(changed))))
        }
        rejects { VaultMediaJournalWire.aad(identity, 1, 2) }
        rejects { VaultMediaJournalWire.aad(identity, 1, 1, binding) }
        rejects { VaultMediaJournalWire.aad(identity, 0, 1) }
    }
    @Test fun legacyV1AndV2KeepExactAcceptedBytes() {
        val legacy = base()
        assertTrue(VaultMediaCatalogWire.decode(identity, legacy, VaultCryptoWire.emptyCatalog(identity, legacy)).entries.isEmpty())
        val v2 = legacy.copy(catalogRevision = 2)
        assertTrue(VaultMediaCatalogWire.decode(identity, v2, VaultCryptoWire.emptyCatalog(identity, v2)).entries.isEmpty())
        rejects { VaultMediaCatalogWire.decode(identity, v2, VaultCryptoWire.emptyCatalog(identity, legacy)) }
    }
    @Test fun v3RoundTripContainsEveryFieldAndDefensiveList() {
        val values = mutableListOf(entry())
        val encoded = VaultMediaCatalogWire.encode(identity, base(), values)
        val decoded = VaultMediaCatalogWire.decode(identity, base(), encoded)
        values.clear()
        assertEquals(listOf(entry()), decoded.entries)
        assertEquals(3, ByteBuffer.wrap(encoded).getInt(4))
        assertArrayEquals(encoded, VaultMediaCatalogWire.encode(identity, base(), decoded.entries))
    }
    @Test fun v3RejectsUnknownVersionTrailingBytesAndNoncanonicalBoolean() {
        val wire = VaultMediaCatalogWire.encode(identity, base(), listOf(entry()))
        rejects { VaultMediaCatalogWire.decode(identity, base(), wire + 0) }
        rejects { VaultMediaCatalogWire.decode(identity, base(), wire.copyOf().also { ByteBuffer.wrap(it).putInt(4, 4) }) }
        rejects { VaultMediaCatalogWire.decode(identity, base(), wire.copyOf().also { it[it.lastIndex] = 2 }) }
        for (length in listOf(0, 1, 7, wire.size - 1)) rejects { VaultMediaCatalogWire.decode(identity, base(), wire.copyOf(length)) }
    }
    @Test fun v3CountCheckedBeforeAllocationAndDuplicateObjectRejected() {
        val prefix = VaultCryptoWire.mediaCatalogPrefix(identity, base())
        val wire = VaultMediaCatalogWire.encode(identity, base(), listOf(entry()))
        for (count in listOf(-1, Int.MAX_VALUE, 9999)) {
            rejects { VaultMediaCatalogWire.decode(identity, base(), wire.copyOf().also { ByteBuffer.wrap(it).putInt(prefix.size, count) }) }
        }
        rejects { VaultMediaCatalogWire.encode(identity, base(), listOf(entry(), entry().copy(identity = objectId.copy(generation = 2)))) }
    }
    @Test fun v3CapacityIsBoundedAndNameUsesOrdinaryLibraryPolicy() {
        val large = (1..8).map { entry().copy(identity = VaultMediaObjectIdentity("object-$it", 1),
            wrappedKeyset = VaultCiphertext(ByteArray(16 * 1024) { 1 })) }
        rejects { VaultMediaCatalogWire.encode(identity, base(), large) }
        rejects { VaultMediaCatalogWire.encode(identity, base(), listOf(entry("bad/name"))) }
        rejects { VaultMediaCatalogWire.encode(identity, base(), listOf(entry("a".repeat(256)))) }
    }
    @Test fun v3BindsCredentialsAndVaultIdentity() {
        val wire = VaultMediaCatalogWire.encode(identity, base(), listOf(entry()))
        rejects { VaultMediaCatalogWire.decode(identity.copy(vaultId = "other"), base(), wire) }
        rejects { VaultMediaCatalogWire.decode(identity, base().copy(catalogRevision = 2), wire) }
        rejects { VaultMediaCatalogWire.decode(identity, base().copy(slots = listOf(primary.copy(envelope = cipher(9)))), wire) }
    }
    @Test fun nonemptyCatalogAndJournalSurviveEveryCredentialResealShape() {
        val before = base().copy(mediaJournalEnvelope = cipher(7), mediaJournalRevision = 8)
        val recovery = VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, cipher(10), true)
        val pending = before.copy(slots = listOf(primary, recovery), catalogRevision = 2, recoveryState = VaultRecoveryState.PENDING)
        val active = pending.copy(catalogRevision = 3, recoveryState = VaultRecoveryState.ACTIVE)
        val reservation = VaultRetryLedger(1, 1, 0, 1)
        val reset = active.copy(catalogRevision = 4, pendingPassword = VaultPendingPassword(
            primary.copy(generation = 4, envelope = cipher(11)), VaultAttemptIdentity(1, 1, 1), reservation))
        val completed = reset.copy(slots = listOf(reset.pendingPassword!!.slot), catalogRevision = 5,
            recoveryState = VaultRecoveryState.NONE, pendingPassword = null)
        var oldData = before
        var wire = VaultMediaCatalogWire.encode(identity, before, listOf(entry()))
        for (next in listOf(pending, active, reset, completed)) {
            val resealed = VaultMediaCatalogWire.resealPlaintext(identity, oldData, next, wire)
            assertEquals(listOf(entry()), VaultMediaCatalogWire.decode(identity, next, resealed).entries)
            assertEquals(before.mediaJournalEnvelope, next.mediaJournalEnvelope)
            wire = resealed; oldData = next
        }
        rejects { VaultMediaCatalogWire.resealPlaintext(identity, oldData,
            oldData.copy(catalogRevision = 6, mediaJournalEnvelope = cipher(9)), wire) }
    }
    @Test fun journalChangesFullFingerprintButNotCatalogRootBinding() {
        val before = base()
        val after = before.copy(mediaJournalEnvelope = cipher(7), mediaJournalRevision = 1)
        assertFalse(VaultCryptoWire.snapshotFingerprint(snapshot(before)).contentEquals(VaultCryptoWire.snapshotFingerprint(snapshot(after))))
        assertArrayEquals(VaultCryptoWire.catalogFingerprint(snapshot(before)), VaultCryptoWire.catalogFingerprint(snapshot(after)))
        assertFalse(VaultCryptoWire.snapshotFingerprint(snapshot(after)).contentEquals(
            VaultCryptoWire.snapshotFingerprint(snapshot(after.copy(mediaJournalRevision = 2)))))
    }
    @Test fun journalRoundTripAndPrivateBindingRetainExactIdentifiers() {
        val value = journal().copy(phase = VaultMediaPhase.COMMITTED_SOURCE_PENDING, expectedCatalogRevision = 2,
            plaintextEvidence = plain, ciphertextEvidence = encrypted)
        assertEquals(value, VaultMediaJournalWire.decode(VaultMediaJournalWire.encode(value)))
        val header = VaultMediaJournalWire.decodeBinding(VaultMediaJournalWire.binding(value))
        assertEquals(value.operation, header.operation); assertEquals(value.objectIdentity, header.objectIdentity)
        assertEquals(value.source, header.source); assertEquals(2L, header.expectedCatalogRevision)
    }
    @Test fun journalRejectsWrongVersionTrailerAndHugeUntrustedLength() {
        val wire = VaultMediaJournalWire.encode(journal())
        rejects { VaultMediaJournalWire.decode(wire + 0) }
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }) }
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }) }
    }
    @Test fun journalTargetIsOnlyEncryptedEvidenceAndCanonicalBoundedText() {
        val copy = journal().copy(operation = operation.copy(kind = VaultMediaKind.COPY_OUT), source = null,
            phase = VaultMediaPhase.TARGET_BOUND, target = VaultMediaTargetRecord(operation.operationId, "public-grant", "public-nonce", "content://public/fixture", 3))
        assertEquals(copy, VaultMediaJournalWire.decode(VaultMediaJournalWire.encode(copy)))
        rejects { VaultMediaJournalWire.encode(copy.copy(target = copy.target!!.copy(grantId = "bad/path"))) }
        rejects { VaultMediaJournalWire.encode(journal().copy(target = copy.target)) }
    }
    @Test fun monotonicSequenceRejectsReplayAndNonterminalReplacement() {
        VaultMediaJournalWire.requireSuccessor(null, journal())
        val next = journal().copy(operation = operation.copy(operationId = "next", sequence = 2))
        rejects { VaultMediaJournalWire.requireSuccessor(journal(), next) }
        VaultMediaJournalWire.requireSuccessor(journal().copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain, ciphertextEvidence = encrypted), next)
        rejects { VaultMediaJournalWire.requireSuccessor(journal().copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain, ciphertextEvidence = encrypted), next.copy(operation = next.operation.copy(sequence = 1))) }
        rejects { VaultMediaJournalWire.requireSuccessor(null, next) }
    }
    @Test fun phaseMutationCannotReplaceSourceObjectOrEstablishedEvidence() {
        val reserved = journal().copy(phase = VaultMediaPhase.SOURCE_RESERVED, plaintextEvidence = plain)
        VaultMediaJournalWire.requireSuccessor(journal(), reserved)
        rejects { VaultMediaJournalWire.requireSuccessor(journal(), reserved.copy(source = source.copy(revision = 2))) }
        rejects { VaultMediaJournalWire.requireSuccessor(journal(), reserved.copy(objectIdentity = objectId.copy(generation = 2))) }
        rejects { VaultMediaJournalWire.requireSuccessor(journal(), journal().copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain, ciphertextEvidence = encrypted)) }
        rejects { VaultMediaJournalWire.requireSuccessor(journal().copy(phase = VaultMediaPhase.VERIFIED, plaintextEvidence = plain),
            journal().copy(phase = VaultMediaPhase.COMMITTED_SOURCE_PENDING, plaintextEvidence = plain.copy(bytes = 3), ciphertextEvidence = encrypted)) }
    }
    @Test fun eachOperationKindRejectsOtherKindsPhasesAndMissingEvidence() {
        for (phase in listOf(VaultMediaPhase.PICKING_TARGET, VaultMediaPhase.TARGET_BOUND, VaultMediaPhase.COPYING,
            VaultMediaPhase.VERIFYING, VaultMediaPhase.PUBLISH_INTENT)) rejects { VaultMediaJournalWire.encode(journal().copy(phase = phase)) }
        val copy = journal().copy(operation = operation.copy(kind = VaultMediaKind.COPY_OUT), source = null)
        for (phase in listOf(VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.ENCRYPTING, VaultMediaPhase.VERIFIED,
            VaultMediaPhase.COMMITTED_SOURCE_PENDING)) rejects { VaultMediaJournalWire.encode(copy.copy(phase = phase)) }
        rejects { VaultMediaJournalWire.encode(journal().copy(phase = VaultMediaPhase.SOURCE_RESERVED)) }
        rejects { VaultMediaJournalWire.encode(copy.copy(phase = VaultMediaPhase.TARGET_BOUND)) }
        rejects { VaultMediaJournalWire.encode(journal().copy(phase = VaultMediaPhase.COMMITTED_SOURCE_PENDING)) }
    }
    private fun pendingCopy(phase: VaultMediaPhase = VaultMediaPhase.PUBLISH_INTENT) = journal().copy(
        operation = operation.copy(kind = VaultMediaKind.COPY_OUT), phase = phase, source = null,
        target = VaultMediaTargetRecord(operation.operationId, "public-grant", "public-nonce", "content://public/fixture", 3),
        plaintextEvidence = plain)

    @Test fun credentialResealAllowsFreshIdenticalPendingPhaseAtCurrentCatalogRevision() {
        val previous = pendingCopy()
        val current = previous.copy(expectedCatalogRevision = 5)
        VaultMediaJournalWire.requireSuccessor(previous, current)
        VaultMediaJournalWire.requireSuccessor(current, current)
        assertEquals(previous.copy(expectedCatalogRevision = 5), current)
        // The authenticated payload/AAD changes; an old proof is not being reused.
        assertFalse(VaultMediaJournalWire.encode(previous).contentEquals(VaultMediaJournalWire.encode(current)))
        assertFalse(VaultMediaJournalWire.binding(previous).contentEquals(VaultMediaJournalWire.binding(current)))
        val targetBound = pendingCopy(VaultMediaPhase.TARGET_BOUND)
        VaultMediaJournalWire.requireSuccessor(targetBound, targetBound.copy(expectedCatalogRevision = 5))
    }
    @Test fun samePhaseRevisionRefreshCannotRegressOrReplaceAnyBoundObject() {
        val previous = pendingCopy().copy(expectedCatalogRevision = 5)
        rejects { VaultMediaJournalWire.requireSuccessor(previous, previous.copy(expectedCatalogRevision = 4)) }
        val changed = listOf(previous.copy(expectedCatalogRevision = 6, objectIdentity = objectId.copy(generation = 2)),
            previous.copy(expectedCatalogRevision = 6, target = previous.target!!.copy(nonce = "different")),
            previous.copy(expectedCatalogRevision = 6, plaintextEvidence = plain.copy(bytes = 3)),
            previous.copy(expectedCatalogRevision = 6, operation = previous.operation.copy(sequence = 2)),
            previous.copy(expectedCatalogRevision = 6, cancelRequested = true),
            previous.copy(expectedCatalogRevision = 6, ciphertextEvidence = encrypted))
        changed.forEach { rejects { VaultMediaJournalWire.requireSuccessor(previous, it) } }
    }
    @Test fun revisionRefreshDoesNotSkipPhaseOrAuthoriseOperationRestart() {
        val previous = pendingCopy(VaultMediaPhase.TARGET_BOUND)
        rejects { VaultMediaJournalWire.requireSuccessor(previous, previous.copy(expectedCatalogRevision = 2,
            phase = VaultMediaPhase.PUBLISH_INTENT)) }
        rejects { VaultMediaJournalWire.requireSuccessor(previous, previous.copy(expectedCatalogRevision = 2,
            operation = previous.operation.copy(operationId = "new-operation", sequence = 2))) }
        val move = journal().copy(phase = VaultMediaPhase.ENCRYPTING, plaintextEvidence = plain)
        rejects { VaultMediaJournalWire.requireSuccessor(move, move.copy(expectedCatalogRevision = 2,
            source = source.copy(revision = 2))) }
        rejects { VaultMediaJournalWire.requireSuccessor(move, move.copy(expectedCatalogRevision = 2)) }
        val complete = pendingCopy(VaultMediaPhase.COMPLETE)
        rejects { VaultMediaJournalWire.requireSuccessor(complete, complete.copy(expectedCatalogRevision = 2)) }
        val cancelled = pendingCopy(VaultMediaPhase.CANCELLED).copy(cancelRequested = true)
        rejects { VaultMediaJournalWire.requireSuccessor(cancelled, cancelled.copy(expectedCatalogRevision = 2)) }
    }
    @Test fun normalPhaseProgressAlsoRejectsCatalogRevisionRegression() {
        val previous = journal().copy(expectedCatalogRevision = 5)
        rejects { VaultMediaJournalWire.requireSuccessor(previous, previous.copy(expectedCatalogRevision = 4,
            phase = VaultMediaPhase.SOURCE_RESERVED, plaintextEvidence = plain)) }
        VaultMediaJournalWire.requireSuccessor(previous, previous.copy(expectedCatalogRevision = 6,
            phase = VaultMediaPhase.SOURCE_RESERVED, plaintextEvidence = plain))
    }

    @Test fun metadataSizeTypesHandlePlaintextLimitPlusStreamingOverhead() {
        val maxPlain = ExportEvidence(VaultMediaLimits.MAX_BYTES, "0".repeat(64))
        val maxCipher = VaultCiphertextEvidence(VaultMediaLimits.MAX_CIPHERTEXT_BYTES, "1".repeat(64))
        val maximum = entry().copy(plaintext = maxPlain, ciphertext = maxCipher)
        assertEquals(maximum, VaultMediaCatalogWire.decode(identity, base(), VaultMediaCatalogWire.encode(identity, base(), listOf(maximum))).entries.single())
        rejects { VaultCiphertextEvidence(VaultMediaLimits.MAX_CIPHERTEXT_BYTES + 1, "1".repeat(64)) }
        rejects { VaultMediaCatalogWire.encode(identity, base(), listOf(entry().copy(ciphertext = encrypted.copy(bytes = 1)))) }
    }
}
