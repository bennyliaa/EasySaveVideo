package com.easysavevideo.app.save.storage

import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Production owner with deterministic descriptors and synthetic conservation, not remux/native acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NormalizationAudioProofPolicyTest {
    private fun audio(rate: Int, channels: Int, count: Long = 3): DirectRemuxConservation {
        val clock = DirectMovClock.audio(rate, count)
        val hash = "b".repeat(64)
        return normalizationTestConservation().copy(audio = DirectTrackConservation(DirectSampleKind.AAC_LC,
            hash, count, count * 8, hash, hash, hash, 0,
            DirectSampleIdentity(count - 1, clock.presentationTimeUs(count - 1), 8, hash, 1), clock.endpoint),
            audioSampleRate = rate, audioChannelCount = channels)
    }
    private fun completed(f: NormalizationDescriptorFixture): OwnedMovRemuxAttempt {
        val attempt = f.prepare()
        val source = attempt.openSource()
        attempt.openEmptyOutput().use { it.write(ByteBuffer.wrap(f.outputBytes)) }
        attempt.openFinalizedOutput().close()
        source.close()
        return attempt
    }

    @Test fun actualOwnerIssuesExplicitAudioTwoAndSilentOneFromRealReleasedAttempt() {
        val profiles = listOf(normalizationTestConservation()) +
            listOf(32_000, 44_100, 48_000).flatMap { rate -> (1..2).map { audio(rate, it) } }
        for (profile in profiles) {
            val f = NormalizationDescriptorFixture(); val attempt = completed(f)
            f.attempts.finishOwned(attempt, f.evidence().copy(conservation = profile), f.context).use { completion ->
                completion.requireOwnedBy(f.attempts)
                assertEquals(if (profile.audio == null) 1 else 2, completion.proof.policyRevision)
                assertTrue(completion.proof.isCurrentPolicy())
                assertEquals(profile, completion.proof.conservation)
                assertEquals(completion.proof.digest(), f.ledger.row!!.proofSha256)
                assertEquals(NormalizationLifecycle.OUTPUT_READY, f.ledger.row!!.lifecycle)
                assertTrue(f.io.handles.all { !it.active })
                assertTrue(f.attempts.hasAnyLeases())
            }
            assertFalse(f.attempts.hasAnyLeases())
            assertThrows(Exception::class.java) { f.attempts.finishOwned(attempt, f.evidence(), f.context) }
        }
    }

    @Test fun finiteAudioClockMismatchCannotIssueProofOrMutateReadyLedger() {
        val good = audio(48_000, 2)
        val track = good.audio!!
        val malformed = listOf(good.copy(audioSampleRate = 44_100),
            good.copy(audio = track.copy(lastSample = track.lastSample.copy(presentationTimeUs = track.lastSample.presentationTimeUs + 1))),
            good.copy(audio = track.copy(endpoint = DirectTrackEndpoint(track.endpoint.minimumUs + 1, track.endpoint.maximumUs + 1))))
        for (profile in malformed) {
            val f = NormalizationDescriptorFixture(); val attempt = completed(f); val before = f.ledger.row
            assertThrows(Exception::class.java) { f.attempts.finishOwned(attempt, f.evidence().copy(conservation = profile), f.context) }
            assertEquals(before, f.ledger.row)
            assertNull(f.ledger.row!!.proofSha256); assertFalse(f.attempts.hasAnyLeases())
            assertThrows(Exception::class.java) { f.attempts.finishOwned(attempt, f.evidence().copy(conservation = good), f.context) }
        }
    }

    @Test fun directOwnerAdoptionCannotReviveSelfConsistentHistoricalAudioOne() {
        for (intent in listOf(false, true)) {
            val f = NormalizationDescriptorFixture(); val attempt = completed(f)
            val current = f.attempts.finish(attempt, f.evidence().copy(conservation = audio(48_000, 2)), f.context)
            val old = current.copy(policyRevision = 1)
            // Model an old persisted tuple. No production mutation or upgrade API is introduced.
            f.record = f.record!!.copy(directNormalization = old, phase = SavePhase.ADOPTING)
            f.authority = f.authority.copy(proofSha256 = old.digest())
            f.ledger.row = f.ledger.row!!.copy(proofSha256 = old.digest(), lifecycle =
                if (intent) NormalizationLifecycle.ADOPT_INTENT else NormalizationLifecycle.OUTPUT_READY)
            val before = f.ledger.row; val calls = f.io.calls.size
            if (intent) assertThrows(Exception::class.java) { f.attempts.adopt(attempt.key, f.context) }
            else assertThrows(Exception::class.java) { f.attempts.recordAdoptionIntent(attempt.key, old, f.context) }
            assertEquals(before, f.ledger.row); assertEquals(calls, f.io.calls.size)
            assertArrayEquals(f.rawBytes, f.io.bytes(f.primary))
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun expectedAudioCannotBeDroppedAndDoesNotSelectPolicyWithoutActualAudio() {
        val f = NormalizationDescriptorFixture()
        f.record = f.record!!.copy(request = f.record!!.request.copy(input = SaveInput.Network(
            f.record!!.request.resource.copy(expectedAudio = true))))
        val attempt = completed(f)
        assertThrows(Exception::class.java) { f.attempts.finishOwned(attempt, f.evidence(), f.context) }
        assertNull(f.ledger.row!!.proofSha256)
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
    }
}
