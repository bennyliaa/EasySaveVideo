package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.easysavevideo.app.media.transform.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure declaration tests. Native head and AAC presentation acceptance remain unverified. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class WebmOpusCandidatePolicyTest {
    @Test fun silentCandidateIsUnchanged() {
        val input = facts(channels = null)
        assertEquals(WebmDirectCandidatePolicy.selectSilent(input, MAX_OUTPUT), select(input))
        assertNull(select(input).audio)
    }
    @Test fun monoAndStereoPreserveInventoryAndOriginalCsdWithBoundedSettings() {
        for (channels in 1..2) {
            val input = facts(channels)
            val track = input.tracks.last()
            val csd = track.initializationData
            val output = select(input)
            assertEquals(TransformerAudioFormat(48_000, channels), output.audio)
            assertEquals(if (channels == 1) 96_000 else 192_000, output.audioBitrate)
            assertEquals(42_667L, output.audioPrimingToleranceUs)
            val bytes = (256_000L + output.audioBitrate) / 8L
            assertEquals(bytes * 2 + 32L * 1024 * 1024, output.requiredFreeBytes)
            assertSame(track, input.tracks.last()); assertEquals(2, input.tracks.size)
            csd.zip(track.initializationData).forEach { (before, after) -> assertArrayEquals(before, after) }
            rejected { WebmDirectCandidatePolicy.selectSilent(input, MAX_OUTPUT) }
        }
    }
    @Test fun originalBuffersAreNotRebuiltAndInvalidCsdIsRejected() {
        val original = csd(2)
        for (invalid in listOf(emptyList(), original.take(2), original + byteArrayOf(0),
            listOf(original[0].copyOf(18), original[1], original[2]),
            listOf(original[0], original[1].copyOf(7), original[2]),
            listOf(original[0].clone().also { it[0] = 0 }, original[1], original[2]),
            listOf(original[0].clone().also { it[9] = 1 }, original[1], original[2]),
            listOf(original[0].clone().also { it[18] = 1 }, original[1], original[2]),
            listOf(original[0], nativeLong(6_500_001L), original[2]),
            listOf(original[0], nativeLong(7_000_000L), original[2]),
            listOf(original[0], original[1], nativeLong(120_000_001L)))) {
            rejected { select(facts(configuration = invalid)) }
        }
    }
    @Test fun wrongInventoryRateChannelsOrOriginCannotDiscardAudio() {
        for (channels in listOf(0, 3)) rejected { select(facts(channels)) }
        rejected { select(facts(rate = 44_100)) }
        rejected { select(facts(mime = MimeTypes.AUDIO_AAC)) }
        rejected { select(facts(first = -1)) }
        rejected { select(facts(first = 1001)) }
        for (scale in listOf(0L, 1_000_001L, Long.MAX_VALUE)) rejected { select(facts(scale = scale)) }
        val input = facts()
        for (tracks in listOf(input.tracks + input.tracks.last(), input.tracks.takeLast(1),
            listOf(input.tracks.last(), input.tracks.last())))
            rejected { select(withTracks(input, tracks)) }
        assertEquals(2, input.tracks.size)
    }
    @Test fun shortAudioDoesNotPretendItsSourceEndIsVideoEnd() {
        val input = facts(last = 580_000L, callbacks = 30)
        val output = select(input)
        assertEquals(1_000_000L, output.durationUs)
        assertEquals(580_000L, input.tracks.last().lastSourceTimestampUs)
        assertNotNull(output.audio)
    }
    @Test fun candidateCannotIssueDirectRunOrClaimVerifiedNativeMapping() {
        val input = facts()
        assertEquals(TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN,
            TransformerOpusSourceSpec.fromTransport(input).headMapping)
        val failure = assertThrows(TransformerFailure::class.java) {
            requireIssuedTransformerRun(TransformerRunSpec.DirectVp8Opus(TransformerDirectLoaderBinding.Unissued, select(input)))
        }
        assertEquals(TransformerError.NOT_ISSUED, failure.code)
    }
    private fun select(facts: WebmTransportFacts) = WebmDirectCandidatePolicy.select(facts, MAX_OUTPUT)
    private fun rejected(block: () -> Unit) {
        assertEquals(TransformerError.UNSUPPORTED_PROFILE, assertThrows(TransformerFailure::class.java) { block() }.code)
    }
    private fun facts(channels: Int? = 2, rate: Int = 48_000, mime: String = MimeTypes.AUDIO_OPUS,
        configuration: List<ByteArray> = csd(channels ?: 2), first: Long = 0, last: Long = 980_000,
        callbacks: Long = 50, scale: Long = 1_000_000): WebmTransportFacts {
        val tracks = arrayListOf(WebmTrackTransport(1, C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_VP8,
            16, 16, -1, -1, emptyList(), 30, 0, 966_667, 0, 33_333_333))
        if (channels != null) tracks += WebmTrackTransport(2, C.TRACK_TYPE_AUDIO, mime,
            -1, -1, rate, channels, configuration, callbacks, first, last, 0, null)
        return WebmTransportFacts(1024, 1_000_000, tracks, 4, 0, 0, 10, 30, 1_000_000, scale)
    }
    private fun withTracks(input: WebmTransportFacts, tracks: List<WebmTrackTransport>) =
        WebmTransportFacts(input.inputExtent, input.declaredContainerDurationUs, tracks, input.maximumPhysicalMasterDepth,
            input.outstandingMastersAtEof, input.actualUnknownEndCallbacks, input.checkedHeaders,
            input.delegatedPayloads, input.demuxerDurationUs, input.timecodeScaleNs)
    private fun csd(channels: Int): List<ByteArray> = listOf(ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1.toByte()).put(channels.toByte()).putShort(312.toShort())
        .putInt(48_000).putShort(0.toShort()).put(0.toByte()).array(), nativeLong(6_500_000), nativeLong(80_000_000))
    private fun nativeLong(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
    companion object { private const val MAX_OUTPUT = 2L * 1024 * 1024 * 1024 }
}
