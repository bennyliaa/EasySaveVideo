package com.easysavevideo.app.media.transform

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.google.common.collect.ImmutableList
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Hard limit includes MP4 headers and final tables, not only compressed sample payloads. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerBoundedMuxer(private val spec: TransformerOutputSpec, private val target: TransformerOwnedTarget, private val session: TransformerSession) : Muxer.Factory {
    private val created = AtomicBoolean(false)
    private val tracks = LinkedHashMap<Int, Int>()
    private val lastPts = HashMap<Int, Long>()
    var videoSamples = 0L; private set
    var audioSamples = 0L; private set
    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
        C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264)
        C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
        else -> ImmutableList.of()
    }
    override fun supportsWritingNegativeTimestampsInEditList() = true
    override fun create(path: String): Muxer {
        session.checkpoint()
        if (path != target.muxerToken || !created.compareAndSet(false, true)) reject()
        target.verifyIdentity()
        if (target.sizeBytes() != 0L) reject()
        val stream = target.openEmptyWriter()
        session.retainedObjects.add(stream)
        session.muxerCreated = true
        try { session.retainWriter(target) }
        catch (failure: Throwable) {
            try { stream.close(); if (!stream.releaseConfirmed) reject(); session.muxerReleased = true } catch (_: Throwable) { session.closeFailed.set(true) }
            throw failure
        }
        val output = Output(stream, session, spec.maxBytes)
        val muxer = try { Mp4Muxer.Builder(output).setSampleBatchingEnabled(false).setAttemptStreamableOutputEnabled(false).build() }
        catch (failure: Throwable) { output.close(); throw failure }
        session.retainedObjects.add(muxer)
        return object : Muxer {
            override fun addTrack(format: Format): Int {
                session.checkpoint()
                val type = when (format.sampleMimeType) {
                    MimeTypes.VIDEO_H264 -> {
                        if (format.width != spec.width || format.height != spec.height ||
                            format.rotationDegrees != 0 || format.pixelWidthHeightRatio != 1f ||
                            format.colorInfo?.colorTransfer?.let { it !in setOf(C.COLOR_TRANSFER_SDR, Format.NO_VALUE) } == true) reject()
                        C.TRACK_TYPE_VIDEO
                    }
                    MimeTypes.AUDIO_AAC -> {
                        val expected = spec.audio ?: reject()
                        if (format.sampleRate != expected.sampleRate || format.channelCount != expected.channels) reject()
                        C.TRACK_TYPE_AUDIO
                    }
                    else -> reject()
                }
                if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE || tracks.containsValue(type)) reject()
                val id = muxer.addTrack(format); tracks[id] = type
                return id
            }
            override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
                session.checkpoint()
                val type = tracks[trackId] ?: reject()
                val last = lastPts[trackId]
                if (bufferInfo.size != byteBuffer.remaining() || bufferInfo.size !in 1..TransformerMechanismPolicy.MAX_SAMPLE_BYTES ||
                    !TransformerMechanismPolicy.timestampAllowed(bufferInfo.presentationTimeUs, last, type == C.TRACK_TYPE_VIDEO,
                        spec.durationUs, spec.durationToleranceUs, spec.audioPrimingToleranceUs) ||
                    bufferInfo.flags and C.BUFFER_FLAG_ENCRYPTED != 0) reject()
                if (type == C.TRACK_TYPE_VIDEO) {
                    if (videoSamples == 0L && bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME == 0) reject()
                    videoSamples++
                    if (videoSamples > spec.expectedVideoFrames) reject()
                } else if (++audioSamples > TransformerMechanismPolicy.MAX_SOURCE_SAMPLES) reject()
                lastPts[trackId] = bufferInfo.presentationTimeUs
                muxer.writeSampleData(trackId, byteBuffer, bufferInfo)
                session.lastProgressNanos = System.nanoTime()
            }
            override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit // Never copy identifying source metadata.
            override fun close() {
                try { output.beginFinalization(); muxer.close(); target.verifyIdentity() }
                // Mp4Muxer is Java-only. Invalid/aborted tables are not evidence that its owned
                // descriptor is still open; Output.close independently establishes that fact.
                catch (failure: Throwable) { session.muxerContentFailed.set(true); throw failure }
                finally { output.close() }
            }
        }
    }
    fun evidence(): TransformerEngineFacts {
        if (!session.muxerReleased || session.muxerContentFailed.get() || videoSamples != spec.expectedVideoFrames || videoSamples < 3 ||
            (spec.audio != null && audioSamples < 1) ||
            (spec.audio == null && audioSamples != 0L)) reject()
        val bytes = target.sizeBytes()
        if (bytes !in 1..spec.maxBytes) reject()
        return TransformerEngineFacts(bytes, videoSamples, audioSamples)
    }
    private class Output(private val stream: TransformerWriter, private val session: TransformerSession, private val limit: Long) : SeekableMuxerOutput {
        private val closed = AtomicBoolean(false)
        private val policy = TransformerOutputIoPolicy(limit)
        fun beginFinalization() { policy.beginFinalization(); stream.beginFinalization() }
        override fun write(source: ByteBuffer): Int {
            policy.activeCheckpoint(session::checkpoint)
            policy.range(stream.position, source.remaining().toLong())
            val count = stream.write(source)
            if (count <= 0 && source.hasRemaining()) rejectRange()
            return count
        }
        override fun getPosition(): Long = stream.position
        override fun setPosition(position: Long) { policy.range(position, 0); stream.setPosition(position) }
        override fun getSize(): Long = stream.size
        override fun truncate(size: Long) { policy.range(size, 0); stream.truncate(size) }
        override fun isOpen() = stream.isOpen
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                var forceFailure: Throwable? = null
                try { stream.force() }
                catch (failure: Throwable) { session.muxerContentFailed.set(true); forceFailure = failure }
                // A force/table error invalidates content. Only failed actual close is sticky.
                try {
                    stream.close()
                    if (!stream.releaseConfirmed || stream.isOpen) rejectRange()
                    policy.confirmedClosed(); session.muxerReleased = true
                } catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                forceFailure?.let { throw it }
            }
        }
        private fun rejectRange(): Nothing = throw IOException("TRANSFORM_OUTPUT_LIMIT")
    }
    private fun reject(): Nothing = throw TransformerFailure(TransformerError.EXECUTION_FAILED)
}
