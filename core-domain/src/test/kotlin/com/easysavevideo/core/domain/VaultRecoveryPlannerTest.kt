package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

internal class VaultRecoveryFixture {
    var schemaVersion = 1
    var operationId = "operation-1"
    var commitId = "commit-1"
    var targetMediaItemId = "media-1"
    var journalRevision = 5L
    var kind = VaultRecoveryOperationKind.MOVE_INTO_VAULT
    var phase = VaultJournalPhase.CLEANUP_PENDING
    var source = VaultSourceRef("source-1", 2, 3)
    var staging = VaultObjectRef("staging-1", 1, VaultObjectRole.STAGING_CIPHERTEXT, operationId)
    var committed = VaultObjectRef("ciphertext-1", 1, VaultObjectRole.COMMITTED_CIPHERTEXT, operationId)
    var metadata = VaultObjectRef("metadata-1", 1, VaultObjectRole.ENCRYPTED_METADATA, operationId)
    var temps: List<VaultOwnedPlaintextTemp> = emptyList()
    var observedSource = VaultSourceObservation(source, VaultObservationStatus.VALID)
    var candidate = VaultObjectObservation(committed, VaultObservationStatus.VALID, commitId, targetMediaItemId, 7, true)
    var observedMetadata = VaultObjectObservation(metadata, VaultObservationStatus.VALID, commitId, targetMediaItemId, 8, true)
    var pointer = VaultPrivateRecordObservation(VaultObservationStatus.VALID, committed, commitId, targetMediaItemId, 3, true, true)
    var index = pointer.copy(revision = 4)
    var evidence: VaultVerificationEvidence? = VaultVerificationEvidence(committed, commitId, 7, VaultVerificationKind.VAULT_AUTHENTICATED_FORMAT)
    var metadataEvidence: VaultVerificationEvidence? = VaultVerificationEvidence(metadata, commitId, 8, VaultVerificationKind.VAULT_AUTHENTICATED_FORMAT)
    var ownership = VaultSourceOwnership(source, true, 0)
    var key = VaultKeyAvailability.AVAILABLE_IN_CURRENT_SESSION
    var authorization: VaultRecoveryAuthorization? = VaultRecoveryAuthorization(VaultSessionStamp(11, 2), VaultSessionStamp(11, 2))
    var export: VaultExportIntent? = null
    var cleanup = VaultCleanupStatus.COMPLETE
    var stop = VaultRecoveryStop.NONE
    fun temp(id: String, generation: Long = 1) = VaultOwnedPlaintextTemp(
        VaultObjectRef(id, generation, VaultObjectRole.OWNED_PLAINTEXT_TEMP, operationId), true, 0)
    fun snapshot() = VaultRecoverySnapshot(schemaVersion, operationId, commitId, targetMediaItemId, journalRevision,
        kind, phase, source, staging, committed, metadata, temps, observedSource, candidate, observedMetadata, pointer, index,
        evidence, metadataEvidence, ownership, key, authorization, export, cleanup, stop)
    fun plan() = VaultRecoveryPlanner().planRecovery(snapshot())
    fun missingRecord() = VaultPrivateRecordObservation(VaultObservationStatus.MISSING, null, null, null, 2, false, true)
}

class VaultRecoveryPlannerTest {
    private fun kinds(plan: VaultRecoveryPlan) = plan.orderedSteps.map { it.stepId.kind }
    private fun noMutation(plan: VaultRecoveryPlan) {
        assertFalse(VaultRecoveryStepKind.RECONCILE_PRIVATE_POINTER_OR_INDEX in kinds(plan))
        assertFalse(VaultRecoveryStepKind.FINISH_EXPLICIT_MOVE_SOURCE_CLEANUP in kinds(plan))
        assertFalse(VaultRecoveryStepKind.MARK_OPERATION_COMPLETE in kinds(plan))
    }

    @Test fun safeSourceCleanupRequiresDurablePrivateCommitAndFullSourceIdentity() {
        val f = VaultRecoveryFixture(); val plan = f.plan()
        assertEquals(RecoveryDisposition.DEFERRED, plan.disposition)
        assertEquals(listOf(VaultRecoveryStepKind.FINISH_EXPLICIT_MOVE_SOURCE_CLEANUP), kinds(plan))
        val step = plan.orderedSteps.single()
        assertEquals(VaultRecoveryTarget.SourceTarget(f.source), step.stepId.target)
        assertEquals(f.journalRevision, step.expectedJournalRevision)
        assertEquals(f.pointer.revision, step.expectedPointerRevision)
        assertEquals(f.index.revision, step.expectedIndexRevision)
        assertEquals(f.observedMetadata.observationRevision, step.expectedMetadataObservationRevision)
        assertEquals(f.authorization!!.current, step.requiredSession)
    }

