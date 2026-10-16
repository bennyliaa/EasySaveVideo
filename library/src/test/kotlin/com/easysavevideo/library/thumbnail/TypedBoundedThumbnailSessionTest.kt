package com.easysavevideo.library.thumbnail

import org.junit.Assert.*
import org.junit.Test

class TypedBoundedThumbnailSessionTest {
    private class Frame(val failClose: Boolean = false) : ThumbnailFrame {
        override val width = 256; override val height = 144; override val allocationBytes = 147_456L
        var closes = 0
        override fun close() { closes++; if (failClose) error("fixed") }
    }
    private class Ui : ThumbnailUiDispatcher {
        val posts = ArrayDeque<Runnable>(); val timers = mutableListOf<Runnable>()
        var executeThenThrow = false; var reject = false
        override fun requireCurrentThread() = Unit
        override fun post(task: Runnable) {
            if (executeThenThrow) { task.run(); error("fixed") }
            if (reject) error("fixed")
            posts += task
        }
        override fun later(task: Runnable, delayMillis: Long) { timers += task }
        override fun remove(task: Runnable) { timers -= task }
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst().run() }
    }
    private class Harness {
        val ui = Ui(); var starts = 0; var stalls = 0; var settlements = 0
        var startResult = ThumbnailStartResult.ACCEPTED
        lateinit var callback: (ThumbnailDecodeResult<Frame>, () -> Unit) -> Unit
        val adapter = object : ThumbnailItemAdapter<Int, Int> {
            override val maxPageSize = 297
            override fun key(item: Int) = item
            override fun isEligible(item: Int) = item > 0
        }
        val session = TypedBoundedThumbnailSession(AsyncThumbnailDecoder<Int, Frame> { _, _, complete ->
            starts++; callback = complete; startResult
        }, ThumbnailWorkLane { it.run(); true }, ui, { stalls++ }, adapter)
        fun begin() { session.updatePage(listOf(1, 2), true); session.request(1); session.request(2) }
        fun deliver(frame: Frame = Frame(), settle: () -> Unit = { settlements++ }) = callback(ThumbnailDecodeResult.Released(frame), settle)
    }
    @Test fun asynchronousJobOccupiesThroughActualMainConsumer() {
        val h = Harness(); h.begin(); assertEquals(1, h.starts)
        h.deliver(); assertTrue(h.session.hasActiveJob); assertEquals(0, h.settlements)
        h.ui.drain(); assertEquals(1, h.settlements); assertEquals(2, h.starts)
    }
    @Test fun duplicateAdoptedFrameNeverClosesOrSettlesAgain() {
        val h = Harness(); h.begin(); val frame = Frame(); val old = h.callback
        old(ThumbnailDecodeResult.Released(frame)) { h.settlements++ }; h.ui.drain()
        repeat(3) { old(ThumbnailDecodeResult.Released(frame)) { h.settlements++ } }; h.ui.drain()
        assertEquals(0, frame.closes); assertEquals(1, h.settlements); assertEquals(2, h.starts)
    }
    @Test fun distinctDuplicateOnlyClosesItsNeverAdoptedOrphan() {
        val h = Harness(); h.begin(); val adopted = Frame(); val orphan = Frame(); val old = h.callback
        h.deliver(adopted); h.ui.drain(); old(ThumbnailDecodeResult.Released(orphan)) { fail() }
        assertEquals(0, adopted.closes); assertEquals(1, orphan.closes); assertEquals(2, h.starts)
    }
    @Test fun settlementFailureRetainsExactJobAndBlocksNext() {
        val h = Harness(); h.begin(); h.deliver { error("fixed") }; h.ui.drain()
        assertTrue(h.session.hasActiveJob); assertTrue(h.session.hasUnconfirmedResources); assertEquals(1, h.starts)
        h.session.close(); assertTrue(h.session.hasActiveJob)
    }
    @Test fun reentrantSettlementCannotStartReplacementBeforeReturning() {
        val h = Harness(); h.begin()
        h.deliver { h.session.request(2); assertEquals(1, h.starts); h.settlements++ }
        h.ui.drain(); assertEquals(2, h.starts)
    }
    @Test fun unknownStartWithLateResultCannotPretendCleanup() {
        val h = Harness(); h.startResult = ThumbnailStartResult.UNKNOWN; h.begin()
        h.deliver(); h.ui.drain()
        assertTrue(h.session.hasActiveJob); assertTrue(h.session.hasUnconfirmedResources)
        assertEquals(0, h.settlements); assertEquals(1, h.starts)
    }
    @Test fun postExecuteThenThrowDoesNotUndoActualSingleSettlement() {
        val h = Harness(); h.ui.executeThenThrow = true; h.begin(); h.deliver()
        assertEquals(1, h.settlements); assertEquals(2, h.starts)
    }
    @Test fun rejectedPostRetainsResultAndOwnerWithoutReplacement() {
        val h = Harness(); h.ui.reject = true; h.begin(); val frame = Frame(); h.deliver(frame)
        assertTrue(h.session.hasActiveJob); assertTrue(h.session.hasUnconfirmedResources)
        assertEquals(0, frame.closes); assertEquals(0, h.settlements); assertEquals(1, h.starts)
    }
    @Test fun viewportChangeClosesLateResultBeforeSettlement() {
        val h = Harness(); h.begin(); val frame = Frame()
        h.session.updatePage(listOf(2), true)
        h.deliver(frame) { assertEquals(1, frame.closes); h.settlements++ }; h.ui.drain()
        assertEquals(1, h.settlements); assertEquals(0, h.session.retainedCount)
    }
    @Test fun failedDiscardNeverReturnsResultReservation() {
        val h = Harness(); h.begin(); h.session.updatePage(emptyList(), false)
        h.deliver(Frame(true)); h.ui.drain()
        assertTrue(h.session.hasActiveJob); assertTrue(h.session.hasUnconfirmedResources)
        assertEquals(1, h.session.retainedCount); assertEquals(0, h.settlements)
    }
    @Test fun executorExecuteThenThrowRetainsActualStartedOwner() {
        val ui = Ui(); var callback: ((ThumbnailDecodeResult<Frame>, () -> Unit) -> Unit)? = null
        val s = TypedBoundedThumbnailSession(AsyncThumbnailDecoder<Int, Frame> { _, _, done -> callback = done; ThumbnailStartResult.ACCEPTED },
            ThumbnailWorkLane { it.run(); error("fixed") }, ui, {}, Harness().adapter)
        s.updatePage(listOf(1), true); s.request(1)
        callback!!(ThumbnailDecodeResult.Released(Frame())) { fail() }; ui.drain()
        assertTrue(s.hasActiveJob); assertTrue(s.hasUnconfirmedResources)
    }
}
