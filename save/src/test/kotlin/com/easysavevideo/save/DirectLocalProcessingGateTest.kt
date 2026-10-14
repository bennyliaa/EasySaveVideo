package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DirectLocalProcessingGateTest {
    @Test fun serializesWholeLocalPipelineWithoutSchedulingAnotherWorker() {
        val gate = DirectLocalProcessingGate(EpochClock { 0 })
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val checks = ObservedToken(); val calls = AtomicInteger()
        val first = Worker { gate.withPermit(Any(), operation()) {
            calls.incrementAndGet(); entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); 1
        } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = Worker { val originalThread = Thread.currentThread()
            gate.withPermit(Any(), operation(checks)) { assertSame(originalThread, Thread.currentThread()); calls.incrementAndGet(); 2 }
        }
        try { assertTrue(checks.checked.await(3, TimeUnit.SECONDS)); assertEquals(1, calls.get()) }
        finally { finish.countDown() }
        assertEquals(1, first.value()); assertEquals(2, second.value())
    }

    @Test fun sameOwnerReentryRejectsWithoutDeadlockOrRetiringOuterPermit() {
        val gate = DirectLocalProcessingGate(EpochClock { 0 }); val owner = Any()
        assertEquals(7, gate.withPermit(owner, operation()) {
            failure(SaveError.BUSY) { gate.withPermit(owner, operation()) { fail("Reentered") } }; 7
        })
        assertEquals(8, gate.withPermit(owner, operation()) { 8 })
    }

    @Test fun ordinaryFailureReleasesOnlyItsLexicalPermit() {
        val gate = DirectLocalProcessingGate(EpochClock { 0 })
        failure(SaveError.STORAGE_FAILURE) { gate.withPermit(Any(), operation()) { throw SaveFailure(SaveError.STORAGE_FAILURE) } }
        assertEquals(9, gate.withPermit(Any(), operation()) { 9 })
    }

    @Test fun releaseUncertaintyContinuesToBlockAfterBlockReturnsUntilActualProbeClears() {
        val draining = AtomicBoolean(false)
        val gate = DirectLocalProcessingGate(EpochClock { 0 }, draining::get)
        gate.withPermit(Any(), operation()) { draining.set(true) }
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { gate.withPermit(Any(), operation()) { fail("Entered") } }
        draining.set(false)
        assertEquals(1, gate.withPermit(Any(), operation()) { 1 })
    }

    @Test fun stoppedIdleCallerNeverEnters() {
        val gate = DirectLocalProcessingGate(EpochClock { 10 })
        val cancelled = CancellationSource().also { it.cancel() }
        failure(SaveError.CANCELLED) { gate.withPermit(Any(), operation(cancelled)) { fail("Entered") } }
        failure(SaveError.DEADLINE_EXCEEDED) { gate.withPermit(Any(), operation(deadline = 10)) { fail("Entered") } }
    }

    @Test fun cancelledWaiterDoesNotRetireActiveOwner() = waitingFailure(SaveError.CANCELLED) { token, _, _ -> token.cancelled.set(true) }
    @Test fun expiredWaiterDoesNotRenewDeadline() = waitingFailure(SaveError.DEADLINE_EXCEEDED) { _, now, _ -> now.set(1000) }
    @Test fun interruptedWaiterPreservesInterrupt() = waitingFailure(SaveError.CANCELLED, expectInterrupted = true) { _, _, worker -> worker.thread.interrupt() }

    @Test fun publicationResultIsNotReclassifiedByLateCancellation() {
        val gate = DirectLocalProcessingGate(EpochClock { 0 }); val cancellation = CancellationSource()
        assertEquals("durable-success", gate.withPermit(Any(), operation(cancellation)) {
            cancellation.cancel(); "durable-success"
        })
    }

    @Test fun failureToReadRecoveryGateFailsClosedBeforeBlock() {
        val gate = DirectLocalProcessingGate(EpochClock { 0 }) { error("unavailable") }
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { gate.withPermit(Any(), operation()) { fail("Entered") } }
        val cancelled = CancellationSource().also { it.cancel() }
        failure(SaveError.CANCELLED) { gate.withPermit(Any(), operation(cancelled)) { fail("Entered") } }
    }

    @Test fun postAcquireRevalidationDoesNotLeakPermit() {
        val count = AtomicInteger(); val token = object : CancellationToken {
            override val isCancelled get() = count.incrementAndGet() >= 2
        }
        val gate = DirectLocalProcessingGate(EpochClock { 0 })
        failure(SaveError.CANCELLED) { gate.withPermit(Any(), operation(token)) { fail("Entered") } }
        assertEquals(3, gate.withPermit(Any(), operation()) { 3 })
    }

    private fun waitingFailure(code: SaveError, expectInterrupted: Boolean = false, stop: (ObservedToken, AtomicLong, Worker) -> Unit) {
        val now = AtomicLong(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val gate = DirectLocalProcessingGate(EpochClock { now.get() }); val token = ObservedToken()
        val first = Worker { gate.withPermit(Any(), operation()) { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); 1 } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = Worker { gate.withPermit(Any(), operation(token)) { fail("Stopped waiter entered"); 2 } }
        try {
            assertTrue(token.checked.await(3, TimeUnit.SECONDS)); stop(token, now, second)
            val result = second.result(); assertTrue(result is SaveFailure); assertEquals(code, (result as SaveFailure).code)
            assertEquals(expectInterrupted, second.interrupted.get())
            assertEquals(1L, finish.count)
        } finally { finish.countDown() }
        assertEquals(1, first.value())
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
        fun result(): Any? { assertTrue(done.await(3, TimeUnit.SECONDS)); thread.join(3000); assertFalse(thread.isAlive); return outcome.get() }
        fun value(): Int = result().let { if (it is Throwable) throw AssertionError(it); it as Int }
    }
    private companion object {
        fun operation(token: CancellationToken = CancellationSource(), deadline: Long = 1000) = OperationContext("local", deadline, token)
        fun failure(code: SaveError, block: () -> Unit) {
            try { block(); fail("Expected $code") } catch (failure: SaveFailure) { assertEquals(code, failure.code) }
        }
    }
}
