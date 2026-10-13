package com.easysavevideo.app.save

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Delayed control/worker/main queues around the production gate and real SaveCoordinator. */
class SaveResumeQueueRegressionTest {
    @Test fun originalDoubleResumeTraceReallyPausesTheFirstRunningOperation() {
        val f = Fixture()
        val first = f.coordinator.resume("task", f.operation("first"))!!
        f.executing = true
        val duplicate = f.coordinator.resume("task", f.operation("duplicate"))!!
        assertEquals(TaskState.RUNNING, duplicate.task.state)
        assertEquals(first.operationId, duplicate.operationId)
        // The former launchExecution reservation failure deferred this already-running task.
        f.coordinator.pauseForBackground("task")
        assertEquals(SaveWaitReason.BACKGROUND, f.coordinator.execute("task")!!.waitReason)
        assertEquals(0, f.transfers)
    }

    @Test fun delayedControlsAndMainRenderAdmitOneLeaseAndOneExecutionForDoubleResume() {
        val f = Fixture()
        val staleRow = f.coordinator.record("task")!!
        assertTrue(f.resume(row = staleRow))
        repeat(3) { assertFalse(f.resume(row = staleRow)) }
        assertEquals(1, f.leasesCreated)
        assertEquals(1, f.controls.size)
        f.runControls()
        assertEquals(TaskState.RUNNING, f.coordinator.record("task")!!.task.state)
        assertEquals(1, f.workers.size)
        f.runMain()
        assertFalse(f.resume(row = staleRow)) // Pending UI may clear before execution finishes.
        f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
        assertEquals(1, f.transfers)
        assertEquals(1, f.commits)
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun differentTasksQueuedWhileIdleCannotPauseOrReplaceTheFirstExecution() {
        val f = Fixture()
        f.prepareWaiting("other")
        val other = f.coordinator.record("other")!!
        assertTrue(f.resume())
        assertTrue(f.resume("other"))
        f.runControls()
        val first = f.coordinator.record("task")!!
        assertEquals(TaskState.RUNNING, first.task.state)
        assertEquals(other, f.coordinator.record("other"))
        assertEquals(1, f.workers.size)
        f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
        assertTrue(f.resume("other"))
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("other")!!.task.state)
        assertEquals(2, f.transfers)
    }

    @Test fun cancelSupersedesQueuedResumeBeforeAnyCoordinatorResumeOrTransfer() {
        val f = Fixture()
        assertTrue(f.resume())
        val resume = f.lastAttempt!!
        assertTrue(f.cancel())
        val cancel = f.lastAttempt!!
        assertFalse(f.resume())
        f.controls.removeFirst().invoke()
        f.runMain()
        assertFalse(f.gate.isCurrent(resume))
        assertTrue(f.gate.isCurrent(cancel))
        assertEquals(setOf("task"), f.gate.pendingTaskIds())
        assertEquals(0, f.resumeCalls)
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.CANCELLED, f.coordinator.record("task")!!.task.state)
        assertEquals(0, f.transfers)
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun cancelBetweenAdmissionCheckAndCoordinatorResumeCannotReplaceItsCancelledRuntime() {
        val f = Fixture()
        f.beforeResume = { assertTrue(f.cancel()) }
        f.resume(); f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.CANCELLED, f.coordinator.record("task")!!.task.state)
        assertEquals(0, f.transfers)
        assertFalse(f.executing)
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun cancelDuringRuntimeReplacementAndRejectedCancelQueueStillFinishesOwnedUnlaunchedAttempt() {
        val f = Fixture()
        f.journal.onRunningWrite = {
            f.rejectControls = true
            assertTrue(f.cancel())
        }
        f.resume(); f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.CANCELLED, f.coordinator.record("task")!!.task.state)
        assertEquals(0, f.transfers)
        assertFalse(f.executing)
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun cancelAfterFinalAdmissionCheckFencesQueuedWorkerByExactOperationToken() {
        val f = Fixture()
        f.beforeLaunch = { assertTrue(f.cancel()) }
        f.resume(); f.controls.removeFirst().invoke()
        // Worker can run before the durable Cancel command; its original token already says Cancel.
        f.runWorkers(); f.runControls(); f.runMain()
        assertEquals(TaskState.CANCELLED, f.coordinator.record("task")!!.task.state)
        assertEquals(0, f.transfers)
        assertEquals(0, f.commits)
    }

