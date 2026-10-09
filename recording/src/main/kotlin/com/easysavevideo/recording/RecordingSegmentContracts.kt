package com.easysavevideo.recording

import kotlin.math.abs

object RecordingSegmentLimits {
    const val MAX_SEGMENTS = MAX_RECORDING_RESUME_BOUNDARIES + 1
    const val MAX_DURATION_US = 30L * 60 * 1_000_000
    const val MAX_VIDEO_FRAMES = 108_000
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_FRAME_TIMESTAMP_DRIFT_US = 1_000L
}

/** Ordinals count actual segments, never taps, resume requests, or discarded non-keyframes. */
data class RecordingSegmentId(val session: RecordingSessionId, val ordinal: Int) {
    init { require(ordinal in 0 until RecordingSegmentLimits.MAX_SEGMENTS) }
    override fun toString() = "RecordingSegmentId(ordinal=$ordinal, [redacted])"
}

enum class RecordingFrameTimingProvenance {
    ENCODED_PRESENTATION_TIMESTAMPS,
    INDEPENDENT_CLOSED_ARTIFACT,
}

/**
 * A bounded immutable snapshot of actual muxed video PTS in presentation order and an observed
 * final endpoint. The endpoint must come from an actual following sample/boundary or independently
 * inspected container timing, never 1/requestedFps. These observations do not assert CFR.
 */
class RecordingFrameTimeline(
    frameTimesUs: LongArray,
    val endUsExclusive: Long,
    val provenance: RecordingFrameTimingProvenance = RecordingFrameTimingProvenance.ENCODED_PRESENTATION_TIMESTAMPS,
) {
    private val times: LongArray
    init {
        require(frameTimesUs.size in 2..RecordingSegmentLimits.MAX_VIDEO_FRAMES)
        require(endUsExclusive in 1..RecordingSegmentLimits.MAX_DURATION_US)
        times = frameTimesUs.copyOf()
        require(times.first() == 0L)
        times.forEachIndexed { index, pts ->
            require(pts >= 0 && pts < endUsExclusive)
            require(index == 0 || pts > times[index - 1])
        }
    }
    val frameCount: Int get() = times.size
    val firstFrameUs: Long get() = times.first()
    val lastFrameUs: Long get() = times.last()
    fun frameTimeUs(index: Int): Long = times[index]

    fun matchesTrack(track: RecordingTrackEvidence): Boolean =
        track.sampleCount == frameCount.toLong() && track.firstPtsUs == firstFrameUs &&
            track.lastPtsUs == lastFrameUs && track.endPtsUs == endUsExclusive

    /** Full global phase check, never a rounded-average or metadata-frame-rate acceptance. */
    fun matchesConstantFrameRate(frameRate: Int, toleranceUs: Long = RecordingSegmentLimits.MAX_FRAME_TIMESTAMP_DRIFT_US): Boolean {
        if (frameRate !in 1..60 || toleranceUs !in 0..RecordingSegmentLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) return false
        for (index in times.indices) {
            if (abs(times[index] - index * 1_000_000L / frameRate) > toleranceUs) return false
        }
        return abs(endUsExclusive - frameCount * 1_000_000L / frameRate) <= toleranceUs
    }

    /** Independent inspection checks every measured PTS, including the final endpoint. */
    fun matchesObserved(other: RecordingFrameTimeline, toleranceUs: Long = RecordingSegmentLimits.MAX_FRAME_TIMESTAMP_DRIFT_US): Boolean {
        if (frameCount != other.frameCount || toleranceUs !in 0..RecordingSegmentLimits.MAX_FRAME_TIMESTAMP_DRIFT_US ||
            abs(endUsExclusive - other.endUsExclusive) > toleranceUs) return false
        return times.indices.all { abs(times[it] - other.times[it]) <= toleranceUs }
    }

    override fun equals(other: Any?): Boolean = other is RecordingFrameTimeline &&
        endUsExclusive == other.endUsExclusive && provenance == other.provenance && times.contentEquals(other.times)
    override fun hashCode(): Int = 31 * (31 * times.contentHashCode() + endUsExclusive.hashCode()) + provenance.hashCode()
    override fun toString() = "RecordingFrameTimeline(frameCount=$frameCount, provenance=$provenance, [redacted])"
}

/**
 * One separately finalized mux/FD product. This is native input evidence, NOT independent
 * verification, editor-read authority, a catalog item, or a completed merged video. It may be
 * retained when a later segment fails, but must be independently verified before editing.
 */
data class RecordingClosedSegment(
    val id: RecordingSegmentId,
    val outputHandle: RecordingOutputHandle,
    val expectation: RecordingArtifactExpectation,
    val videoTiming: RecordingFrameTimeline,
    val geometry: CaptureGeometryObservation,
    val region: RegionCaptureRecipe? = null,
    val effectiveStartUs: Long = 0,
) {
    init {
        require(id.session == expectation.descriptor.session)
        require(region == expectation.descriptor.regionCaptureRecipe)
        require(expectation.effectiveDurationUs <= RecordingSegmentLimits.MAX_DURATION_US)
        require(effectiveStartUs in 0..RecordingSegmentLimits.MAX_DURATION_US - expectation.effectiveDurationUs)
        require(videoTiming.matchesTrack(expectation.video))
        // Resumes open distinct files. The old in-file resume scheme may not masquerade as one.
        require(expectation.resumeBoundaries != null && expectation.resumeBoundaries.isEmpty())
        if (region != null) {
            require(RegionCapturePolicy.matches(region, geometry))
            require(RegionCapturePolicy.profileMatches(region, expectation.actualProfile))
        } else {
            require(geometry.geometry.transformVersion == RegionCaptureLimits.TRANSFORM_VERSION)
            require(expectation.actualProfile.width == geometry.geometry.width / 2 * 2)
            require(expectation.actualProfile.height == geometry.geometry.height / 2 * 2)
        }
    }
    val effectiveEndUs: Long get() = effectiveStartUs + expectation.effectiveDurationUs
    override fun toString() = "RecordingClosedSegment(ordinal=${id.ordinal}, [redacted])"
}

