package com.easysavevideo.app.media.transform

import com.easysavevideo.core.model.OperationStopReason
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real production runner algorithm + real queue/fence; controlled cleanup is not native evidence. */
class TransformerSessionExecutionTest {
    @Test fun workFailureSurvivesConfirmedCleanupAndFutureTransport() = withOwner { owner ->
        val session = ControlledSession(); val primary = IllegalStateException("technical")
        val result = executeTransformerSession(owner, session) { throw ExecutionException(primary) } as TransformerOutcome.Failed
        assertSame(primary, result.primary); assertEquals(TransformerRelease.CONFIRMED, result.release)
        assertEquals(1, session.finishes.get()); assertFalse(owner.fence.occupied)
    }
    @Test fun everyStopReasonSurvivesConfirmedAndUnknownCleanup() {
        for (reason in OperationStopReason.entries) for (confirmed in listOf(true, false)) withOwner { owner ->
            val session = ControlledSession(confirmed); val primary = TransformerStopped(reason)
            val result = executeTransformerSession(owner, session) { throw primary } as TransformerOutcome.Failed
            assertSame(primary, result.primary)
            assertEquals(if (confirmed) TransformerRelease.CONFIRMED else TransformerRelease.UNCONFIRMED, result.release)
            assertEquals(!confirmed, owner.fence.requiresRestart)
        }
    }
    @Test fun successCannotEscapeBeforeActualCleanupAcknowledgment() = withOwner { owner ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val session = ControlledSession(onFinish = { entered.countDown(); await(release) })
        val result = start { executeTransformerSession(owner, session) { "value" } }
        await(entered); assertFalse(result.isDone); release.countDown()
        assertEquals("value", (get(result) as TransformerOutcome.Completed<*>).value)
        assertFalse(owner.fence.occupied)
    }
    @Test fun finalizationOnlyFailureCannotBecomeSuccessAfterCleanup() = withOwner { owner ->
        val session = ControlledSession(contentFailureAtFinish = true)
        val result = executeTransformerSession(owner, session) { "must not escape" } as TransformerOutcome.Failed
        assertEquals(TransformerError.EXECUTION_FAILED, (result.primary as TransformerFailure).code)
        assertEquals(TransformerRelease.CONFIRMED, result.release)
    }
    @Test fun explicitEnqueueRejectionReleasesOnlyTheUnstartedClaim() = withOwner { owner ->
        owner.lane.shutdown()
        val session = ControlledSession(); var work = 0
        val result = executeTransformerSession(owner, session) { work++ } as TransformerOutcome.Failed
        assertEquals(TransformerError.CAPACITY, (result.primary as TransformerFailure).code)
        assertEquals(TransformerRelease.CONFIRMED, result.release)
        assertEquals(0, work); assertEquals(0, session.finishes.get()); assertFalse(owner.fence.occupied)
    }
    @Test fun unknownSubmissionThrowRetainsSameOwnerAndPrimaryWithoutReplacement() = withOwner { owner ->
        val primary = AssertionError("synthetic thread creation failure")
        owner.lane.threadFactory = java.util.concurrent.ThreadFactory { throw primary }
        val session = ControlledSession(releaseTimeoutMillis = 50)
        val result = executeTransformerSession(owner, session) { fail("No work accepted") } as TransformerOutcome.Failed
        assertSame(primary, result.primary); assertEquals(TransformerRelease.UNCONFIRMED, result.release)
        assertEquals(0L, session.abandoned.count); assertTrue(owner.fence.occupied); assertTrue(owner.fence.requiresRestart)
        assertFalse(owner.fence.claim(ControlledSession()))
        owner.fence.release(session); assertTrue(owner.fence.occupied) // Same retained owner cannot retire.
    }
    @Test fun queuedCancellationAcknowledgesTheOriginalWorkerWithoutStartingWork() = withOwner { owner ->
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        owner.lane.execute { blocked.countDown(); await(release) }; await(blocked)
        val session = ControlledSession(); val work = AtomicInteger()
        val result = start { executeTransformerSession(owner, session) { work.incrementAndGet() } }
        await(session.waiting)
        val primary = TransformerStopped(OperationStopReason.CANCELLED); session.stop.set(primary)
        await(session.abandoned); release.countDown()
        val failed = get(result) as TransformerOutcome.Failed
        assertSame(primary, failed.primary); assertEquals(TransformerRelease.CONFIRMED, failed.release)
        assertEquals(0, work.get()); assertNull(session.entry); assertEquals(1, session.finishes.get()); assertFalse(owner.fence.occupied)
    }
    @Test fun lateSuccessfulWorkCannotOverrideCallerCancellation() = withOwner { owner ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val session = ControlledSession()
        val result = start { executeTransformerSession(owner, session) { entered.countDown(); await(release); "late" } }
        await(entered)
        val primary = TransformerStopped(OperationStopReason.CANCELLED); session.stop.set(primary)
        await(session.abandoned); release.countDown()
        val failed = get(result) as TransformerOutcome.Failed
        assertSame(primary, failed.primary); assertEquals(TransformerRelease.CONFIRMED, failed.release)
        assertEquals(1, session.finishes.get()); assertFalse(owner.fence.occupied)
    }
    @Test fun unacknowledgedWorkerBlocksBothCallerKindsEvenAfterLateReturn() = withOwner { owner ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val session = ControlledSession(releaseTimeoutMillis = 50)
        val result = start { executeTransformerSession(owner, session) { entered.countDown(); await(release); "late" } }
        await(entered); val primary = TransformerStopped(OperationStopReason.DEADLINE_EXCEEDED); session.stop.set(primary)
        val failed = get(result) as TransformerOutcome.Failed
        assertSame(primary, failed.primary); assertEquals(TransformerRelease.UNCONFIRMED, failed.release)
        assertTrue(owner.fence.occupied); assertTrue(owner.fence.requiresRestart)
        release.countDown(); await(session.finished)
        for (caller in listOf("Editor", "Direct")) {
            val denied = executeTransformerSession(owner, ControlledSession()) { fail(caller) } as TransformerOutcome.Failed
            assertEquals(TransformerRelease.UNCONFIRMED, denied.release)
        }
        assertTrue(owner.fence.occupied)
    }
    @Test fun workerEntryIsExactAndRetiresBeforeReturnedSuccess() = withOwner { owner ->
        val session = ControlledSession()
        val other = ControlledSession()
        val result = executeTransformerSession(owner, session) {
            session.entry!!.requireActive(session)
            session.entry!!.requireActiveOrCleaning(session)
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireActive(other) }
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireActiveOrCleaning(other) }
            7
        }
        assertEquals(7, (result as TransformerOutcome.Completed).value)
        assertThrows(TransformerFailure::class.java) { session.entry!!.requireActive(session) }
        assertThrows(TransformerFailure::class.java) { session.entry!!.requireActiveOrCleaning(session) }
        Unit
    }
    @Test fun cleanupPhaseRevokesWorkingEntryButPermitsExactWorkerRelease() = withOwner { owner ->
        lateinit var session: ControlledSession
        session = ControlledSession(onFinish = {
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireActive(session) }
            session.entry!!.requireActiveOrCleaning(session)
            session.entry!!.requireCleanup(session)
            get(start {
                session.entry!!.requireActiveOrCleaning(session)
                assertThrows(TransformerFailure::class.java) { session.entry!!.requireCleanup(session) }
            })
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireCleanup(ControlledSession()) }
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireActiveOrCleaning(ControlledSession()) }
        })
        assertTrue(executeTransformerSession(owner, session) { Unit } is TransformerOutcome.Completed)
        assertThrows(TransformerFailure::class.java) { session.entry!!.requireCleanup(session) }
        assertThrows(TransformerFailure::class.java) { session.entry!!.requireActiveOrCleaning(session) }
        Unit
    }
    @Test fun callerTimeoutDoesNotRetireTheStillCleaningWorkerButLateReturnCannotReopenTheLane() = withOwner { owner ->
        val cleaning = CountDownLatch(1); val release = CountDownLatch(1)
        val pins = AtomicInteger(1)
        lateinit var session: ControlledSession
        session = ControlledSession(releaseTimeoutMillis = 50, onFinish = {
            cleaning.countDown(); await(release)
            session.entry!!.requireCleanup(session)
            assertEquals(1, pins.getAndDecrement())
        })
        val result = start { executeTransformerSession(owner, session) { Unit } }
        var original: TransformerOutcome.Failed? = null
        try {
            await(cleaning)
            val primary = TransformerStopped(OperationStopReason.CANCELLED); session.stop.set(primary)
            val failed = get(result) as TransformerOutcome.Failed
            original = failed
            assertSame(primary, failed.primary); assertEquals(TransformerRelease.UNCONFIRMED, failed.release)
            assertEquals(1, pins.get())
            session.entry!!.requireActiveOrCleaning(session)
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireCleanup(session) }
            assertTrue(owner.fence.requiresRestart)
        } finally { release.countDown() }
        // A barrier on the same production lane runs only after its actual finally retires entry.
        get(CompletableFuture<Unit>().also { barrier -> owner.lane.execute { barrier.complete(Unit) } })
        assertThrows(TransformerFailure::class.java) { session.entry!!.requireActiveOrCleaning(session) }
        assertEquals(0, pins.get())
        assertSame(original, get(result))
        assertEquals(TransformerRelease.UNCONFIRMED, requireNotNull(original).release)
        assertTrue(owner.fence.requiresRestart)
        assertTrue(owner.fence.occupied)
        Unit
    }
    @Test fun workerEntryRetiresAfterWorkAndFinishFailures() {
        for (finishFailure in listOf(false, true)) withOwner { owner ->
            val primary = IllegalStateException("technical")
            val session = ControlledSession(onFinish = { if (finishFailure) throw primary })
            val result = executeTransformerSession(owner, session) { if (!finishFailure) throw primary } as TransformerOutcome.Failed
            assertSame(primary, result.primary)
            assertThrows(TransformerFailure::class.java) { session.entry!!.requireActive(session) }
        }
    }
    @Test fun pendingAndExceptionalCurrentFuturesCannotBorrowHistoricalReleaseAcknowledgment() {
        for (exceptional in listOf(false, true)) withOwner { owner ->
            val session = ControlledSession(releaseTimeoutMillis = 1, historicalAcknowledged = true)
            assertTrue(owner.fence.claim(session))
            val current = CompletableFuture<Any>()
            if (exceptional) current.completeExceptionally(IllegalStateException("technical"))
            val primary = TransformerStopped(OperationStopReason.CANCELLED)
            val method = Class.forName("com.easysavevideo.app.media.transform.TransformerSessionExecutionKt")
                .getDeclaredMethod("stoppedTransformerOutcome", TransformerLaneOwner::class.java,
                    TransformerSessionControl::class.java, CompletableFuture::class.java, Throwable::class.java)
                .apply { isAccessible = true }
            val result = method.invoke(null, owner, session, current, primary) as TransformerOutcome.Failed
            assertSame(primary, result.primary); assertEquals(TransformerRelease.UNCONFIRMED, result.release)
            assertTrue(owner.fence.occupied); assertTrue(owner.fence.requiresRestart)
        }
    }
    @Test fun alreadyFinishedEmptySessionCannotEnterOrReuseHistoricalReleaseAcknowledgment() = withOwner { owner ->
        val session = actualSession()
        assertTrue(session.finish()); assertTrue(session.releaseAcknowledged())
        var work = false
        val result = executeTransformerSession(owner, session) { work = true } as TransformerOutcome.Failed
        assertFalse(work); assertFalse(owner.fence.occupied)
        assertEquals(TransformerError.INVALID_STATE, (result.primary as TransformerFailure).code)
        assertEquals(TransformerRelease.CONFIRMED, result.release) // Rejected before enqueue, not old ack.
        assertThrows(TransformerFailure::class.java) { session.requireEntered() }
        Unit
    }
    @Test fun deniedEarlyFinishDoesNotConsumeTheActualWorkersCleanup() = withOwner { owner ->
        val session = actualSession()
        val result = executeTransformerSession(owner, session) {
            session.requireEntered()
            assertThrows(TransformerFailure::class.java) { session.finish() }
            session.requireEntered()
            9
        }
        assertEquals(9, (result as TransformerOutcome.Completed).value)
        assertTrue(session.releaseAcknowledged())
    }
    private fun actualSession() = TransformerSession(
        TransformerSessionLimits(30_000, 30_000, 2_000, 8, 4, 32L * 1024 * 1024 * 1024, 1, 2, 1), {}, { null })
    @Test fun directUnissuedGateRejectsWithoutTouchingAnyCapability() {
        val spec = TransformerRunSpec.DirectVp8Opus(TransformerDirectLoaderBinding.Unissued,
            TransformerOutputSpec(2, 2, 1, 3_000_000, 1_001_000, 0, 3, null, 1024, 0, 256_000, 0))
        var resources = 0
        try { requireIssuedTransformerRun(spec); resources++; fail("Direct must remain unavailable") }
        catch (failure: TransformerFailure) { assertEquals(TransformerError.NOT_ISSUED, failure.code) }
        assertEquals(0, resources)
    }
    private class ControlledSession(
        private val confirmed: Boolean = true,
        private val contentFailureAtFinish: Boolean = false,
        private val onFinish: () -> Unit = { },
        override val releaseTimeoutMillis: Long = 2_000,
        historicalAcknowledged: Boolean = false,
    ) : TransformerSessionControl {
        var entry: TransformerWorkerEntry? = null
        override fun enterWorker(entry: TransformerWorkerEntry) { entry.requireActive(this); check(this.entry == null); this.entry = entry }
        val stop = AtomicReference<Throwable?>()
        val finishes = AtomicInteger()
        val waiting = CountDownLatch(1)
        val abandoned = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val checks = AtomicInteger()
        private val acknowledged = AtomicBoolean(historicalAcknowledged)
        private val closeFailed = AtomicBoolean(false)
        private val contentFailed = AtomicBoolean(false)
        override fun checkpoint() { if (checks.incrementAndGet() >= 2) waiting.countDown(); stop.get()?.let { throw it } }
        override fun abandon() { abandoned.countDown() }
        override fun endDiagnostics() = Unit
        override fun finish(): Boolean {
            finishes.incrementAndGet(); onFinish(); contentFailed.set(contentFailureAtFinish)
            acknowledged.set(confirmed); finished.countDown(); return confirmed
        }
        override fun recordCleanupFailure() { closeFailed.set(true) }
        override fun cleanupFailed(): Boolean = closeFailed.get()
        override fun contentFailed(): Boolean = contentFailed.get()
        override fun releaseAcknowledged(): Boolean = acknowledged.get()
    }
    private fun <T> start(work: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
        Thread({ try { result.complete(work()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }, "transform-test-caller")
            .apply { isDaemon = true; start() }
    }
    private fun <T> get(result: CompletableFuture<T>): T = result.get(5, TimeUnit.SECONDS)
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
    private fun <T> withOwner(work: (TransformerLaneOwner) -> T): T {
        val owner = TransformerLaneOwner()
        return try { work(owner) } finally { owner.lane.shutdownNow(); owner.lane.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
