package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/** Describes reconciliation only. Does not touch storage, decrypt, publish, delete, or register a probe. */
class VaultRecoveryPlanner {
    fun planRecovery(snapshot: VaultRecoverySnapshot): VaultRecoveryPlan = Planning(snapshot).plan()

    private class Planning(private val s: VaultRecoverySnapshot) {
        private val steps = mutableListOf<VaultRecoveryStep>()
        private val operationTarget = VaultRecoveryTarget.OperationTarget(s.targetMediaItemId)
        private val session: VaultSessionStamp? get() = s.authorization?.let {
            it.current.takeIf { stamp -> stamp == it.operation && stamp.processInstance > 0 && stamp.authEpoch > 0 }
        }
        private fun add(kind: VaultRecoveryStepKind, target: VaultRecoveryTarget = operationTarget,
            observationRevision: Long? = null, requireSession: Boolean = false) {
            val id = VaultRecoveryStepId(s.operationId, s.commitId, kind, target)
            if (steps.none { it.stepId == id }) steps += VaultRecoveryStep(id, s.journalRevision, observationRevision,
                if (requireSession) s.observedEncryptedMetadata.observationRevision else null,
                if (requireSession) s.observedPointer.revision else null,
                if (requireSession) s.observedIndex.revision else null,
                if (requireSession) session else null)
        }
        private fun result(disposition: RecoveryDisposition, reason: VaultRecoveryPlanReason) =
            VaultRecoveryPlan(s.operationId, s.commitId, s.journalRevision, disposition, steps, reason)
        private fun isolated(reason: VaultRecoveryPlanReason): VaultRecoveryPlan {
            add(VaultRecoveryStepKind.QUARANTINE_INCOMPLETE_OR_INCONSISTENT_OBJECT)
            return result(RecoveryDisposition.ISOLATED, reason)
        }
        private fun wait(reason: VaultRecoveryPlanReason): VaultRecoveryPlan {
            add(VaultRecoveryStepKind.WAIT_FOR_AUTHENTICATION_OR_RECOVERY)
            return result(RecoveryDisposition.WAITING_USER, reason)
        }
        private fun validId(value: String): Boolean = value.length in 1..128 && value != "." && value != ".." &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }
        private fun validSource(ref: VaultSourceRef) = validId(ref.itemId) && ref.revision > 0 && ref.representationGeneration > 0
        private fun validRef(ref: VaultObjectRef) = validId(ref.objectId) && ref.identityGeneration > 0 && ref.operationId == s.operationId
        private fun validObservation(o: VaultObjectObservation) = validRef(o.ref) && o.observationRevision >= 0 &&
            o.commitId == s.commitId && o.targetMediaItemId == s.targetMediaItemId
        private fun recordConflict(o: VaultPrivateRecordObservation): Boolean = o.revision < 0 || when (o.status) {
            VaultObservationStatus.VALID -> o.ref != s.committedRef || o.commitId != s.commitId ||
                o.targetMediaItemId != s.targetMediaItemId || !o.privateOnly
            VaultObservationStatus.INVALID -> true
            else -> o.ref != null || o.commitId != null || o.targetMediaItemId != null
        }
        private fun evidenceMatches(observation: VaultObjectObservation, evidence: VaultVerificationEvidence?): Boolean =
            observation.status == VaultObservationStatus.VALID && observation.durable && evidence != null &&
                evidence.kind == VaultVerificationKind.VAULT_AUTHENTICATED_FORMAT && evidence.ref == observation.ref &&
                evidence.commitId == s.commitId && evidence.observationRevision == observation.observationRevision
        private fun evidenceConflict(observation: VaultObjectObservation, evidence: VaultVerificationEvidence?): Boolean =
            evidence != null && (evidence.ref != observation.ref || evidence.commitId != s.commitId ||
                evidence.observationRevision != observation.observationRevision)

