package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure

/** One bounded encoded sample waits for its real following PTS, never an estimated frame rate. */
internal class MeasuredVideoTail<T : Any>(private val maxRetainedBytes: Long) {
    data class Completed<T>(val sample: T, val durationUs: Long)
    private var held: T? = null
    private var ptsUs = -1L
    var retainedBytes: Int = 0; private set

    fun advance(sample: T, timestampUs: Long, byteCount: Int, otherPendingBytes: Long): Completed<T>? {
        if (timestampUs < 0 || (held == null && timestampUs != 0L) ||
            (held != null && (timestampUs <= ptsUs || timestampUs - ptsUs > 1_000_000)))
            throw RecordingFailure(RecordingError.TIMESTAMP_INVALID)
        if (byteCount <= 0 || otherPendingBytes < 0 || byteCount.toLong() + retainedBytes + otherPendingBytes > maxRetainedBytes)
            throw RecordingFailure(RecordingError.BUFFER_LIMIT)
        val completed = held?.let { Completed(it, timestampUs - ptsUs) }
        held = sample; ptsUs = timestampUs; retainedBytes = byteCount
        return completed
    }

    /** The final retained sample is an observed endpoint, not a sample of invented duration. */
    fun discardEndpoint(): T? {
        val result = held
        held = null; ptsUs = -1; retainedBytes = 0
        return result
    }
}
