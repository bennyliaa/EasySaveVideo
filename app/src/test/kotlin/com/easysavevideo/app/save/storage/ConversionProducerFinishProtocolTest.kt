package com.easysavevideo.app.save.storage

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Synthetic PRIVATE-STATE protocol seams only. Real owner/Scope/session/retained-descriptor
 * cleanup is exercised, but reflection stages the post-run facts. These tests do NOT execute
 * the actual engine, decode VP8, establish playable output, or prove real producer success.
 * There is deliberately no production outcome/facts injection API.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ConversionProducerFinishProtocolTest {
    private class Staged(val h: OwnedTranscodeTestFixture, outstandingMasters: Int = 0) {
        val attempt = h.prepare()
        val binding = attempt.bindWebmInputOnce()
        val session = h.session()
        private val source = attempt.ownedSource(binding)
        val transport = WebmTransportFacts(h.f.raw.size, 100_000,
            listOf(WebmTrackTransport(1, C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_VP8, 16, 16,
                -1, -1, emptyList(), 3, 0, 66_666, 0, 33_333_333)), maxOf(3, outstandingMasters), outstandingMasters, 0, 12, 3, 100_000)
        val output = WebmDirectCandidatePolicy.selectSilent(transport, attempt.outputMaximum)
        val observation = TransformerSourceObservation(TransformerVideoObservation(3, 0, 66_666, 100_000, 30, 0), null)
        val facts = TransformerEngineFacts(h.f.outputBytes.size.toLong(), 3, 0, observation)
        init {
            attempt.bindSession(binding, session)
            assertTrue(h.entered(session) {
                session.retain(source)
                val target = attempt.ownedTarget(binding)
                val writer = target.openEmptyWriter()
                session.retainWriter(target)
                writer.write(ByteBuffer.wrap(h.f.outputBytes))
                writer.beginFinalization(); writer.force(); writer.close()
            } is TransformerOutcome.Completed)
            attempt.sealResources()
            // A fabricated DTO is used ONLY to test consumers of a synthetic private state.
            set(attempt, "runEngine", allocate(TransformerEngine::class.java))
            set(attempt, "runPair", binding); set(attempt, "runSession", session)
            set(attempt, "runSource", Proxy.newProxyInstance(MediaSource::class.java.classLoader,
                arrayOf(MediaSource::class.java)) { _, _, _ -> error("RETIRED_MEDIA_SOURCE_CALLED") })
            set(attempt, "runTarget", get(attempt, "target"))
            set(attempt, "runOutput", output); set(attempt, "runTransport", transport)
            set(attempt, "runCompletedTransport", transport)
            set(attempt, "runTimeline", TransformerSourceTimeline(100_000, 30, 0))
            set(attempt, "actualRunOutcome", TransformerOutcome.Completed(facts))
            phase(attempt, "SEALED")
        }
        fun finish() = h.f.attempts.finishOwnedTranscode(attempt)
        fun assertRetainedNoProof(lifecycle: NormalizationLifecycle = NormalizationLifecycle.WRITING) {
            assertEquals(lifecycle, h.f.ledger.row!!.lifecycle)
            assertNull(h.f.record!!.directConversion); assertNull(h.f.record!!.directNormalization)
            assertArrayEquals(h.f.outputBytes, h.f.io.bytes(h.f.outputFile(attempt.key)))
            assertArrayEquals(h.f.rawBytes, h.f.io.bytes(h.f.primary))
            assertFalse(h.f.attempts.hasAnyLeases())
        }
    }

    @Test fun syntheticSealedStateHandsOffSameTokenOnceAndOuterUseCloseCannotRevokeIt() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        val completion = staged.attempt.use { staged.finish() }
        assertSame(h.token, completion.reservation)
        assertEquals(NormalizationIoReservations.Stage.PROOF_HANDOFF, h.token.stage)
        completion.requireOwnedBy(h.f.attempts)
        assertEquals(3L, completion.proof.expectation.video.displayedFrameCount)
        assertEquals(0L, completion.proof.expectation.video.maximumFrameDeviationUs)
        assertNull(completion.proof.expectation.audio); assertNull(completion.proof.settings.audioBitrate)
        assertEquals(h.f.output, completion.workspace.output)
        assertEquals(completion.proof.digest(), h.f.ledger.row!!.proofSha256)
        assertEquals(NormalizationLifecycle.OUTPUT_READY, h.f.ledger.row!!.lifecycle)
        staged.attempt.close(); staged.attempt.close()
        assertThrows(Exception::class.java) { staged.finish() }
        completion.requireOwnedBy(h.f.attempts)
        // Model only the already-acknowledged exact Save result. Closing must not demand old Save.
        val prior = h.f.record!!
        h.f.record = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
            directConversion = completion.proof, checkpoint = null)
        completion.close(); completion.close()
        assertFalse(h.token.registered); assertFalse(h.f.attempts.hasAnyLeases())
        assertEquals(NormalizationLifecycle.OUTPUT_READY, h.f.ledger.row!!.lifecycle)
    }

    @Test fun retainedUnknownMasterCountsAreNotReinterpretedAsMissingExtentEof() {
        // Synthetic post-run transport parity only. Actual native producer success is untested.
        for (count in listOf(1, 2, 4, 16)) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h, count)
            staged.finish().use { assertTrue(it.proof.completion.rawExtentEof); it.requireOwnedBy(h.f.attempts) }
            staged.attempt.close(); assertFalse(h.token.registered)
        }
    }
    @Test fun changedRetainedUnknownMasterCountCannotMatchTheOriginalTransport() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h, 1); val expected = staged.transport
        set(staged.attempt, "runCompletedTransport", WebmTransportFacts(expected.inputExtent,
            expected.declaredContainerDurationUs, expected.tracks, expected.maximumPhysicalMasterDepth,
            2, expected.actualUnknownEndCallbacks, expected.checkedHeaders, expected.delegatedPayloads, expected.demuxerDurationUs))
        assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
    }
    @Test fun changedCompletedTimecodeScaleCannotMatchTheOriginalTransport() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val expected = staged.transport
        set(staged.attempt, "runCompletedTransport", WebmTransportFacts(expected.inputExtent,
            expected.declaredContainerDurationUs, expected.tracks, expected.maximumPhysicalMasterDepth,
            expected.outstandingMastersAtEof, expected.actualUnknownEndCallbacks, expected.checkedHeaders,
            expected.delegatedPayloads, expected.demuxerDurationUs, 500_000L))
        assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
    }
    @Test fun syntheticReleasedAudioObservationsCannotMintProofWithoutNativeHeadAcceptance() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        val header = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1.toByte()).put(2.toByte()).putShort(312.toShort())
            .putInt(48_000).putShort(0.toShort()).put(0.toByte()).array()
        fun nativeLong(value: Long) = ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder()).putLong(value).array()
        val original = staged.transport
        val transport = WebmTransportFacts(original.inputExtent, original.declaredContainerDurationUs,
            original.tracks + WebmTrackTransport(2, C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_OPUS, -1, -1, 48_000, 2,
                listOf(header, nativeLong(6_500_000), nativeLong(80_000_000)), 5, 0, 80_000, 0, null),
            original.maximumPhysicalMasterDepth, original.outstandingMastersAtEof, original.actualUnknownEndCallbacks,
            original.checkedHeaders, original.delegatedPayloads, original.demuxerDurationUs, original.timecodeScaleNs)
        val heard = TransformerAudioObservation(0, 100_000, 4_800, 4_800, 48_000, 2,
            codecDelayUs = 6_500, seekPrerollUs = 80_000, discardPaddingUs = 0,
            headMapping = TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN)
        set(staged.attempt, "runTransport", transport); set(staged.attempt, "runCompletedTransport", transport)
        set(staged.attempt, "runOutput", WebmDirectCandidatePolicy.select(transport, staged.attempt.outputMaximum))
        set(staged.attempt, "actualRunOutcome", TransformerOutcome.Completed(staged.facts.copy(audioSamples = 6,
            sourceObservation = staged.observation.copy(audio = heard))))
        val failure = assertThrows(TransformerFailure::class.java) { staged.finish() }
        assertEquals(TransformerError.UNSUPPORTED_PROFILE, failure.code)
        staged.assertRetainedNoProof()
    }

    @Test fun stopDuringFinishIoPreservesStopInsteadOfDerivativeScopeRejection() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); var stopped = false
        h.f.io.before = {
            assertFalse(Thread.holdsLock(h.f.lock))
            if (!stopped) { stopped = true; h.f.cancellation.cancel() }
        }
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) { staged.finish() }.code)
        staged.assertRetainedNoProof()
    }

    @Test fun resourceSealingAloneCannotProduceCompletion() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        set(staged.attempt, "actualRunOutcome", null)
        assertThrows(Exception::class.java) { staged.finish() }
        staged.assertRetainedNoProof()
    }

    @Test fun everyNonSealedPrivateRunStateRejectsWithoutNewWork() {
        for (state in listOf("NEW", "CLAIMED", "BOUND", "WORKING", "OBSERVED", "RELEASED", "FAILED", "FINISHING"))
            OwnedTranscodeTestFixture().run { h, _ ->
                val staged = Staged(h); phase(staged.attempt, state)
                val before = h.f.io.calls.size
                assertThrows(Exception::class.java) { staged.finish() }
                assertEquals(before, h.f.io.calls.size); staged.assertRetainedNoProof()
            }
    }

    @Test fun selectedTerminalFailureAlwaysPrecedesLateOriginalOrLivenessFailure() {
        for (stop in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h)
            val selected = TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)
            set(staged.attempt, "terminalRunFailure", selected)
            set(staged.attempt, "firstOriginalFailure", IOException("LATE_SYNTHETIC_FAILURE"))
            if (stop) h.f.cancellation.cancel()
            assertSame(selected, assertThrows(Throwable::class.java) { staged.finish() })
            staged.assertRetainedNoProof()
        }
    }

    @Test fun failedActualOutcomeAndOriginalFailureCannotBeConvertedToSuccess() {
        for (original in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h); val selected = IOException("SYNTHETIC_ORIGINAL")
            if (original) set(staged.attempt, "firstOriginalFailure", selected)
            else set(staged.attempt, "actualRunOutcome", TransformerOutcome.Failed(selected, TransformerRelease.CONFIRMED))
            assertSame(selected, assertThrows(Throwable::class.java) { staged.finish() })
            staged.assertRetainedNoProof()
        }
    }

    @Test fun audioAndCountTimelineOrCompletedTransportMismatchFailClosed() {
        for (case in 0..5) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h)
            when (case) {
                0 -> set(staged.attempt, "actualRunOutcome", TransformerOutcome.Completed(staged.facts.copy(audioSamples = 1)))
                1 -> set(staged.attempt, "actualRunOutcome", TransformerOutcome.Completed(staged.facts.copy(videoSamples = 2)))
                2 -> set(staged.attempt, "actualRunOutcome", TransformerOutcome.Completed(staged.facts.copy(bytesWritten = 1)))
                3 -> set(staged.attempt, "runTimeline", TransformerSourceTimeline(100_001, 30, 0))
                4 -> set(staged.attempt, "runCompletedTransport", null)
                5 -> set(staged.attempt, "runOutput", staged.output.copy(audioBitrate = 96_000))
            }
            assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
        }
    }

    @Test fun expectedAudioRequestCannotFinishWithSyntheticSilentObservation() {
        val h = OwnedTranscodeTestFixture()
        h.beforePrepare = {
            val record = h.f.record!!
            h.f.record = record.copy(request = record.request.copy(resource = record.request.resource.copy(expectedAudio = true)))
        }
        h.run { fixture, _ ->
            val staged = Staged(fixture)
            assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
        }
    }

    @Test fun allStickyResourceAndSessionFailuresReject() {
        for (case in 0..7) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h)
            when (case) {
                0 -> set(staged.attempt, "outputContentFailed", true)
                1 -> set(staged.attempt, "finalizationFailed", true)
                2 -> set(staged.attempt, "outputResourceUncertain", true)
                3 -> set(staged.attempt, "nativeSourceReleased", false)
                4 -> set(staged.attempt, "nativeWriterReleased", false)
                5 -> staged.session.recordCleanupFailure()
                6 -> staged.session.abandoned.set(true)
                7 -> set(staged.attempt, "outputAbortRequested", true)
            }
            assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
        }
    }

    @Test fun staleSaveLedgerOriginalScopeAndInputRegistrationRejectBeforeFinishIo() {
        for (case in 0..3) OwnedTranscodeTestFixture().run { h, scope ->
            val staged = Staged(h)
            when (case) {
                0 -> h.f.record = h.f.record!!.copy(task = h.f.record!!.task.copy(revision = h.f.record!!.task.revision + 1))
                1 -> h.f.ledger.row = h.f.ledger.row!!.copy(revision = h.f.ledger.row!!.revision + 1)
                2 -> scope.close()
                3 -> set(staged.attempt, "runPair", OwnedWebmInputBinding.createUnregistered(staged.attempt))
            }
            val before = h.f.io.calls.size
            assertThrows(Exception::class.java) { staged.finish() }
            assertEquals(before, h.f.io.calls.size); staged.assertRetainedNoProof()
        }
    }

    @Test fun sameBytesReplacedRawOutputOrParentInodeCannotFinish() {
        for (case in 0..2) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h)
            when (case) {
                0 -> h.f.io.file(h.f.primary, h.f.rawBytes)
                1 -> h.f.io.file(h.f.outputFile(staged.attempt.key), h.f.outputBytes)
                2 -> h.f.io.replaceDirectory(h.f.generation(staged.attempt.key))
            }
            assertThrows(Exception::class.java) { staged.finish() }; staged.assertRetainedNoProof()
        }
    }

    @Test fun writeThenThrowAcknowledgesOnlyExactOutputReadySuccessor() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        h.f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.OUTPUT_READY) throw IOException("AFTER_WRITE") }
        staged.finish().use { completion ->
            completion.requireOwnedBy(h.f.attempts)
            assertEquals(completion.workspace, h.f.ledger.row)
        }
        staged.attempt.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }

    @Test fun unreadableOrUnrelatedReadbackNeverAcknowledgesAndNeverReruns() {
        for (unreadable in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h)
            h.f.ledger.afterWrite = {
                if (it.lifecycle == NormalizationLifecycle.OUTPUT_READY) {
                    if (unreadable) h.f.ledger.unreadable = true else h.f.ledger.row = it.copy(revision = it.revision + 1)
                    throw IOException("AFTER_UNCERTAIN_WRITE")
                }
            }
            assertThrows(Exception::class.java) { staged.finish() }
            assertThrows(Exception::class.java) { staged.finish() }
            staged.assertRetainedNoProof(NormalizationLifecycle.OUTPUT_READY)
        }
    }

    @Test fun lateStopAfterOutputReadyRetainsProoflessReviewEvidence() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        h.f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.OUTPUT_READY) h.f.cancellation.cancel() }
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) { staged.finish() }.code)
        staged.assertRetainedNoProof(NormalizationLifecycle.OUTPUT_READY)
    }

    @Test fun nonzeroReservationObligationBlocksFinishAndDrainsOnlyAfterPositiveRelease() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h)
        val obligation = h.f.attempts.reservations.retain(h.token)
        assertThrows(Exception::class.java) { staged.finish() }
        assertTrue(h.token.registered)
        h.f.attempts.reservations.release(obligation); staged.attempt.close()
        assertFalse(h.token.registered); staged.assertRetainedNoProof()
    }

    @Test fun foreignCopiedClosedCompletionAndExitedScopeNeverAuthorizeCas() = OwnedTranscodeTestFixture().run { h, scope ->
        val staged = Staged(h); val completion = staged.finish()
        val copied = NormalizationConversionProofCompletion(h.f.attempts, completion.proof, completion.reservation,
            completion.expectedRecord, completion.workspace, scope, h.f.context)
        assertThrows(Exception::class.java) { copied.requireOwnedBy(h.f.attempts) }
        assertThrows(Exception::class.java) { completion.requireOwnedBy(NormalizationDescriptorFixture().attempts) }
        completion.requireOwnedBy(h.f.attempts)
        scope.close()
        assertThrows(Exception::class.java) { completion.requireOwnedBy(h.f.attempts) }
        completion.close()
        assertThrows(Exception::class.java) { completion.requireOwnedBy(h.f.attempts) }
        staged.attempt.close(); assertFalse(h.token.registered)
    }

    @Test fun closeWinsWhileFinishIoIsBlockedAndCannotIssueCompletion() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var blocked = false
        h.f.io.before = {
            assertFalse(Thread.holdsLock(h.f.lock))
            if (!blocked) { blocked = true; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val pool = Executors.newSingleThreadExecutor()
        val result = pool.submit<Throwable?> { try { staged.finish(); null } catch (failure: Throwable) { failure } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            staged.attempt.close(); release.countDown()
            assertNotNull(result.get(5, TimeUnit.SECONDS)); staged.assertRetainedNoProof()
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun handoffWinsSharedOwnerLockBeforeConcurrentOldCloseClaim() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val handedOff = CountDownLatch(1); val finishRegistration = CountDownLatch(1)
        val closeStarted = CountDownLatch(1); val closeEnded = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        val originals = get(h.f.attempts, "conversionCompletions") as MutableSet<NormalizationConversionProofCompletion>
        // Test-only interception of private registry insertion, after same-token handoff, while
        // ownerLock is still held. Production has an ordinary identity set and no test callback.
        set(h.f.attempts, "conversionCompletions", object : MutableSet<NormalizationConversionProofCompletion> by originals {
            override fun add(element: NormalizationConversionProofCompletion): Boolean {
                assertTrue(Thread.holdsLock(h.f.lock)); handedOff.countDown()
                check(finishRegistration.await(5, TimeUnit.SECONDS)); return originals.add(element)
            }
        })
        val pool = Executors.newFixedThreadPool(2)
        val finish = pool.submit<NormalizationConversionProofCompletion> { staged.finish() }
        try {
            assertTrue(handedOff.await(3, TimeUnit.SECONDS))
            val close = pool.submit { closeStarted.countDown(); staged.attempt.close(); closeEnded.countDown() }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS)); assertFalse(closeEnded.await(50, TimeUnit.MILLISECONDS))
            finishRegistration.countDown()
            val completion = finish.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS)
            completion.requireOwnedBy(h.f.attempts); assertTrue(h.token.registered)
            completion.close(); assertFalse(h.token.registered)
        } finally { finishRegistration.countDown(); pool.shutdownNow() }
    }

    @Test fun rejectedConcurrentFinishCannotRevokeFirstFinishReservation() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var blocked = false
        h.f.io.before = {
            assertFalse(Thread.holdsLock(h.f.lock))
            if (!blocked) { blocked = true; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val pool = Executors.newSingleThreadExecutor()
        val winner = pool.submit<NormalizationConversionProofCompletion> { staged.finish() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertThrows(Exception::class.java) { staged.finish() }
            assertEquals(NormalizationIoReservations.Stage.FINISH, h.token.stage)
            h.token.requireLive()
            release.countDown()
            winner.get(5, TimeUnit.SECONDS).use { it.requireOwnedBy(h.f.attempts) }
            staged.attempt.close(); assertFalse(h.token.registered)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun actualRecoveryAdapterInspectsExactConvertedReadyAndClassifiesAdoption() = OwnedTranscodeTestFixture().run { h, scope ->
        val staged = Staged(h)
        staged.finish().use { completion ->
            val prior = completion.expectedRecord
            h.f.record = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1), directConversion = completion.proof,
                checkpoint = null)
            h.f.authority = h.f.authority.copy(proofSha256 = completion.proof.digest())
        }
        staged.attempt.close()
        val adapter = recoveryAdapter(h)
        assertEquals(DirectWorkspacePresence.PRESENT,
            adapter.inspectBeforeNetwork(h.f.owner.taskId, h.f.record!!.artifact, h.f.owner.commitId))
        val before = h.f.io.calls.size
        val savedRecord = h.f.record; val savedWorkspace = h.f.ledger.row
        assertEquals(DirectRecoveryAction.ADOPT,
            adapter.inspectRecovery(h.f.record!!, scope.execution, scope.permit, h.f.context))
        assertTrue(h.f.io.calls.drop(before).any { it.operation == "pread" })
        assertEquals(savedRecord, h.f.record); assertEquals(savedWorkspace, h.f.ledger.row)
        assertFalse(h.f.attempts.hasAnyLeases())
        assertEquals(NormalizationLifecycle.OUTPUT_READY, h.f.ledger.row!!.lifecycle)
        assertNull(h.f.record!!.verification); assertNull(h.f.record!!.receipt)
    }

    @Test fun mismatchedConversionAndProoflessReadyOrWritingRemainPresentAndReviewOnly() {
        for (case in 0..2) OwnedTranscodeTestFixture().run { h, scope ->
            val staged = Staged(h)
            if (case == 2) staged.attempt.close() else {
                staged.finish().use { completion ->
                    if (case == 0) {
                        val prior = completion.expectedRecord
                        h.f.record = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
                            directConversion = completion.proof, checkpoint = null)
                        h.f.ledger.row = completion.workspace.copy(proofSha256 = "e".repeat(64))
                    }
                }
                staged.attempt.close()
            }
            val adapter = recoveryAdapter(h)
            assertEquals(DirectWorkspacePresence.PRESENT,
                adapter.inspectBeforeNetwork(h.f.owner.taskId, h.f.record!!.artifact, h.f.owner.commitId))
            assertEquals(DirectRecoveryAction.REVIEW,
                adapter.inspectRecovery(h.f.record!!, scope.execution, scope.permit, h.f.context))
            assertNull(h.f.record!!.verification); assertNull(h.f.record!!.receipt)
            assertFalse(h.f.attempts.hasAnyLeases())
        }
    }
    private fun recoveryAdapter(h: OwnedTranscodeTestFixture): AndroidControlledDirectStorage {
        val clock = com.easysavevideo.core.model.EpochClock { h.f.now }
        val committer = NormalizedCommitProtocol(unavailable(NormalizedCommitJournal::class.java),
            unavailable(NormalizedCommitFiles::class.java), h.f.attempts.reservations, h.f.lock,
            h.gate, { false }, {}, h.f::checkOperation, { _, _, _, _ -> error("UNEXPECTED_COMMIT_AUTHORITY") })
        return AndroidControlledDirectStorage({ h.f.record }, {
            OwnedMedia(h.f.owner.taskId, h.f.record!!.artifact!!, "33333333-3333-4333-8333-333333333333", OwnedState.STAGING)
        }, h.f.ledger, { _, _, _ -> when (h.f.ledger.lookup(h.f.owner)) {
            NormalizationLookup.Absent -> DirectWorkspacePresence.ABSENT
            NormalizationLookup.Unreadable -> DirectWorkspacePresence.UNREADABLE
            is NormalizationLookup.Present -> DirectWorkspacePresence.PRESENT
        } }, h.f.lock, {}, { null }, h.f.attempts,
            com.easysavevideo.app.save.normalization.MovRemuxEngine(clock), h.bridge, h.gate,
            AdoptedVerificationBindings(h.gate, executionBridge = h.bridge), committer, { false },
            { _, _, _ -> error("UNEXPECTED_MOV_REMUX") })
    }

    /** Fake journal only; its SQL body is NOT AndroidSaveStorage. Actual persistExact is invoked. */
    private class ProtocolJournal(val staged: Staged) : ControlledDirectJournal {
        val h = staged.h
        var writes = 0
        var reads = 0
        var beforeWrite: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        lateinit var held: NormalizationConversionProofCompletion
        override fun findByIdempotencyKey(key: String) = h.f.record?.takeIf { it.request.idempotencyKey == key }
        override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord = error("UNEXPECTED_INSERT")
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean = error("GENERIC_CAS_FORBIDDEN")
        override fun listUnfinished(limit: Int) = listOfNotNull(h.f.record)
        override fun listRecent(limit: Int) = listOfNotNull(h.f.record)
        override fun read(taskId: String): SaveRecord? {
            reads++
            assertTrue(h.token.registered)
            assertFalse((get(held, "closed") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertFalse((get(h.scope, "closed") as java.util.concurrent.atomic.AtomicBoolean).get())
            beforeRead()
            return h.f.record?.takeIf { it.task.taskId == taskId }
        }
        override fun compareAndSetConversionProof(expectedRevision: Long, next: SaveRecord,
            completion: DirectConversionProofCompletion): Boolean = synchronized(h.f.lock) {
            val owned = completion as? NormalizationConversionProofCompletion ?: return@synchronized false
            owned.requireOwnedBy(h.f.attempts)
            val prior = h.f.record ?: return@synchronized false
            if (expectedRevision == Long.MAX_VALUE || prior != owned.expectedRecord || prior.task.revision != expectedRevision ||
                next != prior.copy(task = prior.task.copy(revision = expectedRevision + 1), directConversion = owned.proof,
                    checkpoint = null)) return@synchronized false
            beforeWrite(); writes++
            h.f.record = SaveRecordCodec.decode(SaveRecordCodec.encode(next))
            afterWrite()
            true
        }
        fun persist(proposal: SaveRecord, completion: NormalizationConversionProofCompletion): SaveRecord {
            held = completion
            val coordinator = SaveCoordinator(this, unavailable(DirectTransferPort::class.java),
                ArtifactVerifier { _, _, _ -> error("UNEXPECTED_VERIFY") }, unavailable(PrivateMediaStore::class.java),
                com.easysavevideo.core.model.EpochClock { h.f.now }, h.f.limits)
            val method = SaveCoordinator::class.java.getDeclaredMethod("persistExact", SaveRecord::class.java,
                kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            val transaction = { compareAndSetConversionProof(completion.expectedRecord.task.revision, proposal, completion) }
            return try { method.invoke(coordinator, proposal, transaction) as SaveRecord }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }
    }

    @Test fun fixtureCasRejectsWrongRevisionMutationAndForgedCompletionWithoutLosingOwner() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val completion = staged.finish(); val prior = completion.expectedRecord
        val proposal = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
            directConversion = completion.proof, checkpoint = null)
        val journal = ProtocolJournal(staged)
        val forged = object : DirectConversionProofCompletion {
            override val proof = completion.proof
            override fun close() = Unit
        }
        assertFalse(journal.compareAndSetConversionProof(prior.task.revision, proposal, forged))
        assertFalse(journal.compareAndSetConversionProof(prior.task.revision + 1, proposal, completion))
        assertFalse(journal.compareAndSetConversionProof(Long.MAX_VALUE, proposal, completion))
        for (bad in listOf(proposal.copy(bytesTransferred = proposal.bytesTransferred + 1),
            proposal.copy(phase = SavePhase.ADOPTING), proposal.copy(error = SaveError.RECOVERY_REQUIRED),
            proposal.copy(deadlineEpochMillis = proposal.deadlineEpochMillis + 1))) {
            assertFalse(journal.compareAndSetConversionProof(prior.task.revision, bad, completion))
        }
        assertEquals(prior, h.f.record); assertEquals(0, journal.writes)
        completion.requireOwnedBy(h.f.attempts); completion.close(); staged.attempt.close()
    }

    @Test fun oneProposalClearsRawCheckpointWithoutAnyIntermediateProofBearingRawCheckpoint() {
        val h = OwnedTranscodeTestFixture()
        h.beforePrepare = {
            val prior = h.f.record!!; val transfer = prior.transfer!!
            h.f.record = prior.copy(checkpoint = DownloadCheckpoint(transfer.artifactId,
                prior.request.networkSourceMetadata!!.sourceIdentity, transfer.bytesOnDisk, "\"fixture\"", transfer.bytesOnDisk))
        }
        h.run { fixture, _ ->
            val staged = Staged(fixture); val completion = staged.finish(); val prior = completion.expectedRecord
            assertNotNull(prior.checkpoint)
            assertThrows(IllegalArgumentException::class.java) { prior.copy(directConversion = completion.proof) }
            val proposal = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
                directConversion = completion.proof, checkpoint = null)
            val journal = ProtocolJournal(staged)
            completion.use { assertEquals(proposal, journal.persist(proposal, completion)) }
            assertNull(fixture.f.record!!.checkpoint); assertEquals(prior.transfer, fixture.f.record!!.transfer)
            assertEquals(prior.bytesTransferred, fixture.f.record!!.bytesTransferred)
            assertEquals(prior.phase, fixture.f.record!!.phase)
            staged.attempt.close()
        }
    }

    @Test fun actualPersistExactHoldsOriginalCompletionAndScopeThroughWriteThenThrowReadback() {
        for (throwAfter in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h); val completion = staged.attempt.use { staged.finish() }
            val prior = completion.expectedRecord
            val proposal = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
                directConversion = completion.proof, checkpoint = null)
            val journal = ProtocolJournal(staged)
            if (throwAfter) journal.afterWrite = { throw IOException("SYNTHETIC_WRITE_THEN_THROW") }
            completion.use {
                assertEquals(proposal, journal.persist(proposal, completion))
                assertEquals(proposal, h.f.record)
                assertEquals(1, journal.writes); assertEquals(if (throwAfter) 1 else 0, journal.reads)
                assertTrue(h.token.registered)
                // Never demand pre-CAS expectedRecord ownership after acknowledging the new Save.
            }
            assertFalse(h.token.registered)
        }
    }

    @Test fun actualPersistExactCannotAcknowledgePriorUnrelatedRevisionOrUnreadableReadback() {
        for (case in 0..3) OwnedTranscodeTestFixture().run { h, _ ->
            val staged = Staged(h); val completion = staged.finish()
            val prior = completion.expectedRecord
            val proposal = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
                directConversion = completion.proof, checkpoint = null)
            val journal = ProtocolJournal(staged)
            when (case) {
                0 -> journal.beforeWrite = { throw IOException("BEFORE_WRITE") }
                1 -> journal.afterWrite = {
                    h.f.record = proposal.copy(bytesTransferred = proposal.bytesTransferred + 1)
                    throw IOException("SAME_DIGEST_DIFFERENT_RECORD")
                }
                2 -> journal.afterWrite = {
                    h.f.record = proposal.copy(task = proposal.task.copy(revision = proposal.task.revision + 1))
                    throw IOException("LATER_REVISION")
                }
                3 -> {
                    journal.afterWrite = { throw IOException("AFTER_WRITE") }
                    journal.beforeRead = { throw IOException("UNREADABLE") }
                }
            }
            completion.use {
                assertEquals(SaveError.RECOVERY_REQUIRED,
                    assertThrows(SaveFailure::class.java) { journal.persist(proposal, completion) }.code)
                assertEquals(1, journal.reads); assertTrue(h.token.registered)
            }
            staged.attempt.close(); assertFalse(h.token.registered)
            assertEquals(NormalizationLifecycle.OUTPUT_READY, h.f.ledger.row!!.lifecycle)
        }
    }

    @Test fun actualPersistExactAcknowledgesExactWriteBeforeLaterStopWithoutReplayingIt() = OwnedTranscodeTestFixture().run { h, _ ->
        val staged = Staged(h); val completion = staged.finish(); val prior = completion.expectedRecord
        val proposal = prior.copy(task = prior.task.copy(revision = prior.task.revision + 1),
            directConversion = completion.proof, checkpoint = null)
        val journal = ProtocolJournal(staged)
        journal.afterWrite = { h.f.cancellation.cancel(); throw IOException("WRITE_THEN_STOP") }
        completion.use { assertEquals(proposal, journal.persist(proposal, completion)); assertEquals(1, journal.writes) }
        staged.attempt.close(); assertFalse(h.token.registered)
    }

    private companion object {
        fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun set(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
        fun phase(target: OwnedDirectTranscodeAttempt, value: String) {
            val field = target.javaClass.getDeclaredField("runPhase").apply { isAccessible = true }
            field.set(target, requireNotNull(field.type.enumConstants).single { (it as Enum<*>).name == value })
        }
        fun <T : Any> unavailable(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> error("UNEXPECTED_PORT_CALL") }))
        fun <T : Any> allocate(type: Class<T>): T {
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            return requireNotNull(type.cast(unsafe.allocateInstance(type)))
        }
    }
}