        fun plan(): VaultRecoveryPlan {
            val refs = listOf(s.stagingRef, s.committedRef, s.encryptedMetadataRef) + s.ownedPlaintextTemps.map { it.ref }
            if (s.schemaVersion != 1 || !validId(s.operationId) || !validId(s.commitId) || !validId(s.targetMediaItemId) ||
                s.journalRevision < 0 || !validSource(s.sourceRef) || refs.any { !validRef(it) } ||
                s.stagingRef.role != VaultObjectRole.STAGING_CIPHERTEXT || s.committedRef.role != VaultObjectRole.COMMITTED_CIPHERTEXT ||
                s.encryptedMetadataRef.role != VaultObjectRole.ENCRYPTED_METADATA ||
                s.ownedPlaintextTemps.any { it.ref.role != VaultObjectRole.OWNED_PLAINTEXT_TEMP || it.activeReferenceCount < 0 } ||
                s.sourceOwnership.otherActiveReferences < 0 || s.ownedPlaintextTemps.size > 128) {
                return VaultRecoveryPlan(
                    s.operationId.takeIf(::validId) ?: "invalid-operation",
                    s.commitId.takeIf(::validId) ?: "invalid-commit",
                    s.journalRevision.takeIf { it >= 0 } ?: 0,
                    RecoveryDisposition.ISOLATED, emptyList(), VaultRecoveryPlanReason.MALFORMED_SNAPSHOT)
            }
            // One opaque object ID must not alias a different role, generation, source, or target.
            if (refs.map { it.objectId }.distinct().size != refs.size || refs.any { it.objectId == s.sourceRef.itemId } ||
                s.observedSource.ref != s.sourceRef || s.sourceOwnership.ref != s.sourceRef ||
                s.observedCandidate.ref != s.committedRef || s.observedEncryptedMetadata.ref != s.encryptedMetadataRef ||
                !validObservation(s.observedCandidate) || !validObservation(s.observedEncryptedMetadata) ||
                recordConflict(s.observedPointer) || recordConflict(s.observedIndex) ||
                evidenceConflict(s.observedCandidate, s.verificationEvidence) ||
                evidenceConflict(s.observedEncryptedMetadata, s.metadataVerificationEvidence) ||
                s.exportIntent?.operationId?.let { it != s.operationId } == true) {
                return isolated(VaultRecoveryPlanReason.CONFLICTING_IDENTITY)
            }
            if (s.kind == VaultRecoveryOperationKind.MOVE_INTO_VAULT && s.exportIntent != null) {
                return isolated(VaultRecoveryPlanReason.CONFLICTING_IDENTITY)
            }
            // Only proven owned, unreferenced temps are eligible. Sources and committed objects are excluded above.
            for (temp in s.ownedPlaintextTemps) {
                if (temp.ownershipConfirmed && temp.activeReferenceCount == 0)
                    add(VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP, VaultRecoveryTarget.ObjectTarget(temp.ref))
            }
            if (s.stop != VaultRecoveryStop.NONE) {
                add(VaultRecoveryStepKind.DEFER_BOUNDED_VERIFICATION)
                return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.STOP_REQUESTED)
            }
            if (s.observedCandidate.status == VaultObservationStatus.INVALID || s.observedEncryptedMetadata.status == VaultObservationStatus.INVALID) {
                return isolated(VaultRecoveryPlanReason.INTEGRITY_FAILED)
            }
            val pointerPresent = s.observedPointer.status == VaultObservationStatus.VALID
            val indexPresent = s.observedIndex.status == VaultObservationStatus.VALID
            // Existence contradictions remain contradictions while locked and during export recovery.
            if ((pointerPresent || indexPresent || s.phase == VaultJournalPhase.COMPLETE) &&
                (s.observedCandidate.status == VaultObservationStatus.MISSING || s.observedEncryptedMetadata.status == VaultObservationStatus.MISSING)) {
                return isolated(VaultRecoveryPlanReason.INTEGRITY_FAILED)
            }
            if (s.keyAvailability == VaultKeyAvailability.INVALIDATED) return isolated(VaultRecoveryPlanReason.KEY_UNAVAILABLE)
            if (s.kind == VaultRecoveryOperationKind.EXPLICIT_EXPORT) return export()
            if (s.keyAvailability != VaultKeyAvailability.AVAILABLE_IN_CURRENT_SESSION || session == null) {
                return wait(VaultRecoveryPlanReason.KEY_UNAVAILABLE)
            }
            if (!evidenceMatches(s.observedCandidate, s.verificationEvidence) ||
                !evidenceMatches(s.observedEncryptedMetadata, s.metadataVerificationEvidence)) {
                add(VaultRecoveryStepKind.DEFER_BOUNDED_VERIFICATION)
                return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.VERIFICATION_REQUIRED)
            }
            if (s.cleanupStatus != VaultCleanupStatus.COMPLETE || s.ownedPlaintextTemps.isNotEmpty()) {
                return wait(VaultRecoveryPlanReason.CLEANUP_UNCONFIRMED)
            }
            if (s.observedPointer.status == VaultObservationStatus.UNKNOWN || s.observedIndex.status == VaultObservationStatus.UNKNOWN) {
                add(VaultRecoveryStepKind.DEFER_BOUNDED_VERIFICATION)
                return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.VERIFICATION_REQUIRED)
            }
            if (!pointerPresent || !indexPresent || !s.observedPointer.durable || !s.observedIndex.durable) {
                // One semantic reconciliation step targets the existing object and never allocates another media ID.
                add(VaultRecoveryStepKind.RECONCILE_PRIVATE_POINTER_OR_INDEX, VaultRecoveryTarget.ObjectTarget(s.committedRef),
                    s.observedCandidate.observationRevision, true)
                return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.RECONCILIATION_REQUIRED)
            }
            when (s.observedSource.status) {
                VaultObservationStatus.VALID -> {
                    if (!s.sourceOwnership.explicitMoveAuthorized || s.sourceOwnership.otherActiveReferences != 0)
                        return wait(VaultRecoveryPlanReason.SOURCE_STILL_REFERENCED)
                    add(VaultRecoveryStepKind.FINISH_EXPLICIT_MOVE_SOURCE_CLEANUP, VaultRecoveryTarget.SourceTarget(s.sourceRef),
                        s.observedCandidate.observationRevision, true)
                    return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.SOURCE_CLEANUP_REQUIRED)
                }
                VaultObservationStatus.UNKNOWN -> {
                    add(VaultRecoveryStepKind.DEFER_BOUNDED_VERIFICATION)
                    return result(RecoveryDisposition.DEFERRED, VaultRecoveryPlanReason.VERIFICATION_REQUIRED)
                }
                VaultObservationStatus.INVALID -> return isolated(VaultRecoveryPlanReason.INTEGRITY_FAILED)
                VaultObservationStatus.MISSING -> Unit
            }
            add(VaultRecoveryStepKind.MARK_OPERATION_COMPLETE)
            return result(RecoveryDisposition.READY, VaultRecoveryPlanReason.CONSISTENT)
        }

        private fun export(): VaultRecoveryPlan {
            // No step exists for decrypting, writing, resuming or republishing an external export.
            if (s.exportIntent?.publication == VaultExportPublication.PUBLISHED && s.phase == VaultJournalPhase.COMPLETE &&
                s.cleanupStatus == VaultCleanupStatus.COMPLETE && s.ownedPlaintextTemps.isEmpty() &&
                s.keyAvailability == VaultKeyAvailability.AVAILABLE_IN_CURRENT_SESSION && session != null &&
                evidenceMatches(s.observedCandidate, s.verificationEvidence) &&
                evidenceMatches(s.observedEncryptedMetadata, s.metadataVerificationEvidence) &&
                s.observedPointer.status == VaultObservationStatus.VALID && s.observedPointer.durable &&
                s.observedIndex.status == VaultObservationStatus.VALID && s.observedIndex.durable) {
                return result(RecoveryDisposition.READY, VaultRecoveryPlanReason.EXTERNAL_COPY_EXISTS)
            }
            add(VaultRecoveryStepKind.MARK_EXPORT_NEEDS_USER_ACTION)
            add(VaultRecoveryStepKind.WAIT_FOR_EXPLICIT_EXPORT_RESUME)
            return result(RecoveryDisposition.WAITING_USER, VaultRecoveryPlanReason.EXPORT_NEEDS_USER)
        }
    }
}
