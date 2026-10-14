package com.easysavevideo.save

import org.junit.Assert.*
import org.junit.Test

/** Pure integer and state tests. These do not execute a muxer, codec, Android or audio admission. */
class DirectMovClockTest {
    @Test fun exhaustiveFiniteClockPositionsRoundTripExactlyIncludingEveryEndpoint() {
        var positions = 0L
        val videoRates = listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 15, 16, 18, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60)
        assertEquals(videoRates.toSet(), DirectMovClock.VIDEO_FRAME_RATES)
        val clocks = videoRates.map { DirectMovClock.video(it, 1_800L * it) } +
            listOf(32_000, 44_100, 48_000).map { DirectMovClock.audio(it, 1_800L * it / 1_024) }
        for (clock in clocks) {
            for (index in 0..clock.sampleCount) {
                val ticks = index * clock.sampleDeltaTicks
                val expectedUs = ticks * 1_000_000 / clock.timescale
                val expectedMaximum = expectedUs + if (ticks * 1_000_000 % clock.timescale == 0L) 0 else 1
                assertEquals(ticks, clock.ticks(index))
                assertEquals(expectedUs, clock.presentationTimeUs(index))
                assertEquals(ticks, DirectMovClock.halfUpUsToTicks(expectedUs, clock.timescale))
                assertEquals(expectedUs, DirectMovClock.floorUs(ticks, clock.timescale))
                assertEquals(expectedMaximum, DirectMovClock.ceilUs(ticks, clock.timescale))
                positions++
            }
            assertTrue(clock.endpoint.maximumUs <= 1_800_000_000)
            assertTrue(clock.endpoint.maximumUs - clock.endpoint.minimumUs in 0..1)
        }
        assertEquals(1_094_770L, positions)
    }

    @Test fun goldenPtsEndpointsAndDoubleRoundedHeaders() {
        fun check(clock: DirectMovClock, pts: List<Long>, minimum: Long, maximum: Long, header: Long) {
            assertEquals(pts, (0 until clock.sampleCount).map(clock::presentationTimeUs))
            assertEquals(DirectTrackEndpoint(minimum, maximum), clock.endpoint)
            assertEquals(header, clock.headerDurationTicks)
        }
        check(DirectMovClock.video(30, 2), listOf(0, 33_333), 66_666, 66_667, 667)
        check(DirectMovClock.video(24, 3), listOf(0, 41_666, 83_333), 125_000, 125_000, 1_250)
        check(DirectMovClock.audio(32_000, 2), listOf(0, 32_000), 64_000, 64_000, 640)
        check(DirectMovClock.audio(44_100, 2), listOf(0, 23_219), 46_439, 46_440, 464)
        check(DirectMovClock.audio(48_000, 2), listOf(0, 21_333), 42_666, 42_667, 427)
        // Direct rational-to-10k rounding would give 51,780; the pinned two-stage result is 51,781.
        val doubleRounded = DirectMovClock.audio(44_100, 223)
        assertEquals(5_178_050L, doubleRounded.roundedDurationUs)
        assertEquals(51_781L, doubleRounded.headerDurationTicks)
        val video = DirectMovClock.video(30, 2)
        assertEquals(667L, DirectMovClock.movieHeaderDurationTicks(video, DirectMovClock.audio(48_000, 2)))
        assertEquals(51_781L, DirectMovClock.movieHeaderDurationTicks(video, doubleRounded))
        assertEquals(667L, DirectMovClock.movieHeaderDurationTicks(video))
    }

    @Test fun exactCapsAndRawTableDurationAreMandatory() {
        for (rate in DirectMovClock.VIDEO_FRAME_RATES) {
            val cap = rate * 1_800L
            val clock = DirectMovClock.video(rate, cap)
            assertEquals(1_800_000_000L, clock.endpoint.minimumUs)
            assertEquals(clock.endpointTicks, DirectMovClock.videoFromTable(90_000, 90_000L / rate,
                cap, 162_000_000).endpointTicks)
            reject { DirectMovClock.video(rate, cap + 1) }
        }
        for ((rate, cap) in listOf(32_000 to 56_250L, 44_100 to 77_519L, 48_000 to 84_375L)) {
            val clock = DirectMovClock.audio(rate, cap)
            assertEquals(clock.endpointTicks, DirectMovClock.audioFromTable(rate, 1_024, cap,
                cap * 1_024).endpointTicks)
            reject { DirectMovClock.audio(rate, cap + 1) }
        }
        reject { DirectMovClock.videoFromTable(600, 20, 2, 40) }
        reject { DirectMovClock.videoFromTable(90_000, 3_000, 2, 5_999) }
        reject { DirectMovClock.videoFromTable(90_000, 3_000, 2, 6_001) }
        reject { DirectMovClock.audioFromTable(44_100, 1_023, 2, 2_046) }
        reject { DirectMovClock.audioFromTable(44_100, 1_024, 2, 0) }
        reject { DirectMovClock.audio(96_000, 2) }
        for (rate in listOf(-1, 0, 7, 11, 29, 32, 59, 61, Int.MAX_VALUE)) reject { DirectMovClock.video(rate, 2) }
        for (count in listOf(-1L, 0L, 1L, Long.MAX_VALUE)) {
            reject { DirectMovClock.video(30, count) }
            reject { DirectMovClock.audio(44_100, count) }
        }
        for (delta in listOf(-1L, 0L, 2_812L, 2_813L, Long.MAX_VALUE)) {
            reject { DirectMovClock.videoFromTable(90_000, delta, 2, 1) }
        }
    }

    @Test fun zeroOriginExactPtsAndCheckedIntegerOperationsHaveNoTolerance() {
        val clock = DirectMovClock.video(30, 2)
        clock.requirePresentationTime(0, 0)
        clock.requirePresentationTime(1, 33_333)
        reject { clock.requirePresentationTime(0, 1) }
        reject { clock.requirePresentationTime(1, 33_334) }
        reject { clock.requirePresentationTime(2, clock.endpoint.minimumUs) }
        reject { clock.ticks(-1) }; reject { clock.ticks(3) }
        assertEquals(0L, DirectMovClock.floorUs(0, 90_000))
        assertEquals(1L, DirectMovClock.halfUpTicksToUs(1, 2_000_000))
        assertEquals(0L, DirectMovClock.halfUpTicksToUs(1, 2_000_001))
        assertEquals(1L, DirectMovClock.halfUpUsToTicks(50, 10_000))
        assertEquals(0L, DirectMovClock.halfUpUsToTicks(49, 10_000))
        reject { DirectMovClock.floorUs(-1, 90_000) }
        reject { DirectMovClock.floorUs(1, 0) }
        reject { DirectMovClock.halfUpUsToTicks(1, -1) }
        reject { DirectMovClock.floorUs(Long.MAX_VALUE, 90_000) }
        reject { DirectMovClock.ceilUs(Long.MAX_VALUE, 90_000) }
        reject { DirectMovClock.halfUpTicksToUs(Long.MAX_VALUE, 90_000) }
        reject { DirectMovClock.halfUpUsToTicks(Long.MAX_VALUE, 90_000) }
        reject { DirectMovClock.checkedMultiply(Long.MAX_VALUE, 2) }
        reject { DirectMovClock.checkedAdd(Long.MAX_VALUE, 1) }
        assertEquals(Long.MAX_VALUE, DirectMovClock.checkedMultiply(Long.MAX_VALUE, 1))
    }

    @Test fun callbackFlagsOnlyRemovePinnedFinalBookkeepingAndMustMatchRawSync() {
        val clock = DirectMovClock.video(30, 3)
        assertEquals(1, DirectMovCallbackFlags.sync(1, 0, clock, 1))
        assertEquals(0, DirectMovCallbackFlags.sync(0, 1, clock, 0))
        assertEquals(0, DirectMovCallbackFlags.sync(DirectMovCallbackFlags.LAST_SAMPLE, 2, clock, 0))
        assertEquals(1, DirectMovCallbackFlags.sync(1 or DirectMovCallbackFlags.LAST_SAMPLE, 2, clock, 1))
        reject { DirectMovCallbackFlags.sync(0, 0, clock, 0) }
        reject { DirectMovCallbackFlags.sync(1, 1, clock, 0) }
        reject { DirectMovCallbackFlags.sync(0, 2, clock, 1) }
        reject { DirectMovCallbackFlags.sync(DirectMovCallbackFlags.LAST_SAMPLE, 1, clock, 0) }
        reject { DirectMovCallbackFlags.sync(1, 3, clock, 1) }
        for (flag in listOf(2, 4, 8, 1 shl 26, 1 shl 27, 1 shl 28, 1 shl 30, Int.MIN_VALUE, -1)) {
            reject { DirectMovCallbackFlags.sync(flag, 2, clock, 0) }
        }
        reject { DirectMovCallbackFlags.sync(0, 1, DirectMovClock.audio(44_100, 2), 0) }
    }

    @Test fun eachTrackRequiresExactlyOneEmptyEosAtItsOwnExactEndpoint() {
        for (clock in listOf(DirectMovClock.video(30, 2), DirectMovClock.audio(44_100, 2))) {
            fun complete(): DirectMovWriteSequence = DirectMovWriteSequence(clock).also {
                for (i in 0 until clock.sampleCount) it.media(clock.presentationTimeUs(i), 1, 1)
            }
            reject { DirectMovWriteSequence(clock).endOfStream(clock.endpoint.minimumUs) }
            reject { DirectMovWriteSequence(clock).finish() }
            reject { complete().finish() }
            reject { complete().endOfStream(clock.endpoint.minimumUs, 1) }
            for (flags in listOf(0, 1, 2, 5, 4 or DirectMovCallbackFlags.LAST_SAMPLE)) {
                reject { complete().endOfStream(clock.endpoint.minimumUs, flags = flags) }
            }
            for (shift in listOf(-1L, 1L, clock.sampleDeltaTicks)) {
                reject { complete().endOfStream(clock.endpoint.minimumUs + shift) }
            }
            val sequence = complete()
            sequence.endOfStream(clock.endpoint.minimumUs)
            reject { sequence.endOfStream(clock.endpoint.minimumUs) }
            reject { sequence.media(clock.endpoint.minimumUs, 1, 1) }
            sequence.finish()
            reject { sequence.finish() }
            reject { DirectMovWriteSequence(clock).media(0, 0, 1) }
            reject { DirectMovWriteSequence(clock).media(0, DirectNormalizationLimits.MAX_SAMPLE_BYTES + 1, 1) }
            reject { DirectMovWriteSequence(clock).media(1, 1, 1) }
            reject { DirectMovWriteSequence(clock).media(0, 1, 4) }
        }
        val video = DirectMovWriteSequence(DirectMovClock.video(30, 2))
        video.media(0, 1, 1); video.media(33_333, 1, 0)
        reject { video.endOfStream(46_439) } // Another track's endpoint is never substituted.
        reject { DirectMovWriteSequence(DirectMovClock.video(30, 2)).media(0, 1, 0) }
    }

    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
}
