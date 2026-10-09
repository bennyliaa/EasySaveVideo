package com.easysavevideo.app.browser.bookmarks

import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

class BookmarkControllerTest {
    private class Store : BookmarkStorage {
        var bytes: ByteArray? = null
        var reads = 0; var writes = 0
        var readFailure = false; var writeFailure = false; var failAfterCommit = false
        override fun load(): List<BrowserBookmark> { reads++; check(!readFailure); return bytes?.let(BookmarkCodec::decode) ?: emptyList() }
        override fun save(items: List<BrowserBookmark>) { writes++; check(!writeFailure); bytes = BookmarkCodec.encode(items); check(!failAfterCommit) }
    }
    private class Harness(val store: Store = Store()) {
        val work = ArrayDeque<() -> Unit>(); val delivery = ArrayDeque<() -> Unit>()
        val seen = mutableListOf<BookmarkState>(); var nextId = 0
        val c = BookmarkController(store, { work.addLast(it) }, { delivery.addLast(it) },
            { "00000000-0000-0000-0000-${(++nextId).toString().padStart(12, '0')}" }, { seen += it })
        fun ready() { c.load(); finish() }
        fun finish() { while (work.isNotEmpty()) work.removeFirst()(); while (delivery.isNotEmpty()) delivery.removeFirst()() }
        fun add(n: Int = 1) { c.add(page(n)); finish() }
    }
    companion object {
        private fun page(n: Int = 1) = BookmarkPageToken(BrowserContextRef("tab", "session"), 1, 1, 1, "https://example.com/$n", "Page $n")
    }
    @Test fun initialUnavailableUntilBackgroundLoadCompletes() { val h = Harness(); assertFalse(h.c.state.ready); h.c.load(); assertTrue(h.c.state.busy); assertEquals(0, h.store.reads); h.finish(); assertTrue(h.c.state.ready) }
    @Test fun missingNewStoreLoadsEmptyWithoutWriting() { val h = Harness(); h.ready(); assertTrue(h.c.state.items.isEmpty()); assertEquals(0, h.store.writes) }
    @Test fun addAcknowledgedOnlyAfterDurableWorkerAndDelivery() { val h = Harness(); h.ready(); h.c.add(page()); assertTrue(h.c.state.items.isEmpty()); h.work.removeFirst()(); assertTrue(h.c.state.items.isEmpty()); h.delivery.removeFirst()(); assertEquals(BookmarkNotice.ADDED, h.c.state.notice); assertEquals(1, h.c.state.items.size) }
    @Test fun secondTapDoesNotQueueAnotherOperation() { val h = Harness(); h.ready(); repeat(1000) { h.c.add(page()) }; assertEquals(1, h.work.size); h.finish(); assertEquals(1, h.store.writes) }
    @Test fun duplicatePreservesIdTitleAndOrderWithoutWriting() { val h = Harness(); h.ready(); h.add(); val before = h.c.state.items; h.c.add(page().copy(title = "New title")); assertEquals(before, h.c.state.items); assertEquals(1, h.store.writes); assertEquals(BookmarkNotice.DUPLICATE, h.c.state.notice) }
    @Test fun newestFirstAndExistingEntriesStable() { val h = Harness(); h.ready(); h.add(1); val first = h.c.state.items.single(); h.add(2); assertEquals(first, h.c.state.items.last()); assertEquals(page(2).address, h.c.state.items.first().address) }
    @Test fun fullStoreRefusesNewEntryWithoutEviction() { val h = Harness(); h.ready(); (1..200).forEach(h::add); val before = h.c.state.items; h.c.add(page(201)); assertEquals(BookmarkNotice.FULL, h.c.state.notice); assertEquals(before, h.c.state.items); assertEquals(200, h.store.writes) }
    @Test fun duplicateAtFullCapacityStillReportsDuplicate() { val h = Harness(); h.ready(); (1..200).forEach(h::add); h.c.add(page()); assertEquals(BookmarkNotice.DUPLICATE, h.c.state.notice) }
    @Test fun removeOnlyAfterCommit() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); h.c.remove(item); assertEquals(listOf(item), h.c.state.items); h.finish(); assertTrue(h.c.state.items.isEmpty()); assertEquals(BookmarkNotice.REMOVED, h.c.state.notice) }
    @Test fun removedItemCannotOpen() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); h.c.remove(item); h.finish(); assertNull(h.c.addressForOpen(item)) }
    @Test fun oldRemovalCannotDeleteReaddedAddress() { val h = Harness(); h.ready(); h.add(); val old = h.c.state.items.single(); h.c.remove(old); h.finish(); h.add(); assertNotEquals(old.id, h.c.state.items.single().id); h.c.remove(old); assertEquals(1, h.c.state.items.size); assertEquals(3, h.store.writes) }
    @Test fun openIsPureAndRequiresExactCurrentRecord() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); assertEquals(item.address, h.c.addressForOpen(item)); assertEquals(1, h.store.reads); assertEquals(1, h.store.writes); assertNull(h.c.addressForOpen(item.copy(title = "Forged"))) }
    @Test fun openWhileMutationBusyRefused() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); h.c.add(page(2)); assertNull(h.c.addressForOpen(item)); h.finish(); assertNotNull(h.c.addressForOpen(item)) }
    @Test fun failedLoadPreservesBytesAndDisablesMutations() { val h = Harness(); h.store.bytes = byteArrayOf(1, 2, 3); h.ready(); val original = h.store.bytes!!.copyOf(); assertFalse(h.c.state.ready); h.c.add(page()); assertEquals(0, h.store.writes); assertArrayEquals(original, h.store.bytes) }
    @Test fun failureAfterExistingLoadPreservesDisplayedListButNotAuthority() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); h.store.readFailure = true; h.c.load(); h.finish(); assertEquals(listOf(item), h.c.state.items); assertNull(h.c.addressForOpen(item)); assertFalse(h.c.state.ready) }
    @Test fun writeFailureDoesNotPublishUncommittedItem() { val h = Harness(); h.ready(); h.store.writeFailure = true; h.add(); assertTrue(h.c.state.items.isEmpty()); assertFalse(h.c.state.ready); assertEquals(BookmarkNotice.UNAVAILABLE, h.c.state.notice) }
    @Test fun failedRemoveKeepsExistingEntryAndBytes() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); val bytes = h.store.bytes!!.copyOf(); h.store.writeFailure = true; h.c.remove(item); h.finish(); assertEquals(listOf(item), h.c.state.items); assertArrayEquals(bytes, h.store.bytes) }
    @Test fun uncertainCommittedSaveReconcilesOnExplicitReload() { val h = Harness(); h.ready(); h.store.failAfterCommit = true; h.add(); assertFalse(h.c.state.ready); assertTrue(h.c.state.items.isEmpty()); h.store.failAfterCommit = false; h.c.load(); h.finish(); assertEquals(1, h.c.state.items.size); assertEquals(1, h.store.writes) }
    @Test fun restartLoadsPreviouslyCommittedItems() { val first = Harness(); first.ready(); first.add(); val next = Harness(first.store); next.ready(); assertEquals(first.c.state.items, next.c.state.items); assertEquals(1, first.store.writes) }
    @Test fun restartAfterDeleteLoadsEmpty() { val first = Harness(); first.ready(); first.add(); first.c.remove(first.c.state.items.single()); first.finish(); val next = Harness(first.store); next.ready(); assertTrue(next.c.state.items.isEmpty()) }
    @Test fun slowOperationRemainsSingleInFlightAndCannotQueueRetry() { val h = Harness(); h.c.load(); h.c.slow(h.c.operationGeneration()!!); assertEquals(BookmarkNotice.SLOW, h.c.state.notice); repeat(100) { h.c.load(); h.c.add(page()) }; assertEquals(1, h.work.size); h.finish(); assertTrue(h.c.state.ready) }
    @Test fun staleTimeoutCannotDisableNewOperationOrCompleteState() { val h = Harness(); h.c.load(); val old = h.c.operationGeneration()!!; h.finish(); h.c.slow(old); assertTrue(h.c.state.ready); h.c.add(page()); h.c.slow(old); assertNull(h.c.state.notice); h.finish(); assertTrue(h.c.state.ready) }
    @Test fun slowWriteLaterSuccessReportsDurableOutcome() { val h = Harness(); h.ready(); h.c.add(page()); h.c.slow(h.c.operationGeneration()!!); h.finish(); assertEquals(BookmarkNotice.ADDED, h.c.state.notice); assertEquals(1, h.c.state.items.size) }
    @Test fun acceptedWriteSurvivesUiOwnerCloseWithoutLateDelivery() { val h = Harness(); h.ready(); h.c.add(page()); val seen = h.seen.size; h.c.close(); h.finish(); assertEquals(seen, h.seen.size); val reopen = Harness(h.store); reopen.ready(); assertEquals(1, reopen.c.state.items.size) }
    @Test fun closedOwnerCannotMutateLoadOrOpen() { val h = Harness(); h.ready(); h.add(); val item = h.c.state.items.single(); h.c.close(); h.c.load(); h.c.add(page(2)); h.c.remove(item); h.c.stalePage(); h.c.dismissNotice(); assertTrue(h.work.isEmpty()); assertNull(h.c.addressForOpen(item)) }
    @Test fun invalidPageCannotReachStorage() { val h = Harness(); h.ready(); h.c.add(page().copy(address = "javascript:1")); assertEquals(BookmarkNotice.STALE_PAGE, h.c.state.notice); assertEquals(0, h.store.writes) }
    @Test fun noncanonicalPageCannotReachStorage() { val h = Harness(); h.ready(); h.c.add(page().copy(address = "HTTPS://EXAMPLE.COM/")); assertEquals(BookmarkNotice.STALE_PAGE, h.c.state.notice); assertEquals(0, h.store.writes) }
    @Test fun unsafeTitleCannotReachStorage() { val h = Harness(); h.ready(); h.c.add(page().copy(title = "Bad\nTitle")); assertEquals(BookmarkNotice.STALE_PAGE, h.c.state.notice); assertEquals(0, h.store.writes) }
    @Test fun dismissNoticeDoesNotMutateDisk() { val h = Harness(); h.ready(); h.add(); h.c.dismissNotice(); assertNull(h.c.state.notice); assertEquals(1, h.store.writes) }
    @Test fun workerRejectionBecomesUnavailable() { val c = BookmarkController(Store(), { throw IllegalStateException() }, { it() }, { "bad" }, {}); c.load(); assertFalse(c.state.ready); assertFalse(c.state.busy); assertEquals(BookmarkNotice.UNAVAILABLE, c.state.notice) }
    @Test fun invalidGeneratedIdCannotWrite() { val store = Store(); val c = BookmarkController(store, { it() }, { it() }, { "bad" }, {}); c.load(); c.add(page()); assertEquals(0, store.writes); assertEquals(BookmarkNotice.UNAVAILABLE, c.state.notice) }
    @Test fun corruptLoadedRecordsAreRejectedAtControllerBoundary() { val store = object : BookmarkStorage { override fun load() = listOf(BrowserBookmark("bad", "javascript:1", "Bad")); override fun save(items: List<BrowserBookmark>) = error("No writes") }; val c = BookmarkController(store, { it() }, { it() }, { "bad" }, {}); c.load(); assertFalse(c.state.ready) }
}
