package com.easysavevideo.app.editor

/** UI scheduling only. Closing a ticket removes a timer; it says nothing about native ownership. */
internal fun interface EditorTimerScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable
}

/** At most two timers for the one active operation. Stale callbacks cannot affect its successor. */
internal class EditorDeadlineGuard(
    private val scheduler: EditorTimerScheduler,
    private val onDeadline: () -> Unit,
    private val onUnconfirmedStop: (Any) -> Unit,
    private val stopNoticeMillis: Long = 10_000,
) {
    private var active: Any? = null
    private var deadline: AutoCloseable? = null
    private var pendingStop: AutoCloseable? = null
    fun start(identity: Any, remainingMillis: Long) {
        check(active == null && remainingMillis > 0)
        active = identity
        deadline = scheduler.schedule(remainingMillis) {
            if (active === identity) { onDeadline(); cancelled(identity) }
        }
    }
    fun cancelled(identity: Any) {
        if (active !== identity || pendingStop != null) return
        pendingStop = scheduler.schedule(stopNoticeMillis) {
            if (active === identity) onUnconfirmedStop(identity)
        }
    }
    /** Actual worker completion only, including an explicit retained/recovery result. */
    fun completed(identity: Any) {
        if (active !== identity) return
        active = null
        deadline?.close(); pendingStop?.close(); deadline = null; pendingStop = null
    }
}
