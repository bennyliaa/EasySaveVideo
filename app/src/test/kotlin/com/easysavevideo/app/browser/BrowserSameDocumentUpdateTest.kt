package com.easysavevideo.app.browser

import com.easysavevideo.app.save.SaveInspectionPage
import com.easysavevideo.app.storage.BrowserSessionCodec
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pure Host seam with synthetic native reads; Android/WebView execution is separate. */
class BrowserSameDocumentUpdateTest {
    private val a = "https://example.invalid/watch?item=1#start"
    private val b = "https://example.invalid/other/path?item=2#next"
    private fun operation(id: String = "initial") = OperationContext(id, 100, CancellationSource())
    private inner class Fixture(incognito: Boolean = false) {
        val owner = BrowserSessionController().also { it.openTab("tab", "session", incognito) }
        val request = owner.beginNavigation("tab", PageAddress(a), operation())
        val document = BrowserInspectionDocument()
        val view = Any()
        var actual: String? = a
        var live = true
        var reads = 0
        init {
            assertTrue(owner.acceptTitle(request, "Page title", 1))
            assertTrue(owner.acceptPageFinished(request, PageAddress(a), false, false, 2))
            document.started(); document.finished(a, a)
        }
        fun history(url: String = b, native: String? = url, reload: Boolean = false,
            current: Boolean = true, ready: Boolean = true) {
            actual = native
            document.history(url, native, reload, current, ready)
        }
        fun page() = SaveInspectionPage(request.context, request.epoch, true, document.address,
            actual, view, 0, document.revision)
        fun accept(address: String? = document.address, revision: Long = document.revision,
            back: () -> Boolean = { true }, forward: () -> Boolean = { false },
            nativeUrl: () -> String? = { actual }): Boolean =
            BrowserSameDocumentUpdate.accept(owner, request, document, address, revision,
                canGoBack = { reads++; back() }, canGoForward = { reads++; forward() },
                viewUrl = { reads++; nativeUrl() }, stillCurrent = { live })
    }

    @Test fun pathQueryHashAndCombinedChangesUpdateModelAndRefreshTarget() {
        for (url in listOf("https://example.invalid/other/path?item=1#start",
            "https://example.invalid/watch?item=2#start", "https://example.invalid/watch?item=1#next", b)) {
            val f = Fixture(); val before = f.owner.state.selectedTab!!; val old = f.page()
            f.history(url)
            assertTrue(f.accept())
            assertEquals(before.copy(address = PageAddress(url), canGoBack = true), f.owner.state.selectedTab)
            assertEquals(3, f.reads)
            assertFalse(old.matches(f.page(), true))
            assertTrue(f.owner.isCurrent(f.request))
            val reload = f.owner.reload("tab", operation("refresh"))!!
            assertEquals(PageAddress(url), reload.address)
            assertEquals(f.request.epoch + 1, reload.epoch)
            assertFalse(f.owner.isCurrent(f.request))
        }
    }

    @Test fun sameAddressReplaceAndABAStillRetireOldSelectionsAndReadActualFlags() {
        val f = Fixture(); val old = f.page()
        f.history(a); assertTrue(f.accept(back = { false }, forward = { true }))
        assertFalse(old.matches(f.page(), true))
        assertFalse(f.owner.state.selectedTab!!.canGoBack)
        assertTrue(f.owner.state.selectedTab!!.canGoForward)
        f.history(); assertTrue(f.accept())
        f.history(a); assertTrue(f.accept(back = { false }, forward = { false }))
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
        assertFalse(old.matches(f.page(), true))
        assertEquals(9, f.reads)
        assertEquals(1L, f.owner.state.selectedTab!!.epoch)
    }

    @Test fun duplicateOldFinishAndProgressCannotRollBackAcceptedAddress() {
        val f = Fixture(); f.history(); assertTrue(f.accept())
        assertFalse(f.owner.acceptPageFinished(f.request, PageAddress(a), false, false, 3))
        assertFalse(f.owner.acceptProgress(f.request, 2, 4))
        assertEquals(b, f.owner.state.selectedTab!!.address!!.value)
        val next = f.owner.beginNavigation("tab", PageAddress(a), operation("next"))
        assertTrue(f.owner.acceptPageStarted(next, PageAddress(a), 5))
        assertTrue(f.owner.acceptPageFinished(next, PageAddress(a), false, true, 6))
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
    }

    @Test fun backgroundOrdinaryObservationDoesNotRequireSelectionOrUnexpiredLoad() {
        val f = Fixture(); f.owner.openTab("visible", "visible-session")
        val selected = f.owner.state.selectedTab
        assertTrue(f.owner.expireOperations(60_001).isEmpty())
        f.history(); assertTrue(f.accept())
        assertSame(selected, f.owner.state.selectedTab)
        f.owner.selectTab("tab"); assertEquals(b, f.owner.state.selectedTab!!.address!!.value)
    }

    @Test fun privateAndRetiredHostCannotEvenReadNativeFlags() {
        val incognito = Fixture(incognito = true); incognito.history()
        assertFalse(incognito.accept()); assertEquals(0, incognito.reads)
        val retired = Fixture(); retired.history(); retired.live = false
        assertFalse(retired.accept()); assertEquals(0, retired.reads)
        assertEquals(a, retired.owner.state.selectedTab!!.address!!.value)
    }

