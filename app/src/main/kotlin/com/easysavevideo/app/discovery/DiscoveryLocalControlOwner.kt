package com.easysavevideo.app.discovery

import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral receipt identity, independent of the target revision changed by a history clear. */
data class DiscoveryLocalControlOperation internal constructor(
    val token: Long,
    val request: DiscoveryControlRequest,
) { override fun toString() = "DiscoveryLocalControlOperation([redacted])" }

data class DiscoveryLocalControlResult internal constructor(
    val operation: DiscoveryLocalControlOperation,
    val outcome: DiscoveryControlOutcome,
) { override fun toString() = "DiscoveryLocalControlResult([redacted])" }

data class DiscoveryLocalControlState(
    val active: DiscoveryLocalControlOperation? = null,
    val lastOutcome: DiscoveryLocalControlResult? = null,
) {
    val busy: Boolean get() = active != null
    override fun toString() = "DiscoveryLocalControlState([redacted])"
}

/**
 * Controller-owned, single-flight local mutations. This owner never reads or changes a store.
 * Public calls and [publish] belong to the UI lane; [deliver] must run its callback on that lane.
 * The claim is installed before dispatch and held until delivery, including across view disposal.
 * Closing prevents not-yet-started work but does not rewrite a mutation's already-known outcome.
 */
class DiscoveryLocalControlOwner(
    private val publish: (DiscoveryLocalControlState) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var nextToken = 0L
    private var snapshot = DiscoveryLocalControlState()
    val state: DiscoveryLocalControlState get() = synchronized(lock) { snapshot }

    /**
     * True means this request owns a new receipt path; rejected queues still resolve as FAILED.
     * False means another request already owns the lane. There is no queue, retry or replacement.
     * [afterWork] is read-only presentation maintenance and cannot change the mutation's outcome.
     */
    fun submit(
        request: DiscoveryControlRequest,
        dispatch: (() -> Unit) -> Boolean,
        work: (DiscoveryControlRequest) -> DiscoveryControlOutcome,
        deliver: (() -> Unit) -> Unit,
        afterWork: (DiscoveryLocalControlResult) -> Unit = {},
    ): Boolean {
        val operation = synchronized(lock) {
            if (snapshot.busy) return false
            DiscoveryLocalControlOperation(++nextToken, request).also {
                snapshot = DiscoveryLocalControlState(active = it)
            }
        }
        publish(state)
        // A dispatch adapter may call twice, execute inline, or throw after executing inline.
        val started = AtomicBoolean(false)
        fun complete(outcome: DiscoveryControlOutcome) {
            val result = DiscoveryLocalControlResult(operation, outcome)
            deliver {
                val completed = synchronized(lock) {
                    if (snapshot.active !== operation) return@deliver
                    DiscoveryLocalControlState(lastOutcome = result).also { snapshot = it }
                }
                // Clear the claim before notifying: a legitimate new confirmation may reenter.
                publish(completed)
            }
        }
        val run = run@{
            if (!started.compareAndSet(false, true)) return@run
            val mayStart = synchronized(lock) { !closed }
            val outcome = if (!mayStart) DiscoveryControlOutcome.STALE else try {
                work(operation.request)
            } catch (_: Exception) {
                DiscoveryControlOutcome.FAILED
            }
            val result = DiscoveryLocalControlResult(operation, outcome)
            // A committed clear stays committed if statistics or read-only refresh later fails.
            try { afterWork(result) } catch (_: Exception) { /* Never recast a completed mutation. */ }
            complete(outcome)
        }
        if (synchronized(lock) { closed }) {
            run()
        } else {
            val accepted = try { dispatch(run) } catch (_: Exception) { false }
            if (!accepted && started.compareAndSet(false, true)) {
                complete(if (synchronized(lock) { closed }) DiscoveryControlOutcome.STALE else DiscoveryControlOutcome.FAILED)
            }
        }
        return true
    }

    /** A stale close button, even carrying equal data, cannot clear a different receipt. */
    fun dismiss(expected: DiscoveryLocalControlResult): Boolean {
        val updated = synchronized(lock) {
            if (snapshot.lastOutcome !== expected) return false
            snapshot.copy(lastOutcome = null).also { snapshot = it }
        }
        publish(updated)
        return true
    }

    override fun close() { synchronized(lock) { closed = true } }
}
