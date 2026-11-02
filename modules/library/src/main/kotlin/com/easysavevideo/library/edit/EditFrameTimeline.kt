package com.easysavevideo.library.edit

import kotlin.math.abs

/**
 * Exact, observed presentation timestamps plus the observed final video-sample endpoint. The array
 * is copied on entry and never exposed. Source order is presentation order, not codec decode order.
 * At most 108,000 frame timestamps plus one endpoint are retained per source (under 865 KiB).
 */
class EditFrameTimeline(frameTimesUs: LongArray, val endUsExclusive: Long) {
    private val times: LongArray
    init {
        if (frameTimesUs.isEmpty() || frameTimesUs.size > EditLimits.MAX_VIDEO_FRAMES ||
            endUsExclusive !in 1..EditLimits.MAX_SOURCE_DURATION_US) throw EditFailure(EditError.UNSUPPORTED_PROFILE)
        times = frameTimesUs.copyOf()
        for (index in times.indices) {
            if (times[index] < 0 || times[index] >= endUsExclusive ||
                (index > 0 && times[index] <= times[index - 1])) throw EditFailure(EditError.UNSUPPORTED_PROFILE)
        }
    }
    val frameCount: Int get() = times.size
    val firstFrameUs: Long get() = times.first()
    val lastFrameUs: Long get() = times.last()
    fun frameTimeUs(index: Int): Long = times[index]

    /** A declared frame-rate field alone is not accepted as constant-frame-rate evidence. */
    fun matchesFrameRate(frameRate: Int): Boolean {
        if (frameRate !in 1..EditLimits.MAX_FRAME_RATE || firstFrameUs > EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) return false
        for (index in times.indices) {
            if (abs(times[index] - firstFrameUs - index * 1_000_000L / frameRate) >
                EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) return false
        }
        return abs(endUsExclusive - firstFrameUs - frameCount * 1_000_000L / frameRate) <=
            EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US
    }

    /**
     * Start moves forward and end moves backward to exact observed sample boundaries. Each edge
     * loses at most one actual source-frame interval. No guessed timestamp can drop a boundary
     * frame. The original request remains unchanged and continues to bind operation idempotency.
     */
    fun snapInward(startUs: Long, endUsExclusive: Long): EditSnappedRange {
        if (startUs < 0 || endUsExclusive <= startUs || endUsExclusive > this.endUsExclusive) {
            throw EditFailure(EditError.INVALID_REQUEST)
        }
        val foundStart = times.binarySearch(startUs)
        val startIndex = if (foundStart >= 0) foundStart else -foundStart - 1
        val endIndex = if (endUsExclusive == this.endUsExclusive) times.size else {
            val foundEnd = times.binarySearch(endUsExclusive)
            if (foundEnd >= 0) foundEnd else -foundEnd - 2
        }
        if (startIndex >= times.size || endIndex <= startIndex) throw EditFailure(EditError.INVALID_REQUEST)
        val actualEnd = if (endIndex == times.size) this.endUsExclusive else times[endIndex]
        return EditSnappedRange(times[startIndex], actualEnd, startIndex, endIndex - startIndex)
    }

    override fun equals(other: Any?): Boolean = other is EditFrameTimeline &&
        endUsExclusive == other.endUsExclusive && times.contentEquals(other.times)
    override fun hashCode(): Int = 31 * times.contentHashCode() + endUsExclusive.hashCode()
    override fun toString() = "EditFrameTimeline(frameCount=$frameCount, [redacted])"
}

data class EditSnappedRange(
    val startUs: Long,
    val endUsExclusive: Long,
    val firstFrameIndex: Int,
    val frameCount: Int,
) {
    val durationUs: Long get() = endUsExclusive - startUs
}
