package com.easysavevideo.commons

import com.easysavevideo.core.model.EpochClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CommonsProviderRuntimeTest {
    @Test fun callbackRunsOffCallerAndOnlyOnce() {
        val caller = Thread.currentThread()
        CommonsProviderRuntime(provider(TestTransport(Reply())), TestClock()).use { runtime ->
            val done = CountDownLatch(1); val count = AtomicInteger(); val thread = AtomicReference<Thread>()
            val handle = runtime.execute(request, operation()) {
                assertTrue(it is CommonsOutcome.Success); thread.set(Thread.currentThread()); count.incrementAndGet(); done.countDown()
            }
            assertTrue(done.await(2, TimeUnit.SECONDS)); handle.cancel()
            assertNotEquals(caller, thread.get()); assertEquals(1, count.get())
        }
    }
    @Test fun runtimeHookRegistrationFailureDoesNotStartTransportOrLoseCompletion() {
        val transport = TestTransport()
        CommonsProviderRuntime(provider(transport), TestClock()).use { runtime ->
            val op = operation(); repeat(8) { op.cancellation.onCancel { } }
            val done = CountDownLatch(1); val outcome = AtomicReference<CommonsOutcome>()
            runtime.execute(request, op) { outcome.set(it); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(CommonsError.SERVICE_ERROR, (outcome.get() as CommonsOutcome.Failure).code)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun deadlineCompletesEvenWhenTransportIgnoresInterruptAndCancel() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val transport = CommonsTransport { object : CommonsHttpCall {
            override fun execute(): CommonsHttpResponse {
                entered.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
                throw IllegalStateException("untrusted details")
            }
            override fun cancel() { cancelled.countDown() }
        } }
        val p = CommonsMetadataProvider(config(), transport)
        CommonsProviderRuntime(p).use { runtime ->
            val done = CountDownLatch(1); val outcome = AtomicReference<CommonsOutcome>(); val count = AtomicInteger()
            try {
                runtime.execute(request, operation(System.currentTimeMillis() + 150)) { outcome.set(it); count.incrementAndGet(); done.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(done.await(2, TimeUnit.SECONDS))
                assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertEquals(CommonsError.DEADLINE_EXCEEDED, (outcome.get() as CommonsOutcome.Failure).code)
                val busy = AtomicReference<CommonsOutcome>()
                runtime.execute(request, operation(System.currentTimeMillis() + 1000)) { busy.set(it) }
                assertEquals(CommonsError.BUSY, (busy.get() as CommonsOutcome.Failure).code)
                assertEquals(1, count.get())
            } finally { release.countDown() }
        }
    }
    @Test fun closeCancelsTheRealInflightCallAndCompletesPromptly() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val transport = CommonsTransport { object : CommonsHttpCall {
            override fun execute(): CommonsHttpResponse { entered.countDown(); cancelled.await(); throw InterruptedException() }
            override fun cancel() { cancelled.countDown() }
        } }
        val runtime = CommonsProviderRuntime(CommonsMetadataProvider(config(), transport))
        val done = CountDownLatch(1); val outcome = AtomicReference<CommonsOutcome>()
        runtime.execute(request, operation(System.currentTimeMillis() + 15_000)) { outcome.set(it); done.countDown() }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); runtime.close()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(CommonsError.CANCELLED, (outcome.get() as CommonsOutcome.Failure).code)
    }
    @Test fun stuckCancellationHookRetainsPhysicalSlotButCannotBlockDeadlineCallback() {
        val entered = CountDownLatch(1); val cancellationStarted = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = CommonsTransport { object : CommonsHttpCall {
            override fun execute(): CommonsHttpResponse { entered.countDown(); throw InterruptedException() }
            override fun cancel() { cancellationStarted.countDown(); release.await() }
        } }
        CommonsProviderRuntime(CommonsMetadataProvider(config(), transport)).use { runtime ->
            val done = CountDownLatch(1)
            try {
                runtime.execute(request, operation(System.currentTimeMillis() + 150)) { done.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(cancellationStarted.await(2, TimeUnit.SECONDS))
                assertTrue(done.await(2, TimeUnit.SECONDS))
                val busy = AtomicReference<CommonsOutcome>()
                runtime.execute(request, operation(System.currentTimeMillis() + 1000)) { busy.set(it) }
                assertEquals(CommonsError.BUSY, (busy.get() as CommonsOutcome.Failure).code)
            } finally { release.countDown() }
        }
    }
}
