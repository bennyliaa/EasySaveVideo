package com.easysavevideo.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingAudioChoice
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real production Coordinator/admission calls with bounded journal and transfer actors. */
class StreamAlternativeCoordinatorTest {
    private enum class InsertFault { NONE, THROW_BEFORE_WRITE, WRITE_THEN_THROW }
    private class FaultJournal(val memory: MemorySaveJournal = MemorySaveJournal()) : SaveJournal by memory {
        var fault = InsertFault.NONE
        var readFailure = false
        var beforeRead: (() -> Unit)? = null
        var insertCalls = 0
        override fun read(taskId: String): SaveRecord? {
            if (readFailure) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            beforeRead?.invoke()
            return memory.read(taskId)
        }
        override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord {
            insertCalls++
            val next = fault.also { fault = InsertFault.NONE }
            if (next == InsertFault.THROW_BEFORE_WRITE) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            val value = memory.insert(record, maxEntries)
            if (next == InsertFault.WRITE_THEN_THROW) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            return value
        }
    }
    private class Harness {
        val journal = FaultJournal()
        val clock = FakeSaveClock(100)
        val limits = SaveLimits(1024, 2048, waitingUserLifetimeMillis = 1000, sourceContextLifetimeMillis = 1000)
        var streamAvailable = true
        var directRecovery = false
        var streamRecovery = false
        var recordRecovery = false
        var verifierRecovery = false
        var directCalls = 0
        var streamCalls = 0
        var recordCalls = 0
        var verifierCalls = 0
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes,
            resourcesUnreleased = { directRecovery || streamRecovery || recordRecovery || verifierRecovery })
        val store = FakeMediaStore()
        val direct = object : DirectTransferPort {
            override fun requiresProcessRecovery() = directRecovery
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                directCalls++
                throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
            }
        }
        val stream = object : StreamSavePort {
            override fun availability() = SaveMethodAvailability(streamAvailable, if (streamAvailable) null else SaveError.METHOD_UNSUPPORTED)
            override fun requiresProcessRecovery() = streamRecovery
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                streamCalls++; progress.onBytes(100)
                return TransferResult(artifact.artifactId, 400, "video/mp4", 400,
                    StreamArtifactExpectation(2000, 1, 0, 16, 16, testStreamConservation()))
            }
        }
        val verifier = object : ArtifactVerifier {
            override fun requiresProcessRecovery() = verifierRecovery
            override fun verify(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext): VerificationEvidence {
                verifierCalls++
                return goodEvidence(artifact, transfer).copy(durationMillis = 2000, width = 16, height = 16)
            }
        }
        val recorder = object : RecordSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun requiresProcessRecovery() = recordRecovery
            override fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
                context: OperationContext, limits: SaveLimits): TransferResult { recordCalls++; throw AssertionError("NO_CAPTURE") }
        }
        fun coordinator() = SaveCoordinator(journal, direct, verifier, store, clock, limits,
            stream = stream, recording = recorder, admission = admission)
        val coordinator = coordinator()
        val oldRequest = SaveRequest("old-direct", "old-key",
            SelectedMediaResource("old-candidate", "old-variant", SourceUrl("https://example.invalid/old.mp4")))
        val oldOperation = OperationContext("old-operation", 5000, CancellationSource(), resourceBudget = ResourceBudget(1, 2048))
        val old: SaveRecord
        val cancellation = CancellationSource()
        val operation = OperationContext("stream-operation", 5000, cancellation, resourceBudget = ResourceBudget(1, 2048))
        val source = SelectedMediaResource("fresh-candidate", "unselected", SourceUrl("https://example.invalid/master.m3u8?signature=private"),
            requestContextRef = RequestContextRef("fresh-lease"), expiresAtEpochMillis = 2000)
        val request = SaveRequest("new-stream", "new-key", source.copy(variantId = "selected-variant"), TaskMethod.STREAM)
        val generation = 7L
        init {
            assertTrue(coordinator.submit(oldRequest, oldOperation) is SaveSubmission.Accepted)
            old = coordinator.execute(oldRequest.requestId)!!
            assertEquals(TaskState.FAILED, old.task.state)
            assertEquals(SaveError.UNSUPPORTED_MEDIA, old.error)
        }
        fun prepare(failed: SaveFailedIdentity = SaveFailedIdentity.from(old), source: SelectedMediaResource = this.source,
            operation: OperationContext = this.operation, generation: Long = this.generation, expiry: Long = 1000) =
            coordinator.prepareStreamAlternative(failed, generation, source, operation, expiry)
        fun proposal() = (prepare() as SaveAlternativePreparation.Ready).proposal
        fun claim() = requireNotNull(admission.tryAcquireExclusive())
        fun confirm(proposal: StreamAlternativeProposal, claim: SaveExecutionAdmission.Claim, request: SaveRequest = this.request,
            operation: OperationContext = this.operation, generation: Long = this.generation, bytes: Long = 50) =
            coordinator.confirmStreamAlternative(proposal, generation, request, operation, claim, bytes)
        fun assertOldUntouched() {
            assertSame(old, journal.memory.records[old.task.taskId])
            assertEquals(old, journal.memory.records[old.task.taskId])
            assertEquals(1, directCalls); assertEquals(0, recordCalls)
        }
        fun assertNoNewTask() {
            assertEquals(listOf(old), journal.memory.records.values.toList())
            assertEquals(0, streamCalls); assertEquals(0, verifierCalls); assertEquals(0, store.commitCalls)
            assertOldUntouched()
        }
    }
    private fun rejected(value: SaveAlternativeConfirmation, reason: SaveAlternativeRejection, error: SaveError? = null) {
        assertEquals(SaveAlternativeConfirmation.Rejected(reason, error), value)
    }

    @Test fun prepareAndConfirmNeverExecuteAndPreserveTheOldFailureByteForByte() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        h.assertNoNewTask()
        val result = h.confirm(proposal, claim) as SaveAlternativeConfirmation.Accepted
        assertEquals(h.request.asDurableReference(), result.record.request)
        assertEquals(TaskState.QUEUED, result.record.task.state)
        assertEquals(50L, result.record.bytesTransferred)
        assertFalse(result.record.request.resource.url.value.contains("signature"))
        assertEquals(0, h.streamCalls); assertEquals(0, h.verifierCalls)
        h.assertOldUntouched()
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute(h.request.requestId, claim)!!.task.state)
        assertTrue(h.admission.release(claim))
        assertEquals(1, h.streamCalls); assertEquals(1, h.verifierCalls); assertEquals(1, h.store.commitCalls)
        h.assertOldUntouched()
    }

    @Test fun bothExplicitAllowedErrorsPrepareAgainstTheirExactCurrentIdentity() {
        for (error in listOf(SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED)) {
            val h = Harness(); val old = h.old.copy(error = error)
            h.journal.memory.records[old.task.taskId] = old
            assertTrue(h.prepare(SaveFailedIdentity.from(old)) is SaveAlternativePreparation.Ready)
            assertEquals(old, h.journal.memory.records[old.task.taskId])
        }
    }

    @Test fun coordinatorRejectsEveryOtherStateMethodAndErrorWithoutMutation() {
        val h = Harness()
        for (state in TaskState.entries) for (method in TaskMethod.entries) for (error in listOf(null) + SaveError.entries) {
            val failed = SaveFailedIdentity(h.old.task.taskId, h.old.task.revision, state, method, error)
            if (SaveMethodAlternatives.review(failed) == SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE) continue
            assertEquals("$state/$method/$error", SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST), h.prepare(failed))
        }
        h.assertNoNewTask()
    }

    @Test fun staleMissingOrChangedFailureCannotIssueAProposal() {
        val h = Harness()
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST),
            h.prepare(SaveFailedIdentity.from(h.old).copy(revision = h.old.task.revision + 1)))
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST),
            h.prepare(SaveFailedIdentity.from(h.old).copy(taskId = "missing")))
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST),
            h.prepare(SaveFailedIdentity.from(h.old).copy(error = SaveError.METHOD_UNSUPPORTED)))
        h.assertNoNewTask()
    }

    @Test fun preparationRequiresFreshLiveActualSourceAndOperation() {
        val h = Harness()
        for (result in listOf(h.prepare(source = h.source.asDurableReference()), h.prepare(generation = -1),
            h.prepare(operation = h.oldOperation), h.prepare(operation = h.operation.copy(resourceBudget = ResourceBudget())),
            h.prepare(operation = h.operation.copy(resourceBudget = ResourceBudget(2, 2048)))))
            assertEquals(SaveAlternativePreparation.Rejected(SaveError.INVALID_REQUEST), result)
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.EXPIRED_RESOURCE), h.prepare(expiry = h.clock.now))
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.EXPIRED_RESOURCE), h.prepare(source = h.source.copy(expiresAtEpochMillis = 100)))
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.DEADLINE_EXCEEDED), h.prepare(operation = h.operation.copy(deadlineEpochMillis = 100)))
        h.cancellation.cancel()
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.CANCELLED), h.prepare())
        h.assertNoNewTask()
    }

    @Test fun availabilityAndEveryResourceRecoveryFenceAreCheckedAtPreparationAndConfirmation() {
        for (setFence in listOf<(Harness) -> Unit>({ it.directRecovery = true }, { it.streamRecovery = true },
            { it.recordRecovery = true }, { it.verifierRecovery = true }, { it.admission.markRecoveryRequired() })) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal(); setFence(h)
            assertEquals(SaveAlternativePreparation.Rejected(SaveError.RESOURCE_RELEASE_UNCONFIRMED), h.prepare())
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.SUBMISSION_REJECTED, SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            assertFalse(h.admission.release(claim)); h.assertNoNewTask()
        }
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal(); h.streamAvailable = false
        assertEquals(SaveAlternativePreparation.Rejected(SaveError.METHOD_UNSUPPORTED), h.prepare())
        rejected(h.confirm(proposal, claim), SaveAlternativeRejection.SUBMISSION_REJECTED, SaveError.METHOD_UNSUPPORTED)
        h.assertNoNewTask()
    }

    @Test fun foreignCoordinatorCannotConsumeOrSubmitAnOwnedProposal() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        rejected(h.coordinator().confirmStreamAlternative(proposal, h.generation, h.request, h.operation, claim),
            SaveAlternativeRejection.FOREIGN_PROPOSAL)
        h.assertNoNewTask()
        assertTrue(h.confirm(proposal, claim) is SaveAlternativeConfirmation.Accepted)
        h.assertOldUntouched()
    }

    @Test fun revokedAndExpiredProposalsAreConsumedEvenWhenRejected() {
        for (revoke in listOf(false, true)) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            if (revoke) proposal.revoke() else h.clock.now = 1000
            if (revoke) rejected(h.confirm(proposal, claim), SaveAlternativeRejection.REVOKED)
            else rejected(h.confirm(proposal, claim), SaveAlternativeRejection.EXPIRED, SaveError.EXPIRED_RESOURCE)
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.ALREADY_USED)
            h.assertNoNewTask()
        }
    }

    @Test fun proposalExpiryIsTheEarliestSourcePreparationDeadlineAndCoreLifetime() {
        for ((sourceExpiry, deadline, expiry, expected) in listOf(
            listOf(200L, 5000L, 1000L, 200L), listOf(2000L, 300L, 1000L, 300L),
            listOf(2000L, 5000L, 400L, 400L), listOf(5000L, 5000L, 5000L, 1100L))) {
            val h = Harness(); val claim = h.claim()
            val source = h.source.copy(expiresAtEpochMillis = sourceExpiry)
            val operation = h.operation.copy(deadlineEpochMillis = deadline)
            val proposal = (h.prepare(source = source, operation = operation, expiry = expiry) as SaveAlternativePreparation.Ready).proposal
            h.clock.now = expected
            rejected(h.confirm(proposal, claim, h.request.copy(resource = source), operation),
                SaveAlternativeRejection.EXPIRED, SaveError.EXPIRED_RESOURCE)
            h.assertNoNewTask()
        }
    }

    @Test fun exactSourceLeaseExpiryGenerationAndOperationAreBound() {
        val mutations = listOf<(Harness) -> SaveRequest>(
            { it.request.copy(resource = it.request.resource.copy(candidateId = "other-candidate")) },
            { it.request.copy(resource = it.request.resource.copy(url = SourceUrl("https://example.invalid/other.m3u8"),
                sourceIdentity = SourceUrl("https://example.invalid/other.m3u8").identity())) },
            { it.request.copy(resource = it.request.resource.copy(requestContextRef = RequestContextRef("other-lease"))) },
            { it.request.copy(resource = it.request.resource.copy(expiresAtEpochMillis = 2001)) },
            { it.request.copy(resource = it.request.resource.asDurableReference()) },
            { it.request.copy(origin = CommandOrigin.AUTHORIZED_AUTOMATION) },
            { it.request.copy(method = TaskMethod.DIRECT) },
            { SaveRequest("new-stream", "new-key", SaveInput.Capture(CaptureDescriptor("new-stream", 7, RecordingAudioChoice.NONE))) },
        )
        for (mutate in mutations) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            rejected(h.confirm(proposal, claim, mutate(h)), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.ALREADY_USED); h.assertNoNewTask()
        }
        val contexts = listOf<(Harness) -> OperationContext>(
            { it.operation.copy(operationId = "other-operation") }, { it.operation.copy(deadlineEpochMillis = 6000) },
            { it.operation.copy(cancelToken = CancellationSource()) }, { it.operation.copy(retryBudget = 1) },
            { it.operation.copy(resourceBudget = ResourceBudget(1, 2047)) })
        for (mutate in contexts) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            rejected(h.confirm(proposal, claim, operation = mutate(h)), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            h.assertNoNewTask()
        }
        val h = Harness(); val claim = h.claim()
        rejected(h.confirm(h.proposal(), claim, generation = h.generation + 1), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
        h.assertNoNewTask()
    }

    @Test fun variantAndAudioUpgradeAreFixedAtTheSingleExplicitConfirmation() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        val selected = h.request.copy(resource = h.request.resource.copy(variantId = "chosen-720p", expectedAudio = true))
        val result = h.confirm(proposal, claim, selected) as SaveAlternativeConfirmation.Accepted
        assertEquals(selected.asDurableReference(), result.record.request)
        rejected(h.confirm(proposal, claim, selected), SaveAlternativeRejection.ALREADY_USED)
        rejected(h.confirm(proposal, claim, selected.copy(resource = selected.resource.copy(variantId = "other-variant"))), SaveAlternativeRejection.ALREADY_USED)
        rejected(h.confirm(proposal, claim, selected.copy(requestId = "another-task", idempotencyKey = "another-key")), SaveAlternativeRejection.ALREADY_USED)
        assertEquals(2, h.journal.insertCalls); h.assertOldUntouched()
    }

    @Test fun selectedResourceCannotDropPreparedAudioRequirement() {
        val h = Harness(); val claim = h.claim()
        val proposal = (h.prepare(source = h.source.copy(expectedAudio = true)) as SaveAlternativePreparation.Ready).proposal
        rejected(h.confirm(proposal, claim), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
        h.assertNoNewTask()
    }

    @Test fun newRequestAndKeyNeverAliasEitherOldIdentityField() {
        for (oldField in listOf("old-direct", "old-key")) for (changeId in listOf(false, true)) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            val request = if (changeId) h.request.copy(requestId = oldField) else h.request.copy(idempotencyKey = oldField)
            rejected(h.confirm(proposal, claim, request), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            h.assertNoNewTask()
        }
    }

    @Test fun unrelatedExistingTaskOrKeyCannotBecomeAnAlternativeReplay() {
        for (reuseId in listOf(false, true)) {
            val h = Harness(); val proposal = h.proposal()
            val existing = h.request.copy(requestId = "existing-task", idempotencyKey = "existing-key")
            val saved = (h.coordinator.submit(existing, h.operation) as SaveSubmission.Accepted).record
            val claim = h.claim()
            val request = if (reuseId) h.request.copy(requestId = existing.requestId)
                else h.request.copy(idempotencyKey = existing.idempotencyKey)
            rejected(h.confirm(proposal, claim, request), SaveAlternativeRejection.MISMATCH, SaveError.INVALID_REQUEST)
            assertEquals(saved, h.journal.memory.records[existing.requestId])
            assertEquals(2, h.journal.memory.records.size); h.assertOldUntouched()
        }
    }

    @Test fun oldFailureMustStillMatchAtFinalConfirmation() {
        for (change in listOf<(SaveRecord) -> SaveRecord>(
            { it.copy(task = it.task.copy(revision = it.task.revision + 1)) },
            { it.copy(error = SaveError.METHOD_UNSUPPORTED) },
            { it.copy(request = it.request.copy(idempotencyKey = "changed-old-key")) })) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            val changed = change(h.old); h.journal.memory.records[h.old.task.taskId] = changed
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.STALE_FAILURE, SaveError.INVALID_REQUEST)
            assertSame(changed, h.journal.memory.records[h.old.task.taskId]); assertEquals(1, h.journal.memory.records.size)
            assertEquals(0, h.streamCalls)
        }
    }

    @Test fun cancellationAndBudgetRemainOrdinarySubmissionGates() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal(); h.cancellation.cancel()
        rejected(h.confirm(proposal, claim), SaveAlternativeRejection.SUBMISSION_REJECTED, SaveError.CANCELLED)
        h.assertNoNewTask()
        for ((bytes, error) in listOf(-1L to SaveError.INVALID_REQUEST, 2049L to SaveError.BYTE_LIMIT_EXCEEDED)) {
            val budget = Harness(); val owned = budget.claim()
            rejected(budget.confirm(budget.proposal(), owned, bytes = bytes), SaveAlternativeRejection.SUBMISSION_REJECTED, error)
            budget.assertNoNewTask()
        }
    }

    @Test fun wrongForeignReleasedAndAlreadyBoundClaimsCannotAuthorizeSubmission() {
        for (kind in listOf("direct", "foreign", "released", "bound")) {
            val h = Harness(); val proposal = h.proposal()
            val claim = when (kind) {
                "direct" -> requireNotNull(h.admission.tryAcquireDirect())
                "foreign" -> requireNotNull(SaveExecutionAdmission(maxArtifactBytes = 1024).tryAcquireExclusive())
                else -> h.claim()
            }
            if (kind == "released") assertTrue(h.admission.release(claim))
            if (kind == "bound") assertTrue(h.coordinator.submit(h.request.copy(requestId = "bound", idempotencyKey = "bound-key"),
                h.operation, claim) is SaveSubmission.Accepted)
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.ADMISSION_REJECTED, SaveError.BUSY)
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.ALREADY_USED)
            assertFalse(h.journal.memory.records.containsKey(h.request.requestId)); assertEquals(0, h.streamCalls)
            h.assertOldUntouched()
        }
    }

    @Test fun duplicateConcurrentConfirmationHasExactlyOneSubmissionAndNoReplay() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<SaveAlternativeConfirmation>())
        val jobs = List(2) { thread { assertTrue(start.await(2, TimeUnit.SECONDS)); results += h.confirm(proposal, claim) } }
        start.countDown(); jobs.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it is SaveAlternativeConfirmation.Accepted })
        assertEquals(1, results.count { it == SaveAlternativeConfirmation.Rejected(SaveAlternativeRejection.ALREADY_USED) })
        assertEquals(2, h.journal.insertCalls); assertEquals(0, h.streamCalls); h.assertOldUntouched()
    }

    @Test fun admissionMonitorIsNotHeldAcrossCoordinatorJournalWorkAndClaimCannotReleaseDuringIt() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        h.journal.beforeRead = {
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val job = thread(isDaemon = true) {
                try { assertTrue(h.admission.snapshot().exclusive); assertFalse(h.admission.release(claim)) }
                catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }
            assertTrue("Admission monitor must be free during journal IO", finished.await(2, TimeUnit.SECONDS))
            job.join(2000); failure.get()?.let { throw it }
        }
        assertTrue(h.confirm(proposal, claim) is SaveAlternativeConfirmation.Accepted)
        h.journal.beforeRead = null
        h.assertOldUntouched()
    }

    @Test fun revocationDuringJournalValidationFencesTheNewTask() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        h.journal.beforeRead = { proposal.revoke() }
        rejected(h.confirm(proposal, claim), SaveAlternativeRejection.REVOKED)
        h.journal.beforeRead = null; h.assertNoNewTask()
    }

    @Test fun journalInsertWriteThenThrowIsUnknownConsumedAndExistingRecoveryNeverTransfers() {
        val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
        h.journal.fault = InsertFault.WRITE_THEN_THROW
        assertEquals(SaveAlternativeConfirmation.OutcomeUnknown(h.request.requestId), h.confirm(proposal, claim))
        assertEquals(TaskState.QUEUED, h.journal.memory.records[h.request.requestId]!!.task.state)
        rejected(h.confirm(proposal, claim, h.request.copy(requestId = "retry", idempotencyKey = "retry-key")), SaveAlternativeRejection.ALREADY_USED)
        assertTrue(h.admission.release(claim)) // The adapter may retire only its actually released prepare resources.
        val recovered = h.coordinator().recover(h.operation.copy(operationId = "startup-recovery"))
        assertEquals(1, recovered.size)
        assertEquals(h.request.requestId, recovered.single().task.taskId)
        assertEquals(TaskState.WAITING_USER, recovered.single().task.state)
        assertEquals(SaveWaitReason.RECOVERY, recovered.single().waitReason)
        assertEquals(ResumeAction.REVIEW_RECOVERY, recovered.single().task.waitingUser!!.resumeAction)
        assertEquals(0, h.streamCalls); assertEquals(0, h.verifierCalls); assertEquals(0, h.store.commitCalls)
        assertEquals(2, h.journal.insertCalls); h.assertOldUntouched()
    }

    @Test fun journalThrowBeforeWriteAndReadFailureAreAlsoUnknownRatherThanProofOfNoRow() {
        for (readFailure in listOf(false, true)) {
            val h = Harness(); val claim = h.claim(); val proposal = h.proposal()
            if (readFailure) h.journal.readFailure = true else h.journal.fault = InsertFault.THROW_BEFORE_WRITE
            assertEquals(SaveAlternativeConfirmation.OutcomeUnknown(h.request.requestId), h.confirm(proposal, claim))
            rejected(h.confirm(proposal, claim), SaveAlternativeRejection.ALREADY_USED)
            h.journal.readFailure = false
            assertTrue(h.admission.release(claim)); h.assertNoNewTask()
        }
    }

    @Test fun ordinarySubmissionRetainsItsExistingIdempotencyContract() {
        val h = Harness()
        val accepted = h.coordinator.submit(h.request, h.operation) as SaveSubmission.Accepted
        val duplicate = h.coordinator.submit(h.request, h.operation) as SaveSubmission.Accepted
        assertFalse(accepted.existing); assertTrue(duplicate.existing); assertEquals(accepted.record, duplicate.record)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute(h.request.requestId)!!.task.state)
        h.assertOldUntouched()
    }
}
