package com.easysavevideo.app.browser.history

import org.junit.Assert.*
import org.junit.Test

/** Real controller row authority and production action dispatcher; native open is a controlled port. */
class HistoryPanelActionsTest {
    private class Harness {
        val item = BrowserHistoryItem(historyId(1), "https://example.com/ordinary", "Example", 100)
        val work = ArrayDeque<() -> Unit>()
        val controller = HistoryController(object : HistoryStorage {
            override fun load() = listOf(item)
            override fun save(items: List<BrowserHistoryItem>) = Unit
        }, HistoryNavigationGate(), HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS),
            { work.addLast(it) }, { it() }, { historyId(2) }, { 100L }, {})
        val owner = HistoryPanelActionOwner()
        var claim = owner.token
        var interactive = true
        var nativeAccepts = true
        var nativeEffect: () -> Unit = {}
        val events = mutableListOf<String>()
        init { controller.load(); finish() }
        fun finish() { while (work.isNotEmpty()) work.removeFirst()() }
        fun row() = requireNotNull(controller.row(item))
        fun act(row: HistoryRowToken) = openHistoryRow(row,
            { owner.current(claim) && interactive }, controller::addressForOpen,
            { address -> events += address; nativeEffect(); nativeAccepts },
            { owner.current(claim) && interactive }, { events += "close"; owner.invalidate() })
    }
    @Test fun currentRowOpensExactAddressBeforeClosingPanel() {
        val h = Harness(); assertTrue(h.act(h.row())); assertEquals(listOf(h.item.address, "close"), h.events)
    }
    @Test fun rejectedNativeOpenKeepsPanelAndRow() {
        val h = Harness(); h.nativeAccepts = false; assertFalse(h.act(h.row()))
        assertEquals(listOf(h.item.address), h.events); assertNotNull(h.controller.addressForOpen(h.row()))
    }
    @Test fun currentForegroundAndNavigationGateReadAtClick() {
        val h = Harness(); val row = h.row(); h.interactive = false
        assertFalse(h.act(row)); assertTrue(h.events.isEmpty())
    }
    @Test fun oldPanelCallbackCannotActAfterCloseAndReopen() {
        val h = Harness(); val row = h.row(); h.owner.invalidate()
        assertFalse(h.act(row)); assertTrue(h.events.isEmpty())
        h.claim = h.owner.token; assertTrue(h.act(h.row()))
    }
    @Test fun disposedOwnerCannotActEvenIfNewPageHasSameState() {
        val h = Harness(); val row = h.row(); h.owner.retire(); h.claim = h.owner.token
        assertFalse(h.act(row)); assertTrue(h.events.isEmpty())
    }
    @Test fun oldRowRejectedDuringReadAndAfterSameValueReadCompletes() {
        val h = Harness(); val row = h.row(); h.controller.load()
        assertFalse(h.act(row)); h.finish(); assertEquals(listOf(h.item), h.controller.state.items)
        assertFalse(h.act(row)); assertTrue(h.events.isEmpty()); assertTrue(h.act(h.row()))
    }
    @Test fun repeatCallbackIsRetiredAfterAcceptedOpen() {
        val h = Harness(); val row = h.row(); assertTrue(h.act(row)); assertFalse(h.act(row))
        assertEquals(listOf(h.item.address, "close"), h.events)
    }
    @Test fun nativeReentrantNavigationDoesNotCloseNewOwnerPanel() {
        val h = Harness(); h.nativeEffect = { h.owner.invalidate() }
        assertTrue(h.act(h.row())); assertEquals(listOf(h.item.address), h.events)
    }
    @Test fun nativeReentrantBackgroundDoesNotDispatchPanelClose() {
        val h = Harness(); h.nativeEffect = { h.interactive = false }
        assertTrue(h.act(h.row())); assertEquals(listOf(h.item.address), h.events)
    }
    @Test fun authorityChangedByAddressLookupStopsBeforeNativeOpen() {
        val h = Harness(); val row = h.row()
        assertFalse(openHistoryRow(row, { h.interactive }, { h.interactive = false; h.item.address },
            { h.events += it; true }, { true }, { h.events += "close" }))
        assertTrue(h.events.isEmpty())
    }
}
