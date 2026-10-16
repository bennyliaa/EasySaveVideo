package com.easysavevideo.core.model

/** IDs are internal non-content references. They never grant path, credential or object access. */
data class VaultSourceRef(val itemId: String, val revision: Long, val representationGeneration: Long) {
    override fun toString(): String = "VaultSourceRef([redacted])"
}
enum class VaultObjectRole { STAGING_CIPHERTEXT, COMMITTED_CIPHERTEXT, ENCRYPTED_METADATA, OWNED_PLAINTEXT_TEMP }
data class VaultObjectRef(val objectId: String, val identityGeneration: Long, val role: VaultObjectRole, val operationId: String) {
    override fun toString(): String = "VaultObjectRef(role=$role, [redacted])"
}
enum class VaultObservationStatus { UNKNOWN, MISSING, INVALID, VALID }
data class VaultSourceObservation(val ref: VaultSourceRef, val status: VaultObservationStatus)
data class VaultObjectObservation(
    val ref: VaultObjectRef,
    val status: VaultObservationStatus,
    val commitId: String,
    val targetMediaItemId: String,
    val observationRevision: Long,
    val durable: Boolean,
)
data class VaultPrivateRecordObservation(
    val status: VaultObservationStatus,
    val ref: VaultObjectRef?,
    val commitId: String?,
    val targetMediaItemId: String?,
    val revision: Long,
    val durable: Boolean,
    val privateOnly: Boolean,
)
enum class VaultVerificationKind { NONE, ORDINARY_MEDIA_COMMIT_RECEIPT, VAULT_AUTHENTICATED_FORMAT }
data class VaultVerificationEvidence(
    val ref: VaultObjectRef,
    val commitId: String,
    val observationRevision: Long,
    val kind: VaultVerificationKind,
)
data class VaultOwnedPlaintextTemp(val ref: VaultObjectRef, val ownershipConfirmed: Boolean, val activeReferenceCount: Int)
data class VaultSourceOwnership(val ref: VaultSourceRef, val explicitMoveAuthorized: Boolean, val otherActiveReferences: Int)
enum class VaultKeyAvailability { LOCKED, AVAILABLE_IN_CURRENT_SESSION, INVALIDATED, UNAVAILABLE, UNKNOWN }
data class VaultSessionStamp(val processInstance: Long, val authEpoch: Long)
data class VaultRecoveryAuthorization(val current: VaultSessionStamp, val operation: VaultSessionStamp)
enum class VaultRecoveryOperationKind { MOVE_INTO_VAULT, EXPLICIT_EXPORT }
enum class VaultJournalPhase { PREPARED, EXECUTING, VERIFIED, COMMIT_INTENT, POINTER_SWITCHED, INDEX_COMMITTED, CLEANUP_PENDING, COMPLETE }
enum class VaultExportPublication { NOT_PUBLISHED, PUBLISHED, UNKNOWN }
data class VaultExportIntent(val operationId: String, val destinationGrantCurrent: Boolean, val publication: VaultExportPublication)
enum class VaultRecoveryStop { NONE, CANCELLED, DEADLINE_REACHED, BUDGET_EXHAUSTED }

class VaultRecoverySnapshot(
    val schemaVersion: Int,
    val operationId: String,
    val commitId: String,
    val targetMediaItemId: String,
    val journalRevision: Long,
    val kind: VaultRecoveryOperationKind,
    val phase: VaultJournalPhase,
    val sourceRef: VaultSourceRef,
    val stagingRef: VaultObjectRef,
    val committedRef: VaultObjectRef,
    val encryptedMetadataRef: VaultObjectRef,
    ownedPlaintextTemps: List<VaultOwnedPlaintextTemp>,
    val observedSource: VaultSourceObservation,
    val observedCandidate: VaultObjectObservation,
    val observedEncryptedMetadata: VaultObjectObservation,
    val observedPointer: VaultPrivateRecordObservation,
    val observedIndex: VaultPrivateRecordObservation,
    val verificationEvidence: VaultVerificationEvidence?,
    val metadataVerificationEvidence: VaultVerificationEvidence?,
    val sourceOwnership: VaultSourceOwnership,
    val keyAvailability: VaultKeyAvailability,
    val authorization: VaultRecoveryAuthorization?,
    val exportIntent: VaultExportIntent?,
    val cleanupStatus: VaultCleanupStatus,
    val stop: VaultRecoveryStop,
) {
    private val temps = ownedPlaintextTemps.toList()
    val ownedPlaintextTemps: List<VaultOwnedPlaintextTemp> get() = temps.toList()
    override fun toString(): String = "VaultRecoverySnapshot(kind=$kind, phase=$phase, [redacted])"
}

enum class VaultRecoveryStepKind {
    CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP, WAIT_FOR_AUTHENTICATION_OR_RECOVERY, WAIT_FOR_EXPLICIT_EXPORT_RESUME,
    DEFER_BOUNDED_VERIFICATION, QUARANTINE_INCOMPLETE_OR_INCONSISTENT_OBJECT, RECONCILE_PRIVATE_POINTER_OR_INDEX,
    FINISH_EXPLICIT_MOVE_SOURCE_CLEANUP, MARK_EXPORT_NEEDS_USER_ACTION, MARK_OPERATION_COMPLETE,
}
sealed interface VaultRecoveryTarget {
    data class ObjectTarget(val ref: VaultObjectRef) : VaultRecoveryTarget
    data class SourceTarget(val ref: VaultSourceRef) : VaultRecoveryTarget
    data class OperationTarget(val targetMediaItemId: String) : VaultRecoveryTarget {
        override fun toString(): String = "OperationTarget([redacted])"
    }
}
/** Stable semantic identity: never add phase, journal revision, observation revision or sequence number. */
data class VaultRecoveryStepId(
    val operationId: String,
    val commitId: String,
    val kind: VaultRecoveryStepKind,
    val target: VaultRecoveryTarget,
) {
    override fun toString(): String = "VaultRecoveryStepId(kind=$kind, [redacted])"
}
/** Preconditions are independent of step identity and MUST be rechecked by a future reviewed executor. */
data class VaultRecoveryStep(
    val stepId: VaultRecoveryStepId,
    val expectedJournalRevision: Long,
    val expectedObservationRevision: Long?,
    val expectedMetadataObservationRevision: Long?,
    val expectedPointerRevision: Long?,
    val expectedIndexRevision: Long?,
    val requiredSession: VaultSessionStamp?,
)
enum class VaultRecoveryPlanReason {
    CONSISTENT, MALFORMED_SNAPSHOT, CONFLICTING_IDENTITY, INTEGRITY_FAILED, KEY_UNAVAILABLE,
    VERIFICATION_REQUIRED, RECONCILIATION_REQUIRED, SOURCE_CLEANUP_REQUIRED, SOURCE_STILL_REFERENCED,
    CLEANUP_UNCONFIRMED, EXPORT_NEEDS_USER, EXTERNAL_COPY_EXISTS, STOP_REQUESTED,
}
class VaultRecoveryPlan(
    val operationId: String,
    val commitId: String,
    val expectedJournalRevision: Long,
    val disposition: RecoveryDisposition,
    orderedSteps: List<VaultRecoveryStep>,
    val reason: VaultRecoveryPlanReason,
) {
    private val steps = orderedSteps.toList()
    val orderedSteps: List<VaultRecoveryStep> get() = steps.toList()
    override fun toString(): String = "VaultRecoveryPlan(disposition=$disposition, reason=$reason)"
}
