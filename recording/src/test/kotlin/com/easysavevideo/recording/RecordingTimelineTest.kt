package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    private fun accepted(value: RecordingSampleMapping) = value as RecordingSampleMapping.Accepted
    private fun dropped(value: RecordingSampleMapping) = value as RecordingSampleMapping.Dropped
    private fun failWith(error: RecordingError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (failure: RecordingFailure) { assertEquals(error, failure.code) }
    }
    private fun active(origin: Long = 0): RecordingTimeline = RecordingTimeline(origin).also {
        assertEquals(0L, accepted(it.mapSample(RecordingTrack.VIDEO, origin, 33_333, true)).ptsUs)
    }

    @Test fun t01_twentyPlusPause120PlusThirtyProducesFiftySecondTimeline() {
        val t = active()
        assertEquals(19_966_667L, accepted(t.mapSample(RecordingTrack.VIDEO, 19_966_667, 33_333)).ptsUs)
        t.pause(20_000_000)
        assertEquals(20_000_000L, t.effectiveDurationUs(139_999_999))
        t.resume(140_000_000)
        assertEquals(20_000_000L, accepted(t.mapSample(RecordingTrack.VIDEO, 140_000_000, 33_333, true)).ptsUs)
        assertEquals(49_966_667L, accepted(t.mapSample(RecordingTrack.VIDEO, 169_966_667, 33_333)).ptsUs)
        t.stop(170_000_000)
        assertEquals(50_000_000L, t.effectiveDurationUs(170_000_000))
        assertEquals(50_000_000L, t.acceptedMediaEndUs())
    }

    @Test fun t02_rapidPauseResumePreservesAlreadyAcceptedPacketDuration() {
        val t = active()
        t.pause(0)
        assertFalse(t.pause(0))
        assertTrue(t.resume(1))
        assertFalse(t.resume(1))
        assertEquals(33_333L, accepted(t.mapSample(RecordingTrack.VIDEO, 2, 33_333, true)).ptsUs)
        t.pause(2)
        t.resume(3)
        assertEquals(66_666L, accepted(t.mapSample(RecordingTrack.VIDEO, 4, 33_333, true)).ptsUs)
        assertEquals(99_999L, t.acceptedMediaEndUs())
    }

    @Test fun t02_stopBetweenResumeAndKeyframeKeepsGateClosed() {
        val t = active()
        t.pause(1_000_000)
        t.resume(2_000_000)
        t.stop(2_500_000)
        assertEquals(RecordingSampleDrop.STOPPED,
            dropped(t.mapSample(RecordingTrack.VIDEO, 2_300_000, 33_333, true)).reason)
        assertEquals(1_000_000L, t.effectiveDurationUs(2_500_000))
    }

    @Test fun t03_firstFrameAndResumeMustActuallyBeKeyframes() {
        val t = RecordingTimeline(100)
        assertEquals(RecordingSampleDrop.WAITING_KEYFRAME,
            dropped(t.mapSample(RecordingTrack.VIDEO, 200, 33_333)).reason)
        assertEquals(RecordingSampleDrop.WAITING_KEYFRAME,
            dropped(t.mapSample(RecordingTrack.AUDIO, 201, 21_333)).reason)
        assertEquals(0L, accepted(t.mapSample(RecordingTrack.VIDEO, 300, 33_333, true)).ptsUs)
        t.pause(40_000)
        t.resume(100_000)
        assertEquals(RecordingSampleDrop.WAITING_KEYFRAME,
            dropped(t.mapSample(RecordingTrack.VIDEO, 101_000, 33_333)).reason)
        assertEquals(39_700L, accepted(t.mapSample(RecordingTrack.VIDEO, 102_000, 33_333, true)).ptsUs)
    }

    @Test fun t03_missingKeyframeTimesOutEvenWithoutSamples() {
        val t = RecordingTimeline(100, 1000)
        t.checkDeadline(1099)
        failWith(RecordingError.KEYFRAME_TIMEOUT) { t.checkDeadline(1100) }
        assertEquals(RecordingTimelineState.FAILED, t.state)
        assertEquals(RecordingSampleDrop.STOPPED,
            dropped(t.mapSample(RecordingTrack.VIDEO, 1101, 33_333, true)).reason)
    }

    @Test fun t03_resumeKeyframeArrivingAfterDeadlineCannotReopenGate() {
        val t = active()
        t.pause(1_000_000)
        t.resume(2_000_000)
        failWith(RecordingError.KEYFRAME_TIMEOUT) {
            t.mapSample(RecordingTrack.VIDEO, 7_000_000, 33_333, true)
        }
    }

    @Test fun t04_audioAndVideoShareEpochOffsetAndAudioCannotLeadResume() {
        val t = active(10_000_000)
        assertEquals(10_000L, accepted(t.mapSample(RecordingTrack.AUDIO, 10_010_000, 21_333)).ptsUs)
        t.pause(30_000_000)
        t.resume(150_000_000)
        assertEquals(RecordingSampleDrop.WAITING_KEYFRAME,
            dropped(t.mapSample(RecordingTrack.AUDIO, 150_010_000, 21_333)).reason)
        assertEquals(20_000_000L,
            accepted(t.mapSample(RecordingTrack.VIDEO, 150_020_000, 33_333, true)).ptsUs)
        assertEquals(RecordingSampleDrop.BEFORE_BOUNDARY,
            dropped(t.mapSample(RecordingTrack.AUDIO, 150_010_000, 21_333)).reason)
        assertEquals(20_010_000L, accepted(t.mapSample(RecordingTrack.AUDIO, 150_030_000, 21_333)).ptsUs)
        assertEquals(20_020_000L, accepted(t.mapSample(RecordingTrack.VIDEO, 150_040_000, 33_333)).ptsUs)
    }

    @Test fun t04_pausedAndLateOldEpochPacketsAreDroppedNotRemapped() {
        val t = active()
        t.mapSample(RecordingTrack.AUDIO, 1000, 21_333)
        t.pause(1_000_000)
        assertEquals(RecordingSampleDrop.PAUSED,
            dropped(t.mapSample(RecordingTrack.AUDIO, 900_000, 21_333)).reason)
        t.resume(2_000_000)
        t.mapSample(RecordingTrack.VIDEO, 2_000_000, 33_333, true)
        assertEquals(RecordingSampleDrop.BEFORE_BOUNDARY,
            dropped(t.mapSample(RecordingTrack.AUDIO, 900_000, 21_333)).reason)
        assertEquals(1_000_000L, accepted(t.mapSample(RecordingTrack.AUDIO, 2_000_000, 21_333)).ptsUs)
    }

    @Test fun t05_largeMonotonicOriginsDoNotOverflowRelativeMediaTime() {
        val origin = Long.MAX_VALUE - 10_000_000
        val t = active(origin)
        assertEquals(1_000_000L, accepted(t.mapSample(RecordingTrack.VIDEO, origin + 1_000_000, 33_333)).ptsUs)
        t.pause(origin + 2_000_000)
        t.resume(origin + 3_000_000)
        assertEquals(2_000_000L, accepted(t.mapSample(RecordingTrack.VIDEO, origin + 3_100_000, 33_333, true)).ptsUs)
    }

    @Test fun t05_negativeAndOverflowingSampleTimesFailClosed() {
        failWith(RecordingError.TIMESTAMP_INVALID) { active().mapSample(RecordingTrack.VIDEO, -1, 1) }
        failWith(RecordingError.TIMESTAMP_INVALID) { active().mapSample(RecordingTrack.VIDEO, Long.MAX_VALUE, 1) }
        failWith(RecordingError.TIMESTAMP_INVALID) { active().mapSample(RecordingTrack.AUDIO, 1, 0) }
    }

    @Test fun t05_clockRegressionFailsInsteadOfClamping() {
        val t = active(100)
        t.checkDeadline(200)
        failWith(RecordingError.CLOCK_INVALID) { t.pause(199) }
        assertEquals(RecordingTimelineState.FAILED, t.state)
    }

    @Test fun t05_perTrackDuplicateOrBackwardsPtsAreRejected() {
        val duplicate = active()
        failWith(RecordingError.TIMESTAMP_INVALID) { duplicate.mapSample(RecordingTrack.VIDEO, 0, 33_333) }
        val backwards = active()
        backwards.mapSample(RecordingTrack.AUDIO, 100, 21_333)
        failWith(RecordingError.TIMESTAMP_INVALID) { backwards.mapSample(RecordingTrack.AUDIO, 99, 21_333) }
    }

    @Test fun t06_staticVideoAndPauseDoNotTriggerNoByteWatchdog() {
        val t = active()
        t.checkDeadline(60_000_000)
        assertEquals(RecordingTimelineState.ACTIVE, t.state)
        t.pause(60_000_000)
        t.checkDeadline(180_000_000)
        assertEquals(RecordingTimelineState.PAUSED, t.state)
        assertEquals(60_000_000L, t.effectiveDurationUs(180_000_000))
    }

    @Test fun stopDrainsOnlyTheActiveEpochBeforeItsCutoff() {
        val t = active()
        t.mapSample(RecordingTrack.AUDIO, 0, 21_333)
        t.stop(1_000_000)
        assertEquals(900_000L, accepted(t.mapSample(RecordingTrack.VIDEO, 900_000, 33_333)).ptsUs)
        assertEquals(950_000L, accepted(t.mapSample(RecordingTrack.AUDIO, 950_000, 21_333)).ptsUs)
        assertEquals(RecordingSampleDrop.STOPPED,
            dropped(t.mapSample(RecordingTrack.AUDIO, 990_000, 21_333)).reason)
        assertEquals(RecordingSampleDrop.STOPPED,
            dropped(t.mapSample(RecordingTrack.VIDEO, 1_000_000, 33_333)).reason)
        assertFalse(t.stop(1_000_001))
    }

    @Test fun stoppedPausedEpochCannotAdmitPendingAudio() {
        val t = active()
        t.pause(1_000_000)
        t.stop(2_000_000)
        assertEquals(RecordingSampleDrop.STOPPED,
            dropped(t.mapSample(RecordingTrack.AUDIO, 900_000, 21_333)).reason)
    }

    @Test fun keyframeFlagOnAudioIsInvalidRatherThanOpeningGate() {
        failWith(RecordingError.TIMESTAMP_INVALID) {
            RecordingTimeline(0).mapSample(RecordingTrack.AUDIO, 0, 21_333, true)
        }
    }

    @Test fun controlsCannotPrecedeAlreadyAdmittedCaptureTimestamp() {
        val t = active()
        t.mapSample(RecordingTrack.VIDEO, 1_000_000, 33_333)
        failWith(RecordingError.CLOCK_INVALID) { t.pause(999_999) }
    }
}
