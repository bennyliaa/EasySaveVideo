package com.easysavevideo.app.recording.segments

import com.easysavevideo.recording.*
import java.io.*

internal data class RecordedCaptureConfig(val descriptor: CaptureDescriptor, val geometry: CaptureDisplayGeometry, val region: RegionCaptureRecipe?)

/** Persist intention, actual closed mux timing and configuration, never projection grants. */
internal object RecordedSegmentCodec {
    fun config(value: RecordedCaptureConfig) = encode {
        writeInt(1); descriptor(value.descriptor)
        geometry(value.geometry); recipe(value.region)
    }
    fun config(bytes: ByteArray): RecordedCaptureConfig = decode(bytes) {
        require(readInt() == 1); val descriptor = descriptor()
        val geometry = geometry(); val region = recipe()
        RecordedCaptureConfig(descriptor, geometry, region)
    }
    fun capture(value: RecordingArtifactExpectation) = encode {
        writeInt(1); descriptor(value.descriptor); profile(value.actualProfile); writeUTF(value.audioOutcome.name); writeUTF(value.stopReason.name)
        track(value.video); writeBoolean(value.audio != null); value.audio?.let { track(it) }; writeLong(value.effectiveDurationUs); writeLong(value.bytesWritten)
        val boundaries = requireNotNull(value.resumeBoundaries); writeInt(boundaries.size)
        boundaries.forEach { writeLong(it.sampleIndex); writeLong(it.ptsUs) }
    }
    fun capture(bytes: ByteArray): RecordingArtifactExpectation = decode(bytes) {
        require(readInt() == 1); val descriptor = descriptor(); val profile = profile(); val audioOutcome = RecordingAudioOutcome.valueOf(readUTF()); val stop = RecordingStopReason.valueOf(readUTF())
        val video = track(); val audio = if (readBoolean()) track() else null; val duration = readLong(); val size = readLong()
        val count = readInt(); require(count in 0..32)
        RecordingArtifactExpectation(descriptor, profile, audioOutcome, stop, video, audio, duration, size,
            resumeBoundaries = List(count) { RecordingResumeBoundary(readLong(), readLong()) })
    }
    fun observation(value: CaptureGeometryObservation) = encode {
        writeInt(1); geometry(value.geometry); writeUTF(value.scopeEvidence.name); writeUTF(value.provenance.name); writeLong(value.observedAtMonotonicUs)
    }
    fun observation(bytes: ByteArray): CaptureGeometryObservation = decode(bytes) {
        require(readInt() == 1); CaptureGeometryObservation(geometry(), CaptureScopeEvidence.valueOf(readUTF()), CaptureGeometryProvenance.valueOf(readUTF()), readLong())
    }
    private fun DataOutputStream.geometry(v: CaptureDisplayGeometry) { with(v) { writeInt(width); writeInt(height); writeInt(rotationDegrees); writeInt(insets.left); writeInt(insets.top); writeInt(insets.right); writeInt(insets.bottom); writeInt(transformVersion) } }
    private fun DataInputStream.geometry() = CaptureDisplayGeometry(readInt(), readInt(), readInt(), CaptureInsets(readInt(), readInt(), readInt(), readInt()), readInt())
    private fun DataOutputStream.recipe(v: RegionCaptureRecipe?) {
        writeBoolean(v != null)
        v?.let { with(it.bounds) { writeDouble(left); writeDouble(top); writeDouble(right); writeDouble(bottom) }; geometry(it.source); writeUTF(it.scopeEvidence.name); writeLong(it.selectedAtMonotonicUs) }
    }
    private fun DataInputStream.recipe(): RegionCaptureRecipe? = if (readBoolean())
        RegionCaptureRecipe(RegionNormalizedRect(readDouble(), readDouble(), readDouble(), readDouble()), geometry(), CaptureScopeEvidence.valueOf(readUTF()), readLong()) else null
    private fun DataOutputStream.descriptor(v: CaptureDescriptor) { writeUTF(v.sessionId); writeLong(v.generation); writeUTF(v.audioChoice.name); profile(v.requestedProfile); recipe(v.regionCaptureRecipe) }
    private fun DataInputStream.descriptor() = CaptureDescriptor(readUTF(), readLong(), RecordingAudioChoice.valueOf(readUTF()), profile(), recipe())
    private fun DataOutputStream.profile(v: RecordingProfile) { with(v) { writeInt(width); writeInt(height); writeInt(frameRate); writeInt(videoBitrate); writeInt(audioSampleRate); writeInt(audioChannels); writeInt(audioBitrate) } }
    private fun DataInputStream.profile() = RecordingProfile(readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt())
    private fun DataOutputStream.track(v: RecordingTrackEvidence) { with(v) { writeLong(sampleCount); writeLong(firstPtsUs); writeLong(lastPtsUs); writeLong(lastDurationUs); writeLong(syncSampleCount); writeBoolean(firstSampleIsKeyFrame) } }
    private fun DataInputStream.track() = RecordingTrackEvidence(readLong(), readLong(), readLong(), readLong(), readLong(), readBoolean())
    private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use { it.block() } }.toByteArray().also { require(it.size in 8..2048) }
    private fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T {
        require(bytes.size in 8..2048)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.block().also { require(input.read() == -1) } }
    }
}
