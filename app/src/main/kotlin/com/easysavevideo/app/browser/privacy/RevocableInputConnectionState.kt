package com.easysavevideo.app.browser.privacy

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production completion gate, shared by both API34 operations and plain JVM tests. The external
 * callback runs only on its supplied Executor. Rejection is terminal: never fall back to a thread
 * that the caller did not authorize. At most one completion is scheduled and delivered.
 */
internal class PrivateInputCompletion<T>(
    private val token: PrivateInputToken,
    private val executor: Executor,
    private val rejected: () -> T,
    private val consumer: (T) -> Unit,
) {
    private enum class State { PENDING, SCHEDULED, DELIVERED, REJECTED }
    private val state = AtomicReference(State.PENDING)

    fun complete(value: T) {
        if (!state.compareAndSet(State.PENDING, State.SCHEDULED)) return
        try {
            executor.execute {
                if (state.compareAndSet(State.SCHEDULED, State.DELIVERED)) {
                    // Recheck when the callback actually executes, not merely when queued.
                    consumer(if (token.isCurrent()) value else rejected())
                }
            }
        } catch (_: RejectedExecutionException) {
            state.compareAndSet(State.SCHEDULED, State.REJECTED)
        }
    }
}

/** Production once-only cleanup gate. The Android port supplies exact captured-owner scheduling. */
internal class PrivateInputCleanup(
    private val token: PrivateInputToken,
    private val enqueueOnOwner: (Runnable) -> Boolean,
    private val onCleanupReturned: (() -> Unit)? = null,
    private val cleanup: () -> Unit,
) {
    private val requested = AtomicBoolean(false)
    private val performed = AtomicBoolean(false)
    private enum class Schedule { PENDING, ACCEPTED, REJECTED }
    private val schedule = AtomicReference(Schedule.PENDING)
    private val cleanupReturned = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    private fun reportReturned() {
        if (schedule.get() == Schedule.ACCEPTED && cleanupReturned.get() && reported.compareAndSet(false, true)) {
            onCleanupReturned?.invoke()
        }
    }

    fun close() {
        token.revoke()
        if (!requested.compareAndSet(false, true)) return
        // A dead owner/Looper rejects cleanup. Do not run it on the current/new editor's thread.
        val accepted = try {
            enqueueOnOwner(Runnable {
                if (performed.compareAndSet(false, true)) {
                    cleanup()
                    // A requested/performed flag or a thrown cleanup is never a positive receipt.
                    cleanupReturned.set(true)
                    reportReturned()
                }
            })
        } catch (failure: Throwable) {
            schedule.set(Schedule.REJECTED)
            throw failure
        }
        schedule.set(if (accepted) Schedule.ACCEPTED else Schedule.REJECTED)
        reportReturned()
    }
}

/**
 * Opt-in, one-shot accounting for the existing host's exact Vault input generation. This is not a
 * window owner or an OS/IME erasure receipt. Sealing and call/resource registration share one short
 * lock; no provider, scheduling port or host callback executes under it. Unknown cleanup retains
 * the fence indefinitely. Tokens capture this object, never a mutable current-host lookup.
 */
