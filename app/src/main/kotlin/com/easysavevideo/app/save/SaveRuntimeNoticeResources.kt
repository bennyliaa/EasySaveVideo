package com.easysavevideo.app.save

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R

/** Resolve the unchanged token in the current Composition, including the API31/32 locale override. */
@Composable
internal fun saveRuntimeNoticeText(notice: SaveRuntimeNotice): String = when (notice) {
    is SaveRuntimeNotice.Fixed -> stringResource(saveRuntimeFixedNoticeResource(notice.kind))
    is SaveRuntimeNotice.TaskCapacityReached -> pluralStringResource(
        R.plurals.save_runtime_notice_task_capacity_reached,
        notice.maxUnfinishedTasks,
        saveIntegerText(notice.maxUnfinishedTasks.toLong()),
    )
    is SaveRuntimeNotice.DetectionUnavailable -> stringResource(saveRuntimeDetectionNoticeResource(notice.reason))
    is SaveRuntimeNotice.Failure -> stringResource(saveRuntimeFailureNoticeResource(notice.code))
}

internal fun saveRuntimeFixedNoticeResource(kind: SaveRuntimeFixedNotice): Int = when (kind) {
    SaveRuntimeFixedNotice.SAVE_UNAVAILABLE -> R.string.save_runtime_notice_save_unavailable
    SaveRuntimeFixedNotice.OPEN_LOADED_PAGE -> R.string.save_runtime_notice_open_loaded_page
    SaveRuntimeFixedNotice.CANDIDATE_EXPIRED -> R.string.save_runtime_notice_candidate_expired
    SaveRuntimeFixedNotice.CANDIDATE_PROTECTED_OR_UNSUPPORTED -> R.string.save_runtime_notice_candidate_protected_or_unsupported
    SaveRuntimeFixedNotice.STREAM_ENTITLEMENT_REQUIRED -> R.string.save_runtime_notice_stream_entitlement_required
    SaveRuntimeFixedNotice.MEDIA_RESOURCES_EXITING -> R.string.save_runtime_notice_media_resources_exiting
    SaveRuntimeFixedNotice.DIRECT_REQUEST_SESSION_UNAVAILABLE -> R.string.save_runtime_notice_direct_request_session_unavailable
    SaveRuntimeFixedNotice.DIRECT_TASK_ACCEPTED -> R.string.save_runtime_notice_direct_task_accepted
    SaveRuntimeFixedNotice.STREAM_UNAVAILABLE -> R.string.save_runtime_notice_stream_unavailable
    SaveRuntimeFixedNotice.STREAM_REQUEST_SESSION_UNAVAILABLE -> R.string.save_runtime_notice_stream_request_session_unavailable
    SaveRuntimeFixedNotice.STREAM_PREPARATION_QUEUE_UNAVAILABLE -> R.string.save_runtime_notice_stream_preparation_queue_unavailable
    SaveRuntimeFixedNotice.STREAM_CONTEXT_CHANGED -> R.string.save_runtime_notice_stream_context_changed
    SaveRuntimeFixedNotice.TASK_CAPACITY_FULL -> R.string.save_runtime_notice_task_capacity_full
    SaveRuntimeFixedNotice.SAVE_OPERATION_BUSY -> R.string.save_runtime_notice_save_operation_busy
    SaveRuntimeFixedNotice.STREAM_TASK_ACCEPTED -> R.string.save_runtime_notice_stream_task_accepted
    SaveRuntimeFixedNotice.RECORDING_RESTART_REQUIRES_FRESH_CONSENT -> R.string.save_runtime_notice_recording_restart_requires_fresh_consent
    SaveRuntimeFixedNotice.STREAM_RESELECT_REQUIRED -> R.string.save_runtime_notice_stream_reselect_required
    SaveRuntimeFixedNotice.SOURCE_RESELECT_AFTER_RESTART -> R.string.save_runtime_notice_source_reselect_after_restart
    SaveRuntimeFixedNotice.SAME_ORIGIN_LOGIN_REQUIRED -> R.string.save_runtime_notice_same_origin_login_required
    SaveRuntimeFixedNotice.RESUME_REQUEST_SESSION_UNAVAILABLE -> R.string.save_runtime_notice_resume_request_session_unavailable
    SaveRuntimeFixedNotice.TASK_STATE_READ_FAILED -> R.string.save_runtime_notice_task_state_read_failed
    SaveRuntimeFixedNotice.EXECUTION_QUEUE_UNAVAILABLE -> R.string.save_runtime_notice_execution_queue_unavailable
    SaveRuntimeFixedNotice.RECORDING_UNAVAILABLE -> R.string.save_runtime_notice_recording_unavailable
    SaveRuntimeFixedNotice.RECORDING_CAPACITY_OR_GEOMETRY_INVALID -> R.string.save_runtime_notice_recording_capacity_or_geometry_invalid
    SaveRuntimeFixedNotice.RECORDING_SESSION_EXITING -> R.string.save_runtime_notice_recording_session_exiting
    SaveRuntimeFixedNotice.RECORDING_CONFIGURATION_INVALID -> R.string.save_runtime_notice_recording_configuration_invalid
    SaveRuntimeFixedNotice.REGION_RECORDING_UNAVAILABLE -> R.string.save_runtime_notice_region_recording_unavailable
    SaveRuntimeFixedNotice.PREVIEW_RECORDING_UNAVAILABLE -> R.string.save_runtime_notice_preview_recording_unavailable
    SaveRuntimeFixedNotice.RECORDING_FOREGROUND_START_REJECTED -> R.string.save_runtime_notice_recording_foreground_start_rejected
    SaveRuntimeFixedNotice.RECORDING_EXECUTION_FAILED -> R.string.save_runtime_notice_recording_execution_failed
    SaveRuntimeFixedNotice.RECORDING_NATIVE_RELEASE_UNCONFIRMED -> R.string.save_runtime_notice_recording_native_release_unconfirmed
    SaveRuntimeFixedNotice.RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED -> R.string.save_runtime_notice_recording_pause_resume_entitlement_required
    SaveRuntimeFixedNotice.SAVE_STORAGE_UNAVAILABLE -> R.string.save_runtime_notice_save_storage_unavailable
    SaveRuntimeFixedNotice.CONTROL_QUEUE_FULL -> R.string.save_runtime_notice_control_queue_full
    SaveRuntimeFixedNotice.WIFI_REQUIRED -> R.string.save_wifi_required
    SaveRuntimeFixedNotice.WIFI_SETTINGS_UNAVAILABLE -> R.string.save_wifi_settings_unavailable
}