    @Test fun invalidCrossOriginAndNativeMismatchedObservationsRetireWithoutUpdating() {
        for (url in listOf("https://other.invalid/watch", "http://example.invalid/watch",
            "https://example.invalid:444/watch", "https://user:secret@example.invalid/watch",
            "javascript:alert(1)", " https://example.invalid/watch", "https://example.invalid/watch ")) {
            val f = Fixture(); val old = f.page(); f.history(url)
            assertFalse(f.accept()); assertEquals(0, f.reads)
            assertFalse(old.matches(f.page(), true))
            assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
        }
        val f = Fixture(); f.history(native = a)
        assertFalse(f.accept()); assertEquals(0, f.reads)
    }

    @Test fun reloadNotCurrentNotReadyAndUnfinishedObservationsCannotUpdate() {
        for (flags in listOf(Triple(true, true, true), Triple(false, false, true), Triple(false, true, false))) {
            val f = Fixture(); f.history(reload = flags.first, current = flags.second, ready = flags.third)
            assertFalse(f.accept()); assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
        }
        val f = Fixture(); f.document.started(); f.history()
        assertFalse(f.accept()); assertEquals(0, f.reads)
    }

    @Test fun nativeGetterExceptionsNeverBecomeGuessedFlagsOrModelSuccess() {
        for (getter in 0..2) {
            val f = Fixture(); f.history(); val before = f.owner.state
            assertFalse(f.accept(back = { if (getter == 0) throw IllegalStateException() else true },
                forward = { if (getter == 1) throw IllegalStateException() else false },
                nativeUrl = { if (getter == 2) throw IllegalStateException() else f.actual }))
            assertSame(before, f.owner.state)
            assertEquals(getter + 1, f.reads)
        }
    }

    @Test fun gettersCannotCommitAcrossExactHostRetirementAtAnyRead() {
        for (getter in 0..2) {
            val f = Fixture(); f.history(); val before = f.owner.state
            assertFalse(f.accept(back = { if (getter == 0) f.live = false; true },
                forward = { if (getter == 1) f.live = false; false },
                nativeUrl = { if (getter == 2) f.live = false; f.actual }))
            assertSame(before, f.owner.state)
        }
    }

    @Test fun newerObservationBetweenReadsCannotBeOverwrittenEvenAtSameAddress() {
        for (getter in 0..2) for (url in listOf(a, b, "https://example.invalid/newer")) {
            val f = Fixture(); f.history()
            fun newer() { f.history(url); assertTrue(f.accept(back = { false }, forward = { true })) }
            assertFalse(f.accept(back = { if (getter == 0) newer(); true },
                forward = { if (getter == 1) newer(); false },
                nativeUrl = { if (getter == 2) newer(); f.actual }))
            assertEquals(url, f.owner.state.selectedTab!!.address!!.value)
            assertFalse(f.owner.state.selectedTab!!.canGoBack)
            assertTrue(f.owner.state.selectedTab!!.canGoForward)
        }
    }

    @Test fun nativeUrlChangeWithoutHistoryCannotCommit() {
        val f = Fixture(); f.history(); val before = f.owner.state
        assertFalse(f.accept(forward = { f.actual = a; false }))
        assertSame(before, f.owner.state)
    }

    @Test fun newRequestFailureCancellationRendererAndReplacedTabDuringReadCannotCommit() {
        for (event in 0..4) {
            val f = Fixture(); f.history()
            var newerState: BrowserSessionState? = null
            assertFalse(f.accept(back = {
                when (event) {
                    0 -> f.owner.beginNavigation("tab", PageAddress(a), operation("new"))
                    1 -> {
                        val r = f.owner.beginNavigation("tab", PageAddress(a), operation("new"))
                        f.owner.acceptPageFailed(r, ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY), 3)
                    }
                    2 -> { f.owner.beginNavigation("tab", PageAddress(a), operation("new")); f.owner.cancelNavigation("tab") }
                    3 -> f.owner.rendererGone(setOf("tab"))
                    4 -> {
                        f.owner.closeTab("tab"); f.owner.openTab("tab", "replacement")
                        val r = f.owner.beginNavigation("tab", PageAddress(a), operation("replacement"))
                        f.owner.acceptPageFinished(r, PageAddress(a), false, false, 3)
                    }
                }
                newerState = f.owner.state
                true
            }))
            assertSame(newerState, f.owner.state)
            assertNotEquals(b, f.owner.state.selectedTab!!.address!!.value)
        }
    }

    @Test fun capturedRevisionCannotBorrowLaterAcceptedDocumentBeforeNativeReads() {
        val f = Fixture(); f.history(); val revision = f.document.revision
        f.history(a)
        assertFalse(f.accept(b, revision)); assertEquals(0, f.reads)
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
    }

    @Test fun updatedCheckpointRoundTripsAndRestoreIsIdleWithPrivateExcluded() {
        val f = Fixture(); f.history(); assertTrue(f.accept())
        f.owner.openTab("private", "private-session", incognito = true)
        f.owner.beginNavigation("private", PageAddress("https://private.invalid/"), operation("private"))
        val checkpoint = f.owner.persistableSession()
        val decoded = BrowserSessionCodec.decode(BrowserSessionCodec.encode(checkpoint))
        assertEquals(checkpoint, decoded)
        assertEquals(listOf(RestorableTab("tab", PageAddress(b))), decoded.tabs)
        val restored = BrowserSessionController()
        restored.restore(decoded) { "restored-$it" }
        assertEquals(b, restored.state.selectedTab!!.address!!.value)
        assertEquals(BrowserLoadState.IDLE, restored.state.selectedTab!!.loadState)
        assertEquals(0L, restored.state.selectedTab!!.epoch)
    }
}
