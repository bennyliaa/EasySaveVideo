package com.easysavevideo.save

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class StreamConservationTest {
    private fun sample(idr: Boolean = false, value: Int = 1) = byteArrayOf(0, 0, 1, if (idr) 0x65 else 0x41, value.toByte())
    private fun accumulator(times: LongArray = longArrayOf(0, 40_000, 80_000), value: Int = 1) =
        StreamSampleAccumulator(StreamSampleKind.AVC).apply {
            times.forEachIndexed { index, time -> add(time, if (index == 0) 1 else 0, sample(index == 0, value)) }
        }
    @Test fun threeAndFourByteAnnexBAndAvccHaveIdenticalCanonicalPayload() {
        val three = byteArrayOf(0, 0, 1, 9, 16, 0, 0, 1, 0x65, 23)
        val four = byteArrayOf(0, 0, 0, 1, 9, 16, 0, 0, 0, 1, 0x65, 23)
        val avcc = byteArrayOf(0, 0, 0, 2, 9, 16, 0, 0, 0, 2, 0x65, 23)
        assertArrayEquals(avcc, canonicalAvcSample(three)); assertArrayEquals(avcc, canonicalAvcSample(four))
        assertArrayEquals(avcc, canonicalAvcSample(avcc))
    }
    @Test fun framingZerosPreservedOnlyWhenPartOfNalByPinnedMuxRule() {
        val base = sample(true)
        assertArrayEquals(canonicalAvcSample(base), canonicalAvcSample(byteArrayOf(0, 0) + base + byteArrayOf(0, 0, 0)))
        assertFalse(canonicalAvcSample(base).contentEquals(canonicalAvcSample(base + byteArrayOf(0))))
        assertFalse(canonicalAvcSample(base).contentEquals(canonicalAvcSample(base + byteArrayOf(0, 0))))
    }
    @Test fun rejectEmptyTruncatedAmbiguousAndNonVclSamples() {
        listOf(byteArrayOf(), byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 1, 9, 16),
            byteArrayOf(0, 0, 0, 4, 0x65, 1), byteArrayOf(0, 0, 1, 0xe5.toByte(), 1),
            byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 1, 0x65, 1, 0, 0, 0, 3)).forEach {
            assertThrows(IllegalArgumentException::class.java) { canonicalAvcSample(it) }
        }
        // AVCC length 0x00000102 also resembles a three-byte Annex-B start code. Both
        // interpretations are valid but disagree; AUTO must not guess.
        val ambiguous = ByteArray(262) { 0x41 }.also { ByteBuffer.wrap(it).putInt(258) }
        assertThrows(IllegalArgumentException::class.java) { canonicalAvcSample(ambiguous) }
    }
    @Test fun canonicalizationDoesNotMutateCallerBuffer() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99) + sample(true) + byteArrayOf(99)).apply { position(1); limit(6) }
        val position = buffer.position(); val limit = buffer.limit()
        StreamSampleAccumulator(StreamSampleKind.AVC).add(0, 1, buffer)
        assertEquals(position, buffer.position()); assertEquals(limit, buffer.limit())
    }
    @Test fun everyPayloadPtsFlagAndSampleBoundaryIsBoundIntoSequenceDigest() {
        val original = accumulator().finish()
        assertNotEquals(original.sequenceSha256, accumulator(value = 2).finish().sequenceSha256)
        assertNotEquals(original.sequenceSha256, accumulator(longArrayOf(0, 40_011, 80_000)).finish().sequenceSha256)
        val flags = StreamSampleAccumulator(StreamSampleKind.AVC).apply {
            add(0, 1, sample(true)); add(40_000, 1, sample()); add(80_000, 0, sample())
        }.finish()
        assertNotEquals(original.sequenceSha256, flags.sequenceSha256)
        assertNotEquals(original.sequenceSha256, accumulator(longArrayOf(0, 40_000)).finish().sequenceSha256)
    }
    @Test fun muxAbsoluteClockAndExtractorRelativeClockRecoverExactTicks() {
        for (origin in listOf(0L, 1L, 7L, 101L, 10_000_000_007L, 90_000_000_007L)) {
            val expected = accumulator(longArrayOf(origin, origin + 33_333, origin + 66_667))
            val actual = StreamSampleAccumulator(StreamSampleKind.AVC, timestampsAreMuxInput = false)
            val baseTick = (origin * 90_000 + 500_000) / 1_000_000
            listOf(origin, origin + 33_333, origin + 66_667).forEachIndexed { index, pts ->
                val tick = (pts * 90_000 + 500_000) / 1_000_000 - baseTick
                actual.add(51 + tick * 1_000_000 / 90_000, if (index == 0) 1 else 0, sample(index == 0))
            }
            assertEquals(expected.finish(), actual.finish()); assertEquals(expected.videoTail(), actual.videoTail())
        }
    }
    @Test fun cfrEndpointIncludesLastDisplayDurationOnlyAfterCadenceEstablished() {
        val value = accumulator()
        assertEquals(StreamEndpoint(StreamEndpointRule.ESTABLISHED_CFR, 120_000, 120_000), value.endpoint())
        assertEquals(80_000, value.finish().lastSample.presentationTimeUs)
        assertEquals(StreamEndpointRule.BOUNDED_VFR, accumulator(longArrayOf(0, 40_000)).endpoint().rule)
    }
    @Test fun vfrEndpointIsBoundedByObservedCadenceAndAacAddsOneFrame() {
        assertEquals(StreamEndpoint(StreamEndpointRule.BOUNDED_VFR, 140_000, 160_000), accumulator(longArrayOf(0, 40_000, 100_000)).endpoint())
        val aac = StreamSampleAccumulator(StreamSampleKind.AAC, 48_000)
        repeat(3) { aac.add(it * 1_024_000_000L / 48_000, 1, byteArrayOf(1, 2, 3)) }
        assertEquals(StreamEndpoint(StreamEndpointRule.AAC_1024, 64_000, 64_000), aac.endpoint())
    }
    @Test fun sampleLimitsNoBTimingAndOnceOnlyFinishAreEnforced() {
        val a = accumulator(); val first = a.finish()
        assertSame(first, a.finish())
        assertThrows(IllegalStateException::class.java) { a.add(120_000, 0, sample()) }
        assertThrows(IllegalArgumentException::class.java) { StreamSampleAccumulator(StreamSampleKind.AVC).add(0, 0, sample()) }
        assertThrows(IllegalArgumentException::class.java) { accumulator(longArrayOf(0, 40_000, 40_000)) }
        assertThrows(IllegalArgumentException::class.java) { accumulator(longArrayOf(0, 40_000, 20_000)) }
        assertThrows(IllegalArgumentException::class.java) { accumulator(longArrayOf(0, 250_001)) }
        assertThrows(IllegalArgumentException::class.java) { StreamSampleAccumulator(StreamSampleKind.AVC).add(-1, 1, sample(true)) }
        assertThrows(IllegalArgumentException::class.java) { StreamSampleAccumulator(StreamSampleKind.AAC, 48_000).add(0, 0, ByteArray(StreamProofLimits.MAX_SAMPLE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { StreamSampleAccumulator(StreamSampleKind.AVC).finish() }
    }
    @Test fun tailIdentityTracksLatestUsableSyncIncludingFinalIdr() {
        val a = accumulator()
        assertEquals(0, a.videoTail().precedingSync.index)
        val b = StreamSampleAccumulator(StreamSampleKind.AVC).apply {
            add(0, 1, sample(true)); add(40_000, 0, sample()); add(80_000, 1, sample(true))
        }
        assertEquals(b.videoTail().lastSample, b.videoTail().precedingSync)
    }
    @Test fun audioBytesAndAvOriginAreConserved() {
        val c = testStreamConservation(true)
        assertTrue(c.matches(c.copy(audioStartOffsetUs = 102)))
        assertFalse(c.matches(c.copy(audioStartOffsetUs = 103)))
        assertFalse(c.matches(c.copy(audioChannelCount = 1)))
        assertFalse(c.matches(c.copy(audio = c.audio!!.copy(sequenceSha256 = "f".repeat(64)))))
        assertFalse(c.matches(c.copy(audio = null, audioStartOffsetUs = null, audioChannelCount = null)))
        val a = StreamSampleAccumulator(StreamSampleKind.AAC, 48_000)
        val b = StreamSampleAccumulator(StreamSampleKind.AAC, 48_000)
        repeat(3) { index -> a.add(index * 21_333L, 1, byteArrayOf(1, 2)); b.add(index * 21_333L, 1, byteArrayOf(1, 3)) }
        assertNotEquals(a.finish().sequenceSha256, b.finish().sequenceSha256)
    }
    @Test fun nearTailEarlyEosWrongSyncOrWrongSampleIdentityCannotMatch() {
        val c = testStreamConservation()
        val proof = StreamTailProof(c.videoTail.precedingSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true)
        assertTrue(proof.matches(c)); assertFalse(proof.copy(outputEos = false).matches(c))
        assertFalse(proof.copy(terminalOutputTimeUs = proof.terminalOutputTimeUs - 100_000).matches(c))
        assertTrue(proof.copy(terminalOutputTimeUs = proof.terminalOutputTimeUs + c.video.timestampToleranceUs).matches(c))
        assertFalse(proof.copy(terminalOutputTimeUs = proof.terminalOutputTimeUs + c.video.timestampToleranceUs + 1).matches(c))
        assertFalse(proof.copy(lastSample = proof.lastSample.copy(sha256 = "e".repeat(64))).matches(c))
        assertFalse(proof.copy(precedingSync = proof.precedingSync.copy(sha256 = "e".repeat(64))).matches(c))
    }
    @Test fun invalidDurableContractFieldsCannotBeConstructed() {
        val c = testStreamConservation()
        assertThrows(IllegalArgumentException::class.java) { c.video.copy(sampleCount = 250_001) }
        assertThrows(IllegalArgumentException::class.java) { c.video.copy(canonicalBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { c.video.copy(sequenceSha256 = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { c.video.copy(timescale = 1_000_000) }
        assertThrows(IllegalArgumentException::class.java) { c.video.lastSample.copy(index = -1) }
        assertThrows(IllegalArgumentException::class.java) { c.video.lastSample.copy(flags = 2) }
        assertThrows(IllegalArgumentException::class.java) { c.copy(audioStartOffsetUs = 0) }
        assertThrows(IllegalArgumentException::class.java) { c.copy(endpoint = StreamEndpoint(StreamEndpointRule.BOUNDED_VFR, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { c.videoTail.copy(precedingSync = c.videoTail.precedingSync.copy(flags = 0)) }
        assertThrows(IllegalArgumentException::class.java) { StreamEndpoint(StreamEndpointRule.ESTABLISHED_CFR, 1, 2) }
    }
}
