package com.easysavevideo.app.browser.history

import com.easysavevideo.app.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** Production presentation and controller, with synthetic storage and controlled IO delivery. */
class HistoryPanelPresentationTest {
    private class Harness {
        var saved = emptyList<BrowserHistoryItem>()
        var failRead = false; var failWrite = false; var uncertainWrite = false
        var reads = 0; var writes = 0
        val work = ArrayDeque<() -> Unit>(); val delivery = ArrayDeque<() -> Unit>()
        val controller = HistoryController(object : HistoryStorage {
            override fun load(): List<BrowserHistoryItem> { reads++; check(!failRead); return saved }
            override fun save(items: List<BrowserHistoryItem>) {
                writes++; check(!failWrite); saved = items; check(!uncertainWrite)
            }
        }, HistoryNavigationGate(), HistoryRetentionPolicy(HistoryPolicy.MAX_ITEMS, HistoryPolicy.THIRTY_DAYS_MILLIS),
            { work.addLast(it) }, { delivery.addLast(it) }, { historyId(2) }, { 1_000L }, {})
        val view get() = historyPanelStatus(controller.state)
        fun finish() {
            while (work.isNotEmpty() || delivery.isNotEmpty()) {
                while (work.isNotEmpty()) work.removeFirst()()
                while (delivery.isNotEmpty()) delivery.removeFirst()()
            }
        }
        fun load(withItem: Boolean = false) {
            if (withItem) saved = listOf(historyItem(time = 100))
            controller.load(); finish()
        }
        fun clearPrompt(canAct: () -> Boolean = { true }) =
            HistoryPanelPrompt(requireNotNull(controller.requestClear()), controller.state, canAct)
        fun removePrompt(): HistoryPanelPrompt {
            val row = requireNotNull(controller.row(controller.state.items.single()))
            return HistoryPanelPrompt(requireNotNull(controller.requestRemove(row)), controller.state, { true })
        }
        fun confirm(prompt: HistoryPanelPrompt) = confirmHistoryPanelPrompt(
            prompt, { controller.state }, controller::confirm, controller::cancel)
    }

    @Test fun initialReadShowsLoadingWithoutEmptySuccessOrClearUntilDelivered() {
        val h = Harness(); h.controller.load()
        assertEquals(R.string.history_loading, h.view.message); assertFalse(h.view.retry)
        assertFalse(h.view.rowsEnabled); assertFalse(h.view.clearEnabled); assertEquals(0, h.reads)
        h.work.removeFirst()(); assertEquals(R.string.history_loading, h.view.message)
        h.delivery.removeFirst()()
        assertEquals(R.string.history_empty, h.view.message); assertTrue(h.view.rowsEnabled)
        assertTrue(h.view.clearEnabled); assertEquals(0, h.writes)
    }

    @Test fun unreadableStoreRetainsExplicitResetButCannotOpenOrRemoveRows() {
        val h = Harness(); h.failRead = true; h.load(withItem = true)
        assertEquals(HistoryNotice.UNAVAILABLE, h.controller.state.notice)
        assertNull(h.view.message); assertTrue(h.view.retry); assertTrue(h.view.clearEnabled)
        assertFalse(h.view.rowsEnabled); assertEquals(0, h.writes)
        assertNull(h.controller.row(h.saved.single()))
        val prompt = h.clearPrompt(); h.confirm(prompt)
        assertFalse(h.view.clearEnabled); assertNull(h.controller.state.notice)
        h.finish(); assertEquals(HistoryNotice.CLEARED, h.controller.state.notice)
        assertEquals(R.string.history_empty, h.view.message); assertTrue(h.saved.isEmpty())
    }

    @Test fun dismissingFailureKeepsUnavailableStatusAndReadAgain() {
        val h = Harness(); h.failRead = true; h.load(); h.controller.dismissNotice()
        assertNull(h.controller.state.notice); assertEquals(R.string.history_unavailable, h.view.message)
        assertTrue(h.view.retry); assertFalse(h.view.rowsEnabled); assertTrue(h.view.clearEnabled)
        assertEquals(0, h.writes)
    }

