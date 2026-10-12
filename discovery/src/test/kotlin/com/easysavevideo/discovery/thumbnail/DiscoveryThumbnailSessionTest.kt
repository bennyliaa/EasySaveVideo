package com.easysavevideo.discovery.thumbnail

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owned synthetic pixels/fake read authorities only. No network, Android decoder or device. */
class DiscoveryThumbnailSessionTest {
    @Test fun ownedFixtureIsCopiedBorrowedThenReleasedAndDeduplicated() {
        val h = Harness(); val result = result("one"); h.page(result)
        val bytes = byteArrayOf(10, 20, 30, 40)
        val cap = DiscoveryThumbnailCapability.ownedRgbaFixture(h.binding(result), 1, 1, bytes)
        bytes.fill(99)
        var view: ByteBuffer? = null
        h.callback = { _, frame ->
            view = (frame as RgbaFixtureFrame).pixels()
            assertTrue(view!!.isReadOnly); assertEquals(10, view!!.get(0).toInt())
        }
        repeat(10) { h.session.request(cap) }
        assertEquals(1, h.lane.pending.size); assertEquals(0, h.delivered.size)
        h.drain()
        assertEquals(1, h.delivered.size); assertEquals(0, view!!.get(0).toInt())
        assertEquals(DiscoveryThumbnailStatus.DELIVERED, h.session.status(cap.binding))
        repeat(10) { h.session.request(cap) }
        assertTrue(h.lane.pending.isEmpty())
    }

    @Test fun thumbnailUrlAndReportedRightsDoNotGrantReadAccess() {
        val h = Harness(); val result = result("one"); h.page(result)
        assertEquals(DiscoveryThumbnailStatus.NOT_REQUESTED, h.session.status(h.binding(result)))
        assertTrue(h.lane.pending.isEmpty())
        val other = result.copy(thumbnailUrl = "https://other.invalid/image")
        h.session.request(h.cap(other))
        assertTrue(h.lane.pending.isEmpty())
    }

    @Test fun sessionPageRevisionResultAndRightsChangesRejectOldCapabilitiesAndLateFrames() {
        for (change in 0..4) {
            val h = Harness(); val old = result("one"); h.page(old)
            val cap = h.cap(old); h.session.request(cap); h.lane.runNext()
            val next = when (change) {
                2 -> old.copy(fingerprint = "two")
                3 -> old.copy(rights = ResultRights(warnings = setOf(RightsWarning.STALE_OBSERVATION)))
                4 -> old.copy(thumbnailUrl = "https://new.invalid/image")
                else -> old
            }
            if (change == 0) h.stamp = SessionStamp("session", 2)
            if (change == 1) h.revision++
            h.page(next)
            h.session.request(cap); h.ui.posts()
            assertTrue(h.frames.single().closed); assertTrue(h.delivered.isEmpty())
            assertTrue(h.lane.pending.isEmpty())
            h.session.request(h.cap(next)); h.drain(); assertEquals(1, h.delivered.size)
        }
    }

    @Test fun mutableRightsInputsDoNotRewriteExistingCapability() {
        val warnings = mutableSetOf<RightsWarning>()
        val h = Harness(); val old = result("one").copy(rights = ResultRights(warnings = warnings))
        h.page(old); val cap = h.cap(old)
        warnings += RightsWarning.CONFLICTING_REPORTS
        h.page(old); h.session.request(cap)
        assertTrue(h.lane.pending.isEmpty())
    }

    @Test fun bindingDistinguishesMissingFromEmptyAndRejectsOversizeMetadata() {
        val h = Harness(); val one = result("one")
        assertNotEquals(h.binding(one), h.binding(one.copy(title = "")))
        for (bad in listOf("\uD800", "\uD801")) {
            assertThrows(IllegalArgumentException::class.java) { h.binding(one.copy(description = bad)) }
            assertThrows(IllegalArgumentException::class.java) { h.binding(one.copy(provenance = setOf(bad))) }
        }
        assertThrows(IllegalArgumentException::class.java) { h.binding(one.copy(thumbnailUrl = "x".repeat(8193))) }
        assertThrows(IllegalArgumentException::class.java) { h.binding(one.copy(identityAliases = (1..129).map { "$it" }.toSet())) }
    }

    @Test fun offscreenQueuedWorkIsDroppedAndReturningWaitsForActiveRelease() {
        val h = Harness(); val one = result("one"); val two = result("two"); h.page(one, two)
        h.session.request(h.cap(one)); h.session.request(h.cap(two))
        h.lane.runNext() // Frame now exists; publication is pending.
        h.session.setVisible(emptySet())
        assertEquals(0, h.session.queuedCount); assertTrue(h.session.hasActiveRead)
        h.session.setVisible(setOf(one.resultId)); h.session.request(h.cap(one))
        assertEquals(1, h.session.queuedCount); assertTrue(h.lane.pending.isEmpty())
        h.ui.posts(); assertTrue(h.frames.first().closed)
        h.drain(); assertEquals(2, h.calls); assertEquals(1, h.delivered.size)
    }

