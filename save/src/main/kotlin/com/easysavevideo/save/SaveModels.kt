package com.easysavevideo.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.*
import java.net.URI

internal fun requireSaveId(value: String) { require(value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) }

/** Functional private-storage value. Never put its contents in logs, diagnostics or analytics. */
data class SourceUrl(val value: String) {
    init {
        require(value.length in 1..8192)
        val uri = URI(value)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawFragment == null)
    }
    fun originOnly(): SourceUrl {
        val parsed = URI(value)
        return SourceUrl(URI("https", null, parsed.host, parsed.port, "/", null, null).toASCIIString())
    }
    fun identity(): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    override fun toString() = "SourceUrl([redacted])"
}
data class RequestContextRef(val leaseId: String) {
    init { requireSaveId(leaseId) }
    override fun toString() = "RequestContextRef([redacted])"
}
data class SelectedMediaResource(
    val candidateId: String,
    val variantId: String,
    val url: SourceUrl,
    val expectedAudio: Boolean = false,
    val requestContextRef: RequestContextRef? = null,
    val expiresAtEpochMillis: Long? = null,
    val sourceIdentity: String = url.identity(),
    val durableReference: Boolean = false,
) {
    init {
        requireSaveId(candidateId); requireSaveId(variantId); require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0)
        require(sourceIdentity.matches(Regex("[a-f0-9]{64}")))
        require(if (durableReference) url == url.originOnly() && requestContextRef == null else sourceIdentity == url.identity())
    }
    /** Persist only origin and source hash; signed URLs and bearer query parameters remain memory-only. */
    fun asDurableReference() = copy(url = url.originOnly(), requestContextRef = null, durableReference = true)
    override fun toString() = "SelectedMediaResource([redacted])"
}
/** Typed source identity. Capture descriptors contain no source URL or reusable authorization. */
sealed interface SaveInput {
    data class Network(val resource: SelectedMediaResource) : SaveInput
    data class Capture(val descriptor: CaptureDescriptor) : SaveInput
}
/** A request is created only after the user's explicit selection. No Discovery or Provider dependency. */
data class SaveRequest(
    val requestId: String,
    val idempotencyKey: String,
    val input: SaveInput,
    val method: TaskMethod = if (input is SaveInput.Capture) TaskMethod.RECORD else TaskMethod.DIRECT,
    val origin: CommandOrigin = CommandOrigin.USER,
) {
    init {
        requireSaveId(requestId); requireSaveId(idempotencyKey)
        require((method == TaskMethod.RECORD) == (input is SaveInput.Capture))
        require(input !is SaveInput.Capture || input.descriptor.sessionId == requestId)
    }
    constructor(requestId: String, idempotencyKey: String, resource: SelectedMediaResource,
        method: TaskMethod = TaskMethod.DIRECT, origin: CommandOrigin = CommandOrigin.USER) :
        this(requestId, idempotencyKey, SaveInput.Network(resource), method, origin)
    /** Compatibility accessor for explicitly network-only callers; never invents a capture URL. */
    val resource: SelectedMediaResource get() = requireNotNull(networkResource) { "Capture has no network resource" }
    val networkResource: SelectedMediaResource? get() = (input as? SaveInput.Network)?.resource
    val captureDescriptor: CaptureDescriptor? get() = (input as? SaveInput.Capture)?.descriptor
    fun asDurableReference(): SaveRequest = when (val value = input) {
        is SaveInput.Network -> copy(input = SaveInput.Network(value.resource.asDurableReference()))
        is SaveInput.Capture -> this
    }
    /** Preserves named resource-copy callers while the generated copy uses typed input. */
    fun copy(resource: SelectedMediaResource, requestId: String = this.requestId,
        idempotencyKey: String = this.idempotencyKey, method: TaskMethod = this.method,
        origin: CommandOrigin = this.origin): SaveRequest = SaveRequest(requestId, idempotencyKey, resource, method, origin)
    override fun toString() = "SaveRequest([redacted])"
}

