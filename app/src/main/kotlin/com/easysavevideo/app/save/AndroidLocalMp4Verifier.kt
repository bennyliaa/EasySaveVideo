package com.easysavevideo.app.save

import com.easysavevideo.app.media.verification.*
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import com.easysavevideo.recording.RecordingArtifactExpectation
import com.easysavevideo.recording.RecordingTrackEvidence
import com.easysavevideo.recording.RecordingResumeBoundary
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Only an owned local staging FD reaches Android media parsers. The process-wide lane stays
 * occupied if a native call ignores interruption. No replacement threads are created, and
 * timed-out evidence cannot return to the caller or publish a media item.
 */
class AndroidLocalMp4Verifier private constructor(
    private val stagingFile: (StagingArtifact) -> File,
    private val timeoutMillis: Long,
    private val normalizedOutputOwner: DirectNormalizedOutputOwner?,
    private val legacyReadOwnership: ((StagingArtifact, OperationContext) -> ((Boolean) -> Unit))?,
    private val convertedInspectionFactory: (BorrowedAvcAacRead) -> BorrowedAvcAacInspection,
) : ArtifactVerifier {
    constructor(stagingFile: (StagingArtifact) -> File, timeoutMillis: Long,
        normalizedOutputOwner: DirectNormalizedOutputOwner? = null,
        legacyReadOwnership: ((StagingArtifact, OperationContext) -> ((Boolean) -> Unit))? = null) :
        this(stagingFile, timeoutMillis, normalizedOutputOwner, legacyReadOwnership, { BorrowedAvcAacInspection(it) })

    /** Host-only native-body seam; execution still uses the real existing lane and ownership body. */
    internal constructor(stagingFile: (StagingArtifact) -> File, timeoutMillis: Long,
        normalizedOutputOwner: DirectNormalizedOutputOwner,
        legacyReadOwnership: ((StagingArtifact, OperationContext) -> ((Boolean) -> Unit))?,
        convertedNativeBody: (BorrowedAvcAacRead, AvcAacNativeResources, AvcAacOutputExpectation, () -> Unit) -> AvcAacOutputObservations) :
        this(stagingFile, timeoutMillis, normalizedOutputOwner, legacyReadOwnership,
            { read: BorrowedAvcAacRead -> BorrowedAvcAacInspection(read, convertedNativeBody) })

    override fun requiresProcessRecovery(): Boolean = isDraining || normalizedOutputOwner?.requiresProcessRecovery() == true

    override fun verify(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext): VerificationEvidence =
        verify(artifact, ArtifactOutputDescriptor.Transferred(transfer), context)

    override fun verify(artifact: StagingArtifact, output: ArtifactOutputDescriptor,
        context: OperationContext): VerificationEvidence {
        if (requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        checkActive(context)
        when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> DirectNormalizedPreflight.check(artifact, output, context)
            is ArtifactOutputDescriptor.ConvertedDirect -> DirectConvertedPreflight.check(artifact, output, context)
            is ArtifactOutputDescriptor.Transferred -> Unit
        }
        // The legacy pathname callback cannot assert either adopted-generation ownership.
        if (output !is ArtifactOutputDescriptor.Transferred && normalizedOutputOwner == null)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val deadlineNanos = System.nanoTime() + minOf(timeoutMillis,
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1)) * 1_000_000
        val lease = NativeVerificationLease({ draining.incrementAndGet() }, { draining.decrementAndGet() })
        val future: Future<VerificationEvidence> = try {
            nativeLane.submit<VerificationEvidence> {
                if (!lease.start()) throw SaveFailure(SaveError.CANCELLED)
                try {
                    when (output) {
                        is ArtifactOutputDescriptor.ConvertedDirect -> DirectConvertedAndroidVerifier(
                            requireNotNull(normalizedOutputOwner), { releaseUnconfirmed.set(true) }, convertedInspectionFactory
                        ).inspect(artifact, output, context) {
                            checkActive(context)
                            if (System.nanoTime() >= deadlineNanos) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
                        }
                        is ArtifactOutputDescriptor.Transferred -> {
                            val report = legacyReadOwnership?.invoke(artifact, context)
                            var inputReleased = false
                            try { inspect(artifact, output.transfer, context, deadlineNanos) { inputReleased = it } }
                            finally {
                                // Actual worker finally, never Future completion or cancellation.
                                try { report?.invoke(inputReleased && !releaseUnconfirmed.get()) }
                                catch (_: Exception) { releaseUnconfirmed.set(true) }
                            }
                        }
                        is ArtifactOutputDescriptor.NormalizedDirect -> DirectNormalizedAndroidVerifier(
                            requireNotNull(normalizedOutputOwner), { releaseUnconfirmed.set(true) }
                        ).inspect(artifact, output, context) {
                            checkActive(context)
                            if (System.nanoTime() >= deadlineNanos) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
                        }
                    }
                }
                finally { lease.released() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE) }
        try {
            while (true) {
                checkActive(context)
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
                try {
                    val result = future.get(minOf(remaining, 50_000_000), TimeUnit.NANOSECONDS)
                    checkActive(context)
                    if (System.nanoTime() >= deadlineNanos) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
                    if (output !is ArtifactOutputDescriptor.Transferred && requiresProcessRecovery())
                        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    return result
                } catch (_: TimeoutException) { /* Keep the UI cancellation latency bounded. */ }
            }
        } catch (error: ExecutionException) {
            throw (error.cause as? SaveFailure ?: SaveFailure(if (output is ArtifactOutputDescriptor.ConvertedDirect)
                SaveError.VERIFICATION_UNAVAILABLE else SaveError.INVALID_MEDIA))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); throw SaveFailure(SaveError.CANCELLED)
        } finally {
            // An interrupt is a request, not a claim that Android native parsing stopped.
            if (!future.isDone) {
                lease.abandon()
                future.cancel(true)
            }
            nativeLane.purge()
        }
    }

    private fun inspect(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext,
        deadlineNanos: Long, inputClosed: (Boolean) -> Unit = {}): VerificationEvidence {
        fun checkpoint() {
            checkActive(context)
            if (System.nanoTime() >= deadlineNanos) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
        }
        val file = stagingFile(artifact)
        val input = RandomAccessFile(file, "r")
        var inputDescriptor: java.io.FileDescriptor? = null
        try {
            inputDescriptor = input.fd
            checkpoint()
            val size = input.length()
            if (size <= 0 || size != transfer.bytesOnDisk ||
                (transfer.expectedTotalBytes != null && size != transfer.expectedTotalBytes)) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            if (!Mp4ContainerProbe.isCompleteMp4(input, ::checkpoint)) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            val expectedStream = transfer.streamExpectation?.let {
                it.conservation ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            }
            // Decoder evidence may not be paired with a later, same-size mutation of the MP4.
            val expectedRecording = transfer.recordingExpectation
            if (expectedRecording != null) {
                if (expectedRecording.resumeBoundaries == null) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                if (size > 2L * 1024 * 1024 * 1024) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            }
            val verifiedSnapshot = if (expectedStream != null || expectedRecording != null) hashFile(input, size, ::checkpoint) else null
            val extractor = MediaExtractor()
            var retrieverToRelease: MediaMetadataRetriever? = null
            try {
                val retriever = MediaMetadataRetriever().also { retrieverToRelease = it }
                extractor.setDataSource(input.fd, 0, size)
                checkpoint()
                if (!extractor.psshInfo.isNullOrEmpty()) throw SaveFailure(SaveError.PROTECTED_MEDIA)
                if (extractor.trackCount !in 1..16) throw SaveFailure(SaveError.INVALID_MEDIA)
                var videos = 0
                var audios = 0
                var durationUs = 0L
                var width = 0
                var height = 0
                var primaryTrack = -1
                var primaryFormat: MediaFormat? = null
                val audioFormats = ArrayList<Triple<Int, MediaFormat, Long>>()
                val sampleBuffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
                repeat(extractor.trackCount) { track ->
                    checkpoint()
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (!isVideo && !isAudio) {
                        if (expectedStream != null || expectedRecording != null) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                        return@repeat
                    }
                    val trackDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                    if (trackDuration <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                    if (isVideo) {
                        videos++
                        val w = format.getInteger(MediaFormat.KEY_WIDTH)
                        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                        if (w !in 1..8192 || h !in 1..8192 || w.toLong() * h > 16_777_216) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                        if (videos == 1) { width = w; height = h; durationUs = trackDuration; primaryTrack = track; primaryFormat = format }
                    } else { audios++; audioFormats.add(Triple(track, format, trackDuration)) }
                    if (expectedStream == null && expectedRecording == null) {
                        extractor.selectTrack(track)
                        try {
                            for (position in sampleTimes(trackDuration)) {
                                checkpoint()
                                extractor.seekTo(position, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                var advances = 0
                                while (extractor.sampleTime >= 0 && extractor.sampleTime < position - 500_000 && advances++ < 4096) {
                                    checkpoint(); if (!extractor.advance()) break
                                }
                                if (extractor.sampleTrackIndex != track || extractor.sampleTime < 0 ||
                                    abs(extractor.sampleTime - position) > minOf(2_000_000, maxOf(100_000, trackDuration / 10)) ||
                                    extractor.sampleSize !in 1L..MAX_SAMPLE_BYTES.toLong()) throw SaveFailure(SaveError.INVALID_MEDIA)
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw SaveFailure(SaveError.PROTECTED_MEDIA)
                                sampleBuffer.clear()
                                if (extractor.readSampleData(sampleBuffer, 0) <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                            }
                        } finally { extractor.unselectTrack(track) }
                    }
                }
                if (videos <= 0 || durationUs <= 0 || width <= 0 || height <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                checkpoint()
                retriever.setDataSource(input.fd, 0, size)
                checkpoint()
                val containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                if (containerMime != "video/mp4" && containerMime != "application/mp4") throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                val metadataDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val durationMillis = durationUs / 1000
                if (metadataDuration <= 0 || abs(metadataDuration - durationMillis) > maxOf(3000L, durationMillis / 10)) throw SaveFailure(SaveError.INVALID_MEDIA)
                val conserved = expectedStream?.let { expected ->
                    if (videos != 1 || audios != (if (expected.audio == null) 0 else 1) ||
                        extractor.trackCount != videos + audios)
                        throw SaveFailure(SaveError.INVALID_MEDIA)
                    inspectStreamSamples(extractor, primaryTrack, primaryFormat!!, audioFormats, expected, ::checkpoint)
                }
                val recordingScan = expectedRecording?.let { expected ->
                    if (videos != 1 || audios != expected.audioTrackCount || extractor.trackCount != videos + audios)
                        throw SaveFailure(SaveError.INVALID_MEDIA)
                    inspectRecordingSamples(extractor, primaryTrack, primaryFormat!!, audioFormats, expected,
                        RecordingMp4TimingProbe.read(input, ::checkpoint), ::checkpoint)
                }
                val positions = if (recordingScan == null)
                    decodePositions(extractor, primaryTrack, primaryFormat!!, durationUs, ::checkpoint)
                else DecodePosition.entries.toSet()
                val tailProof = conserved?.let {
                    decodeVideoTail(extractor, primaryTrack, primaryFormat!!, it, ::checkpoint)
                }
                val recordingTerminalUs = recordingScan?.let { inspected ->
                    var terminal = -1L
                    val decodedFormat = RecordingVideoFormatState(width, height)
                    for (gop in inspected.selectedGops) {
                        checkpoint()
                        val decodedLast = decodeRecordingGop(extractor, primaryTrack, primaryFormat!!, gop, decodedFormat, ::checkpoint)
                        if (gop.nextSync == null) terminal = decodedLast
                    }
                    if (terminal != inspected.video.lastPtsUs) throw SaveFailure(SaveError.INVALID_MEDIA)
                    terminal
                }
                audioFormats.forEach { (track, format, duration) ->
                    if (recordingScan != null) decodeRecordingAudioPositions(extractor, track, format,
                        recordingScan.audio!!, recordingScan.resumeBoundaries, ::checkpoint)
                    else if (transfer.streamExpectation != null) decodeStreamAudio(extractor, track, format, duration, ::checkpoint)
                    else decodePositions(extractor, track, format, duration, ::checkpoint)
                }
                if (recordingScan == null) DecodePosition.entries.zip(sampleTimes(durationUs).asIterable()).forEach { (_, time) ->
                    checkpoint()
                    val frame = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                        ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                    try {
                        if (frame.width <= 0 || frame.height <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                        checkpoint()
                    } finally { frame.recycle() }
                }
                val sha256 = hashFile(input, size, ::checkpoint)
                if (verifiedSnapshot != null && sha256 != verifiedSnapshot) throw SaveFailure(SaveError.RESOURCE_CHANGED)
                val recordingProof = recordingScan?.let {
                    val audioFormat = audioFormats.singleOrNull()?.second
                    RecordingVerificationProof(it.video, it.audio, primaryFormat!!.getString(MediaFormat.KEY_MIME)!!,
                        audioFormat?.getString(MediaFormat.KEY_MIME), audioFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        audioFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT), recordingTerminalUs!!, videoOutputEos = true,
                        decodedResumeBoundaries = it.resumeBoundaries)
                }
                val actualDurationMillis = recordingScan?.let { maxOf(it.video.endPtsUs, it.audio?.endPtsUs ?: 0) / 1000 }
                    ?: durationMillis
                if (recordingScan != null && abs(metadataDuration - actualDurationMillis) > 1)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                return VerificationEvidence(artifact.artifactId, size, sha256, true,
                    videos, audios, actualDurationMillis, width, height, false, positions, true,
                    streamConservation = conserved?.contract, streamTailProof = tailProof, recordingProof = recordingProof)
            } finally {
                var released = true
                try { retrieverToRelease?.release() } catch (_: Exception) { released = false; releaseUnconfirmed.set(true) }
                try { extractor.release() } catch (_: Exception) { released = false; releaseUnconfirmed.set(true) }
                if (!released) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
        } finally {
            try { input.close() } catch (_: Exception) { releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
            inputClosed(inputDescriptor?.valid() == false)
        }
    }
    private fun hashFile(input: RandomAccessFile, size: Long, checkpoint: () -> Unit): String {
        input.seek(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (bytes < size) {
            checkpoint()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - bytes).toInt())
            if (count <= 0) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            digest.update(buffer, 0, count); bytes += count
        }
        if (input.length() != size) throw SaveFailure(SaveError.INVALID_MEDIA)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class InspectedRecording(
        val video: RecordingTrackEvidence,
        val audio: RecordingTrackEvidence?,
        val selectedGops: List<RecordingSelectedGop>,
        val resumeBoundaries: List<RecordingResumeBoundary>,
    )

    /** RECORD has its own full sample walk and identity set; no Stream expectation is synthesized. */
    private fun inspectRecordingSamples(extractor: MediaExtractor, videoTrack: Int, videoFormat: MediaFormat,
        audioFormats: List<Triple<Int, MediaFormat, Long>>, expected: RecordingArtifactExpectation,
        timing: Map<String, RecordingTrackTiming>, checkpoint: () -> Unit): InspectedRecording {
        if (videoFormat.getString(MediaFormat.KEY_MIME) != "video/avc" ||
            videoFormat.getInteger(MediaFormat.KEY_WIDTH) != expected.actualProfile.width ||
            videoFormat.getInteger(MediaFormat.KEY_HEIGHT) != expected.actualProfile.height)
            throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val boundaries = expected.resumeBoundaries ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (timing.keys != (if (expected.audio == null) setOf("vide") else setOf("vide", "soun")) ||
            timing.values.sumOf { it.sampleCount } > 10_000_000) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val sampleBuffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        var inspectedBytes = 0L
        fun scan(track: Int, video: Boolean, sampleCount: Long): Pair<RecordingTrackEvidence, RecordingSampleScan> {
            val trackTiming = timing[if (video) "vide" else "soun"] ?: throw SaveFailure(SaveError.INVALID_MEDIA)
            val scan = RecordingSampleScan(video, sampleCount, if (video) trackTiming.spanUs / 2 else 0,
                if (video) boundaries else emptyList(), checkpoint)
            extractor.selectTrack(track)
            try {
                checkpoint()
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleTime >= 0) {
                    checkpoint()
                    if (extractor.sampleTrackIndex != track) throw SaveFailure(SaveError.INVALID_MEDIA)
                    val flags = extractor.sampleFlags
                    checkRecordingSampleFlags(flags)
                    val time = extractor.sampleTime
                    val bytes = extractor.sampleSize
                    if (bytes !in 1..MAX_SAMPLE_BYTES.toLong()) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                    sampleBuffer.clear()
                    val count = extractor.readSampleData(sampleBuffer, 0)
                    if (count.toLong() != bytes) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                    sampleBuffer.position(0); sampleBuffer.limit(count)
                    inspectedBytes += count
                    if (inspectedBytes > 2L * 1024 * 1024 * 1024) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                    scan.sample(RecordingTailSample(time, flags, count, hashSample(sampleBuffer)))
                    extractor.advance()
                }
                checkpoint(); sampleBuffer.clear()
                if (extractor.sampleTrackIndex != -1 || extractor.sampleSize != -1L || extractor.readSampleData(sampleBuffer, 0) != -1)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                return scan.finish(trackTiming) to scan
            } finally { extractor.unselectTrack(track) }
        }
        val (video, videoScan) = scan(videoTrack, true, expected.video.sampleCount)
        val audio = expected.audio?.let {
            val (track, format) = audioFormats.single()
            if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm" ||
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != expected.actualProfile.audioSampleRate ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != expected.actualProfile.audioChannels)
                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            scan(track, false, it.sampleCount).first
        }
        return InspectedRecording(video, audio, videoScan.selectedGops(), videoScan.observedResumeBoundaries())
    }

    private fun checkRecordingSampleFlags(flags: Int) {
        if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw SaveFailure(SaveError.PROTECTED_MEDIA)
        if (flags != 0 && flags != MediaExtractor.SAMPLE_FLAG_SYNC) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
    }
    private fun hashSample(buffer: ByteBuffer): String = MessageDigest.getInstance("SHA-256").run {
        update(buffer.duplicate()); digest().joinToString("") { "%02x".format(it) }
    }
    private fun recordingOutputFormat(format: MediaFormat): RecordingDecodedVideoFormat {
        fun value(key: String, default: Int = -1) = if (format.containsKey(key)) format.getInteger(key) else default
        val width = value(MediaFormat.KEY_WIDTH); val height = value(MediaFormat.KEY_HEIGHT)
        return RecordingDecodedVideoFormat(format.getString(MediaFormat.KEY_MIME).orEmpty(), width, height,
            value("crop-left", 0), value("crop-right", width - 1), value("crop-top", 0),
            value("crop-bottom", height - 1), value(MediaFormat.KEY_COLOR_FORMAT))
    }

    /** A fresh decoder proves one entire independently scanned GOP, including forced input/output EOS. */
    private fun decodeRecordingGop(extractor: MediaExtractor, track: Int, format: MediaFormat,
        gop: RecordingSelectedGop, outputFormat: RecordingVideoFormatState, checkpoint: () -> Unit): Long {
        val state = RecordingVideoTailState(gop, checkpoint)
        outputFormat.beginGop()
        var decoder: MediaCodec? = null
        var started = false
        extractor.selectTrack(track)
        try {
            checkpoint()
            val codec = MediaCodec.createDecoderByType("video/avc").also { decoder = it }
            codec.configure(format, null, null, 0); codec.start(); started = true
            extractor.seekTo(gop.samples.first().timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (extractor.sampleTrackIndex != track || extractor.sampleTime != gop.samples.first().timeUs ||
                extractor.sampleFlags != MediaExtractor.SAMPLE_FLAG_SYNC) throw SaveFailure(SaveError.INVALID_MEDIA)
            val progress = RecordingDecodeProgress(System.nanoTime(), NATIVE_NO_PROGRESS_NANOS)
            val info = MediaCodec.BufferInfo()
            while (!state.complete) {
                checkpoint(); progress.check(System.nanoTime())
                if (state.canQueueInput) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        checkpoint()
                        val buffer = codec.getInputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                        if (state.queuedSamples == gop.samples.size) {
                            buffer.clear()
                            if (gop.nextSync == null) {
                                if (extractor.sampleTrackIndex != -1 || extractor.sampleTime != -1L || extractor.sampleSize != -1L ||
                                    extractor.readSampleData(buffer, 0) != -1) throw SaveFailure(SaveError.INVALID_MEDIA)
                                state.inputEndOfStream()
                            } else {
                                if (extractor.sampleTrackIndex != track) throw SaveFailure(SaveError.INVALID_MEDIA)
                                state.inputEndOfStream(extractor.sampleTime, extractor.sampleFlags, extractor.sampleSize)
                            }
                            // Non-final GOPs stop before the independently observed next sync sample.
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            if (extractor.sampleTrackIndex != track || extractor.sampleTime < 0)
                                throw SaveFailure(SaveError.INVALID_MEDIA)
                            val flags = extractor.sampleFlags; checkRecordingSampleFlags(flags)
                            val time = extractor.sampleTime; val size = extractor.sampleSize
                            if (size !in 1..MAX_SAMPLE_BYTES.toLong() || size > buffer.capacity()) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count.toLong() != size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                            buffer.position(0); buffer.limit(count)
                            state.input(RecordingTailSample(time, flags, count, hashSample(buffer)))
                            codec.queueInputBuffer(index, 0, count, time, 0)
                            extractor.advance()
                        }
                        progress.madeProgress(System.nanoTime())
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat.accept(recordingOutputFormat(codec.outputFormat), true)
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (index < 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                        try {
                            checkpoint()
                            outputFormat.accept(recordingOutputFormat(codec.getOutputFormat(index)), false)
                            if (info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM or
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0 || info.offset < 0 || info.size < 0)
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                                if (info.offset.toLong() + info.size > buffer.capacity()) throw SaveFailure(SaveError.INVALID_MEDIA)
                            }
                            if (state.output(info.presentationTimeUs, info.size, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
                                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)) progress.madeProgress(System.nanoTime())
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            checkpoint()
            return state.terminalOutputTimeUs
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } catch (_: Exception) {
                    releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                } finally { extractor.unselectTrack(track) }
            }
        }
    }

    /** Bounded actual AAC readability at start/middle/end and each in-range resume boundary. */
    private fun decodeRecordingAudioPositions(extractor: MediaExtractor, track: Int, format: MediaFormat,
        expected: RecordingTrackEvidence, resumeBoundaries: List<RecordingResumeBoundary>, checkpoint: () -> Unit) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val targets = linkedSetOf(expected.firstPtsUs, expected.firstPtsUs + (expected.lastPtsUs - expected.firstPtsUs) / 2,
            expected.lastPtsUs)
        resumeBoundaries.map { it.ptsUs }.filter { it in expected.firstPtsUs..expected.lastPtsUs }.forEach(targets::add)
        if (targets.size > RecordingGopBounds.MAX_GOPS) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        var decoder: MediaCodec? = null
        var started = false
        extractor.selectTrack(track)
        try {
            checkpoint()
            val codec = MediaCodec.createDecoderByType("audio/mp4a-latm").also { decoder = it }
            codec.configure(format, null, null, 0); codec.start(); started = true
            var totalBytes = 0L
            var firstWindow = true
            fun checkFormat(actual: MediaFormat) {
                if (actual.getString(MediaFormat.KEY_MIME) != "audio/raw" ||
                    !actual.containsKey(MediaFormat.KEY_SAMPLE_RATE) || actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate ||
                    !actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT) || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels)
                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            }
            for (target in targets) {
                checkpoint()
                if (!firstWindow) codec.flush()
                firstWindow = false
                extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var inputEos = false
                var queued = 0
                var windowBytes = 0L
                var lastInput = -1L
                var lastOutput = -1L
                var readable = false
                val pending = ArrayDeque<Long>()
                val tolerance = maxOf(25_000L, minOf(100_000L, expected.lastDurationUs * 2))
                val progress = RecordingDecodeProgress(System.nanoTime(), NATIVE_NO_PROGRESS_NANOS)
                val info = MediaCodec.BufferInfo()
                while (!readable) {
                    checkpoint(); progress.check(System.nanoTime())
                    if (!inputEos) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            checkpoint()
                            val buffer = codec.getInputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                            val time = extractor.sampleTime
                            buffer.clear()
                            if (time < 0) {
                                if (extractor.sampleTrackIndex != -1 || extractor.sampleSize != -1L ||
                                    extractor.readSampleData(buffer, 0) != -1) throw SaveFailure(SaveError.INVALID_MEDIA)
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEos = true
                            } else {
                                if (extractor.sampleTrackIndex != track || time <= lastInput || time !in expected.firstPtsUs..expected.lastPtsUs)
                                    throw SaveFailure(SaveError.INVALID_MEDIA)
                                checkRecordingSampleFlags(extractor.sampleFlags)
                                val size = extractor.sampleSize
                                if (size !in 1..MAX_SAMPLE_BYTES.toLong() || size > buffer.capacity() || ++queued > 64)
                                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                                val count = extractor.readSampleData(buffer, 0)
                                if (count.toLong() != size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                                windowBytes += count; totalBytes += count
                                if (windowBytes > 1024 * 1024 || totalBytes > 4L * 1024 * 1024)
                                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                                pending.addLast(time); lastInput = time
                                codec.queueInputBuffer(index, 0, count, time, 0); extractor.advance()
                            }
                            progress.madeProgress(System.nanoTime())
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> checkFormat(codec.outputFormat)
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> {
                            if (index < 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                            try {
                                checkpoint()
                                if (info.offset < 0 || info.size < 0 || info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0)
                                    throw SaveFailure(SaveError.INVALID_MEDIA)
                                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (info.size > 0 && !config) {
                                    checkFormat(codec.getOutputFormat(index))
                                    val buffer = codec.getOutputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                                    if (info.offset.toLong() + info.size > buffer.capacity() || pending.isEmpty() ||
                                        info.presentationTimeUs < 0 || info.presentationTimeUs <= lastOutput ||
                                        abs(info.presentationTimeUs - pending.removeFirst()) > 1) throw SaveFailure(SaveError.INVALID_MEDIA)
                                    lastOutput = info.presentationTimeUs
                                    readable = abs(lastOutput - target) <= tolerance
                                    if (!readable && lastOutput > target + tolerance) throw SaveFailure(SaveError.INVALID_MEDIA)
                                    progress.madeProgress(System.nanoTime())
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && (config || !inputEos || !readable))
                                    throw SaveFailure(SaveError.INVALID_MEDIA)
                            } finally { codec.releaseOutputBuffer(index, false) }
                        }
                    }
                }
            }
            checkpoint()
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } catch (_: Exception) {
                    releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                } finally { extractor.unselectTrack(track) }
            }
        }
    }

    private data class ConservedStream(
        val contract: StreamConservationContract,
        val videoFirstTimeUs: Long,
        val finalGop: List<StreamTailSample>,
    )

    /** Independent MediaExtractor pass over every output sample, with no seek-based shortcuts. */
    private fun inspectStreamSamples(extractor: MediaExtractor, videoTrack: Int, videoFormat: MediaFormat,
        audioFormats: List<Triple<Int, MediaFormat, Long>>, expected: StreamConservationContract,
        checkpoint: () -> Unit): ConservedStream {
        if (videoFormat.getString(MediaFormat.KEY_MIME) != "video/avc") throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val finalGop = ArrayList<StreamTailSample>()
        val sampleBuffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        fun scan(track: Int, sequence: StreamSampleSequence): StreamSampleAccumulator {
            val accumulator = StreamSampleAccumulator(sequence.kind, sequence.timescale, timestampsAreMuxInput = false)
            var samples = 0L
            var gopBytes = 0L
            extractor.selectTrack(track)
            try {
                checkpoint()
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleTime >= 0) {
                    checkpoint()
                    if (extractor.sampleTrackIndex != track) throw SaveFailure(SaveError.INVALID_MEDIA)
                    val flags = extractor.sampleFlags
                    checkStreamSampleFlags(flags)
                    val size = extractor.sampleSize
                    if (size !in 1..MAX_SAMPLE_BYTES.toLong()) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                    if (samples >= sequence.sampleCount) throw SaveFailure(SaveError.INVALID_MEDIA)
                    val time = extractor.sampleTime
                    sampleBuffer.clear()
                    val count = extractor.readSampleData(sampleBuffer, 0)
                    if (count.toLong() != size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                    sampleBuffer.position(0); sampleBuffer.limit(count)
                    val identity = accumulator.add(time, flags, sampleBuffer)
                    if (sequence.kind == StreamSampleKind.AVC) {
                        if (flags == MediaExtractor.SAMPLE_FLAG_SYNC) { finalGop.clear(); gopBytes = 0 }
                        gopBytes += count
                        finalGop.add(StreamTailSample(time, flags, count, identity.canonicalBytes.toLong(), identity.sha256))
                    }
                    samples++
                    extractor.advance()
                }
                checkpoint()
                sampleBuffer.clear()
                if (extractor.sampleTrackIndex != -1 || extractor.sampleSize != -1L ||
                    extractor.readSampleData(sampleBuffer, 0) != -1 || samples != sequence.sampleCount)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                if (accumulator.finish() != sequence) throw SaveFailure(SaveError.INVALID_MEDIA)
                if (sequence.kind == StreamSampleKind.AVC && gopBytes > MAX_TAIL_BYTES)
                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            } finally { extractor.unselectTrack(track) }
            return accumulator
        }
        val video = scan(videoTrack, expected.video)
        val audio = expected.audio?.let { sequence ->
            val (track, format) = audioFormats.single()
            if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm" ||
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sequence.timescale ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != expected.audioChannelCount)
                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            scan(track, sequence)
        }
        val actual = StreamConservationContract(video.finish(), audio?.finish(),
            audio?.let { it.firstPresentationTimeUs - video.firstPresentationTimeUs }, video.videoTail(), video.endpoint(),
            audioChannelCount = audio?.let { audioFormats.single().second.getInteger(MediaFormat.KEY_CHANNEL_COUNT) })
        // Positive leading edit durations use Media3's 10 kHz movie clock, HALF_UP. There is no
        // accumulating per-segment tolerance. Track-relative ticks are already compared exactly.
        val expectedVideoOrigin = maxOf(0L, -(expected.audioStartOffsetUs ?: 0L))
        if (abs(video.firstPresentationTimeUs - expectedVideoOrigin) > 51 || !expected.matches(actual))
            throw SaveFailure(SaveError.INVALID_MEDIA)
        if (finalGop.size.toLong() != expected.video.sampleCount - expected.videoTail.precedingSync.index)
            throw SaveFailure(SaveError.INVALID_MEDIA)
        return ConservedStream(actual, video.firstPresentationTimeUs, finalGop.toList())
    }

    private fun checkStreamSampleFlags(flags: Int) {
        if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw SaveFailure(SaveError.PROTECTED_MEDIA)
        if (flags != 0 && flags != MediaExtractor.SAMPLE_FLAG_SYNC) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
    }

    /** Seek the proven final GOP, verify every queued identity again, and drain actual output EOS. */
    private fun decodeVideoTail(extractor: MediaExtractor, track: Int, format: MediaFormat,
        conserved: ConservedStream, checkpoint: () -> Unit): StreamTailProof {
        val state = StreamVideoTailState(conserved.finalGop, checkpoint)
        var decoder: MediaCodec? = null
        var started = false
        var terminalOutputTimeUs = -1L
        extractor.selectTrack(track)
        try {
            checkpoint()
            val codec = MediaCodec.createDecoderByType("video/avc").also { decoder = it }
            codec.configure(format, null, null, 0); codec.start(); started = true
            val sync = conserved.finalGop.first()
            extractor.seekTo(sync.timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // PREVIOUS_SYNC may not silently substitute some earlier GOP.
            if (extractor.sampleTrackIndex != track || extractor.sampleTime != sync.timeUs ||
                extractor.sampleFlags != MediaExtractor.SAMPLE_FLAG_SYNC) throw SaveFailure(SaveError.INVALID_MEDIA)
            var inputEnded = false
            var queued = 0
            var queuedBytes = 0L
            val outputFormat = StreamVideoFormatState(format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT))
            val progress = StreamDecodeProgress(System.nanoTime(), NATIVE_NO_PROGRESS_NANOS)
            val info = MediaCodec.BufferInfo()
            while (!state.complete) {
                checkpoint(); progress.check(System.nanoTime())
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        checkpoint()
                        val input = codec.getInputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                        if (extractor.sampleTime < 0) {
                            if (extractor.sampleTrackIndex != -1 || extractor.sampleSize != -1L || queued != conserved.finalGop.size)
                                throw SaveFailure(SaveError.INVALID_MEDIA)
                            input.clear()
                            if (extractor.readSampleData(input, 0) != -1) throw SaveFailure(SaveError.INVALID_MEDIA)
                            state.inputEndOfStream()
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (extractor.sampleTrackIndex != track || queued >= conserved.finalGop.size)
                                throw SaveFailure(SaveError.INVALID_MEDIA)
                            val flags = extractor.sampleFlags
                            checkStreamSampleFlags(flags)
                            val time = extractor.sampleTime
                            val size = extractor.sampleSize
                            if (size !in 1..MAX_SAMPLE_BYTES.toLong() || size > input.capacity())
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            input.clear()
                            val count = extractor.readSampleData(input, 0)
                            if (count.toLong() != size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                            input.position(0); input.limit(count)
                            val bytes = ByteArray(count).also { input.duplicate().get(it) }
                            val canonical = canonicalAvcSample(bytes)
                            val hash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
                            state.input(StreamTailSample(time, flags, count, canonical.size.toLong(), hash))
                            queuedBytes += count
                            if (queuedBytes > MAX_TAIL_BYTES) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            codec.queueInputBuffer(index, 0, count, time, 0)
                            queued++; extractor.advance()
                        }
                        progress.madeProgress(System.nanoTime())
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat.accept(videoOutputFormat(codec.outputFormat), changeEvent = true)
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> {
                        try {
                            checkpoint()
                            outputFormat.accept(videoOutputFormat(codec.getOutputFormat(index)), changeEvent = false)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM or
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0 || info.offset < 0 || info.size < 0)
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                                if (info.offset.toLong() + info.size > buffer.capacity()) throw SaveFailure(SaveError.INVALID_MEDIA)
                            }
                            if (state.output(info.presentationTimeUs, info.size, config, eos)) progress.madeProgress(System.nanoTime())
                            if (info.size > 0 && !config) terminalOutputTimeUs = info.presentationTimeUs
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                    else -> throw SaveFailure(SaveError.INVALID_MEDIA)
                }
            }
            checkpoint()
            val tail = conserved.contract.videoTail
            return StreamTailProof(tail.precedingSync, tail.lastSample,
                terminalOutputTimeUs - conserved.videoFirstTimeUs, outputEos = true).also {
                if (!it.matches(conserved.contract)) throw SaveFailure(SaveError.INVALID_MEDIA)
            }
        } finally {
            try { if (started) decoder?.stop() } finally {
                try {
                    try { decoder?.release() } catch (_: Exception) {
                        releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    }
                } finally { extractor.unselectTrack(track) }
            }
        }
    }

    private fun videoOutputFormat(actual: MediaFormat): StreamDecodedVideoFormat {
        fun value(key: String, fallback: Int = -1) = if (actual.containsKey(key)) actual.getInteger(key) else fallback
        val width = value(MediaFormat.KEY_WIDTH)
        val height = value(MediaFormat.KEY_HEIGHT)
        return StreamDecodedVideoFormat(actual.getString(MediaFormat.KEY_MIME).orEmpty(), width, height,
            value("crop-left", 0), value("crop-right", width - 1), value("crop-top", 0),
            value("crop-bottom", height - 1), value(MediaFormat.KEY_COLOR_FORMAT))
    }

    /** Decode output timestamps, not just metadata or a nearby MMR sync-frame, prove each range. */
    private fun decodePositions(extractor: MediaExtractor, track: Int, format: MediaFormat,
        duration: Long, checkpoint: () -> Unit): Set<DecodePosition> {
        var decoderToRelease: MediaCodec? = null
        var started = false
        extractor.selectTrack(track)
        try {
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { decoderToRelease = it }
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            val proven = linkedSetOf<DecodePosition>()
            for ((position, target) in DecodePosition.entries.zip(sampleTimes(duration).asIterable())) {
                checkpoint()
                if (proven.isNotEmpty()) decoder.flush()
                extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val tolerance = minOf(1_000_000L, maxOf(100_000L, duration / 10))
                var queued = 0
                var queuedBytes = 0L
                var inputEnded = false
                var found = false
                val info = MediaCodec.BufferInfo()
                while (!found) {
                    checkpoint()
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                            val timestamp = extractor.sampleTime
                            if (timestamp < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                if (++queued > 4096 || extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                                    throw SaveFailure(if (queued > 4096) SaveError.UNSUPPORTED_MEDIA else SaveError.PROTECTED_MEDIA)
                                val sampleSize = extractor.sampleSize
                                if (sampleSize !in 1..MAX_SAMPLE_BYTES.toLong() || sampleSize > input.capacity()) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                                input.clear()
                                val count = extractor.readSampleData(input, 0)
                                if (count <= 0 || count.toLong() != sampleSize) throw SaveFailure(SaveError.INVALID_MEDIA)
                                queuedBytes += count
                                if (queuedBytes > 64L * 1024 * 1024) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                                decoder.queueInputBuffer(inputIndex, 0, count, timestamp, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                info.presentationTimeUs >= 0 && abs(info.presentationTimeUs - target) <= tolerance) {
                                checkpoint(); proven.add(position); found = true
                            }
                            if (!found && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                            if (!found && info.presentationTimeUs > target + tolerance) throw SaveFailure(SaveError.INVALID_MEDIA)
                        } finally { decoder.releaseOutputBuffer(outputIndex, false) }
                    }
                }
            }
            return proven
        } finally {
            try { if (started) decoderToRelease?.stop() } finally {
                try {
                    try { decoderToRelease?.release() } catch (_: Exception) { releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                } finally { extractor.unselectTrack(track) }
            }
        }
    }
    /** Stream AAC is fully decoded under the original bounded native lane/deadline, not only sampled.
     * Implicit SBR/PS or a late format switch may not appear in ADTS headers; reject decoder-output
     * rate/channel differences from the inspected encoded track throughout the complete audio track.
     */
    private fun decodeStreamAudio(extractor: MediaExtractor, track: Int, format: MediaFormat,
        durationUs: Long, checkpoint: () -> Unit) {
        if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val expectedRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val expectedChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var decoder: MediaCodec? = null
        var started = false
        extractor.selectTrack(track)
        try {
            val codec = MediaCodec.createDecoderByType("audio/mp4a-latm").also { decoder = it }
            codec.configure(format, null, null, 0); codec.start(); started = true
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var endedInput = false
            var decoded = 0L
            var firstTime = -1L
            var lastTime = -1L
            var queuedBytes = 0L
            var samples = 0L
            val info = MediaCodec.BufferInfo()
            fun checkFormat(actual: MediaFormat) {
                val rate = if (actual.containsKey(MediaFormat.KEY_SAMPLE_RATE)) actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
                val channels = if (actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                if (!StreamAudioFormatPolicy.compatible(expectedRate, expectedChannels, rate, channels))
                    throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            }
            while (true) {
                checkpoint()
                if (!endedInput) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                        val time = extractor.sampleTime
                        if (time < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); endedInput = true
                        } else {
                            if (extractor.sampleTrackIndex != track || extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                                throw SaveFailure(SaveError.PROTECTED_MEDIA)
                            val size = extractor.sampleSize
                            if (size !in 1..MAX_SAMPLE_BYTES.toLong() || size > input.capacity() || ++samples > 5_000_000)
                                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
                            input.clear()
                            val count = extractor.readSampleData(input, 0)
                            if (count.toLong() != size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                            queuedBytes += count
                            if (queuedBytes > 2L * 1024 * 1024 * 1024) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                            codec.queueInputBuffer(index, 0, count, time, 0); extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) checkFormat(codec.outputFormat)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            checkFormat(codec.getOutputFormat(index))
                            if (info.presentationTimeUs < 0 || info.presentationTimeUs < lastTime) throw SaveFailure(SaveError.INVALID_MEDIA)
                            if (firstTime < 0) firstTime = info.presentationTimeUs
                            lastTime = info.presentationTimeUs; decoded++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            if (decoded < 2 || firstTime !in 0..1_000_000 || lastTime <= firstTime ||
                abs(durationUs - lastTime) > 1_000_000) throw SaveFailure(SaveError.INVALID_MEDIA)
        } finally {
            try { if (started) decoder?.stop() } finally {
                try {
                    try { decoder?.release() } catch (_: Exception) { releaseUnconfirmed.set(true); throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                } finally { extractor.unselectTrack(track) }
            }
        }
    }
    private fun sampleTimes(duration: Long) = longArrayOf(0, duration / 2, maxOf(0, duration - minOf(1_000_000, duration / 10)))
    companion object {
        private val draining = AtomicInteger(0)
        private val releaseUnconfirmed = AtomicBoolean(false)
        val requiresRestart: Boolean get() = releaseUnconfirmed.get()
        val isDraining: Boolean get() = draining.get() > 0 || releaseUnconfirmed.get()
        private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
        // Initial compatibility gate: final-GOP decoder work is bounded to 64 MiB compressed
        // input, in addition to the 250,000-sample, 4 MiB/sample and absolute deadline limits.
        private const val MAX_TAIL_BYTES = 64L * 1024 * 1024
        private const val NATIVE_NO_PROGRESS_NANOS = 5_000_000_000L
        private val nativeLane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
            Thread(runnable, "esv-local-media-check").apply { isDaemon = true }
        }.apply { rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy() }
        private fun checkActive(context: OperationContext) {
            if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
        }
    }
}

/** Equality is deliberate: implicit SBR/PS upsampling or channel expansion is outside this slice. */
internal object StreamAudioFormatPolicy {
    fun compatible(encodedRate: Int, encodedChannels: Int, decodedRate: Int, decodedChannels: Int) =
        encodedRate in 8_000..96_000 && encodedChannels in 1..2 && encodedRate == decodedRate && encodedChannels == decodedChannels
}
