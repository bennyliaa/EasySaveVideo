package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.DirectWorkspacePresence
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.hasDirectOutputProof
import com.easysavevideo.save.hasOwnedDirectOutputEvidence

/** A durable ledger never becomes legacy because an incoming record omitted its proof. */
internal object NormalizationDurableMutationPolicy {
    /** DTO insertion and a missing ledger can never manufacture an owned-output issuer. */
    fun permitsInsert(record: SaveRecord): Boolean = !record.hasOwnedDirectOutputEvidence()
    fun permitsLegacyRecord(record: SaveRecord?): Boolean = record?.hasOwnedDirectOutputEvidence() != true
    /** Complete entry fence, before generic Save SQL or Library publication can run. */
    fun permitsGeneric(prior: SaveRecord, next: SaveRecord, presence: DirectWorkspacePresence, owned: Boolean, row: NormalizationWorkspace? = null): Boolean {
        if (presence == DirectWorkspacePresence.UNREADABLE) return false
        val proofBound = prior.hasDirectOutputProof() || next.hasDirectOutputProof() ||
            prior.verification?.directNormalizationProof != null || next.verification?.directNormalizationProof != null ||
            prior.verification?.directConversionProof != null || next.verification?.directConversionProof != null
        if (proofBound && presence != DirectWorkspacePresence.PRESENT) return false
        if (presence == DirectWorkspacePresence.PRESENT) {
            val exact = row ?: return false
            if (exact.key.owner.taskId != prior.task.taskId || exact.key.owner.artifactId != prior.artifact?.artifactId ||
                exact.key.owner.commitId != prior.commitId || prior.transfer?.let {
                    it.bytesOnDisk != exact.raw.size || com.easysavevideo.save.sourceTransferFingerprint(it) != exact.sourceTransferSha256
                } != false) return false
            prior.directConversion?.let { proof ->
                if (proof.normalizationId != exact.key.normalizationId || proof.rawSha256 != exact.raw.sha256 ||
                    proof.digest() != exact.proofSha256 || exact.output != NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256)) return false
            }
            if (!permitsPhase(prior, next, exact)) return false
        } else if (owned && prior.phase != next.phase &&
            !(prior.phase == SavePhase.EXECUTING && next.phase == SavePhase.NORMALIZING && !proofBound)) return false
        return if (presence == DirectWorkspacePresence.PRESENT || owned || proofBound)
            permitsControl(prior, next, owned) else true
    }
    private fun permitsPhase(prior: SaveRecord, next: SaveRecord, row: NormalizationWorkspace): Boolean {
        if (prior.phase == next.phase) return true
        if (next.task.state != TaskState.RUNNING || prior.task.state != TaskState.RUNNING ||
            row.disposition != NormalizationDisposition.CONTINUE || row.failure != null || row.cleanup != null) return false
        if (prior.directConversion != null || next.directConversion != null) {
            val proof = prior.directConversion ?: return false
            if (next.directConversion != proof || prior.directNormalization != null || next.directNormalization != null ||
                !proof.isCurrentPolicy() ||
                (prior.request.networkSourceMetadata?.expectedAudio == true && proof.expectation.audio == null) ||
                prior.checkpoint != null ||
                prior.verification != null || prior.receipt != null ||
                proof.normalizationId != row.key.normalizationId || proof.digest() != row.proofSha256 ||
                NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) != row.raw ||
                NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) != row.output ||
                proof.sourceTransferSha256 != row.sourceTransferSha256) return false
            // Independent evidence and VERIFYING -> COMMITTING are reserved to typed verification CAS.
            return when (row.lifecycle) {
                NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT ->
                    prior.phase == SavePhase.NORMALIZING && next.phase == SavePhase.ADOPTING
                NormalizationLifecycle.ADOPTED -> prior.phase == SavePhase.ADOPTING && next.phase == SavePhase.VERIFYING
                else -> false
            }
        }
        val proof = prior.directNormalization
        val boundProof = proof != null && proof.normalizationId == row.key.normalizationId && proof.digest() == row.proofSha256
        return when (row.lifecycle) {
            NormalizationLifecycle.RAW_COMPLETE -> proof == null && prior.phase == SavePhase.EXECUTING && next.phase == SavePhase.NORMALIZING
            NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT ->
                boundProof && prior.phase == SavePhase.NORMALIZING && next.phase == SavePhase.ADOPTING
            NormalizationLifecycle.ADOPTED -> boundProof &&
                ((prior.phase == SavePhase.ADOPTING && next.phase == SavePhase.VERIFYING) ||
                 (prior.phase == SavePhase.VERIFYING && next.phase == SavePhase.COMMITTING && prior.verification != null))
            else -> false
        }
    }

    fun permitsControl(prior: SaveRecord, next: SaveRecord, owned: Boolean): Boolean {
        if (prior.task.state.isTerminal || next.task.state == TaskState.SUCCEEDED ||
            prior.task.revision == Long.MAX_VALUE || next.task.revision != prior.task.revision + 1 ||
            next.task.taskId != prior.task.taskId || next.task.method != prior.task.method ||
            next.task.progressPercent != prior.task.progressPercent) return false
        if (prior.task.state == TaskState.WAITING_USER && next.task.state == TaskState.WAITING_USER &&
            prior.task.waitingUser != next.task.waitingUser) return false
        val resume = prior.task.state in setOf(TaskState.WAITING_USER, TaskState.INTERRUPTED) &&
            next.task.state == TaskState.RUNNING && next.phase == prior.phase &&
            next.operationId != prior.operationId
        if (resume && (owned || prior.directConversion?.isCurrentPolicy() == false)) return false
        if (!resume && (next.operationId != prior.operationId || next.deadlineEpochMillis != prior.deadlineEpochMillis ||
            next.retryBudget != prior.retryBudget || next.attempt != prior.attempt)) return false
        if (!resume && next.task.state !in setOf(prior.task.state, TaskState.WAITING_USER,
                TaskState.INTERRUPTED, TaskState.FAILED, TaskState.CANCELLED)) return false
        if (next.phase != prior.phase && next.phase !in setOf(SavePhase.PREPARING,
                SavePhase.NORMALIZING, SavePhase.ADOPTING, SavePhase.VERIFYING,
                SavePhase.COMMITTING, SavePhase.CANCELLING, SavePhase.CLEANUP)) return false
        return prior.copy(task = next.task, phase = next.phase, error = next.error,
            waitReason = next.waitReason, operationId = next.operationId,
            deadlineEpochMillis = next.deadlineEpochMillis, retryBudget = next.retryBudget,
            attempt = next.attempt) == next
    }
}
