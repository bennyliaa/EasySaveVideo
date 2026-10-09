package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class StreamCoordinatorTest {
    private class Harness(val limits: SaveLimits = SaveLimits(1024, 2048)) {
        val journal = MemorySaveJournal()
        var inspectionCalls = 0
        val store = object : FakeMediaStore() {
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
                inspectionCalls++
                return super.inspectCommitted(commitId, context)
            }
        }
        var directCalls = 0
        var streamCalls = 0
        var missingExpectation = false
        var missingConservation = false
        var missingTail = false
        var badConservation = false
        var verifications = 0
        var badDuration = false
        var requiresRecovery = false
        var uncertainOnReturn = false
        var sendBytes = 200L
        var onTransfer: (() -> Unit)? = null
        val source = SelectedMediaResource("candidate", "fixed-variant", SourceUrl("https://example.invalid/list.m3u8?signature=secret"))
        val request = SaveRequest("task", "key", source, TaskMethod.STREAM)
        val operation = OperationContext("op", 10000, CancellationSource(), resourceBudget = ResourceBudget(1, 2048))
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?, context: OperationContext,
                limits: SaveLimits, progress: TransferProgress): TransferResult { directCalls++; throw AssertionError("NO_DIRECT_FALLBACK") }
        }, ArtifactVerifier { a, t, _ ->
            verifications++
            goodEvidence(a, t).let { e -> e.copy(durationMillis = if (badDuration) 9000 else 2000, width = 16, height = 16,
                streamTailProof = if (missingTail) null else e.streamTailProof,
                streamConservation = if (badConservation) e.streamConservation!!.let { it.copy(video = it.video.copy(sequenceSha256 = "b".repeat(64))) } else e.streamConservation) }
        },
            store, FakeSaveClock(), limits, stream = object : StreamSavePort {
                override fun availability() = SaveMethodAvailability(true, null)
                override fun requiresProcessRecovery() = requiresRecovery
                override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?, context: OperationContext,
                    limits: SaveLimits, progress: TransferProgress): TransferResult {
                    streamCalls++; onTransfer?.invoke(); progress.onBytes(sendBytes)
                    if (uncertainOnReturn) requiresRecovery = true
                    return TransferResult(artifact.artifactId, 400, "video/mp4", 400,
                        if (missingExpectation) null else StreamArtifactExpectation(2000, 1, 0, 16, 16, if (missingConservation) null else testStreamConservation()))
                }
            })
    }
    @Test fun selectedStreamUsesOwnPortAndStillValidatesAndCommits() {
        val h = Harness(); h.coordinator.submit(h.request, h.operation, 50)
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(TaskMethod.STREAM, result.task.method)
        assertEquals(1, h.streamCalls); assertEquals(0, h.directCalls); assertEquals(1, h.store.commitCalls)
        assertFalse(result.request.resource.url.value.contains("signature"))
    }
    @Test fun preparationBytesPersistOnceAndConsumeSharedBudget() {
        val h = Harness(); h.sendBytes = 100
        val accepted = h.coordinator.submit(h.request, h.operation, 2000) as SaveSubmission.Accepted
        assertEquals(2000, accepted.record.bytesTransferred)
        assertTrue((h.coordinator.submit(h.request, h.operation, 2000) as SaveSubmission.Accepted).existing)
        assertEquals(2000, h.journal.read("task")!!.bytesTransferred)
        assertEquals(SaveError.BYTE_LIMIT_EXCEEDED, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun noPreflightCanExceedLifetimeBudgetOrChargeDirect() {
        val h = Harness()
        assertEquals(SaveSubmission.Rejected(SaveError.BYTE_LIMIT_EXCEEDED), h.coordinator.submit(h.request, h.operation, 2049))
        assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST), h.coordinator.submit(h.request.copy(method = TaskMethod.DIRECT), h.operation, 1))
        assertTrue(h.journal.records.isEmpty())
    }
    @Test fun streamWithoutActualOutputExpectationNeverCommits() {
        val h = Harness(); h.missingExpectation = true; h.coordinator.submit(h.request, h.operation)
        assertEquals(SaveError.INVALID_MEDIA, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun finalDurationMismatchNeverCommits() {
        val h = Harness(); h.badDuration = true; h.coordinator.submit(h.request, h.operation)
        assertEquals(SaveError.INVALID_MEDIA, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun uncertainStreamReleaseFencesEvenNewDirectWork() {
        val h = Harness(); h.requiresRecovery = true
        assertEquals(SaveSubmission.Rejected(SaveError.RESOURCE_RELEASE_UNCONFIRMED), h.coordinator.submit(h.request.copy(method = TaskMethod.DIRECT), h.operation))
        assertTrue(h.journal.records.isEmpty())
    }
    @Test fun adapterFinallyRaisingReleaseFenceCannotPublishSuccess() {
        val h = Harness(); h.uncertainOnReturn = true
        h.coordinator.submit(h.request, h.operation)
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, result.error)
        assertNull(result.verification); assertNull(result.receipt); assertEquals(0, h.store.commitCalls)
    }
    @Test fun durableStateRejectsMissingStreamOrUnexpectedDirectConstraints() {
        val h = Harness()
        val accepted = h.coordinator.submit(h.request, h.operation) as SaveSubmission.Accepted
        val artifact = StagingArtifact("task", "task")
        assertThrows(IllegalArgumentException::class.java) {
            accepted.record.copy(artifact = artifact, transfer = TransferResult("task", 400, "video/mp4", 400))
        }
        val validStream = accepted.record.copy(artifact = artifact, transfer = TransferResult("task", 400, "video/mp4", 400,
            StreamArtifactExpectation(2000, 1, 0, 16, 16)))
        assertThrows(IllegalArgumentException::class.java) {
            validStream.copy(request = validStream.request.copy(method = TaskMethod.DIRECT),
                task = validStream.task.copy(method = TaskMethod.DIRECT))
        }
    }
    @Test fun backgroundPauseUsesCoordinatorTokenAndPreservesWaitingState() {
        val h = Harness()
        h.onTransfer = { h.coordinator.pauseForBackground("task") }
        h.coordinator.submit(h.request, h.operation, 50)
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveWaitReason.BACKGROUND, result.waitReason)
        assertFalse(h.operation.cancelToken.isCancelled)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun memoryOnlyBackgroundSignalFencesAcceptedButNotStartedStream() {
        val h = Harness(); h.coordinator.submit(h.request, h.operation, 50)
        h.coordinator.signalBackgroundPauseAll() // Durable control enqueue may fail or be delayed.
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveWaitReason.BACKGROUND, result.waitReason)
        assertEquals(0, h.streamCalls); assertEquals(0, h.store.commitCalls)
    }
    @Test fun immediateUserCancelSignalDoesNotDependOnControlQueue() {
        val h = Harness(); h.coordinator.submit(h.request, h.operation, 50)
        h.coordinator.signalUserCancel("task")
        assertEquals(TaskState.CANCELLED, h.coordinator.execute("task")!!.task.state)
        assertEquals(0, h.streamCalls)
    }
    @Test fun outputExpectationRejectsTrackDimensionsAndAudioMismatch() {
        val a = StagingArtifact("artifact", "commit"); val t = TransferResult("artifact", 500, "video/mp4", 500,
            StreamArtifactExpectation(2000, 1, 1, 16, 16, testStreamConservation(true)))
        val e = VerificationEvidence("artifact", 500, "a".repeat(64), true, 1, 1, 2000, 16, 16, false, DecodePosition.entries.toSet(), true,
            t.streamExpectation!!.conservation, t.streamExpectation.conservation!!.let {
                StreamTailProof(it.videoTail.precedingSync, it.videoTail.lastSample, it.video.lastSample.presentationTimeUs, true) })
        assertTrue(e.isValidFor(a,t,true)); assertFalse(e.copy(audioTrackCount=0).isValidFor(a,t,false))
        assertFalse(e.copy(width=32).isValidFor(a,t,false)); assertFalse(e.copy(videoTrackCount=2).isValidFor(a,t,false))
    }
    @Test fun newStreamCannotReturnUnprovenLegacyConstraints() {
        val h = Harness(); h.missingConservation = true; h.coordinator.submit(h.request, h.operation)
        assertEquals(SaveError.INVALID_MEDIA, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.verifications); assertEquals(0, h.store.commitCalls)
    }
    @Test fun nearEndEvidenceWithoutActualTailProofNeverPublishes() {
        val h = Harness(); h.missingTail = true; h.coordinator.submit(h.request, h.operation)
        assertEquals(SaveError.INVALID_MEDIA, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun changedIndependentSequenceNeverPublishes() {
        val h = Harness(); h.badConservation = true; h.coordinator.submit(h.request, h.operation)
        assertEquals(SaveError.INVALID_MEDIA, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun recoveredLegacyStreamDefersWithoutSynthesizingProofOrCallingVerifier() {
        val h = Harness(); val accepted = h.coordinator.submit(h.request, h.operation) as SaveSubmission.Accepted
        h.journal.records["task"] = accepted.record.copy(task = accepted.record.task.copy(state = TaskState.RUNNING),
            artifact = StagingArtifact("task", "task"),
            transfer = TransferResult("task", 400, "video/mp4", 400, StreamArtifactExpectation(2000, 1, 0, 16, 16)))
        h.coordinator.recover(h.operation)
        assertEquals(TaskState.WAITING_USER, h.journal.read("task")!!.task.state)
        h.coordinator.resume("task", h.operation.copy(operationId = "resume"))
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.WAITING_USER, result.task.state); assertEquals(SaveError.RECOVERY_REQUIRED, result.error)
        assertNull(result.verification); assertNull(result.receipt)
        assertEquals(0, h.streamCalls); assertEquals(0, h.verifications); assertEquals(0, h.store.commitCalls)
    }

    @Test fun legacyCommittingReceiptCannotBeAdoptedOrPublishedAfterRecovery() {
        val h = Harness(); val accepted = h.coordinator.submit(h.request, h.operation) as SaveSubmission.Accepted
        val artifact = StagingArtifact("task", "task")
        val transfer = TransferResult("task", 400, "video/mp4", 400, StreamArtifactExpectation(2000, 1, 0, 16, 16))
        val oldEvidence = goodEvidence(artifact, transfer).copy(durationMillis = 2000, width = 16, height = 16)
        val oldReceipt = MediaCommitReceipt("task", "task", "media-task", 400, oldEvidence.sha256)
        h.store.committed["task"] = oldReceipt
        h.journal.records["task"] = accepted.record.copy(task = accepted.record.task.copy(state = TaskState.RUNNING),
            phase = SavePhase.COMMITTING, artifact = artifact, transfer = transfer,
            verification = oldEvidence, receipt = oldReceipt)
        val recovered = h.coordinator.recover(h.operation).single()
        assertEquals(TaskState.WAITING_USER, recovered.task.state)
        assertEquals(SavePhase.COMMITTING, recovered.phase)
        assertEquals(oldEvidence, recovered.verification); assertEquals(oldReceipt, recovered.receipt)
        h.coordinator.resume("task", h.operation.copy(operationId = "resume"))
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveError.RECOVERY_REQUIRED, result.error)
        assertFalse(result.verification!!.isValidFor(artifact, transfer, false))
        assertEquals(oldReceipt, result.receipt); assertEquals(oldReceipt, h.store.committed["task"])
        assertEquals(0, h.directCalls); assertEquals(0, h.streamCalls); assertEquals(0, h.verifications)
        assertEquals(0, h.inspectionCalls); assertEquals(0, h.store.commitCalls)
        assertTrue(h.store.cleanup.isEmpty())
    }

}
