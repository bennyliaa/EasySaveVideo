package com.easysavevideo.app.media.verification

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** Synchronous borrowed-read implementation. No executor, process gate, storage owner or FD close. */
internal object AndroidAvcAacOutputInspection {
    fun inspect(read: BorrowedAvcAacRead, resources: AvcAacNativeResources,
        expected: AvcAacOutputExpectation, checkpoint: () -> Unit): AvcAacOutputObservations {
        val owner = NativeOwner(read, resources)
        val size = read.extentBytes
        val timing = AvcAacMp4Probe.read(read.channel, size, expected, checkpoint)
        val formats = inspectFormats(owner, expected, size, checkpoint)
        val videoInfo = formats.single { it.video }
        val videoTiming = timing.single { it.kind == "vide" }
        val videoScan = scan(owner, videoInfo, videoTiming, expected, size, checkpoint)
        val audioInfo = formats.singleOrNull { !it.video }
        val audioScan = audioInfo?.let { scan(owner, it, timing.single { track -> track.kind == "soun" }, expected, size, checkpoint) }
        val videoDecoded = decode(owner, videoInfo, videoScan, expected, size, checkpoint)
        val audioDecoded = audioInfo?.let { decode(owner, it, audioScan!!, expected, size, checkpoint) }
        checkpoint()
        // Direct video duration remains its own track's observation; raw AAC priming is not video.
        val durationUs = if (expected.independentAudio != null) videoScan.endUs else maxOf(videoScan.endUs, audioScan?.endUs ?: 0)
        return AvcAacOutputObservations(durationUs,
            videoInfo.format.getInteger(MediaFormat.KEY_WIDTH), videoInfo.format.getInteger(MediaFormat.KEY_HEIGHT),
            value(videoInfo.format, MediaFormat.KEY_ROTATION, 0), videoScan.firstPtsUs, videoScan.lastPtsUs,
            videoScan.samples.size.toLong(), audioScan?.samples?.size?.toLong() ?: 0, videoDecoded.frames, audioDecoded?.frames ?: 0,
            maximumFixedVideoDeviationUs(videoScan.samples, expected.frameRate, checkpoint), audioDecoded?.presentedAudio)
    }

    private data class Track(val index: Int, val format: MediaFormat, val video: Boolean)
    private data class Decoded(val frames: Long, val presentedAudio: AvcAacPresentedAudio? = null)

    private fun inspectFormats(owner: NativeOwner, expected: AvcAacOutputExpectation, size: Long,
        checkpoint: () -> Unit): List<Track> {
        val extractor = owner.extractor(size)
        return withAvcAacCleanup(listOf({ owner.release(extractor) })) {
            checkpoint()
            if (!extractor.psshInfo.isNullOrEmpty() || extractor.trackCount != (if (expected.audio is AvcAacAudioProfile.Silent) 1 else 2)) invalidAvcAacOutput()
            val tracks = (0 until extractor.trackCount).map { index ->
                checkpoint()
                val format = extractor.getTrackFormat(index)
                val video = when (format.getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> true
                    "audio/mp4a-latm" -> false
                    else -> unsupportedAvcAacOutput()
                }
                checkEncodedFormat(format, video, expected)
                Track(index, format, video)
            }
            if (tracks.count { it.video } != 1 || tracks.count { !it.video } != (if (expected.audio is AvcAacAudioProfile.Silent) 0 else 1)) invalidAvcAacOutput()
            tracks
        }
    }

