package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production CAS guard decision; does not execute Android SQLite transactions. */
class AdoptedCanonicalMutationPolicyTest {
    private fun record(): SaveRecord {
        val f = NormalizationDescriptorFixture()
        val proof = DirectNormalizationProof(f.knownKey.normalizationId, f.owner.taskId,
            f.record!!.request.networkResource!!.sourceIdentity, f.owner.artifactId, f.owner.commitId,
            f.raw.size, f.raw.sha256, f.sourceDigest, f.output.size, f.output.sha256, normalizationTestConservation())
        return f.record!!.copy(phase = SavePhase.VERIFYING, directNormalization = proof, retryBudget = 1)
    }
    private fun stop(prior: SaveRecord, state: TaskState = TaskState.CANCELLED): SaveRecord = prior.copy(
        task = prior.task.copy(revision = prior.task.revision + 1, state = state,
            waitingUser = if (state == TaskState.WAITING_USER) UserWait(200_000, ResumeAction.REVIEW_RECOVERY) else null),
        error = SaveError.CANCELLED,
        waitReason = if (state == TaskState.WAITING_USER) SaveWaitReason.RECOVERY else null)

    @Test fun stopOnlyStatesPreserveSelectedDataAndMayRevokeEvidence() {
        val prior = record()
        for (state in listOf(TaskState.CANCELLED, TaskState.FAILED, TaskState.WAITING_USER, TaskState.INTERRUPTED)) {
            val next = stop(prior, state)
            assertTrue(state.name, AdoptedCanonicalMutationPolicy.permitsStopOnly(prior, next))
            assertEquals(prior.directNormalization, next.directNormalization)
            assertEquals(prior.transfer, next.transfer)
        }
    }
    @Test fun cancellingPhaseIsAllowedOnceWithoutFakingAStateOrChangingData() {
        val prior = record()
        val cancelling = prior.copy(task = prior.task.copy(revision = 2), phase = SavePhase.CANCELLING)
        assertTrue(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior, cancelling))
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(cancelling,
            cancelling.copy(task = cancelling.task.copy(revision = 3))))
        assertTrue(AdoptedCanonicalMutationPolicy.permitsStopOnly(cancelling, stop(cancelling)))
    }
    @Test fun selectedDataAndOperationAccountingCannotChangeAlongsideCancellation() {
        val prior = record(); val next = stop(prior)
        for (changed in listOf(
            next.copy(operationId = "other"), next.copy(deadlineEpochMillis = next.deadlineEpochMillis + 1),
            next.copy(retryBudget = next.retryBudget + 1), next.copy(attempt = next.attempt + 1),
            next.copy(bytesTransferred = next.bytesTransferred + 1), next.copy(directNormalization = null),
            next.copy(transfer = null, directNormalization = null), next.copy(phase = SavePhase.COMMITTING),
            next.copy(phase = SavePhase.CLEANUP), next.copy(task = next.task.copy(progressPercent = 100)),
            next.copy(schemaVersion = next.schemaVersion + 1))) {
            assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior, changed))
        }
    }
    @Test fun runningProgressAndPublicationAreRejectedWhileReadOwned() {
        val prior = record()
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior,
            prior.copy(task = prior.task.copy(revision = 2))))
        val proof = prior.directNormalization!!; val c = proof.conservation
        val checked = DirectNormalizationVerificationProof(proof.normalizationId, proof.taskId, proof.sourceIdentity,
            proof.artifactId, proof.commitId, proof.digest(), c,
            DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true))
        val verification = VerificationEvidence(proof.artifactId, proof.outputSizeBytes, proof.outputSha256,
            true, 1, 0, c.endpoint.minimumUs / 1000, c.width, c.height, false,
            DecodePosition.entries.toSet(), true, directNormalizationProof = checked)
        val published = prior.copy(task = prior.task.copy(revision = 2, state = TaskState.SUCCEEDED),
            verification = verification, receipt = MediaCommitReceipt(proof.commitId, proof.artifactId,
                "media", proof.outputSizeBytes, proof.outputSha256))
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior, published))
    }
    @Test fun revisionMismatchAndTerminalRevivalCannotPass() {
        val prior = record(); val next = stop(prior)
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior,
            next.copy(task = next.task.copy(revision = prior.task.revision))))
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(prior,
            next.copy(task = next.task.copy(revision = prior.task.revision + 2))))
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(next,
            prior.copy(task = prior.task.copy(revision = 3))))
    }
    @Test fun repeatedWaitingCannotRenewItsDeadlineThroughTheReadFence() {
        val waiting = stop(record(), TaskState.WAITING_USER)
        val renewed = waiting.copy(task = waiting.task.copy(revision = waiting.task.revision + 1,
            waitingUser = waiting.task.waitingUser!!.copy(expiresAtEpochMillis = 300_000)))
        assertFalse(AdoptedCanonicalMutationPolicy.permitsStopOnly(waiting, renewed))
    }
}
