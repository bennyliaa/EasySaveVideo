package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Fake IO, but the production Coordinator, admission, attempt views, gate and CAS algorithm. */
class ControlledDirectPipelineTest {
    @Test fun strictMovTraversesActualCoordinatorAndRetainsRawTupleWithOneAtomicProofCheckpointCas() {
        val h = Harness(); val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(4096L, result.transfer!!.bytesOnDisk)
        assertEquals("video/quicktime", result.transfer!!.responseMimeType)
        assertEquals(2048L, result.receipt!!.sizeBytes)
        assertNull(result.checkpoint); assertNotNull(result.directNormalization)
        assertEquals(1, h.transfers); assertEquals(1, h.normalizations); assertEquals(1, h.normalizedVerifications)
        assertEquals(0, h.legacyVerifications); assertEquals(0, h.store.commitCalls)
        assertEquals(1, h.proofCas); assertEquals(1, h.evidenceCas); assertEquals(1, h.publications)
        assertEquals(1, h.commitCloses); assertEquals(1, h.proofCloses); assertEquals(1, h.verificationCloses)
        assertEquals(1, h.cleanupCalls)
        assertTrue(h.events.indexOf("transfer-released") < h.events.indexOf("selector"))
        assertTrue(h.events.indexOf("selector-close") < h.events.indexOf("adopt"))
        assertEquals(result.bytesTransferred, h.networkChargeAtProof)
        assertThrows(SaveFailure::class.java) { h.execution!!.requireLive(h.execution!!.context) }
    }

    @Test fun privateMovUsesRandomDurableMetadataThroughoutActualControlledPipeline() {
        val h = Harness(privateTask = true)
        val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertTrue(result.request.input is SaveInput.PrivateNetwork); assertNull(result.request.networkResource)
        val binding = requireNotNull(h.privateBinding)
        assertEquals(binding.sourceIdentity.encoded, result.directNormalization!!.sourceIdentity)
        assertEquals(result.directNormalization!!.sourceIdentity, result.verification!!.directNormalizationProof!!.sourceIdentity)
        assertNull(result.checkpoint); assertEquals(1, h.normalizations); assertEquals(1, h.publications)
        assertTrue(h.coordinator.isPrivateSourceDetached("task", binding))
        assertEquals(PrivateSourceExitResult.OUTPUT_ONLY, h.coordinator.signalPrivateSourceExit("task", binding))
        assertEquals(TaskState.SUCCEEDED, h.read("task")!!.task.state)
    }

    @Test fun ordinaryMp4UsesSameGateAndExistingEvidenceCommitWithoutCreatingWorkspace() {
        val h = Harness(mov = false); val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(0, h.normalizations); assertEquals(0, h.normalizedVerifications)
        assertEquals(1, h.legacyVerifications); assertEquals(1, h.store.commitCalls)
        assertNull(result.directNormalization)
        assertTrue(h.events.indexOf("selector-close") < h.events.indexOf("legacy"))
    }

