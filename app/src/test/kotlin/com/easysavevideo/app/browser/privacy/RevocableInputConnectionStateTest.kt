package com.easysavevideo.app.browser.privacy

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Actual production completion and cleanup gates with fake ports; no Android thread claim. */
class RevocableInputConnectionStateTest {
    private fun token(): PrivateInputToken = PrivateInputLifetime(Any()).also { it.publish(Any()) }.acquire(Any())!!
    private class QueuedExecutor : Executor {
        val queue = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun run() { queue.toList().also { queue.clear() }.forEach(Runnable::run) }
    }

    @Test fun callbackUsesSuppliedExecutorAndRunsAtMostOnce() {
        val executor = QueuedExecutor(); val results = mutableListOf<String>()
        val gate = PrivateInputCompletion(token(), executor, { "cancelled" }, results::add)
        gate.complete("first"); gate.complete("second")
        assertTrue(results.isEmpty()); assertEquals(1, executor.queue.size)
        executor.run(); executor.run()
        assertEquals(listOf("first"), results)
    }

    @Test fun queuedSuccessBecomesPayloadFreeCancellationAfterRevoke() {
        val token = token(); val executor = QueuedExecutor(); val results = mutableListOf<String>()
        val gate = PrivateInputCompletion(token, executor, { "cancelled" }, results::add)
        gate.complete("fixture payload"); token.revoke(); executor.run()
        assertEquals(listOf("cancelled"), results)
    }

    @Test fun queuedSuccessBecomesCancellationWhenSameDelegateGetsNewLease() {
        val registry = PrivateInputDelegateLeases(); val delegate = Any(); val session = token()
        val oldLease = registry.acquire(delegate, session)!!
        val oldToken = session.fork(oldLease::isCurrent)!!
        val executor = QueuedExecutor(); val results = mutableListOf<String>()
        val gate = PrivateInputCompletion(oldToken, executor, { "cancelled" }, results::add)
        gate.complete("fixture payload")
        val freshLease = registry.acquire(delegate, session)!!
        executor.run()
        assertEquals(listOf("cancelled"), results)
        assertTrue(session.isCurrent()); assertTrue(freshLease.isCurrent())
    }

    @Test fun alreadyStaleCompletionStillUsesExecutorAndOnlyDeliversCancellation() {
        val token = token(); val executor = QueuedExecutor(); val results = mutableListOf<String>()
        token.revoke()
        val gate = PrivateInputCompletion(token, executor, { "cancelled" }, results::add)
        gate.complete("fixture payload")
        assertTrue(results.isEmpty()); executor.run()
        assertEquals(listOf("cancelled"), results)
    }

    @Test fun rejectedExecutorDoesNotFallBackToWrongThreadOrRetry() {
        val schedules = AtomicInteger(); val calls = AtomicInteger()
        val executor = Executor { schedules.incrementAndGet(); throw RejectedExecutionException() }
        val gate = PrivateInputCompletion(token(), executor, { 0 }) { calls.incrementAndGet() }
        gate.complete(1); gate.complete(2)
        assertEquals(1, schedules.get()); assertEquals(0, calls.get())
    }

    @Test fun executorThatQueuesThenRejectsCannotDeliverLater() {
        var pending: Runnable? = null; var calls = 0
        val executor = Executor { pending = it; throw RejectedExecutionException() }
        val gate = PrivateInputCompletion(token(), executor, { 0 }) { calls++ }
        gate.complete(1); pending!!.run()
        assertEquals(0, calls)
    }

    @Test fun misbehavingExecutorInvokingRunnableTwiceStillDeliversOnce() {
        var calls = 0
        val gate = PrivateInputCompletion(token(), Executor { it.run(); it.run() }, { 0 }) { calls++ }
        gate.complete(1)
        assertEquals(1, calls)
    }

    @Test(timeout = 5000) fun competingProviderCompletionsAreAtMostOnce() {
        val count = AtomicInteger()
        val gate = PrivateInputCompletion(token(), Executor(Runnable::run), { 0 }) { count.incrementAndGet() }
        val workers = (1..16).map { value -> thread(isDaemon = true) { gate.complete(value) } }
        workers.forEach { it.join(2000) }
        assertTrue(workers.none { it.isAlive }); assertEquals(1, count.get())
    }

    @Test fun closeRevokesImmediatelyButCleanupWaitsForOwnerPort() {
        val token = token(); val queue = mutableListOf<Runnable>(); var calls = 0
        val gate = PrivateInputCleanup(token, { queue.add(it); true }) { calls++ }
        gate.close(); gate.close()
        assertFalse(token.isCurrent()); assertEquals(0, calls); assertEquals(1, queue.size)
        queue.single().run(); queue.single().run()
        assertEquals(1, calls)
    }

    @Test fun rejectedOwnerScheduleNeverRunsCleanupOnCallingThread() {
        val token = token(); var schedules = 0; var calls = 0
        val gate = PrivateInputCleanup(token, { schedules++; false }) { calls++ }
        gate.close(); gate.close()
        assertFalse(token.isCurrent()); assertEquals(1, schedules); assertEquals(0, calls)
    }

    @Test fun cleanupCanReenterCloseWithoutLockOrDuplicateProviderClose() {
        val token = token(); var calls = 0
        lateinit var gate: PrivateInputCleanup
        gate = PrivateInputCleanup(token, { it.run(); true }) { calls++; gate.close() }
        gate.close()
        assertEquals(1, calls); assertFalse(token.isCurrent())
    }

    @Test(timeout = 5000) fun concurrentCloseOnlyRequestsOneOwnerCleanup() {
        val token = token(); val schedules = AtomicInteger(); val calls = AtomicInteger()
        val gate = PrivateInputCleanup(token, { schedules.incrementAndGet(); it.run(); true }) { calls.incrementAndGet() }
        val workers = (1..16).map { thread(isDaemon = true) { gate.close() } }
        workers.forEach { it.join(2000) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, schedules.get()); assertEquals(1, calls.get()); assertFalse(token.isCurrent())
    }
}
