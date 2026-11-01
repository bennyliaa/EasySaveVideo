package com.easysavevideo.discovery

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Sixteen configured sources remain one bounded user-search session, not sixteen independent budgets. */
class DynamicSourceSessionBudgetTest {
    private class Source(index: Int, override val searchBudget: SourceSearchBudget,
        val work: () -> Unit = {}) : VideoSearchProvider {
        override val descriptor = ProviderDescriptor("searxng-$index", "fixture", progressScope = ProviderProgressScope.SESSION)
        val calls = AtomicInteger()
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
            calls.incrementAndGet(); work(); return ProviderPage()
        }
    }
    private class Fixture(envelope: SourceSearchBudget = SourceSearchBudget(2, 2L * 1024 * 1024),
        work: () -> Unit = {}) : AutoCloseable {
        val sources = (1..16).map { Source(it, envelope, work) }
        val registry = ProviderRegistry(sources)
        var monotonic = 0L
        val stamp = SessionStamp("session", 1)
        val profile = SearchProfile("profile", "synthetic query")
        val admission = SessionCandidateAdmission(registry, epochMillis = { 1000L }, monotonicMillis = { monotonic })
        val pipeline = ValidationPipeline(clock = EpochClock { 1000L }, sessionAdmission = admission)
        // Tests control completion directly; no actual timer/network is created.
        val runtime = IsolatedProviderRuntime(pipeline, object : DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle { }
        }, EpochClock { 1000L })
        init { admission.beginUserSearch(stamp, profile.queryKey) }
        fun request(source: Source) = SearchRequest(stamp, profile, null, source.descriptor)
        fun run(source: Source): ProviderOutcome {
            val done = CountDownLatch(1); val result = AtomicReference<ProviderOutcome>()
            runtime.execute(source, request(source), op()) { result.set(it); done.countDown() }
            assertTrue(done.await(3, TimeUnit.SECONDS)); return result.get()
        }
        override fun close() { admission.revoke(); runtime.close() }
    }
    private companion object {
        fun op(): DiscoveryOperation {
            val cancellation = DiscoveryCancellation()
            return DiscoveryOperation(OperationContext("test", 16000L, cancellation,
                resourceBudget = ResourceBudget(16)), cancellation)
        }
        fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) try { latch.await(); return } catch (_: InterruptedException) { }
        }
    }

    @Test fun sixteenTwoMiBEnvelopesShareTwelveMiBAndEmptyResponsesDoNotRefund() {
        Fixture().use { f ->
            val outcomes = f.sources.map(f::run)
            assertEquals(6, outcomes.count { it is ProviderOutcome.Success })
            assertEquals(10, outcomes.count { it is ProviderOutcome.Failure && it.code == DiscoveryError.CAPACITY_REACHED })
            assertEquals(6, f.sources.sumOf { it.calls.get() })
            assertEquals(SessionAdmissionStatus.BYTE_LIMIT, f.admission.status())
            assertEquals(DiscoveryError.CAPACITY_REACHED, (f.run(f.sources.last()) as ProviderOutcome.Failure).code)
            assertEquals(0, f.sources.last().calls.get())
        }
    }

    @Test fun RequestLimitIsSeventyTwoAcrossAllSourcesAndPages() {
        Fixture(SourceSearchBudget(4, 1024)).use { f ->
            val outcomes = (0 until 20).map { f.run(f.sources[it % 16]) }
            assertEquals(18, outcomes.count { it is ProviderOutcome.Success })
            assertEquals(2, outcomes.count { it is ProviderOutcome.Failure && it.code == DiscoveryError.CAPACITY_REACHED })
            assertEquals(18, f.sources.sumOf { it.calls.get() })
            assertEquals(SessionAdmissionStatus.REQUEST_LIMIT, f.admission.status())
        }
    }

    @Test fun SixteenSourcesNeverCreateMoreThanThreeLogicalWorkersAndLateSuccessCannotPublish() {
        val entered = CountDownLatch(3); val release = CountDownLatch(1); val left = CountDownLatch(3)
        val running = AtomicInteger(); val peak = AtomicInteger()
        val f = Fixture(SourceSearchBudget(1, 1024)) {
            val active = running.incrementAndGet(); peak.updateAndGet { maxOf(it, active) }; entered.countDown()
            try { awaitIgnoringInterrupts(release) } finally { running.decrementAndGet(); left.countDown() }
        }
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<ProviderOutcome>())
        val done = CountDownLatch(16)
        try {
            val handles = f.sources.map { source -> f.runtime.execute(source, f.request(source), op()) { outcomes += it; done.countDown() } }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(3, peak.get())
            assertEquals(13, outcomes.count { it is ProviderOutcome.Failure && it.code == DiscoveryError.BUSY })
            f.admission.revoke(); handles.forEach { it.cancel() }
            assertTrue(done.await(3, TimeUnit.SECONDS)); release.countDown(); assertTrue(left.await(3, TimeUnit.SECONDS))
            assertEquals(16, outcomes.size); assertTrue(outcomes.none { it is ProviderOutcome.Success })
            assertEquals(3, f.sources.sumOf { it.calls.get() })
        } finally { release.countDown(); f.close() }
    }

    @Test fun UniquePageAndLifetimeCapsDoNotScaleWithInstanceCount() {
        Fixture(SourceSearchBudget(1, 1024)).use { f ->
            val operations = f.sources.map { source -> op().also { f.pipeline.reserveTrustedSource(source, f.request(source), null, it) } }
            repeat(24) { index ->
                val source = f.sources[index % 16]
                assertNotNull(f.admission.accept(source, f.request(source), null,
                    CandidateUrl("https://public.example.org/page-$index"), operations[index % 16], allowValidation = false))
            }
            assertNull(f.admission.accept(f.sources.last(), f.request(f.sources.last()), null,
                CandidateUrl("https://public.example.org/page-25"), operations.last(), allowValidation = false))
            assertEquals(SessionAdmissionStatus.PAGE_LIMIT, f.admission.status())
            f.monotonic = 60_000L
            assertEquals(DiscoveryError.DEADLINE_EXCEEDED, (f.run(f.sources.last()) as ProviderOutcome.Failure).code)
            assertEquals(0, f.sources.sumOf { it.calls.get() })
            assertEquals(SessionAdmissionStatus.EXPIRED, f.admission.status())
        }
    }

    @Test fun DeleteAndReaddSameIdCannotReusePriorRegisteredObjectOrSessionGrant() {
        Fixture(SourceSearchBudget(1, 1024)).use { f ->
            val old = f.sources.first(); f.registry.remove(old.descriptor.id)
            val replacement = Source(1, SourceSearchBudget(1, 1024)); f.registry.register(replacement)
            assertEquals(DiscoveryError.CANCELLED, (f.run(old) as ProviderOutcome.Failure).code)
            assertEquals(DiscoveryError.CANCELLED, (f.run(replacement) as ProviderOutcome.Failure).code)
            assertEquals(0, old.calls.get()); assertEquals(0, replacement.calls.get())
            f.admission.revoke()
            assertEquals(DiscoveryError.CANCELLED, (f.run(f.sources[1]) as ProviderOutcome.Failure).code)
            assertEquals(0, f.sources.sumOf { it.calls.get() })
        }
    }
}
