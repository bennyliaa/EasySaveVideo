package com.easysavevideo.app.recording

import com.easysavevideo.app.R
import com.easysavevideo.app.recording.ui.RecordingSegmentsPhase
import com.easysavevideo.recording.RecordingState

/** Display-only values for the existing notification producers; no session or user text is retained. */
internal enum class RecordingNotificationStatus {
    INITIAL_PREPARING,
    PAUSED,
    RECORDING,
    STOPPING,
    CANCELLING,
    RECOVERY_REQUIRED,
    PREPARING_OR_VERIFYING,
    SEGMENT_PREPARING,
    SEGMENT_RECORDING,
    SEGMENT_PAUSED,
    SEGMENT_RESUMING,
    SEGMENT_STOPPING,
}

/** Preserve the native recording notification's six original groups, including its fallback. */
internal fun recordingNotificationStatus(state: RecordingState): RecordingNotificationStatus = when (state) {
    RecordingState.PAUSED -> RecordingNotificationStatus.PAUSED
    RecordingState.RECORDING -> RecordingNotificationStatus.RECORDING
    RecordingState.STOPPING, RecordingState.FINALIZING -> RecordingNotificationStatus.STOPPING
    RecordingState.CANCELLING -> RecordingNotificationStatus.CANCELLING
    RecordingState.RECOVERY_REQUIRED -> RecordingNotificationStatus.RECOVERY_REQUIRED
    else -> RecordingNotificationStatus.PREPARING_OR_VERIFYING
}

/** Only the five phases already emitted by segment capture may produce a notification status. */
internal fun recordingSegmentNotificationStatus(phase: RecordingSegmentsPhase): RecordingNotificationStatus? = when (phase) {
    RecordingSegmentsPhase.PREPARING -> RecordingNotificationStatus.SEGMENT_PREPARING
    RecordingSegmentsPhase.RECORDING -> RecordingNotificationStatus.SEGMENT_RECORDING
    RecordingSegmentsPhase.PAUSED -> RecordingNotificationStatus.SEGMENT_PAUSED
    RecordingSegmentsPhase.RESUMING -> RecordingNotificationStatus.SEGMENT_RESUMING
    RecordingSegmentsPhase.STOPPING -> RecordingNotificationStatus.SEGMENT_STOPPING
    RecordingSegmentsPhase.PAUSING,
    RecordingSegmentsPhase.PROCESSING,
    RecordingSegmentsPhase.VERIFYING,
    RecordingSegmentsPhase.SUCCEEDED,
    RecordingSegmentsPhase.RECOVERY_REQUIRED,
    RecordingSegmentsPhase.CANCELLING,
    RecordingSegmentsPhase.CANCELLED,
    RecordingSegmentsPhase.FAILED -> null
}

internal fun recordingNotificationStatusResource(status: RecordingNotificationStatus): Int = when (status) {
    RecordingNotificationStatus.INITIAL_PREPARING -> R.string.recording_notification_initial_preparing
    RecordingNotificationStatus.PAUSED -> R.string.recording_notification_paused
    RecordingNotificationStatus.RECORDING -> R.string.recording_notification_recording
    RecordingNotificationStatus.STOPPING -> R.string.recording_notification_stopping
    RecordingNotificationStatus.CANCELLING -> R.string.recording_notification_cancelling
    RecordingNotificationStatus.RECOVERY_REQUIRED -> R.string.recording_notification_recovery_required
    RecordingNotificationStatus.PREPARING_OR_VERIFYING -> R.string.recording_notification_preparing_or_verifying
    RecordingNotificationStatus.SEGMENT_PREPARING -> R.string.final_recording_segments_phase_label_0
    RecordingNotificationStatus.SEGMENT_RECORDING -> R.string.final_recording_segments_phase_label_1
    RecordingNotificationStatus.SEGMENT_PAUSED -> R.string.final_recording_segments_phase_label_3
    RecordingNotificationStatus.SEGMENT_RESUMING -> R.string.final_recording_segments_phase_label_4
    RecordingNotificationStatus.SEGMENT_STOPPING -> R.string.final_recording_segments_phase_label_5
}
