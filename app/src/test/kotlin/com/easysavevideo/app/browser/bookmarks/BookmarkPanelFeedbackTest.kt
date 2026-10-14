package com.easysavevideo.app.browser.bookmarks

import com.easysavevideo.app.R
import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

/** Real bookmark controller and presentation with synthetic bounded storage, no Android or network. */
class BookmarkPanelFeedbackTest {
    private class Harness {
        var saved = emptyList<BrowserBookmark>()
        var failRead = false; var failWrite = false; var failAfterCommit = false
        var writes = 0; var reads = 0
        val work = ArrayDeque<() -> Unit>(); val deliveries = ArrayDeque<() -> Unit>()
        val controller = BookmarkController(object : BookmarkStorage {
            override fun load(): List<BrowserBookmark> { reads++; check(!failRead); return saved }
            override fun save(items: List<BrowserBookmark>) {
                writes++; check(!failWrite); saved = items; check(!failAfterCommit)
            }
        }, { work.addLast(it) }, { deliveries.addLast(it) },
            { "00000000-0000-0000-0000-000000000001" }, {})
        val page = BookmarkPageToken(BrowserContextRef("tab", "session"), 1, 1, 1,
            "https://example.test/fixture", "Fixture")
        val view get() = bookmarkPanelStatus(controller.state)
        fun finish() { while (work.isNotEmpty()) work.removeFirst()(); while (deliveries.isNotEmpty()) deliveries.removeFirst()() }
        fun load() { controller.load(); finish() }
        fun add() { controller.add(page); finish() }
    }
    @Test fun initialReadShowsLoadingRatherThanEmptyAndOnlyCompletionEnablesRows() {
        val h = Harness(); h.controller.load()
        assertEquals(R.string.bookmark_loading, h.view.message); assertFalse(h.view.retry); assertFalse(h.view.rowsEnabled)
        h.finish()
        assertEquals(R.string.bookmark_empty, h.view.message); assertFalse(h.view.retry); assertTrue(h.view.rowsEnabled)
        assertEquals(0, h.writes)
    }
    @Test fun addingToEmptyListDoesNotClaimEmptyOrSuccessBeforeDurableCompletion() {
        val h = Harness(); h.load(); h.controller.add(h.page)
        assertEquals(R.string.bookmark_loading, h.view.message); assertFalse(h.view.rowsEnabled)
        assertNull(h.controller.state.notice); assertTrue(h.controller.state.items.isEmpty())
        h.finish(); assertEquals(BookmarkNotice.ADDED, h.controller.state.notice)
        assertEquals(R.string.bookmark_added, bookmarkNoticeResource(h.controller.state.notice!!))
        assertNull(h.view.message); assertTrue(h.view.rowsEnabled); assertEquals(1, h.controller.state.items.size)
    }
    @Test fun slowOperationHasOneNoticeAndNoSecondWriterOrRetryUntilItCompletes() {
        val h = Harness(); h.load(); h.controller.add(h.page)
        h.controller.slow(requireNotNull(h.controller.operationGeneration()))
        assertEquals(BookmarkNotice.SLOW, h.controller.state.notice); assertNull(h.view.message)
        assertFalse(h.view.retry); assertFalse(h.view.rowsEnabled)
        h.controller.load(); h.controller.add(h.page); assertEquals(1, h.work.size)
        h.finish(); assertEquals(BookmarkNotice.ADDED, h.controller.state.notice); assertTrue(h.view.rowsEnabled)
        assertEquals(1, h.writes)
    }
    @Test fun uncertainRemovalKeepsRowsDisabledUntilExplicitRereadReconcilesStorage() {
        val h = Harness(); h.load(); h.add(); val item = h.controller.state.items.single()
        h.failAfterCommit = true; h.controller.remove(item); h.finish()
        assertEquals(BookmarkNotice.UNAVAILABLE, h.controller.state.notice)
        assertTrue(h.view.retry); assertFalse(h.view.rowsEnabled); assertNull(h.view.message)
        assertNull(h.controller.addressForOpen(item)); assertEquals(listOf(item), h.controller.state.items)
        assertTrue(h.saved.isEmpty())
        h.failAfterCommit = false; h.controller.load(); assertFalse(h.view.retry); h.finish()
        assertTrue(h.controller.state.items.isEmpty()); assertEquals(R.string.bookmark_empty, h.view.message)
        assertTrue(h.view.rowsEnabled); assertEquals(2, h.writes)
    }
    @Test fun dismissingFailureDoesNotHidePersistentUnreadableStateOrItsRecoveryAction() {
        val h = Harness(); h.failRead = true; h.load()
        assertEquals(BookmarkNotice.UNAVAILABLE, h.controller.state.notice); assertTrue(h.view.retry)
        h.controller.dismissNotice()
        assertNull(h.controller.state.notice); assertEquals(R.string.bookmark_unavailable, h.view.message)
        assertTrue(h.view.retry); assertFalse(h.view.rowsEnabled); assertEquals(0, h.writes)
    }
    @Test fun successfulRemoveFeedbackComesFromControllerAndDoesNotFabricateNavigation() {
        val h = Harness(); h.load(); h.add(); val item = h.controller.state.items.single()
        h.controller.remove(item)
        assertNull(h.controller.state.notice); assertFalse(h.view.rowsEnabled)
        h.finish(); assertEquals(BookmarkNotice.REMOVED, h.controller.state.notice)
        assertEquals(R.string.bookmark_removed, bookmarkNoticeResource(h.controller.state.notice!!))
        assertEquals(R.string.bookmark_empty, h.view.message); assertNull(h.controller.addressForOpen(item))
    }
    @Test fun duplicateNoticeHasNoStorageMutationAndCanBeDismissedIndependently() {
        val h = Harness(); h.load(); h.add(); val records = h.saved
        h.controller.add(h.page)
        assertEquals(BookmarkNotice.DUPLICATE, h.controller.state.notice)
        assertEquals(R.string.bookmark_duplicate, bookmarkNoticeResource(BookmarkNotice.DUPLICATE))
        h.controller.dismissNotice(); assertEquals(records, h.saved); assertEquals(1, h.writes)
    }
    @Test fun everyNoticeHasAResourceAndStateProjectionKeepsOriginalEnablement() {
        assertEquals(7, BookmarkNotice.entries.map(::bookmarkNoticeResource).toSet().size)
        for (ready in listOf(false, true)) for (busy in listOf(false, true)) for (notice in BookmarkNotice.entries) {
            val status = bookmarkPanelStatus(BookmarkState(ready = ready, busy = busy, notice = notice))
            assertEquals(ready && !busy, status.rowsEnabled); assertEquals(!ready && !busy, status.retry)
            if (busy) assertNotEquals(R.string.bookmark_empty, status.message)
        }
    }
}
