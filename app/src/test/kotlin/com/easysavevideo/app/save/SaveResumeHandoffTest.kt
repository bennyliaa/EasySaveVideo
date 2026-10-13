package com.easysavevideo.app.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.*
import com.easysavevideo.save.*
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

/**
 * Calls the production admission/repair helper, command gate, reservation and coordinator.
 * Only queues, persistence and media ports are fakes. No copy of the Android launch/repair
 * algorithm is used here; Android lifecycle/Compose wiring is covered by source contracts.
 */
class SaveResumeHandoffTest {
    @Test fun baselinePostResumeReadFailureLeavesGenuineRunningRowWithoutAcceptedWorker() {
        val f = Fixture()
        val launch = f.begin { f.journal.failedReads = 1 }
        assertEquals(launch.waiting.task.revision + 1, launch.running.task.revision)
        assertEquals(1, f.journal.resumeCommits)
        assertThrows(IllegalStateException::class.java) { f.coordinator.record("task") }
        // This is the original failing preflight boundary, without the new handoff controller.
        assertEquals(launch.running, f.journal.row())
        assertEquals(TaskState.RUNNING, f.journal.row().task.state)
        assertEquals(0, f.workerSubmissions)
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
        launch.reservation.release()
        f.gate.release(launch.attempt)
    }

    @Test fun exactPostResumeReadFaultIsCompensatedWithoutWorkerOrIdentityChange() {
        val f = Fixture()
        val launch = f.begin { f.journal.failedReads = 1 }
        assertFalse(f.launch(launch))
        val repaired = f.journal.row()
        f.assertRepair(launch.running, repaired, SaveWaitReason.RECOVERY)
        assertEquals(SaveError.RECOVERY_REQUIRED, repaired.error)
        assertEquals(ResumeAction.REVIEW_RECOVERY, repaired.task.waitingUser!!.resumeAction)
        assertEquals(1, f.journal.resumeCommits)
        assertEquals(1, f.journal.repairCommits)
        assertEquals(0, f.workerSubmissions)
        assertEquals(1, f.reservationReleases)
        assertTrue(f.liveLeases.isEmpty())
        assertTrue(f.handoff.pending().isEmpty())
        f.runMain()
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun standardWorkerRejectionCreatesOnlyTheOwnedRecoveryWait() {
        val f = Fixture()
        val launch = f.begin()
        assertFalse(f.launch(launch) { throw RejectedExecutionException() })
        f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.RECOVERY)
        assertEquals(1, f.unacceptedReleases)
        assertEquals(1, f.reservationReleases)
        assertTrue(f.workers.isEmpty())
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun backgroundAndRetiredCommandTakePriorityOverNetworkDeferral() {
        for (retiredOnly in listOf(false, true)) {
            val f = Fixture()
            val launch = f.begin()
            f.networkAllowed = false
            if (retiredOnly) f.gate.retireResumesForBackground() else f.foreground = false
            assertFalse(f.launch(launch))
            f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.BACKGROUND)
            assertNull(f.journal.row().error)
            assertFalse(launch.operation.cancelToken.isCancelled)
            assertEquals(0, f.networkChecks)
            assertEquals(0, f.workerSubmissions)
            f.assertNoMediaWork()
        }
    }

    @Test fun networkDeferralPreservesWifiRequiredAndRetryAction() {
        val f = Fixture()
        val launch = f.begin()
        f.networkAllowed = false
        assertFalse(f.launch(launch))
        f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.NETWORK)
        assertEquals(SaveError.WIFI_REQUIRED, f.journal.row().error)
        assertEquals(ResumeAction.RETRY, f.journal.row().task.waitingUser!!.resumeAction)
        assertTrue("The current network policy denial must be consulted", f.networkChecks > 0)
        assertEquals(0, f.workerSubmissions)
        assertEquals(0, f.workerEntries)
        assertTrue(f.workers.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun lifecycleRetirementRacingPreflightReadFailureKeepsBackgroundPriority() {
        val f = Fixture()
        val launch = f.begin()
        f.journal.beforeNextRead = {
            f.foreground = false
            f.gate.retireResumesForBackground()
            f.coordinator.signalBackgroundPauseAll()
            f.foreground = true
        }
        f.journal.failedReads = 1
        assertFalse(f.launch(launch))
        f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.BACKGROUND)
        assertFalse(launch.operation.cancelToken.isCancelled)
        assertEquals(0, f.workerSubmissions)
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun networkLossAtReadFailureOrWorkerRejectionGetsNetworkPriority() {
        for (duringRead in listOf(false, true)) {
            val f = Fixture()
            val launch = f.begin()
            if (duringRead) {
                f.journal.beforeNextRead = { f.networkAllowed = false }
                f.journal.failedReads = 1
                assertFalse(f.launch(launch))
            } else assertFalse(f.launch(launch) {
                f.networkAllowed = false
                throw RejectedExecutionException()
            })
            f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.NETWORK)
            assertEquals(SaveError.WIFI_REQUIRED, f.journal.row().error)
            assertTrue(f.handoff.pending().isEmpty())
            f.assertNoMediaWork()
        }
    }

    @Test fun everyUnacceptedCauseRetainsExactPendingAuthorityAcrossFirstReadAndCasFailures() {
        for (cause in Cause.entries) for (fault in RepairFault.entries.filter { it != RepairFault.NONE }) {
            val f = Fixture()
            val launch = f.begin()
            f.armCause(cause)
            f.afterUnacceptedRelease = { f.journal.arm(fault) }
            assertFalse(f.launchForCause(launch, cause))
            val ticket = f.pending()
            assertSame(launch.running, ticket.expected)
            assertSame(launch.operation, ticket.operation)
            assertEquals(cause.reason, ticket.reason)
            assertEquals(if (fault == RepairFault.COMMIT_THEN_THROW) TaskState.WAITING_USER else TaskState.RUNNING,
                f.journal.row().task.state)
            assertEquals(if (fault == RepairFault.READ) 0 else 1, f.journal.repairAttempts)
            assertEquals(if (fault == RepairFault.COMMIT_THEN_THROW) 1 else 0, f.journal.repairCommits)
            assertEquals(1, f.reservationReleases)
            assertTrue(f.liveLeases.isEmpty())
            f.runMain()
            assertSame(ticket, f.visiblePending.single())
            assertTrue(f.gate.pendingTaskIds().isEmpty())
            f.foreground = true // Recovery is a later explicit foreground action.
            val beforeReads = f.journal.reads
            val beforeWrites = f.journal.repairAttempts
            assertTrue(f.retry(ticket))
            f.runControls()
            assertEquals(beforeReads + 1, f.journal.reads)
            assertEquals(beforeWrites + if (fault == RepairFault.COMMIT_THEN_THROW) 0 else 1,
                f.journal.repairAttempts)
            f.assertRepair(launch.running, f.journal.row(), cause.reason)
            assertTrue(f.handoff.pending().isEmpty())
            f.runMain()
            assertTrue(f.gate.pendingTaskIds().isEmpty())
            f.assertNoMediaWork()
        }
    }

