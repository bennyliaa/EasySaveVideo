package com.easysavevideo.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RecordingCoordinatorTest {
    private val descriptor = CaptureDescriptor("capture", 7, RecordingAudioChoice.NONE)
    private val artifact = StagingArtifact("capture", "capture")
    private val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
    private fun expectation(d: CaptureDescriptor = descriptor) = RecordingArtifactExpectation(d, d.requestedProfile,
        RecordingAudioOutcome.NO_AUDIO_REQUESTED, RecordingStopReason.USER_STOP, video, null, 1_000_000, 1024)
    private fun output() = TransferResult("capture", 1024, "video/mp4", 1024, recordingExpectation = expectation())
    private fun evidence(result: TransferResult): VerificationEvidence {
        val e = requireNotNull(result.recordingExpectation)
        return VerificationEvidence(result.artifactId, 1024, "a".repeat(64), true, 1, 0, 1000, 1280, 720,
            false, DecodePosition.entries.toSet(), false, recordingProof = RecordingVerificationProof(e.video, null,
                "video/avc", null, null, null, e.video.lastPtsUs, true, decodedResumeBoundaries = emptyList()))
    }
    private fun request() = SaveRequest("capture", "capture-key", SaveInput.Capture(descriptor))
    private fun operation(id: String = "operation") = OperationContext(id, 10_000, CancellationSource(), 2,
        resourceBudget = ResourceBudget(1, 30_000_000))
    private inner class Harness(
        val journal: MemorySaveJournal = MemorySaveJournal(),
        val store: FakeMediaStore = FakeMediaStore(),
        val block: ((OperationContext) -> TransferResult)? = null,
        val verify: ((TransferResult) -> VerificationEvidence)? = null,
        val unreleased: () -> Boolean = { false },
        val limits: SaveLimits = SaveLimits(10_000_000, 30_000_000),
        val networkGate: SaveNetworkGate = SaveNetworkGate {},
    ) {
        var captureCalls = 0; var networkCalls = 0; var verifierCalls = 0
        val commands = mutableListOf<String>()
        val verificationDeadlines = mutableListOf<Long>()
        val finished = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val port = object : RecordSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun requiresProcessRecovery() = unreleased()
            override fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
                context: OperationContext, limits: SaveLimits): TransferResult {
                assertEquals("capture", requestId); assertEquals(requestId, descriptor.sessionId)
                assertEquals(0, context.retryBudget); captureCalls++; entered.countDown()
                return block?.invoke(context) ?: output()
            }
            override fun snapshot(requestId: String): RecordingSnapshot? { commands.add("snapshot:$requestId"); return null }
            override fun pause(requestId: String): RecordingSnapshot? { commands.add("pause:$requestId"); return null }
            override fun resume(requestId: String): RecordingSnapshot? { commands.add("resume:$requestId"); return null }
            override fun stopAndSave(requestId: String): RecordingSnapshot? { commands.add("stop:$requestId"); finished.countDown(); return null }
            override fun cancel(requestId: String): RecordingSnapshot? { commands.add("cancel:$requestId"); finished.countDown(); return null }
        }
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                networkCalls++; error("Capture must never use network transfer")
            }
        }, ArtifactVerifier { _, result, context -> verificationDeadlines += context.deadlineEpochMillis; verifierCalls++; verify?.invoke(result) ?: evidence(result) },
            store, FakeSaveClock(), limits, recording = port, networkGate = networkGate)
        fun submit() = coordinator.submit(request(), operation())
        fun run(): SaveRecord { submit(); return coordinator.execute("capture")!! }
    }
    @Test fun wifiRestrictionDoesNotReachRecording() {
        val h = Harness(networkGate = SaveNetworkGate { throw AssertionError("Recording must not consult Wi-Fi") })
        h.coordinator.submit(request(), operation())
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("capture")!!.task.state)
        assertEquals(1, h.captureCalls)
    }
    @Test fun typedCaptureHasNoUrlCredentialsOrNetworkByteAccounting() {
        val h = Harness(); val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertTrue(result.request.input is SaveInput.Capture); assertNull(result.request.networkResource)
        assertThrows(IllegalArgumentException::class.java) { result.request.resource }
        assertEquals(0L, result.bytesTransferred); assertNull(result.checkpoint)
        assertEquals(0, result.retryBudget); assertEquals(0, h.networkCalls)
        assertEquals(1, h.captureCalls); assertEquals(1, h.verifierCalls); assertEquals(1, h.store.commitCalls)
        assertNotNull(result.receipt); assertNull(result.transfer!!.streamExpectation)
    }
    @Test fun mixedInputAndMethodAndSessionIdentityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { request().copy(method = TaskMethod.DIRECT) }
        assertThrows(IllegalArgumentException::class.java) { SaveRequest("capture", "key", SelectedMediaResource("c", "v", SourceUrl("https://example.invalid/")), TaskMethod.RECORD) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(requestId = "other") }
        val h = Harness(); assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST), h.coordinator.submit(request(), operation(), 1))
    }
    @Test fun unsupportedCaptureNeverExecutesOrCreatesJournal() {
        val network = SaveHarness()
        assertEquals(SaveSubmission.Rejected(SaveError.METHOD_UNSUPPORTED), network.coordinator.submit(request(), operation()))
        assertTrue(network.journal.records.isEmpty())
    }
    @Test fun stopSavesButBackgroundOnlyAffectsNetworkAndControlsAreRequestScoped() {
        lateinit var h: Harness
        h = Harness(block = { context ->
            assertTrue(h.finished.await(3, TimeUnit.SECONDS)); assertFalse(context.cancelToken.isCancelled); output()
        })
        h.submit(); val worker = thread { h.coordinator.execute("capture") }
        assertTrue(h.entered.await(3, TimeUnit.SECONDS))
        h.coordinator.signalBackgroundPauseAll(); h.coordinator.pauseForBackground("capture")
        assertEquals(TaskState.RUNNING, h.coordinator.snapshot("capture")!!.state)
        h.coordinator.pauseRecording("stale"); h.coordinator.resumeRecording("stale"); h.coordinator.stopAndSave("stale")
        h.coordinator.pauseRecording("capture"); h.coordinator.resumeRecording("capture"); h.coordinator.stopAndSave("capture")
        worker.join(3000); assertFalse(worker.isAlive)
        assertEquals(listOf("pause:capture", "resume:capture", "stop:capture"), h.commands)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("capture")!!.state)
    }
    @Test fun cancellationRejectsLateCaptureResultBeforeVerificationOrPublication() {
        lateinit var h: Harness
        h = Harness(block = { assertTrue(h.finished.await(3, TimeUnit.SECONDS)); output() })
        h.submit(); val worker = thread { h.coordinator.execute("capture") }
        assertTrue(h.entered.await(3, TimeUnit.SECONDS)); h.coordinator.cancel("capture")
        worker.join(3000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("capture")!!.state)
        assertEquals(0, h.verifierCalls); assertEquals(0, h.store.commitCalls)
        assertEquals(listOf("cancel:capture"), h.commands)
    }
    @Test fun recordingFailuresNeverRetryEvenIfNamedLikeNetworkError() {
        val h = Harness(block = { throw SaveFailure(SaveError.NETWORK_TIMEOUT) })
        val result = h.run(); assertEquals(TaskState.FAILED, result.task.state)
        assertEquals(SaveError.NETWORK_TIMEOUT, result.error); assertEquals(1, h.captureCalls)
    }
    @Test fun rejectedRecordingEvidenceNeverPublishesAndStreamProofCannotSubstitute() {
        val mutations: List<(VerificationEvidence) -> VerificationEvidence> = listOf(
            { it.copy(recordingProof = null) },
            { it.copy(recordingProof = it.recordingProof!!.copy(videoOutputEos = false)) },
            { it.copy(recordingProof = it.recordingProof!!.copy(terminalVideoOutputTimeUs = 0)) },
            { it.copy(recordingProof = it.recordingProof!!.copy(video = video.copy(sampleCount = 29))) },
            { it.copy(recordingProof = it.recordingProof!!.copy(videoMimeType = "video/hevc")) },
            { it.copy(width = 640) }, { it.copy(durationMillis = 900) },
            { it.copy(streamConservation = testStreamConservation()) },
        )
        mutations.forEach { mutate ->
            val h = Harness(verify = { mutate(evidence(it)) })
            assertEquals(TaskState.FAILED, h.run().task.state); assertEquals(0, h.store.commitCalls)
        }
    }
    @Test fun captureDescriptorMismatchAndStreamExpectationAreNeverAccepted() {
        val h = Harness(block = { output().copy(recordingExpectation = expectation(descriptor.copy(generation = 8))) })
        assertEquals(TaskState.FAILED, h.run().task.state); assertEquals(0, h.verifierCalls)
        assertThrows(IllegalArgumentException::class.java) {
            output().copy(streamExpectation = StreamArtifactExpectation(1000, 1, 0, 1280, 720, testStreamConservation()))
        }
    }
    @Test fun restartCannotCaptureAgainButFinalizedOutputCanRecoverLocally() {
        val first = Harness(block = { throw SaveFailure(SaveError.STORAGE_FULL) })
        assertEquals(TaskState.WAITING_USER, first.run().task.state)
        val restart = Harness(journal = first.journal)
        restart.coordinator.recover(operation("recover"))
        val blocked = restart.coordinator.resume("capture", operation("review"))!!
        assertEquals(TaskState.WAITING_USER, blocked.task.state)
        restart.coordinator.execute("capture"); assertEquals(0, restart.captureCalls)

        val pending = Harness(verify = { throw SaveFailure(SaveError.STORAGE_FULL) })
        assertEquals(TaskState.WAITING_USER, pending.run().task.state)
        val local = Harness(journal = pending.journal)
        local.coordinator.recover(operation("recover-local"))
        local.coordinator.resume("capture", operation("review-local"))
        assertEquals(TaskState.SUCCEEDED, local.coordinator.execute("capture")!!.task.state)
        assertEquals(0, local.captureCalls); assertEquals(0, local.networkCalls); assertEquals(1, local.verifierCalls)
    }
    @Test fun rejectedFinalizedRecoveryScheduleCanResumeLocallyWithoutAnotherCapture() {
        val pending = Harness(verify = { throw SaveFailure(SaveError.STORAGE_FULL) })
        val finalized = pending.run()
        assertEquals(TaskState.WAITING_USER, finalized.task.state)
        assertNotNull(finalized.transfer)
        val local = Harness(journal = pending.journal)
        local.coordinator.recover(operation("recover-local"))
        val resumed = local.coordinator.resume("capture", operation("rejected-schedule"))!!
        assertEquals(TaskState.RUNNING, resumed.task.state)
        val deferred = local.coordinator.deferFinalizedRecording("capture")!!
        assertEquals(TaskState.WAITING_USER, deferred.task.state)
        assertEquals(SaveWaitReason.RECOVERY, deferred.waitReason)
        assertEquals(SaveError.RECOVERY_REQUIRED, deferred.error)
        assertEquals(ResumeAction.REVIEW_RECOVERY, deferred.task.waitingUser!!.resumeAction)
        assertEquals(finalized.artifact, deferred.artifact); assertEquals(finalized.transfer, deferred.transfer)
        assertNull(deferred.receipt); assertEquals(0, local.verifierCalls); assertEquals(0, local.store.commitCalls)
        assertTrue(local.store.cleanup.isEmpty()); assertTrue(local.commands.isEmpty())
        assertEquals(deferred, local.coordinator.execute("capture"))
        val accepted = local.coordinator.resume("capture", operation("accepted-schedule"))!!
        assertEquals(TaskState.RUNNING, accepted.task.state)
        assertEquals(0, accepted.retryBudget); assertEquals(0, accepted.attempt)
        assertEquals(TaskState.SUCCEEDED, local.coordinator.execute("capture")!!.task.state)
        assertEquals(0, local.captureCalls); assertEquals(0, local.networkCalls)
        assertEquals(1, local.verifierCalls); assertEquals(1, local.store.commitCalls)
        assertTrue(local.commands.isEmpty())
    }
    @Test fun repeatedFinalizedRecoveryDeferralDoesNotRewriteOrExtendTheWait() {
        val pending = Harness(verify = { throw SaveFailure(SaveError.STORAGE_FULL) })
        pending.run()
        val local = Harness(journal = pending.journal)
        local.coordinator.recover(operation("recover-local"))
        local.coordinator.resume("capture", operation("rejected-schedule"))
        val deferred = local.coordinator.deferFinalizedRecording("capture")!!
        val writes = local.journal.writes
        repeat(3) { assertEquals(deferred, local.coordinator.deferFinalizedRecording("capture")) }
        assertEquals(writes, local.journal.writes)
        assertEquals(0, local.captureCalls); assertEquals(0, local.networkCalls)
        assertEquals(0, local.verifierCalls); assertEquals(0, local.store.commitCalls)
        assertTrue(local.commands.isEmpty()); assertTrue(local.store.cleanup.isEmpty())
    }
    @Test fun finalizedRecoveryDeferralLeavesQueuedAndRunningLiveCaptureUntouched() {
        val finish = CountDownLatch(1)
        val h = Harness(block = { context ->
            assertTrue(finish.await(3, TimeUnit.SECONDS)); assertFalse(context.cancelToken.isCancelled); output()
        })
        h.submit()
        val queued = h.coordinator.record("capture")!!
        assertNull(queued.transfer)
        assertEquals(queued, h.coordinator.deferFinalizedRecording("capture"))
        assertEquals(0, h.journal.writes)
        val worker = thread { h.coordinator.execute("capture") }
        try {
            assertTrue(h.entered.await(3, TimeUnit.SECONDS))
            val running = h.coordinator.record("capture")!!
            assertNull(running.transfer)
            assertEquals(running, h.coordinator.deferFinalizedRecording("capture"))
            assertEquals(TaskState.RUNNING, h.coordinator.snapshot("capture")!!.state)
            assertTrue(h.commands.isEmpty()); assertEquals(0, h.verifierCalls)
        } finally { finish.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive); assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("capture")!!.state)
        assertEquals(1, h.captureCalls); assertEquals(0, h.networkCalls)
    }
    @Test fun finalizedRecoveryDeferralNeverInterruptsExecutingLocalVerification() {
        val pending = Harness(verify = { throw SaveFailure(SaveError.STORAGE_FULL) })
        pending.run()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val local = Harness(journal = pending.journal, verify = {
            entered.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS)); evidence(it)
        })
        local.coordinator.recover(operation("recover-local"))
        local.coordinator.resume("capture", operation("accepted-schedule"))
        val worker = thread { local.coordinator.execute("capture") }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val verifying = local.coordinator.record("capture")!!
            assertNotNull(verifying.transfer); assertEquals(SavePhase.VERIFYING, verifying.phase)
            val writes = local.journal.writes
            assertEquals(verifying, local.coordinator.deferFinalizedRecording("capture"))
            assertEquals(writes, local.journal.writes)
            assertTrue(local.commands.isEmpty())
        } finally { finish.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive); assertEquals(TaskState.SUCCEEDED, local.coordinator.snapshot("capture")!!.state)
        assertEquals(0, local.captureCalls); assertEquals(0, local.networkCalls); assertEquals(1, local.store.commitCalls)
    }
    @Test fun finalizedRecoveryDeferralUsesLegalQueuedTransitionAndLeavesSuccessUntouched() {
        val h = Harness(); h.submit()
        val queued = h.journal.records.getValue("capture").copy(artifact = artifact, transfer = output())
        h.journal.records["capture"] = queued
        val deferred = h.coordinator.deferFinalizedRecording("capture")!!
        assertEquals(TaskState.WAITING_USER, deferred.task.state)
        assertEquals(queued.task.revision + 2, deferred.task.revision)
        assertEquals(SaveWaitReason.RECOVERY, deferred.waitReason)
        assertEquals(queued.transfer, deferred.transfer); assertTrue(h.commands.isEmpty())
        h.coordinator.resume("capture", operation("review-queued"))
        val success = h.coordinator.execute("capture")!!
        assertEquals(TaskState.SUCCEEDED, success.task.state)
        val writes = h.journal.writes
        assertEquals(success, h.coordinator.deferFinalizedRecording("capture"))
        assertEquals(writes, h.journal.writes); assertEquals(0, h.captureCalls); assertEquals(0, h.networkCalls)
    }
    @Test fun finalizedRecoveryDeferralCannotChangeNetworkRecovery() {
        val h = SaveHarness(verifyBlock = { _, _, _ -> throw SaveFailure(SaveError.STORAGE_FULL) })
        h.run()
        val resumed = h.coordinator.resume("task", h.operation("review-network"))!!
        assertNotNull(resumed.transfer); assertEquals(TaskState.RUNNING, resumed.task.state)
        val writes = h.journal.writes
        assertEquals(resumed, h.coordinator.deferFinalizedRecording("task"))
        assertEquals(writes, h.journal.writes)
        assertNull(h.coordinator.deferFinalizedRecording("unknown"))
    }
    @Test fun unreleasedNativeOwnershipFencesVerificationCleanupAndPublication() {
        var occupied = false
        val h = Harness(block = { occupied = true; output() }, unreleased = { occupied })
        val result = h.run(); assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, result.error)
        assertEquals(0, h.verifierCalls); assertEquals(0, h.store.commitCalls); assertTrue(h.store.cleanup.isEmpty())
    }
    @Test fun cancelDuringProvisionalCommitCannotPublish() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val store = object : FakeMediaStore() {
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                entered.countDown(); assertTrue(exit.await(3, TimeUnit.SECONDS)); return super.commit(artifact, context)
            }
        }
        val h = Harness(store = store); h.submit(); val worker = thread { h.coordinator.execute("capture") }
        assertTrue(entered.await(3, TimeUnit.SECONDS)); h.coordinator.cancel("capture"); exit.countDown()
        worker.join(3000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("capture")!!.state)
    }
    @Test fun networkOutputCannotBorrowRecordingProof() {
        val result = output().copy(recordingExpectation = null)
        assertFalse(evidence(output()).isValidFor(artifact, result, false))
    }
    @Test fun captureIdempotencyAndFinalizedByteBudgetAreEnforced() {
        val h = Harness(); assertTrue(h.submit() is SaveSubmission.Accepted)
        val repeated = h.coordinator.submit(request(), operation("repeat")) as SaveSubmission.Accepted
        assertTrue(repeated.existing); assertEquals(1, h.journal.records.size)
        assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST),
            h.coordinator.submit(request().copy(input = SaveInput.Capture(descriptor.copy(generation = 8))), operation("conflict")))
        h.coordinator.execute("capture"); h.coordinator.execute("capture")
        assertEquals(1, h.captureCalls)
        val tinyBudget = Harness()
        tinyBudget.coordinator.submit(request(), operation().copy(resourceBudget = ResourceBudget(1, 512)))
        assertEquals(TaskState.FAILED, tinyBudget.coordinator.execute("capture")!!.task.state)
        assertEquals(0, tinyBudget.verifierCalls)
    }
    @Test fun cancelAfterStopRequestStillFencesLateFinalization() {
        val finish = CountDownLatch(1)
        val h = Harness(block = { assertTrue(finish.await(3, TimeUnit.SECONDS)); output() })
        h.submit(); val worker = thread { h.coordinator.execute("capture") }
        assertTrue(h.entered.await(3, TimeUnit.SECONDS))
        h.coordinator.stopAndSave("capture"); h.coordinator.signalUserCancel("capture"); finish.countDown()
        worker.join(3000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("capture")!!.state)
        assertEquals(0, h.store.commitCalls)
    }
    @Test fun audioExpectationRequiresActualReadableAacMatchingRateAndChannelCount() {
        val d = descriptor.copy(audioChoice = RecordingAudioChoice.INTERNAL)
        val audio = RecordingTrackEvidence(47, 0, 981_333, 21_333)
        val e = RecordingArtifactExpectation(d, d.requestedProfile, RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN,
            RecordingStopReason.AUDIO_LOST, video, audio, audio.endPtsUs, 1024)
        val t = output().copy(recordingExpectation = e)
        val p = RecordingVerificationProof(video, audio, "video/avc", "audio/mp4a-latm", 48_000, 2, video.lastPtsUs, true, decodedResumeBoundaries = emptyList())
        val proof = evidence(output()).copy(audioTrackCount = 1, audioTrackReadable = true,
            durationMillis = audio.endPtsUs / 1000, recordingProof = p)
        assertTrue(proof.isValidFor(artifact, t, false))
        assertFalse(proof.copy(audioTrackReadable = false).isValidFor(artifact, t, false))
        assertFalse(proof.copy(recordingProof = p.copy(audioSampleRate = 44_100)).isValidFor(artifact, t, false))
        assertFalse(proof.copy(recordingProof = p.copy(audioChannelCount = 1)).isValidFor(artifact, t, false))
        assertFalse(proof.copy(recordingProof = p.copy(audio = audio.copy(sampleCount = 46))).isValidFor(artifact, t, false))
        assertThrows(IllegalArgumentException::class.java) { e.copy(audio = null) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(finalized = false) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(resourcesReleased = false) }
    }
    @Test fun legacyCommittingReceiptCannotPublishWithoutDurableResumePolicy() {
        val prior = Harness(); val completed = prior.run()
        val transfer = completed.transfer!!.copy(recordingExpectation = expectation().copy(resumeBoundaries = null))
        val legacy = completed.copy(task = completed.task.copy(state = TaskState.RUNNING), phase = SavePhase.COMMITTING,
            transfer = transfer, verification = completed.verification!!.let {
                it.copy(recordingProof = it.recordingProof!!.copy(decodedResumeBoundaries = null))
            })
        prior.journal.records["capture"] = legacy
        val store = FakeMediaStore().also { it.committed["capture"] = requireNotNull(legacy.receipt) }
        val recovered = Harness(journal = prior.journal, store = store)
        recovered.coordinator.recover(operation("recover-legacy"))
        recovered.coordinator.resume("capture", operation("review-legacy"))
        val result = recovered.coordinator.execute("capture")!!
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveError.RECOVERY_REQUIRED, result.error)
        assertEquals(legacy.transfer, result.transfer); assertEquals(legacy.receipt, result.receipt)
        assertEquals(0, recovered.captureCalls); assertEquals(0, recovered.networkCalls)
        assertEquals(0, recovered.verifierCalls); assertEquals(0, recovered.store.commitCalls)
        assertFalse(result.verification!!.isValidFor(result.artifact!!, result.transfer!!, false))
    }

    @Test fun freshCaptureCannotClaimLegacyUnknownResumeContract() {
        val h = Harness(block = { output().copy(recordingExpectation = expectation().copy(resumeBoundaries = null)) })
        val result = h.run()
        assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.INVALID_MEDIA, result.error)
        assertEquals(0, h.verifierCalls); assertEquals(0, h.store.commitCalls)
    }

    @Test fun resumeEvidenceRequiresEveryActualDecodedBoundaryWithExactIndexAndBoundedClockError() {
        val boundaries = listOf(RecordingResumeBoundary(10, 333_333), RecordingResumeBoundary(20, 666_667))
        val expected = expectation().copy(video = video.copy(syncSampleCount = 3), resumeBoundaries = boundaries)
        val transfer = output().copy(recordingExpectation = expected)
        val base = evidence(output())
        val proof = base.recordingProof!!.copy(video = expected.video, decodedResumeBoundaries = boundaries)
        val verified = base.copy(recordingProof = proof)
        assertTrue(verified.isValidFor(artifact, transfer, false))
        for (missing in listOf<List<RecordingResumeBoundary>?>(null, emptyList(), boundaries.take(1)))
            assertFalse(verified.copy(recordingProof = proof.copy(decodedResumeBoundaries = missing)).isValidFor(artifact, transfer, false))
        assertFalse(verified.copy(recordingProof = proof.copy(decodedResumeBoundaries =
            listOf(boundaries[0].copy(sampleIndex = 11), boundaries[1]))).isValidFor(artifact, transfer, false))
        assertTrue(verified.copy(recordingProof = proof.copy(decodedResumeBoundaries =
            boundaries.map { it.copy(ptsUs = it.ptsUs + 100) })).isValidFor(artifact, transfer, false))
        assertFalse(verified.copy(recordingProof = proof.copy(decodedResumeBoundaries =
            boundaries.map { it.copy(ptsUs = it.ptsUs + 101) })).isValidFor(artifact, transfer, false))
        assertFalse(verified.isValidFor(artifact, transfer.copy(recordingExpectation = expected.copy(resumeBoundaries = null)), false))
        assertFalse(evidence(output()).copy(recordingProof = base.recordingProof!!.copy(decodedResumeBoundaries = null))
            .isValidFor(artifact, output(), false))
        assertThrows(IllegalArgumentException::class.java) { proof.copy(decodedResumeBoundaries = boundaries.reversed()) }
        val source = boundaries.toMutableList(); val defensive = proof.copy(decodedResumeBoundaries = source)
        source.clear(); assertEquals(boundaries, defensive.decodedResumeBoundaries)
        assertThrows(Exception::class.java) { (defensive.decodedResumeBoundaries as MutableList<RecordingResumeBoundary>).clear() }
    }

    @Test fun stableRecordingErrorsHaveNoAutomaticRetryPolicy() {
        val failures = mapOf(RecordingError.CONSENT_DENIED to SaveError.CONSENT_DENIED,
            RecordingError.AUDIO_PERMISSION_DENIED to SaveError.AUDIO_PERMISSION_DENIED,
            RecordingError.CAPTURE_UNAVAILABLE to SaveError.CAPTURE_UNAVAILABLE,
            RecordingError.FGS_START_REJECTED to SaveError.FGS_START_REJECTED,
            RecordingError.RESOURCE_RELEASE_UNCONFIRMED to SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            RecordingError.FINALIZATION_FAILED to SaveError.FINALIZATION_FAILED)
        failures.forEach { (input, expected) ->
            assertEquals(expected, SaveErrors.classify(RecordingFailure(input)))
            assertFalse(SaveErrors.policy(expected).retryable)
        }
    }

    private fun <T> recordingCall(block: () -> T): FutureTask<T> = FutureTask(Callable { block() }).also { task ->
        // Surface worker assertions through FutureTask and keep all waits bounded.
        thread(isDaemon = true, name = "recording-publication-test") { task.run() }
    }

    private fun requestDiscardWhileBlocked(h: Harness, phase: SavePhase) {
        // Match AndroidSaveRuntime: the immediate signal precedes the queued durable command.
        // Each call must return while the verifier or provisional store is still blocked.
        assertEquals(phase, recordingCall { h.coordinator.phaseSnapshot("capture") }.get(3, TimeUnit.SECONDS))
        recordingCall { h.coordinator.signalUserCancel("capture") }.get(3, TimeUnit.SECONDS)
        val cancelled = recordingCall { h.coordinator.cancel("capture") }.get(3, TimeUnit.SECONDS)
        assertEquals(TaskState.RUNNING, cancelled!!.state)
        assertEquals(listOf("cancel:capture", "cancel:capture"), h.commands)
    }

    @Test fun discardAfterNativeDrainDuringVerificationRejectsLateEvidenceAndNeverPublishes() {
        val verificationEntered = CountDownLatch(1); val releaseVerification = CountDownLatch(1)
        val publications = AtomicInteger()
        val h = Harness(verify = { transfer ->
            verificationEntered.countDown()
            assertTrue(releaseVerification.await(10, TimeUnit.SECONDS))
            // Deliberately return valid evidence even after cancellation: the coordinator must fence it.
            evidence(transfer)
        })
        h.journal.beforeSuccess = { publications.incrementAndGet() }
        assertTrue(h.submit() is SaveSubmission.Accepted)
        val execution = recordingCall { h.coordinator.execute("capture")!! }
        lateinit var result: SaveRecord
        try {
            assertTrue(verificationEntered.await(3, TimeUnit.SECONDS))
            val finalized = h.coordinator.record("capture")!!
            assertEquals(SavePhase.VERIFYING, finalized.phase)
            assertTrue(finalized.transfer!!.recordingExpectation!!.finalized)
            assertTrue(finalized.transfer!!.recordingExpectation!!.resourcesReleased)
            assertEquals(1, h.captureCalls); assertEquals(1, h.verifierCalls)
            requestDiscardWhileBlocked(h, SavePhase.VERIFYING)
            assertFalse(execution.isDone)
            assertEquals(0, publications.get()); assertEquals(0, h.store.commitCalls)
        } finally {
            releaseVerification.countDown()
            result = execution.get(3, TimeUnit.SECONDS)
        }
        assertEquals(TaskState.CANCELLED, result.task.state); assertEquals(SaveError.CANCELLED, result.error)
        assertNotNull(result.transfer); assertNull(result.verification); assertNull(result.receipt)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("capture")!!.state)
        assertEquals(0, publications.get()); assertEquals(0, h.store.commitCalls)
        assertTrue(h.store.committed.isEmpty()); assertEquals(listOf(artifact to false), h.store.cleanup)
        assertEquals(0, h.networkCalls)
    }

    @Test fun discardDuringProvisionalCommitIsNonblockingAndNeverPublishes() {
        assertDiscardDuringProvisionalStep(blockInspection = false)
    }

    @Test fun discardDuringProvisionalInspectionIsNonblockingAndNeverPublishes() {
        assertDiscardDuringProvisionalStep(blockInspection = true)
    }

    private fun assertDiscardDuringProvisionalStep(blockInspection: Boolean) {
        val journal = MemorySaveJournal()
        val provisionalEntered = CountDownLatch(1); val releaseProvisional = CountDownLatch(1)
        val publications = AtomicInteger()
        journal.beforeSuccess = { publications.incrementAndGet() }
        fun <T> blockProvisional(result: T): T = synchronized(journal) {
            // A durable read here would block. phaseSnapshot and the immediate cancellation signal
            // must use memory only, even while a provisional storage operation owns this monitor.
            provisionalEntered.countDown()
            assertTrue(releaseProvisional.await(10, TimeUnit.SECONDS))
            result // Return the late receipt/inspection despite the cancellation signal.
        }
        val store = object : FakeMediaStore() {
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                val receipt = super.commit(artifact, context)
                return if (blockInspection) receipt else blockProvisional(receipt)
            }
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
                val inspection = super.inspectCommitted(commitId, context)
                return if (blockInspection) blockProvisional(inspection) else inspection
            }
        }
        val h = Harness(journal = journal, store = store)
        assertTrue(h.submit() is SaveSubmission.Accepted)
        val execution = recordingCall { h.coordinator.execute("capture")!! }
        lateinit var result: SaveRecord
        try {
            assertTrue(provisionalEntered.await(3, TimeUnit.SECONDS))
            assertEquals(1, h.captureCalls); assertEquals(1, h.verifierCalls)
            assertEquals(1, store.commitCalls); assertEquals(1, store.committed.size)
            assertEquals(TaskState.RUNNING, h.coordinator.snapshot("capture")!!.state)
            requestDiscardWhileBlocked(h, SavePhase.COMMITTING)
            assertFalse(execution.isDone); assertEquals(0, publications.get())
        } finally {
            releaseProvisional.countDown()
            result = execution.get(3, TimeUnit.SECONDS)
        }
        assertEquals(TaskState.CANCELLED, result.task.state); assertEquals(SaveError.CANCELLED, result.error)
        assertEquals(SavePhase.COMMITTING, result.phase)
        assertNotNull(result.transfer); assertNotNull(result.verification); assertNull(result.receipt)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("capture")!!.state)
        assertEquals(0, publications.get())
        // FakeMediaStore.committed holds invisible provisional receipts, not published library rows.
        // CANCELLED never crosses the journal's SUCCEEDED publication boundary or deletes them speculatively.
        assertEquals(1, store.committed.size); assertTrue(store.cleanup.isEmpty())
        assertEquals(0, h.networkCalls)
    }

    @Test fun publicationThatWinsBeforeDelayedDiscardRetainsSuccessAndCommittedMedia() {
        val cleanupEntered = CountDownLatch(1); val releaseCleanup = CountDownLatch(1)
        val publications = AtomicInteger()
        val store = object : FakeMediaStore() {
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) {
                assertTrue(committed)
                cleanupEntered.countDown()
                assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS))
                super.cleanup(artifact, committed)
            }
        }
        val h = Harness(store = store)
        h.journal.beforeSuccess = { publications.incrementAndGet() }
        assertTrue(h.submit() is SaveSubmission.Accepted)
        val execution = recordingCall { h.coordinator.execute("capture")!! }
        lateinit var result: SaveRecord
        lateinit var published: SaveRecord
        try {
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            // Keep the execution alive after publication so the delayed signal reaches its Runtime.
            published = h.coordinator.record("capture")!!
            assertEquals(TaskState.SUCCEEDED, published.task.state); assertNotNull(published.receipt)
            assertEquals(SavePhase.CLEANUP, h.coordinator.phaseSnapshot("capture"))
            assertEquals(1, publications.get()); assertFalse(execution.isDone)
            recordingCall { h.coordinator.signalUserCancel("capture") }.get(3, TimeUnit.SECONDS)
            val cancelled = recordingCall { h.coordinator.cancel("capture") }.get(3, TimeUnit.SECONDS)
            assertEquals(TaskState.SUCCEEDED, cancelled!!.state)
            assertEquals(published, h.coordinator.record("capture"))
            assertEquals(listOf("cancel:capture", "cancel:capture"), h.commands)
        } finally {
            releaseCleanup.countDown()
            result = execution.get(3, TimeUnit.SECONDS)
        }
        assertEquals(published, result); assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(1, publications.get()); assertEquals(1, store.commitCalls)
        assertEquals(result.receipt, store.committed["capture"])
        assertEquals(listOf(artifact to true), store.cleanup)
        // A still later queued cancellation must also preserve the completed publication.
        assertEquals(result.task, h.coordinator.cancel("capture"))
        assertEquals(result, h.coordinator.record("capture")); assertEquals(1, publications.get())
    }

    @Test fun keepAfterNativeDrainDoesNotCancelVerificationOrPublication() {
        val verificationEntered = CountDownLatch(1); val releaseVerification = CountDownLatch(1)
        val publications = AtomicInteger()
        lateinit var captureContext: OperationContext
        val h = Harness(block = { context -> captureContext = context; output() }, verify = { transfer ->
            verificationEntered.countDown()
            assertTrue(releaseVerification.await(10, TimeUnit.SECONDS))
            assertFalse(captureContext.cancelToken.isCancelled)
            evidence(transfer)
        })
        h.journal.beforeSuccess = { publications.incrementAndGet() }
        assertTrue(h.submit() is SaveSubmission.Accepted)
        val execution = recordingCall { h.coordinator.execute("capture")!! }
        lateinit var result: SaveRecord
        try {
            assertTrue(verificationEntered.await(3, TimeUnit.SECONDS))
            val finalized = h.coordinator.record("capture")!!
            assertTrue(finalized.transfer!!.recordingExpectation!!.resourcesReleased)
            assertEquals(SavePhase.VERIFYING, h.coordinator.phaseSnapshot("capture"))
            // KEEP is the save disposition, including when native stop/drain has already finished.
            recordingCall { h.coordinator.stopAndSave("capture") }.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("stop:capture"), h.commands)
            assertFalse(captureContext.cancelToken.isCancelled)
            assertEquals(TaskState.RUNNING, h.coordinator.snapshot("capture")!!.state)
            assertFalse(execution.isDone); assertEquals(0, publications.get())
        } finally {
            releaseVerification.countDown()
            result = execution.get(3, TimeUnit.SECONDS)
        }
        assertEquals(TaskState.SUCCEEDED, result.task.state); assertNotNull(result.receipt)
        assertEquals(1, publications.get()); assertEquals(1, h.store.commitCalls)
        assertEquals(result.receipt, h.store.committed["capture"])
        assertEquals(listOf(artifact to true), h.store.cleanup)
        assertEquals(listOf("stop:capture"), h.commands)
        assertEquals(1, h.captureCalls); assertEquals(1, h.verifierCalls); assertEquals(0, h.networkCalls)
    }

}