    @Test fun cancellationBeforeWorkerStartsDoesNotOpenReader() {
        val h = Harness(); val one = result("one"); h.page(one)
        h.session.request(h.cap(one)); h.session.setVisible(emptySet()); h.drain()
        assertEquals(0, h.calls); assertFalse(h.session.hasActiveRead)
    }

    @Test fun backgroundAndExitInvalidateLateResultsEvenWhenPageReturns() {
        for (exit in listOf(false, true)) {
            val h = Harness(); val one = result("one"); h.page(one)
            h.session.request(h.cap(one)); h.lane.runNext()
            if (exit) h.session.close() else {
                h.session.updatePage(h.stamp, h.revision, listOf(one), false)
                h.page(one)
            }
            h.ui.posts(); assertTrue(h.frames.single().closed); assertTrue(h.delivered.isEmpty())
            if (exit) {
                h.page(one); h.session.request(h.cap(one)); assertTrue(h.lane.pending.isEmpty())
            }
        }
    }

    @Test fun explicitQueueAndViewportLimitsAreEnforcedWithoutExtraWorkers() {
        val h = Harness(maxQueued = 1); val results = (1..4).map { result("r$it") }; h.page(*results.toTypedArray())
        results.forEach { h.session.request(h.cap(it)) }
        assertEquals(1, h.lane.pending.size); assertEquals(1, h.session.queuedCount)
        assertEquals(DiscoveryThumbnailStatus.QUEUE_FULL, h.session.status(h.binding(results.last())))
        h.drain(); assertEquals(2, h.calls)
        assertThrows(IllegalArgumentException::class.java) { h.session.setVisible(setOf("unknown")) }
        val tooMany = (1..17).map { result("r$it") }
        h.session.updatePage(h.stamp, 2, tooMany, true)
        assertThrows(IllegalArgumentException::class.java) { h.session.setVisible(tooMany.map { it.resultId }.toSet()) }
    }

    @Test fun timeoutFencesWithoutReleasingOrReplacingTheActiveOwner() {
        val h = Harness(); val one = result("one"); val two = result("two"); h.page(one, two)
        h.session.request(h.cap(one)); h.session.request(h.cap(two)); h.lane.runNext()
        h.ui.timeout(); assertEquals(1, h.stalls); assertTrue(h.session.hasActiveRead)
        assertEquals(0, h.session.queuedCount)
        h.session.request(h.cap(two)); assertTrue(h.lane.pending.isEmpty())
        h.ui.posts(); assertTrue(h.frames.single().closed); assertTrue(h.delivered.isEmpty())
        assertEquals(DiscoveryThumbnailStatus.FENCED, h.session.status(h.binding(one)))
        assertEquals(1, h.calls)
    }

    @Test fun closeKeepsWatchdogAndOldWatchdogCannotFenceNextRead() {
        val h = Harness(); val one = result("one"); val two = result("two"); h.page(one, two)
        h.session.request(h.cap(one)); h.session.request(h.cap(two))
        val old = h.ui.timers.single(); h.lane.runNext(); h.ui.posts()
        old.run(); assertEquals(0, h.stalls)
        h.session.close(); assertEquals(1, h.ui.timers.size)
        h.ui.timeout(); assertEquals(1, h.stalls); h.drain()
    }

    @Test fun unconfirmedReleaseAndThrownReaderFenceFutureReads() {
        for (throws in listOf(false, true)) {
            val h = Harness(); val one = result("one"); h.page(one)
            val cap = DiscoveryThumbnailCapability(h.binding(one), DiscoveryThumbnailReader {
                if (throws) throw IllegalStateException("private input must not be logged")
                DiscoveryThumbnailRead.ReleaseUnconfirmed
            })
            h.session.request(cap); h.drain()
            assertEquals(1, h.stalls); assertTrue(h.delivered.isEmpty()); assertFalse(h.session.hasActiveRead)
        }
    }

    @Test fun failedReadInvalidFrameAndThrowingConsumerReleaseWithoutRetryLoops() {
        for (mode in 0..3) {
            val h = Harness(); val one = result("one"); h.page(one)
            val frame = Frame(bytes = if (mode == 1) 5 else 4, width = if (mode == 2) 2 else 1)
            val cap = DiscoveryThumbnailCapability(h.binding(one), DiscoveryThumbnailReader {
                h.calls++; DiscoveryThumbnailRead.Released(if (mode == 0) null else frame)
            })
            if (mode == 3) h.callback = { _, _ -> throw IllegalStateException() }
            h.session.request(cap); h.drain(); repeat(5) { h.session.request(cap) }
            assertEquals(1, h.calls); assertFalse(h.session.hasActiveRead)
            if (mode != 0) assertTrue(frame.closed)
            assertEquals(DiscoveryThumbnailStatus.UNAVAILABLE, h.session.status(cap.binding))
        }
    }

