package com.easysavevideo.app.recording

import android.content.Intent
import android.os.SystemClock

/** One bounded process-memory cell. Never serialized, logged, or put in a service Intent. */
internal class OneShotGrantCell<T>(private val nowMillis: () -> Long, private val lifetimeMillis: Long) {
    private data class Entry<T>(val requestId: String, val created: Long, val value: T)
    private var entry: Entry<T>? = null
    init { require(lifetimeMillis > 0) }
    @Synchronized fun offer(requestId: String, value: T): Boolean {
        require(requestId.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        expire()
        if (entry != null) return false
        entry = Entry(requestId, nowMillis(), value)
        return true
    }
    @Synchronized fun take(requestId: String): T? {
        expire()
        val current = entry ?: return null
        if (current.requestId != requestId) return null
        entry = null
        return current.value
    }
    @Synchronized fun discard(requestId: String) { if (entry?.requestId == requestId) entry = null }
    @Synchronized fun contains(requestId: String): Boolean { expire(); return entry?.requestId == requestId }
    private fun expire() {
        val current = entry ?: return
        val now = nowMillis()
        if (now < current.created || now - current.created >= lifetimeMillis) entry = null
    }
}
internal data class ProjectionGrant(val resultCode: Int, val data: Intent)
internal object ProjectionGrantHandoff {
    private val cell = OneShotGrantCell<ProjectionGrant>(SystemClock::elapsedRealtime, 30_000)
    fun offer(requestId: String, resultCode: Int, data: Intent) = cell.offer(requestId, ProjectionGrant(resultCode, data))
    fun take(requestId: String) = cell.take(requestId)
    fun discard(requestId: String) = cell.discard(requestId)
    fun contains(requestId: String) = cell.contains(requestId)
}
