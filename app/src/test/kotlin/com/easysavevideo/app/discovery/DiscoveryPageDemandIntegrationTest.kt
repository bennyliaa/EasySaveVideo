package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

/** Real coordinator/result pool with finite owned metadata and a deferred, network-free runtime. */
class DiscoveryPageDemandIntegrationTest {
    private class Runtime : ProviderRuntime {
        val completions = mutableListOf<(ProviderOutcome) -> Unit>()
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle {
            completions += callback
            return CancellationHandle { operation.cancellation.cancel() }
        }
        fun complete(index: Int, id: String) {
            val result = requireNotNull(ResultNormalizer.normalize(RawVideoResult(
                "https://fixture.invalid/$id", true, title = "Owned fixture", sourceNamespace = "fixture",
                sourceVideoId = id), "source$index"))
            completions[index](ProviderOutcome.Success(ValidatedProviderPage(listOf(result), null, null, true)))
        }
    }
    private class Fixture : AutoCloseable {
        val runtime = Runtime()
        private val providers = (0..1).map { index -> object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("source$index", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("No network")
        } }
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(providers), runtime, InMemoryDiscoveryStore(),
            object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {}
            }, EpochClock { 1_000 })
        val demand = DiscoveryPageDemand()
        init { coordinator.setVisibility(true, true); coordinator.start("session", SearchProfile("profile", "owned")) }
        fun target() = requireNotNull(DiscoveryPresentationPolicy.controlTarget(coordinator.currentCheckpoint(), 1))
        override fun close() = coordinator.close()
    }
    @Test fun slowSourceCompletesExplicitNextDemandAndFirstPageStaysImmutable() = Fixture().use { f ->
        f.runtime.complete(0, "first")
        val first = requireNotNull(f.coordinator.page(0))
        val target = f.target()
        assertTrue(f.demand.begin(target, 0))
        assertNull(f.coordinator.page(1))
        f.coordinator.requestMore()
        assertNull(f.demand.fulfill(target, 0, f.coordinator.status(), true) { f.coordinator.page(it) != null })
        assertEquals(first, f.coordinator.page(0))
        f.runtime.complete(1, "second")
        assertEquals(1, f.demand.fulfill(f.target(), 0, f.coordinator.status(), true) { f.coordinator.page(it) != null })
        assertEquals(first, f.coordinator.page(0))
        assertEquals(1, f.coordinator.page(1)!!.orderedResultIds.size)
        assertTrue(DiscoveryPagerPolicy.sourcesExhausted(f.coordinator.status()))
        assertEquals(2, f.runtime.completions.size)
        assertEquals(2, f.coordinator.currentCheckpoint()!!.pool.pages.size)
    }
    @Test fun cancelledIntentLeavesArrivingResultInPoolWithoutNewPage() = Fixture().use { f ->
        f.runtime.complete(0, "first"); f.coordinator.page(0)
        f.demand.begin(f.target(), 0); f.demand.cancel()
        f.runtime.complete(1, "second")
        assertNull(f.demand.fulfill(f.target(), 0, f.coordinator.status(), true) { f.coordinator.page(it) != null })
        assertEquals(1, f.coordinator.currentCheckpoint()!!.pool.pages.size)
        assertEquals(1, f.coordinator.status().availableCount)
    }
    @Test fun replacementQueryCannotInheritOldNextPageIntent() = Fixture().use { f ->
        f.runtime.complete(0, "first"); f.coordinator.page(0)
        f.demand.begin(f.target(), 0)
        f.coordinator.start("session", SearchProfile("new-profile", "new owned"))
        f.runtime.complete(1, "late-old")
        assertNull(f.demand.fulfill(f.target(), 0, f.coordinator.status(), true) { fail("stale intent"); true })
        assertEquals(0, f.coordinator.currentCheckpoint()!!.pool.pages.size)
        assertEquals(0, f.coordinator.status().availableCount)
    }
}