    private fun checkEncodedFormat(format: MediaFormat, video: Boolean, expected: AvcAacOutputExpectation) {
        if (video) {
            if (value(format, MediaFormat.KEY_WIDTH) != expected.width || value(format, MediaFormat.KEY_HEIGHT) != expected.height ||
                value(format, MediaFormat.KEY_ROTATION, 0) != 0 ||
                (format.containsKey("sar-width") && value(format, "sar-width") != value(format, "sar-height"))) invalidAvcAacOutput()
            if (format.containsKey(MediaFormat.KEY_PROFILE) && value(format, MediaFormat.KEY_PROFILE) !in setOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)) unsupportedAvcAacOutput()
            checkSdr(format)
        } else {
            val audio = expected.audio as? AvcAacAudioProfile.Aac ?: invalidAvcAacOutput()
            if (value(format, MediaFormat.KEY_SAMPLE_RATE) != audio.sampleRate || value(format, MediaFormat.KEY_CHANNEL_COUNT) != audio.channels ||
                (format.containsKey(MediaFormat.KEY_AAC_PROFILE) && value(format, MediaFormat.KEY_AAC_PROFILE) != MediaCodecInfo.CodecProfileLevel.AACObjectLC)) invalidAvcAacOutput()
            val config = format.getByteBuffer("csd-0")?.duplicate() ?: unsupportedAvcAacOutput()
            if (config.remaining() !in 2..AvcAacVerificationBounds.MAX_CONFIG_BYTES) unsupportedAvcAacOutput()
            val data = ByteArray(config.remaining()).also(config::get)
            AvcAacAacConfigPolicy.requireLc(data, audio.sampleRate, audio.channels)
            for (key in listOf(MediaFormat.KEY_ENCODER_DELAY, MediaFormat.KEY_ENCODER_PADDING)) {
                val frames = value(format, key, 0)
                if (frames < 0 || frames * 1_000_000L / audio.sampleRate > expected.audioPrimingToleranceUs) unsupportedAvcAacOutput()
            }
        }
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
        val tolerance = expected.durationToleranceUs + if (video) 0 else expected.audioPrimingToleranceUs
        if (!video && expected.independentAudio != null) {
            // Independent same-track scan/window below is authoritative. Do not compare to video or E.
            if (duration !in 1..1_800_000_000L + 2 * AvcAacIndependentAudioExpectation.BOUNDARY_US) invalidAvcAacOutput()
        } else if (duration <= 0 || kotlin.math.abs(duration - expected.durationUs) > tolerance + 1_000) invalidAvcAacOutput()
    }

    private fun scan(owner: NativeOwner, track: Track, timing: AvcAacMp4Track, expected: AvcAacOutputExpectation,
        size: Long, checkpoint: () -> Unit): AvcAacScannedTrack {
        val extractor = owner.extractor(size)
        return withAvcAacCleanup(listOf({ owner.release(extractor) })) {
            extractor.selectTrack(track.index)
            val ledger = AvcAacSampleLedger(track.video, expected, timing)
            val buffer = ByteBuffer.allocateDirect(AvcAacVerificationBounds.MAX_SAMPLE_BYTES)
            while (extractor.sampleTrackIndex != -1) {
                checkpoint()
                if (extractor.sampleTrackIndex != track.index) invalidAvcAacOutput()
                val bytes = extractor.sampleSize
                if (bytes !in 1..AvcAacVerificationBounds.MAX_SAMPLE_BYTES.toLong()) unsupportedAvcAacOutput()
                buffer.clear()
                if (extractor.readSampleData(buffer, 0).toLong() != bytes) invalidAvcAacOutput()
                ledger.sample(extractor.sampleTime, bytes, extractor.sampleFlags)
                extractor.advance()
            }
            requireEof(extractor, buffer, checkpoint)
            ledger.finish()
        }
    }

    private fun decode(owner: NativeOwner, track: Track, scan: AvcAacScannedTrack, expected: AvcAacOutputExpectation,
        size: Long, checkpoint: () -> Unit): Decoded {
        val extractor = owner.extractor(size)
        var codec: MediaCodec? = null
        return withAvcAacCleanup(listOf({ codec?.let(owner::release) }, { owner.release(extractor) })) {
            extractor.selectTrack(track.index)
            val activeCodec = owner.codec(if (track.video) "video/avc" else "audio/mp4a-latm").also { codec = it }
            activeCodec.configure(track.format, null, null, 0)
            activeCodec.start(); owner.started(activeCodec)
            val video = if (track.video) AvcAacVideoDecodeLedger(scan) else null
            // No Android codec/build has yet passed the known-PCM AAC priming/padding acceptance.
            // Execute the real decode and cleanup; absent/default-zero format keys cannot open this.
            val audio = if (track.video) null else AvcAacAudioDecodeLedger(expected, scan,
                AvcAacNativeAudioMapping.UNVERIFIED)
            var queued = 0
            var inputEnded = false
            var formatSignature: List<Int>? = null
            val info = MediaCodec.BufferInfo()
            val progress = AvcAacDecodeProgress(System.nanoTime())
            fun outputFormat(format: MediaFormat) {
                val signature = decodedFormatSignature(format, track.video, expected)
                if (formatSignature != null && formatSignature != signature) unsupportedAvcAacOutput()
                formatSignature = signature
            }
            while (video?.outputEos != true && audio?.outputEos != true) {
                checkpoint(); progress.check(System.nanoTime())
                if (!inputEnded) {
                    val index = activeCodec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        checkpoint()
                        val input = activeCodec.getInputBuffer(index) ?: invalidAvcAacOutput()
                        if (extractor.sampleTrackIndex == -1) {
                            requireEof(extractor, input, checkpoint)
                            if (queued != scan.samples.size) invalidAvcAacOutput()
                            activeCodec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            video?.inputEnd(); audio?.inputEnd()
                            inputEnded = true
                        } else {
                            if (extractor.sampleTrackIndex != track.index || queued >= scan.samples.size) invalidAvcAacOutput()
                            val sizeBytes = extractor.sampleSize
                            if (sizeBytes !in 1..AvcAacVerificationBounds.MAX_SAMPLE_BYTES.toLong() || sizeBytes > input.capacity()) unsupportedAvcAacOutput()
                            val sample = AvcAacEncodedSample(extractor.sampleTime, sizeBytes.toInt(), extractor.sampleFlags)
                            if (sample != scan.samples[queued]) invalidAvcAacOutput()
                            input.clear()
                            if (extractor.readSampleData(input, 0).toLong() != sizeBytes) invalidAvcAacOutput()
                            activeCodec.queueInputBuffer(index, 0, sizeBytes.toInt(), sample.ptsUs, 0)
                            // Credit only successful actual native queue calls, never attempted input.
                            video?.input(sample); audio?.input(sample)
                            queued++; extractor.advance()
                        }
                        progress.progressed(System.nanoTime())
                    }
                }
                val index = activeCodec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat(activeCodec.outputFormat)
                    index == MediaCodec.INFO_TRY_AGAIN_LATER || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> {
                        withAvcAacCleanup(listOf({ activeCodec.releaseOutputBuffer(index, false) })) {
                            checkpoint()
                            outputFormat(activeCodec.getOutputFormat(index))
                            if (info.offset < 0 || info.size < 0 || info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0) unsupportedAvcAacOutput()
                            if (info.size > 0) {
                                val buffer = activeCodec.getOutputBuffer(index) ?: invalidAvcAacOutput()
                                if (info.offset.toLong() + info.size > buffer.capacity()) invalidAvcAacOutput()
                            }
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val madeProgress = video?.output(info.presentationTimeUs, info.size, config, eos)
                                ?: audio!!.output(info.presentationTimeUs, info.size, config, eos)
                            if (madeProgress) progress.progressed(System.nanoTime())
                        }
                    }
                    else -> invalidAvcAacOutput()
                }
            }
            checkpoint()
            if (formatSignature == null) invalidAvcAacOutput()
            if (video != null) Decoded(video.decodedFrames)
            else Decoded(audio!!.decodedFrames, audio.presentedAudio)
        }
    }

    private fun decodedFormatSignature(format: MediaFormat, video: Boolean, expected: AvcAacOutputExpectation): List<Int> {
        if (video) {
            if (format.getString(MediaFormat.KEY_MIME) != "video/raw") unsupportedAvcAacOutput()
            val width = value(format, MediaFormat.KEY_WIDTH)
            val height = value(format, MediaFormat.KEY_HEIGHT)
            val left = value(format, "crop-left", 0); val right = value(format, "crop-right", width - 1)
            val top = value(format, "crop-top", 0); val bottom = value(format, "crop-bottom", height - 1)
            if (width !in 1..2048 || height !in 1..2048 || left !in 0 until width || right !in left until width ||
                top !in 0 until height || bottom !in top until height || right - left + 1 != expected.width ||
                bottom - top + 1 != expected.height || value(format, MediaFormat.KEY_ROTATION, 0) != 0) invalidAvcAacOutput()
            checkSdr(format)
            return listOf(width, height, left, right, top, bottom, value(format, MediaFormat.KEY_COLOR_FORMAT),
                value(format, MediaFormat.KEY_COLOR_TRANSFER), value(format, MediaFormat.KEY_COLOR_STANDARD), value(format, MediaFormat.KEY_COLOR_RANGE))
        }
        val audio = expected.audio as? AvcAacAudioProfile.Aac ?: invalidAvcAacOutput()
        if (format.getString(MediaFormat.KEY_MIME) != "audio/raw" ||
            value(format, MediaFormat.KEY_SAMPLE_RATE) != audio.sampleRate || value(format, MediaFormat.KEY_CHANNEL_COUNT) != audio.channels ||
            value(format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) != AudioFormat.ENCODING_PCM_16BIT) unsupportedAvcAacOutput()
        return listOf(audio.sampleRate, audio.channels, AudioFormat.ENCODING_PCM_16BIT)
    }

    private fun checkSdr(format: MediaFormat) {
        if ((format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && value(format, MediaFormat.KEY_COLOR_TRANSFER) !in setOf(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)) ||
            format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) unsupportedAvcAacOutput()
    }

    private fun requireEof(extractor: MediaExtractor, buffer: ByteBuffer, checkpoint: () -> Unit) {
        checkpoint(); buffer.clear()
        if (extractor.sampleTrackIndex != -1 || extractor.sampleTime != -1L || extractor.sampleSize != -1L ||
            extractor.readSampleData(buffer, 0) != -1) invalidAvcAacOutput()
    }

    /** Owns only Android native objects; the original descriptor is always borrowed. */
    private class NativeOwner(private val read: BorrowedAvcAacRead, private val resources: AvcAacNativeResources) {
        fun extractor(size: Long): MediaExtractor {
            check(size == read.extentBytes)
            val value = MediaExtractor()
            resources.add(value, release = value::release)
            value.setDataSource(read.originalFd, 0, size)
            return value
        }
        fun codec(mime: String): MediaCodec = MediaCodec.createDecoderByType(mime).also {
            resources.add(it, stop = it::stop, release = it::release)
        }
        fun started(codec: MediaCodec) = resources.started(codec)
        fun release(owner: Any) = resources.release(owner)
    }

    private fun value(format: MediaFormat, key: String, fallback: Int = -1) =
        if (format.containsKey(key)) format.getInteger(key) else fallback
}
