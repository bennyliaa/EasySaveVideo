package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test

class DiscoveryNavigationFenceTest {
    private val address = "https://example.test/video"
    @Test fun initialExactDocumentAndEitherHistoryOrderingAreAccepted() {
        val first = DiscoveryNavigationFence(address)
        assertTrue(first.onStarted(address)); assertTrue(first.onVisitedHistory(address)); assertTrue(first.accepts(address))
        val second = DiscoveryNavigationFence(address)
        assertTrue(second.onVisitedHistory(address)); assertTrue(second.onStarted(address)); assertTrue(second.accepts(address))
    }
    @Test fun repeatedSameAddressStartIsNewNavigation() {
        val fence = DiscoveryNavigationFence(address)
        assertTrue(fence.onStarted(address)); assertFalse(fence.onStarted(address)); assertFalse(fence.accepts(address))
    }
    @Test fun historyMutationAfterOriginalObservationCannotKeepAssociation() {
        val fence = DiscoveryNavigationFence(address)
        assertTrue(fence.onStarted(address)); assertTrue(fence.onVisitedHistory(address))
        assertFalse(fence.onVisitedHistory(address)); assertFalse(fence.accepts(address))
    }
    @Test fun firstHistoryCallbackAfterReadyFailsClosedWithoutAssumingNativeOrdering() {
        val fence = DiscoveryNavigationFence(address)
        assertTrue(fence.onStarted(address)); fence.onFinished()
        assertTrue(fence.accepts(address))
        assertFalse(fence.onVisitedHistory(address)); assertFalse(fence.accepts(address))
    }
    @Test fun redirectedAndFragmentDocumentsDoNotMatch() {
        for (other in listOf("https://example.test/redirect", "$address#media", "$address?query=changed")) {
            val fence = DiscoveryNavigationFence(address)
            assertFalse(fence.onStarted(other)); assertFalse(fence.onStarted(address)); assertFalse(fence.accepts(address))
        }
    }
    @Test fun noLoadedDocumentOrNullAddressMeansNoSaveProof() {
        val fence = DiscoveryNavigationFence(address)
        assertFalse(fence.accepts(address)); assertFalse(fence.accepts(null))
        assertTrue(fence.onStarted(address)); assertFalse(fence.accepts(null))
    }
    @Test fun addressIsRedactedFromDiagnostics() {
        assertFalse(DiscoveryNavigationFence(address).toString().contains(address))
    }
}
