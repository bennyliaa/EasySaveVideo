package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.easysavevideo.app.media.transform.TransformerError
import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.media.transform.TransformerOutputSpec
import com.easysavevideo.app.media.transform.TransformerAudioFormat
import com.easysavevideo.app.media.transform.TransformerOpusSourceSpec

/**
 * Pure declaration-derived candidate, not a decoded-frame observation or run/storage authority.
 * The owner must still capture these exact facts and compare actual displayed/muxed frame counts.
 * Audio selection preserves the original Opus track/CSD. Native mapping acceptance is separate.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object WebmDirectCandidatePolicy {
    /** Inventory selection only; the same owner still must observe, release and seal the real run. */
    fun select(facts: WebmTransportFacts, outputMaximum: Long): TransformerOutputSpec {
        if (facts.tracks.size == 1) return selectSilent(facts, outputMaximum)
        if (facts.tracks.size != 2 || facts.tracks.map { it.trackNumber }.distinct().size != 2) reject()
        val video = facts.tracks.singleOrNull { it.trackType == C.TRACK_TYPE_VIDEO } ?: reject()
        val audio = facts.tracks.singleOrNull { it.trackType == C.TRACK_TYPE_AUDIO } ?: reject()
        if (audio.mimeType != MimeTypes.AUDIO_OPUS || audio.sampleRate != 48_000 || audio.channelCount !in 1..2) reject()
        // The bounded shared validator snapshots all three ORIGINAL CSD buffers; never rebuild them.
        val opus = TransformerOpusSourceSpec.fromTransport(facts)
        if (opus.channels != audio.channelCount) reject()
        return selectVideo(facts, video, outputMaximum, TransformerAudioFormat(48_000, audio.channelCount))
    }

    fun selectSilent(facts: WebmTransportFacts, outputMaximum: Long): TransformerOutputSpec {
        val video = facts.tracks.singleOrNull() ?: reject()
        return selectVideo(facts, video, outputMaximum, null)
    }

    private fun selectVideo(facts: WebmTransportFacts, video: WebmTrackTransport,
        outputMaximum: Long, audio: TransformerAudioFormat?): TransformerOutputSpec {
        if (video.trackType != C.TRACK_TYPE_VIDEO || video.mimeType != MimeTypes.VIDEO_VP8 ||
            video.width !in 2..1920 || video.height !in 2..1920 ||
            video.width % 2 != 0 || video.height % 2 != 0 ||
            video.firstSourceTimestampUs !in -1000L..1000L ||
            outputMaximum !in 1..MAX_OUTPUT_BYTES) reject()
        val declared = facts.declaredContainerDurationUs ?: reject()
        val duration = facts.demuxerDurationUs ?: reject()
        val defaultDurationNs = video.declaredDefaultDurationNs ?: reject()
        if (declared !in 1..MAX_DURATION_US || duration !in 1..MAX_DURATION_US ||
            distance(declared, duration) > TOLERANCE_US || defaultDurationNs !in 1..Int.MAX_VALUE.toLong()) reject()

        try {
            val pixels = Math.multiplyExact(video.width.toLong(), video.height.toLong())
            if (pixels > 1920L * 1080) reject()
            var frameRate: Int? = null
            for (rate in 1..60) {
                if (distance(Math.multiplyExact(defaultDurationNs, rate.toLong()), 1_000_000_000L) <= rate) {
                    if (frameRate != null) reject()
                    frameRate = rate
                }
            }
            val rate = frameRate ?: reject()
            val frames = Math.addExact(Math.multiplyExact(duration, rate.toLong()), 500_000L) / 1_000_000L
            if (frames !in 3..108_000 ||
                distance(Math.multiplyExact(frames, 1_000_000L) / rate, duration) > TOLERANCE_US) reject()
            val bitrate = (Math.multiplyExact(Math.multiplyExact(pixels, rate.toLong()), 16L) / 100L)
                .coerceIn(256_000L, 20_000_000L).toInt()
            val audioBitrate = audio?.let { if (it.channels == 1) 96_000 else 192_000 } ?: 0
            val bitMicroseconds = Math.multiplyExact(Math.addExact(bitrate.toLong(), audioBitrate.toLong()), duration)
            val estimatedBytes = Math.addExact(bitMicroseconds / 8_000_000L,
                if (bitMicroseconds % 8_000_000L == 0L) 0L else 1L)
            val requiredFree = minOf(outputMaximum, Math.addExact(Math.multiplyExact(estimatedBytes, 2L), 32L * 1024 * 1024))
            val frameTolerance = Math.addExact(Math.addExact(1_000_000L, rate.toLong() - 1L) / rate, TOLERANCE_US)
            return TransformerOutputSpec(
                width = video.width, height = video.height, frameRate = rate, durationUs = duration,
                durationToleranceUs = frameTolerance, audioPrimingToleranceUs = if (audio == null) 0 else 42_667,
                expectedVideoFrames = frames, audio = audio, maxBytes = outputMaximum,
                requiredFreeBytes = requiredFree, videoBitrate = bitrate, audioBitrate = audioBitrate,
            )
        } catch (_: ArithmeticException) {
            reject()
        }
    }

    // All operands have already been restricted to positive bounded values.
    private fun distance(a: Long, b: Long): Long = if (a >= b) a - b else b - a
    private fun reject(): Nothing = throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)
    private const val MAX_DURATION_US = 1_800_000_000L
    private const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024 * 1024
    private const val TOLERANCE_US = 1000L
}
