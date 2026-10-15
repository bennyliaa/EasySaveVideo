package com.easysavevideo.app.media.transform

import com.easysavevideo.core.model.OperationStopReason
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual session/lane ownership; synthetic retention callbacks are not native/device evidence. */
class TransformerWriterRetentionTest {
    @Test fun unenteredOrStoppedPreAcquisitionRejectionNeverCallsTheTargetOrPoisonsCleanup() {
        val outside = session()
        val untouched = Target { fail("Unentered session must not acquire"); AutoCloseable { } }
        assertThrows(TransformerFailure::class.java) { outside.retainWriter(untouched) }
        assertEquals(0, untouched.acquisitions.get())
        assertTrue(outside.finish())
        withOwner { owner ->
            val stopThread = AtomicReference<Thread?>()
            val primary = TransformerStopped(OperationStopReason.CANCELLED)
            val current = session(checkpoint = { if (Thread.currentThread() === stopThread.get()) throw primary })
            val result = executeTransformerSession(owner, current) {
                stopThread.set(Thread.currentThread())
                current.retainWriter(untouched)
            } as TransformerOutcome.Failed
            assertSame(primary, result.primary)
            assertEquals(TransformerRelease.CONFIRMED, result.release)
            assertEquals(0, untouched.acquisitions.get())
            assertFalse(current.cleanupFailed()); assertFalse(owner.fence.occupied)
        }
    }

