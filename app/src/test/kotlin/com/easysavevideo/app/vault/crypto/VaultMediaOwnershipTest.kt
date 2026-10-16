package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportInput
import com.easysavevideo.library.export.ExportRelease
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Forged capabilities must reject before any real crypto or platform method. Fixed metadata only. */
class VaultMediaOwnershipTest {
    private fun context() = VaultOperationContext(1000, VaultMonotonicClock { 0 })
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private fun cipher(value: Int) = VaultCiphertext(byteArrayOf(value.toByte()))
    private val snapshot = VaultStoredSnapshot(identity, VaultStorePhase.READY, 3,
        VaultStagedData(listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true)),
            cipher(2), cipher(3), 1, cipher(4), VaultRetryLedger(0, 0, -1, null), cipher(5)))
    private val operation = VaultMediaOperation("public-operation", 1, VaultMediaKind.MOVE_IN)
    private val objectId = VaultMediaObjectIdentity("public-object", 1)
    private val ref = MediaReadRef("public-source", 1, 1)
    private val evidence = VaultMediaSourceEvidence(ref, ExportEvidence(4, "0".repeat(64)), "public.mp4", null, null, null)
    private val root = object : VaultRootCapability { override fun close() {} }
    private val prepared = object : VaultPreparedMediaImport { override fun close() {} }
    private val proof = object : VaultFinalizedMediaProof { override fun close() {} }
    private val mutation = object : VaultMediaMutation { override fun close() {} }
    private var platformCalls = 0
    private val file = object : VaultOwnedCiphertext {
        override val operationId = operation.operationId
        override val identity = objectId
        override fun append(bytes: ByteArray, offset: Int, length: Int) { platformCalls++ }
        override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int { platformCalls++; return -1 }
        override fun size(): Long { platformCalls++; return 4 }
        override fun forceAndCloseWrite(): ExportRelease { platformCalls++; return ExportRelease.RELEASED }
        override fun closeAndObserve(): ExportRelease { platformCalls++; return ExportRelease.RELEASED }
    }
    private val gate = object : VaultMediaPublicationGate {
        override fun requireCurrent() { platformCalls++ }
        override fun admit(): VaultMediaIoPermit { platformCalls++; error("forged-capability-reached-IO") }
    }
    private fun rejected(body: () -> Unit) {
        try { body(); fail("Forged identity accepted") }
        catch (e: VaultOperationException) { assertEquals(VaultFailure.REJECTED, e.category) }
        assertEquals(0, platformCalls)
    }
    @Test fun foreignPreparedCannotEncryptOrVerifyAnyFile() {
        val owner = AndroidVaultAuthentication()
        val source = object : VaultMoveSourceCapability {
            override val metadata = evidence
            override val input = object : ExportInput {
                override val stream get() = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).also { platformCalls++ }
                override fun close(): ExportRelease { platformCalls++; return ExportRelease.RELEASED }
            }
        }
        rejected { owner.encryptMedia(prepared, source, file, gate, context()) }
        rejected { owner.verifyFinalMedia(prepared, file, gate, context()) }
        owner.close()
    }
    @Test fun foreignRootCannotReadCatalogJournalOrStartImport() {
        val owner = AndroidVaultAuthentication()
        rejected { owner.authenticateCatalog(root, snapshot, context()) }
        rejected { owner.authenticateMediaJournal(root, snapshot, context()) }
        rejected { owner.prepareMediaImport(root, snapshot, evidence, operation, objectId, context()) }
        rejected { owner.openMediaRead(root, snapshot, VaultMediaReadRef(identity.vaultId, objectId.objectId, 1, 1), file, gate, context()) }
        owner.close()
    }
    @Test fun foreignProofAndMutationCannotSelfAttestStoreAuthority() {
        val owner = AndroidVaultAuthentication()
        rejected { owner.validateMediaMutation(snapshot, mutation, context()) }
        rejected { owner.validateFinalizedMedia(proof, file, context()) }
        rejected { owner.validateCommittedMedia(snapshot, operation, ref, file, proof, context()) }
        assertEquals(ExportRelease.RELEASED, owner.observeMediaDrain())
        owner.close()
    }
    @Test fun lateAcceptanceRevokesEveryNewOpaqueDisposable() {
        var closed = 0
        val values = listOf(object : VaultPreparedMediaImport { override fun close() { closed++ } },
            object : VaultMediaMutation { override fun close() { closed++ } },
            object : VaultFinalizedMediaProof { override fun close() { closed++ } })
        for (value in values) {
            try { finishVaultCryptoResult(value, VaultOperationContext(1, VaultMonotonicClock { 1 })) {}; fail("Late return") }
            catch (e: VaultOperationException) { assertEquals(VaultFailure.DEADLINE, e.category) }
        }
        assertEquals(3, closed)
    }
}
