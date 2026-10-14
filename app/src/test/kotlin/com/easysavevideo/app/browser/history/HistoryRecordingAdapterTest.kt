package com.easysavevideo.app.browser.history

import com.easysavevideo.app.browser.NavigationAdmission
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual core/gate/history state machines with owned synthetic View identities and memory storage.
 * This suite neither constructs WebView nor claims device callback/lifecycle acceptance. */
class HistoryRecordingAdapterTest {
    private class Store : HistoryStorage {
        var items = emptyList<BrowserHistoryItem>()
        var writes = 0
        var failWrite = false
        override fun load() = items
        override fun save(items: List<BrowserHistoryItem>) {
            check(!failWrite)
            this.items = HistoryCodec.decode(HistoryCodec.encode(items)); writes++
        }
    }
    private class Harness(incognito: Boolean = false) {
        val core = BrowserSessionController()
        val context = core.openTab("tab", "session", incognito).context
        val view = Any()
        val gate = HistoryNavigationGate()
        val store = Store()
        val work = ArrayDeque<() -> Unit>()
        val delivery = ArrayDeque<() -> Unit>()
        var time = 100L
        private var id = 0
        val history = HistoryController(store, gate, HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS),
            { work.addLast(it) }, { delivery.addLast(it) }, { historyId(++id) }, { time }, {})
        val recorder = HistoryRecordingAdapter(context, view, gate, history::record)
        lateinit var request: BrowserRequest
        lateinit var admission: NavigationAdmission
        var address = "https://example.invalid/watch?q=owned#start"
        init { history.load(); flush() }
        fun operation() = OperationContext("operation", time + 60_000, CancellationSource())
        fun tab() = core.state.tabs.firstOrNull { it.context == context }
        fun flush() {
            while (work.isNotEmpty() || delivery.isNotEmpty()) {
                while (work.isNotEmpty()) work.removeFirst()()
                while (delivery.isNotEmpty()) delivery.removeFirst()()
            }
        }
        fun begin(controlled: Boolean = true, target: String = address, historyNavigation: Boolean = false) {
            address = target
            request = if (historyNavigation) core.beginHistoryNavigation(context.tabId, operation())
                else core.beginNavigation(context.tabId, PageAddress(address), operation())
            admission = NavigationAdmission(address)
            if (controlled) recorder.controlledNavigationRequested()
        }
        fun start(callbackView: Any = view, current: Boolean = true, active: Boolean = true,
            source: HistoryRecordingAdapter = recorder): Boolean {
            val admitted = admission.onStarted(address)
            val accepted = admitted && core.acceptPageStarted(request, PageAddress(address), time)
            source.started(request, tab(), core.state.selectedTab?.context, callbackView, address,
                admission, current && core.isCurrent(request), accepted, active)
            return accepted
        }
        fun finish(callbackView: Any = view, actual: String? = address, current: Boolean = true,
            active: Boolean = true, source: HistoryRecordingAdapter = recorder): Boolean {
            val accepted = admission.accepts(address) &&
                core.acceptPageFinished(request, PageAddress(address), false, false, time)
            source.finished(request, tab(), core.state.selectedTab?.context, callbackView, actual, address,
                admission, "Owned\nTitle", current && core.isCurrent(request), accepted, active, time)
            return accepted
        }
        fun complete() { begin(); assertTrue(start()); assertTrue(finish()); flush() }
        fun redirect(target: String) { address = target; admission.allowRedirect(target); assertTrue(start()) }
    }

    @Test fun acceptedRealCoreCompletionCommitsOnlyAfterWorkerAndDelivery() {
        val h = Harness(); h.begin(); assertTrue(h.start()); assertTrue(h.finish())
        assertTrue(h.history.state.items.isEmpty()); assertEquals(1, h.work.size)
        h.work.removeFirst()(); assertTrue(h.history.state.items.isEmpty())
        h.delivery.removeFirst()()
        assertEquals(h.address, h.history.state.items.single().address)
        assertEquals("Owned Title", h.history.state.items.single().title)
    }
    @Test fun duplicateFinishCannotBorrowReadyCoreState() {
        val h = Harness(); h.complete(); val first = h.history.state.items
        assertFalse(h.finish()); h.flush(); assertEquals(first, h.history.state.items); assertEquals(1, h.store.writes)
    }
    @Test fun commandWithoutAcceptedActualStartNeverRecords() {
        val h = Harness(); h.begin(); assertFalse(h.finish()); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun restoredBundleCallbacksCannotArmRecorderEvenWhenCoreAcceptsThem() {
        val h = Harness(); h.core.closeTab("tab")
        h.core.restore(BrowserSessionCheckpoint(listOf(RestorableTab("tab", PageAddress(h.address))), "tab")) { "restored" }
        val ref = requireNotNull(h.core.state.selectedTab).context
        val restoredView = Any()
        val restored = HistoryRecordingAdapter(ref, restoredView, h.gate, h.history::record)
        val request = h.core.beginNavigation("tab", PageAddress(h.address), h.operation())
        val admission = NavigationAdmission(h.address)
        assertTrue(admission.onStarted(h.address))
        val started = h.core.acceptPageStarted(request, PageAddress(h.address), h.time)
        restored.started(request, h.core.state.selectedTab, ref, restoredView, h.address, admission, true, started, true)
        val finished = h.core.acceptPageFinished(request, PageAddress(h.address), false, false, h.time)
        restored.finished(request, h.core.state.selectedTab, ref, restoredView, h.address, h.address, admission,
            "Restored", true, finished, true, h.time)
        assertTrue(started); assertTrue(finished); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun unarmedImplicitNavigationNeverRecords() {
        val h = Harness(); h.begin(controlled = false); assertTrue(h.start()); assertTrue(h.finish())
        h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun laterPostOrFormDocumentInLiveViewGetsNewCoreRequestAndPermit() {
        val h = Harness(); h.complete(); val firstEpoch = h.request.epoch
        h.begin(controlled = false, target = "https://example.invalid/form-result")
        assertTrue(h.request.epoch > firstEpoch); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(2, h.store.items.size); assertEquals(h.address, h.store.items.first().address)
    }
    @Test fun sameDocumentHashOrHistoryUpdateWithoutStartCannotCreateVisit() {
        val h = Harness(); h.complete(); val items = h.store.items
        for (url in listOf("https://example.invalid/watch?q=owned#other", "https://example.invalid/push-state")) {
            h.address = url
            assertFalse(h.finish()); h.flush(); assertEquals(items, h.store.items)
        }
        assertEquals(1, h.store.writes)
    }
    @Test fun firstStartRequiresSelectedOrdinaryForegroundContext() {
        for (kind in listOf("incognito", "background", "unselected")) {
            val h = Harness(incognito = kind == "incognito"); h.begin()
            if (kind == "unselected") h.core.openTab("other", "other-session")
            assertTrue(h.start(active = kind != "background"))
            h.core.selectTab("tab")
            assertTrue(h.finish()); h.flush(); assertTrue(kind, h.store.items.isEmpty())
        }
    }
    @Test fun finishRequiresForegroundAndBrowserSurfaceAllowed() {
        // The host composes foreground && browserSurfaceAllowed into this active boundary.
        val h = Harness(); h.begin(); assertTrue(h.start()); assertTrue(h.finish(active = false))
        assertFalse(h.finish(active = true)); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun unselectedCompletionNeverWrites() {
        val h = Harness(); h.begin(); assertTrue(h.start()); h.core.openTab("other", "other-session")
        assertTrue(h.finish()); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun leavingAndReturningCannotRevivePreLeavePermit() {
        val h = Harness(); h.begin(); assertTrue(h.start()); h.recorder.cancel()
        h.core.openTab("other", "other-session"); h.core.selectTab("tab")
        h.redirect("https://example.invalid/redirect-after-return")
        assertTrue(h.finish()); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun exactEntryAndViewAreRequiredAtStartAndFinish() {
        for (boundary in listOf("start-view", "start-entry", "finish-view", "finish-entry")) {
            val h = Harness(); h.begin()
            assertTrue(h.start(callbackView = if (boundary == "start-view") Any() else h.view,
                current = boundary != "start-entry"))
            assertTrue(h.finish(callbackView = if (boundary == "finish-view") Any() else h.view,
                current = boundary != "finish-entry"))
            h.flush(); assertTrue(boundary, h.store.items.isEmpty())
        }
    }
    @Test fun actualAddressMustMatchTheAdmittedCoreCompletion() {
        val h = Harness(); h.begin(); assertTrue(h.start()); assertTrue(h.finish(actual = "https://example.invalid/stale"))
        h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun rejectedAdmissionAndExpiredCoreStartCannotCreatePermit() {
        for (expired in listOf(false, true)) {
            val h = Harness(); h.begin()
            if (expired) h.time += 60_000 else h.admission = NavigationAdmission("https://example.invalid/other")
            assertFalse(h.start()); assertFalse(h.finish()); h.flush(); assertTrue(h.store.items.isEmpty())
        }
    }
    @Test fun failedStoppedTimedOutOrRendererGoneCoreCompletionCannotWrite() {
        for (failure in listOf("failed", "stopped", "timeout", "renderer")) {
            val h = Harness(); h.begin(); assertTrue(h.start())
            when (failure) {
                "failed" -> assertTrue(h.core.acceptPageFailed(h.request,
                    ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY), h.time))
                "stopped" -> h.core.cancelNavigation("tab")
                "timeout" -> { h.time += 60_000; h.core.expireOperations(h.time) }
                "renderer" -> h.core.rendererGone(setOf("tab"))
            }
            assertFalse(h.finish()); h.flush(); assertTrue(failure, h.store.items.isEmpty())
        }
    }
    @Test fun anOlderRequestCannotFinishAfterANewerEpoch() {
        val h = Harness(); h.begin(); assertTrue(h.start()); val old = h.request; val oldAdmission = h.admission
        h.begin(target = "https://example.invalid/newer"); assertTrue(h.start())
        assertFalse(h.core.acceptPageFinished(old, requireNotNull(old.address), false, false, h.time))
        h.recorder.finished(old, h.tab(), h.context, h.view, old.address?.value, old.address!!.value, oldAdmission,
            "Old", false, false, true, h.time)
        assertTrue(h.finish()); h.flush(); assertEquals(h.address, h.store.items.single().address)
    }
    @Test fun acceptedRedirectRecordsOnlyItsFinalAddress() {
        val h = Harness(); h.begin(); assertTrue(h.start()); val first = h.address
        h.redirect("https://example.invalid/redirected?q=owned#position")
        assertTrue(h.finish()); h.flush(); assertEquals(h.address, h.store.items.single().address)
        assertNotEquals(first, h.store.items.single().address)
    }
    @Test fun confirmedClearThenSameRequestRedirectCannotMintNewAuthority() {
        val h = Harness(); h.complete(); h.begin(target = "https://example.invalid/in-flight"); assertTrue(h.start())
        h.history.confirm(requireNotNull(h.history.requestClear())); h.flush()
        h.redirect("https://example.invalid/after-clear-redirect"); assertTrue(h.finish()); h.flush()
        assertTrue(h.store.items.isEmpty()); assertEquals(HistoryNotice.CLEARED, h.history.state.notice)
        h.begin(target = "https://example.invalid/post-clear-navigation"); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(h.address, h.store.items.single().address)
    }
    @Test fun removingOneItemAlsoInvalidatesOutstandingPermits() {
        val h = Harness(); h.complete(); val item = h.store.items.single()
        h.begin(target = "https://example.invalid/in-flight"); assertTrue(h.start())
        val row = requireNotNull(h.history.row(item)); h.history.confirm(requireNotNull(h.history.requestRemove(row))); h.flush()
        h.redirect("https://example.invalid/late"); assertTrue(h.finish()); h.flush(); assertTrue(h.store.items.isEmpty())
    }
    @Test fun cancelledClearDoesNotEraseLegitimateNavigation() {
        val h = Harness(); h.begin(); assertTrue(h.start())
        val confirmation = requireNotNull(h.history.requestClear()); h.history.cancel(confirmation); h.history.confirm(confirmation)
        assertTrue(h.finish()); h.flush(); assertEquals(1, h.store.items.size)
    }
    @Test fun reloadAndBackForwardNeedNewStartAndUpdateStableRecord() {
        val h = Harness(); h.complete(); val id = h.store.items.single().id
        h.time++; h.begin(historyNavigation = true); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(id, h.store.items.single().id); assertEquals(h.time, h.store.items.single().visitedAtMillis)
        h.time++; h.request = requireNotNull(h.core.reload("tab", h.operation())); h.admission = NavigationAdmission(h.address)
        h.recorder.controlledNavigationRequested(); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(id, h.store.items.single().id); assertEquals(3, h.store.writes)
    }
    @Test fun processOwnedControllerKeepsSerialCommitAndClearAcrossRecorderReplacement() {
        val h = Harness(); h.begin(); assertTrue(h.start()); assertTrue(h.finish())
        h.recorder.cancel() // Host detaches, but the process controller and worker stay alive.
        val newView = Any(); val next = HistoryRecordingAdapter(h.context, newView, h.gate, h.history::record)
        assertNull(h.history.requestClear()); assertEquals(1, h.work.size)
        h.flush(); h.history.confirm(requireNotNull(h.history.requestClear())); assertEquals(1, h.work.size)
        h.begin(target = "https://example.invalid/new-host"); next.controlledNavigationRequested()
        assertTrue(h.start(callbackView = newView, source = next)); assertTrue(h.finish(callbackView = newView, source = next))
        assertEquals(1, h.work.size); h.flush()
        assertEquals(h.address, h.store.items.single().address); assertEquals(3, h.store.writes)
    }
    @Test fun writeFailureDisablesHistoryWithoutUndoingAcceptedBrowserFinish() {
        val h = Harness(); h.store.failWrite = true; h.begin(); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(BrowserLoadState.READY, h.core.state.selectedTab?.loadState)
        assertEquals(HistoryNotice.UNAVAILABLE, h.history.state.notice); assertFalse(h.history.state.ready)
    }
    @Test fun approvedThirtyDayExpiryAndTwoHundredLimitAreAppliedToAcceptedVisits() {
        val h = Harness(); h.complete(); h.time += HistoryPolicy.THIRTY_DAYS_MILLIS
        h.begin(target = "https://example.invalid/recent"); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        assertEquals(h.address, h.store.items.single().address)
        repeat(205) { i ->
            h.begin(target = "https://example.invalid/recent/$i"); assertTrue(h.start()); assertTrue(h.finish()); h.flush()
        }
        assertEquals(200, h.store.items.size); assertEquals(h.address, h.store.items.first().address)
    }
}
