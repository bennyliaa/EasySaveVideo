package com.easysavevideo.save

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One Direct local pipeline, on the already admitted execution worker. HTTP work is outside it.
 * This permit is NOT evidence of descriptor/native release; the existing recovery probes remain
 * authoritative even after a block exits. The caller fences every stage and final publication.
 * In particular, this gate never checks cancellation after a committed publication and turns that
 * already durable success into a failure. No task is scheduled, retried, or given a new deadline.
 */
class DirectLocalProcessingGate(
    private val clock: EpochClock,
    private val resourcesUnreleased: () -> Boolean = { false },
) {
    private val lane = Semaphore(1, true)
    private val activeOwner = AtomicReference<Any?>(null)

    fun <T> withPermit(owner: Any, context: OperationContext, block: () -> T): T {
        while (true) {
            checkActive(context)
            if (activeOwner.get() === owner) throw SaveFailure(SaveError.BUSY)
            val remaining = (context.deadlineEpochMillis - clock.nowMillis()).coerceAtLeast(1)
            val acquired = try {
                lane.tryAcquire(minOf(50L, remaining), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SaveFailure(SaveError.CANCELLED)
            }
            if (acquired) break
        }
        // Only this lexical invocation can retire its permit. There is no public release callback
        // that an old task, completed Future, or a duplicate delivery could use on another owner.
        try {
            checkActive(context)
            check(activeOwner.compareAndSet(null, owner))
            return block()
        } finally {
            activeOwner.compareAndSet(owner, null)
            lane.release()
        }
    }

    private fun checkActive(context: OperationContext) {
        val unreleased = try { resourcesUnreleased() } catch (_: Exception) { true }
        val stopped = when (context.stopReason(clock.nowMillis())) {
            OperationStopReason.CANCELLED -> SaveError.CANCELLED
            OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
            OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
            null -> null
        }
        if (stopped != null) throw SaveFailure(stopped)
        if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        if (unreleased) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
}
