package com.easysavevideo.save

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val activeInvocation = AtomicReference<Invocation?>(null)

    /**
     * A capability for one live lexical local-lane invocation only. It grants no Save execution,
     * accepted Runtime, storage, or ordinary-native batch authority. In particular, [withBoundPermit]
     * accepts any reentrancy identity; possession of that identity is not execution authorization.
     *
     * Assertions are memory-only. They do not read the clock/cancellation token, call recovery
     * probes, acquire a lane, or establish descriptor/native release. Callers still check stop and
     * actual ownership separately. A binding registry must also use its exact expected gate's
     * [assertIssuedHere], and capture the exact registered derivative context instance itself.
     */
    class LocalPermit private constructor(
        private val issuer: DirectLocalProcessingGate,
        private val invocation: Invocation,
    ) {
        fun requireLive(context: OperationContext) {
            val bound = invocation.context
            if (issuer.activeInvocation.get() !== invocation || invocation.permit.get() !== this ||
                context.operationId != bound.operationId || context.cancelToken !== bound.cancelToken ||
                context.attempt != bound.attempt || context.retryBudget != bound.retryBudget ||
                context.resourceBudget != bound.resourceBudget ||
                context.deadlineEpochMillis > bound.deadlineEpochMillis || !invocation.live.get()) {
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            }
        }

        // Kotlin does not let the enclosing class call this class's private constructor. This
        // module-only trampoline can issue only inside the gate's private lexical acquire/finally;
        // it cannot construct or return an independently live permit, or accept a caller's cell.
        internal companion object {
            fun <T> enter(
                gate: DirectLocalProcessingGate,
                owner: Any,
                context: OperationContext,
                block: (LocalPermit) -> T,
            ): T = gate.withInvocation(owner, context) { invocation ->
                val permit = LocalPermit(gate, invocation)
                check(invocation.permit.compareAndSet(null, permit))
                block(permit)
            }
        }
    }

    private class Invocation(val owner: Any, val context: OperationContext) {
        val live = AtomicBoolean(true)
        val permit = AtomicReference<LocalPermit?>(null)
    }

    fun <T> withPermit(owner: Any, context: OperationContext, block: () -> T): T =
        withInvocation(owner, context) { block() }

    /** Shares the original lane and stop/recovery checks; [owner] is only a reentrancy identity. */
    fun <T> withBoundPermit(owner: Any, context: OperationContext, block: (LocalPermit) -> T): T =
        LocalPermit.enter(this, owner, context, block)

    /**
     * Reject foreign, retired, or incorrectly bound permits without callbacks or lane acquisition.
     * A shortened context is allowed only with the same token, operation, attempt and budgets.
     * This validates its bounds; the registry separately requires its exact registered instance.
     */
    fun assertIssuedHere(permit: LocalPermit, registeredContext: OperationContext) {
        val invocation = activeInvocation.get()
        if (invocation == null || invocation.permit.get() !== permit) {
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        permit.requireLive(registeredContext)
    }

    private fun <T> withInvocation(owner: Any, context: OperationContext, block: (Invocation) -> T): T {
        while (true) {
            checkActive(context)
            if (activeInvocation.get()?.owner === owner) throw SaveFailure(SaveError.BUSY)
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
        var invocation: Invocation? = null
        try {
            checkActive(context)
            val entered = Invocation(owner, context)
            invocation = entered
            check(activeInvocation.compareAndSet(null, entered))
            return block(entered)
        } finally {
            // Invalidate the capability before another caller can acquire the same local lane.
            invocation?.let {
                it.live.set(false)
                activeInvocation.compareAndSet(it, null)
            }
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