/** RUNNING subphases, not additional TaskState values. */
enum class SavePhase { PREPARING, EXECUTING, VERIFYING, COMMITTING, CLEANUP, CANCELLING }
enum class SaveWaitReason { BACKGROUND, NETWORK, AUTHENTICATION, STORAGE, RECOVERY }
enum class SaveError {
    INVALID_REQUEST, METHOD_UNSUPPORTED, BUSY, EXPIRED_RESOURCE, CANCELLED, DEADLINE_EXCEEDED,
    RETRY_EXHAUSTED, NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, HTTP_FAILURE, HTTP_NOT_FOUND, HTTP_REJECTED, AUTHENTICATION_REQUIRED,
    RATE_LIMITED, TLS_REJECTED, POLICY_BLOCKED, STORAGE_FULL, STORAGE_FAILURE, BYTE_LIMIT_EXCEEDED,
    INVALID_RANGE, RESOURCE_CHANGED, INCOMPLETE_TRANSFER, INVALID_MEDIA, UNSUPPORTED_MEDIA,
    PROTECTED_MEDIA, VERIFICATION_UNAVAILABLE, COMMIT_MISMATCH, JOURNAL_UNAVAILABLE,
    RECOVERY_REQUIRED, RESOURCE_RELEASE_UNCONFIRMED, SOURCE_RESELECTION_REQUIRED, UNKNOWN_TECHNICAL_ERROR,
    CONSENT_DENIED, AUDIO_PERMISSION_DENIED, CAPTURE_UNAVAILABLE, FGS_START_REJECTED,
    PROFILE_UNSUPPORTED, CAPTURE_EMPTY, CAPTURE_TOO_SHORT, KEYFRAME_TIMEOUT, TIMESTAMP_INVALID,
    AUDIO_FAILED, ENCODER_FAILED, FINALIZATION_FAILED,
}
class SaveFailure(val code: SaveError) : RuntimeException(code.name)

/** No guessed product cap. The composition root must explicitly select and document both byte budgets. */
data class SaveLimits(
    val maxArtifactBytes: Long,
    val maxTotalTransferBytes: Long,
    val connectTimeoutMillis: Int = 10_000,
    val noProgressTimeoutMillis: Int = 30_000,
    val verificationTimeoutMillis: Long = 60_000,
    val waitingUserLifetimeMillis: Long = 24L * 60 * 60 * 1000,
    val sourceContextLifetimeMillis: Long = 15L * 60 * 1000,
    val copyBufferBytes: Int = 64 * 1024,
    val maxRedirects: Int = 5,
    val maxJournalEntries: Int = 100,
    val maxRecoveryEntries: Int = 100,
) {
    init {
        require(maxArtifactBytes > 0 && maxTotalTransferBytes >= maxArtifactBytes)
        require(connectTimeoutMillis in 1..120_000 && noProgressTimeoutMillis in 1..300_000)
        require(verificationTimeoutMillis > 0 && waitingUserLifetimeMillis > 0 && sourceContextLifetimeMillis > 0)
        require(copyBufferBytes in 1024..1024 * 1024 && maxRedirects in 0..10)
        require(maxJournalEntries in 1..1000 && maxRecoveryEntries in 1..1000)
    }
}

data class StagingArtifact(val artifactId: String, val commitId: String) {
    init { requireSaveId(artifactId); requireSaveId(commitId) }
}
/** Safe private-journal range hint; never a credential or proof that a file is valid. */
data class DownloadCheckpoint(
    val artifactId: String,
    val resourceIdentity: String,
    val offset: Long,
    val strongEtag: String,
    val totalBytes: Long,
) {
    init {
        requireSaveId(artifactId); require(resourceIdentity.matches(Regex("[a-f0-9]{64}")))
        require(offset > 0 && totalBytes >= offset)
        require(strongEtag.length in 2..1024 && strongEtag.startsWith('"') && strongEtag.endsWith('"'))
        require(!strongEtag.contains('\r') && !strongEtag.contains('\n'))
    }
    override fun toString() = "DownloadCheckpoint([redacted])"
}

