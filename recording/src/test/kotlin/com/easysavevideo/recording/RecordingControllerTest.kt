package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingControllerTest {
    private val profile = RecordingProfile()
    private fun request(generation: Long = 1, key: String = "key-$generation",
        audio: RecordingAudioChoice = RecordingAudioChoice.NONE) = RecordingRequest(
        "session-$generation", key, CaptureDescriptor("session-$generation", generation, audio, profile))
    private fun prepared(c: RecordingController, r: RecordingRequest = request()): RecordingSessionId {
        assertTrue(c.start(r, 0, true).accepted)
        assertEquals(setOf(RecordingEffect.REQUEST_SYSTEM_CONSENT), c.userConfirmed(r.descriptor.session, 1, true).effects)
        assertEquals(setOf(RecordingEffect.START_CAPTURE), c.consentResult(r.descriptor.session, 2, true, true).effects)
        return r.descriptor.session
    }
    private fun recording(c: RecordingController, r: RecordingRequest = request()): RecordingSessionId {
        val session = prepared(c, r)
        assertTrue(c.progress(session, 3, RecordingState.RECORDING,
            if (r.descriptor.audioChoice == RecordingAudioChoice.NONE) RecordingAudioStatus.NOT_REQUESTED
            else RecordingAudioStatus.PCM_OBSERVED, 1_000_000, profile).accepted)
        return session
    }
    private fun expectation(r: RecordingRequest = request(), duration: Long = 1_000_000,
        reason: RecordingStopReason = RecordingStopReason.USER_STOP) = RecordingArtifactExpectation(
        r.descriptor, profile, RecordingAudioOutcome.NO_AUDIO_REQUESTED, reason,
        RecordingTrackEvidence(2, 0, duration - 33_333, 33_333, 1, true), null, duration, 1000)

    @Test fun thirtyThirdResumeSafelyStopsAndCancellationStillWins() {
        val c = RecordingController(); val session = recording(c)
        var now = 4L
        repeat(32) { index ->
            c.pause(session, now++, true)
            assertEquals(setOf(RecordingEffect.REQUEST_SYNC_FRAME), c.resume(session, now++, true).effects)
            assertEquals(index, c.snapshot().effectiveResumeCount) // Request alone has no evidence.
            assertTrue(c.progress(session, now++, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED,
                1_000_000L + index, profile, effectiveResumeCount = index + 1).accepted)
        }
        c.pause(session, now++, true)
        val limited = c.resume(session, now++, true)
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), limited.effects)
        assertEquals(RecordingStopReason.RESUME_LIMIT, limited.snapshot.stopReason)
        assertEquals(RecordingState.STOPPING, limited.snapshot.state)
        assertEquals(32, limited.snapshot.effectiveResumeCount)
        assertTrue(c.stop(session, now++).effects.isEmpty())
        assertEquals(setOf(RecordingEffect.CANCEL_AND_RELEASE), c.cancel(session, now++).effects)
        assertFalse(c.progress(session, now++, RecordingState.FINALIZING, RecordingAudioStatus.NOT_REQUESTED,
            1_000_031, profile, RecordingStopReason.RESUME_LIMIT, effectiveResumeCount = 32).accepted)
        c.releaseConfirmed(session, now++)
        assertFalse(c.captureFinished(session, now, expectation(reason = RecordingStopReason.RESUME_LIMIT)).accepted)
        assertEquals(RecordingState.CANCELLED, c.snapshot().state)
        assertFalse(c.snapshot().publicationAllowed)
    }

    @Test fun effectiveResumeObservationsCannotRegressOrExceedConfiguredCap() {
        for (next in listOf(-1, 0, 33)) {
            val c = RecordingController(); val session = recording(c)
            assertTrue(c.progress(session, 4, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED,
                1_000_000, profile, effectiveResumeCount = 1).accepted)
            assertEquals(RecordingError.INVALID_REQUEST, c.progress(session, 5, RecordingState.RECORDING,
                RecordingAudioStatus.NOT_REQUESTED, 1_000_000, profile, effectiveResumeCount = next).snapshot.error)
        }
        val c = RecordingController(RecordingLimits(maxResumeBoundaries = 1)); val session = recording(c)
        c.progress(session, 4, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED,
            1_000_000, profile, effectiveResumeCount = 1)
        c.pause(session, 5, true)
        assertEquals(RecordingStopReason.RESUME_LIMIT, c.resume(session, 6, true).snapshot.stopReason)
    }

    @Test fun userStopBeforeResumeLimitRequestHasNoSecondFinalizer() {
        val c = RecordingController(); val session = recording(c)
        c.progress(session, 4, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED,
            1_000_000, profile, effectiveResumeCount = 32)
        c.pause(session, 5, true)
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), c.stop(session, 6).effects)
        assertFalse(c.resume(session, 7, true).accepted)
        assertEquals(RecordingStopReason.USER_STOP, c.snapshot().stopReason)
        assertEquals(32, c.snapshot().effectiveResumeCount)
    }

    @Test fun c01_sameKeyIsIdempotentAndConflictingRequestRejected() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        assertEquals(RecordingCommandDisposition.DUPLICATE, c.start(r, 1, true).disposition)
        assertEquals(1L, c.snapshot().revision)
        assertTrue(c.start(r, 2, true).effects.isEmpty())
        assertEquals(RecordingError.INVALID_REQUEST, c.start(request(2, r.idempotencyKey), 3, true).error)
        assertEquals(RecordingError.BUSY, c.start(request(2), 4, true).error)
    }

    @Test fun c02_deniedConsentNeverStartsCaptureAndDoesNotPromptAgain() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        c.userConfirmed(r.descriptor.session, 1, true)
        val denied = c.consentResult(r.descriptor.session, 2, false, true)
        assertEquals(RecordingState.CANCELLED, denied.snapshot.state)
        assertEquals(RecordingError.CONSENT_DENIED, denied.snapshot.error)
        assertFalse(denied.snapshot.resourceOccupied)
        assertTrue(denied.effects.isEmpty())
        assertFalse(c.consentResult(r.descriptor.session, 3, true, true).accepted)
        assertTrue(c.start(r, 4, true).effects.isEmpty())
    }

    @Test fun c03_oldGenerationAndDuplicateConsentCannotCreateResources() {
        val c = RecordingController()
        val first = request()
        c.start(first, 0, true)
        c.cancel(first.descriptor.session, 1)
        val second = request(2)
        c.start(second, 2, true)
        assertEquals(RecordingError.STALE_SESSION, c.consentResult(first.descriptor.session, 3, true, true).error)
        c.userConfirmed(second.descriptor.session, 3, true)
        val start = c.consentResult(second.descriptor.session, 4, true, true)
        assertEquals(setOf(RecordingEffect.START_CAPTURE), start.effects)
        assertTrue(c.consentResult(second.descriptor.session, 5, true, true).effects.isEmpty())
        assertEquals(RecordingState.PREPARING, c.snapshot().state)
    }

    @Test fun c04_startAndConsentRecheckEntitlement() {
        val c = RecordingController()
        val r = request()
        assertEquals(RecordingError.ENTITLEMENT_REQUIRED, c.start(r, 0, false).error)
        assertEquals(RecordingState.IDLE, c.snapshot().state)
        c.start(r, 1, true)
        c.userConfirmed(r.descriptor.session, 2, true)
        val revoked = c.consentResult(r.descriptor.session, 3, true, false)
        assertEquals(RecordingState.CANCELLED, revoked.snapshot.state)
        assertEquals(RecordingStopReason.ENTITLEMENT_REVOKED, revoked.snapshot.stopReason)
        assertTrue(revoked.effects.isEmpty())
    }

    @Test fun c05_snapshotContainsIntentionAndNoRestartedControllerRetainsConsent() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        c.userConfirmed(r.descriptor.session, 1, true)
        assertEquals(RecordingState.AWAITING_SYSTEM_CONSENT, c.snapshot().state)
        assertEquals(r, c.snapshot().request)
        val restored = RecordingController()
        assertEquals(RecordingState.IDLE, restored.snapshot().state)
        assertEquals(RecordingError.STALE_SESSION, restored.consentResult(r.descriptor.session, 2, true, true).error)
    }

    @Test fun c06_failedPreparationRequestsOneReleaseAndRetainsFence() {
        val c = RecordingController()
        val session = prepared(c)
        val failed = c.failed(session, 3, RecordingError.FGS_START_REJECTED)
        assertEquals(setOf(RecordingEffect.CANCEL_AND_RELEASE), failed.effects)
        assertTrue(failed.snapshot.resourceOccupied)
        assertTrue(c.failed(session, 4, RecordingError.ENCODER_FAILED).effects.isEmpty())
        assertEquals(RecordingError.BUSY, c.start(request(2), 5, true).error)
        c.releaseConfirmed(session, 6)
        assertTrue(c.start(request(2), 7, true).accepted)
    }

    @Test fun c07_systemStopDuringPreparationFinalizesOnlyOnce() {
        val c = RecordingController()
        val session = prepared(c)
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), c.stop(session, 3, RecordingStopReason.SYSTEM_STOPPED).effects)
        assertTrue(c.stop(session, 4, RecordingStopReason.SIZE_CHANGED).effects.isEmpty())
        assertEquals(RecordingStopReason.SYSTEM_STOPPED, c.snapshot().stopReason)
    }

    @Test fun c08_cancelWinsOverStopAndLateSuccess() {
        val c = RecordingController()
        val session = recording(c)
        c.stop(session, 4)
        assertEquals(setOf(RecordingEffect.CANCEL_AND_RELEASE), c.cancel(session, 5).effects)
        c.releaseConfirmed(session, 6)
        assertEquals(RecordingState.CANCELLED, c.snapshot().state)
        assertFalse(c.captureFinished(session, 7, expectation()).accepted)
        assertFalse(c.snapshot().publicationAllowed)
        assertTrue(c.cancel(session, 8).effects.isEmpty())
    }

    @Test fun c09_repeatedPauseResumeStopProduceNoRepeatedEffects() {
        val c = RecordingController()
        val session = recording(c)
        assertEquals(setOf(RecordingEffect.PAUSE_CAPTURE), c.pause(session, 4, true).effects)
        assertTrue(c.pause(session, 5, true).effects.isEmpty())
        assertEquals(setOf(RecordingEffect.REQUEST_SYNC_FRAME), c.resume(session, 6, true).effects)
        assertTrue(c.resume(session, 7, true).effects.isEmpty())
        assertEquals(RecordingState.RESUMING, c.snapshot().state)
        c.progress(session, 8, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED, 1_100_000, profile)
        assertTrue(c.resume(session, 9, true).effects.isEmpty())
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), c.stop(session, 10).effects)
        assertTrue(c.stop(session, 11).effects.isEmpty())
    }

    @Test fun c10_pauseDoesNotBlockSystemStopOrCancellation() {
        for (cancel in listOf(false, true)) {
            val c = RecordingController()
            val session = recording(c)
            c.pause(session, 4, true)
            if (cancel) c.cancel(session, 5) else c.stop(session, 5, RecordingStopReason.SYSTEM_STOPPED)
            assertEquals(if (cancel) RecordingState.CANCELLING else RecordingState.STOPPING, c.snapshot().state)
            c.releaseConfirmed(session, 6)
            assertFalse(c.snapshot().resourceOccupied)
        }
    }

    @Test fun c11_badAdapterProgressFailsBoundedlyWithOneRelease() {
        val c = RecordingController()
        val session = recording(c)
        val result = c.progress(session, 4, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED, 999, profile)
        assertEquals(RecordingState.FAILED, result.snapshot.state)
        assertEquals(RecordingError.TIMESTAMP_INVALID, result.snapshot.error)
        assertEquals(setOf(RecordingEffect.CANCEL_AND_RELEASE), result.effects)
        assertEquals(RecordingError.BUSY, c.start(request(2), 5, true).error)
    }

    @Test fun c12_sessionLimitStartsSeparateFinalizationWindow() {
        val limits = RecordingLimits()
        val c = RecordingController(limits)
        val session = recording(c)
        val limitAt = limits.maxSessionDurationUs + 2
        val stopped = c.checkDeadlines(limitAt)
        assertEquals(RecordingState.STOPPING, stopped.snapshot.state)
        assertEquals(RecordingStopReason.SESSION_LIMIT, stopped.snapshot.stopReason)
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), stopped.effects)
        assertEquals(RecordingState.STOPPING, c.checkDeadlines(limitAt + 1).snapshot.state)
        c.releaseConfirmed(session, limitAt + 2)
        assertTrue(c.captureFinished(session, limitAt + 3,
            expectation(reason = RecordingStopReason.SESSION_LIMIT)).accepted)
    }

    @Test fun c13_timeoutDoesNotReleaseNativeLaneOrAcceptLateProduct() {
        val limits = RecordingLimits()
        val c = RecordingController(limits)
        val session = recording(c)
        c.stop(session, 4)
        c.checkDeadlines(4 + limits.finalizationTimeoutUs)
        assertEquals(RecordingState.RECOVERY_REQUIRED, c.snapshot().state)
        assertTrue(c.snapshot().resourceOccupied)
        assertEquals(RecordingError.BUSY, c.start(request(2), 5 + limits.finalizationTimeoutUs, true).error)
        c.releaseConfirmed(session, 6 + limits.finalizationTimeoutUs)
        assertFalse(c.captureFinished(session, 7 + limits.finalizationTimeoutUs, expectation()).accepted)
        assertTrue(c.start(request(2), 8 + limits.finalizationTimeoutUs, true).accepted)
        assertEquals(RecordingError.STALE_SESSION, c.releaseConfirmed(session, 9 + limits.finalizationTimeoutUs).error)
    }

    @Test fun finishedCaptureRequiresActualReleaseAndIsNotSavePublication() {
        val c = RecordingController()
        val session = recording(c)
        c.stop(session, 4)
        assertEquals(RecordingError.RESOURCE_RELEASE_UNCONFIRMED, c.captureFinished(session, 5, expectation()).error)
        c.releaseConfirmed(session, 6)
        val complete = c.captureFinished(session, 7, expectation())
        assertEquals(RecordingState.CAPTURE_FINISHED, complete.snapshot.state)
        assertTrue(complete.snapshot.publicationAllowed)
        assertEquals(RecordingOutcome.Completed(expectation()), complete.snapshot.outcome)
        assertEquals(RecordingCommandDisposition.DUPLICATE, c.captureFinished(session, 8, expectation()).disposition)
        c.cancel(session, 9)
        assertFalse(c.snapshot().publicationAllowed)
    }

    @Test fun audioDeniedRequiresExplicitNewSilentRequest() {
        val c = RecordingController()
        val r = request(audio = RecordingAudioChoice.INTERNAL)
        c.start(r, 0, true)
        assertEquals(setOf(RecordingEffect.REQUEST_AUDIO_PERMISSION), c.userConfirmed(r.descriptor.session, 1, false).effects)
        val denied = c.audioPermissionResult(r.descriptor.session, 2, false)
        assertEquals(RecordingAudioStatus.PERMISSION_DENIED, denied.snapshot.audioStatus)
        assertEquals(RecordingState.CANCELLED, denied.snapshot.state)
        assertTrue(denied.effects.isEmpty())
        assertEquals(RecordingAudioChoice.INTERNAL, denied.snapshot.request!!.descriptor.audioChoice)
        assertTrue(c.start(request(2), 3, true).accepted)
    }

    @Test fun noAudioChoiceDoesNotRequestAudioPermission() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        assertEquals(setOf(RecordingEffect.REQUEST_SYSTEM_CONSENT), c.userConfirmed(r.descriptor.session, 1, false).effects)
        assertEquals(RecordingAudioStatus.NOT_REQUESTED, c.snapshot().audioStatus)
    }

    @Test fun permissionGrantedRequestsConsentExactlyOnce() {
        val c = RecordingController()
        val r = request(audio = RecordingAudioChoice.INTERNAL)
        c.start(r, 0, true)
        c.userConfirmed(r.descriptor.session, 1, false)
        assertEquals(setOf(RecordingEffect.REQUEST_SYSTEM_CONSENT), c.audioPermissionResult(r.descriptor.session, 2, true).effects)
        assertTrue(c.audioPermissionResult(r.descriptor.session, 3, true).effects.isEmpty())
    }

    @Test fun pauseResumeGateDoesNotBlockSafetyCommands() {
        val c = RecordingController()
        val session = recording(c)
        assertEquals(RecordingError.ENTITLEMENT_REQUIRED, c.pause(session, 4, false).error)
        c.pause(session, 5, true)
        assertEquals(RecordingError.ENTITLEMENT_REQUIRED, c.resume(session, 6, false).error)
        assertEquals(setOf(RecordingEffect.STOP_AND_FINALIZE), c.stop(session, 7).effects)
        assertTrue(c.cancel(session, 8).accepted)
    }

    @Test fun lateRecordingCallbackCannotUndoPauseOrStop() {
        val c = RecordingController()
        val session = recording(c)
        c.pause(session, 4, true)
        assertFalse(c.progress(session, 5, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED, 1_100_000, profile).accepted)
        assertEquals(RecordingState.PAUSED, c.snapshot().state)
        c.stop(session, 6)
        assertFalse(c.progress(session, 7, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED, 1_200_000, profile).accepted)
    }

    @Test fun resumeTimeoutDoesNotPretendSyncFrameWasReceived() {
        val limits = RecordingLimits()
        val c = RecordingController(limits)
        val session = recording(c)
        c.pause(session, 4, true)
        c.resume(session, 5, true)
        val timeout = c.checkDeadlines(5 + limits.keyframeTimeoutUs)
        assertEquals(RecordingState.FAILED, timeout.snapshot.state)
        assertEquals(RecordingError.KEYFRAME_TIMEOUT, timeout.snapshot.error)
        assertTrue(timeout.snapshot.resourceOccupied)
    }

    @Test fun pauseAndConsentHaveIndependentDeadlines() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        c.checkDeadlines(RecordingLimits().consentTimeoutUs)
        assertEquals(RecordingState.CANCELLED, c.snapshot().state)
        val other = RecordingController()
        val session = recording(other)
        other.pause(session, 4, true)
        val stopped = other.checkDeadlines(4 + RecordingLimits().maxPausedDurationUs)
        assertEquals(RecordingStopReason.PAUSE_LIMIT, stopped.snapshot.stopReason)
    }

    @Test fun wallClockStyleRegressionCannotAlterControllerState() {
        val c = RecordingController()
        val session = recording(c)
        val before = c.snapshot()
        assertEquals(RecordingError.CLOCK_INVALID, c.pause(session, 2, true).error)
        assertEquals(before, c.snapshot())
    }

    @Test fun noObservedSampleCannotBeReportedAsRecording() {
        val c = RecordingController()
        val session = prepared(c)
        assertFalse(c.progress(session, 3, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED, 0, profile).accepted)
        assertEquals(RecordingState.PREPARING, c.snapshot().state)
    }

    @Test fun changedActualProfileFailsWithoutSilentReconfiguration() {
        val c = RecordingController()
        val session = recording(c)
        val result = c.progress(session, 4, RecordingState.RECORDING, RecordingAudioStatus.NOT_REQUESTED,
            2_000_000, profile.copy(width = 640, height = 360))
        assertEquals(RecordingError.FORMAT_CHANGED, result.snapshot.error)
        assertFalse(result.snapshot.publicationAllowed)
    }

    @Test fun generationMustIncreaseEvenAfterCancellation() {
        val c = RecordingController()
        val r = request()
        c.start(r, 0, true)
        c.cancel(r.descriptor.session, 1)
        assertEquals(RecordingError.STALE_SESSION, c.start(request(1, "new-key"), 2, true).error)
    }

    @Test fun adapterActualStopReasonWinsUiAndSystemRaceWithoutWeakeningCancel() {
        val c = RecordingController()
        val session = recording(c)
        c.stop(session, 4, RecordingStopReason.USER_STOP)
        c.progress(session, 5, RecordingState.STOPPING, RecordingAudioStatus.NOT_REQUESTED,
            1_000_000, profile, RecordingStopReason.SYSTEM_STOPPED)
        assertEquals(RecordingStopReason.SYSTEM_STOPPED, c.snapshot().stopReason)
        c.releaseConfirmed(session, 6)
        assertTrue(c.captureFinished(session, 7, expectation(reason = RecordingStopReason.SYSTEM_STOPPED)).accepted)
        c.cancel(session, 8)
        assertFalse(c.progress(session, 9, RecordingState.FINALIZING, RecordingAudioStatus.NOT_REQUESTED,
            1_000_000, profile, RecordingStopReason.SYSTEM_STOPPED).accepted)
        assertEquals(RecordingStopReason.USER_CANCELLED, c.snapshot().stopReason)
    }

}
