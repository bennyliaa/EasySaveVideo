package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import com.easysavevideo.app.media.transform.TransformerDirectLoaderBinding
import com.easysavevideo.app.media.transform.TransformerError
import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.media.transform.TransformerRunSpec
import com.easysavevideo.app.media.transform.requireIssuedTransformerRun
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class WebmDirectCandidatePolicyTest {
    @Test fun everyIntegerRateAcceptsItsFloorAndCeilingNanosecondDeclarations() {
        for (rate in 1..60) {
            val floor = 1_000_000_000L / rate
            val ceil = (1_000_000_000L + rate - 1) / rate
            for (declaration in setOf(floor, ceil)) {
                val result = select(facts(duration = 3_000_000, defaultDuration = declaration))
                assertEquals(rate, result.frameRate)
                assertEquals(3L * rate, result.expectedVideoFrames)
                assertEquals(3_000_000L, result.durationUs)
                assertEquals((1_000_000L + rate - 1) / rate + 1000L, result.durationToleranceUs)
            }
        }
    }

    @Test fun exactOneNanosecondAllowanceIsInclusive() {
        for (value in listOf(999_999_999L, 1_000_000_000L, 1_000_000_001L))
            assertEquals(1, select(facts(duration = 3_000_000, defaultDuration = value)).frameRate)
        for (value in listOf(999_999_998L, 1_000_000_002L))
            rejected { select(facts(duration = 3_000_000, defaultDuration = value)) }
    }

    @Test fun declarationsJustOutsideIntegerRoundingAreRejected() {
        for (rate in listOf(24, 25, 30, 60)) {
            val floor = 1_000_000_000L / rate
            val ceil = (1_000_000_000L + rate - 1) / rate
            for (value in listOf(floor - 2, ceil + 2))
                rejected { select(facts(defaultDuration = value)) }
        }
    }

    @Test fun noninteger2997AndRatesOutsideOneToSixtyAreRejected() {
        for (value in listOf(33_366_667L, 2_000_000_000L, 16_393_443L))
            rejected { select(facts(defaultDuration = value)) }
    }

    @Test fun absentZeroNegativeAndOverflowSizedDefaultDurationNeverFabricateRate() {
        for (value in listOf(null, 0L, -1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE, Long.MIN_VALUE))
            rejected { select(facts(defaultDuration = value)) }
    }

    @Test fun declaredAndDemuxerDurationsAreRequiredPositiveAndBounded() {
        for (value in listOf(null, 0L, -1L, 1_800_000_001L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            rejected { select(facts(declared = value)) }
            rejected { select(facts(duration = value)) }
        }
    }

    @Test fun declaredDemuxerDifferenceAccepts1000ButRejects1001BothDirections() {
        for (delta in listOf(-1000L, 1000L))
            assertEquals(100_000L, select(facts(declared = 100_000L + delta)).durationUs)
        for (delta in listOf(-1001L, 1001L))
            rejected { select(facts(declared = 100_000L + delta)) }
    }

    @Test fun candidateDurationResidualAccepts1000ButRejects1001BothDirections() {
        for (delta in listOf(-1000L, 1000L)) {
            val duration = 100_000L + delta
            val result = select(facts(duration = duration, declared = duration))
            assertEquals(3L, result.expectedVideoFrames)
            assertEquals(duration, result.durationUs)
        }
        for (delta in listOf(-1001L, 1001L)) {
            val duration = 100_000L + delta
            rejected { select(facts(duration = duration, declared = duration)) }
        }
    }

    @Test fun fractionalFrameDurationUsesFloorForResidual() {
        assertEquals(4L, select(facts(duration = 133_333L)).expectedVideoFrames)
        assertEquals(4L, select(facts(duration = 134_333L)).expectedVideoFrames)
        rejected { select(facts(duration = 134_334L)) }
    }

    @Test fun minimumThreeAndMaximum108000FramesAreEnforced() {
        rejected { select(facts(duration = 66_667L)) }
        assertEquals(3L, select(facts()).expectedVideoFrames)
        assertEquals(108_000L, select(facts(duration = 1_800_000_000L, defaultDuration = 16_666_667L)).expectedVideoFrames)
        rejected { select(facts(duration = 1_800_016_667L, defaultDuration = 16_666_667L)) }
    }

    @Test fun compressedTransportSampleCountsDoNotSelectDisplayedCount() {
        val expected = select(facts(samples = 3))
        for (callbacks in listOf(1L, 2L, 4L, 108_001L, Long.MAX_VALUE))
            assertEquals(expected, select(facts(samples = callbacks)))
    }

    @Test fun compressedLastTimestampDoesNotEstimateDuration() {
        val expected = select(facts(last = 67_000))
        assertEquals(expected, select(facts(last = 1_700_000_000)))
        rejected { select(facts(duration = null, last = 100_000)) }
    }

    @Test fun sourceZeroToleranceIsInclusiveAndNeverRebased() {
        for (start in listOf(-1000L, 0L, 1000L)) assertEquals(3L, select(facts(first = start)).expectedVideoFrames)
        for (start in listOf(-1001L, 1001L, Long.MIN_VALUE, Long.MAX_VALUE)) rejected { select(facts(first = start)) }
    }

    @Test fun audioInventoryCannotBecomeSilentCandidate() {
        for (channels in 1..2) {
            val input = facts(audioChannels = channels)
            val audio = input.tracks[1]
            rejected { select(input) }
            assertEquals(2, input.tracks.size)
            assertSame(audio, input.tracks[1])
            assertEquals(channels, audio.channelCount)
            assertEquals(3, audio.initializationData.size)
        }
    }

    @Test fun zeroOrMultipleOrAudioOnlyTracksAreRejected() {
        val input = facts()
        for (tracks in listOf(emptyList(), input.tracks + input.tracks, facts(audioChannels = 1).tracks.drop(1)))
            rejected { select(withTracks(input, tracks)) }
    }

    @Test fun acceptedGeometryKeepsSizeAndDeterministicNormalBitrate() {
        val small = select(facts())
        assertEquals(16, small.width); assertEquals(16, small.height); assertEquals(256_000, small.videoBitrate)
        val hd = select(facts(width = 1920, height = 1080))
        assertEquals(9_953_280, hd.videoBitrate)
        val fast = select(facts(width = 1920, height = 1080, defaultDuration = 16_666_667L))
        assertEquals(19_906_560, fast.videoBitrate)
        assertEquals(1080, select(facts(width = 1080, height = 1920)).width)
    }

    @Test fun badDimensionsCannotOverflowOrChangeProfile() {
        for ((width, height) in listOf(1 to 16, 17 to 16, 16 to 17, 1920 to 1920,
            1922 to 1080, Int.MAX_VALUE to Int.MAX_VALUE, Int.MIN_VALUE to 16))
            rejected { select(facts(width = width, height = height)) }
    }

    @Test fun wrongMimeOrTypeIsRejected() {
        rejected { select(facts(mime = "video/x-vnd.on2.vp9")) }
        rejected { select(facts(type = C.TRACK_TYPE_AUDIO)) }
    }

    @Test fun outputMaximumIsCapturedAndSpaceEstimateIsBounded() {
        val result = select(facts())
        val estimate = 256_000L * 100_000L / 8_000_000L
        assertEquals(estimate * 2 + 32L * 1024 * 1024, result.requiredFreeBytes)
        assertEquals(MAX_OUTPUT, result.maxBytes)
        val fractional = select(facts(duration = 100_001L))
        assertEquals(3201L * 2 + 32L * 1024 * 1024, fractional.requiredFreeBytes)
        for (maximum in listOf(result.requiredFreeBytes - 1, result.requiredFreeBytes, result.requiredFreeBytes + 1))
            assertEquals(minOf(maximum, result.requiredFreeBytes),
                WebmDirectCandidatePolicy.selectSilent(facts(), maximum).requiredFreeBytes)
        assertEquals(1L, WebmDirectCandidatePolicy.selectSilent(facts(), 1).requiredFreeBytes)
        val largest = select(facts(duration = 1_800_000_000L, width = 1920, height = 1080,
            defaultDuration = 16_666_667L))
        assertEquals(MAX_OUTPUT, largest.requiredFreeBytes)
        for (maximum in listOf(0L, -1L, MAX_OUTPUT + 1, Long.MAX_VALUE, Long.MIN_VALUE))
            rejected { WebmDirectCandidatePolicy.selectSilent(facts(), maximum) }
    }

    @Test fun silentCandidateHasNoAudioOrIssuanceAuthority() {
        val output = select(facts())
        assertNull(output.audio); assertEquals(0, output.audioBitrate)
        assertEquals(0L, output.audioPrimingToleranceUs); assertEquals(34_334L, output.durationToleranceUs)
        val failure = assertThrows(TransformerFailure::class.java) {
            requireIssuedTransformerRun(TransformerRunSpec.DirectVp8Opus(TransformerDirectLoaderBinding.Unissued, output))
        }
        assertEquals(TransformerError.NOT_ISSUED, failure.code)
    }

    private fun select(facts: WebmTransportFacts) = WebmDirectCandidatePolicy.selectSilent(facts, MAX_OUTPUT)
    private fun rejected(action: () -> Unit) {
        val failure = assertThrows(TransformerFailure::class.java) { action() }
        assertEquals(TransformerError.UNSUPPORTED_PROFILE, failure.code)
    }
    private fun facts(duration: Long? = 100_000L, declared: Long? = duration, defaultDuration: Long? = 33_333_333L,
        width: Int = 16, height: Int = 16, samples: Long = 3, first: Long = 0, last: Long = 67_000,
        audioChannels: Int? = null, type: Int = C.TRACK_TYPE_VIDEO, mime: String = "video/x-vnd.on2.vp8"): WebmTransportFacts {
        val tracks = arrayListOf(WebmTrackTransport(1, type, mime, width, height, -1, -1, emptyList(),
            samples, first, last, 0, defaultDuration))
        if (audioChannels != null) tracks += WebmTrackTransport(2, C.TRACK_TYPE_AUDIO, "audio/opus", -1, -1,
            48_000, audioChannels, listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)), 5, -6500, 90_000, 0, null)
        return WebmTransportFacts(1024, declared, tracks, 4, 0, 0, 10, 3, duration)
    }
    private fun withTracks(input: WebmTransportFacts, tracks: List<WebmTrackTransport>) =
        WebmTransportFacts(input.inputExtent, input.declaredContainerDurationUs, tracks, input.maximumPhysicalMasterDepth,
            input.outstandingMastersAtEof, input.actualUnknownEndCallbacks, input.checkedHeaders, input.delegatedPayloads,
            input.demuxerDurationUs)

    companion object { private const val MAX_OUTPUT = 2L * 1024 * 1024 * 1024 }
}
