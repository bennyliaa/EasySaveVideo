package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.SessionStamp
import com.easysavevideo.discovery.VideoSearchResult
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class DiscoverySaveOriginHandleTest {
    private class Fixture {
        val tracker = DiscoveryOriginTracker({ 1_000 })
        val stamp = SessionStamp("session", 1)
        val target = DiscoveryControlTarget("profile", stamp, 0, 1)
        val result = VideoSearchResult("fingerprint", "https://example.org/video", null, "source", null, null,
            null, null, null, null, setOf("source"), setOf("fingerprint"))
        val page = PageSnapshot(stamp, 0, 0, listOf(result.resultId), 20)
        val browser = DiscoveryOriginBrowserState(BrowserContextRef("tab", "browser-session"), 1, 1)
        val token = requireNotNull(tracker.open(target, page, result, browser))
        val proof = requireNotNull(tracker.selectForSave(token, browser))
        fun handle(dispatch: DiscoverySaveOriginCompletion) = DiscoverySaveOriginHandle(tracker, proof, dispatch)
        fun assertAbandoned() {
            assertFalse(tracker.bindTask(proof, "task"))
            val outcome = tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, target) {
                fail("Abandoned proof must not write"); DiscoveryOriginHistoryOutcome.RECORDED
            }
            assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, outcome.history)
        }
    }

    @Test fun committedOutcomeIsPreservedAcrossRejectedOrThrowingDispatch() {
        listOf(false, true).forEach { rejected ->
            val f = Fixture()
            var dispatches = 0
            val handle = f.handle { _, _, _, _, _ ->
                dispatches++
                if (rejected) throw RejectedExecutionException()
                throw IllegalStateException("private dispatcher error")
            }
            assertTrue(handle.bindTask("task"))
            val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
            handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
            assertEquals(listOf(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED,
                DiscoveryOriginHistoryOutcome.HISTORY_FAILED)), outcomes)
            f.assertAbandoned()
            handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
            assertEquals(1, dispatches)
            assertEquals(1, outcomes.size)
        }
    }

    @Test fun callbackThenDispatchExceptionNeverProducesASecondOrReplacementOutcome() {
        val f = Fixture()
        val expected = DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.RECORDED)
        val handle = f.handle { _, _, _, _, callback ->
            callback(expected)
            throw IllegalStateException("late dispatcher error")
        }
        assertTrue(handle.bindTask("task"))
        val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        assertEquals(listOf(expected), outcomes)
        f.assertAbandoned()
    }

    @Test fun duplicateAsynchronousDispatchCallbacksAreDeliveredOnlyOnce() {
        val f = Fixture()
        lateinit var asynchronous: (DiscoveryOriginSaveOutcome) -> Unit
        val handle = f.handle { _, _, _, _, callback -> asynchronous = callback }
        assertTrue(handle.bindTask("task"))
        val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        assertTrue(outcomes.isEmpty())
        val expected = DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.RECORDED)
        asynchronous(expected)
        asynchronous(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.NOT_COMMITTED,
            DiscoveryOriginHistoryOutcome.HISTORY_FAILED))
        assertEquals(listOf(expected), outcomes)
        handle.abandon()
    }

    @Test fun callbackReentryAndCallbackExceptionCannotDispatchOrDeliverTwice() {
        val f = Fixture()
        val expected = DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.RECORDED)
        val dispatches = AtomicInteger()
        val outcomes = AtomicInteger()
        val handle = f.handle { _, _, _, _, callback -> dispatches.incrementAndGet(); callback(expected) }
        assertTrue(handle.bindTask("task"))
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) { outcome ->
            outcomes.incrementAndGet()
            assertEquals(expected, outcome)
            handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) { fail("Reentry must not deliver") }
            throw IllegalStateException("listener error")
        }
        assertEquals(1, dispatches.get())
        assertEquals(1, outcomes.get())
        f.assertAbandoned()
    }

    @Test fun abandonPreventsBindingDispatchAndLateDuplicateCompletion() {
        val f = Fixture()
        val handle = f.handle { _, _, _, _, _ -> fail("Abandoned handle cannot dispatch") }
        handle.abandon()
        handle.abandon()
        assertFalse(handle.bindTask("task"))
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) { fail("Abandoned handle cannot deliver") }
        f.assertAbandoned()
    }

    @Test fun successfulDispatchPreservesTaskOwnerAndUsesTheRealSingleUseGate() {
        val f = Fixture()
        var writes = 0
        var dispatches = 0
        val handle = f.handle { tracker, proof, task, media, callback ->
            dispatches++
            assertSame(f.tracker, tracker)
            assertSame(f.proof, proof)
            assertEquals("task", task)
            callback(tracker.completeSave(proof, task, media, f.target, DiscoveryOriginMediaIdentityMatch.VERIFIED) { event ->
                assertTrue(event.isCurrent())
                writes++
                DiscoveryOriginHistoryOutcome.RECORDED
            })
        }
        assertTrue(handle.bindTask("task"))
        assertFalse(handle.bindTask("replacement"))
        val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        assertEquals(1, dispatches)
        assertEquals(1, writes)
        assertEquals(listOf(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED,
            DiscoveryOriginHistoryOutcome.RECORDED)), outcomes)
        assertFalse(handle.bindTask("task"))
    }

    @Test fun applicationStyleDispatchWithoutIdentityProofPreservesMediaAndNeverRecordsSaved() {
        val f = Fixture()
        var writes = 0
        val handle = f.handle { tracker, proof, task, media, callback ->
            callback(tracker.completeSave(proof, task, media, f.target) {
                writes++
                DiscoveryOriginHistoryOutcome.RECORDED
            })
        }
        assertTrue(handle.bindTask("task"))
        val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED, outcomes::add)
        assertEquals(0, writes)
        assertEquals(listOf(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED,
            DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED)), outcomes)
        f.assertAbandoned()
    }

    @Test fun failureCompletionRemainsNotCommittedWhenHistoryDispatchFails() {
        val f = Fixture()
        val handle = f.handle { _, _, _, _, _ -> throw RejectedExecutionException() }
        assertTrue(handle.bindTask("task"))
        val outcomes = mutableListOf<DiscoveryOriginSaveOutcome>()
        handle.complete("task", DiscoveryOriginMediaOutcome.NOT_COMMITTED, outcomes::add)
        assertEquals(listOf(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.NOT_COMMITTED,
            DiscoveryOriginHistoryOutcome.HISTORY_FAILED)), outcomes)
        f.assertAbandoned()
    }

    @Test fun handleDiagnosticsDoNotExposeCapabilityOrTaskData() {
        val f = Fixture()
        val handle = f.handle { _, _, _, _, _ -> }
        assertEquals("DiscoverySaveOriginHandle([redacted])", handle.toString())
        handle.abandon()
    }
}
