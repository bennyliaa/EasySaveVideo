package com.easysavevideo.discovery.thumbnail

import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.SessionStamp
import com.easysavevideo.discovery.VideoSearchResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real capability/session/cancellation integration; owned fixtures only, no Android or network. */
class DiscoveryThumbnailCancellationIntegrationTest {
    @Test fun sourceWithdrawalBeforeReadDoesNotInvokeTheRegisteredReader() {
        val current = AtomicBoolean(true)
        val calls = AtomicInteger()
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(), current::get) {
            calls.incrementAndGet()
            DiscoveryThumbnailRead.Released(Frame())
        }
        current.set(false)
        val read = cap.read(DiscoveryCancellation())
        assertEquals(DiscoveryThumbnailRead.Released(null), read)
        assertEquals(0, calls.get())
        assertTrue(cap.isRevoked)
    }

    @Test fun sourceWithdrawalAfterSessionAdmissionButBeforeWorkerAlsoBlocksTheReader() {
        val h = Harness()
        val current = AtomicBoolean(true)
        var reads = 0
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(h.binding, current::get) {
            reads++
            DiscoveryThumbnailRead.Released(Frame())
        }
        h.session.request(cap)
        assertTrue(h.session.hasActiveRead)
        assertEquals(1, h.workers.size)
        current.set(false)
        h.workers.removeFirst().run()
        h.ui.deliver()
        assertEquals(0, reads)
        assertEquals(0, h.framesDelivered)
        assertFalse(h.session.hasActiveRead)
        assertEquals(DiscoveryThumbnailStatus.UNAVAILABLE, h.session.status(h.binding))
        assertTrue(h.ui.timers.isEmpty())
    }

    @Test fun sourceWithdrawalBeforeSessionDeliveryDropsAndClosesTheAlreadyReadFrame() {
        val h = Harness()
        val current = AtomicBoolean(true)
        val frame = Frame()
        var reads = 0
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(h.binding, current::get) {
            reads++
            DiscoveryThumbnailRead.Released(frame)
        }
        h.session.request(cap)
        h.workers.removeFirst().run()
        assertEquals(1, reads)
        assertEquals(0, frame.closes.get())
        assertEquals(1, h.ui.pending.size)
        current.set(false)
        h.ui.deliver()
        assertEquals(0, h.framesDelivered)
        assertEquals(1, frame.closes.get())
        assertFalse(h.session.hasActiveRead)
        assertEquals(DiscoveryThumbnailStatus.UNAVAILABLE, h.session.status(h.binding))
        h.session.request(cap)
        assertTrue(h.workers.isEmpty())
    }

    @Test fun throwingSourceGuardFailsClosedWithoutInvokingTheReader() {
        var reads = 0
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(),
            { throw IllegalStateException("OWNED_GUARD_FAILURE") }) {
            reads++
            DiscoveryThumbnailRead.Released(Frame())
        }
        assertEquals(DiscoveryThumbnailRead.Released(null), cap.read(DiscoveryCancellation()))
        assertEquals(0, reads)
    }

    @Test(timeout = 20_000) fun nonUiCloseSignalsPromptlyButReaderExitStillWaitsForCancellationHookDrain() {
        verifyDrainOrdering(hookFirst = false)
    }

    @Test(timeout = 20_000) fun nonUiCloseAndCompletedCancellationHookCannotReleaseAStillBlockedReader() {
        verifyDrainOrdering(hookFirst = true)
    }

    private fun verifyDrainOrdering(hookFirst: Boolean) {
        val ownerThread = Thread.currentThread()
        val readerEntered = CountDownLatch(1)
        val readerExit = CountDownLatch(1)
        val readerExited = CountDownLatch(1)
        val hookEntered = CountDownLatch(1)
        val hookExit = CountDownLatch(1)
        val hookExited = CountDownLatch(1)
        val hookThread = AtomicReference<Thread?>()
        val hookFailure = AtomicReference<Throwable?>()
        val cancellation = DiscoveryCancellation()
        val readResult = AtomicReference<DiscoveryThumbnailRead?>()
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(), { true }) { actual ->
            assertSame(cancellation, actual)
            actual.onCancel {
                hookThread.set(Thread.currentThread())
                hookEntered.countDown()
                try { check(hookExit.await(5, TimeUnit.SECONDS)) { "Owned hook release timed out" } }
                catch (failure: Throwable) { hookFailure.set(failure) }
                finally { hookExited.countDown() }
            }
            readerEntered.countDown()
            try { check(readerExit.await(5, TimeUnit.SECONDS)) { "Owned reader release timed out" } }
            finally { readerExited.countDown() }
            DiscoveryThumbnailRead.Released(null)
        }
        val worker = Attempt("thumbnail-owned-reader") { readResult.set(cap.read(cancellation)) }
        val closer = Attempt("thumbnail-owned-non-ui-close") { cap.close() }
        worker.thread.start()
        try {
            await(readerEntered, "reader entry")
            closer.thread.start()
            await(closer.finished, "non-UI close return")
            assertNull(closer.failure.get())
            assertTrue(cancellation.isCancelled)
            await(hookEntered, "transport cancellation hook")
            assertNotSame(ownerThread, closer.thread)
            assertNotSame(ownerThread, hookThread.get())
            assertNotSame(closer.thread, hookThread.get())
            assertNotSame(worker.thread, hookThread.get())
            assertEquals(1L, readerExited.count)
            assertEquals(1L, hookExited.count)
            assertEquals(1L, worker.finished.count)

            if (hookFirst) {
                hookExit.countDown()
                await(hookExited, "hook exit")
                assertFalse("Hook exit alone released the reader", worker.finished.await(200, TimeUnit.MILLISECONDS))
                assertEquals(1L, readerExited.count)
                readerExit.countDown()
            } else {
                readerExit.countDown()
                await(readerExited, "reader exit")
                assertFalse("Reader exit returned before hook drain", worker.finished.await(200, TimeUnit.MILLISECONDS))
                assertFalse(cancellation.isDrained)
                assertEquals(1L, hookExited.count)
                hookExit.countDown()
            }
            await(worker.finished, "physical capability read return")
            await(hookExited, "hook exit")
            assertNull(worker.failure.get())
            assertNull(hookFailure.get())
            assertTrue(cancellation.isDrained)
            assertEquals(0L, readerExited.count)
            assertEquals(DiscoveryThumbnailRead.Released(null), readResult.get())
        } finally {
            readerExit.countDown()
            hookExit.countDown()
            cap.close()
            worker.thread.join(5_000)
            if (closer.thread.state != Thread.State.NEW) closer.thread.join(5_000)
        }
        assertFalse(worker.thread.isAlive)
        assertFalse(closer.thread.isAlive)
    }

    @Test(timeout = 15_000) fun thrownReaderAlsoWaitsForClaimedCancellationHookBeforePropagatingFailure() {
        val hookEntered = CountDownLatch(1)
        val hookExit = CountDownLatch(1)
        val hookExited = CountDownLatch(1)
        val hookFailure = AtomicReference<Throwable?>()
        val expected = IllegalStateException("OWNED_READER_FAILURE")
        val cancellation = DiscoveryCancellation()
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(), { true }) { actual ->
            actual.onCancel {
                hookEntered.countDown()
                try { check(hookExit.await(5, TimeUnit.SECONDS)) { "Owned hook release timed out" } }
                catch (failure: Throwable) { hookFailure.set(failure) }
                finally { hookExited.countDown() }
            }
            throw expected
        }
        val worker = Attempt("thumbnail-owned-throwing-reader") { cap.read(cancellation) }
        worker.thread.start()
        try {
            await(hookEntered, "hook triggered by reader failure")
            assertTrue(cancellation.isCancelled)
            assertFalse(worker.finished.await(200, TimeUnit.MILLISECONDS))
            assertFalse(cancellation.isDrained)
            hookExit.countDown()
            await(worker.finished, "reader failure after hook drain")
            await(hookExited, "hook exit")
            assertSame(expected, worker.failure.get())
            assertNull(hookFailure.get())
            assertTrue(cancellation.isDrained)
        } finally {
            hookExit.countDown()
            cap.close()
            worker.thread.join(5_000)
        }
        assertFalse(worker.thread.isAlive)
    }

    @Test(timeout = 15_000) fun unconfirmedReleaseCancelsAndWaitsForHookDrainBeforeReturningUnconfirmed() {
        val hookEntered = CountDownLatch(1)
        val hookExit = CountDownLatch(1)
        val hookExited = CountDownLatch(1)
        val hookFailure = AtomicReference<Throwable?>()
        val cancellation = DiscoveryCancellation()
        val readResult = AtomicReference<DiscoveryThumbnailRead?>()
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(), { true }) { actual ->
            actual.onCancel {
                hookEntered.countDown()
                try { check(hookExit.await(5, TimeUnit.SECONDS)) { "Owned hook release timed out" } }
                catch (failure: Throwable) { hookFailure.set(failure) }
                finally { hookExited.countDown() }
            }
            DiscoveryThumbnailRead.ReleaseUnconfirmed
        }
        val worker = Attempt("thumbnail-owned-unconfirmed-reader") { readResult.set(cap.read(cancellation)) }
        worker.thread.start()
        try {
            await(hookEntered, "hook triggered by unconfirmed release")
            assertTrue(cancellation.isCancelled)
            assertFalse("Unconfirmed release returned before hook drain", worker.finished.await(200, TimeUnit.MILLISECONDS))
            assertFalse(cancellation.isDrained)
            assertNull(readResult.get())
            hookExit.countDown()
            await(worker.finished, "unconfirmed result after hook drain")
            await(hookExited, "hook exit")
            assertNull(worker.failure.get())
            assertNull(hookFailure.get())
            assertTrue(cancellation.isDrained)
            assertSame(DiscoveryThumbnailRead.ReleaseUnconfirmed, readResult.get())
        } finally {
            hookExit.countDown()
            cap.close()
            worker.thread.join(5_000)
        }
        assertFalse(worker.thread.isAlive)
    }

    @Test fun successfulReadAtomicallyRetiresUnstartedHooksBeforeLateCancellation() {
        val hooks = AtomicInteger()
        val cancellation = DiscoveryCancellation()
        val cap = DiscoveryThumbnailCapability.fromCommonsRegistration(binding(), { true }) { actual ->
            actual.onCancel { hooks.incrementAndGet() }
            DiscoveryThumbnailRead.Released(null)
        }
        assertEquals(DiscoveryThumbnailRead.Released(null), cap.read(cancellation))
        assertFalse(cancellation.isCancelled)
        cap.close()
        // No active attempt remains for close to cancel; the old token's hooks are retired too.
        assertFalse(cancellation.isCancelled)
        cancellation.cancel()
        assertTrue(cancellation.isCancelled)
        assertTrue(cancellation.isDrained)
        assertEquals(0, hooks.get())
    }

    private class Attempt(name: String, action: () -> Unit) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try { action() }
            catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }, name).apply { isDaemon = true }
    }

    private class Frame : DiscoveryThumbnailFrame {
        override val width = 1
        override val height = 1
        override val allocationBytes = 4L
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    private class Ui : DiscoveryThumbnailDispatcher {
        private val owner = Thread.currentThread()
        val pending = ConcurrentLinkedQueue<Runnable>()
        val timers = mutableListOf<Runnable>()
        override fun requireCurrentThread() { check(Thread.currentThread() === owner) }
        override fun post(task: Runnable) { pending.add(task) }
        override fun later(task: Runnable, delayMillis: Long) { requireCurrentThread(); timers.add(task) }
        override fun remove(task: Runnable) { requireCurrentThread(); timers.remove(task) }
        fun deliver() { requireCurrentThread(); while (true) (pending.poll() ?: return).run() }
    }

    private class Harness {
        val workers = ArrayDeque<Runnable>()
        val ui = Ui()
        val result = result()
        val binding = binding(result)
        var framesDelivered = 0
        val session = DiscoveryThumbnailSession(DiscoveryThumbnailBudget(1, 1, 4, 1, 1, 100),
            DiscoveryThumbnailLane { workers.addLast(it); true }, ui,
            { fail("Owned cancellation fixture unexpectedly stalled") }, { _, _ -> framesDelivered++ })
        init {
            session.updatePage(STAMP, 1, listOf(result), true)
            session.setVisible(setOf(result.resultId))
        }
    }

    companion object {
        private val STAMP = SessionStamp("owned-thumbnail-cancellation", 1)
        private fun result() = VideoSearchResult("owned", "https://owned.invalid/video", null,
            "Owned fixture", "owned", "owned", "https://owned.invalid/image", null, null, null,
            setOf("fixture"), setOf("owned"))
        private fun binding(result: VideoSearchResult = result()) = DiscoveryThumbnailBinding.of(STAMP, 1, result)
        private fun await(latch: CountDownLatch, phase: String) {
            assertTrue("Timed out waiting for $phase", latch.await(5, TimeUnit.SECONDS))
        }
    }
}
