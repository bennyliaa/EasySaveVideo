package com.easysavevideo.app.browser.history

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class HistoryControllerTest {
    private class Store : HistoryStorage {
        var bytes: ByteArray? = null
        var reads = 0; var writes = 0
        var failRead = false; var failWrite = false; var uncertainWrite = false
        override fun load(): List<BrowserHistoryItem> { reads++; check(!failRead); return bytes?.let(HistoryCodec::decode) ?: emptyList() }
        override fun save(items: List<BrowserHistoryItem>) {
            writes++; check(!failWrite); bytes = HistoryCodec.encode(items); check(!uncertainWrite)
        }
    }
    private class Harness(val store: Store = Store(), policy: HistoryRetentionPolicy = HistoryRetentionPolicy(200, null)) {
        val work = ArrayDeque<() -> Unit>(); val delivery = ArrayDeque<() -> Unit>()
        val gate = HistoryNavigationGate(); var time = 100L; var id = 0
        val c = HistoryController(store, gate, policy, { work.addLast(it) }, { delivery.addLast(it) },
            { historyId(++id) }, { time }, {})
        fun finish() {
            while (work.isNotEmpty() || delivery.isNotEmpty()) {
                while (work.isNotEmpty()) work.removeFirst()()
                while (delivery.isNotEmpty()) delivery.removeFirst()()
            }
        }
        fun ready() { c.load(); finish() }
        fun add(n: Int = 1) { c.record(historyVisit(gate, n, time)); finish() }
        fun row() = requireNotNull(c.row(c.state.items.first()))
    }
    @Test fun newStoreLoadsOnWorkerWithoutWritingOrInventingHistory() {
        val h = Harness(); h.c.load(); assertEquals(0, h.store.reads); assertFalse(h.c.state.ready)
        h.finish(); assertTrue(h.c.state.ready); assertTrue(h.c.state.items.isEmpty()); assertEquals(0, h.store.writes)
    }
    @Test fun visitAcknowledgedOnlyAfterDurableCommitAndDelivery() {
        val h = Harness(); h.ready(); h.c.record(historyVisit(h.gate)); assertTrue(h.c.state.items.isEmpty())
        h.work.removeFirst()(); assertTrue(h.c.state.items.isEmpty()); h.delivery.removeFirst()()
        assertEquals(1, h.c.state.items.size)
    }
    @Test fun repeatedVisitTokenIsNotReplayed() {
        val h = Harness(); h.ready(); val visit = historyVisit(h.gate)
        repeat(100) { h.c.record(visit) }; h.finish(); assertEquals(1, h.store.writes)
    }
    @Test fun visitsDuringInitialReadQueueAndCommitAfterRead() {
        val h = Harness(); h.c.load(); h.c.record(historyVisit(h.gate)); assertEquals(1, h.work.size)
        h.finish(); assertEquals(1, h.c.state.items.size); assertEquals(1, h.store.writes)
    }
    @Test fun burstQueueIsBoundedAndKeepsMostRecentAddresses() {
        val h = Harness(); h.ready()
        for (n in 1..400) h.c.record(historyVisit(h.gate, n))
        assertEquals(1, h.work.size); h.finish(); assertEquals(2, h.store.writes)
        assertEquals(200, h.c.state.items.size); assertEquals("https://example.com/400", h.c.state.items.first().address)
        assertFalse(h.c.state.items.any { it.address == "https://example.com/1" })
    }
    @Test fun repeatedAddressMovesToFrontButStableIdentityRemains() {
        val h = Harness(); h.ready(); h.add(1); val id = h.c.state.items.single().id; h.add(2); h.time++; h.add(1)
        assertEquals(id, h.c.state.items.first().id); assertEquals(2, h.c.state.items.size); assertEquals(101L, h.c.state.items.first().visitedAtMillis)
    }
    @Test fun listTokensCannotReplayAfterLoadMutationDeletionOrAnotherOwner() {
        val h = Harness(); h.ready(); h.add(); val token = h.row(); assertEquals("https://example.com/1", h.c.addressForOpen(token))
        h.c.load(); assertNull(h.c.addressForOpen(token)); h.finish(); assertNull(h.c.addressForOpen(token))
        val fresh = h.row(); h.add(2); assertNull(h.c.addressForOpen(fresh))
        val other = Harness(h.store); other.ready(); assertNull(other.c.addressForOpen(h.row()))
    }
    @Test fun cancellationDoesNotClearOrInvalidateCurrentRows() {
        val h = Harness(); h.ready(); h.add(); val token = h.row(); val confirmation = requireNotNull(h.c.requestClear())
        h.c.cancel(confirmation); h.c.confirm(confirmation); h.finish()
        assertEquals(1, h.c.state.items.size); assertNotNull(h.c.addressForOpen(token)); assertEquals(1, h.store.writes)
    }
    @Test fun removalRequiresFreshConfirmationAndOnlyTouchesHistory() {
        val h = Harness(); h.ready(); h.add(1); h.add(2)
        val confirmation = requireNotNull(h.c.requestRemove(h.row())); h.c.confirm(confirmation)
        assertEquals(2, h.c.state.items.size); h.finish(); assertEquals("https://example.com/1", h.c.state.items.single().address)
        assertEquals(HistoryNotice.REMOVED, h.c.state.notice); h.c.confirm(confirmation); assertTrue(h.work.isEmpty())
    }
    @Test fun oldConfirmationCannotDeleteNewerList() {
        val h = Harness(); h.ready(); h.add(); val confirmation = requireNotNull(h.c.requestClear()); h.add(2)
        h.c.confirm(confirmation); assertTrue(h.work.isEmpty()); assertEquals(2, h.c.state.items.size)
        assertEquals(HistoryNotice.STALE_LIST, h.c.state.notice)
    }
    @Test fun clearIsAtomicAndUndeliveredVisitCannotRefillClearedHistory() {
        val h = Harness(); h.ready(); h.add(); val delayed = historyVisit(h.gate, 2)
        h.c.confirm(requireNotNull(h.c.requestClear())); h.c.record(delayed)
        assertEquals(1, h.c.state.items.size); h.finish(); assertTrue(h.c.state.items.isEmpty())
        h.c.record(delayed); h.finish(); assertTrue(h.c.state.items.isEmpty())
        h.add(3); assertEquals(1, h.c.state.items.size)
    }
    @Test fun corruptLoadPreservesOriginalUntilExplicitConfirmedClear() {
        val h = Harness(); h.store.bytes = byteArrayOf(1, 2, 3); h.ready(); assertFalse(h.c.state.ready)
        assertArrayEquals(byteArrayOf(1, 2, 3), h.store.bytes); assertEquals(0, h.store.writes)
        h.c.record(historyVisit(h.gate)); assertEquals(0, h.store.writes)
        val cancel = requireNotNull(h.c.requestClear()); h.c.cancel(cancel); h.c.confirm(cancel); h.finish()
        assertArrayEquals(byteArrayOf(1, 2, 3), h.store.bytes)
        h.c.confirm(requireNotNull(h.c.requestClear())); h.finish(); assertTrue(h.c.state.ready)
        assertEquals(emptyList<BrowserHistoryItem>(), HistoryCodec.decode(h.store.bytes!!))
    }
    @Test fun clearFailurePreservesOldRowsButDisablesTheirAuthority() {
        val h = Harness(); h.ready(); h.add(); val before = h.c.state.items; val token = h.row(); val bytes = h.store.bytes!!.copyOf()
        h.store.failWrite = true; h.c.confirm(requireNotNull(h.c.requestClear())); h.finish()
        assertEquals(before, h.c.state.items); assertArrayEquals(bytes, h.store.bytes); assertFalse(h.c.state.ready)
        assertNull(h.c.addressForOpen(token)); assertEquals(HistoryNotice.UNAVAILABLE, h.c.state.notice)
    }
    @Test fun uncertainClearCommitRequiresReadBeforeClaimingSuccess() {
        val h = Harness(); h.ready(); h.add(); h.store.uncertainWrite = true
        h.c.confirm(requireNotNull(h.c.requestClear())); h.finish(); assertFalse(h.c.state.ready)
        assertEquals(1, h.c.state.items.size); h.store.uncertainWrite = false; h.c.load(); h.finish()
        assertTrue(h.c.state.ready); assertTrue(h.c.state.items.isEmpty())
    }
    @Test fun failedRecordDropsQueuedVisitsAndDoesNotAffectBrowsingAuthorityOutsideHistory() {
        val h = Harness(); h.ready(); h.store.failWrite = true
        h.c.record(historyVisit(h.gate, 1)); h.c.record(historyVisit(h.gate, 2)); h.finish()
        assertEquals(1, h.store.writes); assertFalse(h.c.state.ready); assertTrue(h.c.state.items.isEmpty())
        h.store.failWrite = false; h.c.load(); h.finish(); assertTrue(h.c.state.items.isEmpty())
    }
    @Test fun explicitTtlPrunesDurablyOnReadWhileCountOnlyNeverDoes() {
        val store = Store(); store.bytes = HistoryCodec.encode(listOf(historyItem(time = 0)))
        val noTtl = Harness(store); noTtl.time = HistoryPolicy.THIRTY_DAYS_MILLIS; noTtl.ready()
        assertEquals(1, noTtl.c.state.items.size); assertEquals(0, store.writes)
        val ttl = Harness(store, HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS))
        ttl.time = HistoryPolicy.THIRTY_DAYS_MILLIS; ttl.ready(); assertTrue(ttl.c.state.items.isEmpty()); assertEquals(1, store.writes)
    }
    @Test fun slowWriterCannotStartSecondWriterAndLateCompletionReconciles() {
        val h = Harness(); h.ready(); h.c.record(historyVisit(h.gate)); val op = h.c.operationGeneration()!!
        h.c.slow(op); h.c.load(); assertNull(h.c.requestClear()); assertEquals(1, h.work.size)
        h.finish(); assertTrue(h.c.state.ready); h.c.slow(op); assertTrue(h.c.state.ready)
    }
    @Test fun restartRestoresHistoryWithoutGeneratingAdditionalVisits() {
        val first = Harness(); first.ready(); first.add(); val second = Harness(first.store); second.ready()
        assertEquals(first.c.state.items, second.c.state.items); assertEquals(1, first.store.writes)
    }
    @Test fun closedOwnerDoesNotPublishLateResultOrAcceptWork() {
        val h = Harness(); h.ready(); h.c.record(historyVisit(h.gate)); h.c.close(); h.finish()
        assertFalse(h.c.state.ready && !h.c.state.busy); h.c.load(); assertTrue(h.work.isEmpty()); assertNull(h.c.requestClear())
        val next = Harness(h.store); next.ready(); assertEquals(1, next.c.state.items.size)
    }
    @Test fun workerRejectionDisablesOnlyHistory() {
        val c = HistoryController(Store(), HistoryNavigationGate(), HistoryRetentionPolicy(200, null),
            { throw IllegalStateException() }, { it() }, { historyId(1) }, { 100 }, {})
        c.load(); assertFalse(c.state.ready); assertFalse(c.state.busy); assertEquals(HistoryNotice.UNAVAILABLE, c.state.notice)
    }
}
