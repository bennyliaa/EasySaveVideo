package com.easysavevideo.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real coordinator calls with journal/port actors; no adapter admission or repair algorithm is copied. */
class UnadmittedResumeRecoveryTest {
    private enum class CasFault { NONE, REJECT, THROW_BEFORE_WRITE, WRITE_THEN_THROW }

    private class FaultJournal(val memory: MemorySaveJournal = MemorySaveJournal()) : SaveJournal by memory {
        var casFault = CasFault.NONE
        var readFailure: RuntimeException? = null
        var beforeCas: ((SaveRecord) -> Unit)? = null
        var afterWrite: ((SaveRecord) -> Unit)? = null
        var reads = 0
        var casAttempts = 0
        val written = mutableListOf<SaveRecord>()

        override fun read(taskId: String): SaveRecord? {
            reads++
            readFailure?.let { readFailure = null; throw it }
            return memory.read(taskId)
        }

        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            casAttempts++
            beforeCas?.invoke(next)
            val fault = casFault.also { casFault = CasFault.NONE }
            if (fault == CasFault.REJECT) return false
            if (fault == CasFault.THROW_BEFORE_WRITE) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            val changed = memory.compareAndSet(expectedRevision, next)
            if (changed) {
                written += next
                afterWrite?.invoke(next)
                if (fault == CasFault.WRITE_THEN_THROW) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            }
            return changed
        }
    }

    private data class Attempt(val expected: SaveRecord, val operation: OperationContext)

    private class Harness(
        val method: TaskMethod = TaskMethod.DIRECT,
        val journal: FaultJournal = FaultJournal(),
    ) {
        val taskId = "task"
        val clock = FakeSaveClock()
        val limits = SaveLimits(10_000_000, 30_000_000, waitingUserLifetimeMillis = 100)
        val descriptor = CaptureDescriptor(taskId, 7, RecordingAudioChoice.NONE)
        val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/media"))
        val request = if (method == TaskMethod.RECORD) SaveRequest(taskId, "key", SaveInput.Capture(descriptor))
            else SaveRequest(taskId, "key", source, method)
        var directCalls = 0
        var streamCalls = 0
        var captureCalls = 0
        var verifierCalls = 0
        var prepareCalls = 0
        var commitAttempts = 0
        var inspectionCalls = 0
        var networkChecks = 0
        var retryCalls = 0
        var failCommit = false
        var failVerification = false
        var requiresRecovery = false
        var transferFailure: SaveError? = null
        var onTransfer: ((OperationContext) -> Unit)? = null
        var onVerify: ((OperationContext) -> Unit)? = null
        val captureCommands = mutableListOf<String>()
        val store = object : FakeMediaStore() {
            override fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact {
                prepareCalls++
                return super.prepare(taskId, commitId, context)
            }

            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                commitAttempts++
                if (failCommit) throw SaveFailure(SaveError.STORAGE_FULL)
                return super.commit(artifact, context)
            }

            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
                inspectionCalls++
                return super.inspectCommitted(commitId, context)
            }
        }
        private val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)

        private fun completed(artifact: StagingArtifact, recording: Boolean = false): TransferResult = when {
            recording -> TransferResult(artifact.artifactId, 1024, "video/mp4", 1024,
                recordingExpectation = RecordingArtifactExpectation(descriptor, descriptor.requestedProfile,
                    RecordingAudioOutcome.NO_AUDIO_REQUESTED, RecordingStopReason.USER_STOP, video, null,
                    1_000_000, 1024))
            method == TaskMethod.STREAM -> TransferResult(artifact.artifactId, 1024, "video/mp4", 1024,
                StreamArtifactExpectation(2000, 1, 0, 16, 16, testStreamConservation()))
            else -> TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
        }

        private fun transfer(artifact: StagingArtifact, context: OperationContext, progress: TransferProgress): TransferResult {
            onTransfer?.invoke(context)
            transferFailure?.let { throw SaveFailure(it) }
            progress.onBytes(1024)
            return completed(artifact)
        }

        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun requiresProcessRecovery() = requiresRecovery
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
                checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
                progress: TransferProgress): TransferResult {
                directCalls++
                return this@Harness.transfer(artifact, context, progress)
            }
        }, ArtifactVerifier { artifact, result, context ->
            verifierCalls++
            onVerify?.invoke(context)
            if (failVerification) throw SaveFailure(SaveError.STORAGE_FULL)
            when (method) {
                TaskMethod.RECORD -> goodEvidence(artifact, result).copy(durationMillis = 1000,
                    width = descriptor.requestedProfile.width, height = descriptor.requestedProfile.height,
                    recordingProof = RecordingVerificationProof(video, null, "video/avc", null, null, null,
                        video.lastPtsUs, true, decodedResumeBoundaries = emptyList()))
                TaskMethod.STREAM -> goodEvidence(artifact, result).copy(durationMillis = 2000, width = 16, height = 16)
                else -> goodEvidence(artifact, result)
            }
        }, store, clock, limits, SaveRetryDelay { _, _ -> retryCalls++ }, stream = object : StreamSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
                checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
                progress: TransferProgress): TransferResult {
                streamCalls++
                return this@Harness.transfer(artifact, context, progress)
            }
        }, recording = object : RecordSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
                context: OperationContext, limits: SaveLimits): TransferResult {
                captureCalls++
                assertEquals(0, context.retryBudget)
                assertEquals(0, context.attempt)
                assertEquals(requestId, descriptor.sessionId)
                return completed(artifact, recording = true)
            }
            override fun snapshot(requestId: String): RecordingSnapshot? { captureCommands += "snapshot"; return null }
            override fun pause(requestId: String): RecordingSnapshot? { captureCommands += "pause"; return null }
            override fun resume(requestId: String): RecordingSnapshot? { captureCommands += "resume"; return null }
            override fun stopAndSave(requestId: String): RecordingSnapshot? { captureCommands += "stop"; return null }
            override fun cancel(requestId: String): RecordingSnapshot? { captureCommands += "cancel"; return null }
        }, networkGate = SaveNetworkGate { networkChecks++ })

        fun operation(id: String = "resumed", cancellation: CancellationToken = CancellationSource(), attempt: Int = 0) =
            OperationContext(id, 10_000, cancellation, 2, attempt, ResourceBudget(1, limits.maxTotalTransferBytes))

        fun submit() {
            assertTrue(coordinator.submit(request, operation("initial")) is SaveSubmission.Accepted)
        }

        fun pausedResume(operation: OperationContext = operation()): Attempt {
            submit()
            assertEquals(TaskState.WAITING_USER, coordinator.pauseForBackground(taskId)!!.task.state)
            return resume(operation)
        }

        fun finalizedResume(operation: OperationContext = operation(), withVerification: Boolean = true): Attempt {
            failCommit = withVerification
            failVerification = !withVerification
            submit()
            val waiting = coordinator.execute(taskId)!!
            assertEquals(TaskState.WAITING_USER, waiting.task.state)
            assertNotNull(waiting.transfer)
            assertEquals(withVerification, waiting.verification != null)
            failCommit = false
            failVerification = false
            return resume(operation)
        }

        fun resume(operation: OperationContext): Attempt {
            val expected = coordinator.resume(taskId, operation)!!
            assertEquals(TaskState.RUNNING, expected.task.state)
            return Attempt(expected, operation)
        }

        fun repair(attempt: Attempt, waiting: SaveRecord? = null, reason: SaveWaitReason = SaveWaitReason.RECOVERY,
            mayRepair: () -> Boolean = { true }, prepared: (SaveRecord) -> Unit = {}) =
            coordinator.recoverUnadmittedResume(attempt.expected, attempt.operation, waiting, reason, mayRepair, prepared)

        fun durable() = journal.memory.records.getValue(taskId)
        fun workCounts() = listOf(directCalls, streamCalls, captureCalls, verifierCalls, prepareCalls,
            commitAttempts, inspectionCalls, networkChecks, retryCalls, captureCommands.size, store.cleanup.size)
    }

    private fun assertPreserved(expected: SaveRecord, waiting: SaveRecord) {
        assertEquals(TaskState.WAITING_USER, waiting.task.state)
        assertEquals(expected.task.revision + 1, waiting.task.revision)
        assertEquals(expected.copy(task = waiting.task, error = waiting.error, waitReason = waiting.waitReason), waiting)
        assertSame(expected.request, waiting.request)
        assertSame(expected.request.input, waiting.request.input)
        assertSame(expected.artifact, waiting.artifact)
        assertSame(expected.transfer, waiting.transfer)
        assertSame(expected.verification, waiting.verification)
        assertSame(expected.receipt, waiting.receipt)
    }

    private fun assertNoRepair(h: Harness, attempt: Attempt, waiting: SaveRecord? = null) {
        val before = h.durable()
        val attempts = h.journal.casAttempts
        val work = h.workCounts()
        assertFalse(h.repair(attempt, waiting, prepared = { fail("Stale attempt must not prepare a write") }))
        assertEquals(before, h.durable())
        assertEquals(attempts, h.journal.casAttempts)
        assertEquals(work, h.workCounts())
    }

    @Test fun exactReadFailureImmediatelyAfterResumeIsRepairableWithoutStartingAnyWork() {
        val h = Harness()
        h.submit()
        h.coordinator.pauseForBackground(h.taskId)
        h.journal.afterWrite = { next ->
            if (next.operationId == "resumed" && next.task.state == TaskState.RUNNING) {
                h.journal.afterWrite = null
                h.journal.readFailure = SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            }
        }
        val attempt = h.resume(h.operation())
        assertThrows(SaveFailure::class.java) { h.coordinator.record(h.taskId) }
        assertEquals(attempt.expected, h.durable())
        assertEquals(List(11) { 0 }, h.workCounts())
        val writes = h.journal.memory.writes
        var proposal: SaveRecord? = null
        assertTrue(h.repair(attempt, prepared = { proposal = it }))
        assertSame(proposal, h.durable())
        assertPreserved(attempt.expected, h.durable())
        assertEquals(SaveWaitReason.RECOVERY, h.durable().waitReason)
        assertEquals(SaveError.RECOVERY_REQUIRED, h.durable().error)
        assertEquals(ResumeAction.REVIEW_RECOVERY, h.durable().task.waitingUser!!.resumeAction)
        assertEquals(writes + 1, h.journal.memory.writes)
        assertEquals(List(11) { 0 }, h.workCounts())
        assertEquals(h.durable(), h.coordinator.execute(h.taskId))
        assertEquals(List(11) { 0 }, h.workCounts())
    }

    @Test fun callerMustSupplyTheIdenticalResumeOperationNotAnEqualCopy() {
        val h = Harness()
        val attempt = h.pausedResume()
        val copiedOperation = attempt.operation.copy()
        assertEquals(attempt.operation, copiedOperation)
        assertNotSame(attempt.operation, copiedOperation)
        assertNoRepair(h, attempt.copy(operation = copiedOperation))
        assertNoRepair(h, attempt.copy(operation = h.operation(attempt.operation.operationId)))
        assertTrue(h.repair(attempt))
    }

    @Test fun fullRecordComparisonRejectsChangedFieldsAndCallerAdoptedJournalRows() {
        val h = Harness()
        val attempt = h.finalizedResume()
        val original = attempt.expected
        val mutations: List<Pair<String, (SaveRecord) -> SaveRecord>> = listOf(
            "request key" to { it.copy(request = it.request.copy(idempotencyKey = "new-key")) },
            "input" to { it.copy(request = it.request.copy(resource = it.request.resource.copy(variantId = "new-variant"))) },
            "origin" to { it.copy(request = it.request.copy(origin = CommandOrigin.AUTHORIZED_AUTOMATION)) },
            "revision" to { it.copy(task = it.task.copy(revision = it.task.revision + 1)) },
            "state" to { it.copy(task = it.task.copy(state = TaskState.INTERRUPTED)) },
            "progress" to { it.copy(task = it.task.copy(progressPercent = 37)) },
            "task error" to { it.copy(task = it.task.copy(errorCode = ErrorCode.INVALID_STATE)) },
            "operation" to { it.copy(operationId = "new-operation") },
            "deadline" to { it.copy(deadlineEpochMillis = it.deadlineEpochMillis + 1) },
            "retry budget" to { it.copy(retryBudget = 1) },
            "attempt" to { it.copy(attempt = 1) },
            "phase" to { it.copy(phase = SavePhase.VERIFYING) },
            "commit" to { it.copy(commitId = "new-commit", artifact = it.artifact!!.copy(commitId = "new-commit")) },
            "artifact" to { it.copy(artifact = it.artifact!!.copy(artifactId = "new-artifact"),
                transfer = it.transfer!!.copy(artifactId = "new-artifact"),
                verification = it.verification!!.copy(artifactId = "new-artifact")) },
            "transfer" to { it.copy(transfer = it.transfer!!.copy(responseMimeType = "application/mp4")) },
            "verification" to { it.copy(verification = it.verification!!.copy(sha256 = "b".repeat(64))) },
            "receipt" to { it.copy(receipt = MediaCommitReceipt(it.commitId, it.artifact!!.artifactId,
                "media", it.verification!!.sizeBytes, it.verification.sha256)) },
            "bytes" to { it.copy(bytesTransferred = it.bytesTransferred + 1) },
            "checkpoint" to { it.copy(checkpoint = DownloadCheckpoint(it.artifact!!.artifactId,
                it.request.resource.sourceIdentity, 512, "\"etag\"", 1024)) },
            "error" to { it.copy(error = SaveError.STORAGE_FULL) },
            "schema" to { it.copy(schemaVersion = 2) },
            "waiting metadata" to { it.copy(task = it.task.copy(state = TaskState.WAITING_USER,
                waitingUser = UserWait(100, ResumeAction.RETRY)), waitReason = SaveWaitReason.NETWORK) },
        )
        for ((name, mutate) in mutations) {
            val changed = mutate(original)
            assertNotEquals(name, original, changed)
            h.journal.memory.records[h.taskId] = changed
            assertNoRepair(h, attempt)
            // Even a caller replacing its expected row with current data cannot replace runtime.lastRecord.
            assertNoRepair(h, attempt.copy(expected = changed))
            h.journal.memory.records[h.taskId] = original
        }
        assertTrue(h.repair(attempt))
        assertPreserved(original, h.durable())
    }

    @Test fun unrelatedTaskAndMethodCannotBorrowAnotherResumeOperation() {
        val h = Harness()
        val attempt = h.pausedResume()
        val unrelated = attempt.expected.copy(request = attempt.expected.request.copy(requestId = "other"),
            task = attempt.expected.task.copy(taskId = "other"))
        h.journal.memory.records["other"] = unrelated
        assertNoRepair(h, attempt.copy(expected = unrelated))
        assertEquals(unrelated, h.journal.memory.records["other"])
        val changedMethod = attempt.expected.copy(request = attempt.expected.request.copy(method = TaskMethod.STREAM),
            task = attempt.expected.task.copy(method = TaskMethod.STREAM))
        h.journal.memory.records[h.taskId] = changedMethod
        assertNoRepair(h, attempt)
        assertNoRepair(h, attempt.copy(expected = changedMethod))
    }

    @Test fun newerRevisionAndResumeOperationLeaveOldRecoveryUntouched() {
        val h = Harness()
        val old = h.pausedResume()
        val newerWait = h.coordinator.pauseForBackground(h.taskId)!!
        assertNoRepair(h, old)
        val newer = h.resume(h.operation("newer-resume"))
        assertTrue(newer.expected.task.revision > newerWait.task.revision)
        assertNoRepair(h, old)
        // A stale durable row alone cannot make a replaced runtime own the old caller again.
        h.journal.memory.records[h.taskId] = old.expected
        assertNoRepair(h, old)
        h.journal.memory.records[h.taskId] = newer.expected
        assertTrue(h.repair(newer))
    }

    @Test fun absentRuntimeCannotBeRecreatedByCompensationAndRestartRecoveryRemainsExplicit() {
        val original = Harness()
        val attempt = original.pausedResume()
        val restarted = Harness(journal = original.journal)
        val reads = restarted.journal.reads
        assertNoRepair(restarted, attempt)
        assertEquals(reads, restarted.journal.reads)
        val recovered = restarted.coordinator.recover(restarted.operation("restart-recovery")).single()
        assertEquals(TaskState.WAITING_USER, recovered.task.state)
        assertEquals(SaveWaitReason.RECOVERY, recovered.waitReason)
        assertEquals(List(11) { 0 }, restarted.workCounts())
        assertNoRepair(restarted, attempt)
        assertEquals(recovered, restarted.coordinator.execute(restarted.taskId))
        assertEquals(List(11) { 0 }, restarted.workCounts())
    }

    @Test fun missingJournalRowCannotBeRestoredFromTheExpectedRecord() {
        val h = Harness()
        val attempt = h.pausedResume()
        h.journal.memory.records.remove(h.taskId)
        val writes = h.journal.casAttempts
        assertFalse(h.repair(attempt, prepared = { fail("Missing task must not prepare a write") }))
        assertNull(h.journal.memory.records[h.taskId])
        assertEquals(writes, h.journal.casAttempts)
        assertEquals(List(11) { 0 }, h.workCounts())
    }

    @Test fun everyDurableTerminalStateRejectsLateCompensation() {
        for (terminal in listOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)) {
            val h = Harness()
            val attempt = h.pausedResume()
            when (terminal) {
                TaskState.CANCELLED -> h.coordinator.cancel(h.taskId)
                TaskState.FAILED -> {
                    h.transferFailure = SaveError.TLS_REJECTED
                    h.coordinator.execute(h.taskId)
                }
                else -> h.coordinator.execute(h.taskId)
            }
            assertEquals(terminal, h.durable().task.state)
            assertNoRepair(h, attempt)
        }
    }

    @Test fun activeWorkerCannotBePausedOrCancelledByAnUnadmittedRepair() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val h = Harness()
        val attempt = h.pausedResume()
        h.onTransfer = { context ->
            entered.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            assertFalse(context.cancelToken.isCancelled)
        }
        val failure = AtomicReference<Throwable?>()
        val worker = thread { try { h.coordinator.execute(h.taskId) } catch (error: Throwable) { failure.set(error) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNoRepair(h, attempt)
            assertEquals(TaskState.RUNNING, h.durable().task.state)
            assertFalse(attempt.operation.cancelToken.isCancelled)
        } finally { finish.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("Worker failed", it) }
        assertEquals(TaskState.SUCCEEDED, h.durable().task.state)
        assertEquals(1, h.directCalls)
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun journalReadFailurePropagatesWithoutPreparingOrWritingAWait() {
        val h = Harness()
        val attempt = h.pausedResume()
        val problem = SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        h.journal.readFailure = problem
        val attempts = h.journal.casAttempts
        assertSame(problem, assertThrows(SaveFailure::class.java) {
            h.repair(attempt, prepared = { fail("A failed read cannot prepare a durable result") })
        })
        assertEquals(attempt.expected, h.durable())
        assertEquals(attempts, h.journal.casAttempts)
        assertEquals(List(11) { 0 }, h.workCounts())
        assertTrue(h.repair(attempt))
    }

    @Test fun rejectedAndFailedWritesReuseTheExactPreparedTargetWithoutExtendingItsWait() {
        for (fault in listOf(CasFault.REJECT, CasFault.THROW_BEFORE_WRITE)) {
            for (reason in listOf(SaveWaitReason.BACKGROUND, SaveWaitReason.NETWORK, SaveWaitReason.RECOVERY)) {
                val h = Harness()
                val attempt = h.pausedResume()
                h.journal.casFault = fault
                var proposal: SaveRecord? = null
                val attempts = h.journal.casAttempts
                assertEquals(SaveError.JOURNAL_UNAVAILABLE, assertThrows(SaveFailure::class.java) {
                    h.repair(attempt, reason = reason, prepared = { proposal = it })
                }.code)
                assertEquals(attempts + 1, h.journal.casAttempts)
                assertEquals(attempt.expected, h.durable())
                val exactTarget = requireNotNull(proposal)
                h.clock.now = 50
                h.coordinator.signalBackgroundPauseAll()
                assertTrue(h.repair(attempt, exactTarget, reason = SaveWaitReason.NETWORK,
                    prepared = { assertSame(exactTarget, it) }))
                assertSame(exactTarget, h.durable())
                assertEquals(100L, h.durable().task.waitingUser!!.expiresAtEpochMillis)
                assertEquals(reason, h.durable().waitReason)
                assertEquals(attempts + 2, h.journal.casAttempts)
                assertEquals(List(11) { 0 }, h.workCounts())
            }
        }
    }

    @Test fun writeThenThrowReconcilesOnlyTheExactOwnedWaitWithoutAnotherCasOrWaitExtension() {
        for (reason in listOf(SaveWaitReason.BACKGROUND, SaveWaitReason.NETWORK, SaveWaitReason.RECOVERY)) {
            val h = Harness()
            val attempt = h.pausedResume()
            h.journal.casFault = CasFault.WRITE_THEN_THROW
            var proposal: SaveRecord? = null
            assertThrows(SaveFailure::class.java) { h.repair(attempt, reason = reason, prepared = { proposal = it }) }
            val exactTarget = requireNotNull(proposal)
            assertSame(exactTarget, h.durable())
            assertEquals(attempt.expected.task, h.coordinator.snapshot(h.taskId))
            val attempts = h.journal.casAttempts
            h.clock.now = 75
            repeat(3) {
                assertTrue(h.repair(attempt, exactTarget, mayRepair = { fail("Reconciliation must not authorize a new CAS"); false },
                    prepared = { fail("Reconciliation must not prepare another target") }))
            }
            assertSame(exactTarget, h.durable())
            assertEquals(exactTarget.task, h.coordinator.snapshot(h.taskId))
            assertEquals(100L, h.durable().task.waitingUser!!.expiresAtEpochMillis)
            assertEquals(attempts, h.journal.casAttempts)
            assertEquals(List(11) { 0 }, h.workCounts())
        }
    }

    @Test fun aDifferentWaitCannotBeMistakenForTheOwnedAmbiguousWrite() {
        val h = Harness()
        val attempt = h.pausedResume()
        h.journal.casFault = CasFault.REJECT
        var proposal: SaveRecord? = null
        assertThrows(SaveFailure::class.java) { h.repair(attempt, prepared = { proposal = it }) }
        val different = h.coordinator.pauseForBackground(h.taskId)!!
        assertNotEquals(proposal, different)
        assertNoRepair(h, attempt, proposal)
    }

    @Test fun malformedCachedProposalsCannotBeReappliedOrReconciledEvenIfTheJournalContainsThem() {
        val h = Harness()
        val attempt = h.finalizedResume()
        h.journal.casFault = CasFault.REJECT
        var proposal: SaveRecord? = null
        assertThrows(SaveFailure::class.java) { h.repair(attempt, prepared = { proposal = it }) }
        val owned = requireNotNull(proposal)
        val malformed: List<SaveRecord> = listOf(
            owned.copy(request = owned.request.copy(idempotencyKey = "changed-payload")),
            owned.copy(phase = SavePhase.COMMITTING),
            owned.copy(transfer = owned.transfer!!.copy(responseMimeType = "application/mp4")),
            owned.copy(verification = owned.verification!!.copy(sha256 = "b".repeat(64))),
            owned.copy(bytesTransferred = owned.bytesTransferred + 1),
            owned.copy(operationId = "different-operation"),
            owned.copy(task = owned.task.copy(revision = owned.task.revision + 1)),
            owned.copy(task = owned.task.copy(progressPercent = 13)),
            owned.copy(task = owned.task.copy(errorCode = ErrorCode.INVALID_STATE)),
            owned.copy(schemaVersion = 2),
            owned.copy(waitReason = SaveWaitReason.STORAGE),
            owned.copy(waitReason = SaveWaitReason.BACKGROUND),
            owned.copy(waitReason = SaveWaitReason.NETWORK),
            owned.copy(error = SaveError.STORAGE_FULL),
            owned.copy(task = owned.task.copy(waitingUser = owned.task.waitingUser!!.copy(resumeAction = ResumeAction.RETRY))),
            owned.copy(task = owned.task.copy(waitingUser = owned.task.waitingUser!!.copy(resumeAction = ResumeAction.RESTORE_PERMISSION))),
            // Its metadata is a legal network wait, but this exact artifact no longer needs transfer.
            owned.copy(waitReason = SaveWaitReason.NETWORK, error = SaveError.WIFI_REQUIRED,
                task = owned.task.copy(errorCode = ErrorCode.NETWORK_UNAVAILABLE,
                    waitingUser = owned.task.waitingUser!!.copy(resumeAction = ResumeAction.RETRY))),
            attempt.expected,
        )
        for (candidate in malformed) {
            h.journal.memory.records[h.taskId] = attempt.expected
            assertNoRepair(h, attempt, candidate)
            h.journal.memory.records[h.taskId] = candidate
            assertNoRepair(h, attempt, candidate)
            assertEquals(attempt.expected.task, h.coordinator.snapshot(h.taskId))
        }
        h.journal.memory.records[h.taskId] = attempt.expected
        assertTrue(h.repair(attempt, owned))
        assertSame(owned, h.durable())
    }

    @Test fun recordingRejectsCachedBackgroundAndNetworkWaitsDespiteOtherwiseMatchingMetadata() {
        val h = Harness(TaskMethod.RECORD)
        val attempt = h.finalizedResume()
        h.journal.casFault = CasFault.REJECT
        var proposal: SaveRecord? = null
        assertThrows(SaveFailure::class.java) { h.repair(attempt, prepared = { proposal = it }) }
        val owned = requireNotNull(proposal)
        for (reason in listOf(SaveWaitReason.BACKGROUND, SaveWaitReason.NETWORK)) {
            val candidate = owned.copy(waitReason = reason,
                error = if (reason == SaveWaitReason.BACKGROUND) null else SaveError.WIFI_REQUIRED,
                task = owned.task.copy(errorCode = if (reason == SaveWaitReason.BACKGROUND) null else ErrorCode.NETWORK_UNAVAILABLE,
                    waitingUser = owned.task.waitingUser!!.copy(resumeAction = ResumeAction.RETRY)))
            h.journal.memory.records[h.taskId] = attempt.expected
            assertNoRepair(h, attempt, candidate)
            h.journal.memory.records[h.taskId] = candidate
            assertNoRepair(h, attempt, candidate)
            assertEquals(attempt.expected.task, h.coordinator.snapshot(h.taskId))
        }
        h.journal.memory.records[h.taskId] = attempt.expected
        assertTrue(h.repair(attempt, owned))
    }

    @Test fun authorizationIsCheckedAfterPreparingTheProposalAndImmediatelyBeforeCas() {
        val h = Harness()
        val attempt = h.pausedResume()
        val events = mutableListOf<String>()
        val writes = h.journal.casAttempts
        assertFalse(h.repair(attempt, mayRepair = { events += "authorization"; false },
            prepared = { events += "prepared"; assertPreserved(attempt.expected, it) }))
        assertEquals(listOf("prepared", "authorization"), events)
        assertEquals(writes, h.journal.casAttempts)
        assertEquals(attempt.expected, h.durable())
        assertEquals(List(11) { 0 }, h.workCounts())
    }

    @Test fun cancelRetirementBeforeConditionalAuthorizationPreventsWaitingAndNeverResurrectsTerminal() {
        val h = Harness()
        val cancellation = CancellationSource()
        val attempt = h.pausedResume(h.operation(cancellation = cancellation))
        val prepared = CountDownLatch(1)
        val allowAuthorization = CountDownLatch(1)
        val live = AtomicBoolean(true)
        val result = AtomicReference<Boolean?>()
        val problem = AtomicReference<Throwable?>()
        val proposal = AtomicReference<SaveRecord?>()
        val writes = h.journal.casAttempts
        val repair = thread {
            try {
                result.set(h.repair(attempt, mayRepair = { live.get() }, prepared = {
                    proposal.set(it)
                    prepared.countDown()
                    assertTrue(allowAuthorization.await(5, TimeUnit.SECONDS))
                }))
            } catch (error: Throwable) { problem.set(error) }
        }
        try {
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            live.set(false) // The app's memory-only ticket retirement precedes its durable Cancel command.
            cancellation.cancel()
            h.coordinator.signalUserCancel(h.taskId)
            assertEquals(attempt.expected, h.durable())
        } finally { allowAuthorization.countDown(); repair.join(5000) }
        assertFalse(repair.isAlive)
        problem.get()?.let { throw AssertionError("Repair failed", it) }
        assertEquals(false, result.get())
        assertEquals(writes, h.journal.casAttempts)
        assertEquals(TaskState.CANCELLED, h.coordinator.cancel(h.taskId)!!.state)
        val terminal = h.durable()
        assertNoRepair(h, attempt, proposal.get())
        assertEquals(terminal, h.durable())
        assertFalse(h.journal.written.dropWhile { it != attempt.expected }.drop(1).any { it.task.state == TaskState.WAITING_USER })
    }

    @Test fun repairAlreadyInsideCasMayFinishWaitingBeforeQueuedCancelAndCannotResurrectItLater() {
        val h = Harness()
        val cancellation = CancellationSource()
        val attempt = h.pausedResume(h.operation(cancellation = cancellation))
        val insideCas = CountDownLatch(1)
        val finishCas = CountDownLatch(1)
        val live = AtomicBoolean(true)
        val result = AtomicReference<Boolean?>()
        val problem = AtomicReference<Throwable?>()
        val proposal = AtomicReference<SaveRecord?>()
        val writtenBefore = h.journal.written.size
        h.journal.beforeCas = { next ->
            assertEquals(TaskState.WAITING_USER, next.task.state)
            h.journal.beforeCas = null
            insideCas.countDown()
            assertTrue(finishCas.await(5, TimeUnit.SECONDS))
        }
        val repair = thread {
            try { result.set(h.repair(attempt, mayRepair = { live.get() }, prepared = { proposal.set(it) })) }
            catch (error: Throwable) { problem.set(error) }
        }
        try {
            assertTrue(insideCas.await(5, TimeUnit.SECONDS))
            live.set(false)
            cancellation.cancel()
            h.coordinator.signalUserCancel(h.taskId)
            assertEquals(attempt.expected, h.durable())
        } finally { finishCas.countDown(); repair.join(5000) }
        assertFalse(repair.isAlive)
        problem.get()?.let { throw AssertionError("Repair failed", it) }
        assertEquals(true, result.get())
        assertSame(proposal.get(), h.durable())
        assertEquals(TaskState.CANCELLED, h.coordinator.cancel(h.taskId)!!.state)
        assertEquals(listOf(TaskState.WAITING_USER, TaskState.CANCELLED),
            h.journal.written.drop(writtenBefore).map { it.task.state })
        val terminal = h.durable()
        assertNoRepair(h, attempt, proposal.get())
        assertEquals(terminal, h.durable())
        assertEquals(List(11) { 0 }, h.workCounts())
    }

    @Test fun cancellationTokenOrImmediateUserSignalAlonePreventsARepairWrite() {
        for (signalRuntime in listOf(false, true)) {
            val h = Harness()
            val cancellation = CancellationSource()
            val attempt = h.pausedResume(h.operation(cancellation = cancellation))
            if (signalRuntime) h.coordinator.signalUserCancel(h.taskId) else cancellation.cancel()
            assertNoRepair(h, attempt)
            assertEquals(TaskState.RUNNING, h.durable().task.state)
            assertEquals(TaskState.CANCELLED, h.coordinator.cancel(h.taskId)!!.state)
        }
    }

    @Test fun backgroundAndNetworkDeferralsPreserveTheirExistingReasonsWithoutMediaWork() {
        for (reason in listOf(SaveWaitReason.BACKGROUND, SaveWaitReason.NETWORK, SaveWaitReason.RECOVERY)) {
            val h = Harness()
            val attempt = h.pausedResume()
            if (reason == SaveWaitReason.BACKGROUND) h.coordinator.signalBackgroundPauseAll()
            assertTrue(h.repair(attempt, reason = reason))
            assertEquals(reason, h.durable().waitReason)
            assertEquals(when (reason) {
                SaveWaitReason.BACKGROUND -> null
                SaveWaitReason.NETWORK -> SaveError.WIFI_REQUIRED
                else -> SaveError.RECOVERY_REQUIRED
            }, h.durable().error)
            assertPreserved(attempt.expected, h.durable())
            assertFalse(attempt.operation.cancelToken.isCancelled)
            assertEquals(List(11) { 0 }, h.workCounts())
        }
    }

    @Test fun anImmediateBackgroundSignalTakesPriorityOverAStaleNetworkOrRecoveryReason() {
        for (reason in listOf(SaveWaitReason.NETWORK, SaveWaitReason.RECOVERY)) {
            val h = Harness()
            val attempt = h.pausedResume()
            h.coordinator.signalBackgroundPauseAll()
            assertTrue(h.repair(attempt, reason = reason))
            assertEquals(SaveWaitReason.BACKGROUND, h.durable().waitReason)
            assertNull(h.durable().error)
            assertEquals(ResumeAction.RETRY, h.durable().task.waitingUser!!.resumeAction)
            assertPreserved(attempt.expected, h.durable())
            assertEquals(List(11) { 0 }, h.workCounts())
        }
    }

    @Test fun unreleasedNativeResourcesForceRecoveryWithoutCleanupOrNewWork() {
        val h = Harness()
        val attempt = h.finalizedResume()
        h.requiresRecovery = true
        h.coordinator.signalBackgroundPauseAll()
        val counts = h.workCounts()
        assertTrue(h.repair(attempt, reason = SaveWaitReason.NETWORK))
        assertEquals(SaveWaitReason.RECOVERY, h.durable().waitReason)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.durable().error)
        assertPreserved(attempt.expected, h.durable())
        assertEquals(counts, h.workCounts())
        val waiting = h.durable()
        assertEquals(waiting, h.coordinator.resume(h.taskId, h.operation("still-fenced")))
        assertEquals(waiting, h.coordinator.execute(h.taskId))
        assertEquals(counts, h.workCounts())
    }

    @Test fun executingLocalVerificationIsNotInterruptedByAStaleRepairForAnySaveMethod() {
        for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD)) {
            val h = Harness(method)
            val attempt = h.finalizedResume()
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            h.onVerify = { context ->
                entered.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
                assertFalse(context.cancelToken.isCancelled)
            }
            val problem = AtomicReference<Throwable?>()
            val worker = thread {
                try { h.coordinator.execute(h.taskId) }
                catch (error: Throwable) { problem.set(error) }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(SavePhase.VERIFYING, h.durable().phase)
                assertNoRepair(h, attempt)
                assertTrue(h.captureCommands.isEmpty())
            } finally { finish.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive)
            problem.get()?.let { throw AssertionError("Local verification failed", it) }
            assertEquals(TaskState.SUCCEEDED, h.durable().task.state)
            assertEquals(1, h.directCalls + h.streamCalls + h.captureCalls)
            assertEquals(2, h.verifierCalls)
            assertEquals(1, h.store.commitCalls)
        }
    }

    @Test fun finalizedRecordingKeepsExactIdentityAndZeroRetryWithTheOriginalCallerOperation() {
        for (reason in listOf(SaveWaitReason.BACKGROUND, SaveWaitReason.NETWORK, SaveWaitReason.RECOVERY)) {
            val h = Harness(TaskMethod.RECORD)
            val operation = h.operation(attempt = 1)
            val attempt = h.finalizedResume(operation)
            assertEquals(2, operation.retryBudget)
            assertEquals(1, operation.attempt)
            assertEquals(0, attempt.expected.retryBudget)
            assertEquals(0, attempt.expected.attempt)
            assertNotNull(attempt.expected.verification)
            assertNoRepair(h, attempt.copy(operation = operation.copy(retryBudget = 0, attempt = 0)))
            assertNoRepair(h, attempt.copy(operation = operation.copy()))
            val counts = h.workCounts()
            assertTrue(h.repair(attempt, reason = reason))
            val waiting = h.durable()
            assertPreserved(attempt.expected, waiting)
            assertEquals(SaveWaitReason.RECOVERY, waiting.waitReason)
            assertEquals(SaveError.RECOVERY_REQUIRED, waiting.error)
            assertEquals(0, waiting.retryBudget)
            assertEquals(0, waiting.attempt)
            assertEquals(0L, waiting.bytesTransferred)
            assertNull(waiting.checkpoint)
            assertEquals(counts, h.workCounts())
            assertEquals(waiting, h.coordinator.execute(h.taskId))
            assertEquals(counts, h.workCounts())
            h.resume(h.operation("explicit-reverify", attempt = 2))
            val completed = h.coordinator.execute(h.taskId)!!
            assertEquals(TaskState.SUCCEEDED, completed.task.state)
            assertSame(waiting.request, completed.request)
            assertSame(waiting.artifact, completed.artifact)
            assertSame(waiting.transfer, completed.transfer)
            assertEquals(waiting.commitId, completed.commitId)
            assertEquals(1, h.captureCalls)
            assertEquals(0, h.directCalls)
            assertEquals(0, h.streamCalls)
            assertEquals(0, h.networkChecks)
            assertEquals(0, h.retryCalls)
            assertEquals(2, h.verifierCalls)
            assertEquals(1, h.store.commitCalls)
            assertTrue(h.captureCommands.isEmpty())
        }
    }

    @Test fun completedDirectAndStreamRepairLocallyAndRequireSeparateExplicitResumeWithoutRetransfer() {
        for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM)) {
            val h = Harness(method)
            val attempt = h.finalizedResume(withVerification = false)
            val counts = h.workCounts()
            assertFalse(attempt.expected.needsNetworkTransfer())
            assertTrue(h.repair(attempt, reason = SaveWaitReason.NETWORK))
            val waiting = h.durable()
            assertPreserved(attempt.expected, waiting)
            assertEquals(SaveWaitReason.RECOVERY, waiting.waitReason)
            assertEquals(counts, h.workCounts())
            assertEquals(waiting, h.coordinator.execute(h.taskId))
            assertEquals(counts, h.workCounts())
            h.resume(h.operation("explicit-local-continuation"))
            assertEquals(TaskState.SUCCEEDED, h.coordinator.execute(h.taskId)!!.task.state)
            assertEquals(if (method == TaskMethod.DIRECT) 1 else 0, h.directCalls)
            assertEquals(if (method == TaskMethod.STREAM) 1 else 0, h.streamCalls)
            assertEquals(0, h.captureCalls)
            assertEquals(1, h.prepareCalls)
            assertEquals(1, h.networkChecks)
            assertEquals(2, h.verifierCalls)
            assertEquals(1, h.store.commitCalls)
            assertSame(waiting.transfer, h.durable().transfer)
            assertEquals(waiting.commitId, h.durable().commitId)
        }
    }
}
