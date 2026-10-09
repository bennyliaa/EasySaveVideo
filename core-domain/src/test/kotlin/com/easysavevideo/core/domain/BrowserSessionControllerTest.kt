package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionControllerTest {
    private val first = PageAddress("https://example.test/first")
    private val second = PageAddress("https://example.test/second")
    private fun operation(id: String = "op", deadline: Long = 1_000) =
        OperationContext(id, deadline, CancellationSource())
    private fun controller() = BrowserSessionController().also { it.openTab("tab", "session") }

    @Test fun newerNavigationRejectsOldCallbacks() {
        val browser = controller()
        val old = browser.beginNavigation("tab", first, operation("first"))
        val current = browser.beginNavigation("tab", second, operation("second"))
        assertFalse(browser.acceptPageFinished(old, first, false, false, 10))
        assertFalse(browser.acceptTitle(old, "old", 10))
        assertTrue(browser.acceptPageFinished(current, second, true, false, 10))
        assertEquals(second, browser.state.selectedTab!!.address)
        assertTrue(browser.state.selectedTab!!.canGoBack)
    }

    @Test fun cancellationCannotBeOverwrittenByLateSuccessOrFailure() {
        val browser = controller()
        val request = browser.beginNavigation("tab", first, operation())
        assertNotNull(browser.cancelNavigation("tab"))
        assertFalse(browser.acceptPageFinished(request, first, false, false, 10))
        assertFalse(browser.acceptPageFailed(request, error(), 10))
        assertEquals(BrowserLoadState.CANCELLED, browser.state.selectedTab!!.loadState)
    }

    @Test fun failedPageCannotBecomeReadyFromLaterFinish() {
        val browser = controller()
        val request = browser.beginNavigation("tab", first, operation())
        assertTrue(browser.acceptPageFailed(request, error(), 10))
        assertFalse(browser.acceptPageFinished(request, first, false, false, 11))
        assertEquals(ErrorCode.PAGE_LOAD_FAILED, browser.state.selectedTab!!.error!!.code)
    }

    @Test fun deadlineStopsLogicalRequestAndReturnsContextForRealStop() {
        val browser = controller()
        val request = browser.beginNavigation("tab", first, operation(deadline = 100))
        assertFalse(browser.acceptProgress(request, 99, 100))
        assertEquals(listOf(request.context), browser.expireOperations(100))
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, browser.state.selectedTab!!.error!!.code)
        assertTrue(browser.expireOperations(101).isEmpty())
        assertFalse(browser.acceptPageFinished(request, first, false, false, 101))
    }

    @Test fun externallyCancelledTokenExpiresWithoutTimeoutError() {
        val browser = controller()
        val cancel = CancellationSource()
        val request = browser.beginNavigation("tab", first, OperationContext("op", 1_000, cancel))
        cancel.cancel()
        assertFalse(browser.acceptProgress(request, 30, 10))
        browser.expireOperations(10)
        assertEquals(BrowserLoadState.CANCELLED, browser.state.selectedTab!!.loadState)
        assertNull(browser.state.selectedTab!!.error)
    }

    @Test fun rendererFailureAffectsOnlyKnownGroupAndFreshRequestCanRecover() {
        val browser = controller()
        val firstRequest = browser.beginNavigation("tab", first, operation())
        browser.openTab("other", "other-session")
        val other = browser.beginNavigation("other", second, operation("other"))
        assertEquals(listOf(firstRequest.context), browser.rendererGone(setOf("tab", "unknown")))
        assertTrue(browser.acceptPageFinished(other, second, false, false, 10))
        assertFalse(browser.acceptPageFinished(firstRequest, first, false, false, 10))
        val retry = browser.reload("tab", operation("retry"))!!
        assertTrue(browser.acceptPageFinished(retry, first, false, false, 20))
    }

    @Test fun closeRemovesCallbacksAndChoosesStableRemainingTab() {
        val browser = controller()
        val request = browser.beginNavigation("tab", first, operation())
        browser.openTab("other", "other-session")
        browser.selectTab("tab")
        browser.closeTab("tab")
        assertEquals("other", browser.state.selectedTabId)
        assertFalse(browser.acceptPageFinished(request, first, false, false, 10))
        browser.closeTab("other")
        assertNull(browser.state.selectedTabId)
    }

    @Test fun incognitoNeverEntersNormalCheckpointAndRestoreIsIdle() {
        val browser = controller()
        browser.beginNavigation("tab", first, operation())
        browser.openTab("secret", "private-session", incognito = true)
        browser.beginNavigation("secret", second, operation("private"))
        val checkpoint = browser.persistableSession()
        assertEquals(listOf("tab"), checkpoint.tabs.map { it.tabId })
        assertEquals("tab", checkpoint.selectedTabId)
        val restored = BrowserSessionController()
        restored.restore(checkpoint) { "restored-$it" }
        assertEquals(first, restored.state.selectedTab!!.address)
        assertEquals(BrowserLoadState.IDLE, restored.state.selectedTab!!.loadState)
        assertEquals("restored-tab", restored.state.selectedTab!!.context.sessionId)
    }

    @Test fun restoredCallbackCannotCrossSessionBoundary() {
        val old = controller()
        val request = old.beginNavigation("tab", first, operation())
        val restored = BrowserSessionController()
        restored.restore(old.persistableSession()) { "fresh-session" }
        restored.beginNavigation("tab", second, operation("new"))
        assertFalse(restored.acceptPageFinished(request, first, false, false, 20))
    }

    @Test fun redirectsAndHistoryNavigationStayInsideCurrentEpoch() {
        val browser = controller()
        val request = browser.beginNavigation("tab", first, operation())
        assertTrue(browser.acceptPageStarted(request, second, 10))
        assertTrue(browser.acceptPageFinished(request, second, true, false, 20))
        val back = browser.beginHistoryNavigation("tab", operation("back"))
        assertTrue(browser.acceptPageFinished(back, first, false, true, 30))
        assertTrue(browser.state.selectedTab!!.canGoForward)
        assertFalse(browser.acceptPageStarted(request, second, 31))
    }

    @Test(expected = IllegalArgumentException::class) fun oldSessionIdsCannotBeReused() {
        val browser = controller()
        browser.closeTab("tab")
        browser.openTab("tab", "session")
    }

    @Test fun onlyWebAddressesAreAcceptedAndToStringIsRedacted() {
        listOf("javascript:alert(1)", "file:///sdcard/file", "intent://test", "https://", "https://user:secret@example.test", "https://exa mple.test", "https://:80", "https://example.test:999999", "https://example.test/%zz", "https://example..test").forEach {
            assertThrows(IllegalArgumentException::class.java) { PageAddress(it) }
        }
        assertFalse(first.toString().contains("example.test"))
    }

    private fun error() = ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY)
}
