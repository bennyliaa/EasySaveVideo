package com.easysavevideo.library.edit

import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.RecordedSegmentReadRef
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Engineering budgets, not measured performance promises. No new third-party dependencies. */
object EditLimits {
    const val MAX_SEGMENTS = 8
    const val MAX_RECORDED_SEGMENTS = 33
    const val MAX_SOURCE_DURATION_US = 30L * 60 * 1_000_000
    const val MAX_OUTPUT_DURATION_US = 30L * 60 * 1_000_000
    const val MIN_SEGMENT_DURATION_US = 250_000L
    const val MAX_SOURCE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_TOTAL_SOURCE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_DIMENSION = 1920
    const val MAX_PIXELS = 1920L * 1080
    const val MAX_FRAME_RATE = 60
    const val MAX_VIDEO_FRAMES = 108_000
    const val MAX_FRAME_TIMESTAMP_DRIFT_US = 1_000L
    // Preserve 24 us for 90 kHz conversion, including the duplicated final-frame interval.
    const val MAX_PLANNED_FRAME_TIMESTAMP_DRIFT_US = 976L
    const val SAVE_SPACE_MAX_DIMENSION = 1280
    const val MAX_ACTIVE = 8
    const val MAX_RUNNING = 1
    const val PREPARE_TIMEOUT_MILLIS = 30_000L
    const val STALL_TIMEOUT_MILLIS = 60_000L
    const val RELEASE_TIMEOUT_MILLIS = 10_000L
    const val MAX_VERIFY_TIMEOUT_MILLIS = 30L * 60 * 1000
    /** Full-decode budget scales with output length; this is not an Android speed guarantee. */
    fun verificationTimeoutMillis(durationUs: Long): Long {
        require(durationUs in 1..MAX_OUTPUT_DURATION_US)
        return (30_000L + durationUs / 1000 * 2).coerceAtMost(MAX_VERIFY_TIMEOUT_MILLIS)
    }
    const val EXECUTION_TIMEOUT_MILLIS = 60L * 60 * 1000
}

enum class EditCompression { ORIGINAL_QUALITY, SAVE_SPACE }
enum class EditVideoCodec { AVC, UNSUPPORTED }
enum class EditAvcProfile { BASELINE, MAIN, HIGH, UNSUPPORTED }
enum class EditColorProfile { SDR, HDR, UNKNOWN }
enum class EditError {
    INVALID_REQUEST, INVALID_CROP, STALE_SOURCE, SOURCE_MISSING, SOURCE_LIMIT,
    DURATION_LIMIT, PIXEL_LIMIT, UNSUPPORTED_PROFILE, INCOMPATIBLE_SEGMENTS,
    INSUFFICIENT_SPACE, CAPACITY, INVALID_STATE, CANCELLED, TIMEOUT,
    EXECUTION_FAILED, VERIFICATION_FAILED, RELEASE_UNCONFIRMED, PUBLICATION_UNCERTAIN,
}
class EditFailure(val code: EditError) : RuntimeException(code.name)

/** M6-A permits all-silent sequences or one consistent AAC-LC mono/stereo layout. */
sealed class EditAudioProfile {
    data object Silent : EditAudioProfile()
    data class Aac(val sampleRate: Int, val channels: Int) : EditAudioProfile() {
        init {
            require(sampleRate in setOf(32_000, 44_100, 48_000))
            require(channels in 1..2)
        }
    }
}

/** End is exclusive. Crop is in DISPLAY orientation after applying the source rotation. */
data class EditSegment(
    val source: EditInputRef,
    val startUs: Long,
    val endUsExclusive: Long,
    val crop: NormalizedCrop = NormalizedCrop.FULL,
) {
    init {
        if (startUs < 0 || endUsExclusive <= startUs ||
            endUsExclusive > EditLimits.MAX_SOURCE_DURATION_US ||
            endUsExclusive - startUs < EditLimits.MIN_SEGMENT_DURATION_US) {
            throw EditFailure(EditError.INVALID_REQUEST)
        }
    }
    val durationUs: Long get() = endUsExclusive - startUs
    override fun toString() = "EditSegment([redacted])"
}

