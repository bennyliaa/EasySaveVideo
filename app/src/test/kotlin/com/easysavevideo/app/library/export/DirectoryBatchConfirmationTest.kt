package com.easysavevideo.app.library.export

import com.easysavevideo.app.library.destination.DirectoryPickerLifecycle
import org.junit.Assert.*
import org.junit.Test

/** Executes the production consume decision within an owner/queue model. Queue rejection,
 * completion and cancellation dispatch are models, bound to runtime by source contracts only.
 * Android executor, lifecycle hosts, Compose and SAF are NOT_RUN here. */
class DirectoryBatchConfirmationTest {
    private class Selection {
        val token = DirectoryBatchConfirmation()
        val lifecycle = DirectoryPickerLifecycle()
        var current = true
    }
    private class Flow {
        var selected: Selection? = null
        var busy = false
        var notice = "idle"
        var proofChecks = 0
        var submissions = 0
        var cancellations = 0
        var rejectSubmission = false
        fun begin(received: Boolean = true): Selection = Selection().also {
            check(!busy)
            selected = it; busy = true; notice = if (received) "confirm" else "picking"
            if (received) it.lifecycle.receive()
        }
        fun cancel() {
            val selection = selected ?: return
            cancellations++; selection.lifecycle.cancel()
            if (!selection.lifecycle.confirmedOnce) { selected = null; busy = false }
            notice = "stopped"
        }
        fun confirm(expected: DirectoryBatchConfirmation) {
            val selection = selected ?: return
            when (consumeDirectoryBatchConfirmation(expected, selection.token, selection.lifecycle) {
                proofChecks++; selection.current
            }) {
                DirectoryConfirmationDecision.IGNORED -> return
                DirectoryConfirmationDecision.STALE -> { cancel(); notice = "stale"; return }
                DirectoryConfirmationDecision.CONFIRMED -> Unit
            }
            notice = "copying"; submissions++
            if (rejectSubmission) { selected = null; busy = false; notice = "queue_full" }
        }
        fun complete(selection: Selection) {
            if (selected !== selection) return
            selected = null; busy = false; notice = "completed"
        }
    }

    @Test fun currentConfirmationIsConsumedOnceBeforeSubmissionAndReplayDoesNotCancelOrChangeNotice() {
        val flow = Flow(); val selected = flow.begin()
        assertEquals(0, flow.submissions)
        flow.confirm(selected.token)
        assertTrue(selected.lifecycle.confirmedOnce); assertTrue(selected.lifecycle.active())
        assertEquals(1, flow.submissions); assertEquals("copying", flow.notice)
        selected.current = false
        repeat(3) { flow.confirm(selected.token) }
        assertEquals(1, flow.submissions); assertEquals(1, flow.proofChecks)
        assertEquals(0, flow.cancellations); assertEquals("copying", flow.notice)
        assertTrue(flow.busy); assertSame(selected, flow.selected); assertTrue(selected.lifecycle.active())
    }

    @Test fun retiredCallbackCannotConfirmOrCancelReopenedSelectionEvenForSameSourceAndFolder() {
        val flow = Flow(); val old = flow.begin(); flow.cancel(); val fresh = flow.begin()
        assertNotSame(old.token, fresh.token)
        flow.confirm(old.token)
        assertSame(fresh, flow.selected); assertTrue(flow.busy); assertEquals("confirm", flow.notice)
        assertEquals(0, flow.submissions); assertEquals(0, flow.proofChecks); assertEquals(1, flow.cancellations)
        flow.confirm(fresh.token)
        assertEquals(1, flow.submissions); assertEquals("copying", flow.notice)
        flow.confirm(old.token)
        assertEquals(1, flow.submissions); assertEquals(1, flow.cancellations); assertEquals("copying", flow.notice)
    }

    @Test fun wrongIdentityIsIgnoredBeforeProofEvenIfNewPendingSelectionIsStale() {
        val flow = Flow(); val selected = flow.begin(); selected.current = false
        flow.confirm(DirectoryBatchConfirmation())
        assertEquals(0, flow.proofChecks); assertEquals(0, flow.cancellations)
        assertSame(selected, flow.selected); assertEquals("confirm", flow.notice)
        flow.confirm(selected.token)
        assertEquals(1, flow.proofChecks); assertEquals(1, flow.cancellations)
        assertNull(flow.selected); assertFalse(flow.busy); assertEquals("stale", flow.notice)
        assertEquals(0, flow.submissions)
    }

