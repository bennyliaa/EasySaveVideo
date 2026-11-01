package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.core.model.UserWait
import com.easysavevideo.core.model.ResumeAction
import com.easysavevideo.discovery.*
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import com.easysavevideo.recording.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

class RecordingSaveEvaluationTaskBindingTest {
    private val descriptor = CaptureDescriptor("capture", 7, RecordingAudioChoice.NONE)
    private val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
    private fun admission(id: String = "capture") = OrdinaryDestinationAdmission(id,
        OrdinaryDestinationChoice.APP_LIBRARY, "destination-operation", "destination-grant")
    private fun accepted(d: CaptureDescriptor = descriptor) = SaveRecord(
        SaveRequest(d.sessionId, "key", SaveInput.Capture(d)),
        TaskSnapshot(d.sessionId, 0, TaskState.QUEUED, TaskMethod.RECORD), "commit", "operation", 10_000, 0)
    private fun expectation(d: CaptureDescriptor = descriptor, reason: RecordingStopReason = RecordingStopReason.USER_STOP,
        legacy: Boolean = false, audioOutcome: RecordingAudioOutcome = if (d.audioChoice == RecordingAudioChoice.NONE)
            RecordingAudioOutcome.NO_AUDIO_REQUESTED else RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED): RecordingArtifactExpectation {
        val audio = if (d.audioChoice == RecordingAudioChoice.NONE) null else RecordingTrackEvidence(46, 0, 960_000, 20_000)
        return RecordingArtifactExpectation(d, d.requestedProfile, audioOutcome, reason, video, audio, 1_000_000, 1024,
            resumeBoundaries = if (legacy) null else emptyList())
    }
    private fun committed(e: RecordingArtifactExpectation = expectation(), positions: Set<DecodePosition> = DecodePosition.entries.toSet()): SaveRecord {
        val proof = VerificationEvidence("artifact", 1024, "a".repeat(64), true, 1, e.audioTrackCount, 1000,
            e.actualProfile.width, e.actualProfile.height, false, positions, true,
            recordingProof = RecordingVerificationProof(e.video, e.audio, "video/avc",
                if (e.audio == null) null else "audio/mp4a-latm", if (e.audio == null) null else e.actualProfile.audioSampleRate,
                if (e.audio == null) null else e.actualProfile.audioChannels, e.video.lastPtsUs, true,
                decodedResumeBoundaries = emptyList()))
        return accepted(e.descriptor).copy(task = TaskSnapshot(e.descriptor.sessionId, 8, TaskState.SUCCEEDED, TaskMethod.RECORD),
            phase = SavePhase.CLEANUP, artifact = StagingArtifact("artifact", "commit"),
            transfer = TransferResult("artifact", 1024, "video/mp4", 1024, recordingExpectation = e),
            verification = proof, receipt = MediaCommitReceipt("commit", "artifact", "media", 1024, "a".repeat(64)))
    }
    private fun failure(error: SaveError = SaveError.ENCODER_FAILED, phase: SavePhase = SavePhase.EXECUTING,
        state: TaskState = TaskState.FAILED) = accepted().copy(task = TaskSnapshot("capture", 4, state, TaskMethod.RECORD,
            waitingUser = if (state == TaskState.WAITING_USER) UserWait(1000, ResumeAction.RETRY) else null), phase = phase,
            error = error, waitReason = if (state == TaskState.WAITING_USER) SaveWaitReason.RECOVERY else null)
    private fun native(e: RecordingArtifactExpectation? = expectation()) = RecordingSaveEvaluationNativeEvidence(true, true, e)

