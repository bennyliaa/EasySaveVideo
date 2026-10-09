package com.easysavevideo.app.editor.storage

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.*
import org.junit.Assert.*
import org.junit.Test

class EditRecordCodecTest {
    private val source = MediaReadRef("source", 3, 7)
    private val request = EditRequest("edit", listOf(EditSegment(source, 0, 1_000_000),
        EditSegment(source, 1_000_000, 2_000_000, NormalizedCrop(0.1, 0.2, 0.9, 0.8))), EditCompression.SAVE_SPACE)
    @Test fun genuineFrozenV1AndV2ProductionRecordsRemainReadable() {
        for (version in listOf("v1", "v2")) {
            fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/edit-codec-legacy/$version/$name.bin")).use { it.readBytes() }
            val request = EditRecordCodec.request(fixture("request"))
            assertEquals("legacy-edit", request.operationId); assertEquals(EditCompression.SAVE_SPACE, request.compression)
            assertEquals(listOf(MediaReadRef("first", 3, 7), MediaReadRef("second", 5, 9)), request.segments.map { it.source })
            assertEquals(NormalizedCrop(0.1, 0.2, 0.9, 0.8), request.segments[0].crop)
            assertFalse(request.recordedInputs)
            val expected = EditRecordCodec.expectation(fixture("expectation"))
            assertEquals(0, expected.recordedSegmentCount); assertEquals(30L, expected.videoFrameCount)
            assertEquals(1_000_000L, expected.durationUs)
            val proof = EditRecordCodec.evidence(fixture("evidence"))
            assertEquals(expected, proof.expectation); assertEquals(1, proof.verificationVersion)
            assertTrue(proof.validFor(EditOutputRef("legacy-edit", "legacy-artifact"), expected))
        }
    }
    @Test fun thirtyThreePrivateInputsRoundTripWithoutCatalogAliases() {
        val value = EditRequest("recorded-edit", List(33) { index ->
            EditSegment(com.easysavevideo.library.RecordedSegmentReadRef("session", "segment-$index", 7, index), 0, 1_000_000)
        })
        val decoded = EditRecordCodec.request(EditRecordCodec.request(value))
        assertEquals(value.fingerprint(), decoded.fingerprint()); assertEquals(value.segments, decoded.segments)
    }
    @Test fun typedRecipeRoundTripPreservesIdentityOrderAndCrop() {
        val decoded = EditRecordCodec.request(EditRecordCodec.request(request))
        assertEquals(request.fingerprint(), decoded.fingerprint()); assertEquals(request.segments, decoded.segments)
    }
    @Test fun appendedTruncatedOversizedAndUnknownVersionRejected() {
        val bytes = EditRecordCodec.request(request)
        listOf(bytes + 0, bytes.copyOf(bytes.size - 1), ByteArray(16385), bytes.clone().also { it[3] = 99 }).forEach { bad ->
            try { EditRecordCodec.request(bad); fail("Expected rejection") } catch (_: Exception) { }
        }
    }
    @Test fun expectationRoundTripRetainsExplicitAudioAndTolerance() {
        val value = EditOutputExpectation(2_000_000, 320, 240, 30, EditAudioProfile.Aac(48000, 2), 66668, 85334)
        assertEquals(value, EditRecordCodec.expectation(EditRecordCodec.expectation(value)))
    }
    @Test fun editedSourcesNeverBecomeNetworkOrRecordRequest() {
        val bytes = EditRecordCodec.request(request)
        assertFalse(String(bytes).contains("https://")); assertFalse(String(bytes).contains("RECORD"))
        assertEquals(source, EditRecordCodec.request(bytes).segments.first().source)
    }
    @Test fun evidenceRoundTripNeverNormalizesAnUnknownVerificationVersion() {
        val expected = EditOutputExpectation(1_000_000, 320, 240, 30, EditAudioProfile.Silent, 33334, 0, videoFrameCount = 30)
        val evidence = EditVerificationEvidence(EditOutputRef("operation", "artifact"), expected, 1024, "a".repeat(64),
            1_000_000, 320, 240, 0, "video/avc", null, null, null, 0, 966666, 30, 0, 30, 0,
            true, false, true, true, verificationVersion = 99)
        val decoded = EditRecordCodec.evidence(EditRecordCodec.evidence(evidence))
        assertEquals(99, decoded.verificationVersion)
        assertFalse(decoded.validFor(evidence.output, expected))
    }
    @Test fun exactSnappedFrameCountSurvivesExpectationEncoding() {
        val expected = EditOutputExpectation(933332, 320, 240, 30, EditAudioProfile.Silent, 66668, 0, videoFrameCount = 28)
        val decoded = EditRecordCodec.expectation(EditRecordCodec.expectation(expected))
        assertEquals(28L, decoded.videoFrameCount); assertEquals(933332L, decoded.durationUs)
    }

}
