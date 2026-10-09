package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.SeenEvent
import com.easysavevideo.discovery.SeenRecord
import com.easysavevideo.discovery.SessionStamp
import com.easysavevideo.discovery.VideoSearchResult
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DiscoveryOriginTrackerTest {
    private class Fixture(ttl: Long = 100, capacity: Int = 12) {
        val now = AtomicLong(1_000)
        val tracker = DiscoveryOriginTracker(now::get, ttl, capacity)
        val stamp = SessionStamp("discovery-session", 7)
        val target = DiscoveryControlTarget("profile-one", stamp, 4, 2)
        val result = VideoSearchResult("fingerprint", "https://example.org/private-page", "private title", "source",
            "source", "video-one", null, null, null, null, setOf("provider"), setOf("fingerprint", "alias"))
        val page = PageSnapshot(stamp, 4, 0, listOf(result.resultId), 20)
        val browser = DiscoveryOriginBrowserState(BrowserContextRef("tab", "browser-session"), 3, 9)
        fun open(browser: DiscoveryOriginBrowserState = this.browser) = requireNotNull(tracker.open(target, page, result, browser))
        fun selected(token: DiscoveryOriginToken = open(), browser: DiscoveryOriginBrowserState = this.browser) =
            requireNotNull(tracker.selectForSave(token, browser))
        fun bound(): DiscoveryOriginSaveProof = selected().also { assertTrue(tracker.bindTask(it, "task")) }
        // A pure trusted-host test seam only. Android has no identity bridge and always uses UNKNOWN.
        fun complete(proof: DiscoveryOriginSaveProof, task: String = "task", current: DiscoveryControlTarget? = target,
            media: DiscoveryOriginMediaOutcome = DiscoveryOriginMediaOutcome.COMMITTED,
            record: (DiscoveryOriginEventProof) -> DiscoveryOriginHistoryOutcome = { DiscoveryOriginHistoryOutcome.RECORDED }) =
            tracker.completeSave(proof, task, media, current, DiscoveryOriginMediaIdentityMatch.VERIFIED, record)
        fun assertSkipped(proof: DiscoveryOriginSaveProof, current: DiscoveryControlTarget? = target) {
            assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE,
                complete(proof, current = current) { fail("Stale origin must never write"); DiscoveryOriginHistoryOutcome.RECORDED }.history)
        }
    }

    @Test fun verifiedCommitRecordsOnlySavedWithoutInferringPlaybackOrOtherEvents() {
        val f = Fixture()
        val proof = f.bound()
        var history = SeenRecord(f.target.profileId, f.result.fingerprint, 10)
        val outcome = f.complete(proof) { event ->
            assertEquals(f.target, event.target)
            assertEquals(f.page, event.page)
            assertEquals(f.result, event.result)
            assertEquals(SeenEvent.SAVED, event.event)
            assertTrue(event.isCurrent())
            history = history.record(event.event, 20)
            DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, outcome.media)
        assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, outcome.history)
        assertEquals(20L, history.savedAt)
        assertNull(history.shownAt)
        assertNull(history.openedAt)
        assertNull(history.playedAt)
        assertNull(history.dismissedAt)
    }

    @Test fun committedPageOriginWithoutVerifiedMediaIdentityNeverWritesSaved() {
        listOf(false, true).forEach { explicitlyUnknown ->
            val f = Fixture()
            val proof = f.bound()
            val history = SeenRecord(f.target.profileId, f.result.fingerprint, 10, openedAt = 12)
            val record: (DiscoveryOriginEventProof) -> DiscoveryOriginHistoryOutcome = {
                fail("Page origin and committed media cannot prove discovery video identity")
                DiscoveryOriginHistoryOutcome.RECORDED
            }
            val outcome = if (explicitlyUnknown) f.tracker.completeSave(proof, "task",
                DiscoveryOriginMediaOutcome.COMMITTED, f.target, DiscoveryOriginMediaIdentityMatch.UNKNOWN, record)
            else f.tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, f.target, record = record)
            assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, outcome.media)
            assertEquals(DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED, outcome.history)
            assertNull(history.savedAt)
            assertNull(history.playedAt)
            assertEquals(12L, history.openedAt)
            // The unverified terminal callback has already consumed this capability.
            f.assertSkipped(proof)
        }
    }

    @Test fun staleOriginIsRejectedBeforeMediaIdentityAndCannotBeRevivedByLaterVerification() {
        val f = Fixture()
        val proof = f.bound()
        val outcome = f.tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED,
            f.target.copy(viewRevision = f.target.viewRevision + 1)) {
            fail("Stale origin cannot record history"); DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, outcome.history)
        f.assertSkipped(proof)
    }

    @Test fun savedAndShownOpenedPlayedColumnsRemainIndependentAndMonotonic() {
        val f = Fixture()
        var history = SeenRecord(f.target.profileId, f.result.fingerprint, 10,
            shownAt = 12, openedAt = 13, playedAt = null, savedAt = 80, dismissedAt = 14)
        f.complete(f.bound()) { event ->
            history = history.record(event.event, 20)
            DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertEquals(12L, history.shownAt)
        assertEquals(13L, history.openedAt)
        assertNull(history.playedAt)
        assertEquals(80L, history.savedAt)
        assertEquals(14L, history.dismissedAt)
    }

    @Test fun unsuccessfulMediaNeverInvokesHistoryAndConsumesMatchingTask() {
        val f = Fixture()
        val proof = f.bound()
        val result = f.complete(proof, media = DiscoveryOriginMediaOutcome.NOT_COMMITTED) {
            fail("Failed/cancelled media cannot record SAVED"); DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertEquals(DiscoveryOriginMediaOutcome.NOT_COMMITTED, result.media)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, result.history)
        f.assertSkipped(proof)
    }

    @Test fun failedHistoryNeverRewritesCommittedMediaAndNeverRetries() {
        listOf(false, true).forEach { throwing ->
            val f = Fixture()
            val proof = f.bound()
            var writes = 0
            val result = f.complete(proof) {
                writes++
                if (throwing) throw IllegalStateException("private history failure")
                DiscoveryOriginHistoryOutcome.HISTORY_FAILED
            }
            assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, result.media)
            assertEquals(DiscoveryOriginHistoryOutcome.HISTORY_FAILED, result.history)
            f.assertSkipped(proof)
            assertEquals(1, writes)
        }
    }

    @Test fun staleHistoryOutcomeRemainsSeparateFromCommittedMedia() {
        val f = Fixture()
        val proof = f.bound()
        val result = f.complete(proof) { DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE }
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, result.media)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, result.history)
        f.assertSkipped(proof)
    }

    @Test fun tokenAndTaskBindingAreEachSingleUse() {
        val f = Fixture()
        val token = f.open()
        val proof = f.selected(token)
        assertNull(f.tracker.selectForSave(token, f.browser))
        assertTrue(f.tracker.bindTask(proof, "task"))
        assertFalse(f.tracker.bindTask(proof, "task"))
        assertFalse(f.tracker.bindTask(proof, "another-task"))
        assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, f.complete(proof).history)
        f.assertSkipped(proof)
    }

    @Test fun unrelatedOrUnboundCallbacksCannotWriteOrReplaceTaskBinding() {
        val f = Fixture()
        val proof = f.selected()
        f.assertSkipped(proof)
        assertFalse(f.tracker.bindTask(proof, ""))
        assertFalse(f.tracker.bindTask(proof, "x".repeat(129)))
        assertTrue(f.tracker.bindTask(proof, "task"))
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, f.complete(proof, task = "unrelated") {
            fail("Unrelated task must not write"); DiscoveryOriginHistoryOutcome.RECORDED
        }.history)
        assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, f.complete(proof).history)
    }

    @Test fun everyBrowserBoundaryMismatchInvalidatesTheOldTokenPermanently() {
        val base = Fixture().browser
        val mismatches = listOf(base.copy(context = BrowserContextRef("other-tab", base.context.sessionId)),
            base.copy(context = BrowserContextRef(base.context.tabId, "other-session")),
            base.copy(epoch = base.epoch + 1), base.copy(navigationGeneration = base.navigationGeneration + 1),
            base.copy(incognito = true), base.copy(open = false))
        mismatches.forEach { changed ->
            val f = Fixture()
            val token = f.open()
            assertNull(f.tracker.selectForSave(token, changed))
            assertNull(f.tracker.selectForSave(token, f.browser))
        }
    }

    @Test fun incognitoClosedAndUnstartedBrowsersNeverAcquireOrigin() {
        val f = Fixture()
        listOf(f.browser.copy(incognito = true), f.browser.copy(open = false), f.browser.copy(epoch = 0),
            f.browser.copy(navigationGeneration = -1)).forEach { browser ->
            assertNull(f.tracker.open(f.target, f.page, f.result, browser))
        }
    }

    @Test fun mismatchedPageAndResultCannotAcquireOrigin() {
        val f = Fixture()
        assertNull(f.tracker.open(f.target, f.page.copy(stamp = f.stamp.copy(epoch = 8)), f.result, f.browser))
        assertNull(f.tracker.open(f.target, f.page.copy(revision = 5), f.result, f.browser))
        assertNull(f.tracker.open(f.target, f.page.copy(orderedResultIds = listOf("another")), f.result, f.browser))
    }

    @Test fun profileSessionRevisionAndControllerGenerationAreAllFinalFences() {
        val base = Fixture().target
        val mismatches = listOf(null, base.copy(profileId = "other-profile"),
            base.copy(stamp = SessionStamp("other-session", base.stamp.epoch)),
            base.copy(stamp = base.stamp.copy(epoch = base.stamp.epoch + 1)),
            base.copy(viewRevision = base.viewRevision + 1),
            base.copy(controllerGeneration = base.controllerGeneration + 1))
        mismatches.forEach { current ->
            val f = Fixture()
            val proof = f.bound()
            f.assertSkipped(proof, current)
            // A clear/reset/reinitialization cannot be undone by a late old-current snapshot.
            f.assertSkipped(proof, f.target)
        }
    }

    @Test fun originExpiresAtBoundaryAndSelectionOrBindingNeverRenewsIt() {
        val f = Fixture()
        val token = f.open()
        f.now.set(1_090)
        val proof = f.selected(token)
        f.now.set(1_099)
        assertTrue(f.tracker.bindTask(proof, "task"))
        f.now.set(1_100)
        f.assertSkipped(proof)
        assertNull(f.tracker.selectForSave(token, f.browser))
    }

    @Test fun expiryBeforeSelectionOrBindingAlsoRejects() {
        val f = Fixture()
        val token = f.open()
        f.now.set(1_100)
        assertNull(f.tracker.selectForSave(token, f.browser))
        val proof = f.selected()
        f.now.set(1_200)
        assertFalse(f.tracker.bindTask(proof, "task"))
    }

    @Test fun existingLifecycleTickCanReleaseExpiredPayloadsWithoutAnOperationOrNewTimer() {
        val f = Fixture()
        val proof = f.bound()
        assertEquals(0, f.tracker.pruneExpired())
        f.now.set(1_100)
        assertEquals(1, f.tracker.pruneExpired())
        assertEquals(0, f.tracker.pruneExpired())
        f.assertSkipped(proof)
    }

    @Test fun expiryBetweenCallbackAndStorageCommitIsVisibleToFinalGuard() {
        val f = Fixture()
        val captured = AtomicReference<DiscoveryOriginEventProof>()
        val outcome = f.complete(f.bound()) { event ->
            captured.set(event)
            assertTrue(event.isCurrent())
            f.now.set(1_100)
            assertFalse(event.isCurrent())
            DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
        }
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, outcome.media)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, outcome.history)
        assertFalse(captured.get().isCurrent())
    }

    @Test fun callbackProofExpiresImmediatelyAfterItsOnlyInvocation() {
        val f = Fixture()
        lateinit var captured: DiscoveryOriginEventProof
        val proof = f.bound()
        f.complete(proof) { event ->
            captured = event
            assertTrue(event.isCurrent())
            f.assertSkipped(proof) // Reentrant duplicate cannot perform a second write.
            DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertFalse(captured.isCurrent())
        f.assertSkipped(proof)
    }

    @Test fun redirectNavigationCloseAbandonAndOwnerResetRejectLateCompletions() {
        val invalidations: List<(Fixture, DiscoveryOriginSaveProof) -> Unit> = listOf(
            { f, _ -> f.tracker.invalidate(f.browser.context) },
            { f, _ -> f.tracker.invalidateAll() },
            { f, proof -> f.tracker.abandon(proof) },
            { f, _ -> f.tracker.close() },
            { f, _ -> f.open(f.browser.copy(navigationGeneration = f.browser.navigationGeneration + 1)) },
        )
        invalidations.forEach { invalidate ->
            val f = Fixture()
            val proof = f.bound()
            invalidate(f, proof)
            f.assertSkipped(proof)
        }
    }

    @Test fun reentrantInvalidationRevokesFinalCommitGuard() {
        val f = Fixture()
        val result = f.complete(f.bound()) { event ->
            f.tracker.invalidate(f.browser.context)
            assertFalse(event.isCurrent())
            DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
        }
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, result.history)
    }

    @Test fun restartOrAnotherOwnerCannotConsumeInMemoryCapabilities() {
        val f = Fixture()
        val token = f.open()
        val proof = f.selected(token)
        assertTrue(f.tracker.bindTask(proof, "task"))
        val restarted = DiscoveryOriginTracker(f.now::get)
        assertNull(restarted.selectForSave(token, f.browser))
        assertFalse(restarted.bindTask(proof, "task"))
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE,
            restarted.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, f.target) {
                fail("Restart cannot restore a correlation"); DiscoveryOriginHistoryOutcome.RECORDED
            }.history)
    }

    @Test fun closedOwnerCannotOpenNewOrigins() {
        val f = Fixture()
        f.tracker.close()
        assertNull(f.tracker.open(f.target, f.page, f.result, f.browser))
    }

    @Test fun boundedCapacityEvictsOldestCapabilityWithoutReusingIt() {
        val f = Fixture(capacity = 1)
        val proof = f.bound()
        f.open(f.browser.copy(context = BrowserContextRef("another-tab", "browser-session")))
        f.assertSkipped(proof)
    }

    @Test fun clockRollbackCannotReviveAnOriginAndDeadlineOverflowFailsClosed() {
        val f = Fixture()
        val proof = f.bound()
        f.now.set(999)
        f.assertSkipped(proof)
        f.now.set(1_001)
        f.assertSkipped(proof)
        f.now.set(Long.MAX_VALUE - 99)
        assertNull(f.tracker.open(f.target, f.page, f.result, f.browser))
    }

    @Test fun navigationThatWinsTheLockPreventsRacingCompletion() {
        val f = Fixture()
        val proof = f.bound()
        val executor = Executors.newFixedThreadPool(2)
        val invalidated = CountDownLatch(1)
        try {
            val navigation = executor.submit { f.tracker.invalidateAll(); invalidated.countDown() }
            val callback = executor.submit<DiscoveryOriginSaveOutcome> {
                assertTrue(invalidated.await(5, TimeUnit.SECONDS))
                f.complete(proof) { fail("Earlier navigation must fence the write"); DiscoveryOriginHistoryOutcome.RECORDED }
            }
            navigation.get(5, TimeUnit.SECONDS)
            assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, callback.get(5, TimeUnit.SECONDS).history)
        } finally { executor.shutdownNow() }
    }

    @Test fun completionAndNavigationUseOneLockWithoutAValidationWriteGap() {
        val f = Fixture()
        val proof = f.bound()
        val executor = Executors.newFixedThreadPool(2)
        val callbackEntered = CountDownLatch(1)
        val invalidateStarted = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        try {
            val callback = executor.submit<DiscoveryOriginSaveOutcome> {
                f.complete(proof) { event ->
                    callbackEntered.countDown()
                    assertTrue(invalidateStarted.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, invalidated.count)
                    assertTrue(event.isCurrent())
                    DiscoveryOriginHistoryOutcome.RECORDED
                }
            }
            val navigation = executor.submit {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                invalidateStarted.countDown()
                f.tracker.invalidateAll()
                invalidated.countDown()
            }
            assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, callback.get(5, TimeUnit.SECONDS).history)
            navigation.get(5, TimeUnit.SECONDS)
            assertEquals(0L, invalidated.count)
            f.assertSkipped(proof)
        } finally { executor.shutdownNow() }
    }

    @Test fun simultaneousDuplicateCallbacksPerformExactlyOneHistoryWrite() {
        val f = Fixture()
        val proof = f.bound()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val writes = AtomicInteger()
        try {
            val futures = (1..2).map {
                executor.submit<DiscoveryOriginSaveOutcome> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    f.complete(proof) { writes.incrementAndGet(); DiscoveryOriginHistoryOutcome.RECORDED }
                }
            }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS).history }
            assertEquals(1, writes.get())
            assertEquals(1, results.count { it == DiscoveryOriginHistoryOutcome.RECORDED })
            assertEquals(1, results.count { it == DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE })
        } finally { executor.shutdownNow() }
    }

    @Test fun capabilityDiagnosticsNeverContainPayloadOrNativeIdentifiers() {
        val f = Fixture()
        val token = f.open()
        val proof = f.selected(token)
        assertTrue(f.tracker.bindTask(proof, "task"))
        assertEquals("DiscoveryOriginBrowserState([redacted])", f.browser.toString())
        assertEquals("DiscoveryOriginToken([redacted])", token.toString())
        assertEquals("DiscoveryOriginSaveProof([redacted])", proof.toString())
        f.complete(proof) {
            assertEquals("DiscoveryOriginEventProof([redacted])", it.toString())
            DiscoveryOriginHistoryOutcome.RECORDED
        }
    }
}
