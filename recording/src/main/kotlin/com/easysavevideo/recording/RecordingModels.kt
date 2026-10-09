package com.easysavevideo.recording

/** Original M4 contracts. Identifiers are local correlation values, never system authorization. */
internal fun requireRecordingId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
}

const val MIN_RECORDING_DURATION_US: Long = 250_000
const val MAX_RECORDING_RESUME_BOUNDARIES: Int = 32

enum class RecordingAudioChoice { NONE, INTERNAL }
enum class RecordingAudioStatus {
    NOT_REQUESTED, AWAITING_PERMISSION, PERMISSION_DENIED, INITIALIZING,
    PCM_OBSERVED, SILENCE_OR_UNKNOWN, INTERRUPTED,
}
enum class RecordingAudioOutcome {
    NO_AUDIO_REQUESTED, INTERNAL_AUDIO_CAPTURED, INTERNAL_AUDIO_SILENT_OR_UNKNOWN,
    INTERNAL_AUDIO_INTERRUPTED,
}

/** Requested/actual SDR AVC + optional AAC configuration; support must be checked by the adapter. */
data class RecordingProfile(
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
    val videoBitrate: Int = 6_000_000,
    val audioSampleRate: Int = 48_000,
    val audioChannels: Int = 2,
    val audioBitrate: Int = 128_000,
) {
    init {
        require(width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 8_847_360L)
        require(frameRate in 1..60 && videoBitrate in 64_000..50_000_000)
        require(audioSampleRate in setOf(16_000, 22_050, 24_000, 32_000, 44_100, 48_000))
        require(audioChannels in 1..2 && audioBitrate in 16_000..320_000)
    }
}

data class RecordingSessionId(val sessionId: String, val generation: Long) {
    init { requireRecordingId(sessionId); require(generation > 0) }
}

/** Safe durable intention. Never contains a permission result, output path, or reusable grant. */
data class CaptureDescriptor(
    val sessionId: String,
    val generation: Long,
    val audioChoice: RecordingAudioChoice,
    val requestedProfile: RecordingProfile = RecordingProfile(),
    val regionCaptureRecipe: RegionCaptureRecipe? = null,
) {
    init { requireRecordingId(sessionId); require(generation > 0) }
    val session: RecordingSessionId get() = RecordingSessionId(sessionId, generation)
}

data class RecordingRequest(
    val requestId: String,
    val idempotencyKey: String,
    val descriptor: CaptureDescriptor,
) {
    init { requireRecordingId(requestId); requireRecordingId(idempotencyKey) }
}

enum class RecordingStopReason {
    USER_STOP, SYSTEM_STOPPED, SIZE_CHANGED, SESSION_LIMIT, PAUSE_LIMIT, RESUME_LIMIT, BYTE_LIMIT,
    STORAGE_LOW, AUDIO_LOST, ENCODER_FAILED, USER_CANCELLED, CONSENT_DENIED,
    AUDIO_PERMISSION_DENIED, ENTITLEMENT_REVOKED, DEADLINE_EXCEEDED, PROCESS_INTERRUPTED,
}
enum class RecordingError {
    INVALID_REQUEST, BUSY, STALE_SESSION, STALE_REVISION, INVALID_STATE, ENTITLEMENT_REQUIRED,
    CONSENT_DENIED, AUDIO_PERMISSION_DENIED, CAPTURE_UNAVAILABLE, FGS_START_REJECTED,
    PROFILE_UNSUPPORTED, CAPTURE_EMPTY, CAPTURE_TOO_SHORT, KEYFRAME_TIMEOUT,
    TIMESTAMP_INVALID, CLOCK_INVALID, FORMAT_TIMEOUT, FORMAT_CHANGED, SAMPLE_INVALID,
    SAMPLE_LIMIT, BUFFER_LIMIT, BYTE_LIMIT, STORAGE_LOW, ENCODER_FAILED, AUDIO_FAILED,
    FINALIZATION_FAILED, DEADLINE_EXCEEDED, RESOURCE_RELEASE_UNCONFIRMED, CANCELLED,
}
class RecordingFailure(val code: RecordingError) : RuntimeException(code.name)