    @Test fun currentPendingProofFailureStillStopsAndReportsStaleOnlyOnce() {
        val flow = Flow(); val selected = flow.begin(); selected.current = false
        flow.confirm(selected.token); flow.confirm(selected.token)
        assertEquals(1, flow.cancellations); assertEquals(1, flow.proofChecks)
        assertEquals(0, flow.submissions); assertEquals("stale", flow.notice); assertFalse(flow.busy)
    }

    @Test fun waitingPickerCannotBeConfirmedAndExpectedPauseCarriesNoCopyAuthority() {
        val flow = Flow(); val selected = flow.begin(received = false)
        assertTrue(selected.lifecycle.expectedPickerPause())
        flow.confirm(selected.token)
        assertEquals(0, flow.proofChecks); assertEquals(0, flow.submissions); assertEquals(0, flow.cancellations)
        assertEquals("picking", flow.notice)
        selected.lifecycle.receive()
        assertFalse(selected.lifecycle.expectedPickerPause()); assertEquals(0, flow.submissions)
        flow.confirm(selected.token); assertEquals(1, flow.submissions)
    }

    @Test fun pendingCancellationModelRetiresConsentWithoutReplay() {
        val flow = Flow(); val selected = flow.begin(); flow.cancel()
        flow.confirm(selected.token)
        assertEquals(0, flow.submissions); assertEquals(0, flow.proofChecks)
        assertEquals(1, flow.cancellations); assertFalse(selected.lifecycle.active())
        assertNull(flow.selected); assertFalse(flow.busy); assertEquals("stopped", flow.notice)
    }

    @Test fun rejectedSubmissionModelReleasesOwnershipAndOldTokenCannotBindFreshAttempt() {
        val flow = Flow(); flow.rejectSubmission = true; val rejected = flow.begin()
        flow.confirm(rejected.token)
        assertTrue(rejected.lifecycle.confirmedOnce); assertFalse(flow.busy); assertNull(flow.selected)
        assertEquals("queue_full", flow.notice)
        flow.confirm(rejected.token); assertEquals("queue_full", flow.notice); assertEquals(1, flow.submissions)
        flow.rejectSubmission = false; val fresh = flow.begin()
        flow.confirm(rejected.token)
        assertSame(fresh, flow.selected); assertEquals("confirm", flow.notice); assertEquals(1, flow.submissions)
        flow.confirm(fresh.token); assertEquals(2, flow.submissions); assertEquals(0, flow.cancellations)
    }

    @Test fun completionModelAndRetiredTokensDoNotClearNewOwnershipOrRewriteResults() {
        for (cancelBeforeFinish in listOf(false, true)) {
            val flow = Flow(); val old = flow.begin(); flow.confirm(old.token)
            if (cancelBeforeFinish) flow.cancel()
            flow.complete(old); flow.confirm(old.token)
            assertEquals("completed", flow.notice)
            val fresh = flow.begin(); flow.complete(old); flow.confirm(old.token)
            assertSame(fresh, flow.selected); assertEquals("confirm", flow.notice); assertTrue(flow.busy)
            assertEquals(1, flow.submissions)
        }
    }

    @Test fun identityIsOpaqueAndProofIsNotEvaluatedForConsumedOrCancelledLifecycle() {
        val token = DirectoryBatchConfirmation(); val lifecycle = DirectoryPickerLifecycle()
        assertEquals("DirectoryBatchConfirmation([opaque])", token.toString())
        assertNotEquals(token, DirectoryBatchConfirmation())
        lifecycle.receive(); assertTrue(lifecycle.confirm(true))
        assertEquals(DirectoryConfirmationDecision.IGNORED,
            consumeDirectoryBatchConfirmation(token, token, lifecycle) { error("Replay evaluated proof") })
        lifecycle.cancel()
        assertEquals(DirectoryConfirmationDecision.IGNORED,
            consumeDirectoryBatchConfirmation(token, token, lifecycle) { error("Retired consent evaluated proof") })
    }
}
