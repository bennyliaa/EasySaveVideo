package com.easysavevideo.app.discovery.network

import com.easysavevideo.searxng.SearxngError
import com.easysavevideo.searxng.SearxngFailure
import java.util.Locale

/** Process-memory only, bounded service-level physical ownership and Retry-After across slot edits. */
internal class SearxngOriginAdmission(private val maximumOrigins: Int = 64) {
    init { require(maximumOrigins in 1..64) }
    internal class State(var active: Boolean = false, var retryAt: Long = 0)
    private val states = linkedMapOf<String, State>()
    internal inner class Lease internal constructor(private val state: State) {
        private var released = false
        fun slowdown(until: Long) = synchronized(this@SearxngOriginAdmission) {
            if (!released) state.retryAt = maxOf(state.retryAt, until)
        }
        fun release() = synchronized(this@SearxngOriginAdmission) {
            if (!released) { released = true; state.active = false }
        }
    }
    @Synchronized fun acquire(host: String, now: Long): Lease {
        val key = host.lowercase(Locale.ROOT)
        states.entries.removeAll { !it.value.active && it.value.retryAt <= now }
        val state = states[key] ?: run {
            if (states.size >= maximumOrigins) throw SearxngFailure(SearxngError.BUSY)
            State().also { states[key] = it }
        }
        if (now < state.retryAt) throw SearxngFailure(SearxngError.RATE_LIMITED, state.retryAt)
        if (state.active) throw SearxngFailure(SearxngError.BUSY)
        state.active = true
        return Lease(state)
    }
}

internal object ProcessSearxngOriginAdmission {
    val shared = SearxngOriginAdmission()
}
