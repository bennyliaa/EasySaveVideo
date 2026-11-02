package com.easysavevideo.library

import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory per-item exclusion only. Adapters must persist destructive intents before attempting
 * their writer lease, validate revision/generation under the storage-owner lock, and recover durable
 * intents after restart. A stalled native reader must retain its lease; timeout is not proof of close.
 * The availability hook runs outside the registry monitor and must schedule/retry durable work.
 */
class MediaLeaseRegistry(private val onItemAvailable: (String) -> Unit = {}) {
    private class State {
        var readers = 0
        var writer: Any? = null
        var activeOperationId: String? = null
        val pending = linkedSetOf<String>()
    }

    private val monitor = Any()
    private val states = mutableMapOf<String, State>()

    /** New readers cannot overtake a queued destructive writer. */
    fun tryAcquireRead(itemId: String): MediaReadLease? {
        LibraryIds.requireValid(itemId)
        return synchronized(monitor) {
            val state = states.getOrPut(itemId) { State() }
            if (state.writer != null || state.pending.isNotEmpty()) return@synchronized null
            if (state.readers == Int.MAX_VALUE) throw LibraryFailure(LibraryError.BUSY)
            state.readers += 1
            MediaReadLease(itemId) { releaseRead(itemId) }
        }
    }

    /**
     * Enqueue once, then acquire only at the FIFO head with zero readers. A null result retains the
     * blocked intent and fences new readers. Retry with the same operation ID; do not invent IDs for
     * retry. Reentrant acquisition is not allowed, even for the same operation.
     */
    fun tryAcquireWrite(itemId: String, operationId: String): MediaWriteLease? {
        LibraryIds.requireValid(itemId); LibraryIds.requireValid(operationId)
        return synchronized(monitor) {
            val state = states.getOrPut(itemId) { State() }
            if (state.activeOperationId == operationId) return@synchronized null
            state.pending.add(operationId)
            if (state.readers != 0 || state.writer != null || state.pending.first() != operationId) return@synchronized null
            val token = Any()
            state.pending.remove(operationId)
            state.writer = token
            state.activeOperationId = operationId
            MediaWriteLease(itemId, operationId) { releaseWrite(itemId, token) }
        }
    }

    /** Cancel only a queued intent after the durable owner has cancelled it. Never revokes a lease. */
    fun cancelWriteIntent(itemId: String, operationId: String): Boolean {
        LibraryIds.requireValid(itemId); LibraryIds.requireValid(operationId)
        var available = false
        val removed = synchronized(monitor) {
            val state = states[itemId] ?: return@synchronized false
            val changed = state.pending.remove(operationId)
            if (changed) {
                available = state.readers == 0 && state.writer == null
                removeIfUnused(itemId, state)
            }
            changed
        }
        if (available) notifyAvailable(itemId)
        return removed
    }

    fun snapshot(itemId: String): MediaLeaseSnapshot {
        LibraryIds.requireValid(itemId)
        return synchronized(monitor) {
            val state = states[itemId]
            MediaLeaseSnapshot(state?.readers ?: 0, state?.writer != null, state?.pending?.size ?: 0)
        }
    }

    private fun releaseRead(itemId: String) {
        val available = synchronized(monitor) {
            val state = checkNotNull(states[itemId]) { "LEASE_STATE_MISSING" }
            check(state.readers > 0 && state.writer == null) { "LEASE_INVARIANT" }
            state.readers -= 1
            val result = state.readers == 0
            removeIfUnused(itemId, state)
            result
        }
        if (available) notifyAvailable(itemId)
    }

    private fun releaseWrite(itemId: String, token: Any) {
        synchronized(monitor) {
            val state = checkNotNull(states[itemId]) { "LEASE_STATE_MISSING" }
            check(state.writer === token && state.readers == 0) { "LEASE_INVARIANT" }
            state.writer = null
            state.activeOperationId = null
            removeIfUnused(itemId, state)
        }
        notifyAvailable(itemId)
    }

    /** Caller holds monitor; no retained entry once the last owner/intent leaves. */
    private fun removeIfUnused(itemId: String, state: State) {
        if (state.readers == 0 && state.writer == null && state.pending.isEmpty()) states.remove(itemId)
    }

    private fun notifyAvailable(itemId: String) {
        // Ownership was already safely released. A failed scheduling hook must not corrupt counts
        // or make close appear retryable; durable storage recovery remains the authoritative retry.
        try { onItemAvailable(itemId) } catch (_: RuntimeException) { }
    }
}

data class MediaLeaseSnapshot(val readers: Int, val writerActive: Boolean, val pendingWriters: Int) {
    init { require(readers >= 0 && pendingWriters >= 0 && (!writerActive || readers == 0)) }
    val idle: Boolean get() = readers == 0 && !writerActive && pendingWriters == 0
}

class MediaReadLease internal constructor(val itemId: String, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
    override fun toString() = "MediaReadLease([redacted], closed=$isClosed)"
}

class MediaWriteLease internal constructor(val itemId: String, val operationId: String, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
    override fun toString() = "MediaWriteLease([redacted], closed=$isClosed)"
}