/** Durable output constraints derived from the inspected local stream, never manifest hints alone. */
data class StreamArtifactExpectation(
    val durationMillis: Long, val videoTrackCount: Int, val audioTrackCount: Int,
    val width: Int, val height: Int,
    /** Null only identifies unproven legacy records; never acceptable for new Stream output. */
    val conservation: StreamConservationContract? = null,
) {
    init {
        require(durationMillis in 1..StreamProofLimits.MAX_DURATION_US / 1_000 && videoTrackCount == 1 && audioTrackCount in 0..1 && width > 0 && height > 0)
        require(conservation == null || ((audioTrackCount == 1) == (conservation.audio != null)))
        require(conservation == null || conservation.endpoint.contains(durationMillis * 1_000, 1_000))
    }
    fun matches(evidence: VerificationEvidence): Boolean = evidence.videoTrackCount == videoTrackCount &&
        evidence.audioTrackCount == audioTrackCount && evidence.width == width && evidence.height == height &&
        conservation != null && evidence.streamConservation?.let(conservation::matches) == true &&
        evidence.streamTailProof?.matches(conservation) == true &&
        evidence.durationMillis <= StreamProofLimits.MAX_DURATION_US / 1_000 &&
        conservation.endpoint.contains(evidence.durationMillis * 1_000, 1_000)
}

data class TransferResult(
    val artifactId: String,
    val bytesOnDisk: Long,
    val responseMimeType: String,
    val expectedTotalBytes: Long? = null,
    val streamExpectation: StreamArtifactExpectation? = null,
    val recordingExpectation: RecordingArtifactExpectation? = null,
) {
    init { require(streamExpectation == null || recordingExpectation == null); requireSaveId(artifactId); require(bytesOnDisk >= 0); require(responseMimeType.length <= 128); require(expectedTotalBytes == null || expectedTotalBytes >= 0) }
    override fun toString() = "TransferResult(artifact=[redacted], bytesOnDisk=$bytesOnDisk)"
}
enum class DecodePosition { START, MIDDLE, END }
/** Evidence produced by a real adapter. Metadata or an ftyp marker alone is insufficient. */
data class VerificationEvidence(
    val artifactId: String,
    val sizeBytes: Long,
    val sha256: String,
    val containerIsMp4: Boolean,
    val videoTrackCount: Int,
    val audioTrackCount: Int,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val encrypted: Boolean,
    val decodedPositions: Set<DecodePosition>,
    val audioTrackReadable: Boolean,
    val streamConservation: StreamConservationContract? = null,
    val streamTailProof: StreamTailProof? = null,
    val recordingProof: RecordingVerificationProof? = null,
) {
    fun isValidFor(artifact: StagingArtifact, transfer: TransferResult, expectedAudio: Boolean): Boolean =
        artifactId == artifact.artifactId && artifactId == transfer.artifactId && sizeBytes > 0 &&
            sizeBytes == transfer.bytesOnDisk && (transfer.expectedTotalBytes == null || sizeBytes == transfer.expectedTotalBytes) &&
            sha256.matches(Regex("[a-f0-9]{64}")) && containerIsMp4 && videoTrackCount > 0 && audioTrackCount >= 0 &&
            durationMillis > 0 && width > 0 && height > 0 && !encrypted &&
            decodedPositions.containsAll(DecodePosition.entries) &&
            (!expectedAudio || audioTrackCount > 0) && (audioTrackCount == 0 || audioTrackReadable) &&
            ((recordingProof != null) == (transfer.recordingExpectation != null)) &&
            (transfer.streamExpectation?.matches(this) ?: true) &&
            (transfer.recordingExpectation?.matches(this) ?: true)
}
/** Created only after the coordinator has checked VerificationEvidence against the selected transfer. */
class VerifiedArtifact internal constructor(val artifact: StagingArtifact, val evidence: VerificationEvidence)