    @Test fun defaultAcceptanceAlsoRejectsMovDisguisedAsOctetStreamBeforeWriterOrLegacyNative() {
        val h = Harness(enabled = false, mime = "application/octet-stream")
        val result = h.run()
        assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.UNSUPPORTED_MEDIA, result.error)
        assertEquals(1, h.selects); assertEquals(0, h.normalizations); assertEquals(0, h.legacyVerifications)
        assertEquals(0, h.normalizedVerifications); assertEquals(1, h.store.cleanup.size)
    }

    @Test fun presentOrUnreadableOrphanFencePrecedesPolicySourcePrepareAndTransport() {
        for (presence in listOf(DirectWorkspacePresence.PRESENT, DirectWorkspacePresence.UNREADABLE)) {
            val h = Harness(); h.presence = presence
            val result = h.run()
            assertEquals(TaskState.WAITING_USER, result.task.state); assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
            assertEquals(0, h.policyCalls); assertEquals(0, h.prepareCalls); assertEquals(0, h.transfers)
            assertEquals(0, h.selects); assertEquals(0, h.normalizations)
        }
    }

    @Test fun escapedLateProgressCannotRewriteACompletedRawTupleOrRestartItsWriter() {
        val h = Harness()
        h.onSelect = {
            val original = h.read("task")!!
            assertThrows(SaveFailure::class.java) { h.transferProgress!!.onRestart() }
            assertThrows(SaveFailure::class.java) { h.transferProgress!!.onBytes(1) }
            assertThrows(SaveFailure::class.java) {
                h.transferProgress!!.onCheckpoint(DownloadCheckpoint("task", original.request.resource.sourceIdentity, 1, "\"etag\"", 4096))
            }
            assertEquals(original, h.read("task"))
        }
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(1, h.transfers); assertEquals(1, h.normalizations)
    }

    @Test fun rawWriteThenThrowIsReadBackExactlyWithoutSecondTransportOrWriter() {
        val h = Harness(); h.throwAfterRaw = true
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(1, h.transfers); assertEquals(1, h.normalizations)
        assertNotNull(h.execution!!.transferredHere)
    }

    @Test fun stopOnBothSidesOfActualTransferFactPreservesLocalResumeIncludingRawWriteThenThrow() {
        for (stopBeforeEvent in listOf(false, true)) for (lostRawReturn in listOf(false, true)) {
            val h = Harness(); h.throwAfterRaw = lostRawReturn
            if (stopBeforeEvent) h.afterRawWritten = { h.coordinator.signalBackgroundPauseAll() }
            else h.onTransferEvent = { h.coordinator.signalBackgroundPauseAll() }
            val waiting = h.run()
            assertEquals(TaskState.WAITING_USER, waiting.task.state)
            assertEquals(SaveWaitReason.BACKGROUND, waiting.waitReason)
            assertEquals(1, h.transferEvents); assertEquals(1, h.transfers)
            assertEquals(0, h.selects); assertEquals(0, h.normalizations)
            val oldView = h.execution!!
            val fact = oldView.recordedTransferFrom(h.admission)!!
            assertEquals(h.lastRawProposal, fact); assertNotSame(h.lastRawProposal, fact)
            assertEquals(SavePhase.EXECUTING, fact.phase)
            assertNotNull(fact.checkpoint); assertEquals(waiting.transfer, fact.transfer)
            assertThrows(SaveFailure::class.java) { oldView.requireLive(oldView.context) }
            h.afterRawWritten = {}; h.onTransferEvent = {}
            assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("local-resume"))!!.task.state)
            assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
            assertEquals(1, h.transfers); assertEquals(1, h.transferEvents); assertEquals(1, h.normalizations)
            assertEquals(fact, oldView.recordedTransferFrom(h.admission))
        }
    }

    @Test fun unknownRawCasNeverRecordsFactAndRealExitedViewCannotRegisterOneLater() {
        val h = Harness(); h.throwBeforeRaw = true
        val result = h.run()
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
        assertEquals(0, h.transferEvents); assertEquals(0, h.selects)
        val issued = h.issuedViews.single()
        assertNull(issued.recordedTransferFrom(h.admission))
        assertThrows(SaveFailure::class.java) { issued.recordTransferCompletion(h.lastRawProposal!!) }
        assertNull(issued.recordedTransferFrom(h.admission))
    }

    @Test fun transferFactIsSameIssuerReadonlyAndCannotBeOverwrittenOrRemintedAfterExit() {
        val h = Harness()
        h.onTransferEvent = {
            val execution = h.execution!!; val fact = execution.recordedTransferFrom(h.admission)!!
            execution.recordTransferCompletion(fact) // Exact same fact is idempotent while entered.
            assertThrows(SaveFailure::class.java) { execution.recordTransferCompletion(fact.copy(bytesTransferred = fact.bytesTransferred + 1)) }
            assertThrows(SaveFailure::class.java) {
                execution.recordedTransferFrom(SaveExecutionAdmission(maxArtifactBytes = 16_384))
            }
            assertEquals(fact, execution.recordedTransferFrom(h.admission))
        }
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        val execution = h.execution!!; val fact = execution.recordedTransferFrom(h.admission)!!
        assertThrows(SaveFailure::class.java) { execution.recordTransferCompletion(fact) }
        assertEquals(fact, execution.recordedTransferFrom(h.admission))
    }

    @Test fun retryReplacementRetiresOldFactRegistrationWithoutAffectingNewActualCompletion() {
        val h = Harness(); h.failFirstTransfer = true
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(2, h.issuedViews.size)
        val old = h.issuedViews.first(); val fresh = h.issuedViews.last()
        assertNull(old.recordedTransferFrom(h.admission))
        val fact = fresh.recordedTransferFrom(h.admission)!!
        assertEquals(1, fact.attempt); assertEquals(1, h.transferEvents)
        assertThrows(SaveFailure::class.java) { old.recordTransferCompletion(fact.copy(attempt = 0)) }
        assertNull(old.recordedTransferFrom(h.admission))
        assertEquals(fact, fresh.recordedTransferFrom(h.admission))
    }

    @Test fun actualIssuedViewRetirementRacingFirstFactCasCannotPublishALateFact() {
        val h = Harness(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val view = AtomicReference<AcceptedDirectAttempt>()
        h.coordinator.acceptedAttemptInstallationObserver = { after, issued ->
            if (after) {
                view.set(issued)
                issued.completionRegistrationObserver = {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        h.submit(); val result = AtomicReference<SaveRecord>()
        val worker = thread { result.set(h.coordinator.execute("task")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.coordinator.signalUserCancel("task")
            // Exercise the exact production retirement transition against the first-fact CAS.
            // This is a real Coordinator-issued view, never a synthetic authority or fake issuer.
            view.get().retire()
            assertNull(view.get().recordedTransferFrom(h.admission))
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, result.get().task.state)
        assertNull(view.get().recordedTransferFrom(h.admission))
        assertEquals(0, h.transferEvents); assertEquals(0, h.selects); assertEquals(0, h.normalizations)
    }

    @Test fun proofWriteThenThrowContinuesOnlyForFullExactProposal() {
        val exact = Harness(); exact.throwAfterProof = true
        assertEquals(TaskState.SUCCEEDED, exact.run().task.state)
        assertEquals(1, exact.proofCas); assertEquals(1, exact.normalizations)
        val changed = Harness(); changed.throwAfterProof = true; changed.alterProofRevision = true
        val result = changed.run()
        assertEquals(TaskState.WAITING_USER, result.task.state); assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
        assertEquals(0, changed.adoptions); assertEquals(0, changed.normalizedVerifications)
        assertEquals(1, changed.normalizations); assertEquals(1, changed.proofCloses)
        assertEquals(1, changed.transfers)
    }

    @Test fun localIncompleteTransferFailureCannotEnterAutomaticHttpRetry() {
        val h = Harness(); h.normalizeError = SaveError.INCOMPLETE_TRANSFER
        val result = h.run()
        assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.RESOURCE_CHANGED, result.error)
        assertEquals(0, result.attempt); assertEquals(1, h.transfers); assertEquals(1, h.normalizations)
        assertEquals(0, h.delays)
    }

    @Test fun publicationWriteThenThrowPreservesSuccessAndRetiresExactlyOneCommitOwner() {
        val h = Harness(); h.throwAfterPublication = true
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(1, h.publications); assertEquals(1, h.commitCloses); assertEquals(1, h.cleanupCalls)
        assertEquals(1, h.normalizedVerifications)
    }

    @Test fun successfulPublicationWithLostReturnAndFirstReadbackFailureStillCleansAndPublishesSnapshot() {
        val h = Harness(); h.throwAfterPublication = true; h.failFirstPublicationReadback = true
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("task")!!.state)
        assertEquals(1, h.publications); assertEquals(1, h.commitCloses); assertEquals(1, h.cleanupCalls)
        assertEquals(1, h.normalizedVerifications)
    }

    @Test fun uncertainCommitCloseKeepsSuccessButRetainsClaimAndDefersDestructiveCleanup() {
        val h = Harness(); h.commitCloseUncertain = true
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertEquals(1, h.commitCloses); assertEquals(0, h.cleanupCalls)
        assertTrue(h.admission.snapshot().busy)
        h.confirmCommitRelease() // Positive actual-release event from the fake storage owner.
        h.coordinator.expireWaitingUsers(h.operation("maintenance"))
        assertEquals(1, h.cleanupCalls)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
        assertFalse(h.admission.snapshot().busy)
        assertEquals(1, h.commitCloses); assertEquals(1, h.publications)
    }

    @Test fun rejectedPublicationStaysInvisibleRecoveryAndRetiresCommitOwnerWithoutRepublish() {
        val h = Harness(); h.rejectPublication = true
        val result = h.run()
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
        assertEquals(1, h.publications); assertEquals(1, h.commitCloses); assertEquals(0, h.cleanupCalls)
        assertEquals(1, h.normalizations); assertEquals(1, h.transfers)
    }

    @Test fun cancellationDuringBlockedNormalizationRevokesExactViewAndDoesNotPublishLateOutput() {
        val h = Harness(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.onNormalize = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.submit(); val result = AtomicReference<SaveRecord>()
        val worker = thread { result.set(h.coordinator.execute("task")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.coordinator.signalUserCancel("task")
            assertThrows(SaveFailure::class.java) { h.execution!!.requireLive(h.execution!!.context) }
            assertTrue(h.admission.snapshot().busy)
            assertEquals(TaskState.RUNNING, h.coordinator.snapshot("task")!!.state)
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, result.get().task.state)
        assertEquals(0, h.proofCas); assertEquals(0, h.normalizedVerifications); assertEquals(0, h.publications)
        assertEquals(1, h.cleanupCalls)
    }

    @Test fun originalCancellationTokenRevokesAnAlreadyIssuedViewDuringBlockedLocalIo() {
        val h = Harness(); val cancellation = CancellationSource()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.onNormalize = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.coordinator.submit(h.request(), h.operation().copy(cancelToken = cancellation))
        val result = AtomicReference<SaveRecord>()
        val worker = thread { result.set(h.coordinator.execute("task")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancellation.cancel()
            assertThrows(SaveFailure::class.java) { h.execution!!.requireLive(h.execution!!.context) }
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, result.get().task.state)
        assertEquals(0, h.proofCas); assertEquals(0, h.normalizedVerifications)
    }

    @Test fun cancelBeforeInitialAcceptanceNeverSignsExecutionOrStartsIo() {
        val h = Harness(); h.submit(); h.coordinator.signalUserCancel("task")
        assertEquals(TaskState.CANCELLED, h.coordinator.execute("task")!!.task.state)
        assertNull(h.execution); assertEquals(0, h.transfers); assertEquals(0, h.selects)
    }

    @Test fun cancelDuringRetryNeverReusesOldAttemptViewOrInstallsLiveReplacement() {
        val h = Harness(); h.failFirstTransfer = true
        h.onRetry = { h.coordinator.signalUserCancel("task") }
        val result = h.run()
        assertEquals(TaskState.CANCELLED, result.task.state)
        assertEquals(1, h.transfers); assertEquals(1, h.delays); assertEquals(0, h.selects)
    }

    @Test fun completeOrdinaryRawExplicitResumeIsLocalOnlyAndPreservesPriorDurablePhase() {
        val h = Harness(mov = false); h.seedCompleteRaw(SavePhase.EXECUTING)
        val operation = h.operation("resume")
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", operation)!!.task.state)
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(0, h.transfers); assertEquals(0, h.policyCalls)
        assertEquals(SavePhase.EXECUTING, h.execution!!.resumedFrom!!.phase)
        assertNull(h.execution!!.transferredHere)
        assertEquals(1, h.legacyVerifications)
    }

    @Test fun outputReadyWithoutFullProofAndWritingRecoveryNeverRemuxOrRedownload() {
        for (action in listOf(DirectRecoveryAction.REVIEW)) {
            val h = Harness(); h.seedCompleteRaw(SavePhase.NORMALIZING)
            h.presence = DirectWorkspacePresence.PRESENT; h.recovery = action
            h.coordinator.resume("task", h.operation("resume"))
            val result = h.coordinator.execute("task")!!
            assertEquals(TaskState.WAITING_USER, result.task.state)
            assertEquals(0, h.transfers); assertEquals(0, h.selects); assertEquals(0, h.normalizations)
        }
    }

    @Test fun resumeThenCrashRecoveryPreservesOriginalControlledRawPhaseBeforeAnyLocalWork() {
        for (phase in listOf(null, SavePhase.EXECUTING, SavePhase.NORMALIZING, SavePhase.VERIFYING, SavePhase.COMMITTING)) {
            val first = Harness(); first.seedCompleteRaw(phase)
            val running = first.coordinator.resume("task", first.operation("resume-before-crash"))!!
            assertEquals(TaskState.RUNNING, running.task.state); assertEquals(phase, running.phase)
            assertEquals(0, first.transfers); assertEquals(0, first.selects)
            // The first execution never enters. Reconstruct from only the durable record in a
            // fresh Coordinator, then run the actual passive-recovery and explicit-Resume paths.
            val restarted = Harness()
            restarted.backing.records["task"] = first.read("task")!!.copy()
            val waiting = restarted.coordinator.recover(restarted.operation("startup-recovery")).single()
            assertEquals(TaskState.WAITING_USER, waiting.task.state); assertEquals(phase, waiting.phase)
            val resumed = restarted.coordinator.resume("task", restarted.operation("explicit-after-crash"))!!
            assertEquals(TaskState.RUNNING, resumed.task.state); assertEquals(phase, resumed.phase)
            assertEquals(running.transfer, resumed.transfer); assertEquals(running.artifact, resumed.artifact)
            if (phase == SavePhase.VERIFYING || phase == SavePhase.COMMITTING) {
                // A storage adapter can still reject its real pre-probe provenance guard because
                // the durable phase was not laundered to PREPARING by either Resume control CAS.
                restarted.rejectFreshPhases = setOf(requireNotNull(phase))
                assertEquals(TaskState.WAITING_USER, restarted.coordinator.execute("task")!!.task.state)
                assertEquals(0, restarted.transfers); assertEquals(0, restarted.selects)
                assertEquals(0, restarted.normalizations); assertEquals(0, restarted.normalizedVerifications)
            }
        }
    }

    @Test fun partialControlledAndUnconfiguredLegacyResumeKeepOriginalPreparingPolicy() {
        val partial = Harness(); partial.seedCompleteRaw(SavePhase.EXECUTING)
        partial.backing.records["task"] = partial.read("task")!!.copy(transfer = null)
        assertEquals(SavePhase.PREPARING, partial.coordinator.resume("task", partial.operation("partial-resume"),
            resource = partial.request().resource)!!.phase)
        val complete = Harness(); complete.seedCompleteRaw(SavePhase.VERIFYING)
        val legacy = SaveHarness()
        legacy.journal.records["task"] = complete.read("task")!!
        assertEquals(SavePhase.PREPARING, legacy.coordinator.resume("task", legacy.operation("legacy-resume"))!!.phase)
    }

    @Test fun adoptedAndCommitRecoveryUseDurableSelectedOutputWithoutNetworkOrAnotherNormalization() {
        for (action in listOf(DirectRecoveryAction.ADOPT, DirectRecoveryAction.VERIFY, DirectRecoveryAction.COMMIT)) {
            val h = Harness(); val success = h.run()
            val phase = when (action) {
                DirectRecoveryAction.ADOPT -> SavePhase.ADOPTING
                DirectRecoveryAction.VERIFY -> SavePhase.VERIFYING
                else -> SavePhase.COMMITTING
            }
            val waiting = success.copy(task = success.task.copy(revision = success.task.revision + 1,
                state = TaskState.WAITING_USER, waitingUser = UserWait(9000, ResumeAction.REVIEW_RECOVERY)),
                waitReason = SaveWaitReason.RECOVERY, phase = phase, receipt = null,
                verification = if (action == DirectRecoveryAction.COMMIT) success.verification else null)
            h.backing.records["task"] = waiting; h.recovery = action
            val verifies = h.normalizedVerifications; val adoptions = h.adoptions
            h.coordinator.resume("task", h.operation("explicit-recovery"))
            val recovered = h.coordinator.execute("task")!!
            assertEquals(TaskState.SUCCEEDED, recovered.task.state)
            assertEquals(1, h.transfers); assertEquals(1, h.normalizations); assertEquals(1, h.selects)
            assertEquals(verifies + if (action == DirectRecoveryAction.COMMIT) 0 else 1, h.normalizedVerifications)
            assertEquals(adoptions + if (action == DirectRecoveryAction.ADOPT) 1 else 0, h.adoptions)
            assertEquals(success.transfer, recovered.transfer); assertEquals(success.directNormalization, recovered.directNormalization)
            assertEquals(phase, h.execution!!.resumedFrom!!.phase)
        }
    }

    @Test fun actualCoordinatorAcceptsCurrentAudioWithEitherPageExpectationAndRequiresReadableAudio() {
        for (expected in listOf(false, true)) {
            val h = Harness(audio = true, expectedAudio = expected)
            val result = h.run()
            assertEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(2, result.directNormalization!!.policyRevision)
            assertTrue(result.verification!!.audioTrackReadable)
            assertEquals(1, h.normalizedVerifications); assertEquals(1, h.publications)
        }
        val absent = Harness(expectedAudio = true)
        assertEquals(SaveError.UNSUPPORTED_MEDIA, absent.run().error)
        assertEquals(0, absent.proofCas); assertEquals(0, absent.normalizedVerifications)
        val unreadable = Harness(audio = true).also { it.audioReadable = false }
        assertNotEquals(TaskState.SUCCEEDED, unreadable.run().task.state)
        assertEquals(0, unreadable.evidenceCas); assertEquals(0, unreadable.publications)
        val old = Harness(audio = true, policyRevision = 1)
        assertEquals(SaveError.RECOVERY_REQUIRED, old.run().error)
        assertEquals(0, old.proofCas); assertEquals(0, old.normalizedVerifications)
        assertEquals(1, old.proofCloses); assertEquals(0, old.publications)
    }

    @Test fun coordinatorChecksOldAudioPolicyBeforeForgedAdoptVerifyOrCommitRecoveryAction() {
        for (action in listOf(DirectRecoveryAction.ADOPT, DirectRecoveryAction.VERIFY, DirectRecoveryAction.COMMIT)) {
            val h = Harness(audio = true)
            val success = h.run()
            // Synthetic historical record with a self-consistent old digest, never an upgrade action.
            val old = success.directNormalization!!.copy(policyRevision = 1)
            val oldEvidence = success.verification!!.copy(directNormalizationProof =
                success.verification!!.directNormalizationProof!!.copy(normalizationProofSha256 = old.digest()))
            val waiting = success.copy(task = success.task.copy(revision = success.task.revision + 1,
                state = TaskState.WAITING_USER, waitingUser = UserWait(9000, ResumeAction.REVIEW_RECOVERY)),
                phase = if (action == DirectRecoveryAction.COMMIT) SavePhase.COMMITTING else SavePhase.VERIFYING,
                waitReason = SaveWaitReason.RECOVERY, receipt = null, directNormalization = old,
                verification = if (action == DirectRecoveryAction.COMMIT) oldEvidence else null)
            h.backing.records["task"] = waiting; h.recovery = action
            val counts = listOf(h.transfers, h.selects, h.normalizations, h.normalizedVerifications, h.adoptions, h.publications)
            h.coordinator.resume("task", h.operation("old-audio-recovery"))
            val result = h.coordinator.execute("task")!!
            assertEquals(TaskState.WAITING_USER, result.task.state)
            assertEquals(SaveError.RECOVERY_REQUIRED, result.error)
            assertEquals(old, result.directNormalization)
            assertEquals(waiting.verification, result.verification)
            assertEquals(counts, listOf(h.transfers, h.selects, h.normalizations, h.normalizedVerifications, h.adoptions, h.publications))
        }
    }

    @Test fun newAudioRecoveryRequiresFreshVerificationUnlessExactPriorEvidenceExists() {
        for (action in listOf(DirectRecoveryAction.VERIFY, DirectRecoveryAction.COMMIT)) {
            val h = Harness(audio = true); val success = h.run()
            val waiting = success.copy(task = success.task.copy(revision = success.task.revision + 1,
                state = TaskState.WAITING_USER, waitingUser = UserWait(9000, ResumeAction.REVIEW_RECOVERY)),
                phase = SavePhase.VERIFYING, waitReason = SaveWaitReason.RECOVERY, receipt = null,
                verification = if (action == DirectRecoveryAction.COMMIT) success.verification else null)
            h.backing.records["task"] = waiting; h.recovery = action
            h.coordinator.resume("task", h.operation("new-audio-recovery"))
            assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
            assertEquals(if (action == DirectRecoveryAction.VERIFY) 2 else 1, h.normalizedVerifications)
            assertEquals(1, h.transfers); assertEquals(1, h.normalizations)
        }
        val h = Harness(audio = true); val success = h.run()
        h.backing.records["task"] = success.copy(task = success.task.copy(revision = success.task.revision + 1,
            state = TaskState.WAITING_USER, waitingUser = UserWait(9000, ResumeAction.REVIEW_RECOVERY)),
            phase = SavePhase.COMMITTING, waitReason = SaveWaitReason.RECOVERY, receipt = null, verification = null)
        h.recovery = DirectRecoveryAction.COMMIT
        h.coordinator.resume("task", h.operation("missing-prior-audio-proof"))
        assertEquals(SaveError.RECOVERY_REQUIRED, h.coordinator.execute("task")!!.error)
        assertEquals(1, h.publications)
    }

    @Test fun foreignAdmissionIsRejectedByProductionIssuedViewWithoutASecondClaim() {
        val h = Harness()
        h.onSelect = {
            val foreign = SaveExecutionAdmission(maxArtifactBytes = 10_000)
            assertThrows(SaveFailure::class.java) { h.execution!!.assertAdmission(foreign) }
            h.execution!!.assertAdmission(h.admission)
        }
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
    }

    @Test fun cancelOnBothSidesOfFirstAndRetryCellInstallationCannotMissNullOrOldCell() {
        for (retry in listOf(false, true)) for (after in listOf(false, true)) {
            val h = Harness(); h.failFirstTransfer = retry
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var installation = 0
            h.coordinator.acceptedAttemptInstallationObserver = { afterInstall, _ ->
                if (!afterInstall) installation++
                if (installation == (if (retry) 2 else 1) && afterInstall == after) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
            h.submit(); val result = AtomicReference<SaveRecord>()
            val worker = thread { result.set(h.coordinator.execute("task")) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // The callback deliberately holds Coordinator's monitor. Stop is memory-only.
                h.coordinator.signalUserCancel("task")
                assertEquals(1, h.admission.snapshot().directCount)
            } finally { release.countDown() }
            worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(TaskState.CANCELLED, result.get().task.state)
            assertEquals(if (retry) 1 else 0, h.transfers)
            assertEquals(0, h.selects); assertEquals(0, h.normalizations); assertEquals(0, h.normalizedVerifications)
        }
    }

    @Test fun blockedTerminalCleanupHoldsLocalLifetimeButNotCoordinatorMonitorOrMemoryCancel() {
        val h = Harness(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.onCleanup = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.submit(); val worker = thread { h.coordinator.execute("task") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.coordinator.signalUserCancel("task")
            assertEquals(TaskState.SUCCEEDED, h.coordinator.record("task")!!.task.state)
            assertTrue(h.admission.snapshot().busy)
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.record("task")!!.task.state)
        assertEquals(1, h.cleanupCalls)
    }

    @Test fun privateConvertedSyntheticPortsTraverseRealCoordinatorAndDistinctTypedHandoffs() {
        val h = Harness(converted = true, conversionEnabled = true, enabled = false, mime = "application/octet-stream")
        val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertNull(result.directNormalization); assertNotNull(result.directConversion); assertNull(result.checkpoint)
        assertEquals(4096L, result.transfer!!.bytesOnDisk); assertEquals(2048L, result.receipt!!.sizeBytes)
        assertEquals(1, h.transfers); assertEquals(1, h.conversions); assertEquals(1, h.conversionCas)
        assertEquals(1, h.convertedVerifications); assertEquals(1, h.evidenceCas); assertEquals(1, h.publications)
        assertEquals(0, h.normalizations); assertEquals(0, h.proofCas); assertEquals(0, h.normalizedVerifications)
        assertEquals(0, h.legacyVerifications); assertEquals(0, h.store.commitCalls)
        val order = listOf("selector", "convert", "conversion-proof", "conversion-close", "selector-close", "adopt",
            "converted-verify", "evidence-cas", "converted-commit", "publication", "cleanup")
        order.forEach { event -> assertTrue("Missing $event", h.events.indexOf(event) >= 0) }
        order.zipWithNext().forEach { (a, b) -> assertTrue("$a before $b", h.events.indexOf(a) < h.events.indexOf(b)) }
        assertEquals(1, h.proofCloses); assertEquals(1, h.verificationCloses); assertEquals(1, h.commitCloses)
    }

    @Test fun privateSwitchDoesNotAdmitWebmMimeAndClosedCandidateNeverIssuesWriter() {
        val h = Harness(converted = true, mime = "application/octet-stream")
        val result = h.run()
        assertEquals(SaveError.UNSUPPORTED_MEDIA, result.error)
        assertEquals(1, h.selects); assertEquals(0, h.conversions); assertEquals(0, h.adoptions)
        assertEquals(0, h.convertedVerifications); assertEquals(0, h.publications)
        val pipeline = ControlledDirectPipeline(h.gate, h, conversionExecutionEnabled = true)
        assertFalse(pipeline.acceptsRawMime("video/webm")); assertFalse(pipeline.acceptsRawMime("audio/webm"))
        assertFalse(pipeline.movAdmissionEnabled)
    }

    @Test fun closedPrivateGateRejectsConvertedRecoveryBeforeInspectionOrIo() {
        val h = Harness(converted = true); h.seedConverted(SavePhase.NORMALIZING, false)
        h.recovery = DirectRecoveryAction.ADOPT
        h.coordinator.resume("task", h.operation("resume"))
        assertEquals(SaveError.RECOVERY_REQUIRED, h.coordinator.execute("task")!!.error)
        assertEquals(0, h.recoveryInspections); assertEquals(0, h.transfers); assertEquals(0, h.conversions)
        assertEquals(0, h.adoptions); assertEquals(0, h.publications)
    }

    @Test fun convertedProofWriteThenThrowRequiresExactReadbackWithoutSecondProducer() {
        for (alter in listOf(false, true)) {
            val h = Harness(converted = true, conversionEnabled = true, mime = "application/octet-stream")
            h.throwAfterProof = true; h.alterProofRevision = alter
            val result = h.run()
            assertEquals(if (alter) TaskState.WAITING_USER else TaskState.SUCCEEDED, result.task.state)
            assertEquals(1, h.transfers); assertEquals(1, h.conversions); assertEquals(1, h.conversionCas)
            assertEquals(if (alter) 0 else 1, h.adoptions); assertEquals(1, h.proofCloses)
        }
    }

    @Test fun convertedRecoveryNeverRepeatsProducerTransferOrSavedIndependentVerification() {
        for ((phase, action, verified) in listOf(Triple(SavePhase.NORMALIZING, DirectRecoveryAction.ADOPT, false),
            Triple(SavePhase.VERIFYING, DirectRecoveryAction.VERIFY, false), Triple(SavePhase.COMMITTING, DirectRecoveryAction.COMMIT, true))) {
            val h = Harness(converted = true, conversionEnabled = true)
            h.seedConverted(phase, verified); h.recovery = action
            val proof = h.read("task")!!.directConversion
            h.coordinator.resume("task", h.operation("resume"))
            val result = h.coordinator.execute("task")!!
            assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(proof, result.directConversion)
            assertEquals(0, h.transfers); assertEquals(0, h.conversions); assertEquals(0, h.selects)
            assertEquals(if (verified) 0 else 1, h.convertedVerifications)
            assertEquals(1, h.publications)
        }
    }

    @Test fun convertedMissingCommitEvidenceAndExpectedAudioNeverPublishOrUseLegacy() {
        val missing = Harness(converted = true, conversionEnabled = true)
        missing.seedConverted(SavePhase.COMMITTING, false); missing.recovery = DirectRecoveryAction.COMMIT
        missing.coordinator.resume("task", missing.operation("resume"))
        assertEquals(SaveError.RECOVERY_REQUIRED, missing.coordinator.execute("task")!!.error)
        assertEquals(0, missing.publications); assertEquals(0, missing.convertedVerifications)
        val audio = Harness(converted = true, conversionEnabled = true, expectedAudio = true, mime = "application/octet-stream")
        assertEquals(SaveError.UNSUPPORTED_MEDIA, audio.run().error)
        assertEquals(0, audio.conversionCas); assertEquals(0, audio.publications); assertEquals(0, audio.legacyVerifications)
    }

    @Test fun convertedCancellationAndLocalFailureCannotRestartHttpOrPublishLateProof() {
        val cancelled = Harness(converted = true, conversionEnabled = true, mime = "application/octet-stream")
        cancelled.onNormalize = { cancelled.coordinator.signalUserCancel("task") }
        assertEquals(TaskState.CANCELLED, cancelled.run().task.state)
        assertEquals(0, cancelled.conversionCas); assertEquals(0, cancelled.publications)
        val failed = Harness(converted = true, conversionEnabled = true, mime = "application/octet-stream")
        failed.normalizeError = SaveError.INCOMPLETE_TRANSFER
        assertEquals(SaveError.RESOURCE_CHANGED, failed.run().error)
        assertEquals(1, failed.transfers); assertEquals(1, failed.conversions); assertEquals(0, failed.delays)
    }

    @Test fun currentConvertedAudioUsesFullTypedCoordinatorChainAndKeepsShortSource() {
        for (expected in listOf(false, true)) {
            val h = Harness(converted = true, conversionEnabled = true, enabled = false, audio = true,
                expectedAudio = expected, mime = "application/octet-stream")
            val result = h.run(); val proof = result.directConversion!!
            assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(2, proof.policyRevision)
            assertEquals(28_800L, proof.expectation.audio!!.sourcePcmFrameCount)
            assertEquals(48_000L, proof.expectation.audio!!.encoderPcmFrameCount)
            assertEquals(600_000L, proof.expectation.audio!!.sourceEndUs)
            assertTrue(result.verification!!.directConversionProof!!.matches(proof))
            assertEquals(1, h.transfers); assertEquals(1, h.conversions); assertEquals(1, h.conversionCas)
            assertEquals(1, h.convertedVerifications); assertEquals(1, h.evidenceCas); assertEquals(1, h.publications)
            assertEquals(0, h.legacyVerifications); assertEquals(0, h.store.commitCalls)
        }
    }
    @Test fun historicalConvertedAudioCannotEnterNewOrRecoveredWorkButFreshCancelUsesTypedCleanup() {
        val producer = Harness(converted = true, conversionEnabled = true, audio = true, policyRevision = 1,
            mime = "application/octet-stream")
        assertEquals(SaveError.RECOVERY_REQUIRED, producer.run().error)
        assertEquals(0, producer.conversionCas); assertEquals(0, producer.adoptions); assertEquals(0, producer.publications)
        for ((phase, action, verified) in listOf(Triple(SavePhase.NORMALIZING, DirectRecoveryAction.ADOPT, false),
            Triple(SavePhase.VERIFYING, DirectRecoveryAction.VERIFY, false), Triple(SavePhase.COMMITTING, DirectRecoveryAction.COMMIT, true))) {
            val h = Harness(converted = true, conversionEnabled = true, audio = true, policyRevision = 1)
            h.seedConverted(phase, verified); h.recovery = action; val original = h.read("task")!!.directConversion
            h.coordinator.resume("task", h.operation("resume"))
            assertEquals(SaveError.RECOVERY_REQUIRED, h.coordinator.execute("task")!!.error)
            assertEquals(original, h.read("task")!!.directConversion); assertEquals(0, h.adoptions)
            assertEquals(0, h.convertedVerifications); assertEquals(0, h.publications); assertEquals(0, h.transfers)
        }
        val cancelled = Harness(converted = true, audio = true, policyRevision = 1)
        cancelled.seedConverted(SavePhase.VERIFYING, false)
        val original = cancelled.read("task")!!.directConversion
        assertEquals(TaskState.CANCELLED, cancelled.coordinator.cancel("task")!!.state)
        assertEquals(original, cancelled.read("task")!!.directConversion)
        assertEquals(1, cancelled.cleanupCalls); assertEquals(0, cancelled.recoveryInspections)
        assertEquals(0, cancelled.adoptions); assertEquals(0, cancelled.convertedVerifications); assertEquals(0, cancelled.publications)
    }

    private class Harness(val mov: Boolean = true, enabled: Boolean = true, privateTask: Boolean = false,
        val mime: String = if (mov) "video/quicktime" else "video/mp4",
        val audio: Boolean = false, val policyRevision: Int = if (audio) 2 else 1,
        val expectedAudio: Boolean = false, val converted: Boolean = false,
        conversionEnabled: Boolean = false) : ControlledDirectStorage, ControlledDirectJournal {
        val backing = MemorySaveJournal()
        val privateBinding = if (privateTask) PrivateNetworkSourceSeed.create().bindTask("task") else null
        val clock = FakeSaveClock()
        val limits = SaveLimits(16_384, 65_536)
        val events = mutableListOf<String>()
        var presence = DirectWorkspacePresence.ABSENT
        var recovery = DirectRecoveryAction.REVIEW
        var conversions = 0; var conversionCas = 0; var convertedVerifications = 0; var recoveryInspections = 0
        var transfers = 0; var normalizations = 0; var normalizedVerifications = 0; var legacyVerifications = 0
        var selects = 0; var prepareCalls = 0; var policyCalls = 0; var adoptions = 0
        var proofCas = 0; var evidenceCas = 0; var publications = 0; var cleanupCalls = 0; var delays = 0
        var commitCloses = 0; var proofCloses = 0; var verificationCloses = 0
        var networkChargeAtProof = 0L
        var execution: AcceptedDirectAttempt? = null
        var transferProgress: TransferProgress? = null
        var throwAfterRaw = false; var throwBeforeRaw = false; var throwAfterProof = false; var alterProofRevision = false
        var transferEvents = 0; var lastRawProposal: SaveRecord? = null
        val issuedViews = mutableListOf<AcceptedDirectAttempt>()
        var historicalRaw: SaveRecord? = null
        var rejectFreshPhases: Set<SavePhase> = emptySet()
        var afterRawWritten: () -> Unit = {}; var onTransferEvent: () -> Unit = {}
        var throwAfterPublication = false; var rejectPublication = false; var failFirstTransfer = false
        var failFirstPublicationReadback = false; private var failNextRead = false
        var commitCloseUncertain = false; var draining = false
        var normalizeError: SaveError? = null
        var audioReadable = true
        var onNormalize: () -> Unit = {}; var onSelect: () -> Unit = {}; var onRetry: () -> Unit = {}
        var onCleanup: () -> Unit = {}
        private var conversionHandle: DirectConversionProofCompletion? = null
        private var proofHandle: DirectProofCompletion? = null
        private var evidenceHandle: DirectVerificationCompletion? = null
        private var commitHandle: NormalizedCommitHandle? = null
        private var publicationHandle: NormalizedPublicationHandle? = null
        val gate = DirectLocalProcessingGate(clock)
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes)
        val store = object : FakeMediaStore() {
            override fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact {
                prepareCalls++; return super.prepare(taskId, commitId, context)
            }
        }
        val verifier = object : ArtifactVerifier {
            override fun verify(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext) = goodEvidence(artifact, transfer)
            override fun verify(artifact: StagingArtifact, output: ArtifactOutputDescriptor, context: OperationContext): VerificationEvidence {
                if (output is ArtifactOutputDescriptor.ConvertedDirect) return convertedEvidence(artifact, output.conversionProof)
                val p = (output as ArtifactOutputDescriptor.NormalizedDirect).normalizationProof
                val c = p.conservation
                return VerificationEvidence(artifact.artifactId, p.outputSizeBytes, p.outputSha256, true, 1, if (c.audio == null) 0 else 1,
                    c.endpoint.minimumUs / 1000, 16, 16, false, DecodePosition.entries.toSet(), c.audio != null && audioReadable,
                    directNormalizationProof = DirectNormalizationVerificationProof(p.normalizationId, p.taskId,
                        p.sourceIdentity, p.artifactId, p.commitId, p.digest(), c,
                        DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true)))
            }
        }
        val coordinator = SaveCoordinator(this, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++; transferProgress = progress
                if (failFirstTransfer && transfers == 1) throw SaveFailure(SaveError.NETWORK_TIMEOUT)
                progress.onBytes(4096)
                progress.onCheckpoint(DownloadCheckpoint(artifact.artifactId, resource.sourceIdentity, 4096, "\"etag\"", 4096))
                events += "transfer-released"
                return TransferResult(artifact.artifactId, 4096, mime, 4096)
            }
        }, verifier, store, clock, limits, SaveRetryDelay { _, _ -> delays++; onRetry() },
            networkGate = SaveNetworkGate { policyCalls++ }, admission = admission,
            controlledDirect = ControlledDirectPipeline(gate, this, enabled, conversionEnabled))
        init {
            coordinator.acceptedAttemptInstallationObserver = { after, view -> if (after) issuedViews += view }
        }
        fun confirmCommitRelease() { commitHandle = null; publicationHandle = null; draining = false }
        fun request(): SaveRequest {
            val url = SourceUrl("https://example.invalid/raw")
            return SaveRequest("task", "key", SelectedMediaResource("candidate", "variant", url,
                sourceIdentity = privateBinding?.sourceIdentity?.encoded ?: url.identity(), privateSource = privateBinding, expectedAudio = expectedAudio))
        }
        fun operation(id: String = "operation") = OperationContext(id, 10_000, CancellationSource(), 2,
            resourceBudget = ResourceBudget(maxBytes = limits.maxTotalTransferBytes))
        fun submit() { assertTrue(coordinator.submit(request(), operation()) is SaveSubmission.Accepted) }
        fun run(): SaveRecord { submit(); return coordinator.execute("task")!! }
        fun seedCompleteRaw(phase: SavePhase?) {
            val request = request().asDurableReference()
            backing.insert(SaveRecord(request, TaskSnapshot("task", 7, TaskState.WAITING_USER, TaskMethod.DIRECT,
                waitingUser = UserWait(9000, ResumeAction.REVIEW_RECOVERY)), "task", "previous", 10_000, 2,
                phase = phase, artifact = StagingArtifact("task", "task"),
                transfer = TransferResult("task", 4096, mime, 4096), bytesTransferred = 4096,
                waitReason = SaveWaitReason.RECOVERY), 100)
        }
        override fun findByIdempotencyKey(key: String) = backing.findByIdempotencyKey(key)
        override fun read(taskId: String): SaveRecord? {
            if (failNextRead) { failNextRead = false; throw IllegalStateException() }
            return backing.read(taskId)
        }
        override fun insert(record: SaveRecord, maxEntries: Int) = backing.insert(record, maxEntries)
        override fun listUnfinished(limit: Int) = backing.listUnfinished(limit)
        override fun listRecent(limit: Int) = backing.listRecent(limit)
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            val prior = read(next.task.taskId)!!
            check(next.directNormalization == prior.directNormalization && next.directConversion == prior.directConversion)
            if (next.hasDirectOutputProof() && next.task.state == TaskState.SUCCEEDED) error("untyped publication")
            val completingRaw = prior.transfer == null && next.transfer != null
            if (completingRaw) {
                lastRawProposal = next
                if (throwBeforeRaw) throw IllegalStateException()
            }
            val saved = backing.compareAndSet(expectedRevision, next)
            if (saved && completingRaw) {
                afterRawWritten()
                if (throwAfterRaw) throw IllegalStateException()
            }
            return saved
        }
        override fun compareAndSetProof(expectedRevision: Long, next: SaveRecord, completion: DirectProofCompletion): Boolean {
            check(completion === proofHandle); check(next.directNormalization == completion.proof); check(next.checkpoint == null)
            proofCas++; networkChargeAtProof = next.bytesTransferred
            val saved = backing.compareAndSet(expectedRevision, next)
            if (alterProofRevision) backing.records["task"] = next.copy(task = next.task.copy(revision = next.task.revision + 1))
            if (throwAfterProof) throw IllegalStateException()
            return saved
        }
        override fun compareAndSetConversionProof(expectedRevision: Long, next: SaveRecord, completion: DirectConversionProofCompletion): Boolean {
            check(completion === conversionHandle); check(next.directConversion == completion.proof)
            check(next.directNormalization == null && next.checkpoint == null)
            conversionCas++; networkChargeAtProof = next.bytesTransferred; events += "conversion-proof"
            val saved = backing.compareAndSet(expectedRevision, next)
            if (alterProofRevision) backing.records["task"] = next.copy(task = next.task.copy(revision = next.task.revision + 1))
            if (throwAfterProof) throw IllegalStateException()
            return saved
        }
        override fun compareAndSetVerification(expectedRevision: Long, next: SaveRecord, completion: DirectVerificationCompletion): Boolean {
            check(completion === evidenceHandle); check(next.verification == completion.evidence)
            evidenceCas++; events += "evidence-cas"; return backing.compareAndSet(expectedRevision, next)
        }
        override fun compareAndSetNormalized(expectedRevision: Long, next: SaveRecord, publication: NormalizedPublicationHandle): Boolean {
            check(publication === publicationHandle && commitHandle != null); publications++; events += "publication"
            if (rejectPublication) return false
            val saved = backing.compareAndSet(expectedRevision, next)
            if (throwAfterPublication) {
                if (failFirstPublicationReadback) failNextRead = true
                throw IllegalStateException()
            }
            return saved
        }
        override fun onTransferReleased(execution: AcceptedDirectAttempt) {
            this.execution = execution
            val fact = execution.recordedTransferFrom(admission) ?: error("MISSING_ACTUAL_TRANSFER_FACT")
            if (historicalRaw != null) check(historicalRaw == fact)
            historicalRaw = fact; transferEvents++; onTransferEvent()
        }
        override fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String) = presence
        override fun requiresProcessRecovery() = draining
        private fun check(execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext) {
            execution.assertAdmission(admission); execution.requireLive(context); gate.assertIssuedHere(permit, context)
        }
        override fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
            context: OperationContext): DirectRawSelection {
            check(execution, permit, context)
            if (record.phase?.let { it in rejectFreshPhases } == true) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            if (execution.transferredHere == null && historicalRaw != null) {
                check(historicalRaw!!.artifact == record.artifact && historicalRaw!!.transfer == record.transfer &&
                    historicalRaw!!.commitId == record.commitId && historicalRaw!!.request == record.request)
            }
            this.execution = execution; selects++; events += "selector"; onSelect()
            return object : DirectRawSelection {
                override val kind = if (converted) DirectRawKind.WEBM_CANDIDATE else if (mov) DirectRawKind.STRICT_MOV else DirectRawKind.ALREADY_MP4
                override fun close() { events += "selector-close" }
            }
        }
        override fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
            permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion {
            check(execution, permit, context); normalizations++; presence = DirectWorkspacePresence.PRESENT; onNormalize()
            check(execution, permit, context); normalizeError?.let { throw SaveFailure(it) }
            val last = DirectSampleIdentity(29, 966_667, 4, "a".repeat(64), 0)
            val video = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, "1".repeat(64), 30, 120,
                "2".repeat(64), "3".repeat(64), "4".repeat(64), 0, last, DirectTrackEndpoint(1_000_000, 1_000_000))
            val audioClock = if (audio) DirectMovClock.audio(48_000, 47) else null
            val sound = audioClock?.let { DirectTrackConservation(DirectSampleKind.AAC_LC, "5".repeat(64), 47, 188,
                "6".repeat(64), "7".repeat(64), "8".repeat(64), 0,
                DirectSampleIdentity(46, it.presentationTimeUs(46), 4, "b".repeat(64), 1), it.endpoint) }
            val conservation = DirectRemuxConservation(16, 16, 30, video, sound,
                if (audio) 48_000 else null, if (audio) 2 else null,
                DirectSampleIdentity(0, 0, 4, "c".repeat(64), 1))
            val p = DirectNormalizationProof("00000000-0000-4000-8000-000000000001", "task", record.request.networkSourceMetadata!!.sourceIdentity,
                "task", "task", 4096, "d".repeat(64), sourceTransferFingerprint(record.transfer!!), 2048, "e".repeat(64), conservation, policyRevision = policyRevision)
            return object : DirectProofCompletion {
                override val proof = p
                override fun close() { proofCloses++; proofHandle = null }
            }.also { proofHandle = it }
        }
        // Explicit synthetic producer port; this test does not execute an Android codec.
        override fun convert(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
            permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectConversionProofCompletion {
            check(execution, permit, context); conversions++; presence = DirectWorkspacePresence.PRESENT
            events += "convert"; onNormalize(); check(execution, permit, context)
            normalizeError?.let { throw SaveFailure(it) }
            return object : DirectConversionProofCompletion {
                override val proof = convertedProof(record)
                override fun close() { proofCloses++; conversionHandle = null; events += "conversion-close" }
            }.also { conversionHandle = it }
        }
        fun convertedProof(record: SaveRecord): DirectConversionProof {
            val sound = if (audio) DirectConversionAudioExpectation(0, if (policyRevision == 2) 600_000 else 1_000_000,
                if (policyRevision == 2) 28_800 else 48_000, 48_000, 2, 0, 0, 0) else null
            val expected = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 30, 1_000_000, 30, 0, 966_666, 0), sound)
            val fact = if (audio) true else null
            return DirectConversionProof("00000000-0000-4000-8000-000000000002", "task", record.request.networkSourceMetadata!!.sourceIdentity,
                "task", "task", 4096, "d".repeat(64), sourceTransferFingerprint(record.transfer!!), 2048, "e".repeat(64),
                expected, DirectConversionEncodingSettings.forExpectation(expected),
                DirectConversionCompletionFacts(true, true, true, true, true, true, fact, fact, fact, fact, fact, fact),
                policyRevision = policyRevision)
        }
        fun convertedEvidence(artifact: StagingArtifact, p: DirectConversionProof): VerificationEvidence =
            VerificationEvidence(artifact.artifactId, p.outputSizeBytes, p.outputSha256, true, 1,
                if (p.expectation.audio == null) 0 else 1, 1000, 16, 16, false,
                DecodePosition.entries.toSet(), p.expectation.audio != null, directConversionProof = DirectConversionVerificationProof(
                    p.normalizationId, p.taskId, p.sourceIdentity, p.artifactId, p.commitId, p.digest(), p.outputSizeBytes, p.outputSha256,
                    30, 0, 966_666, 1_000_000, 0,
                    p.expectation.audio?.let { DirectConversionAudioVerification(0, it.encoderDurationUs, it.encoderPcmFrameCount,
                        it.sampleRate, it.channelCount, true, true) }, true, true, true, policyRevision = p.policyRevision))
        fun seedConverted(phase: SavePhase, verified: Boolean) {
            seedCompleteRaw(phase)
            val prior = read("task")!!; val p = convertedProof(prior)
            backing.records["task"] = prior.copy(directConversion = p,
                verification = if (verified) convertedEvidence(prior.artifact!!, p) else null)
            presence = DirectWorkspacePresence.PRESENT
        }
        override fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
            context: OperationContext): DirectRecoveryAction { check(execution, permit, context); this.execution = execution; recoveryInspections++; return recovery }
        override fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
            context: OperationContext) { check(execution, permit, context); adoptions++; events += "adopt" }
        override fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred, execution: AcceptedDirectAttempt,
            permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence {
            check(execution, permit, context); legacyVerifications++; events += "legacy"
            return verifier.verify(record.artifact!!, output.transfer, context)
        }
        override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt,
            permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion {
            check(execution, permit, context); normalizedVerifications++
            return object : DirectVerificationCompletion {
                override val evidence = verifier.verify(record.artifact!!, output, context)
                override fun close() { verificationCloses++; evidenceHandle = null }
            }.also { evidenceHandle = it }
        }
        override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect, execution: AcceptedDirectAttempt,
            permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion {
            check(execution, permit, context); convertedVerifications++; events += "converted-verify"
            return object : DirectVerificationCompletion {
                override val evidence = verifier.verify(record.artifact!!, output, context)
                override fun close() { verificationCloses++; evidenceHandle = null }
            }.also { evidenceHandle = it }
        }
        override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
            execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedCommitHandle {
            check(execution, permit, context); events += "converted-commit"
            return object : NormalizedCommitHandle {
                override fun close() { commitCloses++; commitHandle = null; publicationHandle = null }
            }.also { commitHandle = it }
        }
        override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
            execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle {
            check(handle === commitHandle); check(execution, permit, context)
            return object : NormalizedPublicationHandle {
                override val receipt = MediaCommitReceipt("task", "task", "media", output.sizeBytes, output.sha256)
            }.also { publicationHandle = it }
        }
        override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
            execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedCommitHandle {
            check(execution, permit, context)
            return object : NormalizedCommitHandle {
                override fun close() {
                    commitCloses++
                    if (commitCloseUncertain) { draining = true; throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                    commitHandle = null; publicationHandle = null
                }
            }.also { commitHandle = it }
        }
        override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
            execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle {
            check(handle === commitHandle); check(execution, permit, context)
            return object : NormalizedPublicationHandle {
                override val receipt = MediaCommitReceipt("task", "task", "media", output.sizeBytes, output.sha256)
            }.also { publicationHandle = it }
        }
        override fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
            invocationContext: OperationContext, ioContext: OperationContext) {
            gate.assertIssuedHere(permit, invocationContext)
            check(record.task.state.isTerminal); check(!ioContext.cancelToken.isCancelled)
            cleanupCalls++; events += "cleanup"; onCleanup()
        }
    }
}
