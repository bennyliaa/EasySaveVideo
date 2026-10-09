package com.easysavevideo.app.recording

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure
import com.easysavevideo.recording.RecordingTrack
import org.junit.Assert.*
import org.junit.Test

class RecordingCaptureAccountingTest {
    @Test fun duplicateControlsAreCoalescedButAlternatingCommandsAreNotLost() {
        val mailbox = RecordingControlMailbox()
        repeat(100) { assertTrue(mailbox.offer(true)) }
        assertEquals(listOf(true), mailbox.drain())
        assertTrue(mailbox.offer(false)); assertTrue(mailbox.offer(true)); assertTrue(mailbox.offer(false))
        assertEquals(listOf(false, true, false), mailbox.drain())
        assertTrue(mailbox.drain().isEmpty())
    }

    @Test fun commandFloodHasAHardBound() {
        val mailbox = RecordingControlMailbox()
        repeat(16) { assertTrue(mailbox.offer(it % 2 == 0)) }
        assertFalse(mailbox.offer(true))
        assertEquals(16, mailbox.drain().size)
        assertTrue(mailbox.offer(true))
    }

    @Test fun trackEvidenceIncludesOnlySuccessfulWritesAndExplicitFinalDuration() {
        val track = RecordingMuxTrack(RecordingTrack.VIDEO)
        track.validate(0, 33_333, true)
        assertEquals(0L, track.count) // Validation before a failing native write cannot count a frame.
        track.accepted(0, 33_333, true)
        track.accepted(100_000, 33_333, false)
        track.accepted(200_000, 33_333, true)
        val proof = track.evidence()
        assertEquals(3L, proof.sampleCount)
        assertEquals(2L, proof.syncSampleCount)
        assertEquals(200_000L, proof.lastPtsUs)
        assertEquals(33_333L, proof.lastDurationUs)
        assertEquals(233_333L, proof.endPtsUs)
    }

    @Test fun videoMustStartWithRealKeyframeAtZero() {
        rejected { RecordingMuxTrack(RecordingTrack.VIDEO).accepted(0, 33_333, false) }
        rejected { RecordingMuxTrack(RecordingTrack.VIDEO).accepted(1, 33_333, true) }
    }

    @Test fun duplicateOrBackwardsSamplesFailWithoutClamping() {
        val track = RecordingMuxTrack(RecordingTrack.VIDEO)
        track.accepted(0, 33_333, true); track.accepted(100_000, 33_333, false)
        rejected { track.accepted(100_000, 33_333, false) }
        rejected { track.accepted(99_999, 33_333, false) }
        assertEquals(2L, track.count)
    }

    @Test fun audioCannotInventSyncFramesAndKeepsItsOwnStartOffset() {
        val track = RecordingMuxTrack(RecordingTrack.AUDIO)
        rejected { track.accepted(5_000, 21_333, true) }
        track.accepted(5_000, 21_333, false); track.accepted(26_333, 21_333, false)
        assertEquals(5_000L, track.evidence().firstPtsUs)
        assertEquals(0L, track.evidence().syncSampleCount)
        assertEquals(47_666L, track.endUs)
    }

    @Test fun arithmeticAndInvalidDurationAreRejectedBeforeWrite() {
        val track = RecordingMuxTrack(RecordingTrack.AUDIO)
        rejected { track.validate(-1, 1, false) }
        rejected { track.validate(0, 0, false) }
        rejected { track.validate(Long.MAX_VALUE, 1, false) }
        rejected { track.validate(0, 1_000_001, false) }
    }

    @Test fun onlySuccessfulMuxedResumeKeyframeCreatesBoundary() {
        val track = RecordingMuxTrack(RecordingTrack.VIDEO)
        track.accepted(0, 33_333, true)
        track.accepted(33_333, 33_333, false)
        track.validate(100_000, 33_333, true, resumeBoundary = true)
        assertEquals(0, track.effectiveResumeCount) // Simulated failed native write.
        assertTrue(track.resumeBoundaries().isEmpty())
        track.accepted(100_000, 33_333, true, resumeBoundary = true)
        track.accepted(200_000, 33_333, true) // Ordinary periodic keyframe is not a resume.
        assertEquals(listOf(com.easysavevideo.recording.RecordingResumeBoundary(2, 100_000)), track.resumeBoundaries())
        rejected { track.validate(300_000, 33_333, false, resumeBoundary = true) }
        rejected { RecordingMuxTrack(RecordingTrack.VIDEO).accepted(0, 33_333, true, resumeBoundary = true) }
        rejected { RecordingMuxTrack(RecordingTrack.AUDIO).accepted(1, 1, false, resumeBoundary = true) }
    }

    @Test fun thirtyTwoEffectiveResumesFitButThirtyThirdCannotBeMuxedOrCounted() {
        val track = RecordingMuxTrack(RecordingTrack.VIDEO)
        track.accepted(0, 10_000, true)
        repeat(32) { index ->
            assertTrue(track.canAdmitResumeBoundary())
            track.accepted((index + 1) * 10_000L, 10_000, true, resumeBoundary = true)
        }
        assertEquals(32, track.effectiveResumeCount)
        assertFalse(track.canAdmitResumeBoundary())
        rejected { track.validate(330_000, 10_000, true, resumeBoundary = true) }
        assertEquals(33L, track.count)
        assertEquals(32, track.resumeBoundaries().size)
        track.accepted(330_000, 10_000, false) // Existing final epoch can still finish safely.
        assertEquals(32, track.effectiveResumeCount)
    }

    @Test fun pendingResumeReservationsBoundPreMuxFormatDelay() {
        val track = RecordingMuxTrack(RecordingTrack.VIDEO, maxResumeBoundaries = 2)
        track.accepted(0, 10_000, true)
        assertTrue(track.canAdmitResumeBoundary(1))
        assertFalse(track.canAdmitResumeBoundary(2))
        track.accepted(10_000, 10_000, true, resumeBoundary = true)
        assertFalse(track.canAdmitResumeBoundary(1))
        assertEquals(1, track.effectiveResumeCount)
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected a stable sample rejection") }
        catch (failure: RecordingFailure) { assertEquals(RecordingError.SAMPLE_INVALID, failure.code) }
    }
}
