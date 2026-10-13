package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPageDemandTest {
    private val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 1, 1)
    private fun status(state: DiscoveryState = DiscoveryState.FILLING, available: Int = 0,
        sources: List<ProviderDiscoveryState> = listOf(ProviderDiscoveryState.REQUESTING)) =
        DiscoveryStatus(state, available, 1, 1, if (state == DiscoveryState.FILLING) 1 else 0, emptyMap(),
            providers = sources.mapIndexed { i, value -> ProviderDiscoveryProgress("source$i", value, false, null, null, 0, null) })

    @Test fun delayedResultFulfillsExactlyOnceWithoutDisplacingCurrentPageWhileWaiting() {
        val demand = DiscoveryPageDemand()
        assertTrue(demand.begin(target, 0))
        var calls = 0
        assertNull(demand.fulfill(target, 0, status(), true) { calls++; true })
        assertEquals(1, demand.targetPage(target, 0))
        assertEquals(0, calls)
        assertEquals(1, demand.fulfill(target, 0, status(available = 1), true) { assertEquals(1, it); calls++; true })
        assertNull(demand.fulfill(target, 1, status(available = 20), true) { calls++; true })
        assertEquals(1, calls)
    }
    @Test fun hideResumeRetiresIntentBeforeQueuedRefreshEvenWhenCurrentlyVisible() {
        val demand = DiscoveryPageDemand(); demand.begin(target, 0, lifecycle = 4)
        assertNull(demand.fulfill(target, 0, status(available = 1), true, lifecycle = 5) {
            fail("old intent survived hide/resume"); true
        })
        assertNull(demand.targetPage(target, 0, 4))
        assertTrue(demand.begin(target, 0, lifecycle = 5))
    }
    @Test fun retirementDuringSlowStatusReadIsCheckedAgainBeforeMaterialization() {
        val demand = DiscoveryPageDemand(); demand.begin(target, 0, lifecycle = 4)
        assertNull(demand.fulfill(target, 0, status(available = 1), true, lifecycle = 4,
            stillCurrent = { false }) { fail("retired during status read"); true })
        assertNull(demand.targetPage(target, 0, 4))
    }
    @Test fun repeatedClicksNeverQueueSeveralPages() {
        val demand = DiscoveryPageDemand()
        assertTrue(demand.begin(target, 0))
        repeat(20) { assertFalse(demand.begin(target, 0)) }
        assertEquals(1, demand.targetPage(target, 0))
    }
    @Test fun newSessionRevisionOwnerOrProfileRetiresDemand() {
        listOf(target.copy(profileId = "other"), target.copy(stamp = SessionStamp("session", 2)),
            target.copy(viewRevision = 2), target.copy(controllerGeneration = 2)).forEach { newer ->
            val demand = DiscoveryPageDemand(); demand.begin(target, 0)
            assertNull(demand.fulfill(newer, 0, status(available = 1), true) { fail("stale materialization"); true })
            assertNull(demand.targetPage(target, 0))
        }
    }
    @Test fun backwardNavigationOrUnavailableAuthorityRetiresDemand() {
        for (current in listOf(0, 2)) {
            val demand = DiscoveryPageDemand(); demand.begin(target, 1)
            assertNull(demand.targetPage(target, current))
            assertNull(demand.targetPage(target, 1))
        }
        val demand = DiscoveryPageDemand(); demand.begin(target, 1)
        assertNull(demand.targetPage(null, 1))
    }
    @Test fun cancellationDoesNotMaterializeOrResume() {
        val demand = DiscoveryPageDemand(); demand.begin(target, 0); demand.cancel()
        assertNull(demand.fulfill(target, 0, status(available = 1), true) { fail("cancelled"); true })
        assertTrue(demand.begin(target, 0))
    }
    @Test fun readOnlyBackgroundAndHiddenRefreshCannotConsumeDemand() {
        val demand = DiscoveryPageDemand(); demand.begin(target, 0)
        repeat(3) { assertNull(demand.fulfill(target, 0, status(available = 1), false) { fail("forbidden"); true }) }
        assertEquals(1, demand.targetPage(target, 0))
    }
    @Test fun requestRoundSettlesWithoutInventingPageOrAutomaticallyRequestingAgain() {
        for (state in listOf(DiscoveryState.EXHAUSTED, DiscoveryState.FAILED, DiscoveryState.CAPACITY_REACHED,
            DiscoveryState.NO_SOURCES, DiscoveryState.UNSUPPORTED_FILTER, DiscoveryState.IDLE)) {
            val demand = DiscoveryPageDemand(); demand.begin(target, 0)
            assertNull(demand.fulfill(target, 0, status(state), true) { fail("no inventory"); true })
            assertNull(demand.targetPage(target, 0))
        }
    }
    @Test fun retriesAndPrioritySuspensionDoNotPretendToBeExhaustion() {
        for (state in listOf(DiscoveryState.BACKOFF, DiscoveryState.SUSPENDED, DiscoveryState.FILLING)) {
            val demand = DiscoveryPageDemand(); demand.begin(target, 0)
            assertNull(demand.fulfill(target, 0, status(state), true) { fail("no inventory"); true })
            assertEquals(1, demand.targetPage(target, 0))
        }
    }
    @Test fun failedMaterializationNeverAdvancesAndPartialPageCanSucceedLater() {
        val demand = DiscoveryPageDemand(); demand.begin(target, 0)
        assertNull(demand.fulfill(target, 0, status(available = 1), true) { false })
        assertEquals(1, demand.fulfill(target, 0, status(available = 1), true) { true })
    }
    @Test fun invalidIndicesCannotOverflow() {
        val demand = DiscoveryPageDemand()
        assertFalse(demand.begin(target, -1)); assertFalse(demand.begin(target, Int.MAX_VALUE))
    }
    @Test fun allSourcesEndedDisablesFurtherDemandButStillAllowsLoadedPages() {
        val ended = status(DiscoveryState.EXHAUSTED, sources = listOf(ProviderDiscoveryState.EXHAUSTED))
        assertTrue(DiscoveryPagerPolicy.sourcesExhausted(ended))
        assertFalse(DiscoveryPagerPolicy.canAdvance(0, 1, ended))
        assertTrue(DiscoveryPagerPolicy.canAdvance(0, 2, ended))
    }
    @Test fun boundedRoundEndIsNotAllSourceExhaustion() {
        val more = status(DiscoveryState.EXHAUSTED, sources = listOf(ProviderDiscoveryState.READY))
        assertFalse(DiscoveryPagerPolicy.sourcesExhausted(more))
        assertTrue(DiscoveryPagerPolicy.canAdvance(0, 1, more))
    }
    @Test fun remainingInventoryWinsOverSourceExhaustion() {
        val remaining = status(DiscoveryState.EXHAUSTED, 1, listOf(ProviderDiscoveryState.EXHAUSTED))
        assertFalse(DiscoveryPagerPolicy.sourcesExhausted(remaining))
        assertTrue(DiscoveryPagerPolicy.canAdvance(0, 1, remaining))
    }
    @Test fun unknownZeroAndMixedSourcesDoNotClaimExhaustion() {
        assertFalse(DiscoveryPagerPolicy.sourcesExhausted(null))
        assertFalse(DiscoveryPagerPolicy.sourcesExhausted(status(sources = emptyList())))
        assertFalse(DiscoveryPagerPolicy.sourcesExhausted(status(sources = listOf(ProviderDiscoveryState.EXHAUSTED, ProviderDiscoveryState.COOLDOWN))))
    }
    @Test fun missingPageCannotInventPageOne() {
        assertFalse(DiscoveryPagerPolicy.canAdvance(0, 0, status(available = 100)))
        assertFalse(DiscoveryPagerPolicy.canAdvance(-1, 1, status()))
        assertFalse(DiscoveryPagerPolicy.canAdvance(1, 1, status()))
    }
}