    @Test fun removeNeedsExplicitConfirmationAndAcknowledgedCommitBeforeSuccess() {
        val h = Harness(); h.load(withItem = true); val before = h.controller.state.items
        val prompt = h.removePrompt()
        assertEquals(0, h.writes); assertEquals(before, h.controller.state.items)
        h.confirm(prompt)
        assertEquals(before, h.controller.state.items); assertNull(h.controller.state.notice)
        assertEquals(R.string.history_loading, h.view.message); assertFalse(h.view.rowsEnabled)
        h.work.removeFirst()(); assertEquals(before, h.controller.state.items)
        h.delivery.removeFirst()()
        assertEquals(HistoryNotice.REMOVED, h.controller.state.notice)
        assertEquals(R.string.history_removed, historyNoticeResource(requireNotNull(h.controller.state.notice)))
        assertEquals(R.string.history_empty, h.view.message); assertTrue(h.saved.isEmpty())
        h.confirm(prompt); h.finish(); assertEquals(1, h.writes)
    }

    @Test fun uncertainClearRetainsOldDisabledRowsUntilRereadReconcilesActualStorage() {
        val h = Harness(); h.load(withItem = true); val before = h.controller.state.items
        h.uncertainWrite = true; h.confirm(h.clearPrompt()); h.finish()
        assertEquals(before, h.controller.state.items); assertTrue(h.saved.isEmpty())
        assertEquals(HistoryNotice.UNAVAILABLE, h.controller.state.notice)
        assertFalse(h.view.rowsEnabled); assertTrue(h.view.retry); assertTrue(h.view.clearEnabled)
        assertNull(h.view.message); assertEquals(1, h.writes)
        h.uncertainWrite = false; h.controller.load()
        assertFalse(h.view.retry); assertFalse(h.view.clearEnabled); h.finish()
        assertEquals(R.string.history_empty, h.view.message); assertTrue(h.view.rowsEnabled)
        assertNull(h.controller.state.notice); assertEquals(1, h.writes)
    }

    @Test fun slowStorageHasOneNoticeAndNoRetrySecondClearOrEmptyClaim() {
        val h = Harness(); h.load(withItem = true); h.confirm(h.clearPrompt())
        h.controller.slow(requireNotNull(h.controller.operationGeneration()))
        assertEquals(HistoryNotice.SLOW, h.controller.state.notice); assertNull(h.view.message)
        assertFalse(h.view.rowsEnabled); assertFalse(h.view.retry); assertFalse(h.view.clearEnabled)
        h.controller.load(); assertNull(h.controller.requestClear()); assertEquals(1, h.work.size)
        h.controller.dismissNotice(); assertEquals(R.string.history_loading, h.view.message)
        h.finish(); assertEquals(HistoryNotice.CLEARED, h.controller.state.notice); assertEquals(1, h.writes)
    }

    @Test fun cancelConsumesConfirmationWithoutMutationAndCannotBeReplayed() {
        val h = Harness(); h.load(withItem = true); val prompt = h.removePrompt()
        h.controller.cancel(prompt.confirmation); h.confirm(prompt); h.finish()
        assertEquals(1, h.controller.state.items.size); assertEquals(0, h.writes)
        assertTrue(prompt.confirmation.consumed)
    }

    @Test fun finalConfirmationReadsLivePanelOwnershipAndCancelsIfItWasLost() {
        val h = Harness(); h.load(withItem = true)
        var active = true; val canAct = { active }
        val prompt = h.clearPrompt(canAct)
        assertTrue(historyPanelCanConfirm(prompt.state, h.controller.state, canAct()))
        active = false; h.confirm(prompt); active = true; h.confirm(prompt); h.finish()
        assertTrue(prompt.confirmation.consumed); assertEquals(0, h.writes)
        assertEquals(1, h.controller.state.items.size)
    }

