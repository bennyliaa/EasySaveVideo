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
    private val cleanup: () -> Unit,
) {
    private val requested = AtomicBoolean(false)
    private val performed = AtomicBoolean(false)

    fun close() {
        token.revoke()
        if (!requested.compareAndSet(false, true)) return
        // A dead owner/Looper rejects cleanup. Do not run it on the current/new editor's thread.
        enqueueOnOwner(Runnable { if (performed.compareAndSet(false, true)) cleanup() })
    }
}