data class MediaCommitReceipt(
    val commitId: String,
    val artifactId: String,
    val mediaId: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init { requireSaveId(commitId); requireSaveId(artifactId); requireSaveId(mediaId); require(sizeBytes > 0); require(sha256.matches(Regex("[a-f0-9]{64}"))) }
    fun matches(verified: VerifiedArtifact): Boolean = commitId == verified.artifact.commitId &&
        artifactId == verified.artifact.artifactId && sizeBytes == verified.evidence.sizeBytes && sha256 == verified.evidence.sha256
}
/** Fresh inspection: the file AND the private-media index must refer to this exact receipt. */
data class CommitInspection(val receipt: MediaCommitReceipt, val filePresent: Boolean, val indexPresent: Boolean, val contentMatches: Boolean) {
    fun confirms(expected: MediaCommitReceipt) = receipt == expected && filePresent && indexPresent && contentMatches
}

/** Durable record contains no cancellation token, request lease, cookies, HTTP headers. Safe checkpoints may be persisted for explicit recovery. */
data class SaveRecord(
    val request: SaveRequest,
    val task: TaskSnapshot,
    val commitId: String,
    val operationId: String,
    val deadlineEpochMillis: Long,
    val retryBudget: Int,
    val attempt: Int = 0,
    val phase: SavePhase? = null,
    val artifact: StagingArtifact? = null,
    val transfer: TransferResult? = null,
    val verification: VerificationEvidence? = null,
    val receipt: MediaCommitReceipt? = null,
    /** Charged network-byte budget, including at most one MiB of unused reserved allowance after a crash. */
    val bytesTransferred: Long = 0,
    val checkpoint: DownloadCheckpoint? = null,
    val waitReason: SaveWaitReason? = null,
    val error: SaveError? = null,
    val schemaVersion: Int = 1,
) {
    init {
        require(request.networkResource?.let { it.requestContextRef == null && it.durableReference } != false) {
            "Durable records require a safe source reference"
        }
        require(request.method != TaskMethod.RECORD || (bytesTransferred == 0L && checkpoint == null && retryBudget == 0 && attempt == 0))
        requireSaveId(commitId); require(task.taskId == request.requestId && task.method == request.method)
        require(artifact == null || artifact.commitId == commitId)
        require(transfer == null || (artifact != null && transfer.artifactId == artifact.artifactId))
        require(transfer == null || transfer.matchesMethod(request)) {
            "Stored transfer method and media constraints disagree"
        }
        // Legacy Stream/Record journals are readable, but missing policy evidence cannot become a new proof.
        // Coordinator refuses publication until a complete conserved contract exists.
        require(verification == null || (artifact != null && transfer != null && when {
            request.method == TaskMethod.RECORD && transfer.recordingExpectation?.resumeBoundaries == null ->
                transfer.recordingExpectation?.matchesLegacyEvidence(verification) == true &&
                    verification.copy(recordingProof = null).isValidFor(artifact, transfer.copy(recordingExpectation = null),
                        request.networkResource?.expectedAudio ?: false)
            else -> verification.isValidFor(artifact, if (request.method == TaskMethod.STREAM &&
                transfer.streamExpectation?.conservation == null) transfer.copy(streamExpectation = null) else transfer,
                request.networkResource?.expectedAudio ?: false)
        }))
        require(receipt == null || (artifact != null && verification != null && receipt.matches(VerifiedArtifact(artifact, verification))))
        require(deadlineEpochMillis >= 0 && retryBudget in 0..2 && attempt in 0..retryBudget && bytesTransferred >= 0)
        require((task.state == TaskState.WAITING_USER) == (waitReason != null))
        require(task.state != TaskState.SUCCEEDED || (receipt != null && verification != null))
    }
    override fun toString() = "SaveRecord(state=${task.state}, phase=$phase, error=$error)"
}
sealed interface SaveSubmission {
    data class Accepted(val record: SaveRecord, val existing: Boolean) : SaveSubmission
    data class Rejected(val error: SaveError) : SaveSubmission
}