data class RecordingLimits(
    val maxSessionDurationUs: Long = 2L * 60 * 60 * 1_000_000,
    val maxPausedDurationUs: Long = 10L * 60 * 1_000_000,
    val consentTimeoutUs: Long = 2L * 60 * 1_000_000,
    val prepareTimeoutUs: Long = 15_000_000,
    val keyframeTimeoutUs: Long = 5_000_000,
    val formatTimeoutUs: Long = 10_000_000,
    val finalizationTimeoutUs: Long = 15_000_000,
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val minFreeBytes: Long = 64L * 1024 * 1024,
    val maxSampleBytes: Int = 4 * 1024 * 1024,
    val maxPendingBytes: Long = 8L * 1024 * 1024,
    val maxPendingSamples: Int = 128,
    val minCaptureDurationUs: Long = MIN_RECORDING_DURATION_US,
    val audioBoundaryToleranceUs: Long = 100_000,
    val maxResumeBoundaries: Int = MAX_RECORDING_RESUME_BOUNDARIES,
) {
    init {
        require(maxSessionDurationUs in 1..(24L * 60 * 60 * 1_000_000))
        require(maxPausedDurationUs in 1..maxSessionDurationUs)
        require(listOf(consentTimeoutUs, prepareTimeoutUs, keyframeTimeoutUs, formatTimeoutUs,
            finalizationTimeoutUs).all { it in 1..maxSessionDurationUs })
        require(maxFileBytes in 1..(2L * 1024 * 1024 * 1024) && minFreeBytes >= 0)
        require(maxSampleBytes > 0 && maxPendingBytes >= maxSampleBytes && maxPendingSamples in 1..4096)
        require(minCaptureDurationUs in MIN_RECORDING_DURATION_US..maxSessionDurationUs)
        require(audioBoundaryToleranceUs in 0..1_000_000)
        require(maxResumeBoundaries in 1..MAX_RECORDING_RESUME_BOUNDARIES)
    }
}

enum class RecordingState {
    IDLE, AWAITING_USER_CONFIRMATION, AWAITING_AUDIO_PERMISSION, AWAITING_SYSTEM_CONSENT,
    PREPARING, RECORDING, PAUSED, RESUMING, STOPPING, FINALIZING, CANCELLING,
    CAPTURE_FINISHED, CANCELLED, FAILED, RECOVERY_REQUIRED,
}

/** Evidence about accepted mux input; the separate verifier must inspect the actual closed output. */
data class RecordingTrackEvidence(
    val sampleCount: Long,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val lastDurationUs: Long,
    val syncSampleCount: Long = 0,
    val firstSampleIsKeyFrame: Boolean = false,
) {
    init {
        require(sampleCount in 1..10_000_000 && firstPtsUs >= 0 && lastPtsUs >= firstPtsUs)
        require(lastDurationUs in 1..1_000_000 && lastPtsUs <= Long.MAX_VALUE - lastDurationUs)
        require(if (sampleCount == 1L) firstPtsUs == lastPtsUs else firstPtsUs < lastPtsUs)
        require(syncSampleCount in 0..sampleCount)
        require(!firstSampleIsKeyFrame || syncSampleCount > 0)
    }
    val endPtsUs: Long get() = lastPtsUs + lastDurationUs
}

/** Zero-based identity of a successfully muxed first keyframe after an effective resume. */
data class RecordingResumeBoundary(val sampleIndex: Long, val ptsUs: Long) {
    init { require(sampleIndex in 1 until 10_000_000L && ptsUs > 0) }
}

/** Defensive snapshot with no mutable list or array exposed across contract boundaries. */
fun snapshotRecordingResumeBoundaries(
    boundaries: List<RecordingResumeBoundary>?, video: RecordingTrackEvidence,
): List<RecordingResumeBoundary>? {
    if (boundaries == null) return null // Legacy policy was absent, rather than known to have no resumes.
    val count = boundaries.size
    require(count in 0..MAX_RECORDING_RESUME_BOUNDARIES)
    val copy = Array(count) { boundaries[it] }
    require(boundaries.size == count)
    require(video.syncSampleCount >= copy.size + 1L)
    copy.forEachIndexed { index, boundary ->
        require(boundary.sampleIndex < video.sampleCount && boundary.ptsUs <= video.lastPtsUs)
        if (index > 0) require(boundary.sampleIndex > copy[index - 1].sampleIndex && boundary.ptsUs > copy[index - 1].ptsUs)
    }
    return object : AbstractList<RecordingResumeBoundary>() {
        override val size: Int get() = copy.size
        override fun get(index: Int): RecordingResumeBoundary = copy[index]
    }
}

