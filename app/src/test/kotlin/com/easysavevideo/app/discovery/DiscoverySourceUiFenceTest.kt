package com.easysavevideo.app.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoverySourceUiFenceTest {
    @Test fun postEditStorageFailureIsVisibleAtCurrentGeneration() {
        val state = DiscoverySourceUiFence.failure(7, false)
        assertTrue(DiscoverySourceUiFence.accepts(state.configurationIntent, 7))
        assertTrue(state.ready); assertTrue(state.storageUnavailable); assertTrue(state.needsExplicitSearch)
        assertNull(state.controlTarget); assertNull(state.page)
        assertTrue(state.results.isEmpty()); assertTrue(state.pendingCandidates.isEmpty())
    }
    @Test fun staleSuccessOrFailureCannotReplaceNewerEdit() {
        assertFalse(DiscoverySourceUiFence.accepts(0, 1))
        assertFalse(DiscoverySourceUiFence.accepts(DiscoverySourceUiFence.failure(1, false).configurationIntent, 2))
    }

    @Test fun sourceClaimCannotBeRestampedAfterAcceptedWriteOrGenerationChange() {
        val claim = DiscoverySourceInitializationClaim(4, "old")
        assertTrue(DiscoverySourceUiFence.canInitialize(claim, 4, "old", false))
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 5, "old", true))
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 5, "new", false))
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 4, "new", false))
    }
    @Test fun postPublicationRecheckRefusesBusyConfigurationAndAllowsSourceFreeFallback() {
        val claim = DiscoverySourceInitializationClaim(1, "current")
        assertFalse(DiscoverySourceUiFence.canInitialize(claim, 1, "current", true))
        assertTrue(DiscoverySourceUiFence.canInitialize(DiscoverySourceInitializationClaim(1, null), 1, "current", false))
    }
}
