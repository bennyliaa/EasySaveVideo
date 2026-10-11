package com.easysavevideo.app.recording

import com.easysavevideo.app.R
import com.easysavevideo.app.recording.ui.RecordingSegmentsPhase
import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RecordingStatusResourcesTest {
    @Test fun captureAndUnverifiedSegmentsCannotClaimPublication() {
        val saved = recordingSegmentsPhaseResource(RecordingSegmentsPhase.SUCCEEDED, true)
        assertEquals(R.string.final_segments_unconfirmed,
            recordingSegmentsPhaseResource(RecordingSegmentsPhase.SUCCEEDED, false))
        RecordingSegmentsPhase.entries.filter { it != RecordingSegmentsPhase.SUCCEEDED }.forEach {
            assertNotEquals(saved, recordingSegmentsPhaseResource(it, true))
            assertEquals(recordingSegmentsPhaseResource(it, false), recordingSegmentsPhaseResource(it, true))
        }
        assertNotEquals(recordingStateResource(RecordingState.CAPTURE_FINISHED), recordingStateResource(RecordingState.RECORDING))
        assertNotEquals(recordingStateResource(RecordingState.RECOVERY_REQUIRED), recordingStateResource(RecordingState.CAPTURE_FINISHED))
    }

    @Test fun uncertaintyCancellationAndLateResultsRemainDistinct() {
        assertNotEquals(recordingAudioStatusResource(RecordingAudioStatus.PCM_OBSERVED),
            recordingAudioStatusResource(RecordingAudioStatus.SILENCE_OR_UNKNOWN))
        assertNotEquals(recordingErrorResource(RecordingError.CANCELLED), recordingErrorResource(RecordingError.DEADLINE_EXCEEDED))
        assertNotEquals(recordingErrorResource(RecordingError.RESOURCE_RELEASE_UNCONFIRMED), recordingErrorResource(RecordingError.BUSY))
        assertNotEquals(recordingStopReasonResource(RecordingStopReason.PROCESS_INTERRUPTED), recordingStopReasonResource(RecordingStopReason.USER_STOP))
    }
}
