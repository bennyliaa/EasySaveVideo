package com.easysavevideo.app.save.normalization

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.AnnexBToAvccConverter
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import com.easysavevideo.save.*
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class MovRemuxEvidence(
    val rawSizeBytes: Long, val rawSha256: String,
    val outputSizeBytes: Long, val outputSha256: String,
    val conservation: DirectRemuxConservation,
)

/**
 * Synchronous, local-only, remux-only engine. No URL, Transformer, codec, thread or task scheduling.
 * Not connected to production MOV admission. The outer Direct local pipeline/claim must remain
 * held through adoption, independent native verification, publication outcome and cleanup.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MovRemuxEngine(private val clock: EpochClock) {
    private val occupied = AtomicBoolean()
    private val releaseUnconfirmed = AtomicBoolean()
    fun requiresProcessRecovery(): Boolean = releaseUnconfirmed.get()

    fun remux(files: MovRemuxFiles, context: OperationContext, limits: SaveLimits): MovRemuxEvidence {
        checkpoint(context)
        val operationBytes = context.resourceBudget.maxBytes ?: throw SaveFailure(SaveError.INVALID_REQUEST)
        if (context.resourceBudget.maxConcurrentWork != 1 || operationBytes <= 0) throw SaveFailure(SaveError.INVALID_REQUEST)
        val maximum = minOf(limits.maxArtifactBytes, operationBytes, DirectNormalizationLimits.MAX_BYTES)
        if (!occupied.compareAndSet(false, true)) throw SaveFailure(SaveError.BUSY)
        var source: MovRemuxInput? = null
        var sink: MovRemuxOutput? = null
        var output: MovRemuxInput? = null
        var extractor: Mp4Extractor? = null
        var muxer: Mp4Muxer? = null
        var muxCloseAttempted = false
        var scanningOutput = false
        fun check() = checkpoint(context)
        try {
            check(); Log.setLogLevel(Log.LOG_LEVEL_OFF)
            source = files.openSource()
            requireExtent(source, maximum)
            val outputMaximum = minOf(maximum, operationBytes - source.length)
            if (outputMaximum < 32) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            val rawHash = hash(source, ::check)
            val profile = MovProfileProbe.probeQuickTime(source, ::check)
            if (profile.conservation.audio != null) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            if (files.availableBytes() < limits.maxArtifactBytes) throw SaveFailure(SaveError.STORAGE_FULL)
            check()
            sink = files.openEmptyOutput()
            val bounded = BoundedOutput(sink, outputMaximum, files::availableBytes, ::check)
            if (bounded.size != 0L || bounded.position != 0L) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            muxer = Mp4Muxer.Builder(bounded).setSampleBatchingEnabled(false)
                .setLastSampleDurationBehavior(Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
                .build()
            extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
            val callback = OneVideoOutput(source, profile, muxer, ::check)
            extract(extractor, source, profile, callback, ::check)
            callback.finish()
            extractor.release(); extractor = null
            if (hash(source, ::check) != rawHash) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            muxCloseAttempted = true
            muxer.close(); muxer = null
            // Even a normal mux close must positively close the separately owned sink.
            closeOutput(sink)
            check()
            scanningOutput = true
            output = files.openFinalizedOutput()
            requireExtent(output, outputMaximum)
            val outputHash = hash(output, ::check)
            val observed = MovProfileProbe.scanCanonicalMp4(output, ::check)
            if (observed.conservation != profile.conservation) throw SaveFailure(SaveError.INVALID_MEDIA)
            // Independently compare finalized raw tables/payload with real extractor callbacks too.
            extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
            val outputCallback = OneVideoOutput(output, observed, null, ::check)
            extract(extractor, output, observed, outputCallback, ::check)
            outputCallback.finish()
            extractor.release(); extractor = null
            if (hash(output, ::check) != outputHash) throw SaveFailure(SaveError.INVALID_MEDIA)
            check()
            return MovRemuxEvidence(source.length, rawHash, output.length, outputHash, observed.conservation)
        } catch (failure: SaveFailure) {
            throw failure
        } catch (failure: MovProfileException) {
            throw SaveFailure(if (failure.failure == MovProfileFailure.PROTECTED_MEDIA) SaveError.PROTECTED_MEDIA
                else if (scanningOutput) SaveError.INVALID_MEDIA else SaveError.UNSUPPORTED_MEDIA)
        } catch (_: IOException) {
            throw SaveFailure(SaveError.STORAGE_FAILURE)
        } catch (failure: Exception) {
            // Pinned muxer wraps sink failures. Preserve typed cancellation/budget errors without
            // inspecting or exposing potentially sensitive exception messages.
            var cause: Throwable? = failure
            val seen = java.util.IdentityHashMap<Throwable, Boolean>()
            repeat(8) {
                val current = cause ?: return@repeat
                if (seen.put(current, true) != null) { cause = null; return@repeat }
                if (current is SaveFailure) throw current
                if (current is IOException) throw SaveFailure(SaveError.STORAGE_FAILURE)
                cause = current.cause
            }
            throw SaveFailure(if (scanningOutput) SaveError.INVALID_MEDIA else SaveError.UNSUPPORTED_MEDIA)
        } finally {
            // Mp4Muxer.close may throw unchecked before closing its sink. Never rely on it alone.
            try {
                if (muxer != null && !muxCloseAttempted) try { muxer.close() } catch (_: Exception) { /* Never publish failed work. */ }
                try { extractor?.release() } catch (_: Exception) { releaseUnconfirmed.set(true) }
                closeInput(output); closeOutput(sink); closeInput(source)
            } finally { occupied.set(false) }
            if (releaseUnconfirmed.get()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
    }

    private fun extract(extractor: Mp4Extractor, source: MovProbeInput, profile: MovProfile,
        callback: OneVideoOutput, checkpoint: () -> Unit) {
        val reader = Reader(source, checkpoint)
        if (!extractor.sniff(DefaultExtractorInput(reader, 0, source.length))) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        reader.position = 0
        var input = DefaultExtractorInput(reader, 0, source.length)
        extractor.init(callback)
        val seek = PositionHolder()
        var steps = 0L
        while (true) {
            checkpoint()
            if (++steps > profile.samples.size.toLong() * 64 + 65_536) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            when (extractor.read(input, seek)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    if (seek.position !in 0..source.length) throw SaveFailure(SaveError.INVALID_MEDIA)
                    reader.position = seek.position
                    input = DefaultExtractorInput(reader, seek.position, source.length)
                }
                Extractor.RESULT_END_OF_INPUT -> return
                else -> throw SaveFailure(SaveError.INVALID_MEDIA)
            }
        }
    }

    private fun closeInput(input: MovRemuxInput?) {
        if (input == null) return
        try { input.close() } catch (_: Exception) { releaseUnconfirmed.set(true) }
        if (!runCatching { input.releaseConfirmed }.getOrDefault(false)) releaseUnconfirmed.set(true)
    }
    private fun closeOutput(output: MovRemuxOutput?) {
        if (output == null) return
        try { output.close() } catch (_: Exception) { releaseUnconfirmed.set(true) }
        if (!runCatching { output.releaseConfirmed }.getOrDefault(false)) releaseUnconfirmed.set(true)
    }
    private fun checkpoint(context: OperationContext) {
        if (releaseUnconfirmed.get()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        val error = when (context.stopReason(clock.nowMillis())) {
            OperationStopReason.CANCELLED -> SaveError.CANCELLED
            OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
            OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
            null -> null
        }
        if (error != null) throw SaveFailure(error)
        if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
    }
    private fun requireExtent(input: MovRemuxInput, maximum: Long) {
        input.validateIdentity()
        if (input.length > maximum) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
        if (input.length < 32) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
    }
    private fun hash(input: MovRemuxInput, checkpoint: () -> Unit): String {
        input.validateIdentity()
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        var offset = 0L
        while (offset < input.length) {
            checkpoint(); val count = minOf(buffer.size.toLong(), input.length - offset).toInt()
            input.readAt(offset, buffer, 0, count); digest.update(buffer, 0, count); offset += count
        }
        input.validateIdentity(); checkpoint()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Reader(private val source: MovProbeInput, private val checkpoint: () -> Unit) : DataReader {
        var position = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkpoint()
            if (length < 0 || offset < 0 || offset > buffer.size - length || position !in 0..source.length) throw SaveFailure(SaveError.INVALID_MEDIA)
            if (length == 0) return 0
            if (position == source.length) return -1
            val count = minOf(length.toLong(), source.length - position).toInt()
            source.readAt(position, buffer, offset, count); position += count
            return count
        }
    }

    internal class OneVideoOutput(
        private val source: MovProbeInput, private val profile: MovProfile,
        private val muxer: Mp4Muxer?, private val checkpoint: () -> Unit,
    ) : ExtractorOutput, TrackOutput {
        private var trackId: Int? = null
        private var muxTrack: Int? = null
        private var tracksEnded = false
        private var pending = ByteArray(64 * 1024)
        private var pendingSize = 0
        private var sampleIndex = 0
        private val proof = DirectMovSampleAccumulator(profile.clock, profile.configuration.sha256, checkpoint)
        private val sequence = DirectMovWriteSequence(profile.clock)
        override fun track(id: Int, type: Int): TrackOutput {
            checkpoint()
            if (tracksEnded || type != C.TRACK_TYPE_VIDEO || (trackId != null && trackId != id)) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            trackId = id; return this
        }
        override fun endTracks() { if (trackId == null || tracksEnded) throw SaveFailure(SaveError.INVALID_MEDIA); tracksEnded = true }
        override fun seekMap(seekMap: SeekMap) { checkpoint() /* Coarse duration is never endpoint evidence. */ }
        override fun format(format: Format) {
            checkpoint()
            val color = format.colorInfo
            if (muxTrack != null || format.sampleMimeType != MimeTypes.VIDEO_H264 || format.width != profile.width ||
                format.height != profile.height || format.rotationDegrees != 0 || format.pixelWidthHeightRatio != 1f ||
                format.drmInitData != null || color == null || color.colorSpace != C.COLOR_SPACE_BT709 ||
                color.colorRange != C.COLOR_RANGE_LIMITED || color.colorTransfer != C.COLOR_TRANSFER_SDR || color.hdrStaticInfo != null)
                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            val config = DirectMovCanonical.avcConfigurationFromInitializationData(format.initializationData, checkpoint)
            if (config.sha256 != profile.configuration.sha256) throw SaveFailure(SaveError.INVALID_MEDIA)
            val approved = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(profile.width).setHeight(profile.height)
                .setFrameRate(profile.frameRate.toFloat()).setRotationDegrees(0).setPixelWidthHeightRatio(1f)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                .setInitializationData(listOf(prefix(profile.configuration.sps), prefix(profile.configuration.pps))).build()
            muxTrack = muxer?.addTrack(approved) ?: 0
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            checkpoint(); part(sampleDataPart)
            if (length <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
            val wanted = minOf(length, 64 * 1024); reserve(wanted)
            val count = input.read(pending, pendingSize, wanted)
            if (count < 0) {
                if (!allowEndOfInput) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                return -1
            }
            if (count !in 1..wanted) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            pendingSize += count; return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            checkpoint(); part(sampleDataPart)
            if (length <= 0 || length > data.bytesLeft()) throw SaveFailure(SaveError.INVALID_MEDIA)
            reserve(length); data.readBytes(pending, pendingSize, length); pendingSize += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            checkpoint()
            if (cryptoData != null) throw SaveFailure(SaveError.PROTECTED_MEDIA)
            if (muxTrack == null || sampleIndex >= profile.samples.size || size !in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES ||
                offset !in 0..DirectNormalizationLimits.MAX_SAMPLE_BYTES || pendingSize.toLong() - offset - size != 0L)
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val sample = profile.samples[sampleIndex]
            val sync = DirectMovCallbackFlags.sync(flags, sampleIndex.toLong(), profile.clock, if (sample.sync) 1 else 0)
            profile.clock.requirePresentationTime(sampleIndex.toLong(), timeUs)
            val annex = pending.copyOfRange(0, size)
            val canonical = DirectMovCanonical.avcSample(annex, DirectMovNalFraming.ANNEX_B, checkpoint)
            if (!canonical.contentEquals(MovProfileProbe.readCanonicalSample(source, profile, sampleIndex, checkpoint)))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val actualConverted = AnnexBToAvccConverter.DEFAULT.process(ByteBuffer.wrap(annex.clone()))
            if (actualConverted.remaining() != canonical.size || !canonical.contentEquals(ByteArray(actualConverted.remaining()).also(actualConverted::get)))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            proof.append(canonical, timeUs, sync)
            sequence.media(timeUs, size, sync)
            muxer?.writeSampleData(requireNotNull(muxTrack), ByteBuffer.wrap(annex), BufferInfo(timeUs, size, sync))
            pending.copyInto(pending, 0, size, pendingSize); pendingSize = offset; sampleIndex++
        }
        fun finish() {
            checkpoint()
            if (!tracksEnded || muxTrack == null || pendingSize != 0 || sampleIndex != profile.samples.size) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            val accumulated = proof.finish()
            if (accumulated.track != profile.conservation.video || accumulated.precedingVideoSync != profile.conservation.precedingVideoSync)
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val endpoint = profile.clock.endpoint.minimumUs
            sequence.endOfStream(endpoint); muxer?.writeSampleData(requireNotNull(muxTrack), ByteBuffer.allocate(0), BufferInfo(endpoint, 0, C.BUFFER_FLAG_END_OF_STREAM))
            sequence.finish()
        }
        private fun reserve(extra: Int) {
            if (extra < 0 || extra > 2 * DirectNormalizationLimits.MAX_SAMPLE_BYTES - pendingSize) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            if (pending.size < pendingSize + extra) pending = pending.copyOf(minOf(2 * DirectNormalizationLimits.MAX_SAMPLE_BYTES, maxOf(pending.size * 2, pendingSize + extra)))
        }
        private fun part(part: Int) { if (part != TrackOutput.SAMPLE_DATA_PART_MAIN) throw SaveFailure(SaveError.PROTECTED_MEDIA) }
        private fun prefix(bytes: ByteArray) = byteArrayOf(0, 0, 0, 1) + bytes
    }

    private class BoundedOutput(
        private val delegate: MovRemuxOutput, private val maximum: Long,
        private val available: () -> Long, private val checkpoint: () -> Unit,
    ) : SeekableMuxerOutput {
        override fun isOpen() = delegate.isOpen
        override fun close() = delegate.close()
        override fun getPosition(): Long { checkpoint(); return delegate.position.also(::extent) }
        override fun setPosition(position: Long) { checkpoint(); extent(position); delegate.setPosition(position) }
        override fun getSize(): Long { checkpoint(); return delegate.size.also(::extent) }
        override fun truncate(size: Long) { checkpoint(); extent(size); delegate.truncate(size) }
        override fun write(source: ByteBuffer): Int {
            checkpoint(); val position = position; val requested = source.remaining()
            if (requested.toLong() > maximum - position) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            if (available() < requested.toLong()) throw SaveFailure(SaveError.STORAGE_FULL)
            val before = source.position(); val written = delegate.write(source)
            if (written < 0 || written > requested || source.position() - before != written || (requested > 0 && written == 0)) throw SaveFailure(SaveError.STORAGE_FAILURE)
            checkpoint(); extent(delegate.size); return written
        }
        private fun extent(value: Long) { if (value !in 0..maximum) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED) }
    }
}
