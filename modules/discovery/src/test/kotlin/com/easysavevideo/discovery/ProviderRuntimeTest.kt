package com.easysavevideo.discovery

import com.easysavevideo.core.model.EpochClock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProviderRuntimeTest {
    private val request = SearchRequest(SessionStamp("s", 1), SearchProfile("p", "q"), null)
    @Test fun cancellationHooksRunOnceAndLateRegistrationIsCancelledImmediately() {
        val cancellation = DiscoveryCancellation(); val calls = AtomicInteger()
        val done = CountDownLatch(1)
        cancellation.onCancel { calls.incrementAndGet(); done.countDown() }; cancellation.cancel(); cancellation.cancel()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        cancellation.onCancel { calls.incrementAndGet() }; assertEquals(2, calls.get())
    }
    @Test fun actualWorkerValidatesResultsAndCompletesOnce() {
        val clock = FakeClock(); val timer = FakeTimer(clock)
        val provider = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("p", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage(results = listOf(RawVideoResult("https://example.com/", true)), exhausted = true)
        }
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock).use { runtime ->
            val latch = CountDownLatch(1); val count = AtomicInteger(); val outcome = AtomicReference<ProviderOutcome>()
            val handle = runtime.execute(provider, request, operation()) { outcome.set(it); count.incrementAndGet(); latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS)); handle.cancel()
            assertEquals(1, count.get()); assertEquals(1, (outcome.get() as ProviderOutcome.Success).page.results.size)
        }
    }
    @Test fun hungWorkerTimesOutWithoutBlockingSiblingOrReusingItsSlot() {
        val clock = EpochClock(System::currentTimeMillis)
        JvmDiscoveryTimer().use { timer ->
            IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock).use { runtime ->
                val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
                val callbacks = AtomicInteger(); val outcome = AtomicReference<ProviderOutcome>()
                val blocked = object : VideoSearchProvider {
                    override val descriptor = ProviderDescriptor("hung", "1")
                    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                        entered.countDown()
                        while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }
                        return ProviderPage(exhausted = true)
                    }
                }
                try {
                    runtime.execute(blocked, request, operation(clock.nowMillis() + 200)) { outcome.set(it); callbacks.incrementAndGet(); done.countDown() }
                    assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(done.await(2, TimeUnit.SECONDS))
                    assertEquals(DiscoveryError.DEADLINE_EXCEEDED, (outcome.get() as ProviderOutcome.Failure).code)
                    val busy = AtomicReference<ProviderOutcome>()
                    runtime.execute(blocked, request, operation(clock.nowMillis() + 1000)) { busy.set(it) }
                    assertEquals(DiscoveryError.BUSY, (busy.get() as ProviderOutcome.Failure).code)
                    val sibling = object : VideoSearchProvider {
                        override val descriptor = ProviderDescriptor("healthy", "1")
                        override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage(exhausted = true)
                    }
                    val siblingDone = CountDownLatch(1)
                    runtime.execute(sibling, request, operation(clock.nowMillis() + 1000)) { assertTrue(it is ProviderOutcome.Success); siblingDone.countDown() }
                    assertTrue(siblingDone.await(2, TimeUnit.SECONDS)); assertEquals(1, callbacks.get())
                } finally { release.countDown() }
            }
        }
    }
    @Test fun adapterCancellationHookIsInvokedForActualInflightWork() {
        JvmDiscoveryTimer().use { timer ->
            IsolatedProviderRuntime(ValidationPipeline(), timer).use { runtime ->
                val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val callbacks = CountDownLatch(1)
                val provider = object : VideoSearchProvider {
                    override val descriptor = ProviderDescriptor("p", "1")
                    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                        operation.cancellation.onCancel { cancelled.countDown() }; entered.countDown(); Thread.sleep(5000); return ProviderPage()
                    }
                }
                val handle = runtime.execute(provider, request, operation(System.currentTimeMillis() + 15000)) { callbacks.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS)); handle.cancel()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(callbacks.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun blockingPriorAttemptHookCannotBlockRetryOrSiblingDeadline() {
        JvmDiscoveryTimer().use { timer ->
            IsolatedProviderRuntime(ValidationPipeline(), timer).use { runtime ->
                val release = CountDownLatch(1); val firstDone = CountDownLatch(1); val retryDone = CountDownLatch(1)
                val retryCancelled = CountDownLatch(1); val siblingDone = CountDownLatch(1)
                val retryOutcome = AtomicReference<ProviderOutcome>(); val oldHookCalls = AtomicInteger()
                val provider = object : VideoSearchProvider {
                    override val descriptor = ProviderDescriptor("retry", "1")
                    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                        if (operation.context.attempt == 0) {
                            operation.cancellation.onCancel { oldHookCalls.incrementAndGet(); release.await() }
                            throw DiscoveryFailure(DiscoveryError.PROVIDER_FAILURE, true)
                        }
                        operation.cancellation.onCancel { retryCancelled.countDown(); release.await() }
                        Thread.sleep(5000); return ProviderPage()
                    }
                }
                val original = operation(System.currentTimeMillis() + 300)
                try {
                    runtime.execute(provider, request, original) { firstDone.countDown() }
                    assertTrue(firstDone.await(2, TimeUnit.SECONDS))
                    runtime.execute(provider, request, original.copy(context = original.context.copy(attempt = 1))) { retryOutcome.set(it); retryDone.countDown() }
                    val sibling = object : VideoSearchProvider {
                        override val descriptor = ProviderDescriptor("sibling", "1")
                        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage { Thread.sleep(5000); return ProviderPage() }
                    }
                    runtime.execute(sibling, request, operation(System.currentTimeMillis() + 350)) { siblingDone.countDown() }
                    assertTrue(retryDone.await(2, TimeUnit.SECONDS)); assertTrue(retryCancelled.await(2, TimeUnit.SECONDS))
                    assertEquals(DiscoveryError.DEADLINE_EXCEEDED, (retryOutcome.get() as ProviderOutcome.Failure).code)
                    assertTrue(siblingDone.await(2, TimeUnit.SECONDS)); assertEquals(0, oldHookCalls.get())
                    val busy = AtomicReference<ProviderOutcome>()
                    runtime.execute(provider, request, operation(System.currentTimeMillis() + 1000)) { busy.set(it) }
                    assertEquals(DiscoveryError.BUSY, (busy.get() as ProviderOutcome.Failure).code)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun timeoutRacingWorkerCompletionStillDeliversExactlyOneTerminal() {
        val clock = FakeClock()
        val timer = object : DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle = CancellationHandle { action() }
        }
        // Canceling the timer invokes its deadline action precisely between worker signal cleanup
        // and worker terminal publication, the race that used to lose every completion callback.
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock).use { runtime ->
            val provider = object : VideoSearchProvider {
                override val descriptor = ProviderDescriptor("racing", "1")
                override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage(exhausted = true)
            }
            val done = CountDownLatch(1); val count = AtomicInteger(); val outcome = AtomicReference<ProviderOutcome>()
            runtime.execute(provider, request, operation()) { outcome.set(it); count.incrementAndGet(); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(1, count.get())
            assertEquals(DiscoveryError.DEADLINE_EXCEEDED, (outcome.get() as ProviderOutcome.Failure).code)
        }
    }

    @Test fun completedAttemptReleasesUnusedHooksWithoutRacingCancellation() {
        val cancellation = DiscoveryCancellation(); val calls = AtomicInteger()
        cancellation.onCancel { calls.incrementAndGet() }
        assertTrue(cancellation.releaseCompletedAttempt()); cancellation.cancel()
        assertTrue(cancellation.isDrained); assertEquals(0, calls.get())
        assertFalse(cancellation.releaseCompletedAttempt())
    }

    @Test fun hungWorkersRetainGlobalPhysicalSlotsAfterLogicalTimeouts() {
        JvmDiscoveryTimer().use { timer ->
            IsolatedProviderRuntime(ValidationPipeline(), timer).use { runtime ->
                val release = CountDownLatch(1); val entered = CountDownLatch(2); val timedOut = CountDownLatch(2)
                fun blocked(id: String) = object : VideoSearchProvider {
                    override val descriptor = ProviderDescriptor(id, "1")
                    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                        entered.countDown()
                        while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
                        return ProviderPage()
                    }
                }
                try {
                    runtime.execute(blocked("one"), request, operation(System.currentTimeMillis() + 250)) { timedOut.countDown() }
                    runtime.execute(blocked("two"), request, operation(System.currentTimeMillis() + 250)) { timedOut.countDown() }
                    assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(timedOut.await(2, TimeUnit.SECONDS))
                    val third = AtomicReference<ProviderOutcome>()
                    runtime.execute(blocked("three"), request, operation(System.currentTimeMillis() + 1000)) { third.set(it) }
                    assertEquals(DiscoveryError.BUSY, (third.get() as ProviderOutcome.Failure).code)
                    assertTrue((third.get() as ProviderOutcome.Failure).retryable)
                } finally { release.countDown() }
            }
        }
    }
}
