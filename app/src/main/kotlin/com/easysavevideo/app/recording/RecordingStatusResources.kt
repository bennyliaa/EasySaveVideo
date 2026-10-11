package com.easysavevideo.app.recording

import com.easysavevideo.app.R
import com.easysavevideo.recording.*
import com.easysavevideo.app.recording.ui.RecordingSegmentsPhase

internal fun recordingStateResource(value: RecordingState): Int = when (value) {
    RecordingState.IDLE -> R.string.final_recording_state_label_0
    RecordingState.AWAITING_USER_CONFIRMATION -> R.string.final_recording_state_label_1
    RecordingState.AWAITING_AUDIO_PERMISSION -> R.string.final_recording_state_label_2
    RecordingState.AWAITING_SYSTEM_CONSENT -> R.string.final_recording_state_label_3
    RecordingState.PREPARING -> R.string.final_recording_state_label_4
    RecordingState.RECORDING -> R.string.final_recording_state_label_5
    RecordingState.PAUSED -> R.string.final_recording_state_label_6
    RecordingState.RESUMING -> R.string.final_recording_state_label_7
    RecordingState.STOPPING -> R.string.final_recording_state_label_8
    RecordingState.FINALIZING -> R.string.final_recording_state_label_9
    RecordingState.CANCELLING -> R.string.final_recording_state_label_10
    RecordingState.CAPTURE_FINISHED -> R.string.final_recording_state_label_11
    RecordingState.CANCELLED -> R.string.final_recording_state_label_12
    RecordingState.FAILED -> R.string.final_recording_state_label_13
    RecordingState.RECOVERY_REQUIRED -> R.string.final_recording_state_label_14
}

internal fun recordingAudioStatusResource(value: RecordingAudioStatus): Int = when (value) {
    RecordingAudioStatus.NOT_REQUESTED -> R.string.final_recording_audio_status_label_0
    RecordingAudioStatus.AWAITING_PERMISSION -> R.string.final_recording_audio_status_label_1
    RecordingAudioStatus.PERMISSION_DENIED -> R.string.final_recording_audio_status_label_2
    RecordingAudioStatus.INITIALIZING -> R.string.final_recording_audio_status_label_3
    RecordingAudioStatus.PCM_OBSERVED -> R.string.final_recording_audio_status_label_4
    RecordingAudioStatus.SILENCE_OR_UNKNOWN -> R.string.final_recording_audio_status_label_5
    RecordingAudioStatus.INTERRUPTED -> R.string.final_recording_audio_status_label_6
}

internal fun recordingAudioOutcomeResource(value: RecordingAudioOutcome): Int = when (value) {
    RecordingAudioOutcome.NO_AUDIO_REQUESTED -> R.string.final_recording_audio_outcome_label_0
    RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED -> R.string.final_recording_audio_outcome_label_1
    RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN -> R.string.final_recording_audio_outcome_label_2
    RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED -> R.string.final_recording_audio_outcome_label_3
}

internal fun recordingStopReasonResource(value: RecordingStopReason): Int = when (value) {
    RecordingStopReason.USER_STOP -> R.string.final_recording_stop_reason_label_0
    RecordingStopReason.SYSTEM_STOPPED -> R.string.final_recording_stop_reason_label_1
    RecordingStopReason.SIZE_CHANGED -> R.string.final_recording_stop_reason_label_2
    RecordingStopReason.SESSION_LIMIT -> R.string.final_recording_stop_reason_label_3
    RecordingStopReason.PAUSE_LIMIT -> R.string.final_recording_stop_reason_label_4
    RecordingStopReason.RESUME_LIMIT -> R.string.final_recording_stop_reason_label_5
    RecordingStopReason.BYTE_LIMIT -> R.string.final_recording_stop_reason_label_6
    RecordingStopReason.STORAGE_LOW -> R.string.final_recording_stop_reason_label_7
    RecordingStopReason.AUDIO_LOST -> R.string.final_recording_stop_reason_label_8
    RecordingStopReason.ENCODER_FAILED -> R.string.final_recording_stop_reason_label_9
    RecordingStopReason.USER_CANCELLED -> R.string.final_recording_stop_reason_label_10
    RecordingStopReason.CONSENT_DENIED -> R.string.final_recording_stop_reason_label_11
    RecordingStopReason.AUDIO_PERMISSION_DENIED -> R.string.final_recording_stop_reason_label_12
    RecordingStopReason.ENTITLEMENT_REVOKED -> R.string.final_recording_stop_reason_label_13
    RecordingStopReason.DEADLINE_EXCEEDED -> R.string.final_recording_stop_reason_label_14
    RecordingStopReason.PROCESS_INTERRUPTED -> R.string.final_recording_stop_reason_label_15
}