/** Immutable ordered input snapshot. Repeats are allowed and retain their exact sequence position. */
class EditRequest(
    val operationId: String,
    segments: List<EditSegment>,
    val compression: EditCompression = EditCompression.ORIGINAL_QUALITY,
) {
    val segments: List<EditSegment> = snapshotSegments(segments)
    val recordedInputs: Boolean = this.segments.firstOrNull()?.source is RecordedSegmentReadRef
    val maxSegments: Int get() = if (recordedInputs) EditLimits.MAX_RECORDED_SEGMENTS else EditLimits.MAX_SEGMENTS
    init {
        LibraryIds.requireValid(operationId)
        if (this.segments.size > maxSegments) throw EditFailure(EditError.SOURCE_LIMIT)
        if (recordedInputs) {
            val refs = this.segments.map { it.source as? RecordedSegmentReadRef ?: throw EditFailure(EditError.INVALID_REQUEST) }
            if (refs.any { it.sessionId != refs.first().sessionId || it.generation != refs.first().generation } || refs.map { it.segmentId }.distinct().size != refs.size ||
                refs.indices.any { refs[it].sequence != it }) throw EditFailure(EditError.INVALID_REQUEST)
        } else if (this.segments.any { it.source !is MediaReadRef }) throw EditFailure(EditError.INVALID_REQUEST)
        if (this.segments.sumOf { it.durationUs } > EditLimits.MAX_OUTPUT_DURATION_US) {
            throw EditFailure(EditError.DURATION_LIMIT)
        }
    }
    private fun snapshotSegments(source: List<EditSegment>): List<EditSegment> {
        val count = source.size
        if (count !in 1..EditLimits.MAX_RECORDED_SEGMENTS) throw EditFailure(EditError.SOURCE_LIMIT)
        val snapshot = ArrayList<EditSegment>(count)
        repeat(count) { snapshot += source[it] }
        if (source.size != count) throw EditFailure(EditError.INVALID_REQUEST)
        return Collections.unmodifiableList(snapshot)
    }
    /** Length-prefixed stable idempotency identity includes ordering, revision, generation and crop. */
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        field("ordinary-edit-v1"); field(operationId); field(compression.name); field(segments.size.toString())
        segments.forEach {
            when (val source = it.source) {
                is MediaReadRef -> { field(source.itemId); field(source.revision.toString()); field(source.representationGeneration.toString()) }
                is RecordedSegmentReadRef -> { field("recorded-segment-v1"); field(source.sessionId); field(source.segmentId); field(source.generation.toString()); field(source.sequence.toString()) }
            }
            field(it.startUs.toString()); field(it.endUsExclusive.toString())
            listOf(it.crop.left, it.crop.top, it.crop.right, it.crop.bottom).forEach { coordinate ->
                field(java.lang.Double.doubleToLongBits(if (coordinate == 0.0) 0.0 else coordinate).toString())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
    override fun toString() = "EditRequest(segments=${segments.size}, compression=$compression, [redacted])"
}

/**
 * Adapter observations bound to a lease for the exact catalog revision/generation, never UI claims.
 * An unknown or unsupported profile is a rejection. VFR is rejected in this initial slice: a
 * declared/rounded frame-rate field alone is insufficient; the adapter checks sample cadence.
 */
data class EditSourceMetadata(
    val ref: EditInputRef,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameRate: Int,
    val audio: EditAudioProfile,
    val bytes: Long,
    val videoCodec: EditVideoCodec = EditVideoCodec.AVC,
    val avcProfile: EditAvcProfile = EditAvcProfile.UNSUPPORTED,
    val color: EditColorProfile = EditColorProfile.UNKNOWN,
    val squarePixels: Boolean = false,
    val constantFrameRate: Boolean = false,
    val bitDepth: Int = 0,
    val frameTimeline: EditFrameTimeline? = null,
) {
    override fun toString() = "EditSourceMetadata([redacted])"
}

/** No filesystem locator, URL, provider grant, or Save receipt may cross this boundary. */
data class EditOutputRef(val operationId: String, val artifactId: String) {
    init { LibraryIds.requireValid(operationId); LibraryIds.requireValid(artifactId) }
    override fun toString() = "EditOutputRef([redacted])"
}

/**
 * All outputs are MP4, AVC, eight-bit SDR, square pixels, physically display-oriented, rotation=0.
 * Requested trim edges snap inward to observed frame boundaries before decoded/re-encoded output.
 * Each edge may move by up to one actual source-frame interval; there is no arbitrary-boundary
 * frame-accuracy or keyframe-copy claim. Duration here is the actual snapped sum, not requested sum.
 * AAC-LC allows at most two 1024-sample packets
 * per segment for priming/padding; offsets beyond those explicit bounds must fail verification.
 */
data class EditOutputExpectation(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val audio: EditAudioProfile,
    val durationToleranceUs: Long,
    val audioPrimingToleranceUs: Long,
    val maxBytes: Long = EditLimits.MAX_OUTPUT_BYTES,
    val videoFrameCount: Long = (durationUs * frameRate + 500_000L) / 1_000_000L,
    /** Explicit private-recording extension. Zero retains the ordinary eight-input budget. */
    val recordedSegmentCount: Int = 0,
) {
    init {
        require(durationUs in 1..EditLimits.MAX_OUTPUT_DURATION_US)
        require(width in 2..EditLimits.MAX_DIMENSION && height in 2..EditLimits.MAX_DIMENSION)
        require(width % 2 == 0 && height % 2 == 0 && width.toLong() * height <= EditLimits.MAX_PIXELS)
        require(frameRate in 1..EditLimits.MAX_FRAME_RATE)
        require(recordedSegmentCount in 0..EditLimits.MAX_RECORDED_SEGMENTS)
        val primingSegments = if (recordedSegmentCount == 0) EditLimits.MAX_SEGMENTS else recordedSegmentCount
        val primingLimit = when (audio) {
            EditAudioProfile.Silent -> 0L
            is EditAudioProfile.Aac -> ((2_048_000_000L + audio.sampleRate - 1) / audio.sampleRate) * primingSegments
        }
        require(durationToleranceUs in 1..8_000_000 && audioPrimingToleranceUs in 0..primingLimit)
        require(maxBytes in 1..EditLimits.MAX_OUTPUT_BYTES)
        require(videoFrameCount in 1..EditLimits.MAX_VIDEO_FRAMES.toLong())
    }
    val rotationDegrees: Int get() = 0
}

data class PlannedEditSegment(
    val segment: EditSegment,
    val metadata: EditSourceMetadata,
    val displayCrop: PixelCrop,
    val sourceCrop: PixelCrop,
    val outputStartUs: Long,
    val executionStartUs: Long,
    val executionEndUsExclusive: Long,
    val frameCount: Int,
) {
    val durationUs: Long get() = executionEndUsExclusive - executionStartUs
    val outputEndUsExclusive: Long get() = outputStartUs + durationUs
    override fun toString() = "PlannedEditSegment([redacted])"
}

class EditPlan internal constructor(
    val request: EditRequest,
    segments: List<PlannedEditSegment>,
    val expectation: EditOutputExpectation,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val requiredFreeBytes: Long,
) {
    val segments: List<PlannedEditSegment> = Collections.unmodifiableList(ArrayList(segments))
    /** Originals are never deleted or overwritten, including after successful publication. */
    val retainsOriginals: Boolean get() = true
    /** ORIGINAL_QUALITY preserves crop resolution where possible; encoding is still lossy. */
    val requiresTranscode: Boolean get() = true
    override fun toString() = "EditPlan(segments=${segments.size}, [redacted])"
}
