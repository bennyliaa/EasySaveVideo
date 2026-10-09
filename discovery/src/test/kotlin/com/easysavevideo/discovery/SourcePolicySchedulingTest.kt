package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class SourcePolicySchedulingTest {
    private class Fixture {
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime()
        val provider = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("source", "1")
            override fun refusal(profile: SearchProfile) = if (profile.filters.safeSearch) DiscoveryError.UNSUPPORTED_FILTER else null
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("Not called")
        }
        val store = InMemoryDiscoveryStore()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(provider)), runtime, store, timer, clock,
            retryDelayMillis = { 1000 })
        fun start(safe: Boolean = false, query: String = "query") {
            coordinator.setVisibility(true, true)
            coordinator.start("session", SearchProfile("profile", query, filters = SearchFilters(safeSearch = safe)))
        }
    }
    @Test fun requiredSafeSearchIsVisibleAndMakesZeroRuntimeCallsAcrossRetries() {
        val f = Fixture(); f.start(safe = true)
        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(DiscoveryState.UNSUPPORTED_FILTER, f.coordinator.status().state)
        assertEquals(DiscoveryError.UNSUPPORTED_FILTER, f.coordinator.status().lastError)
        f.timer.advance(60_000); f.coordinator.requestMore()
        assertTrue(f.runtime.calls.isEmpty())
        f.store.transaction { assertNull(progress("profile", "source", SearchProfile("profile", "query").queryKey)) }
    }
    @Test fun explicitFilterChangeStartsNewEpochAndDoesNotCarryCursor() {
        val f = Fixture(); f.start()
        val first = f.runtime.calls.single()
        f.start(safe = true)
        assertTrue(first.cancelled); assertEquals(1, f.runtime.calls.size)
        f.runtime.complete(0, 1, nextCursor = "stale", exhausted = false)
        assertEquals(0, f.coordinator.status().availableCount)
        f.start(safe = false)
        assertEquals(2, f.runtime.calls.size)
        assertNull(f.runtime.calls[1].request.progress)
        assertTrue(f.runtime.calls[1].request.stamp.epoch > first.request.stamp.epoch)
    }
    @Test fun blankQueryDoesNotStartSourceWork() {
        val f = Fixture(); f.start(query = " ")
        assertTrue(f.runtime.calls.isEmpty()); assertEquals(DiscoveryState.IDLE, f.coordinator.status().state)
    }
    @Test fun serverRetryAfterDominatesShorterLocalDelayWithinOriginalDeadline() {
        val f = Fixture(); f.start()
        val original = f.runtime.calls.single().operation.context
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 6000))
        f.timer.advance(4999); assertEquals(1, f.runtime.calls.size)
        f.timer.advance(1); assertEquals(2, f.runtime.calls.size)
        assertEquals(original.deadlineEpochMillis, f.runtime.calls[1].operation.context.deadlineEpochMillis)
        assertSame(original.cancelToken, f.runtime.calls[1].operation.context.cancelToken)
    }
    @Test fun serverRetryOutsideDeadlineIsRetainedAcrossManualDemandAndQueryChange() {
        val f = Fixture(); f.start()
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 90_000))
        assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
        f.timer.advance(40_000); f.coordinator.requestMore(); f.start(query = "changed")
        assertEquals(1, f.runtime.calls.size)
        assertEquals(90_000L, f.coordinator.status().health["source"]!!.cooldownUntilEpochMillis)
        f.timer.advance(49_000); f.coordinator.requestMore()
        assertEquals(2, f.runtime.calls.size)
    }
    @Test fun retryAfterOverflowCannotCreateImmediateRetry() {
        val f = Fixture(); f.start()
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, Long.MAX_VALUE))
        f.timer.advance(100_000); f.coordinator.requestMore()
        assertEquals(1, f.runtime.calls.size)
        assertEquals(Long.MAX_VALUE, f.coordinator.status().health["source"]!!.cooldownUntilEpochMillis)
    }
    @Test fun inBudgetServerRetrySurvivesNewEpochAndVisibilityCancellation() {
        for (newQuery in listOf(false, true)) {
            val f = Fixture(); f.start()
            f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 6000))
            assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
            if (newQuery) f.start(query = "changed") else {
                f.coordinator.setVisibility(false, false); f.coordinator.setVisibility(true, true)
            }
            f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
            f.timer.advance(4999); f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
            f.timer.advance(1); f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
        }
    }

    @Test fun rateFailureObservedAtDeadlineStillRetainsServerNotBefore() {
        val f = Fixture(); f.start(); f.clock.now = 16_000
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 90_000))
        assertEquals(90_000L, f.coordinator.status().health["source"]!!.cooldownUntilEpochMillis)
        f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
    }

}
