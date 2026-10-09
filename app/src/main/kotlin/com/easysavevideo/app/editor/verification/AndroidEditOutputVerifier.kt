package com.easysavevideo.app.editor.verification

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.EditOutputExpectation
import com.easysavevideo.library.edit.EditOutputRef
import com.easysavevideo.library.edit.EditVerificationEvidence
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Independent edit-only verifier. A Transformer callback is not its input evidence. Only a private
 * immutable staging read capability reaches Android native parsers. Full demux and complete decode
 * are engineering-budgeted, not a claim of real-device performance or visual/quality acceptance.
 */
class AndroidEditOutputVerifier(
    private val openOwnedRead: (EditOutputRef) -> EditOwnedOutputRead,
    private val timeoutMillis: Long = EditLimits.MAX_VERIFY_TIMEOUT_MILLIS,
) {
    init { require(timeoutMillis in 1..EditLimits.MAX_VERIFY_TIMEOUT_MILLIS) }
    fun requiresProcessRecovery(): Boolean = nativeGate.recoveryRequired

    fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext): EditVerificationEvidence {
        checkActive(context)
        if (context.operationId != output.operationId) throw EditFailure(EditError.INVALID_REQUEST)
        val owner = NativeOwner()
        if (!nativeGate.acquire(owner)) throw EditFailure(if (nativeGate.recoveryRequired) EditError.RELEASE_UNCONFIRMED else EditError.CAPACITY)
        val allottedMillis = minOf(timeoutMillis, EditLimits.verificationTimeoutMillis(expected.durationUs),
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1))
        val deadline = System.nanoTime() + allottedMillis * 1_000_000
        val future: Future<EditVerificationEvidence>
        try {
            future = nativeLane.submit<EditVerificationEvidence> {
                var confirmed = false
                try {
                    if (!nativeGate.start(owner)) throw EditFailure(EditError.CANCELLED)
                    val evidence = inspect(output, expected, context, deadline, owner)
                    // Evidence is withheld until the actual native cleanup and descriptor close.
                    confirmed = owner.releaseAll()
                    if (!confirmed) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
                    checkpoint(context, deadline)
                    evidence.copy(nativeReleaseConfirmed = true).also {
                        if (!it.validFor(output, expected)) invalidEditOutput()
                    }
                } finally {
                    if (!owner.cleanupFinished) confirmed = owner.releaseAll()
                    nativeGate.finish(owner, confirmed)
                    owner.cleanup.finished(confirmed)
                }
            }
        } catch (_: RejectedExecutionException) {
            nativeGate.rejectedBeforeScheduling(owner)
            throw EditFailure(EditError.CAPACITY)
        } catch (_: Throwable) {
            // An unexpected scheduling failure does not prove the task was never admitted.
            nativeGate.abandon(owner); owner.abandoned = true
            owner.cleanup.awaitConfirmed(EditLimits.RELEASE_TIMEOUT_MILLIS)
            throw EditFailure(EditError.RELEASE_UNCONFIRMED)
        }
        var received = false
        try {
            while (true) {
                checkpoint(context, deadline)
                try {
                    val result = future.get(minOf(50_000_000L, (deadline - System.nanoTime()).coerceAtLeast(1)), TimeUnit.NANOSECONDS)
                    received = true
                    checkpoint(context, deadline)
                    return result
                } catch (_: TimeoutException) { /* Poll caller cancellation without opening another native lane. */ }
            }
        } catch (failure: ExecutionException) {
            received = true
            throw (failure.cause as? EditFailure ?: EditFailure(EditError.VERIFICATION_FAILED))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EditFailure(EditError.CANCELLED)
        } finally {
            if (!received) {
                nativeGate.abandon(owner)
                // Do not cancel a queued Future: its worker must acknowledge and release its gate.
                // The cooperative checkpoint fences late results; an interrupt is not closure.
                owner.abandoned = true
                if (!owner.cleanup.awaitConfirmed(EditLimits.RELEASE_TIMEOUT_MILLIS))
                    throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            }
        }
    }

    private fun inspect(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext,
        deadline: Long, owner: NativeOwner): EditVerificationEvidence {
        fun check() { if (owner.abandoned) throw EditFailure(EditError.CANCELLED); checkpoint(context, deadline) }
        check()
        val read = openOwnedRead(output).also { owner.read = it }
        if (read.output != output) invalidEditOutput()
        val channel = read.input.channel
        val size = channel.size()
        if (size !in 1..expected.maxBytes || size != read.expectedBytes) invalidEditOutput()
        val initialHash = hash(channel, size, ::check)
        val timing = EditMp4Probe.read(channel, size, expected, ::check)
        val formats = inspectFormats(owner, expected, size, ::check)
        val videoInfo = formats.single { it.video }
        val videoTiming = timing.single { it.kind == "vide" }
        val videoScan = scan(owner, videoInfo, videoTiming, expected, size, ::check)
        val audioInfo = formats.singleOrNull { !it.video }
        val audioScan = audioInfo?.let { scan(owner, it, timing.single { track -> track.kind == "soun" }, expected, size, ::check) }
        val videoDecoded = decode(owner, videoInfo, videoScan, expected, size, ::check)
        val audioDecoded = audioInfo?.let { decode(owner, it, audioScan!!, expected, size, ::check) }
        check()
        if (channel.size() != size || hash(channel, size, ::check) != initialHash) invalidEditOutput()
        val audio = expected.audio as? EditAudioProfile.Aac
        return EditVerificationEvidence(output, expected, size, initialHash,
            maxOf(videoScan.endUs, audioScan?.endUs ?: 0),
            videoInfo.format.getInteger(MediaFormat.KEY_WIDTH), videoInfo.format.getInteger(MediaFormat.KEY_HEIGHT),
            value(videoInfo.format, MediaFormat.KEY_ROTATION, 0), "video/avc",
            audio?.let { "audio/mp4a-latm" }, audio?.sampleRate, audio?.channels,
            videoScan.firstPtsUs, videoScan.lastPtsUs, videoScan.samples.size.toLong(), audioScan?.samples?.size?.toLong() ?: 0,
            videoDecoded, audioDecoded ?: 0, true, audio != null, false, true)
    }

    private data class Track(val index: Int, val format: MediaFormat, val video: Boolean)

    private fun inspectFormats(owner: NativeOwner, expected: EditOutputExpectation, size: Long,
        checkpoint: () -> Unit): List<Track> {
        val extractor = owner.extractor(size)
        try {
            checkpoint()
            if (!extractor.psshInfo.isNullOrEmpty() || extractor.trackCount != (if (expected.audio is EditAudioProfile.Silent) 1 else 2)) invalidEditOutput()
            val tracks = (0 until extractor.trackCount).map { index ->
                checkpoint()
                val format = extractor.getTrackFormat(index)
                val video = when (format.getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> true
                    "audio/mp4a-latm" -> false
                    else -> unsupportedEditOutput()
                }
                checkEncodedFormat(format, video, expected)
                Track(index, format, video)
            }
            if (tracks.count { it.video } != 1 || tracks.count { !it.video } != (if (expected.audio is EditAudioProfile.Silent) 0 else 1)) invalidEditOutput()
            return tracks
        } finally { owner.release(extractor) }
    }

    private fun checkEncodedFormat(format: MediaFormat, video: Boolean, expected: EditOutputExpectation) {
        if (video) {
            if (value(format, MediaFormat.KEY_WIDTH) != expected.width || value(format, MediaFormat.KEY_HEIGHT) != expected.height ||
                value(format, MediaFormat.KEY_ROTATION, 0) != 0 ||
                (format.containsKey("sar-width") && value(format, "sar-width") != value(format, "sar-height"))) invalidEditOutput()
            if (format.containsKey(MediaFormat.KEY_PROFILE) && value(format, MediaFormat.KEY_PROFILE) !in setOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)) unsupportedEditOutput()
            checkSdr(format)
        } else {
            val audio = expected.audio as? EditAudioProfile.Aac ?: invalidEditOutput()
            if (value(format, MediaFormat.KEY_SAMPLE_RATE) != audio.sampleRate || value(format, MediaFormat.KEY_CHANNEL_COUNT) != audio.channels ||
                (format.containsKey(MediaFormat.KEY_AAC_PROFILE) && value(format, MediaFormat.KEY_AAC_PROFILE) != MediaCodecInfo.CodecProfileLevel.AACObjectLC)) invalidEditOutput()
            val config = format.getByteBuffer("csd-0")?.duplicate() ?: unsupportedEditOutput()
            if (config.remaining() !in 2..EditVerificationBounds.MAX_CONFIG_BYTES) unsupportedEditOutput()
            val data = ByteArray(config.remaining()).also(config::get)
            EditAacConfigPolicy.requireLc(data, audio.sampleRate, audio.channels)
            for (key in listOf(MediaFormat.KEY_ENCODER_DELAY, MediaFormat.KEY_ENCODER_PADDING)) {
                val frames = value(format, key, 0)
                if (frames < 0 || frames * 1_000_000L / audio.sampleRate > expected.audioPrimingToleranceUs) unsupportedEditOutput()
            }
        }
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
        val tolerance = expected.durationToleranceUs + if (video) 0 else expected.audioPrimingToleranceUs
        if (duration <= 0 || kotlin.math.abs(duration - expected.durationUs) > tolerance + 1_000) invalidEditOutput()
    }

    private fun scan(owner: NativeOwner, track: Track, timing: EditMp4Track, expected: EditOutputExpectation,
        size: Long, checkpoint: () -> Unit): EditScannedTrack {
        val extractor = owner.extractor(size)
        try {
            extractor.selectTrack(track.index)
            val ledger = EditSampleLedger(track.video, expected, timing)
            val buffer = ByteBuffer.allocateDirect(EditVerificationBounds.MAX_SAMPLE_BYTES)
            while (extractor.sampleTrackIndex != -1) {
                checkpoint()
                if (extractor.sampleTrackIndex != track.index) invalidEditOutput()
                val bytes = extractor.sampleSize
                if (bytes !in 1..EditVerificationBounds.MAX_SAMPLE_BYTES.toLong()) unsupportedEditOutput()
                buffer.clear()
                if (extractor.readSampleData(buffer, 0).toLong() != bytes) invalidEditOutput()
                ledger.sample(extractor.sampleTime, bytes, extractor.sampleFlags)
                extractor.advance()
            }
            requireEof(extractor, buffer, checkpoint)
            return ledger.finish()
        } finally { owner.release(extractor) }
    }

    private fun decode(owner: NativeOwner, track: Track, scan: EditScannedTrack, expected: EditOutputExpectation,
        size: Long, checkpoint: () -> Unit): Long {
        val extractor = owner.extractor(size)
        var codec: MediaCodec? = null
        try {
            extractor.selectTrack(track.index)
            codec = owner.codec(if (track.video) "video/avc" else "audio/mp4a-latm")
            codec.configure(track.format, null, null, 0)
            codec.start(); owner.started(codec)
            val video = if (track.video) EditVideoDecodeLedger(scan) else null
            val audio = if (track.video) null else EditAudioDecodeLedger(expected, scan)
            var queued = 0
            var inputEnded = false
            var formatSignature: List<Int>? = null
            val info = MediaCodec.BufferInfo()
            val progress = EditDecodeProgress(System.nanoTime())
            fun outputFormat(format: MediaFormat) {
                val signature = decodedFormatSignature(format, track.video, expected)
                if (formatSignature != null && formatSignature != signature) unsupportedEditOutput()
                formatSignature = signature
            }
            while (video?.outputEos != true && audio?.outputEos != true) {
                checkpoint(); progress.check(System.nanoTime())
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        checkpoint()
                        val input = codec.getInputBuffer(index) ?: invalidEditOutput()
                        if (extractor.sampleTrackIndex == -1) {
                            requireEof(extractor, input, checkpoint)
                            if (queued != scan.samples.size) invalidEditOutput()
                            video?.inputEnd(); audio?.inputEnd()
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (extractor.sampleTrackIndex != track.index || queued >= scan.samples.size) invalidEditOutput()
                            val sizeBytes = extractor.sampleSize
                            if (sizeBytes !in 1..EditVerificationBounds.MAX_SAMPLE_BYTES.toLong() || sizeBytes > input.capacity()) unsupportedEditOutput()
                            val sample = EditEncodedSample(extractor.sampleTime, sizeBytes.toInt(), extractor.sampleFlags)
                            if (sample != scan.samples[queued]) invalidEditOutput()
                            input.clear()
                            if (extractor.readSampleData(input, 0).toLong() != sizeBytes) invalidEditOutput()
                            video?.input(sample); audio?.input(sample)
                            codec.queueInputBuffer(index, 0, sizeBytes.toInt(), sample.ptsUs, 0)
                            queued++; extractor.advance()
                        }
                        progress.progressed(System.nanoTime())
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat(codec.outputFormat)
                    index == MediaCodec.INFO_TRY_AGAIN_LATER || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> {
                        try {
                            checkpoint()
                            outputFormat(codec.getOutputFormat(index))
                            if (info.offset < 0 || info.size < 0 || info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0) unsupportedEditOutput()
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index) ?: invalidEditOutput()
                                if (info.offset.toLong() + info.size > buffer.capacity()) invalidEditOutput()
                            }
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val madeProgress = video?.output(info.presentationTimeUs, info.size, config, eos)
                                ?: audio!!.output(info.presentationTimeUs, info.size, config, eos)
                            if (madeProgress) progress.progressed(System.nanoTime())
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                    else -> invalidEditOutput()
                }
            }
            checkpoint()
            if (formatSignature == null) invalidEditOutput()
            return video?.decodedFrames ?: audio!!.decodedFrames
        } finally {
            try { codec?.let(owner::release) } finally { owner.release(extractor) }
        }
    }

    private fun decodedFormatSignature(format: MediaFormat, video: Boolean, expected: EditOutputExpectation): List<Int> {
        if (video) {
            if (format.getString(MediaFormat.KEY_MIME) != "video/raw") unsupportedEditOutput()
            val width = value(format, MediaFormat.KEY_WIDTH)
            val height = value(format, MediaFormat.KEY_HEIGHT)
            val left = value(format, "crop-left", 0); val right = value(format, "crop-right", width - 1)
            val top = value(format, "crop-top", 0); val bottom = value(format, "crop-bottom", height - 1)
            if (width !in 1..2048 || height !in 1..2048 || left !in 0 until width || right !in left until width ||
                top !in 0 until height || bottom !in top until height || right - left + 1 != expected.width ||
                bottom - top + 1 != expected.height || value(format, MediaFormat.KEY_ROTATION, 0) != 0) invalidEditOutput()
            checkSdr(format)
            return listOf(width, height, left, right, top, bottom, value(format, MediaFormat.KEY_COLOR_FORMAT),
                value(format, MediaFormat.KEY_COLOR_TRANSFER), value(format, MediaFormat.KEY_COLOR_STANDARD), value(format, MediaFormat.KEY_COLOR_RANGE))
        }
        val audio = expected.audio as? EditAudioProfile.Aac ?: invalidEditOutput()
        if (format.getString(MediaFormat.KEY_MIME) != "audio/raw" ||
            value(format, MediaFormat.KEY_SAMPLE_RATE) != audio.sampleRate || value(format, MediaFormat.KEY_CHANNEL_COUNT) != audio.channels ||
            value(format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) != AudioFormat.ENCODING_PCM_16BIT) unsupportedEditOutput()
        return listOf(audio.sampleRate, audio.channels, AudioFormat.ENCODING_PCM_16BIT)
    }

    private fun checkSdr(format: MediaFormat) {
        if ((format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && value(format, MediaFormat.KEY_COLOR_TRANSFER) !in setOf(0, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)) ||
            format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) unsupportedEditOutput()
    }

    private fun requireEof(extractor: MediaExtractor, buffer: ByteBuffer, checkpoint: () -> Unit) {
        checkpoint(); buffer.clear()
        if (extractor.sampleTrackIndex != -1 || extractor.sampleTime != -1L || extractor.sampleSize != -1L ||
            extractor.readSampleData(buffer, 0) != -1) invalidEditOutput()
    }

    private fun hash(channel: FileChannel, size: Long, checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = 0L
        while (position < size) {
            checkpoint(); buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
            val count = channel.read(buffer, position)
            if (count <= 0) invalidEditOutput()
            buffer.flip(); digest.update(buffer); position += count
        }
        if (channel.size() != size) invalidEditOutput()
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    /** Retained strongly by the process gate until native cleanup and storage close are confirmed. */
    private class NativeOwner {
        val cleanup = EditVerificationCleanup()
        var read: EditOwnedOutputRead? = null
        @Volatile var abandoned = false
        var cleanupFinished = false; private set
        private var releaseConfirmed = true
        private data class Resource(val owner: Any, var started: Boolean = false, var attempted: Boolean = false)
        private val resources = ArrayList<Resource>()

        fun extractor(size: Long): MediaExtractor {
            val value = MediaExtractor()
            resources.add(Resource(value))
            value.setDataSource(read!!.input.fd, 0, size)
            return value
        }
        fun codec(mime: String): MediaCodec = MediaCodec.createDecoderByType(mime).also { resources.add(Resource(it)) }
        fun started(codec: MediaCodec) { resources.single { it.owner === codec }.started = true }
        fun release(owner: Any) {
            val resource = resources.singleOrNull { it.owner === owner } ?: return
            if (resource.attempted) return
            resource.attempted = true
            var stopped = true
            if (owner is MediaCodec && resource.started) try { owner.stop() } catch (_: Throwable) { stopped = false }
            try {
                when (owner) { is MediaCodec -> owner.release(); is MediaExtractor -> owner.release() }
                resources.remove(resource)
            } catch (_: Throwable) {
                releaseConfirmed = false
                throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            }
            if (!stopped) invalidEditOutput()
        }
        fun releaseAll(): Boolean {
            if (cleanupFinished) return releaseConfirmed
            resources.toList().asReversed().forEach {
                try { release(it.owner) } catch (_: Throwable) { /* Actual release status is kept separately. */ }
            }
            if (resources.isNotEmpty()) releaseConfirmed = false
            if (releaseConfirmed) {
                try { read?.closeAfterNativeRelease() } catch (_: Throwable) { releaseConfirmed = false }
            }
            if (!releaseConfirmed) try { read?.retainForProcessRecovery() } catch (_: Throwable) { /* Still retained here. */ }
            if (releaseConfirmed) read = null
            cleanupFinished = true
            return releaseConfirmed
        }
    }

    companion object {
        private val nativeGate = EditVerificationNativeGate()
        private val nativeLane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
            Thread(runnable, "esv-edit-output-check").apply { isDaemon = true }
        }.apply { rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy() }
        private fun value(format: MediaFormat, key: String, fallback: Int = -1) = if (format.containsKey(key)) format.getInteger(key) else fallback
        private fun checkActive(context: OperationContext) {
            if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw EditFailure(EditError.CANCELLED)
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) throw EditFailure(EditError.TIMEOUT)
        }
        private fun checkpoint(context: OperationContext, deadline: Long) {
            checkActive(context)
            if (System.nanoTime() >= deadline) throw EditFailure(EditError.TIMEOUT)
        }
    }
}
