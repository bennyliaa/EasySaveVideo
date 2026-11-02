package com.easysavevideo.library.edit

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class EditLifecycleTest {
    private val plan = EditFixtures.plan()
    private val output = EditFixtures.output()
    private val proof = EditFixtures.evidence(plan)
    private val gate = EditNativeResourceGate()
    private val lifecycle = EditLifecycle(plan, gate)
    private val execution = Any()
    private val verifier = Any()

    private fun executed() {
        assertTrue(lifecycle.beginExecution(execution, output))
        assertTrue(lifecycle.executionFinished(execution, true, EditRelease.RELEASED))
    }
    private fun verified() {
        executed(); assertTrue(lifecycle.beginVerification(verifier))
        assertTrue(lifecycle.verificationFinished(verifier, proof, EditRelease.RELEASED))
    }

    @Test fun successRequiresBothActualReleasesIndependentProofAndPublication() {
        assertThrows(EditFailure::class.java) { lifecycle.beginPublication() }
        assertTrue(lifecycle.beginExecution(execution, output))
        assertFalse(lifecycle.snapshot().publicationAllowed)
        assertTrue(lifecycle.executionFinished(execution, true, EditRelease.RELEASED))
        assertEquals(EditPhase.EXECUTED, lifecycle.snapshot().phase)
        assertFalse(lifecycle.snapshot().publicationAllowed)
        assertTrue(lifecycle.beginVerification(verifier))
        assertFalse(lifecycle.snapshot().publicationAllowed)
        lifecycle.verificationFinished(verifier, proof, EditRelease.RELEASED)
        assertTrue(lifecycle.snapshot().publicationAllowed)
        assertEquals(proof, lifecycle.beginPublication())
        assertEquals(EditPhase.PUBLISHING, lifecycle.snapshot().phase)
        assertTrue(lifecycle.publicationSucceeded()); assertFalse(lifecycle.publicationSucceeded())
        assertEquals(EditPhase.SUCCEEDED, lifecycle.snapshot().phase)
    }

    @Test fun encoderCompletionCannotStandInForIndependentVerification() {
        executed()
        assertThrows(EditFailure::class.java) { lifecycle.beginPublication() }
        assertFalse(lifecycle.publicationSucceeded())
        lifecycle.beginVerification(verifier)
        lifecycle.verificationFinished(verifier, null, EditRelease.RELEASED)
        assertEquals(EditError.VERIFICATION_FAILED, lifecycle.snapshot().error)
        assertTrue(lifecycle.snapshot().cleanupAllowed)
        assertThrows(EditFailure::class.java) { lifecycle.beginPublication() }
    }

    @Test fun cancellationBeforeWorkIsImmediateFreeAndTerminal() {
        assertEquals(EditCancellation.ACCEPTED, lifecycle.requestCancel())
        assertEquals(EditPhase.CANCELLED, lifecycle.snapshot().phase)
        assertEquals(EditCancellation.ALREADY_TERMINAL, lifecycle.requestCancel())
        assertFalse(gate.isOccupied)
        assertThrows(EditFailure::class.java) { lifecycle.beginExecution(execution, output) }
    }

    @Test fun cancellingExecutionRetainsOwnerAndBlocksCleanupUntilActualRelease() {
        lifecycle.beginExecution(execution, output)
        assertEquals(EditCancellation.ACCEPTED, lifecycle.requestCancel())
        assertEquals(EditPhase.CANCELLING, lifecycle.snapshot().phase)
        assertTrue(gate.isOccupied); assertTrue(lifecycle.snapshot().ownerRetained)
        assertFalse(lifecycle.cleanupConfirmed()); assertFalse(lifecycle.snapshot().cleanupAllowed)
        lifecycle.executionFinished(execution, true, EditRelease.RELEASED)
        assertEquals(EditPhase.CLEANUP_PENDING, lifecycle.snapshot().phase)
        assertFalse(gate.isOccupied); assertTrue(lifecycle.cleanupConfirmed())
        assertEquals(EditPhase.CANCELLED, lifecycle.snapshot().phase)
    }

    @Test fun cancelAfterExecutionBeforeVerifierPreventsNativeVerification() {
        executed(); lifecycle.requestCancel()
        assertTrue(lifecycle.snapshot().cleanupAllowed)
        assertThrows(EditFailure::class.java) { lifecycle.beginVerification(verifier) }
    }

    @Test fun cancellingVerifierDiscardsEvenValidLateProof() {
        executed(); lifecycle.beginVerification(verifier); lifecycle.requestCancel()
        assertFalse(lifecycle.snapshot().cleanupAllowed)
        lifecycle.verificationFinished(verifier, proof, EditRelease.RELEASED)
        assertFalse(lifecycle.snapshot().publicationAllowed)
        assertTrue(lifecycle.snapshot().cleanupAllowed)
        assertTrue(lifecycle.cleanupConfirmed()); assertEquals(EditPhase.CANCELLED, lifecycle.snapshot().phase)
    }

    @Test fun cancelAtReadyFenceBeatsPublicationAndAfterCommitPointIsTooLate() {
        verified(); assertEquals(EditCancellation.ACCEPTED, lifecycle.requestCancel())
        assertThrows(EditFailure::class.java) { lifecycle.beginPublication() }
        val other = EditLifecycle(plan, EditNativeResourceGate())
        val owner = Any(); val verifyOwner = Any()
        other.beginExecution(owner, output); other.executionFinished(owner, true, EditRelease.RELEASED)
        other.beginVerification(verifyOwner); other.verificationFinished(verifyOwner, proof, EditRelease.RELEASED)
        other.beginPublication()
        assertEquals(EditCancellation.TOO_LATE, other.requestCancel())
        assertFalse(other.snapshot().cancelRequested)
        assertTrue(other.publicationSucceeded())
    }

    @Test fun unconfirmedExecutionReleaseRetainsOwnerAndPermanentlyFencesAdmission() {
        lifecycle.beginExecution(execution, output)
        lifecycle.executionFinished(execution, true, EditRelease.UNCONFIRMED)
        assertEquals(EditPhase.RELEASE_PENDING, lifecycle.snapshot().phase)
        assertTrue(gate.isOccupied); assertTrue(gate.requiresRestart)
        assertFalse(gate.claim(Any())); assertFalse(lifecycle.cleanupConfirmed())
        assertThrows(EditFailure::class.java) { lifecycle.beginVerification(verifier) }
        assertTrue(lifecycle.confirmLateRelease(execution))
        assertFalse(gate.isOccupied); assertTrue(gate.requiresRestart); assertFalse(gate.claim(Any()))
        assertTrue(lifecycle.snapshot().cleanupAllowed); assertFalse(lifecycle.snapshot().publicationAllowed)
    }

    @Test fun unconfirmedVerifierReleaseCannotPublishDespiteValidDecodeEvidence() {
        executed(); lifecycle.beginVerification(verifier)
        lifecycle.verificationFinished(verifier, proof, EditRelease.UNCONFIRMED)
        assertTrue(lifecycle.snapshot().ownerRetained); assertFalse(lifecycle.snapshot().publicationAllowed)
        assertFalse(lifecycle.cleanupConfirmed()); assertThrows(EditFailure::class.java) { lifecycle.beginPublication() }
        lifecycle.confirmLateRelease(verifier)
        assertTrue(lifecycle.snapshot().cleanupAllowed); assertFalse(lifecycle.snapshot().publicationAllowed)
    }

    @Test fun timeoutNeverFabricatesResourceReleaseAndLateSuccessCannotReviveIt() {
        lifecycle.beginExecution(execution, output)
        lifecycle.fail(EditError.TIMEOUT)
        assertTrue(gate.isOccupied); assertTrue(gate.requiresRestart)
        assertEquals(EditPhase.RELEASE_PENDING, lifecycle.snapshot().phase)
        lifecycle.executionFinished(execution, true, EditRelease.RELEASED)
        assertTrue(lifecycle.snapshot().cleanupAllowed)
        assertEquals(EditError.TIMEOUT, lifecycle.snapshot().error)
        assertFalse(lifecycle.snapshot().publicationAllowed); assertFalse(gate.claim(Any()))
        lifecycle.cleanupConfirmed(); assertEquals(EditPhase.FAILED, lifecycle.snapshot().phase)
    }

    @Test fun wrongOrStaleOwnerCannotReleaseCurrentNativeWork() {
        lifecycle.beginExecution(execution, output)
        assertFalse(lifecycle.executionFinished(Any(), true, EditRelease.RELEASED))
        assertTrue(gate.isOccupied)
        lifecycle.executionFinished(execution, true, EditRelease.RELEASED)
        lifecycle.beginVerification(verifier)
        assertFalse(lifecycle.executionFinished(execution, true, EditRelease.RELEASED))
        assertFalse(lifecycle.confirmLateRelease(execution)); assertTrue(gate.isOccupied)
        assertFalse(gate.released(execution)); assertFalse(gate.unconfirmed(execution))
        assertFalse(gate.requiresRestart)
    }

    @Test fun executionAndVerifierCannotReuseOneCallbackIdentity() {
        executed()
        assertThrows(EditFailure::class.java) { lifecycle.beginVerification(execution) }
        assertFalse(gate.isOccupied)
    }

    @Test fun failedExecutionOnlyCleansOwnedOutputAfterRelease() {
        lifecycle.beginExecution(execution, output)
        lifecycle.executionFinished(execution, false, EditRelease.RELEASED)
        assertEquals(EditError.EXECUTION_FAILED, lifecycle.snapshot().error)
        assertTrue(lifecycle.snapshot().cleanupAllowed)
        assertTrue(lifecycle.cleanupConfirmed()); assertFalse(lifecycle.cleanupConfirmed())
        assertEquals(EditPhase.FAILED, lifecycle.snapshot().phase)
    }

    @Test fun mismatchedArtifactEvidenceCannotPassPublicationFence() {
        executed(); lifecycle.beginVerification(verifier)
        lifecycle.verificationFinished(verifier, proof.copy(output = output.copy(artifactId = "foreign")), EditRelease.RELEASED)
        assertEquals(EditError.VERIFICATION_FAILED, lifecycle.snapshot().error)
        assertFalse(lifecycle.snapshot().publicationAllowed)
    }

    @Test fun uncertainPublicationRequiresRecoveryNotCleanupOrFalseFailure() {
        verified(); lifecycle.beginPublication(); lifecycle.publicationUncertain()
        assertEquals(EditPhase.RECOVERY_REQUIRED, lifecycle.snapshot().phase)
        assertFalse(lifecycle.snapshot().cleanupAllowed); assertFalse(lifecycle.cleanupConfirmed())
        assertFalse(lifecycle.publicationSucceeded()); assertEquals(EditCancellation.TOO_LATE, lifecycle.requestCancel())
    }

    @Test fun busySharedGateRejectsSecondTaskAndRetainsFirstOwner() {
        lifecycle.beginExecution(execution, output)
        val second = EditLifecycle(plan, gate)
        assertFalse(second.beginExecution(Any(), output.copy(artifactId = "second")))
        assertEquals(EditError.CAPACITY, second.snapshot().error)
        assertTrue(second.snapshot().cleanupAllowed); assertTrue(gate.isOccupied)
        assertTrue(lifecycle.snapshot().ownerRetained)
    }

    @Test fun racingClaimsHaveExactlyOneNativeOwner() {
        val start = CountDownLatch(1)
        val count = AtomicInteger()
        val threads = List(24) { Thread { start.await(); if (gate.claim(Any())) count.incrementAndGet() } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach(Thread::join)
        assertEquals(1, count.get()); assertTrue(gate.isOccupied)
    }

    @Test fun cancelAndPublicationRaceHasOneLinearizedOutcome() {
        repeat(50) {
            val local = EditLifecycle(plan, EditNativeResourceGate())
            val owner = Any(); val verifyOwner = Any()
            local.beginExecution(owner, output); local.executionFinished(owner, true, EditRelease.RELEASED)
            local.beginVerification(verifyOwner); local.verificationFinished(verifyOwner, proof, EditRelease.RELEASED)
            val start = CountDownLatch(1)
            val cancellation = arrayOfNulls<EditCancellation>(1)
            val publishWins = AtomicInteger()
            val cancel = Thread { start.await(); cancellation[0] = local.requestCancel() }
            val publish = Thread { start.await(); try { local.beginPublication(); publishWins.incrementAndGet() } catch (_: EditFailure) { } }
            cancel.start(); publish.start(); start.countDown(); cancel.join(); publish.join()
            if (publishWins.get() == 1) {
                assertEquals(EditCancellation.TOO_LATE, cancellation[0]); assertFalse(local.snapshot().cancelRequested)
            } else {
                assertEquals(EditCancellation.ACCEPTED, cancellation[0]); assertTrue(local.snapshot().cleanupAllowed)
            }
        }
    }
}
