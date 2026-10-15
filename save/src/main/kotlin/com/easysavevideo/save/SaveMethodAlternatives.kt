package com.easysavevideo.save

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import java.util.concurrent.atomic.AtomicBoolean

/** Exact observed failure, without the failed request's URL, credentials or capture permission. */
data class SaveFailedIdentity(
    val taskId: String,
    val revision: Long,
    val state: TaskState,
    val method: TaskMethod,
    val error: SaveError?,
) {
    init { requireSaveId(taskId); require(revision >= 0) }
    companion object {
        fun from(record: SaveRecord) = SaveFailedIdentity(record.task.taskId, record.task.revision,
            record.task.state, record.task.method, record.error)
    }
}

enum class SaveAlternativeReview { NOT_ELIGIBLE, NEEDS_FRESH_STREAM_SOURCE }

/** Pure failure policy. A suitable error never establishes source or capture capability. */
object SaveMethodAlternatives {
    fun review(failed: SaveFailedIdentity): SaveAlternativeReview =
        if (failed.state == TaskState.FAILED && failed.method == TaskMethod.DIRECT &&
            failed.error in setOf(SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED))
            SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE else SaveAlternativeReview.NOT_ELIGIBLE
}

/**
 * Caller-owned, process-local decision token. There is no global proposal registry or result cache.
 * Only the issuing Coordinator can consume it; resource ownership stays with the existing HLS claim.
 * Revocation is memory-only. A confirmation already admitted before revocation may finish.
 */
class StreamAlternativeProposal internal constructor(
    internal val owner: Any,
    internal val failed: SaveFailedIdentity,
    internal val oldIdempotencyKey: String,
    internal val inspectionGeneration: Long,
    internal val candidateId: String,
    internal val sourceIdentity: String,
    internal val requestContextRef: RequestContextRef?,
    internal val sourceExpiresAtEpochMillis: Long?,
    internal val expectedAudio: Boolean,
    internal val operation: OperationContext,
    internal val expiresAtEpochMillis: Long,
    internal val privateSeed: PrivateNetworkSourceSeed? = null,
) {
    private val consumed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    fun revoke() { revoked.set(true) }
    internal val isRevoked: Boolean get() = revoked.get()
    internal fun consume(issuer: Any): SaveAlternativeRejection? = when {
        owner !== issuer -> SaveAlternativeRejection.FOREIGN_PROPOSAL
        !consumed.compareAndSet(false, true) -> SaveAlternativeRejection.ALREADY_USED
        isRevoked -> SaveAlternativeRejection.REVOKED
        else -> null
    }
    override fun toString() = "StreamAlternativeProposal([redacted])"
}

sealed interface SaveAlternativePreparation {
    /** READY_TO_CONFIRM_ATTEMPT is permission to attempt, not proof of downloadable media. */
    data class Ready(val proposal: StreamAlternativeProposal) : SaveAlternativePreparation
    data class Rejected(val error: SaveError) : SaveAlternativePreparation
}

enum class SaveAlternativeRejection {
    FOREIGN_PROPOSAL, REVOKED, EXPIRED, ALREADY_USED, MISMATCH, STALE_FAILURE,
    ADMISSION_REJECTED, SUBMISSION_REJECTED,
}

sealed interface SaveAlternativeConfirmation {
    data class Accepted(val record: SaveRecord) : SaveAlternativeConfirmation
    data class Rejected(val reason: SaveAlternativeRejection, val error: SaveError? = null) : SaveAlternativeConfirmation
    /** The fixed task identity may have been written. Never dispatch, replay or generate a retry ID. */
    data class OutcomeUnknown(val taskId: String) : SaveAlternativeConfirmation
}