    @Test fun sourceReadRevisionGenerationAndOwnershipMismatchesAreIsolated() {
        for (wrong in listOf(VaultSourceRef("other", 2, 3), VaultSourceRef("source-1", 3, 3), VaultSourceRef("source-1", 2, 4))) {
            val f = VaultRecoveryFixture(); f.observedSource = f.observedSource.copy(ref = wrong)
            assertEquals(RecoveryDisposition.ISOLATED, f.plan().disposition); noMutation(f.plan())
            val g = VaultRecoveryFixture(); g.ownership = g.ownership.copy(ref = wrong)
            assertEquals(RecoveryDisposition.ISOLATED, g.plan().disposition); noMutation(g.plan())
        }
    }

    @Test fun ordinaryMediaReceiptOrJournalVerifiedPhaseCannotProveVaultIntegrity() {
        for (phase in listOf(VaultJournalPhase.VERIFIED, VaultJournalPhase.COMPLETE)) {
            for (evidenceKind in listOf(VaultVerificationKind.NONE, VaultVerificationKind.ORDINARY_MEDIA_COMMIT_RECEIPT)) {
                val f = VaultRecoveryFixture(); f.phase = phase; f.evidence = f.evidence!!.copy(kind = evidenceKind)
                assertEquals(RecoveryDisposition.DEFERRED, f.plan().disposition); noMutation(f.plan())
            }
        }
        val f = VaultRecoveryFixture(); f.evidence = null
        assertEquals(VaultRecoveryPlanReason.VERIFICATION_REQUIRED, f.plan().reason); noMutation(f.plan())
    }

    @Test fun encryptedMetadataNeedsIndependentCurrentDurableEvidenceBeforeCleanupOrReady() {
        val f = VaultRecoveryFixture(); f.metadataEvidence = null
        noMutation(f.plan()); assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        f.observedSource = f.observedSource.copy(status = VaultObservationStatus.MISSING)
        assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        val g = VaultRecoveryFixture(); g.observedMetadata = g.observedMetadata.copy(durable = false)
        noMutation(g.plan()); assertEquals(RecoveryDisposition.DEFERRED, g.plan().disposition)
        val h = VaultRecoveryFixture(); h.metadataEvidence = h.metadataEvidence!!.copy(observationRevision = 99)
        assertEquals(RecoveryDisposition.ISOLATED, h.plan().disposition); noMutation(h.plan())
    }

    @Test fun incompleteAndInvalidCandidatesPreserveSourceAndCannotPublish() {
        for (phase in listOf(VaultJournalPhase.PREPARED, VaultJournalPhase.EXECUTING)) {
            val f = VaultRecoveryFixture(); f.phase = phase; f.pointer = f.missingRecord(); f.index = f.missingRecord()
            f.candidate = f.candidate.copy(status = VaultObservationStatus.UNKNOWN); f.evidence = null
            noMutation(f.plan()); assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        }
        for (sourceStatus in listOf(VaultObservationStatus.VALID, VaultObservationStatus.MISSING)) {
            val f = VaultRecoveryFixture(); f.candidate = f.candidate.copy(status = VaultObservationStatus.INVALID)
            f.observedSource = f.observedSource.copy(status = sourceStatus)
            assertEquals(RecoveryDisposition.ISOLATED, f.plan().disposition); noMutation(f.plan())
        }
    }

    @Test fun commitIntentOrSwitchedPointerCanOnlyReconcileExactExistingPrivateTarget() {
        for (pointerMissing in listOf(true, false)) {
            val f = VaultRecoveryFixture()
            f.phase = if (pointerMissing) VaultJournalPhase.COMMIT_INTENT else VaultJournalPhase.POINTER_SWITCHED
            if (pointerMissing) f.pointer = f.missingRecord()
            f.index = f.missingRecord()
            val p = f.plan()
            assertEquals(listOf(VaultRecoveryStepKind.RECONCILE_PRIVATE_POINTER_OR_INDEX), kinds(p))
            assertEquals(VaultRecoveryTarget.ObjectTarget(f.committed), p.orderedSteps.single().stepId.target)
            assertEquals(f.commitId, p.commitId)
            assertFalse(VaultRecoveryStepKind.FINISH_EXPLICIT_MOVE_SOURCE_CLEANUP in kinds(p))
            f.authorization = null
            noMutation(f.plan()); assertEquals(RecoveryDisposition.WAITING_USER, f.plan().disposition)
        }
    }

    @Test fun stillReferencedOrUnauthorizedSourceNeverGetsCleanupStep() {
        val f = VaultRecoveryFixture(); f.ownership = f.ownership.copy(otherActiveReferences = 1)
        assertEquals(RecoveryDisposition.WAITING_USER, f.plan().disposition); noMutation(f.plan())
        val g = VaultRecoveryFixture(); g.ownership = g.ownership.copy(explicitMoveAuthorized = false)
        noMutation(g.plan()); assertEquals(VaultRecoveryPlanReason.SOURCE_STILL_REFERENCED, g.plan().reason)
    }

