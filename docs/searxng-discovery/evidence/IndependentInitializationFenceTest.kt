package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual pure claim predicate and real coordinator latch, with deterministic interleavings. */
class IndependentInitializationFenceTest {
    private val key = "a".repeat(64)
    private val profile = SearchProfile("profile", "query", filters = SearchFilters(safeSearch = false))
    private class Runtime : ProviderRuntime {
        var calls = 0
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle { calls++; return CancellationHandle {} }
    }
    private fun coordinator(runtime: Runtime): VideoDiscoveryCoordinator {
        val store = InMemoryDiscoveryStore()
        store.transaction { putCheckpoint(DiscoveryCheckpoint(SessionStamp("session", 1), profile, sourceConfigurationKey = key)) }
        val source = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("searxng-1", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("Fixture runtime only")
        }
        val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
        return VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, store, timer, EpochClock { 1000L }, sourceConfigurationKey = key)
    }
    @Test fun callbackClaimCannotBecomeCurrentByStampingLaterIntentOrNewGeneration() {
        val claim = DiscoverySourceInitializationClaim(3, key)
        assertTrue(DiscoverySourceUiFence.canInitialize(claim, 3, key, false))
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 4, key, false))
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 3, "b".repeat(64), false))
    }
    @Test fun busyOwnerRejectsEvenOtherwiseMatchingInitialization() {
        val claim = DiscoverySourceInitializationClaim(3, key)
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 3, key, true))
    }
    @Test fun acceptedWriteBeforeNewReferenceIsCaughtByPostPublicationCheckAndLatch() {
        val runtime = Runtime(); val claim = DiscoverySourceInitializationClaim(3, key)
        assertTrue(DiscoverySourceUiFence.canInitialize(claim, 3, key, false))
        // Accepted write fences the previous/null coordinator, then initialization publishes a new one.
        val currentIntent = 4L; val ownerBusy = true
        val installed = coordinator(runtime)
        if (!DiscoverySourceUiFence.canInitialize(claim, currentIntent, key, ownerBusy)) installed.suspendSourcesImmediately()
        assertTrue(installed.restore("session"))
        installed.setVisibility(true, true); installed.requestMore(); installed.setPriority(false, false)
        assertEquals(0, runtime.calls)
    }
    @Test fun acceptedWriteAfterReferencePublicationDirectlyLatchesNewCoordinator() {
        val runtime = Runtime(); val claim = DiscoverySourceInitializationClaim(3, key)
        val installed = coordinator(runtime)
        assertTrue(DiscoverySourceUiFence.canInitialize(claim, 3, key, false))
        // The shared subscriber now sees the newly published reference before lifecycle dispatch.
        installed.suspendSourcesImmediately()
        assertTrue(installed.restore("session")); installed.setVisibility(true, true); installed.requestMore()
        assertEquals(0, runtime.calls)
    }
    @Test fun unavailableFallbackHasNoSearxngProviderAuthority() {
        assertTrue(DiscoverySourceUiFence.canInitialize(DiscoverySourceInitializationClaim(3, null), 3, key, false))
        assertTrue(SearxngSourceRegistration.providers(null).isEmpty())
        assertFalse(DiscoverySourceUiFence.canInitialize(DiscoverySourceInitializationClaim(3, null), 4, key, false))
        assertFalse(DiscoverySourceUiFence.canInitialize(DiscoverySourceInitializationClaim(3, null), 3, key, true))
    }
}
