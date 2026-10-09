package com.easysavevideo.app.recording

/** Memory-only correlation for an actually submitted browser-host draw; never saved state. */
enum class RecordingPreviewHostStage { BEFORE_CONSENT, BEFORE_SNAPSHOT, BEFORE_RECORDING }

data class RecordingPreviewHostRequest(val sessionId: String, val generation: Long, val stage: RecordingPreviewHostStage)

internal fun recordingPreviewHostCallbackCurrent(current: RecordingPreviewHostRequest?,
    callback: RecordingPreviewHostRequest, activeSessionId: String?): Boolean =
    current == callback && callback.sessionId == activeSessionId

/** Pure production fence for one renderer callback; no renderer/device execution is implied. */
internal class RecordingPreviewDrawFence {
    private var retired = false
    private var pending = false
    private var delivered = false
    @Synchronized fun reserve(focused: Boolean): Boolean {
        if (retired || pending || delivered || !focused) return false
        pending = true
        return true
    }
    @Synchronized fun arrive(attached: Boolean, focused: Boolean): Boolean {
        if (retired || !pending || delivered) return false
        pending = false
        if (!attached || !focused) return false
        delivered = true
        return true
    }
    @Synchronized fun close() { retired = true; pending = false }
    @Synchronized fun isRetired() = retired
}

/** The known secure selector owns focus between publication and retirement. */
internal fun recordingPreviewRequiresHostFocus(phase: com.easysavevideo.recording.RegionPreviewPhase?): Boolean =
    phase in setOf(com.easysavevideo.recording.RegionPreviewPhase.WAITING_FOR_FRAME,
        com.easysavevideo.recording.RegionPreviewPhase.READY_TO_ENCODE,
        com.easysavevideo.recording.RegionPreviewPhase.ADMITTED)