    @Test fun ownedTempCleanupPrecedesWaitingAndCannotAliasSourceOrPrimaryObject() {
        val f = VaultRecoveryFixture(); f.temps = listOf(f.temp("temp-a"), f.temp("temp-b")); f.key = VaultKeyAvailability.LOCKED
        val p = f.plan()
        assertEquals(listOf(VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP,
            VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP, VaultRecoveryStepKind.WAIT_FOR_AUTHENTICATION_OR_RECOVERY), kinds(p))
        assertNotEquals(p.orderedSteps[0].stepId, p.orderedSteps[1].stepId)
        for (id in listOf(f.source.itemId, f.committed.objectId, f.metadata.objectId)) {
            val g = VaultRecoveryFixture(); g.temps = listOf(g.temp(id))
            assertEquals(RecoveryDisposition.ISOLATED, g.plan().disposition)
            assertFalse(VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP in kinds(g.plan()))
        }
    }

    @Test fun unknownOwnershipAndReferencedTempsAreNeverDeletedAndPreventCompletion() {
        for (temp in listOf(VaultRecoveryFixture().temp("temp-a").copy(ownershipConfirmed = false),
            VaultRecoveryFixture().temp("temp-a").copy(activeReferenceCount = 1))) {
            val f = VaultRecoveryFixture(); f.temps = listOf(temp)
            assertFalse(VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP in kinds(f.plan()))
            noMutation(f.plan()); assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        }
    }

    @Test fun keyStatesAndStaleSessionsNeverCreateAuthorityOrClearSource() {
        for (key in VaultKeyAvailability.entries.filter { it != VaultKeyAvailability.AVAILABLE_IN_CURRENT_SESSION }) {
            val f = VaultRecoveryFixture(); f.key = key
            noMutation(f.plan()); assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        }
        for (stamp in listOf(VaultSessionStamp(12, 2), VaultSessionStamp(11, 3), VaultSessionStamp(0, 2))) {
            val f = VaultRecoveryFixture(); f.authorization = VaultRecoveryAuthorization(stamp, VaultSessionStamp(11, 2))
            noMutation(f.plan()); assertEquals(RecoveryDisposition.WAITING_USER, f.plan().disposition)
        }
    }

    @Test fun commitTargetConflictsPrivateVisibilityAndMissingCommittedObjectsAreIsolated() {
        val cases: List<(VaultRecoveryFixture) -> Unit> = listOf(
            { it.pointer = it.pointer.copy(commitId = "different-commit") },
            { it.index = it.index.copy(targetMediaItemId = "second-media") },
            { it.index = it.index.copy(privateOnly = false) },
            { it.candidate = it.candidate.copy(status = VaultObservationStatus.MISSING) },
            { it.observedMetadata = it.observedMetadata.copy(status = VaultObservationStatus.MISSING) },
            { it.candidate = it.candidate.copy(targetMediaItemId = "second-media") },
        )
        for (change in cases) {
            val f = VaultRecoveryFixture(); change(f)
            assertEquals(RecoveryDisposition.ISOLATED, f.plan().disposition); noMutation(f.plan())
        }
    }

    @Test fun knownMissingCommittedTargetsStayIsolatedWhileLockedOrExporting() {
        for (kind in VaultRecoveryOperationKind.entries) for (key in listOf(VaultKeyAvailability.LOCKED, VaultKeyAvailability.AVAILABLE_IN_CURRENT_SESSION)) {
            for (missingMetadata in listOf(false, true)) {
                val f = VaultRecoveryFixture(); f.kind = kind; f.key = key
                if (kind == VaultRecoveryOperationKind.EXPLICIT_EXPORT)
                    f.export = VaultExportIntent(f.operationId, true, VaultExportPublication.UNKNOWN)
                if (missingMetadata) f.observedMetadata = f.observedMetadata.copy(status = VaultObservationStatus.MISSING)
                else f.candidate = f.candidate.copy(status = VaultObservationStatus.MISSING)
                assertEquals(RecoveryDisposition.ISOLATED, f.plan().disposition)
                assertEquals(VaultRecoveryPlanReason.INTEGRITY_FAILED, f.plan().reason)
                noMutation(f.plan())
            }
        }
    }

