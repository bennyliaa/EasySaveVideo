package com.easysavevideo.recording

/**
 * One bounded, synchronized control owner; no platform resources or callbacks run under this lock.
 * Execute returned effects outside this object. A duplicate command returns no effects. Save owns
 * publication; this controller can only attest that capture finished and its release was confirmed.
 */
class RecordingController(private val limits: RecordingLimits = RecordingLimits()) {
    private var current = RecordingSnapshot(null, RecordingState.IDLE, 0,
        RecordingAudioStatus.NOT_REQUESTED, null, null, false, false, null)
    private var lastNowUs = 0L
    private var stateSinceUs = 0L
    private var captureStartedUs: Long? = null
    private var lastGeneration = 0L
    private var cancelled = false
    private var finalizerIssued = false

    @Synchronized fun snapshot(): RecordingSnapshot = current

    @Synchronized fun start(request: RecordingRequest, nowUs: Long, recordAllowed: Boolean): RecordingCommandResult {
        if (!validTime(nowUs)) return rejected(RecordingError.CLOCK_INVALID)
        if (current.request?.idempotencyKey == request.idempotencyKey) {
            return if (current.request == request) duplicate() else rejected(RecordingError.INVALID_REQUEST)
        }
        if (current.resourceOccupied || current.state !in terminalStates) return rejected(RecordingError.BUSY)
        if (!recordAllowed) return rejected(RecordingError.ENTITLEMENT_REQUIRED)
        if (request.descriptor.generation <= lastGeneration) return rejected(RecordingError.STALE_SESSION)
        lastGeneration = request.descriptor.generation
        cancelled = false
        finalizerIssued = false
        captureStartedUs = null
        stateSinceUs = nowUs
        current = RecordingSnapshot(request, RecordingState.AWAITING_USER_CONFIRMATION, nextRevision(),
            if (request.descriptor.audioChoice == RecordingAudioChoice.NONE) RecordingAudioStatus.NOT_REQUESTED
            else RecordingAudioStatus.AWAITING_PERMISSION, null, null, false, false, null)
        return accepted()
    }

