package com.easysavevideo.app.save

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.system.Os
import com.easysavevideo.app.save.normalization.MovProfile
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import java.nio.ByteBuffer
import kotlin.math.abs

/** Native implementation only. It runs inside AndroidLocalMp4Verifier's existing single lane. */
internal class DirectNormalizedAndroidVerifier(
    private val owner: DirectNormalizedOutputOwner,
    private val markReleaseUnconfirmed: () -> Unit,
) {
    fun inspect(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext, checkpoint: () -> Unit): VerificationEvidence {
        checkpoint()
        DirectNormalizedPreflight.check(artifact, output, context)
        if (owner.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        // Acquisition happens only after NativeVerificationLease.start(), never on the caller lane.
        val lease = DirectNormalizedPreflight.acquire(owner, artifact, output, context, markReleaseUnconfirmed)
        var primary: Throwable? = null
        try {
            DirectNormalizedPreflight.checkLease(lease, artifact, output)
            val input = lease.readOnlyInput
            val descriptor = directLocalIo { input.fd }
            fun identity(): DirectFileIdentity = try {
                val stat = Os.fstat(descriptor)
                DirectFileIdentity(stat.st_dev, stat.st_ino, stat.st_size)
            } catch (_: android.system.ErrnoException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
            val captured = identity()
            if (captured.size != output.sizeBytes) throw SaveFailure(SaveError.RESOURCE_CHANGED)
            val fixed = FixedDirectOutputInput(input, captured, ::identity, checkpoint)
            val before = fixed.requireHash(output.sha256)
            if (!directLocalIo { Mp4ContainerProbe.isCompleteMp4(input) { fixed.checkIdentity() } })
                throw SaveFailure(SaveError.INVALID_MEDIA)
            fixed.checkIdentity()
            val profile = directRawScan { MovProfileProbe.scanCanonicalMp4(fixed, checkpoint) }
            if (profile.conservation != output.normalizationProof.conservation) throw SaveFailure(SaveError.INVALID_MEDIA)
            var extractor: MediaExtractor? = null
            var retriever: MediaMetadataRetriever? = null
            var nativePrimary: Throwable? = null
            val nativeResult: NativeResult
            try {
                val selected = directNativeCall { MediaExtractor() }.also { extractor = it }
                directNativeCall { selected.setDataSource(descriptor, 0, captured.size) }
                checkpoint(); fixed.checkIdentity()
                val trackCount = directNativeCall { selected.trackCount }
                if (trackCount != 1) throw SaveFailure(SaveError.INVALID_MEDIA)
                val format = directNativeCall { selected.getTrackFormat(0) }
                val facts = directNativeCall { nativeTrackFormat(selected, format) }
                val configuration = inspectDirectFormat(facts, profile, checkpoint)
                val source = AndroidDirectSampleSource(selected, fixed, checkpoint)
                directNativeCall { selected.selectTrack(0) }
                val samples: DirectInspectedSamples
                var samplePrimary: Throwable? = null
                try { samples = inspectDirectSamples(source, 0, fixed, profile, configuration, checkpoint) }
                catch (failure: Throwable) { samplePrimary = failure; throw failure }
                finally { finishDirectCleanup(samplePrimary, {}, releases = emptyList(), after = { selected.unselectTrack(0) }) }
                val positions = decodePositions(selected, source, format, profile, checkpoint, lease)
                val tail = decodeTail(selected, source, format, samples, checkpoint, lease)
                if (!tail.matches(samples.conservation) || !tail.matches(output.normalizationProof.conservation))
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                val metadata = directNativeCall { MediaMetadataRetriever() }.also { retriever = it }
                directNativeCall { metadata.setDataSource(descriptor, 0, captured.size) }
                checkpoint(); fixed.checkIdentity()
                val mime = directNativeCall { metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) }
                if (mime != "video/mp4" && mime != "application/mp4") throw SaveFailure(SaveError.INVALID_MEDIA)
                val durationMillis = profile.clock.endpoint.minimumUs / 1000
                val metadataMillis = directNativeCall {
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                if (metadataMillis <= 0 || abs(metadataMillis - durationMillis) > 1)
                    throw SaveFailure(SaveError.INVALID_MEDIA)
                for (time in directSampleTimes(profile.clock.endpoint.minimumUs)) {
                    checkpoint(); fixed.checkIdentity()
                    val frame = directNativeCall {
                        metadata.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                    } ?: throw SaveFailure(SaveError.INVALID_MEDIA)
                    try {
                        if (frame.width <= 0 || frame.height <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
                        checkpoint()
                    } finally { directNativeCall { frame.recycle() } }
                }
                nativeResult = NativeResult(samples.conservation, tail, positions, durationMillis)
            } catch (failure: Throwable) { nativePrimary = failure; throw failure }
            finally {
                finishDirectCleanup(nativePrimary, { latch(lease) }, releases = listOf(
                    { retriever?.release() }, { extractor?.release() }))
            }
            checkpoint()
            // All native observations, then real native release, then the same descriptor's hash.
            fixed.requireHash(before)
            fixed.checkIdentity()
            if (lease.readOnlyInput !== input || input.fd !== descriptor) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            DirectNormalizedPreflight.checkLease(lease, artifact, output)
            checkpoint()
            val proof = output.normalizationProof
            val verification = DirectNormalizationVerificationProof(proof.normalizationId, proof.taskId,
                proof.sourceIdentity, artifact.artifactId, artifact.commitId, proof.digest(),
                nativeResult.conservation, nativeResult.tail)
            if (!verification.matches(proof)) throw SaveFailure(SaveError.INVALID_MEDIA)
            // The owning caller must still pass its real expectedAudio to typed isValidFor.
            return VerificationEvidence(artifact.artifactId, captured.size, before, true,
                1, 0, nativeResult.durationMillis, profile.width, profile.height, false,
                nativeResult.positions, true, directNormalizationProof = verification)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            finishDirectCleanup(primary, markReleaseUnconfirmed, releases = listOf({
                lease.close()
                if (!lease.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }))
        }
    }

    private fun latch(lease: DirectNormalizedOutputLease) {
        markReleaseUnconfirmed()
        lease.retainOnNativeReleaseUncertainty()
    }

    private data class NativeResult(val conservation: DirectRemuxConservation, val tail: DirectVideoTailProof,
        val positions: Set<DecodePosition>, val durationMillis: Long)

    private fun decodeTail(extractor: MediaExtractor, source: DirectNativeSampleSource, format: MediaFormat,
        samples: DirectInspectedSamples, checkpoint: () -> Unit, lease: DirectNormalizedOutputLease): DirectVideoTailProof {
        val plan = samples.tail
        val state = DirectVideoTailState(plan, checkpoint)
        return withDecoder(extractor, format, checkpoint, lease) { codec ->
            val sync = plan.samples.first().identity
            source.seekPreviousSync(sync.presentationTimeUs)
            if (source.trackIndex != 0 || source.timeUs != sync.presentationTimeUs || source.flags != MediaExtractor.SAMPLE_FLAG_SYNC)
                throw SaveFailure(SaveError.INVALID_MEDIA)
            var inputEnded = false
            val outputFormat = DirectVideoFormatState(samples.conservation.width, samples.conservation.height, checkpoint)
            val progress = DirectDecodeProgress(System.nanoTime(), checkpoint = checkpoint)
            val info = MediaCodec.BufferInfo()
            while (!state.complete) {
                checkpoint(); progress.check(System.nanoTime())
                if (!inputEnded) {
                    val index = directNativeCall { codec.dequeueInputBuffer(10_000) }
                    if (index >= 0) {
                        checkpoint()
                        val input = directNativeCall { codec.getInputBuffer(index) }
                            ?: throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                        if (state.queuedSamples == plan.sampleCount) {
                            requireDirectEof(source, input, checkpoint)
                            directNativeCall { codec.queueInputBuffer(index, 0, 0, plan.clock.endpoint.minimumUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                            state.inputEndOfStream()
                            inputEnded = true
                        } else {
                            val expected = plan.samples[state.queuedSamples]
                            val observed = readDirectNativeSample(source, 0, expected.identity.index, plan.clock, input, checkpoint)
                            val native = DirectNativeSample(observed.identity, observed.nativeBytes)
                            state.requireNextInput(native)
                            directNativeCall { codec.queueInputBuffer(index, 0, observed.nativeBytes,
                                observed.identity.presentationTimeUs, 0) }
                            state.input(native)
                            source.advance()
                        }
                        progress.madeProgress(System.nanoTime())
                    }
                }
                val index = directNativeCall { codec.dequeueOutputBuffer(info, 10_000) }
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        outputFormat.accept(directNativeCall { directDecodedFormat(codec.outputFormat) }, changeEvent = true)
                    index == MediaCodec.INFO_TRY_AGAIN_LATER || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> withOutputBuffer(codec, index, info, outputFormat, checkpoint) { config, eos ->
                        if (state.output(info.presentationTimeUs, info.size, config, eos)) progress.madeProgress(System.nanoTime())
                    }
                    else -> throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                }
            }
            checkpoint()
            state.finish()
        }
    }

    /** Genuine existing beginning/middle/end range checks, with normalized-only strict input policy. */
    private fun decodePositions(extractor: MediaExtractor, source: DirectNativeSampleSource, format: MediaFormat,
        profile: MovProfile, checkpoint: () -> Unit, lease: DirectNormalizedOutputLease): Set<DecodePosition> =
        withDecoder(extractor, format, checkpoint, lease) { codec ->
            val proven = linkedSetOf<DecodePosition>()
            val duration = profile.clock.endpoint.minimumUs
            for ((position, target) in DecodePosition.entries.zip(directSampleTimes(duration).asIterable())) {
                checkpoint()
                if (proven.isNotEmpty()) directNativeCall { codec.flush() }
                source.seekPreviousSync(target)
                val firstIndex = profile.samples.binarySearchBy(source.timeUs) { it.presentationTimeUs }
                if (firstIndex < 0 || source.trackIndex != 0 || source.flags != MediaExtractor.SAMPLE_FLAG_SYNC ||
                    !profile.samples[firstIndex].sync) throw SaveFailure(SaveError.INVALID_MEDIA)
                val tolerance = minOf(1_000_000L, maxOf(100_000L, duration / 10))
                val outputFormat = DirectVideoFormatState(profile.width, profile.height, checkpoint)
                val progress = DirectDecodeProgress(System.nanoTime(), checkpoint = checkpoint)
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var next = firstIndex
                var queued = 0
                var queuedBytes = 0L
                var found = false
                var decoded = 0
                var lastDecoded = -1L
                while (!found) {
                    checkpoint(); progress.check(System.nanoTime())
                    if (!inputEnded) {
                        val index = directNativeCall { codec.dequeueInputBuffer(10_000) }
                        if (index >= 0) {
                            val input = directNativeCall { codec.getInputBuffer(index) }
                                ?: throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                            if (next == profile.samples.size) {
                                requireDirectEof(source, input, checkpoint)
                                directNativeCall { codec.queueInputBuffer(index, 0, 0, duration, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                                inputEnded = true
                            } else {
                                if (queued >= 4096) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                                val observed = readDirectNativeSample(source, 0, next.toLong(), profile.clock, input, checkpoint)
                                if (observed.nativeBytes > DIRECT_TAIL_BYTES - queuedBytes)
                                    throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                                directNativeCall { codec.queueInputBuffer(index, 0, observed.nativeBytes,
                                    observed.identity.presentationTimeUs, 0) }
                                queuedBytes += observed.nativeBytes; queued++; next++; source.advance()
                            }
                            progress.madeProgress(System.nanoTime())
                        }
                    }
                    val index = directNativeCall { codec.dequeueOutputBuffer(info, 10_000) }
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            outputFormat.accept(directNativeCall { directDecodedFormat(codec.outputFormat) }, true)
                        index == MediaCodec.INFO_TRY_AGAIN_LATER || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> withOutputBuffer(codec, index, info, outputFormat, checkpoint) { config, eos ->
                            if (config && eos) throw SaveFailure(SaveError.INVALID_MEDIA)
                            if (info.size > 0 && !config) {
                                if (decoded >= queued || firstIndex + decoded >= profile.samples.size ||
                                    info.presentationTimeUs <= lastDecoded || info.presentationTimeUs < 0 ||
                                    abs(info.presentationTimeUs - profile.clock.presentationTimeUs((firstIndex + decoded).toLong())) >
                                    DirectNormalizationLimits.MAX_ROUNDING_US) throw SaveFailure(SaveError.INVALID_MEDIA)
                                decoded++; lastDecoded = info.presentationTimeUs
                                progress.madeProgress(System.nanoTime())
                                if (abs(info.presentationTimeUs - target) <= tolerance) { proven.add(position); found = true }
                                if (!found && info.presentationTimeUs > target + tolerance) throw SaveFailure(SaveError.INVALID_MEDIA)
                            }
                            if (eos && (!inputEnded || !found)) throw SaveFailure(SaveError.INVALID_MEDIA)
                        }
                        else -> throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
                    }
                }
            }
            checkpoint()
            proven
        }

    private fun <T> withDecoder(extractor: MediaExtractor, format: MediaFormat, checkpoint: () -> Unit,
        lease: DirectNormalizedOutputLease, action: (MediaCodec) -> T): T {
        var decoder: MediaCodec? = null
        var started = false
        var selected = false
        var primary: Throwable? = null
        try {
            checkpoint()
            directNativeCall { extractor.selectTrack(0) }; selected = true
            val codec = directNativeCall { MediaCodec.createDecoderByType("video/avc") }.also { decoder = it }
            directNativeCall { codec.configure(format, null, null, 0) }
            directNativeCall { codec.start() }; started = true
            checkpoint()
            return action(codec)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            finishDirectCleanup(primary, { latch(lease) }, stop = { if (started) decoder?.stop() },
                releases = listOf({ decoder?.release() }), after = { if (selected) extractor.unselectTrack(0) })
        }
    }

    private fun withOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo,
        format: DirectVideoFormatState, checkpoint: () -> Unit, action: (Boolean, Boolean) -> Unit) {
        var primary: Throwable? = null
        try {
            checkpoint()
            format.accept(directNativeCall { directDecodedFormat(codec.getOutputFormat(index)) }, changeEvent = false)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM or
                    MediaCodec.BUFFER_FLAG_KEY_FRAME).inv() != 0) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
            if (info.offset < 0 || info.size < 0) throw SaveFailure(SaveError.INVALID_MEDIA)
            val buffer = directNativeCall { codec.getOutputBuffer(index) }
            if (buffer == null || info.offset.toLong() + info.size > buffer.capacity())
                throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
            action(config, eos)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // If returning an output buffer fails, eventual codec.release still owns native cleanup.
            finishDirectCleanup(primary, {}, releases = emptyList(), after = { codec.releaseOutputBuffer(index, false) })
        }
    }
}

private class AndroidDirectSampleSource(private val extractor: MediaExtractor,
    private val input: FixedDirectOutputInput, private val checkpoint: () -> Unit) : DirectNativeSampleSource {
    override val timeUs: Long get() = directNativeCall { extractor.sampleTime }
    override val trackIndex: Int get() = directNativeCall { extractor.sampleTrackIndex }
    override val size: Long get() = directNativeCall { extractor.sampleSize }
    override val flags: Int get() = directNativeCall { extractor.sampleFlags }
    override fun seekPreviousSync(timeUs: Long) {
        checkpoint(); input.checkIdentity()
        directNativeCall { extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
        input.checkIdentity()
    }
    override fun read(target: ByteBuffer): Int {
        checkpoint(); input.checkIdentity()
        val count = directNativeCall { extractor.readSampleData(target, 0) }
        input.checkIdentity()
        return count
    }
    override fun advance() { checkpoint(); directNativeCall { extractor.advance() }; input.checkIdentity() }
}

private fun nativeTrackFormat(extractor: MediaExtractor, format: MediaFormat): DirectNativeTrackFormat {
    fun optional(key: String): Int? = if (format.containsKey(key)) format.getInteger(key) else null
    val initKeys = format.keys.filter { it.startsWith("csd-") }.toSet()
    val buffers = listOf("csd-0", "csd-1").map { format.getByteBuffer(it)
        ?: throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE) }
    return DirectNativeTrackFormat(extractor.trackCount, format.getString(MediaFormat.KEY_MIME).orEmpty(),
        format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0,
        directInitializationData(buffers), initKeys != setOf("csd-0", "csd-1"), optional("rotation-degrees"),
        optional("crop-left"), optional("crop-right"), optional("crop-top"), optional("crop-bottom"),
        optional("sar-width"), optional("sar-height"), optional("color-standard"), optional("color-transfer"),
        optional("color-range"), !extractor.psshInfo.isNullOrEmpty() ||
            format.keys.any { it.startsWith("crypto-") } ||
            directExplicitEncryptionFlag(format.containsKey("is-encrypted")) { format.getInteger("is-encrypted") },
        format.containsKey("hdr-static-info") || format.containsKey("hdr10-plus-info"))
}

private fun directDecodedFormat(actual: MediaFormat): DirectDecodedVideoFormat {
    fun value(key: String, fallback: Int = -1) = if (actual.containsKey(key)) actual.getInteger(key) else fallback
    val width = value(MediaFormat.KEY_WIDTH)
    val height = value(MediaFormat.KEY_HEIGHT)
    val representation = when (value(MediaFormat.KEY_COLOR_FORMAT)) {
        19 -> DirectDecodedVideoRepresentation.YUV420_PLANAR
        21 -> DirectDecodedVideoRepresentation.YUV420_SEMIPLANAR
        0x7f420888 -> DirectDecodedVideoRepresentation.YUV420_FLEXIBLE
        else -> throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    }
    return DirectDecodedVideoFormat(actual.getString(MediaFormat.KEY_MIME).orEmpty(), width, height,
        value("crop-left", 0), value("crop-right", width - 1), value("crop-top", 0),
        value("crop-bottom", height - 1), representation)
}

private fun directSampleTimes(durationUs: Long): LongArray = longArrayOf(0, durationUs / 2,
    (durationUs - minOf(1_000_000, durationUs / 10)).coerceAtLeast(0))
