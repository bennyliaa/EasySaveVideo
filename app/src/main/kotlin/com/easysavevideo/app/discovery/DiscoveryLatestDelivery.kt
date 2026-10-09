package com.easysavevideo.app.discovery

/** One scheduled drain plus one latest value; each drain yields after a single delivery. */
class DiscoveryLatestDelivery<T : Any>(
    private val enqueue: (() -> Unit) -> Boolean,
    private val consume: (T) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var latest: T? = null
    private var scheduled = false
    private var closed = false
    fun offer(value: T): Boolean = synchronized(lock) {
        if (closed) return false
        latest = value
        scheduleLocked()
    }
    private fun scheduleLocked(): Boolean {
        if (scheduled) return true
        scheduled = true
        val accepted = try { enqueue(::drain) } catch (_: Exception) { false }
        if (!accepted) { closed = true; latest = null; scheduled = false }
        return accepted
    }
    private fun drain() {
        val value = synchronized(lock) {
            if (closed) { scheduled = false; return }
            latest.also { latest = null }
        }
        try { if (value != null) consume(value) }
        finally {
            synchronized(lock) {
                scheduled = false
                if (!closed && latest != null) scheduleLocked()
            }
        }
    }
    override fun close() = synchronized(lock) { closed = true; latest = null }
}
