package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.CommonsStageCapture
import com.easysavevideo.discovery.CommonsStageDiagnosticsRecorder
import com.easysavevideo.discovery.CommonsStageDiagnosticsSnapshot

/** Memory-only debug ownership. Calls below touch only the concrete bounded recorder, never callbacks. */
internal class DebugCommonsStageDiagnosticsOwner(debugEnabled: Boolean) {
    val recorder: CommonsStageDiagnosticsRecorder? = if (debugEnabled) CommonsStageDiagnosticsRecorder() else null
    private var revision = 0L
    private var active = false

    data class Preview(val revision: Long, val active: Boolean, val armed: Boolean,
        val diagnostics: CommonsStageDiagnosticsSnapshot?)

    @Synchronized fun preview(): Preview {
        val snapshot = recorder?.snapshot()
        return Preview(revision, active, snapshot?.armed == true, snapshot)
    }

    /** Explicit Start only arms. It cannot call a coordinator, create a request, or enroll old work. */
    @Synchronized fun start(expectedRevision: Long): Preview? {
        val diagnostics = recorder ?: return null
        if (expectedRevision != revision || active) return null
        diagnostics.setEnabled(true)
        active = true
        revision++
        return preview()
    }

    @Synchronized fun stop(expectedRevision: Long): Preview? {
        val diagnostics = recorder ?: return null
        if (expectedRevision != revision) return null
        diagnostics.stop()
        active = false
        revision++
        return preview()
    }

    /** Captured at the explicit Search click, never re-read from a later queued search. */
    @Synchronized fun captureAuthorization(): CommonsStageCapture? =
        if (active) recorder?.captureAuthorization() else null

    /** Revocation is synchronous; the recorder retains a sealed bounded local preview. */
    @Synchronized fun revoke(): Preview {
        recorder?.stop()
        active = false
        revision++
        return preview()
    }
}