    @Test fun revokedCapabilityCannotStartOrPublishAndBorrowCallbackCanCloseSession() {
        val h = Harness(); val one = result("one"); h.page(one)
        val revoked = h.cap(one); revoked.close(); h.session.request(revoked)
        assertTrue(h.lane.pending.isEmpty())
        val cap = h.cap(one); h.session.request(cap); h.lane.runNext(); cap.close(); h.ui.posts()
        assertTrue(h.frames.single().closed); assertTrue(h.delivered.isEmpty())
        h.session.setVisible(emptySet()); h.session.setVisible(setOf(one.resultId))
        h.callback = { _, _ -> h.session.close() }
        h.session.request(h.cap(one)); h.drain()
        assertTrue(h.frames.all { it.closed }); assertFalse(h.session.hasActiveRead)
    }

    @Test fun rejectedSubmissionAndDuplicateTaskOrCallbackAreSafe() {
        val h = Harness(); val one = result("one"); h.page(one)
        h.lane.reject = true; h.session.request(h.cap(one))
        assertEquals(DiscoveryThumbnailStatus.BUSY, h.session.status(h.binding(one)))
        assertFalse(h.session.hasActiveRead); assertTrue(h.ui.timers.isEmpty())
        h.lane.reject = false; h.session.setVisible(emptySet()); h.session.setVisible(setOf(one.resultId))
        h.session.request(h.cap(one)); val task = h.lane.pending.removeFirst(); task.run(); task.run()
        assertEquals(1, h.calls)
        val callback = h.ui.pending.removeFirst(); callback.run(); callback.run()
        assertEquals(1, h.delivered.size); assertEquals(1, h.frames.single().closes)
    }

    @Test fun throwingFrameCloseAndStallNotificationCannotAdmitReplacement() {
        val h = Harness(); val one = result("one"); val two = result("two"); h.page(one, two)
        h.stallThrows = true
        val bad = object : DiscoveryThumbnailFrame {
            override val width = 1; override val height = 1; override val allocationBytes = 4L
            override fun close() { throw IllegalStateException() }
        }
        h.session.request(DiscoveryThumbnailCapability(h.binding(one), DiscoveryThumbnailReader { DiscoveryThumbnailRead.Released(bad) }))
        h.session.request(h.cap(two)); h.drain()
        assertEquals(1, h.stalls); assertEquals(0, h.calls); assertTrue(h.lane.pending.isEmpty())
        assertEquals(DiscoveryThumbnailStatus.FENCED, h.session.status(h.binding(two)))
    }

    @Test fun uncertainSubmissionAndFailedUiPostKeepOwnerUntilWatchdogOrActualCompletion() {
        val h = Harness(); val one = result("one"); h.page(one)
        h.lane.throwAfterSubmit = true; h.session.request(h.cap(one))
        assertEquals(1, h.stalls); assertTrue(h.session.hasActiveRead); h.drain()
        assertFalse(h.session.hasActiveRead); assertEquals(0, h.calls)
        val other = Harness(); other.page(one); other.ui.throwPost = true
        other.session.request(other.cap(one)); other.lane.runNext()
        assertTrue(other.frames.single().closed); assertTrue(other.session.hasActiveRead)
        other.ui.timeout(); assertEquals(1, other.stalls)
        val queuedThenFailed = Harness(); queuedThenFailed.page(one); queuedThenFailed.ui.throwAfterPost = true
        queuedThenFailed.session.request(queuedThenFailed.cap(one)); queuedThenFailed.lane.runNext()
        queuedThenFailed.ui.posts(); assertEquals(1, queuedThenFailed.frames.single().closes)
        assertTrue(queuedThenFailed.delivered.isEmpty()); assertTrue(queuedThenFailed.session.hasActiveRead)
        queuedThenFailed.ui.timeout(); assertEquals(1, queuedThenFailed.stalls)
    }

    @Test fun consumerPageChangeCanQueueNewAuthorityWithoutPublishingOldAgain() {
        val h = Harness(); val one = result("one"); h.page(one)
        h.callback = { _, _ ->
            h.callback = { _, _ -> }
            h.revision++
            h.page(one); h.session.request(h.cap(one))
            assertEquals(1, h.session.queuedCount)
            assertTrue(h.lane.pending.isEmpty())
        }
        h.session.request(h.cap(one)); h.drain()
        assertEquals(2, h.calls); assertEquals(2, h.delivered.size)
        assertNotEquals(h.delivered[0], h.delivered[1]); assertTrue(h.frames.all { it.closed })
    }