internal fun recordingErrorResource(value: RecordingError): Int = when (value) {
    RecordingError.ENTITLEMENT_REQUIRED -> R.string.final_recording_error_label_0
    RecordingError.CONSENT_DENIED -> R.string.final_recording_error_label_1
    RecordingError.AUDIO_PERMISSION_DENIED -> R.string.final_recording_error_label_2
    RecordingError.CAPTURE_UNAVAILABLE, RecordingError.FGS_START_REJECTED -> R.string.final_recording_error_label_3
    RecordingError.PROFILE_UNSUPPORTED -> R.string.final_recording_error_label_4
    RecordingError.CAPTURE_EMPTY, RecordingError.CAPTURE_TOO_SHORT -> R.string.final_recording_error_label_5
    RecordingError.RESOURCE_RELEASE_UNCONFIRMED -> R.string.final_recording_error_label_6
    RecordingError.STORAGE_LOW -> R.string.final_recording_error_label_7
    RecordingError.BYTE_LIMIT -> R.string.final_recording_error_label_8
    RecordingError.AUDIO_FAILED -> R.string.final_recording_error_label_9
    RecordingError.DEADLINE_EXCEEDED -> R.string.final_recording_error_label_10
    RecordingError.CANCELLED -> R.string.final_recording_error_label_11
    RecordingError.BUSY -> R.string.final_recording_error_label_12
    RecordingError.INVALID_REQUEST, RecordingError.STALE_SESSION, RecordingError.STALE_REVISION,
    RecordingError.INVALID_STATE -> R.string.final_recording_error_label_13
    RecordingError.KEYFRAME_TIMEOUT, RecordingError.TIMESTAMP_INVALID, RecordingError.CLOCK_INVALID,
    RecordingError.FORMAT_TIMEOUT, RecordingError.FORMAT_CHANGED, RecordingError.SAMPLE_INVALID,
    RecordingError.SAMPLE_LIMIT, RecordingError.BUFFER_LIMIT, RecordingError.ENCODER_FAILED,
    RecordingError.FINALIZATION_FAILED -> R.string.final_recording_error_label_14
}

internal fun recordingSegmentsPhaseResource(value: RecordingSegmentsPhase, verifiedOutputAvailable: Boolean): Int = when (value) {
    RecordingSegmentsPhase.PREPARING -> R.string.final_recording_segments_phase_label_0
    RecordingSegmentsPhase.RECORDING -> R.string.final_recording_segments_phase_label_1
    RecordingSegmentsPhase.PAUSING -> R.string.final_recording_segments_phase_label_2
    RecordingSegmentsPhase.PAUSED -> R.string.final_recording_segments_phase_label_3
    RecordingSegmentsPhase.RESUMING -> R.string.final_recording_segments_phase_label_4
    RecordingSegmentsPhase.STOPPING -> R.string.final_recording_segments_phase_label_5
    RecordingSegmentsPhase.PROCESSING -> R.string.final_recording_segments_phase_label_6
    RecordingSegmentsPhase.VERIFYING -> R.string.final_recording_segments_phase_label_7
    RecordingSegmentsPhase.SUCCEEDED -> if (verifiedOutputAvailable) R.string.final_recording_segments_phase_label_8 else R.string.final_segments_unconfirmed
    RecordingSegmentsPhase.RECOVERY_REQUIRED -> R.string.final_recording_segments_phase_label_9
    RecordingSegmentsPhase.CANCELLING -> R.string.final_recording_segments_phase_label_10
    RecordingSegmentsPhase.CANCELLED -> R.string.final_recording_segments_phase_label_11
    RecordingSegmentsPhase.FAILED -> R.string.final_recording_segments_phase_label_12
}