    @Test fun journalFailureReleasesLeaseReservationAndAttemptForExplicitRetry() {
        val f = Fixture()
        f.resume()
        f.journal.failNextRead = true
        f.runControls(); f.runMain()
        assertTrue(f.liveLeases.isEmpty())
        assertFalse(f.executing)
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun resumeJournalSaveFailureReleasesItsReservedLaneAndLease() {
        val f = Fixture()
        f.journal.failNextRunningWrite = true
        f.resume(); f.runControls(); f.runMain()
        assertEquals(TaskState.WAITING_USER, f.coordinator.record("task")!!.task.state)
        assertTrue(f.liveLeases.isEmpty())
        assertFalse(f.executing)
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun leaseAndControlQueueFailuresAllowFreshExplicitRetry() {
        val f = Fixture()
        f.rejectLease = true
        assertFalse(f.resume())
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        f.rejectLease = false; f.rejectControls = true
        assertTrue(f.resume())
        assertTrue(f.gate.pendingTaskIds().isEmpty())
        assertTrue(f.liveLeases.isEmpty())
        f.rejectControls = false
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun executionQueueRejectionDefersOnlyItsOwnedAttemptAndAllowsExplicitRetry() {
        val f = Fixture()
        f.rejectExecution = true
        f.resume(); f.runControls(); f.runMain()
        assertEquals(SaveWaitReason.BACKGROUND, f.coordinator.record("task")!!.waitReason)
        assertTrue(f.liveLeases.isEmpty())
        assertFalse(f.executing)
        f.rejectExecution = false
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun launchExceptionBeforeReleaseStillReleasesItsOwnReservationAndLease() {
        val f = Fixture()
        f.failLaunchBeforeRelease = true
        f.resume(); f.runControls(); f.runMain()
        assertFalse(f.executing)
        assertTrue(f.liveLeases.isEmpty())
        assertTrue(f.gate.pendingTaskIds().isEmpty())
    }

    @Test fun launchReleaseThenNewOwnerThenDeferralExceptionCannotReleaseTheNewOwner() {
        val f = Fixture()
        f.rejectExecution = true
        f.afterLaunchRelease = { f.executing = true; f.journal.failNextRead = true }
        f.resume(); f.runControls(); f.runMain()
        assertTrue(f.executing)
        assertTrue(f.liveLeases.isEmpty())
        assertTrue(f.gate.pendingTaskIds().isEmpty())
    }

    @Test fun staleWaitingRevisionDoesNotMutateOrLaunchAnAlreadyRunningOperation() {
        val f = Fixture()
        val old = f.coordinator.record("task")!!
        f.resume(row = old)
        val otherOperation = f.coordinator.resume("task", f.operation("other-operation"))!!
        f.runControls(); f.runMain()
        assertEquals(otherOperation, f.coordinator.record("task"))
        assertEquals(0, f.resumeCalls)
        assertTrue(f.workers.isEmpty())
        assertTrue(f.liveLeases.isEmpty())
    }

    @Test fun newWaitingRevisionCannotConsumeAnOldResumeCommand() {
        val f = Fixture()
        f.resume()
        f.coordinator.resume("task", f.operation("another-explicit-command"))
        val latest = f.coordinator.pauseForBackground("task")!!
        f.runControls(); f.runMain()
        assertEquals(latest, f.coordinator.record("task"))
        assertEquals(0, f.resumeCalls)
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun backgroundThenForegroundCannotReplayQueuedResumeButFreshExplicitRetryWorks() {
        val f = Fixture()
        f.resume(); f.background(); f.foreground = true
        assertFalse(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.WAITING_USER, f.coordinator.record("task")!!.task.state)
        assertEquals(0, f.resumeCalls)
        assertEquals(0, f.transfers)
        assertTrue(f.resume())
        f.runControls(); f.runMain(); f.runWorkers()
        assertEquals(TaskState.SUCCEEDED, f.coordinator.record("task")!!.task.state)
    }

    @Test fun backgroundDuringRuntimeReplacementDefersWithoutUserCancellationEvenAfterForegroundReturn() {
        val f = Fixture()
        f.journal.onRunningWrite = { f.background(); f.foreground = true }
        f.resume(); f.runControls(); f.runMain(); f.runWorkers()
        val waiting = f.coordinator.record("task")!!
        assertEquals(TaskState.WAITING_USER, waiting.task.state)
        assertEquals(SaveWaitReason.BACKGROUND, waiting.waitReason)
        assertEquals(0, f.transfers)
        assertFalse(f.executing)
        assertTrue(f.liveLeases.isEmpty())
    }

    private class Fixture {
        val gate = SaveTaskCommandGate()
        val controls = ArrayDeque<() -> Unit>()
        val workers = ArrayDeque<() -> Unit>()
        private val main = ArrayDeque<() -> Unit>()
        val journal = Journal()
        val limits = SaveLimits(10_000_000, 30_000_000)
        var foreground = true
        var executing = false
        var rejectLease = false
        var rejectControls = false
        var rejectExecution = false
        var failLaunchBeforeRelease = false
        var afterLaunchRelease: (() -> Unit)? = null
        var leasesCreated = 0
        val liveLeases = mutableSetOf<RequestContextRef>()
        var resumeCalls = 0
        var transfers = 0
        var commits = 0
        var lastAttempt: SaveTaskCommandGate.Attempt? = null
        var beforeResume: (() -> Unit)? = null
        var beforeLaunch: (() -> Unit)? = null
        private var sequence = 0
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++
                assertFalse(context.cancelToken.isCancelled)
                progress.onBytes(1024)
                return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
            }
        }, ArtifactVerifier { artifact, transfer, _ ->
            VerificationEvidence(artifact.artifactId, transfer.bytesOnDisk, "a".repeat(64), true,
                1, 0, 1_000, 1920, 1080, false, DecodePosition.entries.toSet(), false)
        }, object : PrivateMediaStore {
            private val committed = mutableMapOf<String, MediaCommitReceipt>()
            override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact(taskId, commitId)
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                commits++
                return MediaCommitReceipt(artifact.artifact.commitId, artifact.artifact.artifactId,
                    "media-${artifact.artifact.commitId}", artifact.evidence.sizeBytes, artifact.evidence.sha256)
                    .also { committed[it.commitId] = it }
            }
            override fun inspectCommitted(commitId: String, context: OperationContext) =
                committed[commitId]?.let { CommitInspection(it, true, true, true) }
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) = Unit
        }, EpochClock { 0 }, limits)

        init { prepareWaiting("task") }

        fun operation(id: String, cancel: CancellationToken = CancellationSource()) =
            OperationContext(id, 10_000, cancel, resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))