/** Only produced after mux finalization and actual resource close, never merely after a timeout. */
class RecordingArtifactExpectation(
    val descriptor: CaptureDescriptor,
    val actualProfile: RecordingProfile,
    val audioOutcome: RecordingAudioOutcome,
    val stopReason: RecordingStopReason,
    val video: RecordingTrackEvidence,
    val audio: RecordingTrackEvidence?,
    val effectiveDurationUs: Long,
    val bytesWritten: Long,
    val finalized: Boolean = true,
    val resourcesReleased: Boolean = true,
    resumeBoundaries: List<RecordingResumeBoundary>? = emptyList(),
) {
    val resumeBoundaries: List<RecordingResumeBoundary>? = snapshotRecordingResumeBoundaries(resumeBoundaries, video)
    init {
        require(finalized && resourcesReleased)
        require(video.sampleCount >= 2 && video.firstSampleIsKeyFrame && video.syncSampleCount > 0 && video.firstPtsUs == 0L)
        require(effectiveDurationUs in MIN_RECORDING_DURATION_US..(24L * 60 * 60 * 1_000_000))
        require(effectiveDurationUs == maxOf(video.endPtsUs, audio?.endPtsUs ?: 0L))
        require(bytesWritten in 1..(2L * 1024 * 1024 * 1024))
        require(stopReason !in setOf(RecordingStopReason.USER_CANCELLED, RecordingStopReason.CONSENT_DENIED,
            RecordingStopReason.AUDIO_PERMISSION_DENIED, RecordingStopReason.ENTITLEMENT_REVOKED,
            RecordingStopReason.PROCESS_INTERRUPTED))
        if (descriptor.audioChoice == RecordingAudioChoice.NONE) {
            require(audio == null && audioOutcome == RecordingAudioOutcome.NO_AUDIO_REQUESTED)
        } else {
            require(audio != null && audioOutcome != RecordingAudioOutcome.NO_AUDIO_REQUESTED)
            require(audio.syncSampleCount == 0L && !audio.firstSampleIsKeyFrame)
        }
    }
    fun copy(
        descriptor: CaptureDescriptor = this.descriptor,
        actualProfile: RecordingProfile = this.actualProfile,
        audioOutcome: RecordingAudioOutcome = this.audioOutcome,
        stopReason: RecordingStopReason = this.stopReason,
        video: RecordingTrackEvidence = this.video,
        audio: RecordingTrackEvidence? = this.audio,
        effectiveDurationUs: Long = this.effectiveDurationUs,
        bytesWritten: Long = this.bytesWritten,
        finalized: Boolean = this.finalized,
        resourcesReleased: Boolean = this.resourcesReleased,
        resumeBoundaries: List<RecordingResumeBoundary>? = this.resumeBoundaries,
    ) = RecordingArtifactExpectation(descriptor, actualProfile, audioOutcome, stopReason, video, audio,
        effectiveDurationUs, bytesWritten, finalized, resourcesReleased, resumeBoundaries)

    private fun values(): List<Any?> = listOf(descriptor, actualProfile, audioOutcome, stopReason, video, audio,
        effectiveDurationUs, bytesWritten, finalized, resourcesReleased, resumeBoundaries)
    override fun equals(other: Any?): Boolean = other is RecordingArtifactExpectation && values() == other.values()
    override fun hashCode(): Int = values().hashCode()

    val videoTrackCount: Int get() = 1
    val audioTrackCount: Int get() = if (audio == null) 0 else 1
}

sealed interface RecordingOutcome {
    data class Completed(val expectation: RecordingArtifactExpectation) : RecordingOutcome
    data class Failed(val error: RecordingError) : RecordingOutcome
    data class Cancelled(val reason: RecordingStopReason) : RecordingOutcome
}

data class RecordingSnapshot(
    val request: RecordingRequest?,
    val state: RecordingState,
    val revision: Long,
    val audioStatus: RecordingAudioStatus,
    val stopReason: RecordingStopReason?,
    val error: RecordingError?,
    val resourceOccupied: Boolean,
    val publicationAllowed: Boolean,
    val outcome: RecordingOutcome?,
    val effectiveDurationUs: Long = 0,
    val actualProfile: RecordingProfile? = null,
    val effectiveResumeCount: Int = 0,
) {
    init { require(effectiveResumeCount in 0..MAX_RECORDING_RESUME_BOUNDARIES) }
    val session: RecordingSessionId? get() = request?.descriptor?.session
}

enum class RecordingEffect {
    REQUEST_AUDIO_PERMISSION, REQUEST_SYSTEM_CONSENT, START_CAPTURE, PAUSE_CAPTURE,
    REQUEST_SYNC_FRAME, STOP_AND_FINALIZE, CANCEL_AND_RELEASE,
}
enum class RecordingCommandDisposition { ACCEPTED, DUPLICATE, REJECTED }
data class RecordingCommandResult(
    val disposition: RecordingCommandDisposition,
    val snapshot: RecordingSnapshot,
    val effects: Set<RecordingEffect> = emptySet(),
    val error: RecordingError? = null,
) {
    val accepted: Boolean get() = disposition != RecordingCommandDisposition.REJECTED
}