internal fun saveRuntimeDetectionNoticeResource(reason: PageSaveDetection.Reason): Int = when (reason) {
    PageSaveDetection.Reason.STALE_PAGE -> R.string.save_runtime_notice_detection_stale_page
    PageSaveDetection.Reason.JAVASCRIPT_DISABLED -> R.string.save_runtime_notice_detection_javascript_disabled
    PageSaveDetection.Reason.TIMED_OUT -> R.string.save_runtime_notice_detection_timed_out
    PageSaveDetection.Reason.UNSUPPORTED -> R.string.save_runtime_notice_detection_unsupported
    PageSaveDetection.Reason.NO_MEDIA -> R.string.save_runtime_notice_detection_no_media
    PageSaveDetection.Reason.INVALID_RESPONSE -> R.string.save_runtime_notice_detection_invalid_response
}

internal fun saveRuntimeFailureNoticeResource(code: SaveRuntimeFailureCode): Int = when (code) {
    SaveRuntimeFailureCode.WIFI_REQUIRED -> R.string.save_runtime_notice_failure_wifi_required
    SaveRuntimeFailureCode.CANCELLED -> R.string.save_runtime_notice_failure_cancelled
    SaveRuntimeFailureCode.AUTHENTICATION_REQUIRED -> R.string.save_runtime_notice_failure_authentication_required
    SaveRuntimeFailureCode.STORAGE_FULL -> R.string.save_runtime_notice_failure_storage_full
    SaveRuntimeFailureCode.STORAGE_FAILURE -> R.string.save_runtime_notice_failure_storage_failure
    SaveRuntimeFailureCode.NETWORK_TIMEOUT -> R.string.save_runtime_notice_failure_network_timeout
    SaveRuntimeFailureCode.NETWORK_UNAVAILABLE -> R.string.save_runtime_notice_failure_network_unavailable
    SaveRuntimeFailureCode.TLS_REJECTED -> R.string.save_runtime_notice_failure_tls_rejected
    SaveRuntimeFailureCode.POLICY_BLOCKED -> R.string.save_runtime_notice_failure_policy_blocked
    SaveRuntimeFailureCode.BYTE_LIMIT_EXCEEDED -> R.string.save_runtime_notice_failure_byte_limit_exceeded
    SaveRuntimeFailureCode.PROTECTED_MEDIA -> R.string.save_runtime_notice_failure_protected_media
    SaveRuntimeFailureCode.UNSUPPORTED_MEDIA -> R.string.save_runtime_notice_failure_unsupported_media
    SaveRuntimeFailureCode.INVALID_MEDIA -> R.string.save_runtime_notice_failure_invalid_media
    SaveRuntimeFailureCode.INVALID_RANGE -> R.string.save_runtime_notice_failure_invalid_range
    SaveRuntimeFailureCode.EXPIRED_RESOURCE -> R.string.save_runtime_notice_failure_expired_resource
    SaveRuntimeFailureCode.RESOURCE_RELEASE_UNCONFIRMED -> R.string.save_runtime_notice_failure_resource_release_unconfirmed
    SaveRuntimeFailureCode.VERIFICATION_UNAVAILABLE -> R.string.save_runtime_notice_failure_verification_unavailable
    SaveRuntimeFailureCode.COMMIT_MISMATCH -> R.string.save_runtime_notice_failure_commit_mismatch
    SaveRuntimeFailureCode.RATE_LIMITED -> R.string.save_runtime_notice_failure_rate_limited
    SaveRuntimeFailureCode.SOURCE_RESELECTION_REQUIRED -> R.string.save_runtime_notice_failure_source_reselection_required
    SaveRuntimeFailureCode.HTTP_NOT_FOUND -> R.string.save_runtime_notice_failure_http_not_found
    SaveRuntimeFailureCode.HTTP_REJECTED -> R.string.save_runtime_notice_failure_http_rejected
    SaveRuntimeFailureCode.HTTP_FAILURE -> R.string.save_runtime_notice_failure_http_failure
    SaveRuntimeFailureCode.BUSY -> R.string.save_runtime_notice_failure_busy
    SaveRuntimeFailureCode.CONSENT_DENIED -> R.string.save_runtime_notice_failure_consent_denied
    SaveRuntimeFailureCode.AUDIO_PERMISSION_DENIED -> R.string.save_runtime_notice_failure_audio_permission_denied
    SaveRuntimeFailureCode.CAPTURE_UNAVAILABLE -> R.string.save_runtime_notice_failure_capture_unavailable
    SaveRuntimeFailureCode.PROFILE_UNSUPPORTED -> R.string.save_runtime_notice_failure_profile_unsupported
    SaveRuntimeFailureCode.CAPTURE_EMPTY -> R.string.save_runtime_notice_failure_capture_empty
    SaveRuntimeFailureCode.KEYFRAME_TIMEOUT -> R.string.save_runtime_notice_failure_keyframe_timeout
    SaveRuntimeFailureCode.TIMESTAMP_INVALID -> R.string.save_runtime_notice_failure_timestamp_invalid
    SaveRuntimeFailureCode.AUDIO_FAILED -> R.string.save_runtime_notice_failure_audio_failed
    SaveRuntimeFailureCode.ENCODER_FAILED -> R.string.save_runtime_notice_failure_encoder_failed
    SaveRuntimeFailureCode.FINALIZATION_FAILED -> R.string.save_runtime_notice_failure_finalization_failed
    SaveRuntimeFailureCode.INVALID_REQUEST -> R.string.save_runtime_notice_failure_invalid_request
}
