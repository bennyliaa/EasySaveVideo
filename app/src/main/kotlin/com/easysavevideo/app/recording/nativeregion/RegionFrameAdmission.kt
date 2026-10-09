package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure

/**
 * Bounded-rate selection of real frames against a target deadline. A 1ms input tolerance avoids
 * losing ordinary rounded/jittered vsync pairs. Accepted timestamps are NEVER changed; this is
 * not a CFR encoder or CFR evidence. A later independent scan decides whether cadence is usable.
 */
internal class RegionFrameAdmission(frameRate: Int) {
    init { require(frameRate in 15..60) }
    private val periodNs = 1_000_000_000L / frameRate
    private var nextDueNs: Long? = null
    private var lastSeenNs = -1L

    fun admit(timestampNs: Long): Boolean {
        if (timestampNs <= 0 || timestampNs <= lastSeenNs || timestampNs > Long.MAX_VALUE - periodNs)
            throw RecordingFailure(RecordingError.TIMESTAMP_INVALID)
        lastSeenNs = timestampNs
        val due = nextDueNs
        if (due != null && timestampNs < due - INPUT_SELECTION_TOLERANCE_NS) return false
        // A late source or pause does not accumulate a burst of "catch-up" frame requests.
        nextDueNs = if (due == null || timestampNs - due >= periodNs) timestampNs + periodNs else due + periodNs
        return true
    }

    private companion object { const val INPUT_SELECTION_TOLERANCE_NS = 1_000_000L }
}
