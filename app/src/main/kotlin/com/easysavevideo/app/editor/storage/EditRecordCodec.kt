package com.easysavevideo.app.editor.storage

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.edit.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Small versioned records. Save/HLS/record evidence codecs remain unchanged. */
internal object EditRecordCodec {
    const val MAX_REQUEST_BYTES = 16_384
    fun request(value: EditRequest): ByteArray = encode(MAX_REQUEST_BYTES) {
        writeInt(if (value.recordedInputs) 2 else 1); writeUTF(value.operationId); writeUTF(value.compression.name); writeInt(value.segments.size)
        value.segments.forEach { segment ->
            when (val source = segment.source) {
                is MediaReadRef -> { writeUTF(source.itemId); writeLong(source.revision); writeLong(source.representationGeneration) }
                is RecordedSegmentReadRef -> { writeUTF(source.sessionId); writeUTF(source.segmentId); writeLong(source.generation); writeInt(source.sequence) }
            }
            writeLong(segment.startUs); writeLong(segment.endUsExclusive)
            with(segment.crop) { writeDouble(left); writeDouble(top); writeDouble(right); writeDouble(bottom) }
        }
    }
    fun request(bytes: ByteArray): EditRequest = decode(bytes, MAX_REQUEST_BYTES) {
        val version = readInt(); require(version in 1..2)
        val operationId = readUTF(); val compression = EditCompression.valueOf(readUTF())
        val count = readInt(); require(count in 1..if (version == 1) EditLimits.MAX_SEGMENTS else EditLimits.MAX_RECORDED_SEGMENTS)
        EditRequest(operationId, List(count) {
            val ref = if (version == 1) MediaReadRef(readUTF(), readLong(), readLong())
                else RecordedSegmentReadRef(readUTF(), readUTF(), readLong(), readInt())
            EditSegment(ref, readLong(), readLong(),
                NormalizedCrop(readDouble(), readDouble(), readDouble(), readDouble()))
        }, compression)
    }
    fun expectation(value: EditOutputExpectation): ByteArray = encode(1024) {
        writeInt(3); writeLong(value.durationUs); writeInt(value.width); writeInt(value.height); writeInt(value.frameRate)
        audio(value.audio); writeLong(value.durationToleranceUs); writeLong(value.audioPrimingToleranceUs); writeLong(value.maxBytes); writeLong(value.videoFrameCount); writeInt(value.recordedSegmentCount)
    }
    fun expectation(bytes: ByteArray): EditOutputExpectation = decode(bytes, 1024) {
        val version = readInt(); require(version in 1..3)
        val duration = readLong(); val width = readInt(); val height = readInt(); val fps = readInt()
        val audio = audio(); val tolerance = readLong(); val priming = readLong(); val maxBytes = readLong()
        val frameCount = if (version >= 2) readLong() else (duration * fps + 500_000) / 1_000_000
        val recordedCount = if (version >= 3) readInt() else 0
        EditOutputExpectation(duration, width, height, fps, audio, tolerance, priming, maxBytes, frameCount, recordedCount)
    }
    fun evidence(value: EditVerificationEvidence): ByteArray = encode(4096) {
        writeInt(2); writeUTF(value.output.operationId); writeUTF(value.output.artifactId)
        val expected = expectation(value.expectation); writeInt(expected.size); write(expected)
        writeLong(value.bytes); writeUTF(value.sha256); writeLong(value.actualDurationUs)
        writeInt(value.actualWidth); writeInt(value.actualHeight); writeInt(value.rotationDegrees); writeUTF(value.videoMime)
        writeBoolean(value.audioMime != null); value.audioMime?.let { writeUTF(it) }
        writeBoolean(value.audioSampleRate != null); value.audioSampleRate?.let { writeInt(it) }
        writeBoolean(value.audioChannels != null); value.audioChannels?.let { writeInt(it) }
        writeLong(value.firstVideoPtsUs); writeLong(value.lastVideoPtsUs); writeLong(value.videoSamples)
        writeLong(value.audioSamples); writeLong(value.decodedVideoFrames); writeLong(value.audioDecodedFrames)
        writeBoolean(value.videoOutputEos); writeBoolean(value.audioOutputEos)
        writeBoolean(value.nativeReleaseConfirmed); writeBoolean(value.containerExtentVerified); writeInt(value.verificationVersion)
    }
    fun evidence(bytes: ByteArray): EditVerificationEvidence = decode(bytes, 4096) {
        val version = readInt(); require(version in 1..2)
        val output = EditOutputRef(readUTF(), readUTF()); val size = readInt(); require(size in 8..1024)
        val expected = ByteArray(size).also(::readFully)
        EditVerificationEvidence(output, expectation(expected), readLong(), readUTF(), readLong(), readInt(), readInt(), readInt(), readUTF(),
            if (readBoolean()) readUTF() else null, if (readBoolean()) readInt() else null, if (readBoolean()) readInt() else null,
            readLong(), readLong(), readLong(), readLong(), readLong(), readLong(), readBoolean(), readBoolean(), readBoolean(), readBoolean(), if (version == 2) readInt() else 1)
    }
    private fun DataOutputStream.audio(value: EditAudioProfile) {
        when (value) {
            EditAudioProfile.Silent -> writeBoolean(false)
            is EditAudioProfile.Aac -> { writeBoolean(true); writeInt(value.sampleRate); writeInt(value.channels) }
        }
    }
    private fun DataInputStream.audio(): EditAudioProfile = if (readBoolean()) EditAudioProfile.Aac(readInt(), readInt()) else EditAudioProfile.Silent
    private fun encode(max: Int, action: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { it.action() }
    }.toByteArray().also { require(it.size in 8..max) }
    private fun <T> decode(bytes: ByteArray, max: Int, action: DataInputStream.() -> T): T {
        require(bytes.size in 8..max)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.action().also { require(input.read() == -1) } }
    }
}
