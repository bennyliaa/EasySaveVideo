package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Fixed public metadata. New in-memory diagnostics never allocate a new persisted wire code. */
class VaultFailureWireCompatibilityTest {
    private val old = listOf(VaultFailure.CANCELLED, VaultFailure.DEADLINE, VaultFailure.REJECTED,
        VaultFailure.UNAVAILABLE, VaultFailure.CORRUPT, VaultFailure.CONFLICT, VaultFailure.INCOMPLETE,
        VaultFailure.UNSUPPORTED, VaultFailure.RELEASE_UNKNOWN)
    private fun journal(failure: VaultFailure): VaultMediaJournal {
        val row = VaultCopyBatchItem("1".repeat(32), VaultMediaObjectIdentity("public-object", 1), 1,
            VaultCopyBatchOutcome.FAILED_NO_OUTPUT, failure)
        val rows = listOf(row); val batchId = "2".repeat(32)
        val batch = VaultCopyBatchState(batchId, 1, 1, VaultMediaJournalWire.planDigest(batchId, 1, 1, rows),
            rows, null, 0, 0, VaultCopyBatchStop.NONE, VaultCopyBatchStatus.FINISHED)
        return VaultMediaJournal(VaultMediaOperation(row.operationId, 1, VaultMediaKind.COPY_OUT),
            VaultMediaPhase.CANCELLED, 1, objectIdentity = row.identity, cancelRequested = true, batch = batch)
    }
    @Test fun everyLegacyFailureKeepsItsExactCodeAndRoundTripBytes() {
        val base = VaultMediaJournalWire.encode(journal(VaultFailure.UNAVAILABLE))
        assertEquals(2, ByteBuffer.wrap(base).getInt(4))
        old.forEachIndexed { code, category ->
            val expected = base.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 15, code) }
            val value = journal(category); val encoded = VaultMediaJournalWire.encode(value)
            assertArrayEquals(expected, encoded)
            assertEquals(value, VaultMediaJournalWire.decode(expected))
            assertArrayEquals(encoded, VaultMediaJournalWire.encode(VaultMediaJournalWire.decode(encoded)))
        }
    }
    @Test fun finiteDiagnosticsEncodeOnlyAsExistingCoarseFailuresAndDecodeWithoutRestoringAuthority() {
        val pairs = listOf(VaultFailure.DEVICE_KEY_UNAVAILABLE to VaultFailure.UNAVAILABLE,
            VaultFailure.STORE_UNREADABLE to VaultFailure.UNAVAILABLE,
            VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT to VaultFailure.CORRUPT,
            VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT to VaultFailure.CORRUPT)
        pairs.forEach { (detailed, coarse) ->
            val encoded = VaultMediaJournalWire.encode(journal(detailed))
            assertArrayEquals(VaultMediaJournalWire.encode(journal(coarse)), encoded)
            val decoded = VaultMediaJournalWire.decode(encoded)
            assertEquals(journal(coarse), decoded)
            assertFalse(requireNotNull(decoded.batch!!.items.single().failure).confirmedPrimaryFailure())
            assertArrayEquals(encoded, VaultMediaJournalWire.encode(decoded))
        }
    }
    @Test fun decoderStillRejectsNewDiagnosticOrdinalsAndUnknownFailureCodes() {
        val wire = VaultMediaJournalWire.encode(journal(VaultFailure.UNAVAILABLE))
        for (code in listOf(-2, 9, 10, 11, 12, 13, Int.MAX_VALUE)) {
            val invalid = wire.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 15, code) }
            assertThrows(IllegalArgumentException::class.java) { VaultMediaJournalWire.decode(invalid) }
        }
    }
}