    @Synchronized fun userConfirmed(session: RecordingSessionId, nowUs: Long,
        audioPermissionGranted: Boolean): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (current.state in setOf(RecordingState.AWAITING_AUDIO_PERMISSION, RecordingState.AWAITING_SYSTEM_CONSENT))
            return duplicate()
        if (current.state != RecordingState.AWAITING_USER_CONFIRMATION) return rejected(RecordingError.INVALID_STATE)
        return if (current.request!!.descriptor.audioChoice == RecordingAudioChoice.INTERNAL && !audioPermissionGranted) {
            change(RecordingState.AWAITING_AUDIO_PERMISSION, nowUs)
            accepted(RecordingEffect.REQUEST_AUDIO_PERMISSION)
        } else {
            change(RecordingState.AWAITING_SYSTEM_CONSENT, nowUs)
            accepted(RecordingEffect.REQUEST_SYSTEM_CONSENT)
        }
    }

    @Synchronized fun audioPermissionResult(session: RecordingSessionId, nowUs: Long,
        granted: Boolean): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (current.state != RecordingState.AWAITING_AUDIO_PERMISSION) return rejected(RecordingError.INVALID_STATE)
        if (!granted) return cancelBeforeCapture(nowUs, RecordingStopReason.AUDIO_PERMISSION_DENIED,
            RecordingError.AUDIO_PERMISSION_DENIED, RecordingAudioStatus.PERMISSION_DENIED)
        change(RecordingState.AWAITING_SYSTEM_CONSENT, nowUs)
        return accepted(RecordingEffect.REQUEST_SYSTEM_CONSENT)
    }

    /** Must be called immediately before consuming the external one-shot grant, with a fresh gate. */
    @Synchronized fun consentResult(session: RecordingSessionId, nowUs: Long,
        granted: Boolean, recordAllowed: Boolean): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (current.state != RecordingState.AWAITING_SYSTEM_CONSENT) return rejected(RecordingError.INVALID_STATE)
        if (!granted) return cancelBeforeCapture(nowUs, RecordingStopReason.CONSENT_DENIED, RecordingError.CONSENT_DENIED)
        if (!recordAllowed) return cancelBeforeCapture(nowUs, RecordingStopReason.ENTITLEMENT_REVOKED,
            RecordingError.ENTITLEMENT_REQUIRED)
        captureStartedUs = nowUs
        change(RecordingState.PREPARING, nowUs)
        current = current.copy(resourceOccupied = true,
            audioStatus = if (current.request!!.descriptor.audioChoice == RecordingAudioChoice.NONE)
                RecordingAudioStatus.NOT_REQUESTED else RecordingAudioStatus.INITIALIZING)
        return accepted(RecordingEffect.START_CAPTURE)
    }

    @Synchronized fun pause(session: RecordingSessionId, nowUs: Long,
        segmentAllowed: Boolean): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (current.state == RecordingState.PAUSED) return duplicate()
        if (!segmentAllowed) return rejected(RecordingError.ENTITLEMENT_REQUIRED)
        if (current.state !in setOf(RecordingState.RECORDING, RecordingState.RESUMING)) return rejected(RecordingError.INVALID_STATE)
        change(RecordingState.PAUSED, nowUs)
        return accepted(RecordingEffect.PAUSE_CAPTURE)
    }

    @Synchronized fun resume(session: RecordingSessionId, nowUs: Long,
        segmentAllowed: Boolean): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (current.state == RecordingState.RESUMING) return duplicate()
        if (!segmentAllowed) return rejected(RecordingError.ENTITLEMENT_REQUIRED)
        if (current.state == RecordingState.RECORDING) return duplicate()
        if (current.state != RecordingState.PAUSED) return rejected(RecordingError.INVALID_STATE)
        if (current.effectiveResumeCount >= limits.maxResumeBoundaries)
            return stop(session, nowUs, RecordingStopReason.RESUME_LIMIT)
        change(RecordingState.RESUMING, nowUs)
        return accepted(RecordingEffect.REQUEST_SYNC_FRAME)
    }

    @Synchronized fun stop(session: RecordingSessionId, nowUs: Long,
        reason: RecordingStopReason = RecordingStopReason.USER_STOP): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (reason in cancellationReasons) return rejected(RecordingError.INVALID_REQUEST)
        if (current.state in setOf(RecordingState.STOPPING, RecordingState.FINALIZING,
                RecordingState.CAPTURE_FINISHED, RecordingState.CANCELLING, RecordingState.CANCELLED)) return duplicate()
        if (current.state in terminalStates) return rejected(RecordingError.INVALID_STATE)
        if (!current.resourceOccupied) return cancelBeforeCapture(nowUs, reason, null)
        change(RecordingState.STOPPING, nowUs)
        current = current.copy(stopReason = reason)
        return finalizerEffect(RecordingEffect.STOP_AND_FINALIZE)
    }

    /** Sticky even if a stopped capture/late verifier subsequently reports success. */
    @Synchronized fun cancel(session: RecordingSessionId, nowUs: Long): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (cancelled) return duplicate()
        cancelled = true
        val reason = RecordingStopReason.USER_CANCELLED
        change(if (current.resourceOccupied) RecordingState.CANCELLING else RecordingState.CANCELLED, nowUs)
        current = current.copy(stopReason = reason, publicationAllowed = false,
            outcome = RecordingOutcome.Cancelled(reason), error = null)
        // Cancellation must reach an already-running finalizer to fence its late output, but never
        // asks the adapter to create a second finalizer or a replacement owner lane.
        if (current.resourceOccupied) {
            finalizerIssued = true
            return accepted(RecordingEffect.CANCEL_AND_RELEASE)
        }
        return accepted()
    }

    /** Adapter reports actual observations; it cannot resurrect a superseded command or session. */
    @Synchronized fun progress(
        session: RecordingSessionId, nowUs: Long, state: RecordingState,
        audioStatus: RecordingAudioStatus, effectiveDurationUs: Long,
        actualProfile: RecordingProfile?, stopReason: RecordingStopReason? = null,
        error: RecordingError? = null,
        effectiveResumeCount: Int = 0,
    ): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (cancelled || current.state in terminalStates) return rejected(RecordingError.INVALID_STATE)
        if (error != null) return failed(session, nowUs, error)
        if (effectiveResumeCount < current.effectiveResumeCount || effectiveResumeCount > limits.maxResumeBoundaries)
            return failed(session, nowUs, RecordingError.INVALID_REQUEST)
        if (effectiveDurationUs < current.effectiveDurationUs || effectiveDurationUs > limits.maxSessionDurationUs)
            return failed(session, nowUs, RecordingError.TIMESTAMP_INVALID)
        if (current.request!!.descriptor.audioChoice == RecordingAudioChoice.NONE && audioStatus != RecordingAudioStatus.NOT_REQUESTED)
            return failed(session, nowUs, RecordingError.INVALID_REQUEST)
        if (current.actualProfile != null && actualProfile != null && current.actualProfile != actualProfile)
            return failed(session, nowUs, RecordingError.FORMAT_CHANGED)
        if (stopReason != null && (stopReason in cancellationReasons ||
                state !in setOf(RecordingState.STOPPING, RecordingState.FINALIZING)))
            return rejected(RecordingError.INVALID_REQUEST)
        when (state) {
            RecordingState.PREPARING -> if (current.state != state) return rejected(RecordingError.INVALID_STATE)
            RecordingState.RECORDING -> {
                if (current.state !in setOf(RecordingState.PREPARING, RecordingState.RESUMING, state))
                    return rejected(RecordingError.INVALID_STATE)
                if (effectiveDurationUs == 0L || actualProfile == null) return rejected(RecordingError.INVALID_REQUEST)
            }
            RecordingState.PAUSED, RecordingState.RESUMING -> if (current.state != state) return rejected(RecordingError.INVALID_STATE)
            RecordingState.STOPPING -> {
                if (current.state !in setOf(RecordingState.PREPARING, RecordingState.RECORDING,
                        RecordingState.PAUSED, RecordingState.RESUMING, RecordingState.STOPPING)) return rejected(RecordingError.INVALID_STATE)
                if (stopReason == null || stopReason in cancellationReasons) return rejected(RecordingError.INVALID_REQUEST)
            }
            RecordingState.FINALIZING -> if (current.state !in setOf(RecordingState.STOPPING, state))
                return rejected(RecordingError.INVALID_STATE)
            else -> return rejected(RecordingError.INVALID_STATE)
        }
        if (state != current.state) change(state, nowUs) else bump()
        current = current.copy(audioStatus = audioStatus, effectiveDurationUs = effectiveDurationUs,
            effectiveResumeCount = effectiveResumeCount, actualProfile = actualProfile ?: current.actualProfile,
            stopReason = stopReason ?: current.stopReason)
        if (state == RecordingState.STOPPING) finalizerIssued = true
        return accepted()
    }

    @Synchronized fun failed(session: RecordingSessionId, nowUs: Long, error: RecordingError): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (cancelled) return duplicate()
        if (current.state in terminalStates) return duplicate()
        change(RecordingState.FAILED, nowUs)
        current = current.copy(error = error, publicationAllowed = false, outcome = RecordingOutcome.Failed(error))
        return if (current.resourceOccupied) finalizerEffect(RecordingEffect.CANCEL_AND_RELEASE) else accepted()
    }

    /** Only call after the underlying resource owner really exits and closes every owned handle. */
    @Synchronized fun releaseConfirmed(session: RecordingSessionId, nowUs: Long): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (!current.resourceOccupied) return duplicate()
        if (current.state !in setOf(RecordingState.STOPPING, RecordingState.FINALIZING, RecordingState.CANCELLING,
                RecordingState.FAILED, RecordingState.RECOVERY_REQUIRED)) return rejected(RecordingError.INVALID_STATE)
        if (cancelled) change(RecordingState.CANCELLED, nowUs) else bump()
        current = current.copy(resourceOccupied = false)
        return accepted()
    }

    /** An interrupt, Future timeout, wrapper close, or abandoned callback is NOT release evidence. */
    @Synchronized fun releaseUnconfirmed(session: RecordingSessionId, nowUs: Long): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (!current.resourceOccupied) return rejected(RecordingError.INVALID_STATE)
        if (current.state == RecordingState.RECOVERY_REQUIRED) return duplicate()
        change(RecordingState.RECOVERY_REQUIRED, nowUs)
        current = current.copy(publicationAllowed = false, error = RecordingError.RESOURCE_RELEASE_UNCONFIRMED,
            outcome = if (cancelled) current.outcome else RecordingOutcome.Failed(RecordingError.RESOURCE_RELEASE_UNCONFIRMED))
        return finalizerEffect(RecordingEffect.CANCEL_AND_RELEASE)
    }

    @Synchronized fun captureFinished(session: RecordingSessionId, nowUs: Long,
        expectation: RecordingArtifactExpectation): RecordingCommandResult {
        checked(session, nowUs)?.let { return it }
        if (cancelled || current.state == RecordingState.RECOVERY_REQUIRED || current.state == RecordingState.FAILED)
            return rejected(RecordingError.CANCELLED)
        if (current.state == RecordingState.CAPTURE_FINISHED)
            return if (current.outcome == RecordingOutcome.Completed(expectation)) duplicate() else rejected(RecordingError.INVALID_REQUEST)
        if (current.resourceOccupied) return rejected(RecordingError.RESOURCE_RELEASE_UNCONFIRMED)
        if (current.state !in setOf(RecordingState.STOPPING, RecordingState.FINALIZING)) return rejected(RecordingError.INVALID_STATE)
        val resumeCount = expectation.resumeBoundaries?.size
        if (resumeCount == null || resumeCount < current.effectiveResumeCount || resumeCount > limits.maxResumeBoundaries ||
            expectation.descriptor != current.request!!.descriptor || expectation.stopReason != current.stopReason ||
            expectation.bytesWritten > limits.maxFileBytes || expectation.effectiveDurationUs > limits.maxSessionDurationUs ||
            (current.actualProfile != null && expectation.actualProfile != current.actualProfile))
            return failed(session, nowUs, RecordingError.INVALID_REQUEST)
        if (expectation.effectiveDurationUs < limits.minCaptureDurationUs)
            return failed(session, nowUs, RecordingError.CAPTURE_TOO_SHORT)
        change(RecordingState.CAPTURE_FINISHED, nowUs)
        current = current.copy(publicationAllowed = true, outcome = RecordingOutcome.Completed(expectation),
            effectiveDurationUs = expectation.effectiveDurationUs, actualProfile = expectation.actualProfile,
            effectiveResumeCount = resumeCount)
        return accepted()
    }

    @Synchronized fun checkDeadlines(nowUs: Long): RecordingCommandResult {
        if (!validTime(nowUs)) return rejected(RecordingError.CLOCK_INVALID)
        val session = current.session ?: return duplicate()
        if (current.state in terminalStates) return duplicate()
        val elapsed = nowUs - stateSinceUs
        return when {
            current.state in consentStates && elapsed >= limits.consentTimeoutUs ->
                cancelBeforeCapture(nowUs, RecordingStopReason.DEADLINE_EXCEEDED, RecordingError.DEADLINE_EXCEEDED)
            current.state in setOf(RecordingState.STOPPING, RecordingState.FINALIZING, RecordingState.CANCELLING) &&
                elapsed >= limits.finalizationTimeoutUs -> releaseUnconfirmed(session, nowUs)
            current.state == RecordingState.PREPARING && elapsed >= limits.prepareTimeoutUs ->
                failed(session, nowUs, RecordingError.DEADLINE_EXCEEDED)
            current.state == RecordingState.RESUMING && elapsed >= limits.keyframeTimeoutUs ->
                failed(session, nowUs, RecordingError.KEYFRAME_TIMEOUT)
            current.state == RecordingState.PAUSED && elapsed >= limits.maxPausedDurationUs ->
                stop(session, nowUs, RecordingStopReason.PAUSE_LIMIT)
            captureStartedUs != null && nowUs - captureStartedUs!! >= limits.maxSessionDurationUs &&
                current.state in setOf(RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING) ->
                stop(session, nowUs, RecordingStopReason.SESSION_LIMIT)
            else -> duplicate()
        }
    }

    private fun checked(session: RecordingSessionId, nowUs: Long): RecordingCommandResult? {
        if (current.session != session) return rejected(RecordingError.STALE_SESSION)
        if (!validTime(nowUs)) return rejected(RecordingError.CLOCK_INVALID)
        return null
    }
    private fun validTime(nowUs: Long): Boolean {
        if (nowUs < lastNowUs || nowUs < 0) return false
        lastNowUs = nowUs
        return true
    }
    private fun cancelBeforeCapture(nowUs: Long, reason: RecordingStopReason, error: RecordingError?,
        audioStatus: RecordingAudioStatus = current.audioStatus): RecordingCommandResult {
        cancelled = true
        change(RecordingState.CANCELLED, nowUs)
        current = current.copy(stopReason = reason, error = error, publicationAllowed = false,
            outcome = RecordingOutcome.Cancelled(reason), audioStatus = audioStatus)
        return accepted()
    }
    private fun change(state: RecordingState, nowUs: Long) {
        stateSinceUs = nowUs
        current = current.copy(state = state, revision = nextRevision())
    }
    private fun bump() { current = current.copy(revision = nextRevision()) }
    private fun nextRevision(): Long {
        check(current.revision < Long.MAX_VALUE)
        return current.revision + 1
    }
    private fun finalizerEffect(effect: RecordingEffect): RecordingCommandResult {
        if (finalizerIssued) return accepted()
        finalizerIssued = true
        return accepted(effect)
    }
    private fun accepted(vararg effects: RecordingEffect) = RecordingCommandResult(
        RecordingCommandDisposition.ACCEPTED, current, effects.toSet())
    private fun duplicate() = RecordingCommandResult(RecordingCommandDisposition.DUPLICATE, current)
    private fun rejected(error: RecordingError) = RecordingCommandResult(RecordingCommandDisposition.REJECTED, current, error = error)

    private companion object {
        val terminalStates = setOf(RecordingState.IDLE, RecordingState.CAPTURE_FINISHED,
            RecordingState.CANCELLED, RecordingState.FAILED, RecordingState.RECOVERY_REQUIRED)
        val consentStates = setOf(RecordingState.AWAITING_USER_CONFIRMATION, RecordingState.AWAITING_AUDIO_PERMISSION,
            RecordingState.AWAITING_SYSTEM_CONSENT)
        val cancellationReasons = setOf(RecordingStopReason.USER_CANCELLED, RecordingStopReason.CONSENT_DENIED,
            RecordingStopReason.AUDIO_PERMISSION_DENIED, RecordingStopReason.ENTITLEMENT_REVOKED,
            RecordingStopReason.PROCESS_INTERRUPTED)
    }
}
