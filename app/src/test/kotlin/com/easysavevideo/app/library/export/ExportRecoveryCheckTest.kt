package com.easysavevideo.app.library.export

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

/** Executes the production controller with deterministic worker/main queues, never Android IO. */
class ExportRecoveryCheckTest {
    private data class Reviews(val pending: Long, val unverified: Long)
    private class Rig {
        val worker = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        var runtimeCurrent = true
        var reject = false
        var failAt: String? = null
        var during: (String) -> Unit = {}
        var interrupted = false
        var records = emptyList<ExportRecord>()
        var nextReviews = Reviews(0, 0)
        var lastReviews = Reviews(0, 0)
        var check = ExportRecoveryCheckState()
        var busy = false
        var updates = 0
        var ordinaryNotice: String? = null
        var checkedInterrupted: Boolean? = null
        val controller = ExportRecoveryCheckController(
            enqueue = { work -> if (reject) throw RejectedExecutionException() else worker.add(work) },
            post = { update -> main.add(update) },
            isRuntimeCurrent = { runtimeCurrent },
            stopInterrupted = { stage("stop"); interrupted },
            recoverPending = { stage("recover"); records },
            releaseFinishedGrant = { stage("release:${it.operationId}") },
            readReviews = { stage("reviews"); nextReviews },
            onUpdate = { next, result, ownsBusy ->
                updates++
                check = next
                if (ownsBusy) busy = next.status == ExportRecoveryStatus.CHECKING
                result?.let { lastReviews = it.reviews; checkedInterrupted = it.interrupted }
            },
        )
        private fun stage(name: String) {
            events += name
            during(name)
            if (failAt == name) throw IllegalStateException("synthetic isolated failure")
        }
        fun drainMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }
        fun runPass() { worker.removeFirst().invoke(); drainMain() }
        fun startPass() { assertTrue(controller.start()); drainMain(); runPass() }
    }
    private fun record(id: String = "accepted") = ExportRecord(
        ExportRequest(id, MediaReadRef("item", 1, 1), ExportDestination(ExportKind.MEDIA_STORE, "issued-grant")),
        ExportPhase.FAILED,
    )

    @Test fun unknownHasRetryWithHonestZeroCounts() {
        val rig = Rig()
        assertEquals(ExportRecoveryStatus.UNKNOWN, rig.check.status)
        assertTrue(offerExportRecoveryRetry(rig.check, 0))
        assertEquals(Reviews(0, 0), rig.lastReviews)
    }

    @Test fun startupClaimsBeforeWorkerAndMainPublicationSoRepeatedClicksCannotQueueAgain() {
        val rig = Rig()
        assertTrue(rig.controller.start())
        assertTrue(rig.controller.checking())
        repeat(5) { assertFalse(rig.controller.start()) }
        assertEquals(1, rig.worker.size)
        rig.drainMain()
        assertTrue(rig.busy)
        assertEquals(ExportRecoveryStatus.CHECKING, rig.check.status)
        rig.runPass()
        assertEquals(listOf("stop", "recover", "reviews"), rig.events)
    }

    @Test fun onlyFullOrderedPassPublishesCountsAndCheckedTogether() {
        val rig = Rig()
        rig.records = listOf(record("first"), record("second"))
        rig.nextReviews = Reviews(3, 2)
        rig.interrupted = true
        assertTrue(rig.controller.start()); rig.drainMain()
        rig.worker.removeFirst().invoke()
        assertEquals(ExportRecoveryStatus.CHECKING, rig.check.status)
        assertEquals(Reviews(0, 0), rig.lastReviews)
        assertTrue(rig.controller.checking())
        rig.drainMain()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertEquals(Reviews(3, 2), rig.lastReviews)
        assertEquals(true, rig.checkedInterrupted)
        assertEquals(listOf("stop", "recover", "release:first", "release:second", "reviews"), rig.events)
        assertFalse(rig.busy)
    }

    @Test fun boundedSuccessfulPassKeepsRetryWhenActualUnfinishedCountIsPositive() {
        val rig = Rig(); rig.nextReviews = Reviews(2, 0); rig.startPass()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertTrue(offerExportRecoveryRetry(rig.check, rig.lastReviews.pending))
        assertFalse(offerExportRecoveryRetry(rig.check, 0))
    }

    @Test fun startupFailureAtEveryPortOffersRetryWithoutInventingARecord() {
        for (stage in listOf("stop", "recover", "release:accepted", "reviews")) {
            val rig = Rig(); rig.records = listOf(record()); rig.failAt = stage; rig.startPass()
            assertEquals(stage, ExportRecoveryStatus.FAILED, rig.check.status)
            assertEquals(stage, ExportRecoveryFailure.INCOMPLETE, rig.check.failure)
            assertEquals(stage, Reviews(0, 0), rig.lastReviews)
            assertTrue(stage, offerExportRecoveryRetry(rig.check, 0))
            assertFalse(stage, rig.busy)
        }
    }

    @Test fun emptyRecoveryWithReviewFailureStillFailsAtZero() {
        val rig = Rig(); rig.failAt = "reviews"; rig.startPass()
        assertEquals(listOf("stop", "recover", "reviews"), rig.events)
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertEquals(Reviews(0, 0), rig.lastReviews)
    }

    @Test fun failedPassPreservesPreviouslyVerifiedCountsInsteadOfPartialNewCounts() {
        val rig = Rig(); rig.nextReviews = Reviews(7, 4); rig.startPass()
        rig.nextReviews = Reviews(0, 0); rig.failAt = "reviews"; rig.startPass()
        assertEquals(Reviews(7, 4), rig.lastReviews)
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
    }

    @Test fun rejectedQueueStaysOwnedUntilFailurePublicationThenAllowsExplicitRetry() {
        val rig = Rig(); rig.reject = true
        assertTrue(rig.controller.start()); assertFalse(rig.controller.start())
        rig.drainMain()
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertEquals(ExportRecoveryFailure.QUEUE_BUSY, rig.check.failure)
        assertFalse(rig.busy); assertFalse(rig.controller.checking())
        assertTrue(rig.worker.isEmpty()); assertTrue(rig.events.isEmpty())
        rig.reject = false; rig.startPass()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
    }

    @Test fun rejectedQueueDoesNotResetPreviouslyVerifiedCounts() {
        val rig = Rig(); rig.nextReviews = Reviews(5, 3); rig.startPass()
        rig.reject = true; assertTrue(rig.controller.start()); rig.drainMain()
        assertEquals(Reviews(5, 3), rig.lastReviews)
        assertTrue(offerExportRecoveryRetry(rig.check, rig.lastReviews.pending))
    }

    @Test fun repeatedFailuresNeedExplicitClicksAndPreserveFailureDuringNextAttempt() {
        val rig = Rig(); rig.failAt = "recover"; rig.startPass()
        repeat(3) {
            assertTrue(rig.worker.isEmpty())
            assertTrue(rig.controller.start()); rig.drainMain()
            assertEquals(ExportRecoveryStatus.CHECKING, rig.check.status)
            assertEquals(ExportRecoveryFailure.INCOMPLETE, rig.check.failure)
            assertFalse(rig.controller.start())
            rig.runPass()
            assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        }
        assertEquals(4, rig.events.count { it == "recover" })
    }

    @Test fun onlyLaterFullSuccessClearsFailure() {
        val rig = Rig(); rig.failAt = "reviews"; rig.startPass()
        rig.failAt = null; assertTrue(rig.controller.start()); rig.drainMain()
        rig.worker.removeFirst().invoke()
        assertEquals(ExportRecoveryFailure.INCOMPLETE, rig.check.failure)
        rig.drainMain()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertNull(rig.check.failure)
    }

    @Test fun ordinaryNoticeDismissalAndReplacementDoNotClearRecoveryFailure() {
        val rig = Rig(); rig.failAt = "reviews"; rig.startPass()
        rig.ordinaryNotice = "ordinary result"; rig.ordinaryNotice = null
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        rig.lastReviews = Reviews(0, 0) // Ordinary review refresh cannot complete the recovery pass.
        assertTrue(offerExportRecoveryRetry(rig.check, 0))
        assertEquals(ExportRecoveryFailure.INCOMPLETE, rig.check.failure)
    }

    @Test fun genericOperationFailureRequiresRecoveryWithoutChangingCountsOrQueuingWork() {
        val rig = Rig(); rig.nextReviews = Reviews(6, 1); rig.startPass()
        rig.controller.requireRecovery()
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertEquals(Reviews(6, 1), rig.lastReviews)
        assertTrue(rig.worker.isEmpty())
    }

    @Test fun knownOrdinaryFailureWithReadableZeroCountsDoesNotPretendRecoveryFailed() {
        val rig = Rig(); rig.startPass()
        if (exportRecoveryReviewUnavailable(countsAvailable = true, reviewsAvailable = true)) rig.controller.requireRecovery()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertNull(rig.check.failure)
        assertFalse(offerExportRecoveryRetry(rig.check, 0))
    }

    @Test fun eitherUnavailableReviewReadRequiresRecoveryEvenWithNoKnownRecords() {
        for ((counts, reviews) in listOf(false to true, true to false, false to false)) {
            val rig = Rig()
            if (exportRecoveryReviewUnavailable(counts, reviews)) rig.controller.requireRecovery()
            assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
            assertEquals(Reviews(0, 0), rig.lastReviews)
            assertTrue(offerExportRecoveryRetry(rig.check, 0))
        }
    }

    @Test fun unrelatedBusyOwnerIsNotReleasedByARecoveryRequiredNotice() {
        val rig = Rig(); rig.startPass(); rig.busy = true
        rig.controller.requireRecovery()
        assertTrue(rig.busy)
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertFalse(rig.controller.checking())
    }

    @Test fun failureObservedDuringAnAttemptCannotBeClearedByItsOlderSuccess() {
        val rig = Rig(); assertTrue(rig.controller.start()); rig.drainMain()
        rig.worker.removeFirst().invoke()
        rig.controller.requireRecovery()
        rig.drainMain()
        assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertFalse(rig.busy)
        rig.startPass(); assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
    }

    @Test fun replayedWorkerClosureDoesNotRunRecoveryTwice() {
        val rig = Rig(); assertTrue(rig.controller.start())
        val work = rig.worker.removeFirst(); work(); work(); rig.drainMain()
        assertEquals(1, rig.events.count { it == "recover" })
    }

    @Test fun oldCheckingAndSuccessCallbacksCannotOverwriteNewAttemptOrBusyState() {
        val rig = Rig(); assertTrue(rig.controller.start())
        val oldChecking = rig.main.removeFirst(); oldChecking()
        rig.worker.removeFirst().invoke(); val oldSuccess = rig.main.removeFirst(); oldSuccess()
        assertTrue(rig.controller.start()); rig.drainMain()
        val updates = rig.updates; oldChecking(); oldSuccess()
        assertEquals(updates, rig.updates)
        assertTrue(rig.busy); assertTrue(rig.controller.checking())
        rig.runPass()
    }

    @Test fun oldFailureOrRejectionCannotReleaseNewAttempt() {
        for (reject in listOf(false, true)) {
            val rig = Rig(); rig.reject = reject; rig.failAt = "recover"
            assertTrue(rig.controller.start()); rig.main.removeFirst().invoke()
            if (!reject) rig.worker.removeFirst().invoke()
            val oldFailure = rig.main.removeFirst(); oldFailure()
            rig.reject = false; rig.failAt = null
            assertTrue(rig.controller.start()); rig.drainMain()
            val updates = rig.updates; oldFailure()
            assertEquals(updates, rig.updates); assertTrue(rig.busy)
            rig.runPass(); assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        }
    }

    @Test fun closeBeforeQueuedWorkPreventsAllPortsAndPostedUpdates() {
        val rig = Rig(); assertTrue(rig.controller.start()); rig.controller.close()
        rig.runPass()
        assertTrue(rig.events.isEmpty()); assertEquals(0, rig.updates)
        assertFalse(rig.controller.start())
    }

    @Test fun closeAfterWorkButBeforePostedResultCannotPublish() {
        val rig = Rig(); assertTrue(rig.controller.start()); rig.drainMain()
        rig.worker.removeFirst().invoke(); rig.controller.close(); val updates = rig.updates
        rig.drainMain(); assertEquals(updates, rig.updates)
    }

    @Test fun closeDuringRecoveryAllowsCurrentCallToReturnButStopsFurtherPorts() {
        val rig = Rig(); rig.records = listOf(record()); rig.during = { if (it == "recover") rig.controller.close() }
        assertTrue(rig.controller.start()); rig.runPass()
        assertEquals(listOf("stop", "recover"), rig.events)
        assertEquals(0, rig.updates)
    }

    @Test fun runtimeReplacementRejectsOldWorkAndOldPostsIndependentlyOfNewController() {
        val old = Rig(); assertTrue(old.controller.start()); old.drainMain()
        old.worker.removeFirst().invoke(); old.runtimeCurrent = false
        val current = Rig(); current.nextReviews = Reviews(4, 1); current.startPass()
        val updates = old.updates; old.drainMain()
        assertEquals(updates, old.updates); assertFalse(old.controller.start())
        assertEquals(ExportRecoveryStatus.CHECKED, current.check.status)
        assertEquals(Reviews(4, 1), current.lastReviews)
    }

    @Test fun noCurrentRuntimeMeansNoAdmission() {
        val rig = Rig(); rig.runtimeCurrent = false
        assertFalse(rig.controller.start()); rig.controller.requireRecovery()
        assertTrue(rig.worker.isEmpty()); assertEquals(0, rig.updates)
    }

    @Test fun pageLeaveDisposesStaleClickButAlreadyAcceptedRecoveryFinishesWithoutAnotherPass() {
        val rig = Rig(); var foreground = true
        val action = ExportRecoveryRetryAction({ foreground }) { rig.controller.start() }
        action.invoke(); foreground = false; action.close(); action.invoke()
        rig.runPass()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertTrue(rig.worker.isEmpty())
        foreground = true; action.invoke()
        assertTrue(rig.worker.isEmpty())
    }

    @Test fun currentForegroundAndRuntimeAreCheckedAtClickTime() {
        val rig = Rig(); var foreground = false; var sameRuntime = true
        val action = ExportRecoveryRetryAction({ foreground && sameRuntime }) { rig.controller.start() }
        action.invoke(); assertTrue(rig.worker.isEmpty())
        foreground = true; sameRuntime = false
        action.invoke(); assertTrue(rig.worker.isEmpty())
        sameRuntime = true; action.invoke(); action.invoke()
        assertEquals(1, rig.worker.size)
    }

    @Test fun removedRetryButtonCannotReplayAfterZeroCountSuccessButPendingRowsRemainEligible() {
        val rig = Rig()
        val action = ExportRecoveryRetryAction({ true }) {
            if (offerExportRecoveryRetry(rig.check, rig.lastReviews.pending) && !rig.busy) rig.controller.start()
        }
        action.invoke(); rig.runPass(); action.invoke()
        assertTrue(rig.worker.isEmpty())
        rig.lastReviews = Reviews(1, 0)
        action.invoke(); assertEquals(1, rig.worker.size)
    }

    @Test fun backgroundDoesNotCancelAcceptedWorkOrReplayItOnForegroundReturn() {
        val rig = Rig(); var foreground = true
        val action = ExportRecoveryRetryAction({ foreground }) { rig.controller.start() }
        action.invoke(); foreground = false; action.invoke(); rig.runPass()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        foreground = true
        assertTrue(rig.worker.isEmpty()); assertEquals(1, rig.events.count { it == "recover" })
    }
}
