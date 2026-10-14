package com.easysavevideo.save

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real gate capabilities only. These tests do not manufacture execution/Runtime/batch authority. */
class DirectLocalProcessingPermitTest {
    @Test fun privateFinalCapabilityHasNoCallerControlledLifecycle() {
        val type = DirectLocalProcessingGate.LocalPermit::class.java
        assertTrue(Modifier.isFinal(type.modifiers))
        assertFalse(type.isInterface)
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }
            .all { Modifier.isPrivate(it.modifiers) })
        assertFalse(type.methods.any { it.name in setOf("release", "reactivate", "copy", "setLive") })
    }

    @Test fun exactIssuerAcceptsItsLiveOriginalAndShortenedContext() {
        val gate = gate(); val original = operation()
        val shortened = original.copy(deadlineEpochMillis = 500)
        assertEquals(7, gate.withBoundPermit(Any(), original) { permit ->
            permit.requireLive(original)
            gate.assertIssuedHere(permit, original)
            permit.requireLive(shortened)
            gate.assertIssuedHere(permit, shortened)
            // The gate checks immutable bounds; the registry must capture the exact derivative.
            gate.assertIssuedHere(permit, shortened.copy())
            7
        })
    }

    @Test fun foreignGateRejectsEvenWithSameOwnerAndExactContext() {
        val first = gate(); val second = gate(); val owner = Any(); val context = operation()
        first.withBoundPermit(owner, context) { firstPermit ->
            second.withBoundPermit(owner, context) { secondPermit ->
                assertNotSame(firstPermit, secondPermit)
                rejected { second.assertIssuedHere(firstPermit, context) }
                rejected { first.assertIssuedHere(secondPermit, context) }
                first.assertIssuedHere(firstPermit, context)
                second.assertIssuedHere(secondPermit, context)
            }
        }
    }

    @Test fun retiredPermitCannotReviveWhenSameOwnerAndContextAreReused() {
        val gate = gate(); val owner = Any(); val context = operation()
        val retired = gate.withBoundPermit(owner, context) { it }
        rejected { retired.requireLive(context) }
        rejected { gate.assertIssuedHere(retired, context) }
        gate.withBoundPermit(owner, context) { current ->
            assertNotSame(retired, current)
            rejected { retired.requireLive(context) }
            rejected { gate.assertIssuedHere(retired, context) }
            gate.assertIssuedHere(current, context)
        }
        rejected { retired.requireLive(context) }
    }

    @Test fun ordinaryExceptionRetiresCapabilityAndReleasesItsLane() {
        val gate = gate(); val context = operation()
        val captured = AtomicReference<DirectLocalProcessingGate.LocalPermit?>()
        val expected = IllegalStateException("test-only failure")
        try {
            gate.withBoundPermit(Any(), context) { permit -> captured.set(permit); throw expected }
            fail("Expected failure")
        } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        rejected { captured.get()!!.requireLive(context) }
        assertEquals(9, gate.withPermit(Any(), context) { 9 })
    }

    @Test fun operationChangeIsNotARegisteredDerivative() = invalidDerivative {
        it.copy(operationId = "different-operation")
    }

    @Test fun cancellationTokenMustBeTheSameReferenceEvenIfEqual() {
        val originalToken = EqualToken(); val otherToken = EqualToken()
        assertEquals(originalToken, otherToken)
        invalidDerivative(operation(originalToken)) { it.copy(cancelToken = otherToken) }
    }

    @Test fun attemptChangeIsNotARegisteredDerivative() = invalidDerivative { it.copy(attempt = 1) }

    @Test fun retryBudgetCannotGrowOrShrink() {
        invalidDerivative { it.copy(retryBudget = 2) }
        invalidDerivative { it.copy(retryBudget = 0) }
    }

    @Test fun resourceBudgetCannotGrowOrShrink() {
        invalidDerivative { it.copy(resourceBudget = ResourceBudget(2, 100)) }
        invalidDerivative { it.copy(resourceBudget = ResourceBudget(1, 101)) }
        invalidDerivative { it.copy(resourceBudget = ResourceBudget(1, 99)) }
        invalidDerivative { it.copy(resourceBudget = ResourceBudget(1, null)) }
    }

    @Test fun immutableResourceBudgetValuesArePreserved() {
        val gate = gate(); val original = operation()
        gate.withBoundPermit(Any(), original) { permit ->
            gate.assertIssuedHere(permit, original.copy(resourceBudget = ResourceBudget(1, 100)))
        }
    }

    @Test fun deadlineCannotBeExtendedOrRenewed() = invalidDerivative {
        it.copy(deadlineEpochMillis = it.deadlineEpochMillis + 1)
    }

    @Test fun liveAssertionsNeverInvokeRecoveryClockOrCancellationCallbacks() {
        val forbidCallbacks = AtomicBoolean(false)
        val clockCalls = AtomicInteger(); val recoveryCalls = AtomicInteger(); val cancellationCalls = AtomicInteger()
        val token = object : CancellationToken {
            override val isCancelled: Boolean get() {
                check(!forbidCallbacks.get()); cancellationCalls.incrementAndGet(); return false
            }
        }
        val gate = DirectLocalProcessingGate(EpochClock {
            check(!forbidCallbacks.get()); clockCalls.incrementAndGet(); 0
        }) { check(!forbidCallbacks.get()); recoveryCalls.incrementAndGet(); false }
        val context = operation(token)
        val captured = gate.withBoundPermit(Any(), context) { permit ->
            val before = listOf(clockCalls.get(), recoveryCalls.get(), cancellationCalls.get())
            forbidCallbacks.set(true)
            repeat(10) {
                permit.requireLive(context)
                gate.assertIssuedHere(permit, context.copy(deadlineEpochMillis = 500))
            }
            rejected { gate.assertIssuedHere(permit, context.copy(operationId = "wrong")) }
            assertEquals(before, listOf(clockCalls.get(), recoveryCalls.get(), cancellationCalls.get()))
            permit
        }
        // Retirement and stale assertions likewise need no externally supplied callback.
        rejected { captured.requireLive(context) }
        rejected { gate.assertIssuedHere(captured, context) }
    }

    @Test fun lexicalLivenessDoesNotPretendToRecheckStopOrActualResourceRelease() {
        val now = AtomicLong(); val unreleased = AtomicBoolean()
        val token = CancellationSource(); val context = operation(token)
        val gate = DirectLocalProcessingGate(EpochClock { now.get() }, unreleased::get)
        assertEquals("durable-success", gate.withBoundPermit(Any(), context) { permit ->
            token.cancel(); now.set(context.deadlineEpochMillis); unreleased.set(true)
            permit.requireLive(context)
            gate.assertIssuedHere(permit, context)
            "durable-success"
        })
        // The next real entry still applies the unchanged stop-before-recovery priority.
        failure(SaveError.CANCELLED) { gate.withBoundPermit(Any(), context) { fail("Entered") } }
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
            gate.withBoundPermit(Any(), operation(deadline = 2000)) { fail("Entered") }
        }
        unreleased.set(false)
        assertEquals(1, gate.withBoundPermit(Any(), operation(deadline = 2000)) { 1 })
    }

    @Test fun legacyOuterRejectsBoundReentryWithoutRetiringOuterLane() {
        val gate = gate(); val owner = Any(); val context = operation()
        assertEquals(3, gate.withPermit(owner, context) {
            failure(SaveError.BUSY) { gate.withBoundPermit(owner, context) { fail("Reentered") } }
            3
        })
        assertEquals(4, gate.withBoundPermit(owner, context) { 4 })
    }

    @Test fun boundOuterRejectsBothReentryApisAndPreservesItsLiveCapability() {
        val gate = gate(); val owner = Any(); val context = operation()
        gate.withBoundPermit(owner, context) { permit ->
            failure(SaveError.BUSY) { gate.withPermit(owner, context) { fail("Reentered") } }
            failure(SaveError.BUSY) { gate.withBoundPermit(owner, context) { fail("Reentered") } }
            gate.assertIssuedHere(permit, context)
        }
    }

    @Test fun capabilityIsInvalidBeforeLegacyWaiterCanEnterSharedLane() {
        val gate = gate(); val context = operation(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val captured = AtomicReference<DirectLocalProcessingGate.LocalPermit?>()
        val first = Worker { gate.withBoundPermit(Any(), context) { permit ->
            captured.set(permit); entered.countDown(); await(finish); 1
        } }
        await(entered)
        val token = ObservedToken(); val secondEntered = AtomicBoolean()
        val second = Worker { gate.withPermit(Any(), operation(token)) {
            secondEntered.set(true)
            rejected { captured.get()!!.requireLive(context) }
            rejected { gate.assertIssuedHere(captured.get()!!, context) }
            2
        } }
        try { await(token.checked); assertFalse(secondEntered.get()) }
        finally { finish.countDown() }
        assertEquals(1, first.value()); assertEquals(2, second.value())
    }

    @Test fun boundWaiterUsesSameLaneAsLegacyOwnerAndRunsOnOriginalWorker() {
        val gate = gate(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val first = Worker { gate.withPermit(Any(), operation()) { entered.countDown(); await(finish); 1 } }
        await(entered)
        val token = ObservedToken(); val secondEntered = AtomicBoolean()
        val second = Worker {
            val originalThread = Thread.currentThread(); val context = operation(token)
            gate.withBoundPermit(Any(), context) { permit ->
                secondEntered.set(true); assertSame(originalThread, Thread.currentThread())
                gate.assertIssuedHere(permit, context); 2
            }
        }
        try { await(token.checked); assertFalse(secondEntered.get()) }
        finally { finish.countDown() }
        assertEquals(1, first.value()); assertEquals(2, second.value())
    }

    @Test fun failedPostAcquireCheckCannotExposeCapabilityOrLeakLane() {
        val checks = AtomicInteger(); val callbacks = AtomicInteger()
        val token = object : CancellationToken { override val isCancelled get() = checks.incrementAndGet() >= 2 }
        val gate = gate()
        failure(SaveError.CANCELLED) {
            gate.withBoundPermit(Any(), operation(token)) { callbacks.incrementAndGet(); fail("Entered") }
        }
        assertEquals(0, callbacks.get())
        assertEquals(5, gate.withBoundPermit(Any(), operation()) { 5 })
    }

    @Test fun cancelledBoundWaiterCannotRetireLiveOwner() = stoppedWaiter(SaveError.CANCELLED) {
        token, _, _ -> token.cancelled.set(true)
    }

    @Test fun expiredBoundWaiterCannotRetireLiveOwner() = stoppedWaiter(SaveError.DEADLINE_EXCEEDED) {
        _, now, _ -> now.set(1000)
    }

    @Test fun interruptedBoundWaiterPreservesInterruptAndCannotRetireLiveOwner() =
        stoppedWaiter(SaveError.CANCELLED, expectInterrupted = true) { _, _, worker -> worker.thread.interrupt() }

    private fun invalidDerivative(
        original: OperationContext = operation(),
        derivative: (OperationContext) -> OperationContext,
    ) {
        val gate = gate()
        gate.withBoundPermit(Any(), original) { permit ->
            val invalid = derivative(original)
            rejected { permit.requireLive(invalid) }
            rejected { gate.assertIssuedHere(permit, invalid) }
            gate.assertIssuedHere(permit, original)
        }
    }

    private fun stoppedWaiter(
        code: SaveError,
        expectInterrupted: Boolean = false,
        stop: (ObservedToken, AtomicLong, Worker) -> Unit,
    ) {
        val now = AtomicLong(); val gate = DirectLocalProcessingGate(EpochClock { now.get() })
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val ownerContext = operation()
        val captured = AtomicReference<DirectLocalProcessingGate.LocalPermit?>()
        val first = Worker { gate.withBoundPermit(Any(), ownerContext) { permit ->
            captured.set(permit); entered.countDown(); await(finish); 1
        } }
        await(entered)
        val token = ObservedToken()
        val second = Worker { gate.withBoundPermit(Any(), operation(token)) { fail("Stopped waiter entered"); 2 } }
        try {
            await(token.checked); stop(token, now, second)
            val result = second.result()
            assertTrue(result is SaveFailure); assertEquals(code, (result as SaveFailure).code)
            assertEquals(expectInterrupted, second.interrupted.get())
            gate.assertIssuedHere(captured.get()!!, ownerContext)
            assertEquals(1L, finish.count)
        } finally { finish.countDown() }
        assertEquals(1, first.value())
        rejected { captured.get()!!.requireLive(ownerContext) }
    }

    private class EqualToken : CancellationToken {
        override val isCancelled = false
        override fun equals(other: Any?): Boolean = other is EqualToken
        override fun hashCode(): Int = 1
    }

    private class ObservedToken : CancellationToken {
        val checked = CountDownLatch(1); val cancelled = AtomicBoolean()
        override val isCancelled: Boolean get() { checked.countDown(); return cancelled.get() }
    }

    private class Worker(block: () -> Int) {
        private val outcome = AtomicReference<Any?>(); private val done = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val thread = thread(isDaemon = true) {
            try { outcome.set(block()) } catch (failure: Throwable) { outcome.set(failure) }
            finally { interrupted.set(Thread.currentThread().isInterrupted); done.countDown() }
        }
        fun result(): Any? { await(done); thread.join(3000); assertFalse(thread.isAlive); return outcome.get() }
        fun value(): Int = result().let { if (it is Throwable) throw AssertionError(it); it as Int }
    }

    private companion object {
        fun gate() = DirectLocalProcessingGate(EpochClock { 0 })
        fun operation(token: CancellationToken = CancellationSource(), deadline: Long = 1000) =
            OperationContext("local", deadline, token, retryBudget = 1, resourceBudget = ResourceBudget(1, 100))
        fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }
        fun rejected(block: () -> Unit) = failure(SaveError.RECOVERY_REQUIRED, block)
        fun failure(code: SaveError, block: () -> Unit) {
            try { block(); fail("Expected $code") } catch (failure: SaveFailure) { assertEquals(code, failure.code) }
        }
    }
}