        fun prepareWaiting(id: String) {
            val request = SaveRequest(id, "key-$id", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/media")))
            assertTrue(coordinator.submit(request, operation("initial-$id")) is SaveSubmission.Accepted)
            assertEquals(TaskState.WAITING_USER, coordinator.pauseForBackground(id)!!.task.state)
        }

        fun resume(id: String = "task", row: SaveRecord = coordinator.record(id)!!): Boolean {
            if (!foreground || executing || row.task.state != TaskState.WAITING_USER) return false
            val attempt = gate.tryResume(row.task) ?: return false
            lastAttempt = attempt
            if (rejectLease) { gate.release(attempt); return false }
            val lease = RequestContextRef("lease-${++leasesCreated}").also(liveLeases::add)
            if (rejectControls) { gate.release(attempt); liveLeases.remove(lease); return true }
            controls.add {
                var reservation: SaveResumeExecutionReservation? = null
                var launched = false
                try {
                    val current = coordinator.record(id)
                    if (current != null && foreground && gate.canResume(attempt, current.task) && !executing) {
                        executing = true
                        val execution = SaveResumeExecutionReservation { executing = false }
                        reservation = execution
                        val operation = operation("resume-${++sequence}", attempt.cancelToken)
                        beforeResume?.also { beforeResume = null }?.invoke()
                        resumeCalls++
                        val resumed = coordinator.resume(id, operation, lease)
                        if (attempt.ownsResumed(resumed, operation)) {
                            when {
                                attempt.cancelToken.isCancelled -> coordinator.cancel(id)
                                !foreground || !gate.isCurrent(attempt) -> coordinator.pauseForBackground(id)
                                else -> {
                                    beforeLaunch?.also { beforeLaunch = null }?.invoke()
                                    launched = launch(id, lease, execution::release)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* Same command failure boundary as the Android runtime. */ }
                finally {
                    if (!launched) { reservation?.release(); liveLeases.remove(lease) }
                    main.add { gate.release(attempt); Unit }
                }
            }
            return true
        }

        fun cancel(id: String = "task"): Boolean {
            val attempt = gate.tryCancel(id) ?: return false
            lastAttempt = attempt
            coordinator.signalUserCancel(id)
            if (rejectControls) { gate.release(attempt); return true }
            controls.add {
                try { coordinator.cancel(id) }
                finally { main.add { gate.release(attempt); Unit } }
            }
            return true
        }

        private fun launch(id: String, lease: RequestContextRef, releaseExecution: () -> Unit): Boolean {
            if (failLaunchBeforeRelease) throw IllegalStateException()
            if (!foreground || rejectExecution) {
                releaseExecution()
                afterLaunchRelease?.also { afterLaunchRelease = null }?.invoke()
                liveLeases.remove(lease); coordinator.pauseForBackground(id); return false
            }
            workers.add {
                try {
                    if (!foreground) coordinator.pauseForBackground(id)
                    coordinator.execute(id)
                } finally { releaseExecution(); liveLeases.remove(lease) }
            }
            return true
        }

        fun background() {
            foreground = false
            gate.retireResumesForBackground()
            coordinator.signalBackgroundPauseAll()
            controls.add { journal.listUnfinished(100).forEach { coordinator.pauseForBackground(it.task.taskId) } }
        }
        fun runControls() { while (controls.isNotEmpty()) controls.removeFirst().invoke() }
        fun runWorkers() { while (workers.isNotEmpty()) workers.removeFirst().invoke() }
        fun runMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }
    }

    private class Journal : SaveJournal {
        private val records = linkedMapOf<String, SaveRecord>()
        var failNextRead = false
        var failNextRunningWrite = false
        var onRunningWrite: (() -> Unit)? = null
        override fun findByIdempotencyKey(key: String) = records.values.firstOrNull { it.request.idempotencyKey == key }
        override fun read(taskId: String): SaveRecord? {
            if (failNextRead) { failNextRead = false; throw IllegalStateException() }
            return records[taskId]
        }
        override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord {
            check(records.size < maxEntries)
            records[record.task.taskId] = record
            return record
        }
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            val previous = records[next.task.taskId] ?: return false
            if (previous.task.revision != expectedRevision || next.task.revision != expectedRevision + 1) return false
            if (previous.task.state == TaskState.WAITING_USER && next.task.state == TaskState.RUNNING) {
                if (failNextRunningWrite) { failNextRunningWrite = false; throw IllegalStateException() }
                onRunningWrite?.also { onRunningWrite = null }?.invoke()
            }
            records[next.task.taskId] = next
            return true
        }
        override fun listUnfinished(limit: Int) = records.values.filterNot { it.task.state.isTerminal }.take(limit)
        override fun listRecent(limit: Int) = records.values.toList().takeLast(limit)
    }
}
