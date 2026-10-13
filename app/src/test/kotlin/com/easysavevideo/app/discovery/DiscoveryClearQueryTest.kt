package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryClearQueryTest {
    private val profile = SearchProfile("profile", "original", "en", SearchFilters())
    private val draft = DiscoveryFilterPresentationPolicy.fromProfile(profile, false).copy(query = "new", minimumSeconds = "1")
    private val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
    private val claim = DiscoveryFilterClaim(target, false, draft)
    private fun clear(current: DiscoveryFilterDraft? = draft, currentTarget: DiscoveryControlTarget? = target,
        currentProfile: SearchProfile = profile, demo: Boolean = false, ready: Boolean = true,
        unavailable: Boolean = false, busy: Boolean = false, expectedLifecycle: Long = 1, currentLifecycle: Long = 1, interactive: Boolean = true) =
        DiscoveryFilterPresentationPolicy.clearQuery(claim, current, currentTarget, currentProfile, demo, ready, unavailable, busy, expectedLifecycle, currentLifecycle, interactive)

    @Test fun currentClaimOnlyChangesQueryAndKeepsAppliedProfileAndFilters() {
        val cleared = requireNotNull(clear())
        assertEquals("", cleared.query)
        assertEquals(draft.copy(query = ""), cleared)
        assertEquals("original", cleared.appliedProfile.query)
        assertEquals("1", cleared.minimumSeconds)
        assertEquals("new", draft.query)
    }
    @Test fun repeatedAndEmptyClearAreNoOps() {
        val cleared = requireNotNull(clear());assertNull(clear(cleared))
        val emptyClaim = claim.copy(draft = cleared)
        assertNull(DiscoveryFilterPresentationPolicy.clearQuery(emptyClaim, cleared, target, profile, false, true, false, false, 1, 1, true))
    }
    @Test fun whitespaceIsStillExplicitlyClearable() {
        val whitespace = draft.copy(query = "  ");val captured = claim.copy(draft = whitespace)
        assertEquals("", DiscoveryFilterPresentationPolicy.clearQuery(captured, whitespace, target, profile, false, true, false, false, 1, 1, true)!!.query)
    }
    @Test fun oldQueryOrFilterClaimCannotEraseNewEdits() {
        assertNull(clear(draft.copy(query = "later")))
        assertNull(clear(draft.copy(maximumSeconds = "20")))
        assertNull(clear(draft.copy(safeSearch = !draft.safeSearch)))
    }
    @Test fun equalButNewAbaDraftIdentityStillRejectsOldClaim() {
        val restored = draft.copy(query = "different").copy(query = draft.query)
        assertEquals(draft, restored);assertNotSame(draft, restored);assertNull(clear(restored))
    }
    @Test fun allExistingClaimGatesRemainRequired() {
        assertNull(clear(null));assertNull(clear(currentTarget = null))
        assertNull(clear(currentTarget = target.copy(viewRevision = 1)))
        assertNull(clear(currentTarget = target.copy(controllerGeneration = 2)))
        assertNull(clear(currentProfile = profile.copy(query = "changed")))
        assertNull(clear(demo = true));assertNull(clear(ready = false));assertNull(clear(unavailable = true));assertNull(clear(busy = true))
    }
    @Test fun returnToSameDraftAfterLeavingDoesNotReviveOldLifecycleClaim() {
        assertNull(clear(expectedLifecycle = 1, currentLifecycle = 2))
        assertEquals("", clear(expectedLifecycle = 2, currentLifecycle = 2)!!.query)
    }
    @Test fun currentInteractionMustBeAllowedEvenWithAnOtherwiseValidClaim() {
        assertNull(clear(interactive = false))
        assertNotNull(clear(interactive = true))
    }

}
