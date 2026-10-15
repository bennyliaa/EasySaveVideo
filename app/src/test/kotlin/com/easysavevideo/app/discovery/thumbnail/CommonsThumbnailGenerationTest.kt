package com.easysavevideo.app.discovery.thumbnail

import com.easysavevideo.core.model.AppSettings
import com.easysavevideo.core.model.DiscoverySettings
import com.easysavevideo.core.model.ThemeMode
import com.easysavevideo.discovery.SessionStamp
import com.easysavevideo.discovery.VideoSearchResult
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailBinding
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailBudget
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailCapability
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailDispatcher
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailFrame
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailLane
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailRead
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailSession
import com.easysavevideo.discovery.thumbnail.DiscoveryThumbnailStatus
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Executes the production generation seam and pure session; no Android object initialization. */
class CommonsThumbnailGenerationTest {
    @Test(timeout = 15_000) fun refreshBlockedAfterCaptureCannotRelabelOldWorkWithAWithdrawalGeneration() {
        val h = Harness()
        val generation = AtomicLong(11)
        val requested = AtomicLong(0)
        val applied = AtomicLong(0)
        val oldAuthority = h.capability(generation, 11) { requested.get() == applied.get() }
        val captured = CountDownLatch(1)
        val releaseStoreRead = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val published = AtomicLong(Long.MIN_VALUE)
        val failure = AtomicReference<Throwable?>()
        val refresh = Thread({
            try {
                val capturedGeneration = generation.get()
                val snapshot = CommonsThumbnailPolicy.publicationGeneration(capturedGeneration, requested.get(), applied.get())
                captured.countDown()
                check(releaseStoreRead.await(5, TimeUnit.SECONDS)) { "Owned store read release timed out" }
                published.set(snapshot)
            } catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }, "thumbnail-owned-refresh").apply { isDaemon = true }
        refresh.start()
        try {
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val token = requested.incrementAndGet()
            generation.incrementAndGet()
            releaseStoreRead.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(11L, published.get())
            applied.set(token)
            assertNull(CommonsThumbnailPolicy.issueAtGeneration(published.get(), generation::get) {
                h.capability(generation, published.get()) { requested.get() == applied.get() }
            })
            // Exercise the real capability/session guard as well as the issuance boundary.
            h.session.request(oldAuthority)
            h.drain()
            assertEquals(0, h.reads)
            assertEquals(0, h.published)
            assertTrue(h.workers.isEmpty())
        } finally {
            releaseStoreRead.countDown()
            refresh.join(5_000)
            oldAuthority.close()
            h.session.close()
        }
        assertFalse(refresh.isAlive)
    }

    @Test fun pendingSnapshotAndOldCommandAckCannotAuthorizeReadsUntilTheNewestCommandAndFreshCapture() {
        val h = Harness()
        val generation = AtomicLong(11)
        val requested = AtomicLong(0)
        val applied = AtomicLong(0)
        val oldToken = requested.incrementAndGet()
        generation.incrementAndGet()
        val pendingSnapshot = CommonsThumbnailPolicy.publicationGeneration(generation.get(), requested.get(), applied.get())
        assertEquals(-1L, pendingSnapshot)
        val pendingAuthority = h.capability(generation, pendingSnapshot) { requested.get() == applied.get() }
        val newestToken = requested.incrementAndGet()
        generation.incrementAndGet()
        applied.set(oldToken)
        assertNotEquals(requested.get(), applied.get())
        assertEquals(-1L, CommonsThumbnailPolicy.publicationGeneration(generation.get(), requested.get(), applied.get()))
        val pendingIntentAuthority = h.capability(generation, generation.get()) { requested.get() == applied.get() }
        h.session.request(pendingIntentAuthority)
        h.session.request(pendingAuthority)
        h.drain()
        assertEquals(0, h.reads)
        assertTrue(h.workers.isEmpty())
        pendingIntentAuthority.close()

        applied.set(newestToken)
        assertNull(CommonsThumbnailPolicy.issueAtGeneration(pendingSnapshot, generation::get) {
            h.capability(generation, pendingSnapshot) { requested.get() == applied.get() }
        })
        h.session.request(pendingAuthority)
        h.drain()
        assertEquals(0, h.reads)
        val freshSnapshot = CommonsThumbnailPolicy.publicationGeneration(generation.get(), requested.get(), applied.get())
        assertEquals(generation.get(), freshSnapshot)
        val freshAuthority = checkNotNull(CommonsThumbnailPolicy.issueAtGeneration(freshSnapshot, generation::get) {
            h.capability(generation, freshSnapshot) { requested.get() == applied.get() }
        })
        h.session.request(freshAuthority)
        h.drain()
        assertEquals(1, h.reads)
        assertEquals(1, h.published)
        assertEquals(1, h.closes)
        assertEquals(DiscoveryThumbnailStatus.DELIVERED, h.session.status(h.binding))
        pendingAuthority.close()
        freshAuthority.close()
        h.session.close()
    }