    @Test fun exactTargetOneShotAcquisitionReleasesOnlyInsideTheSameWorkersCleanup() = withOwner { owner ->
        val current = session(); val foreign = session()
        val released = AtomicInteger(); val other = Target { AutoCloseable { fail("Foreign release") } }
        lateinit var target: Target
        target = Target {
            assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
            assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
            assertThrows(TransformerFailure::class.java) { current.retainWriter(target) } // Pending slot is already consumed.
            AutoCloseable {
                assertFalse(Thread.holdsLock(current))
                current.requireWriterRelease(target)
                assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(other) }
                assertThrows(TransformerFailure::class.java) { foreign.requireWriterRelease(target) }
                get(start {
                    assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
                    assertNotNull(current.requireWriterFinalization(target)) // Callback may finalize, never release.
                })
                released.incrementAndGet()
            }
        }
        assertTrue(target == other) // Hostile equals cannot substitute for exact reference identity.
        val result = executeTransformerSession(owner, current) {
            assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
            current.retainWriter(target)
            assertNull(current.requireWriterFinalization(target))
            assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(other) }
            assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
            assertThrows(TransformerFailure::class.java) { current.retainWriter(target) }
            assertThrows(TransformerFailure::class.java) { current.retainWriter(other) }
            "value"
        }
        assertEquals("value", (result as TransformerOutcome.Completed).value)
        assertEquals(1, target.acquisitions.get()); assertEquals(0, other.acquisitions.get())
        assertEquals(1, released.get()); assertTrue(current.releaseAcknowledged())
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.retainWriter(target) }
        Unit
    }

    @Test fun positivelyReturnedRetentionIsStoredBeforeThePostAcquisitionStopCheck() = withOwner { owner ->
        val stopThread = AtomicReference<Thread?>()
        val primary = TransformerStopped(OperationStopReason.DEADLINE_EXCEEDED)
        val current = session(checkpoint = { if (Thread.currentThread() === stopThread.get()) throw primary })
        val released = AtomicInteger()
        lateinit var target: Target
        target = Target {
            stopThread.set(Thread.currentThread())
            AutoCloseable { current.requireWriterRelease(target); released.incrementAndGet() }
        }
        val result = executeTransformerSession(owner, current) { current.retainWriter(target) } as TransformerOutcome.Failed
        assertSame(primary, result.primary); assertEquals(TransformerRelease.CONFIRMED, result.release)
        assertEquals(1, target.acquisitions.get()); assertEquals(1, released.get())
        assertTrue(current.releaseAcknowledged()); assertFalse(owner.fence.occupied)
    }

    @Test fun postPinAcquisitionThrowRemainsUnknownAndCannotReleaseOtherNativeFences() = withOwner { owner ->
        val current = session(); val pins = AtomicInteger(); val sourceReleases = AtomicInteger()
        val primary = AssertionError("synthetic retention construction failure")
        val target = Target { pins.incrementAndGet(); throw primary }
        val source = source { sourceReleases.incrementAndGet() }
        val result = executeTransformerSession(owner, current) {
            current.retain(source)
            current.retainWriter(target)
        } as TransformerOutcome.Failed
        assertSame(primary, result.primary); assertEquals(TransformerRelease.UNCONFIRMED, result.release)
        assertEquals(1, target.acquisitions.get()); assertEquals(1, pins.get()); assertEquals(0, sourceReleases.get())
        assertTrue(current.cleanupFailed()); assertFalse(current.releaseAcknowledged()); assertTrue(owner.fence.requiresRestart)
        assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        assertThrows(TransformerFailure::class.java) { current.retainWriter(target) }
        Unit
    }

    @Test fun evenAnEarlyTargetThrowWithoutPositiveNoAcquisitionEvidenceIsUnknown() = withOwner { owner ->
        val current = session(); val primary = IllegalStateException("synthetic target rejection")
        val target = Target { throw primary }
        val result = executeTransformerSession(owner, current) { current.retainWriter(target) } as TransformerOutcome.Failed
        assertSame(primary, result.primary); assertEquals(TransformerRelease.UNCONFIRMED, result.release)
        assertEquals(1, target.acquisitions.get()); assertTrue(current.cleanupFailed()); assertTrue(owner.fence.requiresRestart)
    }

    @Test fun writerReleasePrecedesSourceReleaseAndNeverHoldsTheSessionMonitor() = withOwner { owner ->
        val current = session(); val order = ArrayList<String>()
        lateinit var target: Target
        target = Target { AutoCloseable { assertFalse(Thread.holdsLock(current)); current.requireWriterRelease(target); order.add("writer") } }
        lateinit var input: TransformerOwnedSource
        input = source {
            assertFalse(Thread.holdsLock(current)); current.requireSourceRelease(input)
            assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
            order.add("source")
        }
        assertTrue(executeTransformerSession(owner, current) { current.retain(input); current.retainWriter(target) } is TransformerOutcome.Completed)
        assertEquals(listOf("writer", "source"), order)
    }

    @Test fun throwingWriterReleaseRetainsTheRecordAndStopsSourceRelease() = withOwner { owner ->
        val current = session(); val closes = AtomicInteger(); val sourceReleases = AtomicInteger()
        lateinit var target: Target
        target = Target { AutoCloseable { current.requireWriterRelease(target); closes.incrementAndGet(); throw AssertionError("synthetic close") } }
        val input = source { sourceReleases.incrementAndGet() }
        val result = executeTransformerSession(owner, current) { current.retain(input); current.retainWriter(target) } as TransformerOutcome.Failed
        assertEquals(TransformerRelease.UNCONFIRMED, result.release)
        assertEquals(1, closes.get()); assertEquals(0, sourceReleases.get())
        assertTrue(current.cleanupFailed()); assertFalse(current.releaseAcknowledged())
        assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        assertThrows(TransformerFailure::class.java) { current.finish() }
        assertEquals(1, closes.get())
        Unit
    }

    @Test fun establishedCleanupDeadlineIsReadOnlyFixedAndExactTargetBound() = withOwner { owner ->
        val current = session(); val other = Target { AutoCloseable { } }
        val observed = AtomicReference<Long?>()
        lateinit var target: Target
        target = Target { AutoCloseable {
            current.requireWriterRelease(target)
            val first = requireNotNull(current.requireWriterFinalization(target)); observed.set(first)
            assertFalse(current.finish()) // A repeated begin cannot reset this cleanup budget.
            assertEquals(first, requireNotNull(current.requireWriterFinalization(target)))
            get(start { assertEquals(first, requireNotNull(current.requireWriterFinalization(target))) })
            assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(other) }
        } }
        val before = System.nanoTime()
        assertTrue(executeTransformerSession(owner, current) { current.retainWriter(target) } is TransformerOutcome.Completed)
        val after = System.nanoTime()
        val deadline = requireNotNull(observed.get())
        val timeout = TimeUnit.MILLISECONDS.toNanos(current.releaseTimeoutMillis)
        assertTrue(deadline >= before + timeout); assertTrue(deadline <= after + timeout)
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        Unit
    }

    @Test fun lateAcquisitionAfterFinishTimedOutCannotResurrectARetiredReleaseScope() = withOwner { owner ->
        val current = session(releaseTimeoutMillis = 50)
        val acquiring = CountDownLatch(1); val returnRetention = CountDownLatch(1); val closes = AtomicInteger()
        val lateRetention = AtomicReference<AutoCloseable?>()
        lateinit var target: Target
        target = Target {
            acquiring.countDown(); await(returnRetention)
            AutoCloseable { current.requireWriterRelease(target); closes.incrementAndGet() }.also { lateRetention.set(it) }
        }
        var acquisition: CompletableFuture<Unit>? = null
        val result = try {
            executeTransformerSession(owner, current) {
                acquisition = start { current.retainWriter(target) }
                await(acquiring)
            } as TransformerOutcome.Failed
        } finally { returnRetention.countDown() }
        assertEquals(TransformerRelease.UNCONFIRMED, result.release)
        val late = assertThrows(java.util.concurrent.ExecutionException::class.java) { get(requireNotNull(acquisition)) }
        assertTrue(late.cause is TransformerFailure)
        assertEquals(0, closes.get()); assertFalse(current.releaseAcknowledged()); assertTrue(owner.fence.requiresRestart)
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.finish() }
        assertThrows(TransformerFailure::class.java) { requireNotNull(lateRetention.get()).close() }
        assertEquals(0, closes.get())
        Unit
    }

    @Test fun exactRetentionReleasesOnlyAfterExistingNativeReleaseChecksPass() = withOwner { owner ->
        val current = session(releaseTimeoutMillis = 50); val closes = AtomicInteger()
        lateinit var target: Target
        target = Target { AutoCloseable { current.requireWriterRelease(target); closes.incrementAndGet() } }
        val result = executeTransformerSession(owner, current) {
            current.retainWriter(target)
            current.muxerCreated = true // No positive logical muxer close acknowledgement.
        } as TransformerOutcome.Failed
        assertEquals(TransformerRelease.UNCONFIRMED, result.release); assertEquals(0, closes.get())
        current.muxerReleased = true // A late callback after worker retirement cannot grant release.
        assertThrows(TransformerFailure::class.java) { session().requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterRelease(target) }
        assertThrows(TransformerFailure::class.java) { current.requireWriterFinalization(target) }
        assertThrows(TransformerFailure::class.java) { current.finish() }
        assertEquals(0, closes.get()); assertTrue(owner.fence.requiresRestart)
        Unit
    }

    @Test fun cancellationAndFinishFreezeTheSameDeadlineBeforePostingCleanup() {
        val text = File("src/main/kotlin/com/easysavevideo/app/media/transform/TransformerSession.kt").readText()
        val abandon = text.substringAfter("override fun abandon() {").substringBefore("fun postCancel()")
        assertTrue(abandon.indexOf("freezeCleanupDeadline()") >= 0)
        assertTrue(abandon.indexOf("freezeCleanupDeadline()") < abandon.indexOf("abandoned.set(true)"))
        val cancel = text.substringAfter("fun postCancel() {").substringBefore("private fun nativeReleaseConfirmed")
        assertTrue(cancel.indexOf("freezeCleanupDeadline()") >= 0)
        assertTrue(cancel.indexOf("freezeCleanupDeadline()") < cancel.indexOf("cancelPosted.compareAndSet"))
        assertTrue(cancel.indexOf("freezeCleanupDeadline()") < cancel.indexOf("handler.post"))
        val finish = text.substringAfter("override fun finish(): Boolean {")
        assertTrue(finish.indexOf("freezeCleanupDeadline()") >= 0)
        assertTrue(finish.indexOf("freezeCleanupDeadline()") < finish.indexOf("postCancel()"))
        assertFalse(text.contains("var nativeWriter:"))
        val muxer = File("src/main/kotlin/com/easysavevideo/app/media/transform/TransformerBoundedMuxer.kt").readText()
        assertTrue(muxer.indexOf("target.openEmptyWriter()") >= 0)
        assertTrue(muxer.indexOf("session.retainWriter(target)") >= 0)
        assertTrue(muxer.indexOf("target.openEmptyWriter()") < muxer.indexOf("session.retainWriter(target)"))
        assertFalse(muxer.contains("target.retainNativeWriter()"))
    }

    private class Target(private val acquire: () -> AutoCloseable) : TransformerOwnedTarget {
        val acquisitions = AtomicInteger()
        override val muxerToken = "test-output"
        override fun verifyIdentity() = Unit
        override fun sizeBytes() = 0L
        override fun availableBytes() = Long.MAX_VALUE
        override fun retainNativeWriter(): AutoCloseable { acquisitions.incrementAndGet(); return acquire() }
        override fun openEmptyWriter(): TransformerWriter = throw AssertionError("No writer should be opened")
        override fun equals(other: Any?) = other is Target
        override fun hashCode() = 1
    }
    private fun source(close: () -> Unit) = object : TransformerOwnedSource {
        override fun retainNativeReader() = AutoCloseable(close)
        override fun openReader(): TransformerReadHandle = throw AssertionError("No reader should be opened")
    }
    private fun session(releaseTimeoutMillis: Long = 2_000, checkpoint: () -> Unit = {}) = TransformerSession(
        TransformerSessionLimits(30_000, 30_000, releaseTimeoutMillis, 8, 4, 32L * 1024 * 1024 * 1024, 1, 2, 1), checkpoint, { null })
    private fun <T> start(work: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
        Thread({ try { result.complete(work()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }, "transform-retention-callback")
            .apply { isDaemon = true; start() }
    }
    private fun <T> get(result: CompletableFuture<T>): T = result.get(5, TimeUnit.SECONDS)
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
    private fun <T> withOwner(work: (TransformerLaneOwner) -> T): T {
        val owner = TransformerLaneOwner()
        return try { work(owner) } finally { owner.lane.shutdownNow(); owner.lane.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
