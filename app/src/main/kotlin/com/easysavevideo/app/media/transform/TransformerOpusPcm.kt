package com.easysavevideo.app.media.transform

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.OpusUtil
import com.easysavevideo.app.save.conversion.WebmTransportFacts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed, still UNVERIFIED native head/timestamp mapping. This is not acceptance authority. */
internal enum class TransformerOpusHeadMapping { UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN }

/** Immutable admission snapshot, only derived from the bounded original transport. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerOpusSourceSpec private constructor(facts: WebmTransportFacts) {
    private val track = facts.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }
    private val csd = track.initializationData.map { it.clone() }
    private val extent = facts.inputExtent
    private val declaredDuration = facts.declaredContainerDurationUs
    private val demuxerDuration = facts.demuxerDurationUs
    val channels = track.channelCount
    val compressedOriginUs = track.firstSourceTimestampUs
    val timecodeScaleNs = facts.timecodeScaleNs
    val timestampToleranceUs = (timecodeScaleNs + 999) / 1000 + 1
    val headMapping = TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN
    // A caller cannot substitute observed first PCM/encoder PTS for either fixed source origin.
    val sourceOriginUs = 0L
    val nativePcmOriginUs = 0L
    val headFrames: Long
    val codecDelayUs: Long
    val seekPrerollUs: Long
    init {
        opusRequire(facts.tracks.size == 2 && facts.tracks.count { it.trackType == C.TRACK_TYPE_VIDEO && it.mimeType == MimeTypes.VIDEO_VP8 } == 1)
        opusRequire(extent > 0 && track.mimeType == MimeTypes.AUDIO_OPUS && track.sampleRate == OPUS_RATE && channels in 1..2 &&
            track.sourceSampleCallbacks in 1..MAX_OPUS_PACKETS && compressedOriginUs == 0L &&
            track.lastSourceTimestampUs in compressedOriginUs..1_800_001_000L && timecodeScaleNs in 1..1_000_000L)
        opusRequire(csd.size == 3 && csd[0].size == 19 && csd[1].size == 8 && csd[2].size == 8)
        val header = csd[0]
        opusRequire(header.copyOfRange(0, 8).contentEquals(byteArrayOf(79, 112, 117, 115, 72, 101, 97, 100)) &&
            header[8].toInt() == 1 && OpusUtil.getChannelCount(header) == channels && header[18].toInt() == 0)
        val delayNs = ByteBuffer.wrap(csd[1]).order(ByteOrder.nativeOrder()).long
        val prerollNs = ByteBuffer.wrap(csd[2]).order(ByteOrder.nativeOrder()).long
        headFrames = opusExactFrames(delayNs)
        opusRequire(OpusUtil.getPreSkipSamples(header).toLong() == headFrames && prerollNs in 0..MAX_OPUS_BOUNDARY_NS)
        codecDelayUs = delayNs / 1000
        seekPrerollUs = prerollNs / 1000
    }
    fun matches(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_OPUS &&
        format.sampleRate == OPUS_RATE && format.channelCount == channels && format.drmInitData == null &&
        format.cryptoType == C.CRYPTO_TYPE_NONE && sameCsd(format.initializationData)
    fun matchesCompleted(facts: WebmTransportFacts): Boolean {
        if (facts.inputExtent != extent || facts.declaredContainerDurationUs != declaredDuration ||
            facts.demuxerDurationUs != demuxerDuration || facts.timecodeScaleNs != timecodeScaleNs || facts.tracks.size != 2 ||
            facts.tracks.count { it.trackType == C.TRACK_TYPE_VIDEO && it.mimeType == MimeTypes.VIDEO_VP8 } != 1) return false
        val loaded = facts.tracks.singleOrNull { it.trackType == C.TRACK_TYPE_AUDIO } ?: return false
        return loaded.trackNumber == track.trackNumber && loaded.mimeType == track.mimeType && loaded.sampleRate == OPUS_RATE &&
            loaded.channelCount == channels && loaded.sourceSampleCallbacks == track.sourceSampleCallbacks &&
            loaded.firstSourceTimestampUs == compressedOriginUs && loaded.lastSourceTimestampUs == track.lastSourceTimestampUs &&
            loaded.supplementalBytes == track.supplementalBytes && sameCsd(loaded.initializationData)
    }
    private fun sameCsd(candidate: List<ByteArray>) = candidate.size == csd.size && candidate.indices.all { csd[it].contentEquals(candidate[it]) }
    companion object {
        fun fromTransport(facts: WebmTransportFacts): TransformerOpusSourceSpec {
            opusRequire(facts.tracks.count { it.trackType == C.TRACK_TYPE_AUDIO } == 1)
            return TransformerOpusSourceSpec(facts)
        }
    }
}

internal const val OPUS_RATE = 48_000
internal const val MAX_OPUS_TAIL_FRAMES = 5_760
internal const val MAX_OPUS_PCM_FRAMES = 86_400_000L
internal const val MAX_OPUS_PACKETS = 720_096L // Effective 30 minutes plus two 120 ms boundaries at 2.5 ms/packet.
internal const val MAX_OPUS_BOUNDARY_NS = 120_000_000L
internal fun opusRequire(condition: Boolean) { if (!condition) throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE) }
internal fun opusExactFrames(ns: Long): Long {
    opusRequire(ns in 0..MAX_OPUS_BOUNDARY_NS)
    val product = ns * OPUS_RATE
    opusRequire(product % 1_000_000_000L == 0L)
    return product / 1_000_000_000L
}
internal fun opusTimeUs(frames: Long): Long = frames * 1_000_000L / OPUS_RATE

/** Exactly 120 ms of PCM16; no native output lifetime is retained. */
internal class TransformerPcmTail(private val frameBytes: Int) {
    private val bytes = ByteArray(MAX_OPUS_TAIL_FRAMES * frameBytes)
    private var head = 0
    var frames = 0; private set
    fun byteAt(index: Int): Byte {
        opusRequire(index in 0 until frames * frameBytes)
        return bytes[(head + index) % bytes.size]
    }
    fun append(input: ByteBuffer, skipFrames: Int) {
        val start = input.position() + skipFrames * frameBytes
        val count = input.limit() - start
        opusRequire(count >= 0 && count % frameBytes == 0 && frames + count / frameBytes <= MAX_OPUS_TAIL_FRAMES)
        for (i in 0 until count) bytes[(head + frames * frameBytes + i) % bytes.size] = input.get(start + i)
        frames += count / frameBytes
    }
    fun removeHead(count: Int) {
        opusRequire(count in 0..frames)
        repeat(count * frameBytes) { bytes[(head + it) % bytes.size] = 0 }
        head = (head + count * frameBytes) % bytes.size; frames -= count
    }
    fun removeTail(count: Int) {
        opusRequire(count in 0..frames)
        repeat(count * frameBytes) { bytes[(head + (frames - count) * frameBytes + it) % bytes.size] = 0 }
        frames -= count
    }
    fun clear() { bytes.fill(0); head = 0; frames = 0 }
}
