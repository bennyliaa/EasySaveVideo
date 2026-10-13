package com.easysavevideo.app.editor

/** Main-thread admission ownership, retained by the runtime across composition recreation. */
internal class EditorSubmissionAttempts {
    class Attempt internal constructor(val revision: Long)
    var current: Attempt? = null
        private set

    fun begin(revision: Long): Attempt? {
        if (current != null) return null
        return Attempt(revision).also { current = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = current === attempt

    /** Only the current attempt can acknowledge its UI latch; stale/duplicate completions do nothing. */
    fun finish(attempt: Attempt): Long? {
        if (!isCurrent(attempt)) return null
        current = null
        return attempt.revision + 1
    }
}
