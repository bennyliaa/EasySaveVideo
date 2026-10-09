package com.easysavevideo.app.editor.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.easysavevideo.library.edit.*
import com.google.common.collect.ImmutableList
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Hard limit includes MP4 headers and final tables, not only compressed sample payloads. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EditorBoundedMuxer(private val plan: EditPlan, private val session: EditorSession) : Muxer.Factory {
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
        val target = session.output ?: reject()
        if (path != target.file.absolutePath || !created.compareAndSet(false, true)) reject()
        target.verifyIdentity()
        if (target.file.length() != 0L) reject()
        val stream = target.openWriter()
        session.retainedObjects.add(stream)
        session.muxerCreated = true
        try { session.nativeWriter = target.retainNativeWriter() }
        catch (failure: Throwable) {
            try { stream.close(); session.muxerReleased = true } catch (_: Throwable) { session.closeFailed.set(true) }
            throw failure
        }
        val output = Output(stream, session, plan.expectation.maxBytes)
        val muxer = try { Mp4Muxer.Builder(output).setSampleBatchingEnabled(false).setAttemptStreamableOutputEnabled(false).build() }
        catch (failure: Throwable) { output.close(); throw failure }
        session.retainedObjects.add(muxer)
        return object : Muxer {
            override fun addTrack(format: Format): Int {
                session.checkpoint()
                val type = when (format.sampleMimeType) {
                    MimeTypes.VIDEO_H264 -> {
                        if (format.width != plan.expectation.width || format.height != plan.expectation.height ||
                            format.rotationDegrees != 0 || format.pixelWidthHeightRatio != 1f ||
                            format.colorInfo?.colorTransfer?.let { it !in setOf(C.COLOR_TRANSFER_SDR, Format.NO_VALUE) } == true) reject()
                        C.TRACK_TYPE_VIDEO
                    }
                    MimeTypes.AUDIO_AAC -> {
                        val expected = plan.expectation.audio as? EditAudioProfile.Aac ?: reject()
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
                if (bufferInfo.size != byteBuffer.remaining() || bufferInfo.size !in 1..EditorEnginePolicy.MAX_SAMPLE_BYTES ||
                    !EditorEnginePolicy.timestampAllowed(bufferInfo.presentationTimeUs, last, type == C.TRACK_TYPE_VIDEO,
                        plan.expectation.durationUs, plan.expectation.durationToleranceUs, plan.expectation.audioPrimingToleranceUs) ||
                    bufferInfo.flags and C.BUFFER_FLAG_ENCRYPTED != 0) reject()
                if (type == C.TRACK_TYPE_VIDEO) {
                    if (videoSamples == 0L && bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME == 0) reject()
                    videoSamples++
                    if (videoSamples > plan.expectation.videoFrameCount) reject()
                } else if (++audioSamples > EditorEnginePolicy.MAX_SOURCE_SAMPLES) reject()
                lastPts[trackId] = bufferInfo.presentationTimeUs
                muxer.writeSampleData(trackId, byteBuffer, bufferInfo)
                session.lastProgressNanos = System.nanoTime()
            }
            override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit // Never copy identifying source metadata.
            override fun close() {
                output.beginFinalization()
                try { muxer.close(); target.verifyIdentity() }
                // Mp4Muxer is Java-only. Invalid/aborted tables are not evidence that its owned
                // descriptor is still open; Output.close independently establishes that fact.
                catch (failure: Throwable) { session.muxerContentFailed.set(true); throw failure }
                finally { output.close() }
            }
        }
    }
    fun evidence(): EditorEngineEvidence {
        if (!session.muxerReleased || session.muxerContentFailed.get() || videoSamples != plan.expectation.videoFrameCount || videoSamples < 3 ||
            (plan.expectation.audio is EditAudioProfile.Aac && audioSamples < 1) ||
            (plan.expectation.audio == EditAudioProfile.Silent && audioSamples != 0L)) reject()
        val bytes = session.output!!.file.length()
        if (bytes !in 1..plan.expectation.maxBytes) reject()
        return EditorEngineEvidence(bytes, videoSamples, audioSamples)
    }
    private class Output(private val stream: FileOutputStream, private val session: EditorSession, private val limit: Long) : SeekableMuxerOutput {
        private val channel = stream.channel
        private val closed = AtomicBoolean(false)
        private val policy = EditorOutputIoPolicy(limit)
        fun beginFinalization() = policy.beginFinalization()
        override fun write(source: ByteBuffer): Int {
            policy.activeCheckpoint(session::checkpoint)
            policy.range(channel.position(), source.remaining().toLong())
            val count = channel.write(source)
            if (count <= 0 && source.hasRemaining()) rejectRange()
            return count
        }
        override fun getPosition(): Long = channel.position()
        override fun setPosition(position: Long) { policy.range(position, 0); channel.position(position) }
        override fun getSize(): Long = channel.size()
        override fun truncate(size: Long) { policy.range(size, 0); channel.truncate(size) }
        override fun isOpen() = channel.isOpen
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                var forceFailure: Throwable? = null
                try { channel.force(true) }
                catch (failure: Throwable) { session.muxerContentFailed.set(true); forceFailure = failure }
                // A force/table error invalidates content. Only failed actual close is sticky.
                try {
                    stream.close()
                    if (stream.fd.valid() || channel.isOpen) rejectRange()
                    policy.confirmedClosed(); session.muxerReleased = true
                } catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                forceFailure?.let { throw it }
            }
        }
        private fun rejectRange(): Nothing = throw IOException("EDIT_OUTPUT_LIMIT")
    }
    private fun reject(): Nothing = throw EditFailure(EditError.EXECUTION_FAILED)
}