internal class PrivateInputRetirement internal constructor(
    private val revokePublication: (PrivateInputRetirement) -> Unit,
) {
    private val lock = Any()
    private val sealed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private var revocationReturned = false
    private var calls = 0L
    private val connections = mutableSetOf<PrivateInputConnectionRetirement>()
    private var callback: (() -> Unit)? = null
    val isRetired: Boolean get() = retired.get()
    internal val isSealed: Boolean get() = sealed.get()

    /** The callback is solely an internal publication CAS, never a provider or host callback. */
    internal fun publishIfOpen(publish: () -> Boolean): Boolean = synchronized(lock) {
        !sealed.get() && publish()
    }

    internal fun beginCall(isCurrent: () -> Boolean): PrivateInputInvocation? = synchronized(lock) {
        if (sealed.get() || !isCurrent() || calls == Long.MAX_VALUE) return@synchronized null
        calls++
        PrivateInputInvocation(this)
    }

    /** Reserve the possible connection BEFORE invoking its provider factory or getHandler. */
    internal fun beginConnection(isCurrent: () -> Boolean): PrivateInputConnectionCreation? = synchronized(lock) {
        if (sealed.get() || !isCurrent() || calls == Long.MAX_VALUE) return@synchronized null
        calls++
        val connection = PrivateInputConnectionRetirement(this)
        connections.add(connection)
        PrivateInputConnectionCreation(PrivateInputInvocation(this), connection)
    }

    fun retire(onRetired: () -> Unit) {
        val close = synchronized(lock) {
            if (!sealed.compareAndSet(false, true)) return
            callback = onRetired
            takeClosesLocked()
        }
        // Admission is already sealed, including calls that still hold a pre-revoke token.
        revokePublication(this)
        synchronized(lock) { revocationReturned = true }
        runCloses(close)
        dispatchIfRetired()
    }

    internal fun callReturned() {
        val close = synchronized(lock) { check(calls > 0); calls--; takeClosesLocked() }
        runCloses(close)
        dispatchIfRetired()
    }

    internal fun bindClose(connection: PrivateInputConnectionRetirement, close: () -> Unit) {
        val action = synchronized(lock) {
            check(connection in connections && connection.close == null)
            connection.close = close
            if ((sealed.get() || connection.closeRequested) && calls == 0L) connection.takeCloseLocked() else null
        }
        // A factory admitted before sealing may only now have obtained a closeable wrapper.
        action?.invoke()
    }

    internal fun requestClose(connection: PrivateInputConnectionRetirement) {
        val close = synchronized(lock) {
            if (connection !in connections) return
            connection.closeRequested = true
            if (calls == 0L) connection.takeCloseLocked() else null
        }
        close?.invoke()
    }

    internal fun connectionReturned(connection: PrivateInputConnectionRetirement) {
        synchronized(lock) { connections.remove(connection) }
        dispatchIfRetired()
    }

    private fun takeClosesLocked(): List<() -> Unit> =
        if (calls == 0L) connections.mapNotNull {
            if (sealed.get() || it.closeRequested) it.takeCloseLocked() else null
        } else emptyList()

    private fun runCloses(close: List<() -> Unit>) {
        close.forEach { action ->
            try { action() } catch (_: RuntimeException) { /* Unknown; keep that exact resource. */ }
        }
    }

    private fun dispatchIfRetired() {
        val notify = synchronized(lock) {
            if (!sealed.get() || !revocationReturned || calls != 0L || connections.isNotEmpty() ||
                !retired.compareAndSet(false, true)) return
            callback.also { callback = null }
        }
        notify?.invoke()
    }

    override fun toString() = "PrivateInputRetirement([redacted])"
}

internal class PrivateInputInvocation(private val retirement: PrivateInputRetirement) {
    private val returned = AtomicBoolean(false)
    fun returned() { if (returned.compareAndSet(false, true)) retirement.callReturned() }
}

internal class PrivateInputConnectionCreation internal constructor(
    val invocation: PrivateInputInvocation,
    val connection: PrivateInputConnectionRetirement,
)

/** Only an exact successful cleanup, or a factory's normal null result, can discharge this debt. */
internal class PrivateInputConnectionRetirement internal constructor(private val retirement: PrivateInputRetirement) {
    internal var close: (() -> Unit)? = null
    internal var closeRequested = false
    private var closeTaken = false
    private val returned = AtomicBoolean(false)
    internal fun takeCloseLocked(): (() -> Unit)? {
        if (closeTaken) return null
        return close?.also { closeTaken = true }
    }
    fun bindClose(close: () -> Unit) = retirement.bindClose(this, close)
    fun requestClose() = retirement.requestClose(this)
    fun confirmClosed() {
        if (returned.compareAndSet(false, true)) retirement.connectionReturned(this)
    }
}
