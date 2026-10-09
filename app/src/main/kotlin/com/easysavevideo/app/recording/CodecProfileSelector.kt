package com.easysavevideo.app.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Fixed, bounded AVC baseline/SDR and optional AAC-LC candidates. No third-party codec path. */
internal object CodecProfileSelector {
    data class Video(
        val encoderName: String, val width: Int, val height: Int,
        val frameRate: Int, val bitrate: Int, val bitrateMode: Int,
    ) {
        fun format(): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            // Static screens still produce bounded-duration samples; this is an encoder request,
            // not evidence of a frame. Only actual output samples count toward the result.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
    }

    data class Audio(val encoderName: String, val sampleRate: Int, val channels: Int, val bitrate: Int) {
        val packetDurationUs: Long get() = 1_024_000_000L / sampleRate
        fun format(): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * channels * 2)
        }
    }

    fun video(sourceWidth: Int, sourceHeight: Int, maxDimension: Int, frameRate: Int): Video? {
        require(sourceWidth in 1..8192 && sourceHeight in 1..8192)
        require(maxDimension in 256..3840 && frameRate in 15..60)
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type == MediaFormat.MIMETYPE_VIDEO_AVC } }
            .sortedBy { !it.isHardwareAccelerated }.take(32)
        for (encoder in encoders) {
            val caps = try { encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: RuntimeException) { continue }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats ||
                caps.profileLevels.none { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) continue
            val video = caps.videoCapabilities ?: continue
            val encoderCaps = caps.encoderCapabilities ?: continue
            val mode = listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                .firstOrNull(encoderCaps::isBitrateModeSupported) ?: continue
            for (edge in listOf(maxDimension, 1280, 960).filter { it <= maxDimension }.distinct()) {
                val size = RecordingProfileGeometry.fit(sourceWidth, sourceHeight, edge,
                    maxOf(2, video.widthAlignment), maxOf(2, video.heightAlignment)) ?: continue
                if (!video.areSizeAndRateSupported(size.first, size.second, frameRate.toDouble())) continue
                val bitrate = (size.first.toLong() * size.second * frameRate / 6).coerceIn(1_000_000, 12_000_000).toInt()
                if (!video.bitrateRange.contains(bitrate)) continue
                val candidate = Video(encoder.name, size.first, size.second, frameRate, bitrate, mode)
                if (try { caps.isFormatSupported(candidate.format()) } catch (_: RuntimeException) { false }) return candidate
            }
        }
        return null
    }

    fun audio(): Audio? {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type == MediaFormat.MIMETYPE_AUDIO_AAC } }
            .sortedBy { !it.isHardwareAccelerated }.take(32)
        for (encoder in encoders) {
            val caps = try { encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC) } catch (_: RuntimeException) { continue }
            val audio = caps.audioCapabilities ?: continue
            if (caps.profileLevels.none { it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC }) continue
            for (channels in listOf(2, 1)) for (rate in listOf(48_000, 44_100)) {
                val bitrate = if (channels == 2) 128_000 else 64_000
                if (channels > audio.maxInputChannelCount || !audio.isSampleRateSupported(rate) || !audio.bitrateRange.contains(bitrate)) continue
                val candidate = Audio(encoder.name, rate, channels, bitrate)
                if (try { caps.isFormatSupported(candidate.format()) } catch (_: RuntimeException) { false }) return candidate
            }
        }
        return null
    }

    /** Region pixels are never aligned by changing dimensions, downscaled, or stretched. */
    fun exactVideo(width: Int, height: Int, frameRate: Int): Video? {
        if (!RecordingProfileGeometry.exact(width, height, 2, 2) || frameRate !in 15..60) return null
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .sortedBy { !it.isHardwareAccelerated }.take(32)
        for (encoder in encoders) {
            val caps = try { encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: RuntimeException) { continue }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats ||
                caps.profileLevels.none { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) continue
            val video = caps.videoCapabilities ?: continue
            if (!RecordingProfileGeometry.exact(width, height, maxOf(2, video.widthAlignment), maxOf(2, video.heightAlignment)) ||
                !video.areSizeAndRateSupported(width, height, frameRate.toDouble())) continue
            val encoderCaps = caps.encoderCapabilities ?: continue
            val mode = listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                .firstOrNull(encoderCaps::isBitrateModeSupported) ?: continue
            val bitrate = (width.toLong() * height * frameRate / 6).coerceIn(1_000_000, 12_000_000).toInt()
            if (!video.bitrateRange.contains(bitrate)) continue
            val candidate = Video(encoder.name, width, height, frameRate, bitrate, mode)
            if (try { caps.isFormatSupported(candidate.format()) } catch (_: RuntimeException) { false }) return candidate
        }
        return null
    }
}

/** Exact source ratio is retained even on API 31; alignment never silently stretches a picture. */
internal object RecordingProfileGeometry {
    fun exact(width: Int, height: Int, widthAlignment: Int, heightAlignment: Int): Boolean =
        width in 2..4096 && height in 2..4096 && widthAlignment in 1..1024 && heightAlignment in 1..1024 &&
            width.toLong() * height <= 8_847_360L && width % widthAlignment == 0 && height % heightAlignment == 0

    fun fit(width: Int, height: Int, maxEdge: Int, widthAlignment: Int, heightAlignment: Int): Pair<Int, Int>? {
        require(width in 1..8192 && height in 1..8192 && maxEdge in 1..8192)
        require(widthAlignment in 1..1024 && heightAlignment in 1..1024)
        val ratio = gcd(width, height)
        val w = width / ratio
        val h = height / ratio
        val stepW = widthAlignment / gcd(widthAlignment, w)
        val stepH = heightAlignment / gcd(heightAlignment, h)
        val step = stepW.toLong() / gcd(stepW, stepH) * stepH
        val scale = minOf(ratio, maxEdge / maxOf(w, h)).toLong() / step * step
        if (scale <= 0 || w * scale < 128 || h * scale < 128) return null
        return (w * scale).toInt() to (h * scale).toInt()
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
