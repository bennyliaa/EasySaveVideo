package com.easysavevideo.recording

/** Both tracks must supply timestamps from the SAME monotonic capture clock. */
enum class RecordingTrack { VIDEO, AUDIO }
enum class RecordingTimelineState { WAITING_KEYFRAME, ACTIVE, PAUSED, RESUMING, STOPPED, FAILED }
enum class RecordingSampleDrop { WAITING_KEYFRAME, PAUSED, BEFORE_BOUNDARY, STOPPED }
sealed interface RecordingSampleMapping {
    data class Accepted(val ptsUs: Long, val durationUs: Long) : RecordingSampleMapping
    data class Dropped(val reason: RecordingSampleDrop) : RecordingSampleMapping
}

/**
 * A bounded timebase: no frame storage and no growing pause history. The first actual keyframe of
 * each admitted epoch opens both tracks. Pause -> resume-request -> keyframe is one excluded gap.
 * Audio packets starting before that keyframe are dropped whole, even when crossing its boundary.
 * Arbitrary backwards timestamps are errors, never silently clamped into apparently valid media.
 * Call only on the capture owner. Commands and sample writes must share that owner's ordering.
 */
class RecordingTimeline(
    startedAtMonotonicUs: Long,
    private val keyframeTimeoutUs: Long = 5_000_000,
) {
    init { require(startedAtMonotonicUs >= 0 && keyframeTimeoutUs > 0) }
    var state: RecordingTimelineState = RecordingTimelineState.WAITING_KEYFRAME
        private set
    private var lastControlUs = startedAtMonotonicUs
    private var boundaryUs = startedAtMonotonicUs
    private var keyframeWaitStartedUs = startedAtMonotonicUs
    private var sourceEpochStartUs = 0L
    private var outputEpochStartUs = 0L
    private var closedDurationUs = 0L
    private var lastVideoSourceUs: Long? = null
    private var lastAudioSourceUs: Long? = null
    private var lastVideoOutputUs: Long? = null
    private var lastAudioOutputUs: Long? = null
    private var lastAcceptedEndUs = 0L
    private var stopCutoffUs: Long? = null
    private var drainStoppedEpoch = false

    fun pause(nowUs: Long): Boolean {
        controlTime(nowUs)
        if (state == RecordingTimelineState.PAUSED) return false
        if (state != RecordingTimelineState.ACTIVE && state != RecordingTimelineState.RESUMING &&
            state != RecordingTimelineState.WAITING_KEYFRAME) return false
        if (state == RecordingTimelineState.ACTIVE) {
            if (nowUs < sourceEpochStartUs || nowUs < (lastVideoSourceUs ?: 0L) ||
                nowUs < (lastAudioSourceUs ?: 0L)) fail(RecordingError.CLOCK_INVALID)
            closedDurationUs = maxOf(add(outputEpochStartUs, nowUs - sourceEpochStartUs), lastAcceptedEndUs)
        }
        boundaryUs = nowUs
        state = RecordingTimelineState.PAUSED
        return true
    }

    /** Returns true once, when the owner must request a video sync frame. */
    fun resume(nowUs: Long): Boolean {
        controlTime(nowUs)
        if (state != RecordingTimelineState.PAUSED) return false
        boundaryUs = nowUs
        keyframeWaitStartedUs = nowUs
        state = RecordingTimelineState.RESUMING
        return true
    }

    fun stop(nowUs: Long): Boolean {
        controlTime(nowUs)
        if (state == RecordingTimelineState.STOPPED || state == RecordingTimelineState.FAILED) return false
        if (state == RecordingTimelineState.ACTIVE) {
            if (nowUs < sourceEpochStartUs || nowUs < (lastVideoSourceUs ?: 0L) ||
                nowUs < (lastAudioSourceUs ?: 0L)) fail(RecordingError.CLOCK_INVALID)
            closedDurationUs = maxOf(add(outputEpochStartUs, nowUs - sourceEpochStartUs), lastAcceptedEndUs)
        }
        drainStoppedEpoch = state == RecordingTimelineState.ACTIVE
        stopCutoffUs = nowUs
        state = RecordingTimelineState.STOPPED
        return true
    }

    fun mapSample(
        track: RecordingTrack,
        sourcePtsUs: Long,
        durationUs: Long,
        isKeyFrame: Boolean = false,
    ): RecordingSampleMapping {
        if (sourcePtsUs < 0 || durationUs <= 0 || sourcePtsUs > Long.MAX_VALUE - durationUs ||
            (track == RecordingTrack.AUDIO && isKeyFrame)) fail(RecordingError.TIMESTAMP_INVALID)
        if (state == RecordingTimelineState.FAILED)
            return RecordingSampleMapping.Dropped(RecordingSampleDrop.STOPPED)
        if (state == RecordingTimelineState.STOPPED) {
            val cutoff = stopCutoffUs ?: 0L
            if (!drainStoppedEpoch || sourcePtsUs >= cutoff ||
                (track == RecordingTrack.AUDIO && sourcePtsUs + durationUs > cutoff))
                return RecordingSampleMapping.Dropped(RecordingSampleDrop.STOPPED)
        }
        if (state == RecordingTimelineState.PAUSED)
            return RecordingSampleMapping.Dropped(RecordingSampleDrop.PAUSED)
        if (sourcePtsUs < boundaryUs)
            return RecordingSampleMapping.Dropped(RecordingSampleDrop.BEFORE_BOUNDARY)
        if (state == RecordingTimelineState.WAITING_KEYFRAME || state == RecordingTimelineState.RESUMING) {
            if (sourcePtsUs - keyframeWaitStartedUs >= keyframeTimeoutUs) fail(RecordingError.KEYFRAME_TIMEOUT)
            if (track != RecordingTrack.VIDEO || !isKeyFrame)
                return RecordingSampleMapping.Dropped(RecordingSampleDrop.WAITING_KEYFRAME)
            sourceEpochStartUs = sourcePtsUs
            outputEpochStartUs = closedDurationUs
            state = RecordingTimelineState.ACTIVE
        }
        if (sourcePtsUs < sourceEpochStartUs)
            return RecordingSampleMapping.Dropped(RecordingSampleDrop.BEFORE_BOUNDARY)
        val lastSource = if (track == RecordingTrack.VIDEO) lastVideoSourceUs else lastAudioSourceUs
        val mapped = add(outputEpochStartUs, sourcePtsUs - sourceEpochStartUs)
        val lastMapped = if (track == RecordingTrack.VIDEO) lastVideoOutputUs else lastAudioOutputUs
        if ((lastSource != null && sourcePtsUs <= lastSource) || (lastMapped != null && mapped <= lastMapped))
            fail(RecordingError.TIMESTAMP_INVALID)
        val end = add(mapped, durationUs)
        if (track == RecordingTrack.VIDEO) {
            lastVideoSourceUs = sourcePtsUs
            lastVideoOutputUs = mapped
        } else {
            lastAudioSourceUs = sourcePtsUs
            lastAudioOutputUs = mapped
        }
        lastAcceptedEndUs = maxOf(lastAcceptedEndUs, end)
        return RecordingSampleMapping.Accepted(mapped, durationUs)
    }

    fun checkDeadline(nowUs: Long) {
        controlTime(nowUs)
        if ((state == RecordingTimelineState.WAITING_KEYFRAME || state == RecordingTimelineState.RESUMING) &&
            nowUs - keyframeWaitStartedUs >= keyframeTimeoutUs) fail(RecordingError.KEYFRAME_TIMEOUT)
    }

    /** UI duration from monotonic time; persisted media duration comes from actual sample ends. */
    fun effectiveDurationUs(nowUs: Long): Long {
        controlTime(nowUs)
        return if (state == RecordingTimelineState.ACTIVE) {
            if (nowUs < sourceEpochStartUs) fail(RecordingError.CLOCK_INVALID)
            add(outputEpochStartUs, nowUs - sourceEpochStartUs)
        } else closedDurationUs
    }

    fun acceptedMediaEndUs(): Long = lastAcceptedEndUs

    private fun controlTime(nowUs: Long) {
        if (nowUs < lastControlUs || nowUs < 0) fail(RecordingError.CLOCK_INVALID)
        lastControlUs = nowUs
    }
    private fun add(a: Long, b: Long): Long {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) fail(RecordingError.TIMESTAMP_INVALID)
        return a + b
    }
    private fun fail(error: RecordingError): Nothing {
        state = RecordingTimelineState.FAILED
        throw RecordingFailure(error)
    }
}