    @Test fun equalSettingsAndThemeOnlyNotificationsDoNotWithdrawButEachActualTransitionDoes() {
        val original = AppSettings()
        val previous = AtomicReference(original.discovery)
        var withdrawals = 0
        fun notify(value: DiscoverySettings): Boolean {
            val changed = CommonsThumbnailPolicy.settingsInputChanged(previous, value)
            if (changed) withdrawals++
            return changed
        }
        assertFalse(notify(DiscoverySettings()))
        assertFalse(notify(original.discovery.copy()))
        assertFalse(notify(original.copy(appearance = original.appearance.copy(themeMode = ThemeMode.DARK)).discovery))
        assertEquals(0, withdrawals)
        val changed = original.discovery.copy(pageSize = 30)
        assertTrue(notify(changed))
        assertFalse(notify(changed))
        assertFalse(notify(changed.copy()))
        assertEquals(1, withdrawals)
        assertEquals(changed, previous.get())
        assertTrue(notify(original.discovery))
        assertFalse(notify(original.discovery.copy()))
        assertEquals(2, withdrawals)
        assertEquals(original.discovery, previous.get())
    }

    @Test fun withdrawalAfterTheOldAllowedCheckPreventsIssuanceAndRead() {
        val h = Harness()
        val generation = AtomicLong(11)
        val published = 11L
        val allowed = published == generation.get()
        assertTrue(allowed)
        generation.incrementAndGet()
        var issues = 0
        val issued = if (allowed) CommonsThumbnailPolicy.issueAtGeneration(published, generation::get) {
            issues++
            h.capability(generation, published)
        } else null
        issued?.let(h.session::request)
        h.drain()
        assertNull(issued)
        assertEquals(0, issues)
        assertEquals(0, h.reads)
    }

    @Test fun onlyAFreshPublishedEpochCanIssueAfterWithdrawal() {
        val h = Harness()
        val generation = AtomicLong(11)
        val oldPublished = generation.get()
        generation.incrementAndGet()
        var issues = 0
        assertNull(CommonsThumbnailPolicy.issueAtGeneration(oldPublished, generation::get) {
            issues++
            h.capability(generation, oldPublished)
        })
        val freshPublished = generation.get()
        val issued = checkNotNull(CommonsThumbnailPolicy.issueAtGeneration(freshPublished, generation::get) {
            issues++
            h.capability(generation, freshPublished)
        })
        assertEquals(1, issues)
        assertEquals(0, h.reads)
        h.session.request(issued)
        h.drain()
        assertEquals(1, h.reads)
        assertEquals(1, h.published)
        assertEquals(1, h.closes)
        assertEquals(DiscoveryThumbnailStatus.DELIVERED, h.session.status(h.binding))
        issued.close()
        h.session.close()
    }

    @Test fun capturedEpochRejectsWithdrawalsInsideIssuanceAndAfterWorkerAdmission() {
        for (duringIssue in listOf(true, false)) {
            val h = Harness()
            val generation = AtomicLong(11)
            val capturedEpoch = generation.get()
            var issues = 0
            val capability = checkNotNull(CommonsThumbnailPolicy.issueAtGeneration(capturedEpoch, generation::get) {
                issues++
                if (duringIssue) generation.incrementAndGet()
                h.capability(generation, capturedEpoch)
            })
            h.session.request(capability)
            assertEquals(1, issues)
            if (duringIssue) {
                assertTrue(h.workers.isEmpty())
            } else {
                assertEquals(1, h.workers.size)
                generation.incrementAndGet()
            }
            h.drain()
            assertEquals(0, h.reads)
            assertEquals(0, h.published)
            assertEquals(if (duringIssue) DiscoveryThumbnailStatus.NOT_REQUESTED else DiscoveryThumbnailStatus.UNAVAILABLE,
                h.session.status(h.binding))
            assertFalse(h.session.hasActiveRead)
            assertTrue(h.timers.isEmpty())
            capability.close()
            h.session.close()
        }
    }

    private class Harness {
        private val stamp = SessionStamp("owned-generation", 1)
        private val result = VideoSearchResult("owned", "https://owned.invalid/video", null,
            "Owned fixture", "owned", "owned", "https://owned.invalid/image", null, null, null,
            setOf("fixture"), setOf("owned"))
        val binding = DiscoveryThumbnailBinding.of(stamp, 1, result)
        var reads = 0
        var published = 0
        var closes = 0
        val workers = ArrayDeque<Runnable>()
        private val deliveries = ArrayDeque<Runnable>()
        val timers = mutableListOf<Runnable>()
        private val ui = object : DiscoveryThumbnailDispatcher {
            override fun requireCurrentThread() = Unit
            override fun post(task: Runnable) { deliveries.addLast(task) }
            override fun later(task: Runnable, delayMillis: Long) { timers.add(task) }
            override fun remove(task: Runnable) { timers.remove(task) }
        }
        val session = DiscoveryThumbnailSession(DiscoveryThumbnailBudget(1, 1, 4, 1, 1, 100),
            DiscoveryThumbnailLane { workers.addLast(it); true }, ui,
            { fail("Owned epoch fixture unexpectedly stalled") }, { _, _ -> published++ })
        init {
            session.updatePage(stamp, 1, listOf(result), true)
            session.setVisible(setOf(result.resultId))
        }
        fun capability(generation: AtomicLong, capturedEpoch: Long, intentApplied: () -> Boolean = { true }) =
            DiscoveryThumbnailCapability.fromCommonsRegistration(binding,
                sourceCurrent = { generation.get() == capturedEpoch && intentApplied() }) {
                reads++
                DiscoveryThumbnailRead.Released(object : DiscoveryThumbnailFrame {
                    override val width = 1
                    override val height = 1
                    override val allocationBytes = 4L
                    override fun close() { closes++ }
                })
            }
        fun drain() {
            while (workers.isNotEmpty() || deliveries.isNotEmpty()) {
                if (workers.isNotEmpty()) workers.removeFirst().run()
                while (deliveries.isNotEmpty()) deliveries.removeFirst().run()
            }
        }
    }
}
