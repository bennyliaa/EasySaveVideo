package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class BrowserSameDocumentTest {
    private val a = PageAddress("https://example.invalid/watch?item=1#start")
    private val b = PageAddress("https://example.invalid/other?item=2#next")
    private fun operation(id: String = "initial") = OperationContext(id, 100, CancellationSource())
    private fun loaded(incognito: Boolean = false): Pair<BrowserSessionController, BrowserRequest> {
        val owner = BrowserSessionController().also { it.openTab("tab", "session", incognito) }
        val request = owner.beginNavigation("tab", a, operation())
        assertTrue(owner.acceptTitle(request, "Original title", 1))
        assertTrue(owner.acceptPageFinished(request, a, false, false, 2))
        return owner to request
    }

    @Test fun ordinaryUpdateChangesOnlyAddressAndObservedFlagsWithoutNewRequest() {
        val (owner, request) = loaded()
        val before = owner.state.selectedTab!!
        assertTrue(owner.acceptSameDocument(request, b, true, true))
        assertEquals(before.copy(address = b, canGoBack = true, canGoForward = true), owner.state.selectedTab)
        assertTrue(owner.isCurrent(request))
        assertEquals(a, request.address)
        assertFalse(owner.acceptPageFinished(request, a, false, false, 3))
        assertFalse(owner.acceptProgress(request, 10, 4))
        assertEquals(b, owner.state.selectedTab!!.address)
        val reload = owner.reload("tab", operation("reload"))!!
        assertEquals(b, reload.address)
        assertEquals(request.epoch + 1, reload.epoch)
        assertFalse(owner.isCurrent(request))
        assertTrue(owner.acceptPageStarted(reload, b, 5))
        assertTrue(owner.acceptPageFinished(reload, b, true, false, 6))
    }

    @Test fun completedLoadDeadlineDoesNotExpireSameDocumentOrChangeRequest() {
        val (owner, request) = loaded()
        assertEquals(OperationStopReason.DEADLINE_EXCEEDED, request.operation.stopReason(60_001))
        assertTrue(owner.expireOperations(60_001).isEmpty())
        assertTrue(owner.acceptSameDocument(request, b, true, false))
        assertTrue(owner.isCurrent(request))
        assertEquals(BrowserLoadState.READY, owner.state.selectedTab!!.loadState)
    }

    @Test fun backgroundUpdatePreservesVisibleTabAndSelection() {
        val (owner, request) = loaded()
        owner.openTab("visible", "visible-session")
        val visible = owner.state.selectedTab
        assertTrue(owner.acceptSameDocument(request, b, true, false))
        assertSame(visible, owner.state.selectedTab)
        assertEquals("visible", owner.state.selectedTabId)
        owner.selectTab("tab")
        assertEquals(b, owner.state.selectedTab!!.address)
    }

    @Test fun exactRequestIdentityContextAndEpochAreRequired() {
        val (owner, request) = loaded()
        val before = owner.state
        for (wrong in listOf(request.copy(), request.copy(context = BrowserContextRef("tab", "replacement")),
            request.copy(context = BrowserContextRef("other", "session")), request.copy(epoch = request.epoch + 1))) {
            assertFalse(owner.acceptSameDocument(wrong, b, true, true))
            assertSame(before, owner.state)
        }
        owner.beginNavigation("tab", a, operation("new"))
        assertFalse(owner.acceptSameDocument(request, b, true, true))
    }

    @Test fun privateReadyCannotUpdateOrEnterOrdinaryCheckpoint() {
        val (owner, request) = loaded(incognito = true)
        val before = owner.state
        assertFalse(owner.acceptSameDocument(request, b, true, true))
        assertSame(before, owner.state)
        assertTrue(owner.persistableSession().tabs.isEmpty())
    }

    @Test fun nonReadyClosedAndReplacedSessionsCannotUpdate() {
        for (state in listOf(BrowserLoadState.LOADING, BrowserLoadState.FAILED,
            BrowserLoadState.CANCELLED, BrowserLoadState.RENDERER_UNAVAILABLE)) {
            val owner = BrowserSessionController().also { it.openTab("tab", "session") }
            val request = owner.beginNavigation("tab", a, operation())
            when (state) {
                BrowserLoadState.FAILED -> owner.acceptPageFailed(request,
                    ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY), 1)
                BrowserLoadState.CANCELLED -> owner.cancelNavigation("tab")
                BrowserLoadState.RENDERER_UNAVAILABLE -> owner.rendererGone(setOf("tab"))
                else -> Unit
            }
            val before = owner.state
            assertFalse(owner.acceptSameDocument(request, b, true, true))
            assertSame(before, owner.state)
        }
        val (owner, request) = loaded()
        owner.closeTab("tab")
        assertFalse(owner.acceptSameDocument(request, b, true, true))
        owner.openTab("tab", "replacement")
        val next = owner.beginNavigation("tab", a, operation("new"))
        owner.acceptPageFinished(next, a, false, false, 2)
        assertFalse(owner.acceptSameDocument(request, b, true, true))
        assertEquals(a, owner.state.selectedTab!!.address)
    }

    @Test fun ordinaryCheckpointCarriesUpdatedAddressAndRestoresWithoutRequest() {
        val (owner, request) = loaded()
        assertTrue(owner.acceptSameDocument(request, b, true, false))
        owner.openTab("secret", "private", incognito = true)
        owner.beginNavigation("secret", a, operation("private"))
        val checkpoint = owner.persistableSession()
        assertEquals(listOf(RestorableTab("tab", b)), checkpoint.tabs)
        assertEquals("tab", checkpoint.selectedTabId)
        val restored = BrowserSessionController()
        restored.restore(checkpoint) { "restored-$it" }
        assertEquals(b, restored.state.selectedTab!!.address)
        assertEquals(BrowserLoadState.IDLE, restored.state.selectedTab!!.loadState)
        assertEquals(0L, restored.state.selectedTab!!.epoch)
        assertFalse(restored.isCurrent(request))
    }
}