    private inner class Fixture(capacity: Int = 100, val current: () -> Boolean = { true },
        mode: SaveEvaluationMode = SaveEvaluationMode.JOINT) {
        val links = RecordingSaveEvaluationTaskLinks(capacity)
        val session = DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE, mode)
        val page = PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/evaluated-page"), "fixture", SearchFilters())!!
        val admission = admission()
        var binding: RecordingSaveEvaluationBinding? = null
        init { session.begin(); session.register(page); session.beginPage(page.candidateId) }
        fun arm() = links.arm(admission, session, page.candidateId, current).also { if (it != null) binding = it }
        fun prepare() = links.prepared(admission, descriptor)
        fun accept() = links.accepted(binding, descriptor, accepted(), false)
        fun bind(): RecordingSaveEvaluationBinding {
            assertNotNull(arm()); assertSame(binding, prepare()); assertSame(binding, accept()); return binding!!
        }
        fun witness(e: RecordingArtifactExpectation = expectation()) {
            binding!!.nativeStarted(descriptor); binding!!.nativeReleased(descriptor); binding!!.nativeCompleted(descriptor, e)
        }
        fun counts() = session.snapshot().evaluation.overall
    }

    @Test fun completeUserStoppedSilentAndInternalAudioRecordingsRequireRealFinalCommit() {
        for (d in listOf(descriptor, descriptor.copy(audioChoice = RecordingAudioChoice.INTERNAL))) {
            val e = expectation(d)
            assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT, classifyEvaluationRecording(d, committed(e), native(e)))
        }
    }
    @Test fun successRequiresExactSessionGenerationAndNativeCompletedOutput() {
        val final = committed()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor.copy(generation = 8), final, native()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor.copy(sessionId = "another"), final, native()))
        for (n in listOf(native().copy(started = false), native().copy(released = false), native(null),
            native(expectation(descriptor.copy(generation = 8)))))
            assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, final, n))
    }
    @Test fun provisionalOrMutableProofCannotBecomeSuccessOrFailure() {
        val final = committed()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, final.copy(phase = SavePhase.COMMITTING), native()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor,
            final.copy(task = TaskSnapshot("capture", 8, TaskState.FAILED, TaskMethod.RECORD), error = SaveError.INVALID_MEDIA), native()))
        val positions = DecodePosition.entries.toMutableSet()
        val mutable = committed(positions = positions)
        positions.clear()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, mutable, native()))
    }
    @Test fun legacyMissingResumeProofAndMissingExpectedSizeStayUnknown() {
        val e = expectation(legacy = true)
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, committed(e), native(e)))
        val final = committed()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor,
            final.copy(transfer = final.transfer!!.copy(expectedTotalBytes = null)), native()))
    }
    @Test fun verifiedSystemOrBudgetPrefixAndUnknownAudioNeverCountAsSuccess() {
        for (reason in listOf(RecordingStopReason.SYSTEM_STOPPED, RecordingStopReason.SESSION_LIMIT,
                RecordingStopReason.BYTE_LIMIT, RecordingStopReason.AUDIO_LOST, RecordingStopReason.ENCODER_FAILED,
                RecordingStopReason.STORAGE_LOW, RecordingStopReason.DEADLINE_EXCEEDED)) {
            val e = expectation(reason = reason)
            assertEquals(SaveEvaluationTaskResult.BLOCKED, classifyEvaluationRecording(descriptor, committed(e), native(e)))
        }
        val d = descriptor.copy(audioChoice = RecordingAudioChoice.INTERNAL)
        for (audio in listOf(RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED, RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN)) {
            val e = expectation(d, audioOutcome = audio)
            assertEquals(SaveEvaluationTaskResult.BLOCKED, classifyEvaluationRecording(d, committed(e), native(e)))
        }
    }
    @Test fun onlyExplicitExecutedReleasedTerminalFailuresCount() {
        for (error in listOf(SaveError.INVALID_MEDIA, SaveError.TIMESTAMP_INVALID, SaveError.AUDIO_FAILED,
                SaveError.ENCODER_FAILED, SaveError.FINALIZATION_FAILED)) {
            for (phase in listOf(SavePhase.EXECUTING, SavePhase.VERIFYING)) {
                assertEquals(SaveEvaluationTaskResult.DEFINITIVE_FAILURE,
                    classifyEvaluationRecording(descriptor, failure(error, phase), native(null)))
                assertEquals(SaveEvaluationTaskResult.UNKNOWN,
                    classifyEvaluationRecording(descriptor, failure(error, phase), native(null).copy(started = false)))
                assertEquals(SaveEvaluationTaskResult.UNKNOWN,
                    classifyEvaluationRecording(descriptor, failure(error, phase), native(null).copy(released = false)))
            }
        }
    }
    @Test fun preflightCancellationPermissionAndShortCaptureNeverFailTheMethod() {
        for (state in listOf(TaskState.CANCELLED, TaskState.WAITING_USER, TaskState.INTERRUPTED))
            assertEquals(SaveEvaluationTaskResult.BLOCKED, classifyEvaluationRecording(descriptor, failure(state = state), native(null)))
        for (error in listOf(SaveError.CANCELLED, SaveError.CONSENT_DENIED, SaveError.AUDIO_PERMISSION_DENIED,
                SaveError.FGS_START_REJECTED, SaveError.CAPTURE_UNAVAILABLE, SaveError.CAPTURE_TOO_SHORT,
                SaveError.CAPTURE_EMPTY, SaveError.STORAGE_FULL, SaveError.BYTE_LIMIT_EXCEEDED, SaveError.PROFILE_UNSUPPORTED))
            assertEquals(SaveEvaluationTaskResult.BLOCKED, classifyEvaluationRecording(descriptor, failure(error), native(null)))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor,
            failure(phase = SavePhase.PREPARING), native(null)))
    }
    @Test fun unknownRecoveryAndTimeoutErrorsNeverInventFailure() {
        for (error in listOf(SaveError.RESOURCE_RELEASE_UNCONFIRMED, SaveError.RECOVERY_REQUIRED, SaveError.JOURNAL_UNAVAILABLE,
                SaveError.COMMIT_MISMATCH, SaveError.UNKNOWN_TECHNICAL_ERROR, SaveError.DEADLINE_EXCEEDED,
                SaveError.KEYFRAME_TIMEOUT, SaveError.NETWORK_TIMEOUT))
            assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, failure(error), native(null)))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, null, native(null)))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, classifyEvaluationRecording(descriptor, accepted(), native(null)))
    }
    @Test fun regionAndSegmentedDescriptorCannotEnrollEvenWithMatchingAdmission() {
        val region = descriptor.copy(regionCaptureRecipe = RegionCaptureRecipe(RegionNormalizedRect.FULL,
            CaptureDisplayGeometry(1280, 720, 0), CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY))
        val f = Fixture(); f.arm()
        assertNull(f.links.prepared(f.admission, region))
        assertNull(f.links.accepted(f.binding, region, accepted(region), false))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,
            classifyEvaluationRecording(region, committed(expectation(region)), native(expectation(region))))
        assertEquals(0, f.counts().save3.tested)
    }
    @Test fun noEvaluationContextDoesNotReadAllocateOrCollect() {
        val f = Fixture()
        assertNull(f.prepare()); assertNull(f.accept())
        assertNull(f.links.afterExecution(null, descriptor) { error("No context must not read a record") })
        assertEquals(0, f.session.snapshot().unfinishedMethodAttempts); assertEquals(0, f.counts().save3.tested)
    }
    @Test fun exactFreshAcceptanceAndNativeCommitCompleteSave3Once() {
        val f = Fixture(); f.bind(); f.witness(); var reads = 0
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT, f.links.afterExecution(f.binding, descriptor) { reads++; committed() })
        assertNull(f.links.afterExecution(f.binding, descriptor) { error("Duplicate completion") })
        assertEquals(1, reads); assertEquals(1, f.counts().save3.succeeded)
        assertEquals(0, f.counts().save1.tested); assertEquals(0, f.counts().save2.tested)
        assertEquals(0, f.counts().fallbackEligiblePages)
    }
    @Test fun copiedAdmissionAndEqualDescriptorCannotStealLiveOwner() {
        val f = Fixture(); f.arm()
        assertNull(f.links.prepared(f.admission.copy(), descriptor))
        assertSame(f.binding, f.prepare())
        assertNull(f.links.accepted(f.binding, descriptor.copy(), accepted(), false))
        assertSame(f.binding, f.accept())
        assertNull(f.links.afterExecution(f.binding, descriptor.copy()) { error("Copied owner") })
        f.witness(); assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,
            f.links.afterExecution(f.binding, descriptor) { committed() })
    }
    @Test fun stalePageBeforePreparationRejectsButPermissionPauseAfterPreparationIsAllowed() {
        var current = false
        val f = Fixture(current = { current }); f.arm(); assertNull(f.prepare())
        assertEquals(0, f.session.snapshot().unfinishedMethodAttempts)
        current = true
        assertNotNull(f.arm()); assertNotNull(f.prepare()); current = false
        assertNotNull(f.accept()); f.witness()
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT, f.links.afterExecution(f.binding, descriptor) { committed() })
    }
    @Test fun preparationDropsSourceCallbackAndRejectsReplays() {
        var reads = 0
        val f = Fixture(current = { reads++; true }); f.arm(); f.prepare()
        assertNull(f.prepare()); assertSame(f.binding, f.accept()); f.witness()
        f.links.afterExecution(f.binding, descriptor) { committed() }
        assertEquals(1, reads)
    }
    @Test fun sourceExceptionsDoNotEscapeOrBeginAnAttempt() {
        val f = Fixture(current = { error("Sensitive source failure") }); f.arm()
        assertNull(f.prepare()); assertNull(f.accept()); assertEquals(0, f.session.snapshot().unfinishedMethodAttempts)
    }
    @Test fun nativeEventsBeforeAcceptanceOrWrongOwnerCannotSupplyEvidence() {
        val f = Fixture(); f.arm(); f.prepare(); f.witness(); f.accept()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, f.links.afterExecution(f.binding, descriptor) { committed() })
        val g = Fixture(); g.bind()
        g.binding!!.nativeStarted(descriptor.copy()); g.binding!!.nativeReleased(descriptor.copy())
        g.binding!!.nativeCompleted(descriptor.copy(), expectation())
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, g.links.afterExecution(g.binding, descriptor) { committed() })
    }
    @Test fun missingNativeCaptureNeverTurnsTerminalErrorIntoFailure() {
        val f = Fixture(); f.bind(); f.binding!!.nativeReleased(descriptor)
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, f.links.afterExecution(f.binding, descriptor) { failure() })
        assertEquals(0, f.counts().save3.failed); assertEquals(1, f.session.finish().unfinishedMethodAttempts)
    }
    @Test fun realFailureAndNoWorkerHandoffRemainDistinct() {
        val f = Fixture(); f.bind(); f.binding!!.nativeStarted(descriptor); f.binding!!.nativeReleased(descriptor)
        assertEquals(SaveEvaluationTaskResult.DEFINITIVE_FAILURE, f.links.afterExecution(f.binding, descriptor) { failure() })
        assertEquals(1, f.counts().save3.failed)
        val g = Fixture(); g.bind(); g.links.notLaunched(g.binding, descriptor)
        assertEquals(1, g.counts().save3.blocked); assertEquals(0, g.counts().save3.failed)
        assertNull(g.links.afterExecution(g.binding, descriptor) { error("Never handed off") })
    }
    @Test fun pendingPermissionCancelLeavesNoInventedMethodAttempt() {
        val f = Fixture(); f.arm(); f.prepare(); f.links.notLaunched(f.binding, descriptor)
        assertEquals(0, f.counts().save3.tested); assertEquals(0, f.session.snapshot().unfinishedMethodAttempts)
        assertNull(f.accept())
    }
    @Test fun freshAcceptedRecordOnlyNeverExistingRestoredOrDirtyRows() {
        for (record in listOf(committed(), accepted().copy(phase = SavePhase.PREPARING),
                accepted().copy(task = TaskSnapshot("capture", 1, TaskState.QUEUED, TaskMethod.RECORD)))) {
            val f = Fixture(); f.arm(); f.prepare()
            assertNull(f.links.accepted(f.binding, descriptor, record, false))
            assertEquals(0, f.session.snapshot().unfinishedMethodAttempts)
        }
        val f = Fixture(); f.arm(); f.prepare()
        assertNull(f.links.accepted(f.binding, descriptor, accepted(), true))
    }
    @Test fun repeatedAcceptedCallsCannotEraseRealWorkerBinding() {
        val f = Fixture(); f.bind()
        assertNull(f.links.accepted(f.binding, descriptor, accepted(), true))
        assertNull(f.links.accepted(f.binding, descriptor, committed(), false))
        f.witness(); assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,
            f.links.afterExecution(f.binding, descriptor) { committed() })
    }
    @Test fun finalCommitOperationOrGenerationMismatchRemainsUnknown() {
        val otherCommit = committed().let { it.copy(commitId = "other-commit", artifact = it.artifact!!.copy(commitId = "other-commit"),
            receipt = it.receipt!!.copy(commitId = "other-commit")) }
        for (record in listOf(otherCommit, committed().copy(operationId = "other-operation"),
                committed(expectation(descriptor.copy(generation = 9))))) {
            val f = Fixture(); f.bind(); f.witness()
            assertEquals(SaveEvaluationTaskResult.UNKNOWN, f.links.afterExecution(f.binding, descriptor) { record })
            assertEquals(0, f.counts().save3.tested)
        }
    }
    @Test fun recordReadFailureClosedSessionAndExplicitCloseNeverChangeSealedReport() {
        val f = Fixture(); f.bind(); f.witness()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, f.links.afterExecution(f.binding, descriptor) { error("Unavailable journal") })
        assertEquals(0, f.counts().save3.failed)
        val g = Fixture(); g.bind(); val sealed = g.session.finish()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN, g.links.afterExecution(g.binding, descriptor) { error("Closed run") })
        assertEquals(sealed, g.session.snapshot())
        val h = Fixture(); h.bind(); h.links.close(h.session)
        assertNull(h.links.afterExecution(h.binding, descriptor) { error("Detached run") })
    }
    @Test fun closedOrAbandonedCapabilityCannotConsumeRearmedSameTask() {
        for (abandon in listOf(false, true)) {
            val f = Fixture(); val old = f.bind()
            if (abandon) f.links.abandon(old) else f.links.close(f.session)
            f.session.finish()
            val g = Fixture()
            val next = f.links.arm(g.admission, g.session, g.page.candidateId) { true }!!
            assertSame(next, f.links.prepared(g.admission, descriptor))
            assertSame(next, f.links.accepted(next, descriptor, accepted().copy(operationId = "new-operation"), false))
            old.nativeStarted(descriptor); old.nativeReleased(descriptor); old.nativeCompleted(descriptor, expectation())
            f.links.notLaunched(old, descriptor)
            assertNull(f.links.afterExecution(old, descriptor) { error("Old capability cannot read") })
            next.nativeStarted(descriptor); next.nativeReleased(descriptor); next.nativeCompleted(descriptor, expectation())
            assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,
                f.links.afterExecution(next, descriptor) { committed().copy(operationId = "new-operation") })
            assertEquals(1, g.counts().save3.succeeded)
        }
    }
    @Test fun boundedArmsAndSessionCloseRejectExtraOrStaleContexts() {
        val f = Fixture(capacity = 1); assertNotNull(f.arm()); assertNull(f.arm())
        assertNull(f.links.arm(admission("other"), f.session, f.page.candidateId) { true })
        f.links.close(f.session); f.session.finish(); assertNull(f.arm())
        val g = Fixture(); g.arm(); g.session.finish(); assertNull(g.prepare())
        val h = Fixture(); h.arm(); h.prepare(); h.session.finish(); assertNull(h.accept())
    }
    @Test fun unstartedPageCannotIssueRecordingMethod() {
        val s = DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE, SaveEvaluationMode.JOINT); s.begin()
        val page = PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/pending"), "fixture", SearchFilters())!!
        s.register(page)
        val links = RecordingSaveEvaluationTaskLinks(); val a = admission()
        val binding = links.arm(a, s, page.candidateId) { true }!!; links.prepared(a, descriptor)
        assertNull(links.accepted(binding, descriptor, accepted(), false))
        assertEquals(0, s.snapshot().evaluation.overall.actualTestPages)
    }
    @Test fun independentRecordingSkipsPrimariesAndUsesItsOwnDenominator() {
        val f = Fixture(mode = SaveEvaluationMode.INDEPENDENT_RECORD); f.bind(); f.witness()
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT, f.links.afterExecution(f.binding, descriptor) { committed() })
        assertEquals(1, f.counts().actualTestPages); assertEquals(1, f.counts().independentRecord.succeeded)
        assertEquals(1, f.counts().save1.skipped); assertEquals(1, f.counts().save2.skipped)
        assertEquals(0, f.counts().save1.failed); assertEquals(0, f.counts().save2.failed)
        assertEquals(0, f.counts().save3.tested); assertEquals(0, f.counts().fallbackSave3.tested)
        assertEquals(0, f.counts().fallbackEligiblePages); assertFalse(f.counts().partial)
        assertThrows(IllegalArgumentException::class.java) { f.session.beginMethod(f.page.candidateId, SaveEvaluationMethod.SAVE1) }
    }
    @Test fun jointRecordingRunsWithPrimarySuccessWhileFallbackRequiresTwoRealFailures() {
        for (primary in listOf(SaveEvaluationOutcome.SUCCEEDED, SaveEvaluationOutcome.FAILED)) {
            val f = Fixture()
            for (method in listOf(SaveEvaluationMethod.SAVE1, SaveEvaluationMethod.SAVE2))
                f.session.finishMethod(f.session.beginMethod(f.page.candidateId, method), primary)
            f.bind(); f.witness()
            f.links.afterExecution(f.binding, descriptor) { committed() }
            assertEquals(1, f.counts().save3.succeeded)
            assertEquals(if (primary == SaveEvaluationOutcome.FAILED) 1 else 0, f.counts().fallbackSave3.succeeded)
            assertFalse(f.counts().partial)
        }
    }
    @Test fun actualRuntimeCarriesOptionalExactBindingIntoWorkerFinally() {
        val source = java.io.File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertTrue(source.contains("private val recordingEvaluationLinks = RecordingSaveEvaluationTaskLinks()"))
        assertTrue(source.contains("recordingEvaluationLinks.prepared(admission, descriptor)"))
        assertTrue(source.contains("recorder.prepare(descriptor, audioAllowed, binding, evaluation)"))
        assertTrue(source.contains("recordingEvaluationLinks.accepted(it, descriptor, result.record, result.existing)"))
        assertTrue(source.contains("recordingEvaluationLinks.afterExecution(it, descriptor) { storage.read(id) }"))
        assertTrue(source.contains("recordingEvaluationLinks.notLaunched(it, descriptor)"))
        assertTrue(source.contains("recordingEvaluationLinks.close(session)"))
    }
    @Test fun nativeRuntimeHooksAreOptionalAndAbsentFromSegmentedLane() {
        val source = java.io.File("src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordSavePort.kt").readText()
        assertTrue(source.contains("evaluationBinding: RecordingSaveEvaluationBinding? = null"))
        assertTrue(source.contains("if (progress.state == RecordingState.RECORDING) evaluation?.nativeStarted(owned.descriptor)"))
        assertTrue(source.contains("evaluation?.nativeReleased(owned.descriptor)"))
        assertTrue(source.contains("evaluation?.nativeCompleted(owned.descriptor, outcome.expectation)"))
        assertFalse(java.io.File("src/main/kotlin/com/easysavevideo/app/recording/AndroidSegmentRecordingRuntime.kt").readText()
            .contains("RecordingSaveEvaluationBinding"))
        assertFalse(source.contains("if (evaluation?."))
    }
}