    @Test fun stableStepIdentityExcludesPhaseJournalAndObservationRevisionButIncludesExactTarget() {
        val f = VaultRecoveryFixture(); f.index = f.missingRecord()
        val before = f.plan().orderedSteps.single()
        f.phase = VaultJournalPhase.INDEX_COMMITTED; f.journalRevision++
        f.candidate = f.candidate.copy(observationRevision = 9); f.evidence = f.evidence!!.copy(observationRevision = 9)
        f.index = f.index.copy(revision = 9)
        val after = f.plan().orderedSteps.single()
        assertEquals(before.stepId, after.stepId)
        assertNotEquals(before.expectedJournalRevision, after.expectedJournalRevision)
        assertNotEquals(before.expectedObservationRevision, after.expectedObservationRevision)
        assertNotEquals(before.expectedIndexRevision, after.expectedIndexRevision)
        val a = VaultRecoveryFixture(); a.temps = listOf(a.temp("temp-a", 1))
        val firstId = a.plan().orderedSteps.first().stepId
        a.journalRevision++; a.phase = VaultJournalPhase.COMPLETE
        assertEquals(firstId, a.plan().orderedSteps.first().stepId)
        a.temps = listOf(a.temp("temp-a", 2))
        assertNotEquals(firstId, a.plan().orderedSteps.first().stepId)
    }

    @Test fun explicitExportNeverAutomaticallyRepublishesAndExternalCopyIsOnlyAReportedFact() {
        for (publication in VaultExportPublication.entries) for (grant in listOf(true, false)) {
            val f = VaultRecoveryFixture(); f.kind = VaultRecoveryOperationKind.EXPLICIT_EXPORT
            f.export = VaultExportIntent(f.operationId, grant, publication)
            val p = f.plan(); noMutation(p)
            assertEquals(RecoveryDisposition.WAITING_USER, p.disposition)
            assertTrue(VaultRecoveryStepKind.WAIT_FOR_EXPLICIT_EXPORT_RESUME in kinds(p))
        }
        val f = VaultRecoveryFixture(); f.kind = VaultRecoveryOperationKind.EXPLICIT_EXPORT; f.phase = VaultJournalPhase.COMPLETE
        f.export = VaultExportIntent(f.operationId, false, VaultExportPublication.PUBLISHED)
        val p = f.plan(); assertEquals(RecoveryDisposition.READY, p.disposition)
        assertEquals(VaultRecoveryPlanReason.EXTERNAL_COPY_EXISTS, p.reason)
        assertTrue(p.orderedSteps.isEmpty())
    }

    @Test fun stopAndCleanupFailureKeepBoundedRecoveryFromClaimingCompletion() {
        for (stop in VaultRecoveryStop.entries.filter { it != VaultRecoveryStop.NONE }) {
            val f = VaultRecoveryFixture(); f.stop = stop; f.temps = listOf(f.temp("temp-a"))
            val p = f.plan(); assertEquals(RecoveryDisposition.DEFERRED, p.disposition); noMutation(p)
            assertEquals(VaultRecoveryStepKind.CLEAN_OWNED_UNREFERENCED_PLAINTEXT_TEMP, kinds(p).first())
        }
        for (cleanup in listOf(VaultCleanupStatus.PENDING, VaultCleanupStatus.FAILED)) {
            val f = VaultRecoveryFixture(); f.cleanup = cleanup
            noMutation(f.plan()); assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        }
    }

    @Test fun completePhaseAloneIsNotReadyAndConsistentSourceAbsentStateIsReady() {
        val f = VaultRecoveryFixture(); f.phase = VaultJournalPhase.COMPLETE
        assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
        f.observedSource = f.observedSource.copy(status = VaultObservationStatus.MISSING)
        val p = f.plan(); assertEquals(RecoveryDisposition.READY, p.disposition)
        assertEquals(listOf(VaultRecoveryStepKind.MARK_OPERATION_COMPLETE), kinds(p))
        f.observedSource = f.observedSource.copy(status = VaultObservationStatus.UNKNOWN)
        assertNotEquals(RecoveryDisposition.READY, f.plan().disposition)
    }

    @Test fun malformedInputHasOnlyBoundedRejectionAndNeverEchoesIllegalIdentityOrTarget() {
        for (bad in listOf("../../private", "https://private.invalid/a", "x".repeat(129), "..", "")) {
            val f = VaultRecoveryFixture(); f.operationId = bad; f.commitId = bad; f.targetMediaItemId = bad
            val p = f.plan()
            assertEquals(RecoveryDisposition.ISOLATED, p.disposition)
            assertEquals(VaultRecoveryPlanReason.MALFORMED_SNAPSHOT, p.reason)
            assertEquals("invalid-operation", p.operationId); assertEquals("invalid-commit", p.commitId)
            assertTrue(p.orderedSteps.isEmpty())
        }
        val f = VaultRecoveryFixture(); f.schemaVersion = 99
        assertTrue(f.plan().orderedSteps.isEmpty())
        val g = VaultRecoveryFixture(); g.staging = g.staging.copy(operationId = "other-operation")
        assertTrue(g.plan().orderedSteps.isEmpty())
    }
}
