package com.easysavevideo.app.browser

import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real controller + production cleanup seam; these are not native WebView acceptance tests. */
class DiscoveryAddressHostSequenceTest {
    private val owner = BrowserSessionController()
    private val tab = owner.openTab("tab", "session")
    private fun request(address: String = "https://page.example") = owner.beginNavigation(tab.context.tabId,
        PageAddress(address), OperationContext("test", Long.MAX_VALUE, CancellationSource()))
    private fun failed(request: BrowserRequest) = owner.acceptPageFailed(request,
        ErrorEnvelope(ErrorCode.WEBVIEW_UNAVAILABLE, ErrorModule.BROWSER, true, UserAction.RETRY), 1)
    @Test fun ownLoadingStopsThenCancelsAndNeverRepeatsStop() {
        val request = request(); var stops = 0
        assertTrue(settleOrdinaryAddressRequest(owner, request) { stops++ })
        assertEquals(BrowserLoadState.CANCELLED, owner.state.selectedTab?.loadState)
        assertFalse(settleOrdinaryAddressRequest(owner, request) { stops++ }); assertEquals(1, stops)
    }
    @Test fun unavailableEntryStillSettlesExactLogicalLoading() {
        val request = request(); assertTrue(settleOrdinaryAddressRequest(owner, request) {})
        assertEquals(BrowserLoadState.CANCELLED, owner.state.selectedTab?.loadState)
    }
    @Test fun throwingStopStillCancelsOnlyItsRequest() {
        val request = request()
        assertTrue(settleOrdinaryAddressRequest(owner, request) { throw IllegalStateException("synthetic") })
        assertFalse(owner.isCurrent(request))
    }
    @Test fun nativeFailureIsPreservedWithoutNativeStop() {
        val request = request(); assertTrue(failed(request)); val before = owner.state
        assertFalse(settleOrdinaryAddressRequest(owner, request) { fail("must not stop failed page") })
        assertEquals(before, owner.state)
    }
    @Test fun synchronousReadyAndRedirectRemainUntouched() {
        val request = request(); val redirected = PageAddress("https://redirect.example/path")
        assertTrue(owner.acceptPageStarted(request, redirected, 1))
        assertTrue(owner.acceptPageFinished(request, redirected, false, false, 1)); val before = owner.state
        assertFalse(settleOrdinaryAddressRequest(owner, request) { fail("must not stop ready page") })
        assertTrue(owner.isCurrent(request)); assertEquals(before, owner.state)
    }
    @Test fun successorBeforeCleanupIsNeverStoppedOrCancelled() {
        val old = request(); val next = request("https://next.example")
        assertFalse(settleOrdinaryAddressRequest(owner, old) { fail("must not stop successor") })
        assertTrue(owner.isCurrent(next)); assertEquals(BrowserLoadState.LOADING, owner.state.selectedTab?.loadState)
    }
    @Test fun successorDuringStopIsNeverCancelledEvenIfStopThrows() {
        listOf(false, true).forEach { throws ->
            val old = request(); var next: BrowserRequest? = null
            assertFalse(settleOrdinaryAddressRequest(owner, old) {
                next = request("https://next.example")
                if (throws) throw IllegalStateException("synthetic")
            })
            assertTrue(owner.isCurrent(checkNotNull(next))); assertEquals(BrowserLoadState.LOADING, owner.state.selectedTab?.loadState)
        }
    }
    @Test fun stopCallbackReadyOrFailureIsNotRolledBack() {
        listOf(false, true).forEach { ready ->
            val request = request()
            assertFalse(settleOrdinaryAddressRequest(owner, request) {
                if (ready) owner.acceptPageFinished(request, request.address!!, false, false, 1) else failed(request)
            })
            assertEquals(if (ready) BrowserLoadState.READY else BrowserLoadState.FAILED, owner.state.selectedTab?.loadState)
        }
    }
    @Test fun closedAndReusedTabIdCannotBeTargetedByOldSessionRequest() {
        val old = request(); owner.closeTab(tab.context.tabId); owner.openTab("tab", "new-session")
        val next = request("https://next.example")
        assertFalse(settleOrdinaryAddressRequest(owner, old) { fail("must not stop reused tab") })
        assertTrue(owner.isCurrent(next))
    }
    @Test fun anotherSelectedTabDoesNotRedirectCleanupToItsRequest() {
        val old = request(); val second = owner.openTab("second", "second-session")
        val next = owner.beginNavigation(second.context.tabId, PageAddress("https://next.example"),
            OperationContext("second", Long.MAX_VALUE, CancellationSource()))
        assertTrue(settleOrdinaryAddressRequest(owner, old) {})
        assertTrue(owner.isCurrent(next)); assertEquals(second.context, owner.state.selectedTab?.context)
    }
}
