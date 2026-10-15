package com.easysavevideo.app.media.transform

import com.easysavevideo.app.editor.engine.EditorReleaseFence
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Real production owner/fence/executor tests with isolated instances, not a fake scheduler.
 * Tokens below are ownership identities, not an implemented Direct adapter or native sessions.
 * Android Handler/Looper, Transformer and actual native cleanup remain outside these host tests.
 */
class TransformerLaneOwnerTest {
    @Test fun processReferencesShareTheActualProductionOwnerWithoutUsingTheAndroidHandler() {
        val first = SharedTransformerRuntime
        val second = SharedTransformerRuntime
        assertSame(first, second)
        assertSame(first.laneOwner, second.laneOwner)
        assertSame(first.laneOwner.fence, second.laneOwner.fence)
        assertSame(first.laneOwner.lane, second.laneOwner.lane)
    }

    @Test fun legacyEditorNameIsTheExactSharedFenceType() {
        val shared = TransformerReleaseFence()
        val editor: EditorReleaseFence = shared
        assertSame(shared, editor)
        val owner = Any()
        assertTrue(editor.claim(owner))
        assertTrue(shared.occupied)
        shared.release(owner)
        assertFalse(editor.occupied)
    }

    @Test fun confirmedOwnerReleaseAllowsTheOtherIdentityInEitherOrder() {
        val first = Any()
        val second = Any()
        for ((accepted, contender) in listOf(first to second, second to first)) withOwner { runtime ->
            assertTrue(runtime.fence.claim(accepted))
            assertFalse(runtime.fence.claim(contender))
            assertTrue(runtime.fence.occupied)
            runtime.fence.release(accepted)
            assertFalse(runtime.fence.occupied)
            assertTrue(runtime.fence.claim(contender))
            runtime.fence.release(contender)
        }
    }

    @Test fun unknownReleaseRemainsStickyForBothIdentitiesInEitherOrder() {
        val first = Any()
        val second = Any()
        for ((accepted, contender) in listOf(first to second, second to first)) withOwner { runtime ->
            assertTrue(runtime.fence.claim(accepted))
            runtime.fence.unconfirmed()
            repeat(3) { runtime.fence.release(accepted) }
            assertTrue(runtime.fence.requiresRestart)
            assertTrue(runtime.fence.occupied)
            assertFalse(runtime.fence.claim(accepted))
            assertFalse(runtime.fence.claim(contender))
        }
    }

    @Test fun equalButDistinctTokenCannotReleaseAnOwner() = withOwner { runtime ->
        data class Token(val number: Int)
        val owner = Token(1)
        val other = Token(1)
        assertEquals(owner, other)
        assertTrue(runtime.fence.claim(owner))
        assertThrows(IllegalStateException::class.java) { runtime.fence.release(other) }
        assertTrue(runtime.fence.occupied)
        runtime.fence.release(owner)
    }

    @Test fun laneKeepsSingleWorkerSingleQueueAbortPolicyAndDaemonName() = withOwner { runtime ->
        val lane = runtime.lane
        assertEquals(1, lane.corePoolSize)
        assertEquals(1, lane.maximumPoolSize)
        assertEquals(0L, lane.getKeepAliveTime(TimeUnit.MILLISECONDS))
        assertFalse(lane.allowsCoreThreadTimeOut())
        assertTrue(lane.queue is ArrayBlockingQueue<*>)
        assertEquals(1, lane.queue.remainingCapacity())
        assertTrue(lane.rejectedExecutionHandler is ThreadPoolExecutor.AbortPolicy)
        assertEquals(0, lane.poolSize)
        val worker = CompletableFuture<Thread>()
        lane.execute { worker.complete(Thread.currentThread()) }
        val observed = worker.get(5, TimeUnit.SECONDS)
        assertEquals("esv-edit-native", observed.name)
        assertTrue(observed.isDaemon)
    }

    @Test fun saturatedQueueRejectionCleanupCannotClearRecoveryOrRetainedOwner() = withOwner { runtime ->
        val started = CountDownLatch(1)
        val finishRunning = CountDownLatch(1)
        val running = CompletableFuture<Unit>()
        val queued = CompletableFuture<Unit>()
        val accepted = Any()
        assertTrue(runtime.fence.claim(accepted))
        runtime.lane.execute {
            started.countDown()
            try {
                check(finishRunning.await(5, TimeUnit.SECONDS))
                running.complete(Unit)
            } catch (failure: Throwable) { running.completeExceptionally(failure) }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            runtime.lane.execute { queued.complete(Unit) }
            assertEquals(1, runtime.lane.queue.size)
            assertFalse(queued.isDone)
            assertFalse(runtime.fence.claim(Any()))
            runtime.fence.unconfirmed()
            assertThrows(RejectedExecutionException::class.java) {
                try { runtime.lane.execute { fail("Rejected runnable must not execute") } }
                catch (failure: RejectedExecutionException) {
                    // Same release operation as the unchanged Editor rejection catch.
                    runtime.fence.release(accepted)
                    throw failure
                }
            }
            assertTrue(runtime.fence.requiresRestart)
            assertTrue(runtime.fence.occupied)
            finishRunning.countDown()
            running.get(5, TimeUnit.SECONDS)
            queued.get(5, TimeUnit.SECONDS)
            // Late runnable completion is not native-release evidence and cannot reset the fence.
            runtime.fence.release(accepted)
            assertTrue(runtime.fence.requiresRestart)
            assertTrue(runtime.fence.occupied)
            assertFalse(runtime.fence.claim(Any()))
        } finally { finishRunning.countDown() }
    }

    @Test fun rejectedSubmissionCanReleaseAnUnpoisonedOwnerWithoutChangingFailureType() = withOwner { runtime ->
        val owner = Any()
        assertTrue(runtime.fence.claim(owner))
        runtime.lane.shutdown()
        assertThrows(RejectedExecutionException::class.java) {
            try { runtime.lane.execute { fail("Rejected runnable must not execute") } }
            catch (failure: RejectedExecutionException) { runtime.fence.release(owner); throw failure }
        }
        assertFalse(runtime.fence.requiresRestart)
        assertFalse(runtime.fence.occupied)
        assertTrue(runtime.fence.claim(Any()))
    }

    private fun withOwner(block: (TransformerLaneOwner) -> Unit) {
        val runtime = TransformerLaneOwner()
        try { block(runtime) }
        finally {
            runtime.lane.shutdownNow()
            assertTrue("Host fixture worker must terminate", runtime.lane.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
