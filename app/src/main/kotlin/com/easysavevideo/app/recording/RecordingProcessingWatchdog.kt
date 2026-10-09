package com.easysavevideo.app.recording

import com.easysavevideo.app.editor.EditorDeadlineGuard
import com.easysavevideo.app.editor.EditorTimerScheduler

/** Main-thread timer ownership only. Never releases files, projections, jobs or native lanes. */
internal class RecordingProcessingWatchdog(
    scheduler: EditorTimerScheduler,
    onDeadline: () -> Unit,
    onWaiting: () -> Unit,
) {
    private var active: Any? = null
    private var waiting = false
    private val guard = EditorDeadlineGuard(scheduler, { if (!waiting) onDeadline() }, {
        waiting = true; onWaiting()
    })
    fun processing(identity: Any, remainingMillis: Long, cancelled: Boolean = false) {
        if (active !== identity) { check(active == null); active = identity; waiting = false; guard.start(identity, remainingMillis) }
        if (cancelled) guard.cancelled(identity)
    }
    fun stopping(identity: Any) {
        // Explicit retained cleanup may have no preceding media processing operation.
        processing(identity, 15_000, cancelled = true)
    }
    fun completed(identity: Any) {
        if (active !== identity) return
        guard.completed(identity); active = null; waiting = false
    }
}