/**
 * Exact ordered snapshot, bounded across the whole session. This preserves recoverable evidence;
 * a separate storage owner must independently verify every file and issue current capabilities.
 * No pause/wait interval is part of this effective timeline. No ordering is repaired silently.
 */
class RecordingSegmentSequence(segments: List<RecordingClosedSegment>) {
    val segments: List<RecordingClosedSegment>
    init {
        val count = segments.size
        require(count in 1..RecordingSegmentLimits.MAX_SEGMENTS)
        val copy = Array(count) { segments[it] }
        require(segments.size == count)
        val first = copy.first()
        var end = 0L
        var bytes = 0L
        var frames = 0L
        val handles = HashSet<RecordingOutputHandle>()
        copy.forEachIndexed { index, segment ->
            require(segment.id.session == first.id.session && segment.id.ordinal == index)
            require(handles.add(segment.outputHandle))
            require(segment.effectiveStartUs == end)
            require(segment.expectation.actualProfile == first.expectation.actualProfile)
            require(segment.expectation.descriptor == first.expectation.descriptor)
            require(segment.geometry.geometry == first.geometry.geometry)
            require(segment.geometry.scopeEvidence == first.geometry.scopeEvidence)
            require(segment.geometry.provenance == first.geometry.provenance)
            require(segment.region == first.region)
            end = segment.effectiveEndUs
            bytes += segment.expectation.bytesWritten
            frames += segment.videoTiming.frameCount
            require(end <= RecordingSegmentLimits.MAX_DURATION_US)
            require(bytes <= RecordingSegmentLimits.MAX_TOTAL_BYTES)
            require(frames <= RecordingSegmentLimits.MAX_VIDEO_FRAMES)
        }
        this.segments = object : AbstractList<RecordingClosedSegment>() {
            override val size: Int get() = copy.size
            override fun get(index: Int): RecordingClosedSegment = copy[index]
        }
    }
    val session: RecordingSessionId get() = segments.first().id.session
    val effectiveDurationUs: Long get() = segments.last().effectiveEndUs
    val actualResumeCount: Int get() = segments.size - 1
    fun effectiveStartUs(index: Int): Long = segments[index].effectiveStartUs
    override fun toString() = "RecordingSegmentSequence(count=${segments.size}, [redacted])"
}

enum class RecordingSegmentTerminalIntent { STOP_AND_SAVE, CANCEL_AND_DISCARD }
enum class RecordingSegmentNativeRelease { OWNED, CONFIRMED_CLOSED, UNCONFIRMED }
enum class RecordingSegmentRetentionAction {
    WAIT_FOR_RELEASE, RECOVERY_REQUIRED, RETAIN_FOR_VERIFICATION, RETAIN_VERIFIED_SEGMENTS, DISCARD_OWNED_SEGMENTS,
}

/** Timeouts, cancel clicks and process interruption are never proof that native owners released. */
object RecordingSegmentRetentionPolicy {
    fun decide(
        intent: RecordingSegmentTerminalIntent,
        nativeRelease: RecordingSegmentNativeRelease,
        processInterrupted: Boolean,
        allClosedSegmentsVerified: Boolean,
    ): RecordingSegmentRetentionAction {
        if (processInterrupted || nativeRelease == RecordingSegmentNativeRelease.UNCONFIRMED)
            return RecordingSegmentRetentionAction.RECOVERY_REQUIRED
        if (nativeRelease == RecordingSegmentNativeRelease.OWNED) return RecordingSegmentRetentionAction.WAIT_FOR_RELEASE
        if (intent == RecordingSegmentTerminalIntent.CANCEL_AND_DISCARD)
            return RecordingSegmentRetentionAction.DISCARD_OWNED_SEGMENTS
        return if (allClosedSegmentsVerified) RecordingSegmentRetentionAction.RETAIN_VERIFIED_SEGMENTS
        else RecordingSegmentRetentionAction.RETAIN_FOR_VERIFICATION
    }
}

/** Increment only when an actual resumed segment starts. Repeated requests reserve no slots. */
class RecordingSegmentResumeBudget(private val maxResumes: Int = MAX_RECORDING_RESUME_BOUNDARIES) {
    init { require(maxResumes in 0..MAX_RECORDING_RESUME_BOUNDARIES) }
    var actualResumeCount: Int = 0
        private set
    val canStartResume: Boolean get() = actualResumeCount < maxResumes
    fun resumedSegmentStarted(): Int {
        if (!canStartResume) throw RecordingFailure(RecordingError.SAMPLE_LIMIT)
        actualResumeCount++
        return actualResumeCount
    }
}
