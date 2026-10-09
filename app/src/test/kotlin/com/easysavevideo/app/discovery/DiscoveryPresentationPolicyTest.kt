package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.SearchFilters
import com.easysavevideo.discovery.ShownVisibilityTracker
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationPolicyTest {
    @Test fun historyIsolatedByQueryFiltersLocaleAndDemoButNotDisplayPolicy() {
        fun profile(query: String = "cats", locale: String = "en", filters: SearchFilters = SearchFilters(), policy: RepeatPolicy = RepeatPolicy.PREFER_NEW, demo: Boolean = false) =
            DiscoveryPresentationPolicy.profile(query, locale, filters, policy, demo)
        val base = profile()
        assertEquals(base.profileId, profile("  CATS ").profileId)
        assertEquals(base.profileId, profile(policy = RepeatPolicy.HIDE_SHOWN).profileId)
        assertNotEquals(base.profileId, profile("dogs").profileId)
        assertNotEquals(base.profileId, profile(locale = "zh").profileId)
        assertNotEquals(base.profileId, profile(filters = SearchFilters(maximumDurationMs = 60_000)).profileId)
        assertNotEquals(base.profileId, profile(demo = true).profileId)
    }
    @Test fun fractionUsesBothDimensionsAndViewportClipping() {
        assertEquals(0.5, DiscoveryPresentationPolicy.fraction(0, -50, 100, 100, 100, 0, 500, true, true), 0.0)
        assertEquals(0.25, DiscoveryPresentationPolicy.fraction(-50, -50, 100, 100, 100, 0, 500, true, true), 0.0)
        assertEquals(0.0, DiscoveryPresentationPolicy.fraction(0, 600, 100, 100, 100, 0, 500, true, true), 0.0)
    }
    @Test fun backgroundOrModalCannotEarnShown() {
        assertEquals(0.0, DiscoveryPresentationPolicy.fraction(0, 0, 100, 100, 100, 0, 500, false, true), 0.0)
        assertEquals(0.0, DiscoveryPresentationPolicy.fraction(0, 0, 100, 100, 100, 0, 500, true, false), 0.0)
    }
    @Test fun obscuredIntervalResetsContinuousDwell() {
        val tracker = ShownVisibilityTracker()
        assertFalse(tracker.update("id", 1.0, 0))
        assertFalse(tracker.update("id", 0.0, 499))
        assertFalse(tracker.update("id", 1.0, 600))
        assertFalse(tracker.update("id", 1.0, 1099))
        assertTrue(tracker.update("id", 1.0, 1100))
    }
    @Test fun invalidDimensionsDoNotYieldExposure() {
        assertEquals(0.0, DiscoveryPresentationPolicy.fraction(0, 0, 0, 100, 100, 0, 500, true, true), 0.0)
        assertEquals(0.0, DiscoveryPresentationPolicy.fraction(0, 0, 100, 100, 100, 500, 0, true, true), 0.0)
    }
}
