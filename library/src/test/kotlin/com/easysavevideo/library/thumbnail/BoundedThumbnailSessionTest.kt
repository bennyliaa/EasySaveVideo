package com.easysavevideo.library.thumbnail

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

/** Pure scheduling/read-resource fakes. No Android native decoding, device or media is used. */
class BoundedThumbnailSessionTest {
    @Test fun exactReadyItemIsDecodedOffUiAndCachedOnlyAfterCompletion() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true)
        h.session.request(item)
        assertEquals(0, h.decoder.calls)
        assertEquals(1, h.work.pending.size)
        h.work.runNext()
        assertEquals(ThumbnailState.Waiting, h.session.state(item))
        h.ui.posts()
        assertTrue(h.session.state(item) is ThumbnailState.Ready)
        assertEquals(item.readRef(), h.decoder.lastReference)
        h.session.request(item)
        assertEquals(1, h.decoder.calls)
        assertEquals(147_456, h.session.retainedBytes)
    }

    @Test fun arbitraryStaleNonReadyAndUnknownSelectionsNeverOpenSource() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true)
        listOf(item.copy(revision = 2), item.copy(representationGeneration = 2), item.copy(displayName = "Changed"),
            item.copy(lifecycle = MediaLifecycle.DELETE_PENDING), media("unknown")).forEach(h.session::request)
        assertTrue(h.work.pending.isEmpty())
        assertEquals(0, h.decoder.calls)
    }

    @Test fun lateCompletionCannotPublishAfterRevisionChangeOrMove() {
        val h = Harness(); val old = media("one"); val moved = old.copy(revision = 2, folderId = "folder")
        h.session.updatePage(listOf(old), true); h.session.request(old); h.work.runNext()
        val obsolete = h.decoder.frames.single()
        h.session.updatePage(listOf(moved), true); h.session.request(moved)
        h.ui.posts()
        assertTrue(obsolete.closed)
        assertEquals(1, h.work.pending.size)
        h.drain()
        assertTrue(h.session.state(moved) is ThumbnailState.Ready)
        assertTrue(h.session.state(old) is ThumbnailState.Unavailable)
    }

    @Test fun deleteAndRenameImmediatelyDropCachedOldAuthority() {
        val h = Harness(); val old = media("one")
        h.session.updatePage(listOf(old), true); h.session.request(old); h.drain()
        val image = h.decoder.frames.single()
        val renamed = old.copy(revision = 2, displayName = "Renamed")
        h.session.updatePage(listOf(renamed), true)
        assertTrue(image.closed); assertEquals(0, h.session.retainedCount)
        h.session.request(renamed); h.drain()
        h.session.updatePage(emptyList(), true)
        assertTrue(h.decoder.frames.all { it.closed })
        assertEquals(0, h.session.retainedBytes)
    }

    @Test fun sameReferenceReturningAfterBackgroundDoesNotAcceptOldSessionResult() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true); h.session.request(item); h.work.runNext()
        h.session.updatePage(listOf(item), false); h.session.updatePage(listOf(item), true)
        h.session.request(item); h.ui.posts()
        assertTrue(h.decoder.frames.single().closed)
        h.drain(); assertEquals(2, h.decoder.calls)
        assertTrue(h.session.state(item) is ThumbnailState.Ready)
    }

    @Test fun oneActiveJobAndExplicitQueueLimitStayBounded() {
        val h = Harness(ThumbnailLimits(maxQueued = 2)); val items = (1..8).map { media("item-$it") }
        h.session.updatePage(items, true); items.forEach(h.session::request)
        assertEquals(1, h.work.pending.size)
        assertEquals(2, h.session.queuedCount)
        assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.QUEUE_FULL), h.session.state(items.last()))
        h.drain(); assertEquals(3, h.decoder.calls)
        assertEquals(1, h.work.maxPending)
    }

    @Test fun cacheEvictsOldestWithinBothEntryAndByteBudgets() {
        val h = Harness(ThumbnailLimits(maxCacheBytes = 294_912, maxCacheEntries = 2)); val items = (1..4).map { media("item-$it") }
        h.session.updatePage(items, true)
        items.forEach { h.session.request(it); h.drain() }
        assertEquals(2, h.session.retainedCount)
        assertEquals(294_912, h.session.retainedBytes)
        assertTrue(h.decoder.frames.take(2).all { it.closed })
        assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.CACHE_LIMIT), h.session.state(items.first()))
    }

    @Test fun actualOversizePixelsOrAllocationAreRejectedEvenFromSuccessfulDecoder() {
        listOf(Frame(width = 257), Frame(height = 145), Frame(bytes = 147_457), Frame(bytes = 0)).forEach { frame ->
            val h = Harness(); val item = media("one")
            h.decoder.nextFrame = { frame }
            h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
            assertTrue(frame.closed)
            assertEquals(0, h.session.retainedBytes)
            assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.DECODE_FAILED), h.session.state(item))
        }
    }

    @Test fun decoderFailureIsHonestAndDoesNotLoopOnRecomposition() {
        val h = Harness(); val item = media("one")
        h.decoder.fail = true
        h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
        repeat(10) { h.session.request(item) }
        assertEquals(1, h.decoder.calls)
        assertTrue(h.work.pending.isEmpty())
        assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.DECODE_FAILED), h.session.state(item))
    }

    @Test fun rejectedWorkerSubmissionDoesNotClaimSuccessOrStayActive() {
        val h = Harness(); val item = media("one"); h.work.reject = true
        h.session.updatePage(listOf(item), true); h.session.request(item)
        assertFalse(h.session.hasActiveJob)
        assertTrue(h.ui.timers.isEmpty())
        assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.BUSY), h.session.state(item))
    }

    @Test fun timeoutDoesNotFreeActiveJobOrSubmitReplacement() {
        val h = Harness(); val items = listOf(media("one"), media("two"))
        h.session.updatePage(items, true); items.forEach(h.session::request)
        h.ui.timeouts()
        assertEquals(1, h.fences)
        assertTrue(h.session.hasActiveJob)
        assertEquals(0, h.session.queuedCount)
        assertEquals(1, h.work.pending.size)
        h.session.request(items.last())
        h.drain()
        assertTrue(h.decoder.cancellationObserved)
        assertTrue(h.decoder.frames.single().closed)
        assertEquals(1, h.decoder.calls)
        assertEquals(ThumbnailState.Unavailable(ThumbnailUnavailable.RELEASE_UNCONFIRMED), h.session.state(items.first()))
    }

    @Test fun unconfirmedReleaseFencesFollowingJobsAndNeverProducesAFrame() {
        val h = Harness(); val items = listOf(media("one"), media("two"))
        h.decoder.uncertain = true
        h.session.updatePage(items, true); items.forEach(h.session::request); h.drain()
        assertEquals(1, h.fences)
        assertEquals(1, h.decoder.calls)
        assertEquals(0, h.session.retainedCount)
        assertTrue(h.work.pending.isEmpty())
    }

    @Test fun disposeCancelsQueuedWorkButRetainsWatchdogForHungNativeOwner() {
        val h = Harness(); val items = listOf(media("one"), media("two"))
        h.session.updatePage(items, true); items.forEach(h.session::request)
        h.session.close()
        assertEquals(0, h.session.queuedCount)
        assertEquals(1, h.ui.timers.size)
        h.ui.timeouts(); assertEquals(1, h.fences)
        h.drain()
        assertTrue(h.decoder.frames.single().closed)
        assertEquals(1, h.decoder.calls)
    }

    @Test fun oldWatchdogCannotFenceNewJobAfterConfirmedCompletion() {
        val h = Harness(); val items = listOf(media("one"), media("two"))
        h.session.updatePage(items, true); items.forEach(h.session::request)
        val oldTimeout = h.ui.timers.single()
        h.work.runNext(); h.ui.posts()
        oldTimeout.run()
        assertEquals(0, h.fences)
        h.drain(); assertEquals(2, h.decoder.calls)
    }

    @Test fun offscreenQueuedItemNeverDecodesAndActiveCancellationDoesNotFakeRelease() {
        val h = Harness(); val first = media("one"); val second = media("two")
        h.session.updatePage(listOf(first,second),true); h.session.request(first);h.session.request(second)
        h.session.cancel(second)
        assertEquals(0,h.session.queuedCount)
        h.session.cancel(first)
        assertTrue(h.session.hasActiveJob)
        assertEquals(1,h.ui.timers.size)
        h.work.runNext(); h.ui.posts()
        assertTrue(h.decoder.cancellationObserved)
        assertTrue(h.decoder.frames.single().closed)
        assertFalse(h.session.hasActiveJob)
        assertEquals(0,h.session.retainedCount)
        assertEquals(1,h.decoder.calls)
    }
    @Test fun offscreenCompletedCacheRemainsBoundedAndDoesNotDecodeAgain() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item),true);h.session.request(item);h.drain()
        h.session.cancel(item);h.session.request(item);h.drain()
        assertEquals(1,h.decoder.calls)
        assertEquals(1,h.session.retainedCount)
        assertEquals(147_456,h.session.retainedBytes)
        h.session.updatePage(listOf(item),false)
        assertEquals(0,h.session.retainedCount)
        assertTrue(h.decoder.frames.single().closed)
    }
    @Test fun returningVisibleWaitsForOldCancelledCompletionBeforeReplacement() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item),true);h.session.request(item);h.session.cancel(item);h.session.request(item)
        assertEquals(1,h.work.pending.size)
        assertEquals(1,h.session.queuedCount)
        h.drain()
        assertEquals(2,h.decoder.calls)
        assertTrue(h.decoder.frames.first().closed)
        assertTrue(h.session.state(item) is ThumbnailState.Ready)
        assertEquals(1,h.work.maxPending)
    }
    @Test fun sourceRetirementPurgesCachedOlderLogicalRevisionBeforeAck() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
        var ack = false
        h.session.retireExact(item.copy(revision = 2).readRef()) {
            assertTrue(h.decoder.frames.single().closed)
            assertEquals(0, h.session.retainedBytes)
            ack = true
        }
        assertTrue(ack)
        h.session.updatePage(listOf(item), true); h.session.request(item)
        assertTrue(h.work.pending.isEmpty())
    }
    @Test fun sourceRetirementWaitsForLateFrameCloseAndActualCompletion() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true); h.session.request(item); h.work.runNext()
        var ack = false
        h.session.retireExact(item.readRef()) {
            assertTrue(h.decoder.frames.single().closed)
            ack = true
        }
        assertFalse(ack)
        h.ui.timeouts(); assertFalse(ack)
        h.ui.posts(); assertTrue(ack)
    }
    @Test fun sourceRetirementNeverAcknowledgesUnconfirmedNativeRelease() {
        val h = Harness(); val item = media("one"); h.decoder.uncertain = true
        h.session.updatePage(listOf(item), true); h.session.request(item)
        var acknowledgements = 0
        h.session.retireExact(item.readRef()) { acknowledgements++ }
        h.session.close(); h.drain()
        assertFalse(h.session.hasActiveJob)
        assertTrue(h.session.hasUnconfirmedResources)
        assertEquals(0, acknowledgements)
    }
    @Test fun alreadyUnconfirmedOldSessionCannotClaimRetiredLater() {
        val h = Harness(); val item = media("one"); h.decoder.uncertain = true
        h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
        h.session.close()
        var ack = false
        h.session.retireExact(item.copy(revision = 4).readRef()) { ack = true }
        assertFalse(ack)
    }
    @Test fun retirementDoesNotPurgeDifferentRepresentationGeneration() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
        var ack = false
        h.session.retireExact(item.copy(representationGeneration = 2).readRef()) { ack = true }
        assertTrue(ack); assertFalse(h.decoder.frames.single().closed)
    }
    @Test fun retirementDropsExactQueuedWorkWithoutWaitingForOtherSource() {
        val h = Harness(); val one = media("one"); val two = media("two")
        h.session.updatePage(listOf(one, two), true); h.session.request(one); h.session.request(two)
        var ack = false
        h.session.retireExact(two.readRef()) { ack = true }
        assertTrue(ack); assertTrue(h.session.hasActiveJob); assertEquals(0, h.session.queuedCount)
        h.drain(); assertEquals(1, h.decoder.calls)
    }
    @Test fun cachedFrameCloseFailureIsStronglyRetainedAndNeverAcknowledged() {
        val h = Harness(); val item = media("one")
        h.decoder.nextFrame = { Frame(throwOnClose = true) }
        h.session.updatePage(listOf(item), true); h.session.request(item); h.drain()
        var ack = false
        try { h.session.retireExact(item.readRef()) { ack = true }; fail("Expected close failure") } catch (_: IllegalStateException) { }
        assertTrue(h.session.hasUnconfirmedResources); assertEquals(1, h.session.retainedCount)
        try { h.session.close() } catch (_: IllegalStateException) { }
        assertFalse(ack); assertEquals(1, h.decoder.frames.single().closeCalls)
    }
    @Test fun lateFrameCloseFailureIsHeldAfterActualDecoderReleaseWithoutAck() {
        val h = Harness(); val item = media("one")
        h.decoder.nextFrame = { Frame(throwOnClose = true) }
        h.session.updatePage(listOf(item), true); h.session.request(item); h.work.runNext()
        var ack = false
        h.session.retireExact(item.readRef()) { ack = true }
        h.ui.posts(); h.session.close()
        assertFalse(h.session.hasActiveJob); assertTrue(h.session.hasUnconfirmedResources)
        assertEquals(1, h.session.retainedCount); assertFalse(ack)
        assertEquals(1, h.decoder.frames.single().closeCalls)
    }
    @Test fun replacementRetirementObserverWaitsForSameActualCompletion() {
        val h = Harness(); val item = media("one")
        h.session.updatePage(listOf(item), true); h.session.request(item)
        var oldAck = false; var newAck = false
        h.session.retireExact(item.readRef()) { oldAck = true }
        h.session.retireExact(item.copy(revision = 2).readRef()) { newAck = true }
        assertFalse(newAck); h.drain()
        assertFalse(oldAck); assertTrue(newAck)
    }
    private class Frame(override val width: Int = 256, override val height: Int = 144, private val bytes: Long = 147_456, private val throwOnClose: Boolean = false) : ThumbnailFrame {
        override val allocationBytes get() = bytes
        var closed = false; var closeCalls = 0
        override fun close() { closeCalls++; if (throwOnClose) error("fixed close failure"); closed = true }
    }
    private class Decoder : ThumbnailDecoder<Frame> {
        var calls = 0; var fail = false; var uncertain = false; var cancellationObserved = false
        var lastReference: MediaReadRef? = null
        var nextFrame: () -> Frame = { Frame() }
        val frames = mutableListOf<Frame>()
        override fun decode(reference: MediaReadRef, cancelled: () -> Boolean): ThumbnailDecodeResult<Frame> {
            calls++; lastReference = reference; cancellationObserved = cancelled()
            if (uncertain) return ThumbnailDecodeResult.ReleaseUnconfirmed
            if (fail) return ThumbnailDecodeResult.Released(null)
            return ThumbnailDecodeResult.Released(nextFrame().also(frames::add))
        }
    }
    private class Worker : ThumbnailWorkLane {
        val pending = ArrayDeque<Runnable>(); var reject = false; var maxPending = 0
        override fun submit(task: Runnable): Boolean {
            if (reject) return false
            pending.addLast(task); maxPending = maxOf(maxPending, pending.size); return true
        }
        fun runNext() = pending.removeFirst().run()
    }
    private class Ui : ThumbnailUiDispatcher {
        val queued = ArrayDeque<Runnable>(); val timers = mutableListOf<Runnable>()
        override fun requireCurrentThread() = Unit
        override fun post(task: Runnable) { queued.addLast(task) }
        override fun later(task: Runnable, delayMillis: Long) { timers.add(task) }
        override fun remove(task: Runnable) { timers.remove(task) }
        fun posts() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        fun timeouts() { val pending = timers.toList(); timers.clear(); pending.forEach(Runnable::run) }
    }
    private class Harness(limits: ThumbnailLimits = ThumbnailLimits()) {
        val decoder = Decoder(); val work = Worker(); val ui = Ui(); var fences = 0
        val session = BoundedThumbnailSession(decoder, work, ui, { fences++ }, limits)
        fun drain() { while (work.pending.isNotEmpty() || ui.queued.isNotEmpty()) { if (work.pending.isNotEmpty()) work.runNext(); ui.posts() } }
    }
    companion object {
        private fun media(id: String) = MediaItem(id, 1, 1, null, "Video", 1, MediaDateKind.SAVED, 128, null, null, null)
    }
}