    @Test fun equalButDifferentStateCannotConfirmAndBusyStateCannotConfirm() {
        val h = Harness(); h.load(withItem = true); val prompt = h.clearPrompt()
        val equalCopy = prompt.state.copy(); assertEquals(prompt.state, equalCopy)
        assertFalse(historyPanelCanConfirm(prompt.state, equalCopy, true))
        var confirmed = false
        confirmHistoryPanelPrompt(prompt, { equalCopy }, { confirmed = true }, h.controller::cancel)
        assertFalse(confirmed); assertTrue(prompt.confirmation.consumed)
        val busy = HistoryState(ready = true, busy = true)
        assertFalse(historyPanelCanConfirm(busy, busy, true))
        val unreadable = HistoryState()
        assertTrue(historyPanelCanConfirm(unreadable, unreadable, true))
    }

    @Test fun loadAfterPromptCancelsOldConfirmationWithoutClearingRefreshedList() {
        val h = Harness(); h.load(withItem = true); val prompt = h.clearPrompt()
        h.controller.load(); h.confirm(prompt); h.finish(); h.confirm(prompt); h.finish()
        assertEquals(1, h.controller.state.items.size); assertEquals(0, h.writes)
        assertTrue(prompt.confirmation.consumed)
    }

    @Test fun newerRenderAuthorityCannotUpgradeAnOldConfirmationOwner() {
        val h = Harness(); h.load(withItem = true)
        val oldOwner = Any(); var currentOwner = oldOwner
        val oldGate = { currentOwner === oldOwner }; val prompt = h.clearPrompt(oldGate)
        val newOwner = Any(); currentOwner = newOwner
        val newerRenderGate = { currentOwner === newOwner }
        assertTrue(newerRenderGate()); assertFalse(prompt.canAct())
        h.confirm(prompt); h.finish()
        assertTrue(prompt.confirmation.consumed); assertEquals(0, h.writes)
        assertEquals(1, h.controller.state.items.size)
    }

    @Test fun capturedRowCannotRegainAuthorityAfterListChangesAndReturnsToEqualValues() {
        val h = Harness(); h.load(withItem = true)
        val original = h.controller.state.items
        val capturedRow = requireNotNull(h.controller.row(original.single()))
        h.saved = listOf(historyItem(2)); h.controller.load(); h.finish()
        h.saved = original; h.controller.load(); h.finish()
        assertEquals(original, h.controller.state.items)
        assertNull(h.controller.addressForOpen(capturedRow)); assertNull(h.controller.requestRemove(capturedRow))
        val freshRow = requireNotNull(h.controller.row(h.controller.state.items.single()))
        assertNotNull(h.controller.addressForOpen(freshRow)); assertEquals(0, h.writes)
    }

    @Test fun everyNoticeMapsToActualResourcesAndBusyAlwaysWinsOverEmpty() {
        assertEquals(HistoryNotice.entries.size, HistoryNotice.entries.map(::historyNoticeResource).toSet().size)
        assertEquals(R.string.history_stale_list, historyNoticeResource(HistoryNotice.STALE_LIST))
        assertEquals(R.string.history_cleared, historyNoticeResource(HistoryNotice.CLEARED))
        for (ready in listOf(false, true)) for (busy in listOf(false, true)) {
            for (notice in listOf<HistoryNotice?>(null) + HistoryNotice.entries) {
                val view = historyPanelStatus(HistoryState(ready = ready, busy = busy, notice = notice))
                assertEquals(ready && !busy, view.rowsEnabled); assertEquals(!ready && !busy, view.retry)
                assertEquals(!busy, view.clearEnabled)
                if (busy || !ready) assertNotEquals(R.string.history_empty, view.message)
            }
        }
    }

    @Test fun visitedDateUsesRequestedLocaleAndZoneAndPromptDiagnosticIsRedacted() {
        val millis = 1_730_001_234_000L
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("ar"))) {
            for (zone in listOf(TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("Asia/Shanghai"))) {
                val expected = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .apply { timeZone = zone }.format(Date(millis))
                assertEquals(expected, historyVisitedDate(millis, locale, zone))
            }
        }
        val h = Harness(); h.load(withItem = true); val prompt = h.removePrompt()
        assertEquals("HistoryPanelPrompt(redacted)", prompt.toString())
        assertFalse(prompt.toString().contains(h.saved.single().address))
        assertFalse(prompt.toString().contains(h.saved.single().title))
    }
}
