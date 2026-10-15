package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.save.DirectConvertedAndroidVerifier
import com.easysavevideo.app.media.verification.AvcAacOutputObservations
import com.easysavevideo.app.media.verification.BorrowedAvcAacInspection
import com.easysavevideo.app.save.SaveExecutionRetirement
import com.easysavevideo.app.save.normalization.*
import com.easysavevideo.core.model.*
import com.easysavevideo.library.thumbnail.ThumbnailNativeResourceGate
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/** Actual Android adapter/core/owners/commit composition; fake OS, remux and native decoding only. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AndroidControlledDirectStorageIntegrationTest {
    @Test fun ordinaryMp4TraversesActualCoreBridgeSelectorAndPositiveLegacyRelease() {
        Harness(mov = false).use { h ->
            val result = h.run()
            assertEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(1, h.transfers); assertEquals(1, h.legacyVerifications)
            assertEquals(1, h.legacyReleases); assertEquals(1, h.genericCommits)
            assertEquals(0, h.remuxes); assertNull(h.ledger.row)
            assertFalse(h.adapter.requiresProcessRecovery()); assertFalse(h.reservations.hasAnyReservations())
            assertFalse(h.admission.owns(h.lastClaim!!)); assertFalse(h.nativeGate.isOccupied)
            assertEquals(listOf(h.lastClaim), h.retiredClaims)
            assertTrue(h.io.calls.any { it.operation == "pread" })
            assertTrue(h.io.handles.all { !it.active })
        }
    }
    @Test fun ordinaryFutureResultWithoutActualLegacyReleaseCannotPublish() {
        Harness(mov = false).use { h ->
            h.confirmLegacyRelease = false
            val result = h.run()
            assertNotEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(0, h.genericCommits); assertTrue(h.adapter.requiresProcessRecovery())
            assertEquals(1, h.legacyVerifications); assertEquals(0, h.legacyReleases)
            val original = h.lastClaim!!
            val oldBatch = h.batchView!!
            assertTrue(h.admission.owns(original)); assertTrue(h.nativeGate.isOccupied); assertTrue(h.retiredClaims.isEmpty())
            assertNull(h.admission.tryAcquireDirect())
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, assertThrows(SaveFailure::class.java) {
                h.gate.withBoundPermit(Any(), h.operation("blocked-local")) { error("SECOND_LOCAL_ENTERED") }
            }.code)
            h.retirement.retry(); assertTrue(h.admission.owns(original))
            // The original worker's positive release, rather than a new execution, clears drain.
            h.pendingLegacyRelease!!(true)
            assertFalse(h.adapter.requiresProcessRecovery())
            h.retirement.retry()
            assertFalse(h.admission.owns(original)); assertFalse(oldBatch.isLive)
            assertFalse(h.nativeGate.isOccupied); assertEquals(listOf(original), h.retiredClaims)
            val fresh = h.admission.tryAcquireDirect()!!
            assertNotSame(oldBatch, h.batchView); assertTrue(h.batchView!!.isLive)
            h.retirement.request(original); h.retirement.retry()
            assertTrue(h.admission.owns(fresh)); assertTrue(h.nativeGate.isOccupied)
            h.retirement.request(fresh); assertFalse(h.nativeGate.isOccupied)
        }
    }
    @Test fun unconfirmedLegacyCloseIsStickyEvenAfterALaterCallback() {
        Harness(mov = false).use { h ->
            h.uncertainLegacyRelease = true
            val result = h.run()
            assertNotEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(0, h.genericCommits); assertTrue(h.adapter.requiresProcessRecovery())
            val original = h.lastClaim!!
            assertTrue(h.admission.owns(original)); assertTrue(h.nativeGate.isOccupied)
            assertNull(h.admission.tryAcquireDirect())
            h.pendingLegacyRelease!!(true); h.retirement.retry()
            assertTrue(h.adapter.requiresProcessRecovery())
            assertTrue(h.admission.owns(original)); assertTrue(h.nativeGate.isOccupied); assertTrue(h.retiredClaims.isEmpty())
        }
    }
    @Test fun queuedCancellationRetiresItsRegistrationAndLateWorkerCannotUseNoopPath() {
        Harness(mov = false).use { h ->
            h.cancelBeforeLegacyIssue = true
            val result = h.run()
            assertNotEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(0, h.genericCommits); assertFalse(h.adapter.requiresProcessRecovery())
            assertEquals(SaveError.RECOVERY_REQUIRED, assertThrows(SaveFailure::class.java) {
                h.adapter.onLegacyReadIssued(h.artifact, h.lastLegacyContext!!)
            }.code)
            assertFalse(h.adapter.requiresProcessRecovery())
        }
    }
    @Test fun defaultMovSwitchRejectsActualByteSelectionBeforeAnyWriterOrLegacyDispatch() {
        Harness(mov = true, enabled = false).use { h ->
            val result = h.run()
            assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.UNSUPPORTED_MEDIA, result.error)
            assertEquals(1, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.legacyVerifications)
            assertNull(h.ledger.row)
            assertFalse(h.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE ||
                it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
            assertFalse(h.reservations.hasAnyReservations())
        }
    }
    @Test fun completeRawResumeNeedsRealStartupFactBeforeAnyReadonlyIo() {
        Harness(mov = false).use { h ->
            h.seedRaw(SavePhase.EXECUTING, startup = false)
            val result = h.resume()
            assertEquals(TaskState.WAITING_USER, result.task.state); assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
            assertEquals(0, h.transfers); assertEquals(0, h.legacyVerifications)
            assertFalse(h.io.calls.any { it.operation == "open" || it.operation == "pread" })
        }
    }
    @Test fun genuineStartupOldVerifyingMp4RemainsLocalAndNeverCreatesMovWriter() {
        Harness(mov = false).use { h ->
            h.seedRaw(SavePhase.VERIFYING, startup = true)
            val result = h.resume()
            assertEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(0, h.transfers); assertEquals(1, h.legacyVerifications); assertEquals(0, h.remuxes)
        }
    }

    @Test fun movUsesRealOwnerProofAdoptionBindingCommitAndTerminalCleanup() {
        Harness(mov = true).use { h ->
            val result = h.run()
            assertEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(1, h.transfers); assertEquals(1, h.remuxes); assertEquals(1, h.normalizedVerifications)
            assertEquals(1, h.proofCas); assertEquals(1, h.verifyCas); assertEquals(1, h.publications)
            assertEquals(0, h.legacyVerifications); assertEquals(0, h.genericCommits)
            assertEquals(h.raw.size.toLong(), result.transfer!!.bytesOnDisk)
            assertEquals(h.canonical.size.toLong(), result.receipt!!.sizeBytes)
            assertEquals(NormalizationLifecycle.CLEANED, h.ledger.row!!.lifecycle)
            assertTrue(h.visible); assertTrue(h.linked)
            assertTrue(h.io.exists(File(File(h.root, "media"), h.mediaId + ".mp4")))
            assertFalse(h.io.exists(h.primary)); assertFalse(h.reservations.hasAnyReservations())
            assertFalse(h.canonicalOwner.hasAnyLeases()); assertEquals(1, h.raf.opens); assertEquals(1, h.raf.closes)
            assertTrue(h.events.indexOf("proof") < h.events.indexOf("verified"))
            assertTrue(h.events.indexOf("verified") < h.events.indexOf("intent"))
            assertTrue(h.events.indexOf("publish") < h.events.indexOf("cleaned"))
        }
    }
    @Test fun proofAndPublicationWriteThenThrowReadBackExactWithoutSecondRemuxOrPublish() {
        for (proof in listOf(false, true)) Harness(mov = true).use { h ->
            h.throwAfterProof = proof; h.throwAfterPublish = !proof
            assertEquals(TaskState.SUCCEEDED, h.run().task.state)
            assertEquals(1, h.remuxes); assertEquals(1, h.normalizedVerifications)
            assertEquals(1, h.proofCas); assertEquals(1, h.publications)
            assertEquals(NormalizationLifecycle.CLEANED, h.ledger.row!!.lifecycle)
        }
    }
    @Test fun cancellationAfterClosedRemuxCannotTurnProducedBytesIntoProofOrNativeEvidence() {
        Harness(mov = true).use { h ->
            h.afterRemux = { h.coordinator.signalUserCancel("task") }
            val result = h.run()
            assertEquals(TaskState.CANCELLED, result.task.state)
            assertEquals(1, h.remuxes); assertEquals(0, h.proofCas); assertEquals(0, h.normalizedVerifications)
            assertEquals(0, h.publications); assertFalse(h.reservations.hasAnyReservations())
            assertNull(result.directNormalization)
        }
    }

    @Test fun privateOutputMetadataTraversesActualOrdinaryAndNormalizedPublicationWithoutSourceUrl() {
        for (mov in listOf(false, true)) Harness(mov = mov, privateSource = true).use { h ->
            val result = h.run()
            assertEquals(TaskState.SUCCEEDED, result.task.state)
            assertTrue(result.request.input is SaveInput.PrivateNetwork)
            assertNull(result.request.networkResource)
            assertNull(result.checkpoint)
            assertEquals(h.privateBinding!!.sourceIdentity.encoded, result.request.networkSourceMetadata!!.sourceIdentity)
            val bytes = SaveRecordCodec.encode(result)
            val decoded = SaveRecordCodec.decode(bytes)
            assertEquals(result, decoded)
            val persisted = bytes.toString(Charsets.ISO_8859_1)
            assertFalse(persisted.contains("example.invalid"))
            assertFalse(persisted.contains("/owned"))
            assertFalse(persisted.contains(h.source.url.identity()))
            if (mov) {
                assertEquals(h.privateBinding.sourceIdentity.encoded, result.directNormalization!!.sourceIdentity)
                assertEquals(1, h.publications)
            } else assertEquals(1, h.genericCommits)
            assertFalse(h.reservations.hasAnyReservations())
            assertFalse(h.admission.owns(h.lastClaim!!))
        }
    }

    @Test fun audioPolicyTwoTraversesActualOwnerCoreAndCommitWithFakeDecoder() {
        // Real storage/core composition and bounded scanner. Opaque AAC and fake PCM are not native acceptance.
        for (expected in listOf(false, true)) for (count in listOf(3, 7)) {
            Harness(mov = true, audio = true, expectedAudio = expected, audioCount = count).use { h ->
                val result = h.run()
                assertEquals(TaskState.SUCCEEDED, result.task.state)
                assertEquals(2, result.directNormalization!!.policyRevision)
                assertEquals(1, result.verification!!.audioTrackCount)
                assertTrue(result.verification!!.audioTrackReadable)
                assertEquals(1, h.remuxes); assertEquals(1, h.normalizedVerifications)
                assertEquals(1, h.proofCas); assertEquals(1, h.verifyCas); assertEquals(1, h.publications)
                assertEquals(NormalizationLifecycle.CLEANED, h.ledger.row!!.lifecycle)
                assertEquals(0, h.legacyVerifications); assertEquals(0, h.genericCommits)
            }
        }
    }

    @Test fun failedSeparateAudioObservationCannotPublishDespiteValidVideoAndPolicyTwo() {
        Harness(mov = true, audio = true).use { h ->
            h.audioReadable = false
            val result = h.run()
            assertNotEquals(TaskState.SUCCEEDED, result.task.state)
            assertEquals(1, h.normalizedVerifications); assertEquals(0, h.verifyCas); assertEquals(0, h.publications)
            assertFalse(h.visible); assertFalse(h.linked)
        }
    }

    @Test fun historicalAudioRecoveryStopsBeforeIoAndRetainsEveryProofWorkspaceAndFile() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            for (verified in listOf(false, true)) {
                if (catalog != OwnedState.STAGING && !verified) continue
                Harness(mov = true, audio = true).use { h ->
                    h.seedNormalized(policy = 1, verified = verified, catalog = catalog)
                    val proof = h.record!!.directNormalization
                    val verification = h.record!!.verification
                    val workspace = h.ledger.row
                    val owned = h.owned
                    val paths = h.normalizedPaths()
                    val bytes = paths.associateWith { h.io.bytes(it) }
                    val calls = h.io.calls.size
                    val result = h.resume()
                    assertEquals(TaskState.WAITING_USER, result.task.state)
                    assertEquals(SaveError.RECOVERY_REQUIRED, result.error)
                    assertEquals(proof, result.directNormalization); assertEquals(verification, result.verification)
                    assertEquals(workspace, h.ledger.row); assertEquals(owned, h.owned)
                    assertEquals(calls, h.io.calls.size)
                    paths.forEach { assertArrayEquals(bytes[it], h.io.bytes(it)) }
                    assertEquals(0, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.normalizedVerifications)
                    assertEquals(0, h.publications); assertEquals(0, h.proofCas); assertFalse(h.visible)
                }
            }
        }
    }

    @Test fun currentAudioAndSilentAdoptedRecoveryVerifiesMissingEvidenceAndReusesOnlyExactStoredEvidence() {
        for (audio in listOf(false, true)) for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            for (verified in listOf(false, true)) {
                if (catalog != OwnedState.STAGING && !verified) continue
                Harness(mov = true, audio = audio, expectedAudio = audio).use { h ->
                    h.seedNormalized(policy = if (audio) 2 else 1, verified = verified, catalog = catalog)
                    val proof = h.record!!.directNormalization
                    val result = h.resume()
                    assertEquals(TaskState.SUCCEEDED, result.task.state)
                    assertEquals(proof, result.directNormalization)
                    assertEquals(if (verified) 0 else 1, h.normalizedVerifications)
                    assertEquals(0, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.proofCas)
                    assertEquals(1, h.publications); assertTrue(h.visible); assertTrue(h.linked)
                }
            }
        }
    }

    @Test fun currentAudioOutputReadyAndAdoptIntentRecoverThroughRealAdoptionAndFreshVerification() {
        for (lifecycle in listOf(NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT)) {
            for (audio in listOf(false, true)) Harness(mov = true, audio = audio).use { h ->
                h.seedNormalized(policy = if (audio) 2 else 1, verified = false, catalog = OwnedState.STAGING, lifecycle = lifecycle)
                val proof = h.record!!.directNormalization
                val result = h.resume()
                assertEquals(TaskState.SUCCEEDED, result.task.state)
                assertEquals(proof, result.directNormalization)
                assertEquals(1, h.normalizedVerifications); assertEquals(1, h.publications)
                assertEquals(0, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.proofCas)
            }
            Harness(mov = true, audio = true).use { h ->
                h.seedNormalized(policy = 1, verified = false, catalog = OwnedState.STAGING, lifecycle = lifecycle)
                val old = h.record!!.directNormalization; val row = h.ledger.row; val calls = h.io.calls.size
                assertEquals(SaveError.RECOVERY_REQUIRED, h.resume().error)
                assertEquals(old, h.record!!.directNormalization); assertEquals(row, h.ledger.row)
                assertEquals(calls, h.io.calls.size); assertEquals(0, h.publications); assertEquals(0, h.normalizedVerifications)
            }
        }
    }

    @Test fun currentAudioCommitRecoveryRequiresExactSameStoredCatalogEvidence() {
        for (catalog in listOf(OwnedState.INTENT, OwnedState.INDEXED)) {
            for (missing in listOf(false, true)) Harness(mov = true, audio = true).use { h ->
                h.seedNormalized(policy = 2, verified = true, catalog = catalog)
                if (missing) {
                    h.record = h.record!!.copy(verification = null)
                } else {
                    val old = h.record!!.directNormalization!!.copy(policyRevision = 1)
                    h.owned = h.owned.copy(evidence = h.owned.evidence!!.copy(directNormalizationProof =
                        h.owned.evidence!!.directNormalizationProof!!.copy(normalizationProofSha256 = old.digest())))
                }
                h.startup = h.record
                val calls = h.io.calls.size
                assertEquals(SaveError.RECOVERY_REQUIRED, h.resume().error)
                assertEquals(calls, h.io.calls.size); assertEquals(0, h.publications); assertEquals(0, h.normalizedVerifications)
                assertEquals(0, h.transfers); assertEquals(0, h.remuxes)
            }
        }
    }

    @Test fun currentPolicyStillRejectsWrongLedgerDigestAndCorruptCanonicalFinalRole() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            Harness(mov = true, audio = true).use { h ->
                h.seedNormalized(policy = 2, verified = true, catalog = catalog)
                h.ledger.row = h.ledger.row!!.copy(proofSha256 = h.record!!.directNormalization!!.copy(policyRevision = 1).digest())
                val calls = h.io.calls.size
                assertEquals(SaveError.RECOVERY_REQUIRED, h.resume().error)
                assertEquals(calls, h.io.calls.size); assertEquals(0, h.publications)
            }
            Harness(mov = true, audio = true).use { h ->
                h.seedNormalized(policy = 2, verified = true, catalog = catalog)
                val canonical = h.normalizedPaths().last()
                h.io.mutate(canonical, h.io.bytes(canonical).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                assertNotEquals(TaskState.SUCCEEDED, h.resume().task.state)
                assertEquals(0, h.publications); assertFalse(h.visible); assertFalse(h.linked)
                assertEquals(0, h.transfers); assertEquals(0, h.normalizedVerifications)
            }
        }
    }

    @Test fun convertedCandidateDefaultGateDoesNotPrepareWriterAndOpenGateUsesActualFailClosedProducer() {
        for (enabled in listOf(false, true)) Harness(mov = false, converted = true, conversionEnabled = enabled).use { h ->
            val result = h.run()
            assertEquals(if (enabled) SaveError.RECOVERY_REQUIRED else SaveError.UNSUPPORTED_MEDIA, result.error)
            assertEquals(1, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.proofCas)
            assertEquals(0, h.normalizedVerifications); assertEquals(0, h.legacyVerifications); assertEquals(0, h.publications)
            // No native engine is supplied to this host owner; enabling routing cannot manufacture success.
            if (enabled) assertEquals(NormalizationLifecycle.WRITING, h.ledger.row!!.lifecycle)
            else assertNull(h.ledger.row)
        }
    }

    @Test fun convertedRealAdapterRecoveryTraversesBothAdoptionMovesAndSyntheticIndependentRead() {
        for ((lifecycle, layout) in listOf(NormalizationLifecycle.OUTPUT_READY to NormalizationLayout.READY,
            NormalizationLifecycle.ADOPT_INTENT to NormalizationLayout.READY,
            NormalizationLifecycle.ADOPT_INTENT to NormalizationLayout.RAW_MOVED,
            NormalizationLifecycle.ADOPT_INTENT to NormalizationLayout.ADOPTED)) {
            Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
                h.seedConverted(false, OwnedState.STAGING, lifecycle, layout)
                val proof = h.record!!.directConversion
                val result = h.resume()
                assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(proof, result.directConversion)
                assertNull(result.directNormalization); assertEquals(0, h.transfers); assertEquals(0, h.remuxes)
                assertEquals(1, h.normalizedVerifications); assertEquals(1, h.verifyCas); assertEquals(1, h.publications)
                assertEquals(0, h.genericCommits); assertEquals(0, h.legacyVerifications)
                assertEquals(NormalizationLifecycle.CLEANED, h.ledger.row!!.lifecycle)
                assertTrue(h.visible); assertTrue(h.linked); assertFalse(h.reservations.hasAnyReservations())
            }
        }
    }

    @Test fun newConvertedFixtureRecoversSavedVerificationBeforeFirstIntentWithoutRepeatingNativeOrHttp() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            // A genuinely fresh owner/protocol fixture restores this exact durable checkpoint.
            Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
                h.seedConverted(true, catalog)
                val original = h.record!!; val key = h.ledger.row!!.key
                if (catalog == OwnedState.STAGING) { assertNull(h.owned.evidence); assertEquals(SavePhase.COMMITTING, original.phase) }
                val result = h.resume()
                assertEquals(TaskState.SUCCEEDED, result.task.state)
                assertEquals(original.directConversion, result.directConversion); assertEquals(original.verification, result.verification)
                assertEquals(key, h.ledger.row!!.key)
                assertEquals(0, h.transfers); assertEquals(0, h.remuxes); assertEquals(0, h.normalizedVerifications)
                assertEquals(0, h.verifyCas); assertEquals(1, h.publications); assertEquals(0, h.genericCommits)
                assertArrayEquals(h.canonical, h.io.bytes(File(File(h.root, "media"), "${h.mediaId}.mp4")))
            }
        }
    }

    @Test fun newConvertedFixtureCancelsSavedVerificationStagingCheckpointAndInvisibleCommitExactly() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
                h.seedConverted(true, catalog)
                val paths = h.normalizedPaths(); val original = h.record!!
                h.beforeResumeExecution = { h.coordinator.signalUserCancel("task") }
                val result = h.resume()
                assertEquals(TaskState.CANCELLED, result.task.state)
                assertEquals(original.directConversion, result.directConversion); assertEquals(original.verification, result.verification)
                assertEquals(NormalizationLifecycle.CLEANED, h.ledger.row!!.lifecycle)
                paths.forEach { assertNull(h.io.lstat(it)) }
                assertEquals(OwnedState.DISCARDED, h.owned.state); assertNull(h.indexed)
                assertFalse(h.visible); assertFalse(h.linked); assertEquals(0, h.publications)
                assertEquals(0, h.normalizedVerifications); assertEquals(0, h.transfers)
            }
        }
    }

    @Test fun convertedRecoveryClosedGateMissingEvidenceOrChangedCanonicalCannotPublish() {
        Harness(mov = false, converted = true).use { h ->
            h.seedConverted(true, OwnedState.STAGING); val calls = h.io.calls.size
            assertEquals(SaveError.RECOVERY_REQUIRED, h.resume().error)
            assertEquals(calls, h.io.calls.size); assertEquals(0, h.publications)
        }
        Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
            h.seedConverted(true, OwnedState.STAGING)
            h.record = h.record!!.copy(verification = null)
            assertEquals(SaveError.RECOVERY_REQUIRED, h.resume().error)
            assertEquals(0, h.normalizedVerifications); assertEquals(0, h.publications)
        }
        Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
            h.seedConverted(true, OwnedState.STAGING)
            h.io.mutate(h.primary, h.canonical.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertNotEquals(TaskState.SUCCEEDED, h.resume().task.state)
            assertEquals(0, h.publications); assertEquals(0, h.transfers); assertEquals(0, h.normalizedVerifications)
        }
    }

    @Test fun convertedUnconsumedBindingOrStopAfterPositiveReadCannotIssueEvidenceCompletion() {
        for (unconsumed in listOf(false, true)) Harness(mov = false, converted = true, conversionEnabled = true).use { h ->
            h.seedConverted(false, OwnedState.STAGING)
            h.bypassOwnedRead = unconsumed
            if (!unconsumed) h.afterOwnedRead = { h.coordinator.signalUserCancel("task") }
            assertNotEquals(TaskState.SUCCEEDED, h.resume().task.state)
            assertEquals(0, h.verifyCas); assertEquals(0, h.publications)
            assertEquals(if (unconsumed) 0 else 1, h.raf.opens)
            assertEquals(h.raf.opens, h.raf.closes)
        }
    }

    private class Harness(val mov: Boolean, val enabled: Boolean = true, privateSource: Boolean = false,
        val audio: Boolean = false, val expectedAudio: Boolean = false, val audioCount: Int = 3,
        val converted: Boolean = false, val conversionEnabled: Boolean = false) : AutoCloseable {
        val lock = Any()
        val root = File("/controlled-direct-host-composition")
        val io = MemoryNormalizationDescriptorIo()
        val ledger = NormalizationTestLedger()
        val reservations = NormalizationIoReservations(lock)
        val clock = EpochClock { 0 }
        val limits = SaveLimits(NORMALIZATION_MAX_BYTES, 4L * 1024 * 1024 * 1024)
        val artifact = StagingArtifact("11111111-1111-4111-8111-111111111111", "task")
        val ownerKey = NormalizationOwner("task", artifact.artifactId, "task")
        val mediaId = "33333333-3333-4333-8333-333333333333"
        val primary = File(File(root, "staging"), artifact.artifactId + ".part")
        val raw = if (converted) byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(2048)
            else OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = mov, audio = audio, audioCount = audioCount))
        val canonical = OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = false, audio = audio, audioCount = audioCount))
        val transfer = TransferResult(artifact.artifactId, raw.size.toLong(), "application/octet-stream", raw.size.toLong())
        val privateBinding = if (privateSource) PrivateNetworkSourceSeed.create().bindTask("task") else null
        val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/owned"),
            sourceIdentity = privateBinding?.sourceIdentity?.encoded ?: SourceUrl("https://example.invalid/owned").identity(),
            privateSource = privateBinding, expectedAudio = expectedAudio)
        var record: SaveRecord? = null
        var startup: SaveRecord? = null
        var owned = OwnedMedia("task", artifact, mediaId, OwnedState.STAGING)
        var indexed: MediaCommitReceipt? = null
        var visible = false
        var linked = false
        var transfers = 0; var legacyVerifications = 0; var legacyReleases = 0; var genericCommits = 0; var remuxes = 0
        var confirmLegacyRelease = true; var uncertainLegacyRelease = false; var cancelBeforeLegacyIssue = false
        var lastLegacyContext: OperationContext? = null
        var nativeOutstanding = false; var nativeReturned = false
        var lastClaim: SaveExecutionAdmission.Claim? = null
        val retiredClaims = mutableListOf<SaveExecutionAdmission.Claim>()
        var pendingLegacyRelease: ((Boolean) -> Unit)? = null
        var proofCas = 0; var verifyCas = 0; var publications = 0; var normalizedVerifications = 0
        var throwAfterProof = false; var throwAfterPublish = false
        var audioReadable = true
        var bypassOwnedRead = false
        var beforeResumeExecution: () -> Unit = {}
        var afterOwnedRead: () -> Unit = {}
        var afterRemux: () -> Unit = {}
        val events = mutableListOf<String>()
        val nativeGate = ThumbnailNativeResourceGate()
        val batch = Any()
        var batchView: ThumbnailNativeResourceGate.Ownership? = null
        fun draining(): Boolean = reservations.resourcesUnreleased() || owner.releaseUnconfirmed() ||
            adapter.requiresProcessRecovery() || canonicalOwner.requiresProcessRecovery() || (nativeOutstanding && nativeReturned)
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes,
            resourcesUnreleased = ::draining,
            onFirstDirect = { nativeGate.claim(batch).also { if (it) batchView = nativeGate.ownership(batch) } },
            onLastDirectReleased = { nativeGate.released(batch); batchView = null })
        val retirement = SaveExecutionRetirement(admission::owns, ::draining, admission::release) { retiredClaims += it }
        val gate = DirectLocalProcessingGate(clock, ::draining)
        val bridge = AndroidDirectExecutionBridge(admission, gate) { batchView }
        val bindings = AdoptedVerificationBindings(gate, executionBridge = bridge)
        val durable = object : DurableFileOps {
            override fun syncDirectory(directory: File): Unit = error("DESCRIPTOR_BARRIER_REQUIRED")
            override fun promoteNoReplace(source: File, destination: File) { io.move(source, destination) }
            override fun replaceOwnedStaging(source: File, destination: File): Unit = error("NO_REPLACEMENT")
        }
        val normalFiles = AndroidNormalizationFiles(root, durable, lock, { limits.maxTotalTransferBytes }, { false }, io, reservations)
        val owner = NormalizationAttemptOwner(ledger, normalFiles, { key ->
            val current = record!!
            NormalizationAuthority(key, when (current.task.state) {
                TaskState.SUCCEEDED -> NormalizationTaskState.SUCCEEDED
                TaskState.FAILED -> NormalizationTaskState.FAILED
                TaskState.CANCELLED -> NormalizationTaskState.CANCELLED
                else -> NormalizationTaskState.ACTIVE
            }, when (owned.state) {
                OwnedState.STAGING -> NormalizationParentState.STAGING
                OwnedState.DISCARDED -> NormalizationParentState.DISCARDED
                else -> if (visible) NormalizationParentState.PUBLISHED else NormalizationParentState.COMMIT_PENDING
            }, sourceTransferFingerprint(current.transfer!!), current.directNormalization?.digest() ?: current.directConversion?.digest(),
                current.receipt?.let { NormalizationFingerprint(it.sizeBytes, it.sha256) })
        }, { record }, lock, { false }, {}, ::checkOperation,
            requireTranscodeScope = { it.requireIssuer(bridge) })
        fun state(): NormalizedCommitState? = ledger.row?.let { row -> record?.let {
            NormalizedCommitState(it, row, owned, indexed, visible, linked)
        } }
        val commitJournal = object : NormalizedCommitJournal {
            override fun read(owner: NormalizationOwner) = state()?.takeIf { it.workspace.key.owner == owner }
            fun update(expected: NormalizedCommitState, next: NormalizedCommitState, event: String): Boolean {
                check(Thread.holdsLock(lock)); if (state() != expected) return false
                record = next.record; ledger.row = next.workspace; owned = next.owned
                indexed = next.indexed; visible = next.indexVisible; linked = next.libraryLinked
                events += event
                if (event == "publish") { publications++; if (throwAfterPublish) throw IOException("LOST_PUBLISH_ACK") }
                return true
            }
            override fun writeIntent(expected: NormalizedCommitState, intent: OwnedMedia) = update(expected, expected.copy(owned = intent), "intent")
            override fun writeIndex(expected: NormalizedCommitState) = update(expected,
                expected.copy(owned = expected.owned.copy(state = OwnedState.INDEXED), indexed = expected.owned.receipt()), "index")
            override fun publish(expected: NormalizedCommitState, succeeded: SaveRecord) = update(expected,
                expected.copy(record = succeeded, indexVisible = true, libraryLinked = true), "publish")
            override fun planCleanup(expected: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean) = update(expected,
                expected.copy(workspace = pending, owned = if (discard) expected.owned.copy(state = OwnedState.DISCARDED) else expected.owned,
                    indexed = if (discard) null else expected.indexed), "plan")
            override fun finishCleanup(expected: NormalizedCommitState, cleaned: NormalizationWorkspace) = update(expected,
                expected.copy(workspace = cleaned), "cleaned")
        }
        val committer = NormalizedCommitProtocol(commitJournal,
            AndroidNormalizedCommitFiles(root, reservations, durable, io), reservations, lock, gate, { false }, {}, ::checkOperation,
            { execution, _, permit, context -> bridge.capture(execution, permit, context).use { it.requireLive() } })
        val adapter = AndroidControlledDirectStorage({ record }, { owned }, ledger,
            { _, _, _ -> when (ledger.lookup(ownerKey)) {
                NormalizationLookup.Absent -> DirectWorkspacePresence.ABSENT
                NormalizationLookup.Unreadable -> DirectWorkspacePresence.UNREADABLE
                is NormalizationLookup.Present -> DirectWorkspacePresence.PRESENT
            } }, lock, {}, { startup }, owner, MovRemuxEngine(clock), bridge, gate, bindings, committer, { false }, ::remux)
        val raf = MirrorReadOnlyIo(io, lock)
        val canonicalOwner = AdoptedCanonicalOutputOwner(root, bindings, ledger, { record }, { owned }, lock,
            { false }, { if (reservations.hasAnyReservations()) throw SaveFailure(SaveError.BUSY) }, ::checkOperation, raf)
        val journal = object : ControlledDirectJournal {
            override fun findByIdempotencyKey(key: String) = record?.takeIf { it.request.idempotencyKey == key }
            override fun read(taskId: String) = record?.takeIf { it.task.taskId == taskId }
            override fun insert(record: SaveRecord, maxEntries: Int) = record.also { this@Harness.record = it }
            override fun listUnfinished(limit: Int) = listOfNotNull(record).filter { !it.task.state.isTerminal }
            override fun listRecent(limit: Int) = listOfNotNull(record)
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean = synchronized(lock) {
                val before = record ?: return@synchronized false
                if (before.task.revision != expectedRevision) return@synchronized false
                val present = when (ledger.lookup(ownerKey)) {
                    NormalizationLookup.Absent -> DirectWorkspacePresence.ABSENT
                    NormalizationLookup.Unreadable -> DirectWorkspacePresence.UNREADABLE
                    is NormalizationLookup.Present -> DirectWorkspacePresence.PRESENT
                }
                if (!NormalizationDurableMutationPolicy.permitsGeneric(before, next, present,
                        reservations.hasAnyReservations(), ledger.row)) return@synchronized false
                record = next; true
            }
            override fun compareAndSetProof(expectedRevision: Long, next: SaveRecord, completion: DirectProofCompletion): Boolean = synchronized(lock) {
                val actual = completion as? NormalizationProofCompletion ?: return@synchronized false
                actual.requireOwnedBy(owner)
                if (record != actual.expectedRecord || record!!.task.revision != expectedRevision ||
                    next != record!!.copy(task = record!!.task.copy(revision = expectedRevision + 1),
                        directNormalization = actual.proof, checkpoint = null)) return@synchronized false
                proofCas++; record = next; events += "proof"
                if (throwAfterProof) throw IOException("LOST_PROOF_ACK")
                true
            }
            override fun compareAndSetVerification(expectedRevision: Long, next: SaveRecord, completion: DirectVerificationCompletion): Boolean = synchronized(lock) {
                val actual = adapter.verificationCompletion(completion) ?: return@synchronized false
                if (record != actual.expectedRecord || record!!.task.revision != expectedRevision ||
                    next != record!!.copy(task = record!!.task.copy(revision = expectedRevision + 1),
                        verification = actual.evidence, phase = SavePhase.COMMITTING)) return@synchronized false
                verifyCas++; record = next; events += "verified"; true
            }
            override fun compareAndSetNormalized(expectedRevision: Long, next: SaveRecord, publication: NormalizedPublicationHandle) =
                committer.publish(expectedRevision, next, publication)
        }
        val verifier = object : ArtifactVerifier {
            override fun requiresProcessRecovery() = canonicalOwner.requiresProcessRecovery() || (nativeOutstanding && nativeReturned)
            override fun verify(artifact: StagingArtifact, output: ArtifactOutputDescriptor, context: OperationContext): VerificationEvidence {
                if (output is ArtifactOutputDescriptor.Transferred) return verify(artifact, output.transfer, context)
                if (output is ArtifactOutputDescriptor.ConvertedDirect) {
                    normalizedVerifications++
                    // Synthetic scanner/native seam: actual production binding/RAF owner is used,
                    // but these host observations explicitly do not claim a real Android decode.
                    if (bypassOwnedRead) return convertedEvidence(output.conversionProof)
                    val adapter = DirectConvertedAndroidVerifier(canonicalOwner,
                        { nativeOutstanding = true; nativeReturned = true }, { read ->
                            BorrowedAvcAacInspection(read) { borrowed, resources, expected, checkpoint ->
                                checkpoint()
                                val buffer = ByteBuffer.allocate(borrowed.extentBytes.toInt())
                                var offset = 0L
                                while (buffer.hasRemaining()) {
                                    val count = borrowed.channel.read(buffer, offset)
                                    check(count > 0); offset += count
                                }
                                assertArrayEquals(canonical, buffer.array())
                                MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(buffer.array()))
                                val fakeCodec = Any()
                                resources.add(fakeCodec) { events += "synthetic-native-release" }
                                // Synthetic native body only. No actual codec or device claim.
                                AvcAacOutputObservations(expected.durationUs, expected.width, expected.height, 0,
                                    0, 66_666, 3, 0, 3, 0, maximumFixedVideoDeviationUs = 0)
                            }
                        })
                    return adapter.inspect(artifact, output, context) { checkOperation(context) }.also { afterOwnedRead() }
                }
                output as ArtifactOutputDescriptor.NormalizedDirect
                normalizedVerifications++
                val lease = canonicalOwner.acquire(artifact, output, context)
                val observed: MovProfile
                val bytes: ByteArray
                try {
                    lease.revalidate()
                    val input = lease.readOnlyInput
                    bytes = ByteArray(input.length().toInt()); input.readFully(bytes)
                    observed = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes))
                    lease.revalidate()
                } finally { lease.close() }
                check(lease.releaseConfirmed)
                val proof = output.normalizationProof
                val c = observed.conservation
                return VerificationEvidence(artifact.artifactId, bytes.size.toLong(), normalizationTestFingerprint(bytes).sha256,
                    true, 1, if (c.audio == null) 0 else 1, c.endpoint.minimumUs / 1000, observed.width, observed.height, false,
                    DecodePosition.entries.toSet(), c.audio != null && audioReadable, directNormalizationProof = DirectNormalizationVerificationProof(
                        proof.normalizationId, proof.taskId, proof.sourceIdentity, proof.artifactId, proof.commitId, proof.digest(), c,
                        DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true)))
            }
            override fun verify(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext): VerificationEvidence {
                legacyVerifications++; lastLegacyContext = context
                if (cancelBeforeLegacyIssue) throw SaveFailure(SaveError.CANCELLED)
                val actualRelease = adapter.onLegacyReadIssued(artifact, context)
                nativeOutstanding = true; nativeReturned = false
                val released: (Boolean) -> Unit = { confirmed ->
                    actualRelease(confirmed)
                    if (confirmed) nativeOutstanding = false
                }
                pendingLegacyRelease = released
                if (uncertainLegacyRelease) released(false)
                else if (confirmLegacyRelease) { released(true); legacyReleases++ }
                nativeReturned = true
                return VerificationEvidence(artifact.artifactId, raw.size.toLong(), normalizationTestFingerprint(raw).sha256,
                    true, 1, 0, 100, 16, 16, false, DecodePosition.entries.toSet(), true)
            }
        }
        val store = object : PrivateMediaStore {
            override fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact {
                check(taskId == "task" && commitId == "task"); return artifact
            }
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                check(ledger.row == null); genericCommits++
                owned = owned.copy(state = OwnedState.INDEXED, evidence = artifact.evidence)
                return owned.receipt().also { indexed = it }
            }
            override fun inspectCommitted(commitId: String, context: OperationContext) =
                indexed?.let { CommitInspection(it, true, true, true) }
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) { check(ledger.row == null) }
        }
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++; progress.onBytes(raw.size.toLong()); events += "transfer-release"; return transfer
            }
        }, SerialArtifactVerifier(verifier, clock), store, clock, limits, admission = admission,
            controlledDirect = ControlledDirectPipeline(gate, adapter, enabled, conversionExecutionEnabled = conversionEnabled))
        init {
            io.directory(File(root, "staging")); io.directory(File(root, "media")); io.file(primary, raw)
            io.before = { check(!Thread.holdsLock(lock)) { "IO_INSIDE_OWNER_MONITOR" } }
        }
        fun operation(id: String) = OperationContext(id, 100_000, CancellationSource(), resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))
        fun run(): SaveRecord {
            val claim = admission.tryAcquireDirect()!!.also { lastClaim = it }
            assertTrue(coordinator.submit(SaveRequest("task", "key", source), operation("operation"), claim) is SaveSubmission.Accepted)
            return try { coordinator.execute("task", claim)!! } finally { retirement.request(claim) }
        }
        fun seedRaw(phase: SavePhase, startup: Boolean) {
            record = SaveRecord(SaveRequest("task", "key", source).asDurableReference(),
                TaskSnapshot("task", 7, TaskState.WAITING_USER, TaskMethod.DIRECT,
                    waitingUser = UserWait(90_000, ResumeAction.REVIEW_RECOVERY)), "task", "previous", 100_000, 0,
                phase = phase, artifact = artifact, transfer = transfer, bytesTransferred = raw.size.toLong(), waitReason = SaveWaitReason.RECOVERY)
            this.startup = if (startup) record else null
        }
        fun seedNormalized(policy: Int, verified: Boolean, catalog: OwnedState,
            lifecycle: NormalizationLifecycle = NormalizationLifecycle.ADOPTED) {
            seedRaw(if (verified) SavePhase.COMMITTING else if (lifecycle == NormalizationLifecycle.ADOPTED)
                SavePhase.VERIFYING else SavePhase.ADOPTING, startup = false)
            val profile = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(canonical))
            val c = profile.conservation
            val id = "22222222-2222-4222-8222-222222222222"
            val proof = DirectNormalizationProof(id, "task", source.sourceIdentity, artifact.artifactId, artifact.commitId,
                raw.size.toLong(), normalizationTestFingerprint(raw).sha256, sourceTransferFingerprint(transfer),
                canonical.size.toLong(), normalizationTestFingerprint(canonical).sha256, c, policyRevision = policy)
            val evidence = VerificationEvidence(artifact.artifactId, canonical.size.toLong(), proof.outputSha256, true, 1,
                if (audio) 1 else 0, c.endpoint.minimumUs / 1000, c.width, c.height, false,
                DecodePosition.entries.toSet(), audio, directNormalizationProof = DirectNormalizationVerificationProof(
                    id, "task", source.sourceIdentity, artifact.artifactId, artifact.commitId, proof.digest(), c,
                    DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true)))
            record = record!!.copy(directNormalization = proof, verification = if (verified) evidence else null)
            startup = record
            ledger.row = NormalizationWorkspace(NormalizationKey(ownerKey, id), 5, lifecycle,
                NormalizationDisposition.CONTINUE, normalizationTestFingerprint(raw), sourceTransferFingerprint(transfer),
                normalizationTestFingerprint(canonical), proof.digest())
            owned = owned.copy(state = catalog, evidence = if (catalog == OwnedState.STAGING) null else evidence)
            indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null
            val generation = File(File(File(root, "staging"), artifact.artifactId + ".normalization"), id)
            if (lifecycle == NormalizationLifecycle.ADOPTED) {
                io.file(File(generation, "raw.bin"), raw)
                io.remove(primary)
                io.file(if (catalog == OwnedState.STAGING) primary else File(File(root, "media"), "$mediaId.mp4"), canonical)
            } else {
                check(!verified && catalog == OwnedState.STAGING)
                io.file(File(generation, "output.mp4"), canonical)
            }
        }
        fun convertedEvidence(proof: DirectConversionProof): VerificationEvidence =
            VerificationEvidence(artifact.artifactId, canonical.size.toLong(), proof.outputSha256, true, 1, 0, 100, 16, 16, false,
                DecodePosition.entries.toSet(), false, directConversionProof = DirectConversionVerificationProof(
                    proof.normalizationId, proof.taskId, proof.sourceIdentity, proof.artifactId, proof.commitId, proof.digest(),
                    proof.outputSizeBytes, proof.outputSha256, 3, 0, 66_666, 100_000, 0, null, true, true, true))
        fun seedConverted(verified: Boolean, catalog: OwnedState,
            lifecycle: NormalizationLifecycle = NormalizationLifecycle.ADOPTED,
            layout: NormalizationLayout = if (lifecycle == NormalizationLifecycle.ADOPTED) NormalizationLayout.ADOPTED else NormalizationLayout.READY) {
            check(converted && !audio)
            seedRaw(if (verified) SavePhase.COMMITTING else when (lifecycle) {
                NormalizationLifecycle.OUTPUT_READY -> SavePhase.NORMALIZING
                NormalizationLifecycle.ADOPT_INTENT -> SavePhase.ADOPTING
                else -> SavePhase.VERIFYING
            }, startup = false)
            val id = "22222222-2222-4222-8222-222222222222"
            val expected = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 30, 100_000, 3, 0, 66_666, 0), null)
            val proof = DirectConversionProof(id, "task", source.sourceIdentity, artifact.artifactId, artifact.commitId,
                raw.size.toLong(), normalizationTestFingerprint(raw).sha256, sourceTransferFingerprint(transfer),
                canonical.size.toLong(), normalizationTestFingerprint(canonical).sha256, expected,
                DirectConversionEncodingSettings.forExpectation(expected),
                DirectConversionCompletionFacts(true, true, true, true, true, true, null, null, null, null, null, null))
            val evidence = convertedEvidence(proof)
            record = record!!.copy(directConversion = proof, verification = if (verified) evidence else null)
            startup = record
            ledger.row = NormalizationWorkspace(NormalizationKey(ownerKey, id), 5, lifecycle, NormalizationDisposition.CONTINUE,
                normalizationTestFingerprint(raw), sourceTransferFingerprint(transfer), normalizationTestFingerprint(canonical), proof.digest())
            owned = owned.copy(state = catalog, evidence = if (catalog == OwnedState.STAGING) null else evidence)
            indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null
            val generation = File(File(File(root, "staging"), artifact.artifactId + ".normalization"), id)
            when (layout) {
                NormalizationLayout.READY -> io.file(File(generation, "output.mp4"), canonical)
                NormalizationLayout.RAW_MOVED -> {
                    io.remove(primary); io.file(File(generation, "raw.bin"), raw); io.file(File(generation, "output.mp4"), canonical)
                }
                NormalizationLayout.ADOPTED -> {
                    io.remove(primary); io.file(File(generation, "raw.bin"), raw)
                    io.file(if (catalog == OwnedState.STAGING) primary else File(File(root, "media"), "$mediaId.mp4"), canonical)
                }
                else -> error("UNSUPPORTED_FIXTURE_LAYOUT")
            }
        }
        fun normalizedPaths(): List<File> {
            val generation = File(File(File(root, "staging"), artifact.artifactId + ".normalization"), ledger.row!!.key.normalizationId)
            return if (ledger.row!!.lifecycle != NormalizationLifecycle.ADOPTED)
                listOf(primary, File(generation, "output.mp4")) else listOf(File(generation, "raw.bin"),
                if (owned.state == OwnedState.STAGING) primary else File(File(root, "media"), "$mediaId.mp4"))
        }
        fun resume(): SaveRecord {
            val claim = admission.tryAcquireDirect()!!.also { lastClaim = it }
            assertNotNull(coordinator.resume("task", operation("resume"), claim))
            beforeResumeExecution()
            return try { coordinator.execute("task", claim)!! } finally { retirement.request(claim) }
        }
        fun checkOperation(context: OperationContext) { if (context.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED) }
        fun remux(files: MovRemuxFiles, context: OperationContext, limits: SaveLimits): MovRemuxEvidence {
            remuxes++; checkOperation(context)
            val profile = MovProfileProbe.probeQuickTime(OriginalMovFixtures.input(raw))
            files.openSource().use { input ->
                val read = ByteArray(raw.size); input.readAt(0, read, 0, read.size); assertArrayEquals(raw, read)
                files.openEmptyOutput().use { output -> assertEquals(canonical.size, output.write(ByteBuffer.wrap(canonical))) }
                files.openFinalizedOutput().use { output ->
                    val readOutput = ByteArray(canonical.size); output.readAt(0, readOutput, 0, readOutput.size)
                    assertArrayEquals(canonical, readOutput)
                }
            }
            val out = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(canonical))
            assertEquals(profile.conservation, out.conservation)
            afterRemux()
            return MovRemuxEvidence(raw.size.toLong(), normalizationTestFingerprint(raw).sha256,
                canonical.size.toLong(), normalizationTestFingerprint(canonical).sha256, profile.conservation)
        }
        override fun close() { check(io.handles.all { !it.active }); raf.dispose() }
    }

    /** Genuine readonly RAFs with metadata backed by the same deterministic descriptor fixture. */
    private class MirrorReadOnlyIo(val memory: MemoryNormalizationDescriptorIo, val lock: Any) : AdoptedCanonicalRafIo {
        private data class Open(val temp: File, val descriptor: FileDescriptor, val stat: NormalizationDescriptorIo.Stat)
        private val opened = IdentityHashMap<RandomAccessFile, Open>()
        private val descriptors = IdentityHashMap<FileDescriptor, NormalizationDescriptorIo.Stat>()
        var opens = 0; var closes = 0
        private fun offLock() { check(!Thread.holdsLock(lock)) { "RAF_IO_INSIDE_OWNER_MONITOR" } }
        override fun lstat(path: File): NormalizationDescriptorIo.Stat? { offLock(); return memory.lstat(path) }
        override fun openReadOnly(path: File): RandomAccessFile {
            offLock(); val temp = File.createTempFile("owned-adapter-readonly-", ".bin")
            temp.writeBytes(memory.bytes(path))
            val input = RandomAccessFile(temp, "r")
            val metadata = memory.lstat(path)!!
            opened[input] = Open(temp, input.fd, metadata); descriptors[input.fd] = metadata; opens++
            return input
        }
        override fun descriptor(input: RandomAccessFile): FileDescriptor { offLock(); return input.fd }
        override fun fstat(descriptor: FileDescriptor): NormalizationDescriptorIo.Stat { offLock(); return descriptors[descriptor]!! }
        override fun valid(descriptor: FileDescriptor): Boolean { offLock(); return descriptor.valid() }
        override fun close(input: RandomAccessFile) {
            offLock(); val held = opened.remove(input)!!; input.close(); descriptors.remove(held.descriptor)
            check(held.temp.delete()); closes++
        }
        override fun openDirectory(path: File): AdoptedCanonicalRafIo.Directory {
            offLock()
            return object : AdoptedCanonicalRafIo.Directory {
                override fun children(maximum: Int): List<String> { offLock(); return memory.children(path, maximum, {}, {}) }
                override fun close() { offLock() }
            }
        }
        fun dispose() { for (input in opened.keys.toList()) close(input) }
    }
}