    @Test fun pollingSnapshotsAndForegroundChangesNeverRetryPendingRepair() {
        val f = Fixture()
        val ticket = f.makePending()
        val before = f.journal.row()
        val writes = f.journal.repairAttempts
        repeat(5) {
            assertEquals(before, f.coordinator.record("task"))
            assertEquals(listOf(before), f.journal.listRecent(100))
            f.handoff.observeTerminal(listOf(before))
            f.foreground = false
            f.gate.retireResumesForBackground()
            f.coordinator.signalBackgroundPauseAll()
            f.foreground = true
            assertSame(ticket, f.handoff.pending().single())
        }
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(writes, f.journal.repairAttempts)
        assertEquals(before, f.journal.row())
        assertSame(ticket, f.visiblePending.single())
        f.assertNoMediaWork()
    }

    @Test fun eachExplicitRetryWhileReadUnavailableMakesOneReadAndReleasesItsClickGate() {
        val f = Fixture()
        val ticket = f.makePending()
        val leases = f.leasesCreated
        val reservationReleases = f.reservationReleases
        repeat(3) {
            f.journal.failedReads = 1
            val reads = f.journal.reads
            val writes = f.journal.repairAttempts
            assertTrue(f.retry(ticket))
            assertEquals(setOf("task"), f.gate.pendingTaskIds())
            f.runControls()
            assertEquals(reads + 1, f.journal.reads)
            assertEquals(writes, f.journal.repairAttempts)
            assertSame(ticket, f.pending())
            f.runMain()
            assertTrue(f.gate.pendingTaskIds().isEmpty())
        }
        assertEquals(leases, f.leasesCreated)
        assertEquals(reservationReleases, f.reservationReleases)
        assertNull(f.globalOwner)
        assertTrue(f.liveLeases.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun rejectedOrThrowingRetryCasReusesExactProposalWithoutExtendingWait() {
        for (fault in listOf(RepairFault.REJECT, RepairFault.THROW_BEFORE_COMMIT)) {
            val f = Fixture()
            val ticket = f.makePending()
            f.journal.arm(fault)
            val reads = f.journal.reads
            assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
            assertEquals(reads + 1, f.journal.reads)
            assertEquals(1, f.journal.repairAttempts)
            assertEquals(0, f.journal.repairCommits)
            val proposed = ticket.expectedWaiting!!
            assertEquals(TaskState.RUNNING, f.journal.row().task.state)
            f.now += 1_000
            assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
            assertEquals(proposed, f.journal.row())
            assertEquals(2, f.journal.repairAttempts)
            assertEquals(1, f.journal.repairCommits)
            assertTrue(f.handoff.pending().isEmpty())
            f.assertNoMediaWork()
        }
    }

    @Test fun commitThenThrowIsReconciledByOneReadAndNoSecondCasOrExtendedWait() {
        val f = Fixture()
        val ticket = f.makePending(RepairFault.COMMIT_THEN_THROW)
        val committed = f.journal.row()
        assertEquals(ticket.expectedWaiting, committed)
        assertEquals(TaskState.WAITING_USER, committed.task.state)
        val writes = f.journal.repairAttempts
        val reads = f.journal.reads
        f.now += 1_000
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(reads + 1, f.journal.reads)
        assertEquals(writes, f.journal.repairAttempts)
        assertEquals(committed, f.journal.row())
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun successfulCasThenSnapshotFailureRetainsTicketUntilReadOnlyReconciliation() {
        val f = Fixture()
        val launch = f.begin()
        f.journal.failedLists = 1
        assertFalse(f.launch(launch) { throw RejectedExecutionException() })
        f.runMain()
        val ticket = f.pending()
        val committed = f.journal.row()
        assertEquals(TaskState.WAITING_USER, committed.task.state)
        assertEquals(ticket.expectedWaiting, committed)
        assertEquals(1, f.journal.repairCommits)
        assertTrue(f.visibleRecords.isEmpty())
        assertSame(ticket, f.visiblePending.single())
        val writes = f.journal.repairAttempts
        f.now += 2_000
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(writes, f.journal.repairAttempts)
        assertEquals(committed, f.journal.row())
        assertEquals(listOf(committed), f.visibleRecords)
        assertTrue(f.visiblePending.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun successfulRepairRequiresAnotherExplicitResumeBeforeOneWorkerCanRun() {
        val f = Fixture()
        val ticket = f.makePending()
        val resumedBefore = f.journal.resumeCommits
        val submissionsBefore = f.workerSubmissions // makePending already attempted a rejected submit.
        assertEquals(0, f.workerEntries)
        assertTrue(f.workers.isEmpty())
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(TaskState.WAITING_USER, f.journal.row().task.state)
        assertEquals(resumedBefore, f.journal.resumeCommits)
        assertEquals(submissionsBefore, f.workerSubmissions)
        assertEquals(0, f.workerEntries)
        assertTrue(f.workers.isEmpty())
        f.assertNoMediaWork()
        val explicitResume = f.begin()
        assertTrue(f.launch(explicitResume))
        f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(resumedBefore + 1, f.journal.resumeCommits)
        assertEquals(submissionsBefore + 1, f.workerSubmissions)
        assertEquals(1, f.workerEntries)
        assertEquals(1, f.transfers)
        assertEquals(1, f.verifications)
        assertEquals(1, f.commits)
        assertTrue(f.handoff.pending().isEmpty())
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun repeatedClicksAndDelayedMainCompletionKeepOneRepairCommand() {
        val f = Fixture()
        val ticket = f.makePending()
        f.journal.failedReads = 1
        assertTrue(f.retry(ticket))
        repeat(5) { assertFalse(f.retry(ticket)) }
        assertEquals(1, f.controls.size)
        val reads = f.journal.reads
        f.runControls()
        assertEquals(reads + 1, f.journal.reads)
        repeat(5) { assertFalse(f.retry(ticket)) }
        assertEquals(setOf("task"), f.gate.pendingTaskIds())
        f.runMain()
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        f.assertNoMediaWork()
    }

    @Test fun rejectedControlQueueKeepsRecoveryAvailableAndOnlyReleasesItsOwnClick() {
        val f = Fixture()
        val ticket = f.makePending()
        val reads = f.journal.reads
        f.rejectControls = true
        assertTrue(f.retry(ticket))
        assertFalse(f.retry(ticket))
        assertTrue(f.controls.isEmpty())
        assertEquals(reads, f.journal.reads)
        f.runMain()
        assertSame(ticket, f.visiblePending.single())
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        f.rejectControls = false
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        f.assertNoMediaWork()
    }

    @Test fun delayedResumeAndRepairCallbacksCannotReleaseNewCancelCommand() {
        val f = Fixture()
        val ticket = f.makePending()
        f.journal.failedReads = 1
        assertTrue(f.retry(ticket)); f.runControls()
        val cancel = f.retireForCancel()
        assertTrue(f.gate.isCurrent(cancel))
        f.runMain()
        assertTrue(f.gate.isCurrent(cancel))
        assertEquals(setOf("task"), f.gate.pendingTaskIds())
        assertTrue(f.visiblePending.isEmpty())
        assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
        assertTrue(f.gate.release(cancel))

        val other = Fixture()
        val launch = other.begin()
        assertFalse(other.launch(launch) { throw RejectedExecutionException() })
        val newerCancel = other.retireForCancel()
        other.runMain() // Original Resume completion was still queued.
        assertTrue(other.gate.isCurrent(newerCancel))
        assertFalse(other.gate.release(launch.attempt))
    }

    @Test fun acceptedButQueuedWorkerPermanentlyConsumesRecoveryAuthority() {
        val f = Fixture()
        val launch = f.begin()
        assertTrue(f.launch(launch))
        assertEquals(1, f.workers.size)
        assertEquals(0, f.workerEntries)
        assertEquals(launch.running, f.journal.row())
        assertTrue(f.handoff.pending().isEmpty())
        assertEquals(0, f.journal.repairAttempts)
        f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(1, f.transfers)
        assertTrue(f.handoff.pending().isEmpty())
    }

    @Test fun runnableEntryThenRejectedExecutorExceptionDoesNotCompensateCompletedWork() {
        val f = Fixture()
        val launch = f.begin()
        assertTrue(f.launch(launch) { worker -> worker.run(); throw RejectedExecutionException() })
        assertEquals(1, f.workerEntries)
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(0, f.journal.repairAttempts)
        assertEquals(0, f.unacceptedReleases)
        assertTrue(f.handoff.pending().isEmpty())
    }

    @Test fun runnableEntryThenUnknownExecutorExceptionAlsoConsumesAuthority() {
        val f = Fixture()
        val launch = f.begin()
        assertTrue(f.launch(launch) { worker -> worker.run(); throw IllegalStateException() })
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(0, f.journal.repairAttempts)
        assertEquals(0, f.unacceptedReleases)
        assertTrue(f.handoff.pending().isEmpty())
    }

    @Test fun unknownExecutorFailureWithOrWithoutQueuedWorkNeverCreatesRepairAuthority() {
        for (queued in listOf(false, true)) {
            val f = Fixture()
            val launch = f.begin()
            assertThrows(IllegalStateException::class.java) {
                f.launch(launch) { worker ->
                    if (queued) f.workers.addLast(worker)
                    throw IllegalStateException()
                }
            }
            assertEquals(launch.running, f.journal.row())
            assertEquals(0, f.journal.repairAttempts)
            assertEquals(0, f.unacceptedReleases)
            assertTrue(f.handoff.pending().isEmpty())
            assertEquals(1, f.reservationReleases)
            assertTrue(f.liveLeases.isEmpty())
            f.assertNoMediaWork()
            f.runMain()
            if (queued) {
                f.globalOwner = "later-media-owner"
                f.runWorkers()
                assertEquals(launch.running, f.journal.row())
                assertEquals(0, f.workerEntries)
                assertEquals("later-media-owner", f.globalOwner)
                assertEquals(1, f.reservationReleases)
                f.assertNoMediaWork()
                assertTrue(f.handoff.pending().isEmpty())
            }
        }
    }

    @Test fun acceptedWorkerThenPublicationFailureDoesNotCreatePendingRepair() {
        val f = Fixture()
        val launch = f.begin()
        assertTrue(f.launch(launch))
        f.journal.failedLists = 1
        assertThrows(IllegalStateException::class.java) { f.publish() }
        assertEquals(launch.running, f.journal.row())
        assertTrue(f.handoff.pending().isEmpty())
        assertEquals(0, f.journal.repairAttempts)
        f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(1, f.transfers)
    }

    @Test fun admittedWorkersFirstJournalReadFailureIsOutsideUnacceptedRecovery() {
        val f = Fixture()
        val launch = f.begin()
        assertTrue(f.launch(launch))
        f.journal.failedReads = 1
        assertThrows(IllegalStateException::class.java) { f.runWorkers() }
        assertEquals(launch.running, f.journal.row())
        assertEquals(1, f.workerEntries)
        assertEquals(0, f.journal.repairAttempts)
        assertTrue(f.handoff.pending().isEmpty())
        assertTrue(f.liveLeases.isEmpty())
        assertEquals(1, f.reservationReleases)
        f.assertNoMediaWork()
    }

    @Test fun cancelRetiresBeforeQueuedRepairAndDurableCancellationCannotBeRewritten() {
        val f = Fixture()
        val ticket = f.makePending()
        assertTrue(f.retry(ticket))
        val reads = f.journal.reads
        val cancel = f.retireForCancel()
        assertTrue(ticket.operation.cancelToken.isCancelled)
        assertFalse(ticket.live.get())
        assertTrue(f.handoff.pending().isEmpty())
        f.runControls()
        assertEquals(reads, f.journal.reads)
        assertEquals(0, f.journal.repairAttempts)
        assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
        val terminal = f.journal.row()
        f.runMain()
        assertTrue(f.gate.isCurrent(cancel))
        repeat(3) { assertFalse(f.retry(ticket)) }
        assertEquals(terminal, f.journal.row())
        assertTrue(f.visiblePending.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun cancelDuringRepairReadFailureCannotReviveTicketOrReleaseNewCancelOwner() {
        val f = Fixture()
        val ticket = f.makePending()
        var cancel: SaveTaskCommandGate.Attempt? = null
        f.journal.beforeNextRead = { cancel = f.retireForCancel() }
        f.journal.failedReads = 1
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertNotNull(cancel)
        assertTrue(f.gate.isCurrent(cancel!!))
        assertTrue(ticket.operation.cancelToken.isCancelled)
        assertTrue(f.handoff.pending().isEmpty())
        assertTrue(f.visiblePending.isEmpty())
        assertEquals(0, f.journal.repairAttempts)
        assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
        assertFalse(f.retry(ticket))
        f.assertNoMediaWork()
    }

    @Test fun cancelAfterRepairCommitAndLatePublicationErrorNeverRevivesOldAuthority() {
        val f = Fixture()
        val ticket = f.makePending()
        var cancel: SaveTaskCommandGate.Attempt? = null
        f.journal.afterNextRepairCommit = {
            assertEquals(TaskState.WAITING_USER, f.journal.row().task.state)
            cancel = f.retireForCancel()
        }
        f.journal.failedLists = 1
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        assertEquals(TaskState.WAITING_USER, f.journal.row().task.state)
        assertTrue(f.gate.isCurrent(cancel!!))
        assertTrue(f.handoff.pending().isEmpty())
        assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
        val terminal = f.journal.row()
        assertFalse(f.retry(ticket))
        assertEquals(terminal, f.journal.row())
        assertTrue(f.visiblePending.isEmpty())
    }

    @Test fun rejectedDurableCancelCanBeRetriedWithoutRestoringRetiredRecoveryTicket() {
        val f = Fixture()
        val ticket = f.makePending()
        assertTrue(f.visibleRecords.isEmpty())
        f.journal.failedLists = 1
        assertThrows(IllegalStateException::class.java) { f.publish() }
        assertTrue(f.retry(ticket))
        val cancelledCommand = f.retireForCancel()
        assertEquals(setOf("task"), f.handoff.pendingCancellations())
        f.rejectControls = true
        assertThrows(RejectedExecutionException::class.java) {
            f.enqueue { f.coordinator.cancel("task") }
        }
        assertTrue(f.gate.release(cancelledCommand))
        f.runControls(); f.runMain()
        assertEquals(TaskState.RUNNING, f.journal.row().task.state)
        assertTrue(f.handoff.pending().isEmpty())
        assertEquals(setOf("task"), f.handoff.pendingCancellations())
        assertEquals(setOf("task"), f.visibleCancellations)
        assertFalse(f.retry(ticket))
        val explicitCancelRetry = f.gate.tryCancel("task")!!
        f.rejectControls = false
        f.enqueue { f.coordinator.cancel("task") }
        f.runControls()
        assertEquals(TaskState.CANCELLED, f.journal.row().task.state)
        assertTrue(f.gate.release(explicitCancelRetry))
        f.handoff.cancellationConfirmed("task")
        f.runMain()
        assertTrue(f.handoff.pendingCancellations().isEmpty())
        assertTrue(f.visibleCancellations.isEmpty())
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun failedDurableCancelKeepsFallbackUntilTerminalConfirmation() {
        val f = Fixture()
        val ticket = f.makePending()
        val cancel = f.retireForCancel()
        f.journal.failedReads = 1
        assertThrows(IllegalStateException::class.java) { f.coordinator.cancel("task") }
        assertTrue(f.gate.release(cancel))
        f.runMain()
        assertEquals(setOf("task"), f.handoff.pendingCancellations())
        assertTrue(f.handoff.pending().isEmpty())
        assertFalse(f.retry(ticket))
        f.handoff.observeTerminal(listOf(f.journal.row()))
        assertEquals(setOf("task"), f.handoff.pendingCancellations())
        val retriedCancel = f.gate.tryCancel("task")!!
        assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
        val terminal = f.journal.row()
        f.handoff.observeTerminal(listOf(terminal))
        assertTrue(f.handoff.pendingCancellations().isEmpty())
        assertTrue(f.gate.release(retriedCancel))
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun separatelyAdmittedNewResumeClearsObsoleteCancelOnlyFallback() {
        for (rejectNewWorker in listOf(false, true)) {
            val f = Fixture()
            val oldTicket = f.makePending(RepairFault.COMMIT_THEN_THROW)
            assertEquals(TaskState.WAITING_USER, f.journal.row().task.state)
            val cancel = f.retireForCancel()
            // The durable Cancel queue rejected the command, leaving the already-committed wait.
            assertTrue(f.gate.release(cancel))
            f.runMain()
            assertEquals(setOf("task"), f.handoff.pendingCancellations())
            val newer = f.begin()
            if (rejectNewWorker) {
                f.afterUnacceptedRelease = { f.journal.failedReads = 1 }
                assertFalse(f.launch(newer) { throw RejectedExecutionException() })
                assertSame(newer.running, f.pending().expected)
                assertNotSame(oldTicket, f.pending())
            } else assertTrue(f.launch(newer))
            f.runMain()
            assertTrue(f.handoff.pendingCancellations().isEmpty())
            assertTrue(f.visibleCancellations.isEmpty())
            assertFalse(f.retry(oldTicket))
            if (rejectNewWorker) f.assertNoMediaWork()
            else {
                f.runWorkers()
                assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
                assertTrue(f.handoff.pending().isEmpty())
            }
        }
    }

    @Test fun originalReleaseThenNewGlobalOwnerThenLateRepairFailureCannotReleaseNewOwner() {
        val f = Fixture()
        val launch = f.begin()
        f.afterUnacceptedRelease = {
            assertNull(f.globalOwner)
            f.globalOwner = "another-media-owner"
            f.journal.failedReads = 1
        }
        assertFalse(f.launch(launch) { throw RejectedExecutionException() })
        repeat(3) { launch.reservation.release() }
        f.runMain()
        assertEquals("another-media-owner", f.globalOwner)
        assertEquals(1, f.reservationReleases)
        val ticket = f.pending()
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals("another-media-owner", f.globalOwner)
        assertEquals(1, f.reservationReleases)
        assertTrue(f.liveLeases.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun missingOrFailedListSnapshotStillExposesExactPendingRecoveryAndCanRepair() {
        val f = Fixture()
        f.prepareWaiting("other")
        val other = f.journal.row("other")
        val ticket = f.makePending()
        f.journal.failedLists = 1
        assertThrows(IllegalStateException::class.java) { f.publish() }
        f.runMain()
        assertTrue(f.visibleRecords.isEmpty())
        assertEquals("task", f.visiblePending.single().taskId)
        assertSame(ticket, f.visiblePending.single())
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(TaskState.WAITING_USER, f.journal.row().task.state)
        assertEquals(other, f.journal.row("other"))
        assertEquals(setOf("task", "other"), f.visibleRecords.map { it.task.taskId }.toSet())
        assertTrue(f.visiblePending.isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun disposedPagePermanentlyRejectsCapturedActionAndQueuedRepair() {
        val f = Fixture()
        val ticket = f.makePending()
        val oldPage = f.action()
        oldPage.invoke(ticket)
        assertEquals(1, f.controls.size)
        oldPage.close()
        val reads = f.journal.reads
        f.runControls(); f.runMain()
        assertEquals(reads, f.journal.reads)
        assertSame(ticket, f.pending())
        oldPage.invoke(ticket)
        assertTrue(f.controls.isEmpty())
        f.action().invoke(ticket)
        f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun backgroundInvalidatesQueuedRepairAndForegroundReturnNeverReplaysIt() {
        val f = Fixture()
        val ticket = f.makePending()
        val page = f.action()
        page.invoke(ticket)
        f.foreground = false
        f.gate.retireResumesForBackground()
        f.coordinator.signalBackgroundPauseAll()
        f.foreground = true
        val reads = f.journal.reads
        f.runControls(); f.runMain()
        assertEquals(reads, f.journal.reads)
        assertSame(ticket, f.pending())
        assertFalse(ticket.operation.cancelToken.isCancelled)
        assertEquals(0, f.journal.repairAttempts)
        page.invoke(ticket) // A new explicit click, after return to foreground.
        f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun pageCurrentGuardBlocksRepairWhileBackgroundedWithoutCancellingTicket() {
        val f = Fixture()
        val ticket = f.makePending()
        val page = f.action()
        f.foreground = false
        page.invoke(ticket)
        assertTrue(f.controls.isEmpty())
        assertFalse(f.retry(ticket))
        assertSame(ticket, f.pending())
        f.foreground = true
        assertEquals(0, f.journal.repairAttempts)
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(1, f.journal.repairCommits)
        f.assertNoMediaWork()
    }

    @Test fun inactiveHostRouteRejectsCapturedRecoveryAndFallbackCancelBeforeDisposal() {
        val f = Fixture()
        val ticket = f.makePending()
        val page = f.action()
        f.hostCurrent = false
        page.invoke(ticket)
        var cancels = 0
        page.cancel("task") { cancels++; f.retireForCancel() }
        assertEquals(0, cancels)
        assertTrue(f.controls.isEmpty())
        assertSame(ticket, f.pending())
        assertFalse(ticket.operation.cancelToken.isCancelled)
        f.hostCurrent = true
        page.cancel("task") { cancels++; f.retireForCancel() }
        assertEquals(1, cancels)
        assertTrue(f.handoff.pending().isEmpty())
        assertEquals(setOf("task"), f.handoff.pendingCancellations())
        page.close()
        page.cancel("task") { cancels++ }
        assertEquals(1, cancels)
        f.assertNoMediaWork()
    }

    @Test fun hostPredicateIsReadAtClickOnlyAndNeverFromTheControlLane() {
        val f = Fixture()
        val ticket = f.makePending()
        var hostReads = 0
        var onMain = true
        val page = SaveRecoveryRetryAction({
            assertTrue("Host/navigation predicate must run on the caller's main lane", onMain)
            hostReads++
            true
        }) { value, attached -> f.handoff.retry(value, attached); Unit }
        page.invoke(ticket)
        assertEquals(1, hostReads)
        onMain = false
        f.runControls()
        assertEquals(1, hostReads)
        onMain = true
        f.runMain()
        assertEquals(1, f.journal.repairCommits)
        f.assertNoMediaWork()
    }

    @Test fun replacedRuntimeBlocksQueuedAndCapturedCallbacksIncludingMainPublication() {
        val f = Fixture()
        val ticket = f.makePending()
        val page = f.action()
        page.invoke(ticket)
        val changes = f.changedCalls
        val reads = f.journal.reads
        f.runtimeCurrent = false
        f.runControls(); f.runMain()
        assertEquals(reads, f.journal.reads)
        assertEquals(changes, f.changedCalls)
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        assertFalse(f.retry(ticket))
        page.invoke(ticket)
        assertTrue(f.controls.isEmpty())
        assertEquals(0, f.journal.repairAttempts)
        f.assertNoMediaWork()
    }

    @Test fun changedRevisionAndNewOperationRetireOnlyTheOldRecoveryCapability() {
        val f = Fixture()
        val ticket = f.makePending()
        f.prepareWaiting("other")
        val other = f.journal.row("other")
        val newWait = f.coordinator.pauseForBackground("task")!!
        val newOperation = f.operation("independent-operation")
        val newer = f.coordinator.resume("task", newOperation)!!
        assertEquals(newWait.task.revision + 1, newer.task.revision)
        val writes = f.journal.repairAttempts
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        assertEquals(writes, f.journal.repairAttempts)
        assertEquals(newer, f.journal.row())
        assertEquals(other, f.journal.row("other"))
        assertFalse(newOperation.cancelToken.isCancelled)
        assertTrue(f.handoff.pending().isEmpty())
        f.assertNoMediaWork()
    }

    @Test fun terminalOrMissingRowCannotBeOverwrittenByOldRecoveryTicket() {
        for (missing in listOf(false, true)) {
            val f = Fixture()
            val ticket = f.makePending()
            val terminal = if (missing) null else {
                assertEquals(TaskState.CANCELLED, f.coordinator.cancel("task")!!.state)
                f.journal.row()
            }
            if (missing) f.journal.records.remove("task")
            val writes = f.journal.repairAttempts
            assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
            assertEquals(writes, f.journal.repairAttempts)
            assertEquals(terminal, f.journal.records["task"])
            assertTrue(f.handoff.pending().isEmpty())
            f.assertNoMediaWork()
        }
    }

    @Test fun processRestartUsesExistingBoundedRecoveryWithoutTicketOrAutomaticWork() {
        val original = Fixture()
        original.makePending()
        assertEquals(TaskState.RUNNING, original.journal.row().task.state)
        val restarted = Fixture(journal = original.journal, initialize = false)
        assertTrue(restarted.handoff.pending().isEmpty())
        val rows = restarted.coordinator.recover(restarted.operation("restart-recovery"))
        assertEquals(1, rows.size)
        assertEquals(TaskState.WAITING_USER, rows.single().task.state)
        assertEquals(SaveWaitReason.RECOVERY, rows.single().waitReason)
        assertEquals(SaveError.RECOVERY_REQUIRED, rows.single().error)
        assertTrue(restarted.controls.isEmpty())
        assertTrue(restarted.workers.isEmpty())
        assertTrue(restarted.handoff.pending().isEmpty())
        restarted.assertNoMediaWork()
    }

    @Test fun finalizedRecordRepairsLocallyWithSameIdentityAndZeroRetryBudgetThenReverifiesOnce() {
        val f = Fixture(method = TaskMethod.RECORD)
        val original = f.journal.row()
        assertNotNull(original.transfer)
        assertEquals(0, original.retryBudget)
        val launch = f.begin()
        f.foreground = false
        f.networkAllowed = false
        f.afterUnacceptedRelease = { f.journal.failedReads = 1 }
        assertFalse(f.launch(launch))
        f.runMain()
        val ticket = f.pending()
        f.foreground = true
        assertTrue(f.retry(ticket)); f.runControls(); f.runMain()
        val repaired = f.journal.row()
        f.assertRepair(launch.running, repaired, SaveWaitReason.RECOVERY)
        assertEquals(original.request.input, repaired.request.input)
        assertEquals(original.artifact, repaired.artifact)
        assertEquals(original.transfer, repaired.transfer)
        assertEquals(original.commitId, repaired.commitId)
        assertEquals(0, repaired.retryBudget)
        assertEquals(0, repaired.attempt)
        assertEquals(0, f.networkChecks)
        assertEquals(0, f.leasesCreated)
        f.assertNoMediaWork()
        val reverify = f.begin()
        assertTrue(f.launch(reverify))
        f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(0, f.captures)
        assertEquals(0, f.transfers)
        assertEquals(0, f.networkChecks)
        assertEquals(1, f.verifications)
        assertEquals(1, f.commits)
        assertTrue(f.recordingCommands.isEmpty())
    }

    @Test fun finalizedDirectDoesNotConsultNetworkOrRetransferAfterLocalRepair() {
        val f = Fixture(finalized = true)
        val original = f.journal.row()
        assertNotNull(original.transfer)
        val launch = f.begin()
        f.networkAllowed = false
        assertFalse(f.launch(launch) { throw RejectedExecutionException() })
        f.runMain()
        f.assertRepair(launch.running, f.journal.row(), SaveWaitReason.RECOVERY)
        assertEquals(original.transfer, f.journal.row().transfer)
        assertEquals(0, f.networkChecks)
        f.assertNoMediaWork()
        val resume = f.begin()
        assertTrue(f.launch(resume)); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.journal.row().task.state)
        assertEquals(0, f.transfers)
        assertEquals(0, f.networkChecks)
        assertEquals(1, f.verifications)
        assertEquals(1, f.commits)
    }

    private enum class Cause(val reason: SaveWaitReason) {
        PREFLIGHT_READ(SaveWaitReason.RECOVERY),
        WORKER_REJECTION(SaveWaitReason.RECOVERY),
        BACKGROUND(SaveWaitReason.BACKGROUND),
        NETWORK(SaveWaitReason.NETWORK),
    }

    private enum class RepairFault { NONE, READ, REJECT, THROW_BEFORE_COMMIT, COMMIT_THEN_THROW }

    private data class Launch(
        val waiting: SaveRecord,
        val running: SaveRecord,
        val operation: OperationContext,
        val attempt: SaveTaskCommandGate.Attempt,
        val reservation: SaveResumeExecutionReservation,
        val lease: RequestContextRef?,
    )

    /** Small port/queue harness; all decisions about admission and repair belong to production. */
    private class Fixture(
        val journal: Journal = Journal(),
        val method: TaskMethod = TaskMethod.DIRECT,
        initialize: Boolean = true,
        finalized: Boolean = false,
    ) {
        val gate = SaveTaskCommandGate()
        val controls = ArrayDeque<() -> Unit>()
        val workers = ArrayDeque<Runnable>()
        private val main = ArrayDeque<() -> Unit>()
        val limits = SaveLimits(10_000_000, 30_000_000)
        var now = 0L
        var foreground = true
        var hostCurrent = true
        var networkAllowed = true
        var runtimeCurrent = true
        var rejectControls = false
        var networkChecks = 0
        var workerSubmissions = 0
        var workerEntries = 0
        var unacceptedReleases = 0
        var reservationReleases = 0
        var globalOwner: String? = null
        var leasesCreated = 0
        val liveLeases = mutableSetOf<RequestContextRef>()
        var changedCalls = 0
        var visibleRecords = emptyList<SaveRecord>()
        var visiblePending = emptyList<SaveResumeHandoff.Ticket>()
        var visibleCancellations = emptySet<String>()
        var afterUnacceptedRelease: (() -> Unit)? = null
        var prepares = 0
        var transfers = 0
        var captures = 0
        var verifications = 0
        var commits = 0
        var inspections = 0
        var cleanups = 0
        var failVerification = false
        val recordingCommands = mutableListOf<String>()
        private var sequence = 0
        private val receipts = mutableMapOf<String, MediaCommitReceipt>()
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
                checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
                progress: TransferProgress): TransferResult {
                transfers++
                assertFalse(context.cancelToken.isCancelled)
                progress.onBytes(1024)
                return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
            }
        }, ArtifactVerifier { artifact, transfer, _ ->
            verifications++
            if (failVerification) throw SaveFailure(SaveError.STORAGE_FULL)
            val recording = transfer.recordingExpectation
            VerificationEvidence(artifact.artifactId, transfer.bytesOnDisk, "a".repeat(64), true,
                1, 0, 1_000, if (recording == null) 1920 else 1280, if (recording == null) 1080 else 720,
                false, DecodePosition.entries.toSet(), false,
                recordingProof = recording?.let {
                    RecordingVerificationProof(it.video, null, "video/avc", null, null, null,
                        it.video.lastPtsUs, true, decodedResumeBoundaries = emptyList())
                })
        }, object : PrivateMediaStore {
            override fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact {
                prepares++
                return StagingArtifact(taskId, commitId)
            }
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                commits++
                return MediaCommitReceipt(artifact.artifact.commitId, artifact.artifact.artifactId,
                    "media-${artifact.artifact.commitId}", artifact.evidence.sizeBytes, artifact.evidence.sha256)
                    .also { receipts[it.commitId] = it }
            }
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
                inspections++
                return receipts[commitId]?.let { CommitInspection(it, true, true, true) }
            }
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) { cleanups++ }
        }, EpochClock { now }, limits, recording = object : RecordSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
                context: OperationContext, limits: SaveLimits): TransferResult {
                captures++
                assertEquals(0, context.retryBudget)
                val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
                val expectation = RecordingArtifactExpectation(descriptor, descriptor.requestedProfile,
                    RecordingAudioOutcome.NO_AUDIO_REQUESTED, RecordingStopReason.USER_STOP,
                    video, null, 1_000_000, 1024)
                return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024,
                    recordingExpectation = expectation)
            }
            override fun pause(requestId: String): RecordingSnapshot? { recordingCommands += "pause"; return null }
            override fun resume(requestId: String): RecordingSnapshot? { recordingCommands += "resume"; return null }
            override fun stopAndSave(requestId: String): RecordingSnapshot? { recordingCommands += "stop"; return null }
            override fun cancel(requestId: String): RecordingSnapshot? { recordingCommands += "cancel"; return null }
        })
        val handoff: SaveResumeHandoff = SaveResumeHandoff(coordinator, gate, ::enqueue,
            post = { action -> main.addLast(action) }, publishRecords = ::publish,
            onChanged = { changedCalls++; visiblePending = pendingForRender(); visibleCancellations = cancellationsForRender() },
            isRuntimeCurrent = { runtimeCurrent })

        init {
            if (initialize) {
                prepareWaiting("task", finalized || method == TaskMethod.RECORD)
                clearMediaCounters()
                journal.resetCounters()
            }
        }

        // This indirection avoids referring to handoff from its own property initializer.
        private fun pendingForRender() = handoff.pending()
        private fun cancellationsForRender() = handoff.pendingCancellations()

        fun operation(id: String, token: CancellationToken = CancellationSource()) =
            OperationContext(id, 100_000, token, retryBudget = 2,
                resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))

        fun prepareWaiting(id: String, finalized: Boolean = false) {
            val request = if (method == TaskMethod.RECORD)
                SaveRequest(id, "key-$id", SaveInput.Capture(CaptureDescriptor(id, 7, RecordingAudioChoice.NONE)))
            else SaveRequest(id, "key-$id", SelectedMediaResource("candidate", "variant",
                SourceUrl("https://example.invalid/media")))
            assertTrue(coordinator.submit(request, operation("initial-$id")) is SaveSubmission.Accepted)
            if (finalized) {
                failVerification = true
                try { assertEquals(TaskState.WAITING_USER, coordinator.execute(id)!!.task.state) }
                finally { failVerification = false }
                assertNotNull(journal.row(id).transfer)
            } else assertEquals(TaskState.WAITING_USER, coordinator.pauseForBackground(id)!!.task.state)
        }

        fun begin(afterResumeCommit: (() -> Unit)? = null): Launch {
            val waiting = coordinator.record("task")!!
            assertEquals(TaskState.WAITING_USER, waiting.task.state)
            val attempt = gate.tryResume(waiting.task)!!
            val operation = operation("resume-${++sequence}", attempt.cancelToken)
            assertNull(globalOwner)
            globalOwner = operation.operationId
            val reservation = SaveResumeExecutionReservation { reservationReleases++; globalOwner = null }
            val lease = if (method == TaskMethod.RECORD) null else
                RequestContextRef("lease-${++leasesCreated}").also(liveLeases::add)
            journal.afterNextResumeCommit = afterResumeCommit
            val running = coordinator.resume("task", operation, lease)!!
            assertTrue(attempt.ownsResumed(running, operation))
            return Launch(waiting, running, operation, attempt, reservation, lease)
        }

        /** Caller-owned resources are separate from the production helper's admission decisions. */
        fun launch(value: Launch, submit: (Runnable) -> Unit = { workers.addLast(it) }): Boolean {
            var accepted = false
            try {
                accepted = handoff.launch(value.running, value.operation, value.attempt,
                    foreground = { foreground },
                    networkAllowed = { networkChecks++; networkAllowed },
                    submit = { worker -> workerSubmissions++; submit(worker) },
                    work = {
                        workerEntries++
                        try { coordinator.execute(value.running.task.taskId) }
                        finally { value.reservation.release(); value.lease?.let(liveLeases::remove) }
                    },
                    releaseUnaccepted = {
                        unacceptedReleases++
                        value.reservation.release()
                        value.lease?.let(liveLeases::remove)
                        afterUnacceptedRelease?.also { afterUnacceptedRelease = null }?.invoke()
                    })
                return accepted
            } finally {
                if (!accepted) { value.reservation.release(); value.lease?.let(liveLeases::remove) }
                main.addLast { gate.release(value.attempt); Unit }
            }
        }

        fun armCause(cause: Cause) {
            when (cause) {
                Cause.PREFLIGHT_READ -> journal.failedReads = 1
                Cause.WORKER_REJECTION -> Unit
                Cause.BACKGROUND -> foreground = false
                Cause.NETWORK -> networkAllowed = false
            }
        }

        fun launchForCause(value: Launch, cause: Cause): Boolean = if (cause == Cause.WORKER_REJECTION)
            launch(value) { throw RejectedExecutionException() } else launch(value)

        fun makePending(fault: RepairFault = RepairFault.READ): SaveResumeHandoff.Ticket {
            val value = begin()
            afterUnacceptedRelease = { journal.arm(fault) }
            assertFalse(launch(value) { throw RejectedExecutionException() })
            runMain()
            return pending()
        }

        fun pending(): SaveResumeHandoff.Ticket {
            assertEquals(1, handoff.pending().size)
            return handoff.pending().single()
        }

        fun retry(ticket: SaveResumeHandoff.Ticket) = handoff.retry(ticket) { foreground && runtimeCurrent }
        fun action() = SaveRecoveryRetryAction({ foreground && runtimeCurrent && hostCurrent }) { ticket, current ->
            handoff.retry(ticket, current); Unit
        }

        fun retireForCancel(): SaveTaskCommandGate.Attempt {
            handoff.cancel("task")
            val cancel = gate.tryCancel("task")!!
            coordinator.signalUserCancel("task")
            return cancel
        }

        fun enqueue(action: () -> Unit) {
            if (rejectControls) throw RejectedExecutionException()
            controls.addLast(action)
        }
        fun publish() { visibleRecords = journal.listRecent(100) }
        fun runControls() { while (controls.isNotEmpty()) controls.removeFirst().invoke() }
        fun runWorkers() { while (workers.isNotEmpty()) workers.removeFirst().run() }
        fun runMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }

        fun assertRepair(expected: SaveRecord, actual: SaveRecord, reason: SaveWaitReason) {
            assertEquals(TaskState.WAITING_USER, actual.task.state)
            assertEquals(expected.task.revision + 1, actual.task.revision)
            assertEquals(reason, actual.waitReason)
            // Exactly the allowed state/revision/wait/error fields may differ.
            assertEquals(expected, actual.copy(task = expected.task, waitReason = expected.waitReason, error = expected.error))
        }

        fun assertNoMediaWork() {
            assertEquals(0, prepares)
            assertEquals(0, transfers)
            assertEquals(0, captures)
            assertEquals(0, verifications)
            assertEquals(0, commits)
            assertEquals(0, inspections)
            assertEquals(0, cleanups)
            assertTrue(recordingCommands.isEmpty())
        }

        private fun clearMediaCounters() {
            prepares = 0; transfers = 0; captures = 0; verifications = 0
            commits = 0; inspections = 0; cleanups = 0; recordingCommands.clear()
        }
    }

    private class Journal : SaveJournal {
        val records = linkedMapOf<String, SaveRecord>()
        var reads = 0
        var listReads = 0
        var resumeCommits = 0
        var repairAttempts = 0
        var repairCommits = 0
        var failedReads = 0
        var failedLists = 0
        private var repairFault = RepairFault.NONE
        var afterNextResumeCommit: (() -> Unit)? = null
        var beforeNextRead: (() -> Unit)? = null
        var afterNextRepairCommit: (() -> Unit)? = null

        fun row(id: String = "task") = records.getValue(id)
        fun arm(fault: RepairFault) {
            if (fault == RepairFault.READ) failedReads++ else repairFault = fault
        }
        fun resetCounters() { reads = 0; listReads = 0; resumeCommits = 0; repairAttempts = 0; repairCommits = 0 }
        override fun findByIdempotencyKey(key: String) = records.values.firstOrNull { it.request.idempotencyKey == key }
        override fun read(taskId: String): SaveRecord? {
            reads++
            beforeNextRead?.also { beforeNextRead = null }?.invoke()
            if (failedReads > 0) { failedReads--; throw IllegalStateException() }
            return records[taskId]
        }
        override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord {
            check(records.size < maxEntries)
            check(!records.containsKey(record.task.taskId))
            records[record.task.taskId] = record
            return record
        }
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            val previous = records[next.task.taskId] ?: return false
            if (previous.task.revision != expectedRevision || next.task.revision != expectedRevision + 1) return false
            val repair = previous.task.state == TaskState.RUNNING && next.task.state == TaskState.WAITING_USER
            val fault = if (repair) repairFault.also { repairFault = RepairFault.NONE; repairAttempts++ }
                else RepairFault.NONE
            if (fault == RepairFault.REJECT) return false
            if (fault == RepairFault.THROW_BEFORE_COMMIT) throw IllegalStateException()
            records[next.task.taskId] = next
            if (previous.task.state == TaskState.WAITING_USER && next.task.state == TaskState.RUNNING) {
                resumeCommits++
                afterNextResumeCommit?.also { afterNextResumeCommit = null }?.invoke()
            }
            if (repair) {
                repairCommits++
                afterNextRepairCommit?.also { afterNextRepairCommit = null }?.invoke()
            }
            if (fault == RepairFault.COMMIT_THEN_THROW) throw IllegalStateException()
            return true
        }
        override fun listUnfinished(limit: Int) = records.values.filterNot { it.task.state.isTerminal }.take(limit)
        override fun listRecent(limit: Int): List<SaveRecord> {
            listReads++
            if (failedLists > 0) { failedLists--; throw IllegalStateException() }
            return records.values.toList().takeLast(limit)
        }
    }
}
