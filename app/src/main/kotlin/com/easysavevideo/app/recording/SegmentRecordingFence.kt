package com.easysavevideo.app.recording

import com.easysavevideo.recording.RecordingSessionId
import com.easysavevideo.recording.RecordingStopReason

/** Pure job fence. An interrupt/deadline revokes results but never acknowledges native closure. */
internal class SegmentRecordingFence(val session: RecordingSessionId) {
    private var queued = true
    private var started = false
    private var captureClosed = false
    private var processing = false
    private var finished = false
    private var revoked = false
    private var discarded = false
    private var stop: RecordingStopReason? = null
    @Synchronized fun startNative(candidate: RecordingSessionId): Boolean {
        if (candidate != session || !queued || started || finished) return false
        queued = false
        if (revoked || discarded) return false
        started = true
        return true
    }
    @Synchronized fun stopAndSave(candidate: RecordingSessionId, reason: RecordingStopReason): Boolean {
        if (candidate != session || finished || discarded || reason == RecordingStopReason.USER_CANCELLED) return false
        if (stop == null) stop = reason
        return true
    }
    @Synchronized fun discard(candidate: RecordingSessionId): Boolean {
        if (candidate != session || finished) return false
        discarded = true; revoked = true
        return true
    }
    @Synchronized fun revoke(candidate: RecordingSessionId): Boolean {
        if (candidate != session || finished) return false
        revoked = true
        return true
    }
    /** Only the actual worker may acknowledge this; false remains sticky for this job. */
    @Synchronized fun nativeReleased(candidate: RecordingSessionId, confirmed: Boolean) {
        if (candidate != session || finished) return
        queued = false
        if (confirmed) captureClosed = true else revoked = true
    }
    @Synchronized fun beginProcessing(candidate: RecordingSessionId): Boolean {
        if (candidate != session || !captureClosed || revoked || discarded || finished || processing) return false
        processing = true
        return true
    }
    @Synchronized fun acceptsResult(candidate: RecordingSessionId): Boolean =
        candidate == session && captureClosed && processing && !revoked && !discarded && !finished
    @Synchronized fun finish(candidate: RecordingSessionId): Boolean {
        if (candidate != session || finished) return false
        finished = true; processing = false
        return true
    }
    @get:Synchronized val isDiscarded get() = discarded
    @get:Synchronized val isRevoked get() = revoked
    @get:Synchronized val stopReason get() = stop
    @get:Synchronized val isCaptureClosed get() = captureClosed
}
