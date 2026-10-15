package com.easysavevideo.app.save.storage
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
class NormalizationDurableMutationPolicyTest {
    private fun record(): SaveRecord {
        val f = NormalizationDescriptorFixture()
        val proof = DirectNormalizationProof(f.knownKey.normalizationId, f.owner.taskId,
            f.record!!.request.networkResource!!.sourceIdentity, f.owner.artifactId, f.owner.commitId,
            f.raw.size, f.raw.sha256, f.sourceDigest, f.output.size, f.output.sha256, normalizationTestConservation())
        return f.record!!.copy(phase = SavePhase.VERIFYING, directNormalization = proof, retryBudget = 1)
    }
    private fun workspace(record: SaveRecord, lifecycle: NormalizationLifecycle = NormalizationLifecycle.ADOPTED): NormalizationWorkspace {
        val proof = record.directNormalization!!
        return NormalizationWorkspace(NormalizationKey(NormalizationOwner(record.task.taskId, record.artifact!!.artifactId, record.commitId),
            proof.normalizationId), 1, lifecycle, NormalizationDisposition.CONTINUE,
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256), proof.sourceTransferSha256,
            if (lifecycle in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)) null else NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256),
            if (lifecycle in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)) null else proof.digest())
    }
    private fun stop(prior: SaveRecord, state: TaskState = TaskState.CANCELLED): SaveRecord = prior.copy(
        task = prior.task.copy(revision = prior.task.revision + 1, state = state,
            waitingUser = if (state == TaskState.WAITING_USER) UserWait(200_000, ResumeAction.REVIEW_RECOVERY) else null),
        error = SaveError.CANCELLED,
        waitReason = if (state == TaskState.WAITING_USER) SaveWaitReason.RECOVERY else null)


    @Test fun genericEntryRejectsOrphanProofBeforeAnyMutation() {
        val prior = record()
        val next = stop(prior)
        for (owned in listOf(false, true)) {
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, DirectWorkspacePresence.ABSENT, owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next.copy(directNormalization = null), DirectWorkspacePresence.ABSENT, owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior.copy(directNormalization = null), next, DirectWorkspacePresence.ABSENT, owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, DirectWorkspacePresence.UNREADABLE, owned))
        }
        assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, DirectWorkspacePresence.PRESENT, false, workspace(prior)))
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next.copy(directNormalization = null), DirectWorkspacePresence.PRESENT, false, workspace(prior)))
    }

    @Test fun verificationProofAlsoCannotUseAbsentLedger() {
        val prior = record()
        val proof = prior.directNormalization!!
        val evidence = VerificationEvidence(prior.artifact!!.artifactId, proof.outputSizeBytes, proof.outputSha256,
            true, 1, 0, proof.conservation.endpoint.minimumUs / 1_000, proof.conservation.width,
            proof.conservation.height, false, DecodePosition.entries.toSet(), false,
            directNormalizationProof = DirectNormalizationVerificationProof(proof.normalizationId,
                prior.task.taskId, prior.request.networkResource!!.sourceIdentity, prior.artifact!!.artifactId,
                prior.commitId, proof.digest(), proof.conservation,
                DirectVideoTailProof(proof.conservation.precedingVideoSync, proof.conservation.video.lastSample,
                    proof.conservation.video.lastSample.presentationTimeUs, true)))
        val verified = prior.copy(verification = evidence)
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(verified, stop(verified), DirectWorkspacePresence.ABSENT, false))
        // The model itself prevents constructing a detached verification-only record.
        assertThrows(IllegalArgumentException::class.java) { verified.copy(directNormalization = null) }
    }

    @Test fun actualGenericTransactionRunsEntryFenceBeforePublicationAndWrites() {
        val text = java.io.File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
            .substringAfter("override fun compareAndSet(expectedRevision:").substringBefore("internal fun inspectNormalizationPresence")
        val fence = text.indexOf("NormalizationDurableMutationPolicy.permitsGeneric(prior, next, normalizedPresence,")
        assertTrue(fence >= 0)
        assertTrue(fence < text.indexOf("if (next.task.state == TaskState.SUCCEEDED)"))
        assertTrue(fence < text.indexOf("db.update("))
        assertTrue(text.substring(fence).contains("liveNormalizationOwner, durableWorkspace)) return@transaction false"))
    }

    @Test fun durableLifecycleRejectsStageSkippingAndWriterRegression() {
        val normalized = record()
        fun next(p: SaveRecord, phase: SavePhase) = p.copy(task = p.task.copy(revision = p.task.revision + 1), phase = phase)
        val raw = normalized.copy(directNormalization = null, phase = SavePhase.NORMALIZING)
        val writing = workspace(normalized, NormalizationLifecycle.WRITING)
        for (phase in listOf(SavePhase.ADOPTING, SavePhase.VERIFYING, SavePhase.COMMITTING))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(raw, next(raw, phase), DirectWorkspacePresence.PRESENT, false, writing))
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(raw, next(raw, SavePhase.ADOPTING), DirectWorkspacePresence.PRESENT, false,
            workspace(normalized, NormalizationLifecycle.OUTPUT_READY)))
        for (phase in listOf(SavePhase.NORMALIZING, SavePhase.PREPARING))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(normalized, next(normalized, phase), DirectWorkspacePresence.PRESENT, false, workspace(normalized)))
        val producer = normalized.copy(phase = SavePhase.NORMALIZING)
        assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(producer, next(producer, SavePhase.ADOPTING), DirectWorkspacePresence.PRESENT, false,
            workspace(normalized, NormalizationLifecycle.OUTPUT_READY)))
        val adopting = normalized.copy(phase = SavePhase.ADOPTING)
        assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(adopting, next(adopting, SavePhase.VERIFYING), DirectWorkspacePresence.PRESENT, false, workspace(normalized)))
    }

    @Test fun strippedProofAndChangedRawCannotBecomeLegacyEvenWithoutLiveOwner() {
        val prior = record(); val next = stop(prior)
        for (owned in listOf(false, true)) {
            assertTrue(NormalizationDurableMutationPolicy.permitsControl(prior, next, owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsControl(prior, next.copy(directNormalization = null), owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsControl(prior,
                next.copy(directNormalization = null, transfer = null), owned))
            assertFalse(NormalizationDurableMutationPolicy.permitsControl(prior,
                next.copy(bytesTransferred = next.bytesTransferred + 1), owned))
        }
    }
    @Test fun phaseOnlyAdvancePreservesAllDurableEvidence() {
        val prior = record(); val next = prior.copy(task = prior.task.copy(revision = 2), phase = SavePhase.COMMITTING)
        assertTrue(NormalizationDurableMutationPolicy.permitsControl(prior, next, false))
        assertFalse(NormalizationDurableMutationPolicy.permitsControl(prior, next.copy(directNormalization = null), false))
    }
    @Test fun explicitResumeMayChangeOperationOnlyAfterActualOwnerRelease() {
        val waiting = stop(record(), TaskState.WAITING_USER)
        val next = waiting.copy(task = waiting.task.copy(revision = waiting.task.revision + 1,
            state = TaskState.RUNNING, waitingUser = null), operationId = "new-operation", phase = waiting.phase,
            error = null, waitReason = null)
        assertTrue(NormalizationDurableMutationPolicy.permitsControl(waiting, next, false))
        assertFalse(NormalizationDurableMutationPolicy.permitsControl(waiting, next, true))
        assertFalse(NormalizationDurableMutationPolicy.permitsControl(waiting,
            next.copy(directNormalization = null), false))
    }
    @Test fun stopCannotRewriteOperationOrExtendWaitingExpiry() {
        val prior = record(); val next = stop(prior)
        assertFalse(NormalizationDurableMutationPolicy.permitsControl(prior, next.copy(operationId = "other"), false))
        val waiting = stop(prior, TaskState.WAITING_USER)
        assertFalse(NormalizationDurableMutationPolicy.permitsControl(waiting, waiting.copy(
            task = waiting.task.copy(revision = waiting.task.revision + 1,
                waitingUser = waiting.task.waitingUser!!.copy(expiresAtEpochMillis = 300_000))), false))
    }
    @Test fun conversionCannotBeInsertedDowngradedAdvancedOrTreatedAsAbsentRaw() {
        for (verified in listOf(false, true)) {
            val prior = conversionTestRecord(verified = verified, terminal = false)
            val p = prior.directConversion!!
            val row = NormalizationWorkspace(NormalizationKey(NormalizationOwner(prior.task.taskId,
                prior.artifact!!.artifactId, prior.commitId), p.normalizationId), 1,
                NormalizationLifecycle.ADOPTED, NormalizationDisposition.CONTINUE,
                NormalizationFingerprint(p.rawSizeBytes, p.rawSha256), p.sourceTransferSha256,
                NormalizationFingerprint(p.outputSizeBytes, p.outputSha256), p.digest())
            val next = stop(prior)
            assertFalse(NormalizationDurableMutationPolicy.permitsInsert(prior))
            assertFalse(NormalizationDurableMutationPolicy.permitsLegacyRecord(prior))
            for (presence in listOf(DirectWorkspacePresence.ABSENT, DirectWorkspacePresence.UNREADABLE)) {
                assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, presence, false))
                val stripped = next.copy(directConversion = null, verification = null)
                assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, stripped, presence, false))
                assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior.copy(directConversion = null, verification = null),
                    next, presence, false))
            }
            assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, DirectWorkspacePresence.PRESENT, false, row))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next.copy(directConversion = null, verification = null),
                DirectWorkspacePresence.PRESENT, false, row))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior,
                prior.copy(task = prior.task.copy(revision = prior.task.revision + 1), phase = SavePhase.COMMITTING),
                DirectWorkspacePresence.PRESENT, false, row))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, next, DirectWorkspacePresence.PRESENT, false,
                row.copy(proofSha256 = "f".repeat(64))))
        }
        assertTrue(NormalizationDurableMutationPolicy.permitsLegacyRecord(null))
        val raw = conversionTestRecord(verified = false, terminal = false).copy(directConversion = null)
        assertTrue(NormalizationDurableMutationPolicy.permitsInsert(raw))
        assertTrue(NormalizationDurableMutationPolicy.permitsLegacyRecord(raw))
    }
    @Test fun sqlInsertAndEveryLegacyFileFenceCheckParentProofBeforeStateOrFileEscape() {
        // Source-wiring evidence plus executed pure predicates above; no Android SQLite is simulated.
        val source = java.io.File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
        val insert = source.substringAfter("override fun insert(").substringBefore("override fun compareAndSet(")
        assertTrue(insert.indexOf("NormalizationDurableMutationPolicy.permitsInsert") < insert.indexOf("transaction {"))
        for (name in listOf("ensureLegacyNormalizationArtifact", "ensureLegacyNormalizationCommit")) {
            val method = source.substringAfter("private fun $name(").substringBefore("\n    private fun ")
            assertTrue(method.contains("NormalizationDurableMutationPolicy.permitsLegacyRecord(queryRecord"))
            assertTrue(method.indexOf("permitsLegacyRecord") < method.indexOf("normalizationReservations.requireLegacyAvailable"))
            assertTrue(method.indexOf("permitsLegacyRecord") < method.indexOf("NormalizationLegacyFence.requireAbsent"))
        }
    }

    @Test fun silentConversionAllowsOnlyBoundAdoptionPhasesAndKeepsEvidenceCasTyped() {
        val base = conversionTestRecord(audio = false, verified = false, terminal = false).copy(phase = SavePhase.NORMALIZING)
        val proof = base.directConversion!!
        val row = NormalizationWorkspace(NormalizationKey(NormalizationOwner(base.task.taskId, base.artifact!!.artifactId, base.commitId),
            proof.normalizationId), 5, NormalizationLifecycle.OUTPUT_READY, NormalizationDisposition.CONTINUE,
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256), proof.sourceTransferSha256,
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256), proof.digest())
        fun next(record: SaveRecord, phase: SavePhase) = record.copy(task = record.task.copy(revision = record.task.revision + 1), phase = phase)
        val adopting = next(base, SavePhase.ADOPTING)
        for (lifecycle in listOf(NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT))
            assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(base, adopting, DirectWorkspacePresence.PRESENT, false, row.copy(lifecycle = lifecycle)))
        val adopted = row.copy(lifecycle = NormalizationLifecycle.ADOPTED)
        val verifying = next(adopting, SavePhase.VERIFYING)
        assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(adopting, verifying, DirectWorkspacePresence.PRESENT, false, adopted))
        for ((prior, after, workspace) in listOf(Triple(base, next(base, SavePhase.VERIFYING), row),
            Triple(base, next(base, SavePhase.COMMITTING), row), Triple(adopting, verifying, row),
            Triple(verifying, next(verifying, SavePhase.COMMITTING), adopted)))
            assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(prior, after, DirectWorkspacePresence.PRESENT, false, workspace))
        val verified = conversionTestRecord(audio = false, verified = true, terminal = false)
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(verified, next(verified, SavePhase.COMMITTING),
            DirectWorkspacePresence.PRESENT, false, adopted))
        for (phase in listOf(SavePhase.NORMALIZING, SavePhase.ADOPTING, SavePhase.VERIFYING, SavePhase.COMMITTING)) {
            val prior = if (phase == SavePhase.COMMITTING) verified.copy(phase = phase) else base.copy(phase = phase)
            assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(prior, stop(prior), DirectWorkspacePresence.PRESENT, false,
                if (phase == SavePhase.NORMALIZING) row else adopted))
        }
        val changed = listOf(row.copy(proofSha256 = "f".repeat(64)), row.copy(raw = row.raw.copy(sha256 = "e".repeat(64))),
            row.copy(output = row.output!!.copy(sha256 = "d".repeat(64))), row.copy(sourceTransferSha256 = "c".repeat(64)),
            row.copy(key = row.key.copy(normalizationId = "99999999-9999-4999-8999-999999999999")))
        for (foreign in changed) assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(base, adopting, DirectWorkspacePresence.PRESENT, false, foreign))
        val maximum = base.copy(task = base.task.copy(revision = Long.MAX_VALUE))
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(maximum, adopting, DirectWorkspacePresence.PRESENT, false, row))
    }
    @Test fun convertedCurrentAudioAllowsAdoptionButHistoryOnlyAllowsControlStop() {
        for (expectedAudio in listOf(false, true)) for (policy in listOf(1, 2)) {
            val historical = conversionTestRecord(audio = true, verified = false, terminal = false)
            val input = historical.request.input as SaveInput.PrivateNetwork
            val base = historical.copy(phase = SavePhase.NORMALIZING,
                request = historical.request.copy(input = input.copy(source = input.source.copy(expectedAudio = expectedAudio))),
                directConversion = historical.directConversion!!.copy(policyRevision = policy))
            val proof = base.directConversion!!
            val row = NormalizationWorkspace(NormalizationKey(NormalizationOwner(base.task.taskId, base.artifact!!.artifactId,
                base.commitId), proof.normalizationId), 5, NormalizationLifecycle.OUTPUT_READY, NormalizationDisposition.CONTINUE,
                NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256), proof.sourceTransferSha256,
                NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256), proof.digest())
            val next = base.copy(task = base.task.copy(revision = base.task.revision + 1), phase = SavePhase.ADOPTING)
            assertEquals(policy == 2, NormalizationDurableMutationPolicy.permitsGeneric(base, next, DirectWorkspacePresence.PRESENT, false, row))
            for (terminal in listOf(TaskState.CANCELLED, TaskState.FAILED))
                assertTrue(NormalizationDurableMutationPolicy.permitsGeneric(base, stop(base, terminal), DirectWorkspacePresence.PRESENT, false, row))
            val waiting = stop(base, TaskState.WAITING_USER)
            val resume = waiting.copy(task = waiting.task.copy(revision = waiting.task.revision + 1, state = TaskState.RUNNING,
                waitingUser = null), operationId = "new-operation", error = null, waitReason = null)
            assertEquals(policy == 2, NormalizationDurableMutationPolicy.permitsControl(waiting, resume, false))
            assertEquals(policy == 2, NormalizationDurableMutationPolicy.permitsGeneric(waiting, resume, DirectWorkspacePresence.PRESENT, false, row))
            assertFalse(NormalizationDurableMutationPolicy.permitsControl(waiting, resume, true))
        }
        val silent = conversionTestRecord(audio = false, verified = false, terminal = false)
        val input = silent.request.input as SaveInput.PrivateNetwork
        val missing = silent.copy(phase = SavePhase.NORMALIZING,
            request = silent.request.copy(input = input.copy(source = input.source.copy(expectedAudio = true))))
        val p = missing.directConversion!!
        val row = NormalizationWorkspace(NormalizationKey(NormalizationOwner(missing.task.taskId, missing.artifact!!.artifactId,
            missing.commitId), p.normalizationId), 5, NormalizationLifecycle.OUTPUT_READY, NormalizationDisposition.CONTINUE,
            NormalizationFingerprint(p.rawSizeBytes, p.rawSha256), p.sourceTransferSha256,
            NormalizationFingerprint(p.outputSizeBytes, p.outputSha256), p.digest())
        assertFalse(NormalizationDurableMutationPolicy.permitsGeneric(missing,
            missing.copy(task = missing.task.copy(revision = missing.task.revision + 1), phase = SavePhase.ADOPTING),
            DirectWorkspacePresence.PRESENT, false, row))
    }
    @Test fun actualVerificationSqlBoundaryRejectsOverflowBeforeAddingRevision() {
        // Supplemental source-order evidence. Android SQLite transactions are not executed by this test.
        val source = java.io.File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
        val method = source.substringAfter("override fun compareAndSetVerification(").substringBefore("override fun compareAndSetNormalized(")
        assertTrue(method.indexOf("expectedRevision == Long.MAX_VALUE") >= 0)
        assertTrue(method.indexOf("expectedRevision == Long.MAX_VALUE") < method.indexOf("expectedRevision + 1"))
        assertTrue(method.contains("prior != fact.expectedRecord")); assertTrue(method.contains("verification = fact.evidence"))
    }

}
