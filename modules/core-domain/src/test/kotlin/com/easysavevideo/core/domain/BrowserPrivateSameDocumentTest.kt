package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class BrowserPrivateSameDocumentTest {
    private val a = PageAddress("https://private.invalid/watch?item=1#start")
    private val b = PageAddress("https://private.invalid/other?item=2#next")
    private fun operation(id: String = "initial") = OperationContext(id, 100, CancellationSource())
    private fun loaded(private: Boolean = true): Pair<BrowserSessionController, BrowserRequest> {
        val owner = BrowserSessionController().also { it.openTab("tab", "session", private) }
        val request = owner.beginNavigation("tab", a, operation())
        assertTrue(owner.acceptTitle(request, "Original title", 1))
        assertTrue(owner.acceptPageFinished(request, a, false, false, 2))
        return owner to request
    }

    @Test fun privateUpdateChangesOnlyAddressAndRamFlagsWithoutNewRequest() {
        val (owner, request) = loaded(); val before = owner.state.selectedTab!!
        assertTrue(owner.acceptPrivateSameDocument(request, b, true, true))
        assertEquals(before.copy(address = b, canGoBack = true, canGoForward = true), owner.state.selectedTab)
        assertTrue(owner.isCurrent(request)); assertEquals(a, request.address)
        assertFalse(owner.acceptPageFinished(request, a, false, false, 3))
        assertFalse(owner.acceptProgress(request, 10, 4))
        assertEquals(b, owner.state.selectedTab!!.address)
        val reload = owner.reload("tab", operation("reload"))!!
        assertEquals(b, reload.address); assertEquals(request.epoch + 1, reload.epoch)
        assertFalse(owner.isCurrent(request))
    }

    @Test fun explicitPartitionMethodsNeverAcceptTheOtherPartition() {
        val (private, privateRequest) = loaded(); val privateBefore = private.state
        assertFalse(private.acceptSameDocument(privateRequest, b, true, true))
        assertSame(privateBefore, private.state)
        val (ordinary, ordinaryRequest) = loaded(false); val ordinaryBefore = ordinary.state
        assertFalse(ordinary.acceptPrivateSameDocument(ordinaryRequest, b, true, true))
        assertSame(ordinaryBefore, ordinary.state)
    }

    @Test fun completedDeadlineAndBackgroundSelectionAreIndependentOfObservation() {
        val (owner, request) = loaded()
        owner.openTab("visible", "visible-session", true); val visible = owner.state.selectedTab
        assertEquals(OperationStopReason.DEADLINE_EXCEEDED, request.operation.stopReason(60_001))
        assertTrue(owner.expireOperations(60_001).isEmpty())
        assertTrue(owner.acceptPrivateSameDocument(request, b, true, false))
        assertSame(visible, owner.state.selectedTab); assertEquals("visible", owner.state.selectedTabId)
        assertTrue(owner.isCurrent(request))
        owner.selectTab("tab"); assertEquals(b, owner.state.selectedTab!!.address)
    }

    @Test fun exactRequestContextSessionAndEpochRequired() {
        val (owner, request) = loaded(); val before = owner.state
        for (wrong in listOf(request.copy(), request.copy(context = BrowserContextRef("tab", "replacement")),
            request.copy(context = BrowserContextRef("other", "session")), request.copy(epoch = request.epoch + 1))) {
            assertFalse(owner.acceptPrivateSameDocument(wrong, b, true, true)); assertSame(before, owner.state)
        }
        owner.beginNavigation("tab", a, operation("new")); val newer = owner.state
        assertFalse(owner.acceptPrivateSameDocument(request, b, true, true)); assertSame(newer, owner.state)
    }

    @Test fun allNonReadyStatesRejectWithoutMutation() {
        for (state in listOf(BrowserLoadState.LOADING, BrowserLoadState.FAILED,
            BrowserLoadState.CANCELLED, BrowserLoadState.RENDERER_UNAVAILABLE)) {
            val owner = BrowserSessionController().also { it.openTab("tab", "session", true) }
            val request = owner.beginNavigation("tab", a, operation())
            when (state) {
                BrowserLoadState.FAILED -> owner.acceptPageFailed(request,
                    ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY), 1)
                BrowserLoadState.CANCELLED -> owner.cancelNavigation("tab")
                BrowserLoadState.RENDERER_UNAVAILABLE -> owner.rendererGone(setOf("tab"))
                else -> Unit
            }
            val before = owner.state
            assertFalse(state.name, owner.acceptPrivateSameDocument(request, b, true, true)); assertSame(before, owner.state)
        }
    }

    @Test fun closedAndReplacementSessionCannotBorrowOldRequest() {
        val (owner, request) = loaded(); owner.closeTab("tab")
        assertFalse(owner.acceptPrivateSameDocument(request, b, true, true))
        owner.openTab("tab", "replacement", true)
        val next = owner.beginNavigation("tab", a, operation("new"))
        owner.acceptPageFinished(next, a, false, false, 2); val newer = owner.state
        assertFalse(owner.acceptPrivateSameDocument(request, b, true, true)); assertSame(newer, owner.state)
    }

    @Test fun updatedPrivateAddressNeverEntersMixedCheckpoint() {
        val (owner, request) = loaded(); assertTrue(owner.acceptPrivateSameDocument(request, b, true, false))
        assertTrue(owner.persistableSession().tabs.isEmpty())
        owner.openTab("ordinary", "ordinary-session")
        val ordinary = owner.beginNavigation("ordinary", PageAddress("https://ordinary.invalid/"), operation("ordinary"))
        owner.acceptPageFinished(ordinary, ordinary.address!!, false, false, 2)
        owner.selectTab("tab")
        assertEquals(BrowserSessionCheckpoint(listOf(RestorableTab("ordinary", ordinary.address)), "ordinary"), owner.persistableSession())
    }
}
