package com.easysavevideo.app.library.export

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

class ExportRecordCodecTest {
    private fun request(kind: ExportKind = ExportKind.MEDIA_STORE) = ExportRequest("operation", MediaReadRef("item", 3, 9), ExportDestination(kind, "grant"))
    @Test fun preparedRoundTrip() {
        val record = ExportRecord(request())
        assertEquals(record, ExportRecordCodec.decode(ExportRecordCodec.encode(record)))
    }
    @Test fun allNonterminalPhasesRoundTrip() {
        ExportPhase.entries.filter { !it.terminal }.forEach { phase ->
            val record = ExportRecord(request(), phase, ExportOutput("operation", ExportKind.MEDIA_STORE), ExportEvidence(9, "a".repeat(64)), true,
                ExportVerification.SOURCE_VERIFIED, ExportCleanup.UNCONFIRMED, ExportError.RELEASE_UNCONFIRMED)
            assertEquals(record, ExportRecordCodec.decode(ExportRecordCodec.encode(record)))
        }
    }
    @Test fun successRoundTripKeepsDestinationProof() {
        val record = ExportRecord(request(), ExportPhase.SUCCEEDED, ExportOutput("operation", ExportKind.MEDIA_STORE), ExportEvidence(9, "a".repeat(64)),
            verification = ExportVerification.DESTINATION_VERIFIED)
        assertEquals(record, ExportRecordCodec.decode(ExportRecordCodec.encode(record)))
    }
    @Test fun safUnverifiedIsNotSuccess() {
        val record = ExportRecord(request(ExportKind.SAF), ExportPhase.COPIED_UNVERIFIED, ExportOutput("operation", ExportKind.SAF), ExportEvidence(9, "a".repeat(64)),
            verification = ExportVerification.READBACK_UNSUPPORTED, error = ExportError.READBACK_UNSUPPORTED)
        assertEquals(record, ExportRecordCodec.decode(ExportRecordCodec.encode(record)))
        assertTrue(exportNotice(record).contains("不能确认"))
    }
    @Test fun rejectsTruncatedAndOversizedAndTrailing() {
        val encoded = ExportRecordCodec.encode(ExportRecord(request()))
        rejects { ExportRecordCodec.decode(encoded.copyOf(encoded.size - 1)) }
        rejects { ExportRecordCodec.decode(encoded + byteArrayOf(0)) }
        rejects { ExportRecordCodec.decode(ByteArray(ExportRecordCodec.MAX_BYTES + 1)) }
        rejects { ExportRecordCodec.decode(encoded.clone().apply { this[3] = 99 }) }
    }
    @Test fun sameOperationDifferentSelectionDifferentFingerprint() {
        assertNotEquals(request().fingerprint(), request().copy(source = MediaReadRef("item", 4, 9)).fingerprint())
    }
    @Test fun redactedDiagnosticStrings() {
        val record = ExportRecord(request())
        assertFalse(record.toString().contains("item"))
        assertFalse(record.request.toString().contains("grant"))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected corrupt codec failure") } catch (_: Exception) { } }
}
