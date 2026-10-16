package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultRetryLedger
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Public synthetic ciphertext, no encryption, credentials, or Android database. */
class VaultMediaSnapshotCodecTest {
    private fun blob(seed: Int = 1) = VaultCiphertext(ByteArray(32) { (seed + it).toByte() })
    private fun original(): VaultStoredSnapshot = VaultStoredSnapshot(
        VaultPersistentIdentity("vault-one", "installation-one", "init-one", "esv.vault.v1.init-one.device", "esv.vault.v1.init-one.biometric"),
        VaultStorePhase.READY, 3, VaultStagedData(listOf(VaultSlotRecord("password", 1, VaultSlotKind.PASSWORD, blob(), true)),
            blob(2), blob(3), 1, blob(4), VaultRetryLedger(0, 0, -1, null), blob(5)))
    private fun media(): VaultStoredSnapshot = original().let { it.copy(storeRevision = 4,
        data = it.data!!.copy(mediaJournalEnvelope = blob(9), mediaJournalRevision = 1)) }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { } catch (_: VaultStoreFault) { } }
    @Test fun mediaEnvelopeUsesCanonicalV3AndPreservesWholeSnapshot() {
        val value = media(); val bytes = VaultSnapshotCodec.encode(value)
        assertEquals(3, ByteBuffer.wrap(bytes).getInt(4)); assertEquals(value, VaultSnapshotCodec.decode(bytes))
        assertArrayEquals(bytes, VaultSnapshotCodec.encode(VaultSnapshotCodec.decode(bytes)))
    }
    @Test fun legacyNoJournalRemainsV1() {
        val value = original(); val bytes = VaultSnapshotCodec.encode(value)
        assertEquals(1, ByteBuffer.wrap(bytes).getInt(4)); assertEquals(value, VaultSnapshotCodec.decode(bytes))
    }
    @Test fun journalAndRevisionMustAppearTogetherAndRemainBounded() {
        val value = media(); val data = value.data!!
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(mediaJournalRevision = 0))) }
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(mediaJournalEnvelope = null))) }
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(mediaJournalRevision = Long.MAX_VALUE))) }
        rejected { VaultSnapshotCodec.encode(value.copy(data = data.copy(mediaJournalEnvelope = VaultCiphertext(ByteArray(VaultMediaLimits.MAX_JOURNAL_BYTES + 1))))) }
    }
    @Test fun truncationTrailingBytesAndVersionDowngradeCannotStripJournal() {
        val bytes = VaultSnapshotCodec.encode(media())
        bytes.indices.forEach { rejected { VaultSnapshotCodec.decode(bytes.copyOf(it)) } }
        rejected { VaultSnapshotCodec.decode(bytes + 0) }
        rejected { VaultSnapshotCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }) }
    }
    @Test fun initialStagedSnapshotCannotAcquireMediaJournal() {
        rejected { VaultSnapshotCodec.encode(media().copy(phase = VaultStorePhase.STAGED, storeRevision = 2)) }
    }
}