    @Test fun uiWinningDeliveryClaimIsNotCancelledByPostThrowingAfterTransfer() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        var delivery: Thread? = null
        var observedUiClaim = false
        val ui = object : DiscoveryThumbnailDispatcher {
            override fun requireCurrentThread() {
                if (Thread.currentThread() === delivery) {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                }
            }
            override fun post(task: Runnable) {
                delivery = Thread(task, "thumbnail-owned-fixture-ui")
                delivery!!.start()
                check(entered.await(5, TimeUnit.SECONDS)) // UI has won the CAS but not checked cancellation.
                observedUiClaim = true
                throw IllegalStateException()
            }
            override fun later(task: Runnable, delayMillis: Long) = Unit
            override fun remove(task: Runnable) = Unit
        }
        val lane = Lane(); val one = result("one"); val stamp = SessionStamp("session", 1)
        val binding = DiscoveryThumbnailBinding.of(stamp, 1, one); val frame = Frame()
        var delivered = 0
        val session = DiscoveryThumbnailSession(DiscoveryThumbnailBudget(1, 1, 4, 1, 1, 100), lane, ui, {}, { _, _ -> delivered++ })
        session.updatePage(stamp, 1, listOf(one), true); session.setVisible(setOf(one.resultId))
        session.request(DiscoveryThumbnailCapability(binding, DiscoveryThumbnailReader { DiscoveryThumbnailRead.Released(frame) }))
        try { lane.runNext() } finally { proceed.countDown() }
        delivery!!.join(5000)
        assertTrue(observedUiClaim)
        assertFalse(delivery!!.isAlive); assertEquals(1, delivered); assertEquals(1, frame.closes)
        assertEquals(DiscoveryThumbnailStatus.DELIVERED, session.status(binding)); assertFalse(session.hasActiveRead)
    }

    private class Frame(bytes: Long = 4, override val width: Int = 1, override val height: Int = 1) : DiscoveryThumbnailFrame {
        override val allocationBytes = bytes
        var closes = 0; val closed get() = closes > 0
        override fun close() { closes++ }
    }
    private class Lane : DiscoveryThumbnailLane {
        val pending = ArrayDeque<Runnable>(); var reject = false; var throwAfterSubmit = false
        override fun submit(task: Runnable): Boolean { if (reject) return false; pending.add(task); if (throwAfterSubmit) throw IllegalStateException(); return true }
        fun runNext() = pending.removeFirst().run()
    }
    private class Ui : DiscoveryThumbnailDispatcher {
        val pending = ArrayDeque<Runnable>(); val timers = mutableListOf<Runnable>(); var throwPost = false; var throwAfterPost = false
        override fun requireCurrentThread() = Unit
        override fun post(task: Runnable) { if (throwPost) throw IllegalStateException(); pending.add(task); if (throwAfterPost) throw IllegalStateException() }
        override fun later(task: Runnable, delayMillis: Long) { timers.add(task) }
        override fun remove(task: Runnable) { timers.remove(task) }
        fun posts() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        fun timeout() { timers.toList().forEach { timers.remove(it); it.run() } }
    }
    private class Harness(maxQueued: Int = 2) {
        var stamp = SessionStamp("session", 1); var revision = 1L
        val lane = Lane(); val ui = Ui(); var stalls = 0; var calls = 0; var stallThrows = false
        val frames = mutableListOf<Frame>(); val delivered = mutableListOf<DiscoveryThumbnailBinding>()
        var callback: (DiscoveryThumbnailBinding, DiscoveryThumbnailFrame) -> Unit = { _, _ -> }
        val session = DiscoveryThumbnailSession(DiscoveryThumbnailBudget(1, 1, 4, 16, maxQueued, 100), lane, ui,
            { stalls++; if (stallThrows) throw IllegalStateException() }, { binding, frame -> delivered += binding; callback(binding, frame) })
        fun binding(result: VideoSearchResult) = DiscoveryThumbnailBinding.of(stamp, revision, result)
        fun cap(result: VideoSearchResult) = DiscoveryThumbnailCapability(binding(result), DiscoveryThumbnailReader {
            calls++; DiscoveryThumbnailRead.Released(Frame().also(frames::add))
        })
        fun page(vararg results: VideoSearchResult) {
            session.updatePage(stamp, revision, results.toList(), true); session.setVisible(results.map { it.resultId }.toSet())
        }
        fun drain() { while (lane.pending.isNotEmpty() || ui.pending.isNotEmpty()) { if (lane.pending.isNotEmpty()) lane.runNext(); ui.posts() } }
    }
    companion object {
        private fun result(id: String) = VideoSearchResult(id, "https://owned.invalid/$id", null, "Owned fixture", "owned", id,
            "https://untrusted.invalid/image", null, null, null, setOf("fixture"), setOf(id))
    }
}
