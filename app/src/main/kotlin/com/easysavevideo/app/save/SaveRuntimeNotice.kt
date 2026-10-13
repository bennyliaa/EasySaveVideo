package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError

/** Process-local display facts only; never retain a request, resource, exception, or user text. */
internal sealed interface SaveRuntimeNotice {
    data class Fixed(val kind: SaveRuntimeFixedNotice) : SaveRuntimeNotice {
        override fun toString() = "SaveRuntimeNotice.Fixed($kind)"
    }
    data class TaskCapacityReached(val maxUnfinishedTasks: Int) : SaveRuntimeNotice {
        override fun toString() = "SaveRuntimeNotice.TaskCapacityReached([redacted])"
    }
    data class DetectionUnavailable(val reason: PageSaveDetection.Reason) : SaveRuntimeNotice {
        override fun toString() = "SaveRuntimeNotice.DetectionUnavailable($reason)"
    }
    data class Failure(val code: SaveRuntimeFailureCode) : SaveRuntimeNotice {
        override fun toString() = "SaveRuntimeNotice.Failure($code)"
    }
}

internal enum class SaveRuntimeFixedNotice {
    SAVE_UNAVAILABLE,
    OPEN_LOADED_PAGE,
    CANDIDATE_EXPIRED,
    CANDIDATE_PROTECTED_OR_UNSUPPORTED,
    STREAM_ENTITLEMENT_REQUIRED,
    MEDIA_RESOURCES_EXITING,
    DIRECT_REQUEST_SESSION_UNAVAILABLE,
    DIRECT_TASK_ACCEPTED,
    STREAM_UNAVAILABLE,
    STREAM_REQUEST_SESSION_UNAVAILABLE,
    STREAM_PREPARATION_QUEUE_UNAVAILABLE,
    STREAM_CONTEXT_CHANGED,
    TASK_CAPACITY_FULL,
    SAVE_OPERATION_BUSY,
    STREAM_TASK_ACCEPTED,
    RECORDING_RESTART_REQUIRES_FRESH_CONSENT,
    STREAM_RESELECT_REQUIRED,
    SOURCE_RESELECT_AFTER_RESTART,
    SAME_ORIGIN_LOGIN_REQUIRED,
    RESUME_REQUEST_SESSION_UNAVAILABLE,
    TASK_STATE_READ_FAILED,
    EXECUTION_QUEUE_UNAVAILABLE,
    RECORDING_UNAVAILABLE,
    RECORDING_CAPACITY_OR_GEOMETRY_INVALID,
    RECORDING_SESSION_EXITING,
    RECORDING_CONFIGURATION_INVALID,
    REGION_RECORDING_UNAVAILABLE,
    PREVIEW_RECORDING_UNAVAILABLE,
    RECORDING_FOREGROUND_START_REJECTED,
    RECORDING_EXECUTION_FAILED,
    RECORDING_NATIVE_RELEASE_UNCONFIRMED,
    RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED,
    SAVE_STORAGE_UNAVAILABLE,
    CONTROL_QUEUE_FULL,
    WIFI_REQUIRED,
    WIFI_SETTINGS_UNAVAILABLE,
}

/** Preserve the legacy detailed-text equality groups without changing domain error identity. */
internal enum class SaveRuntimeFailureCode {
    WIFI_REQUIRED,
    CANCELLED,
    AUTHENTICATION_REQUIRED,
    STORAGE_FULL,
    STORAGE_FAILURE,
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    TLS_REJECTED,
    POLICY_BLOCKED,
    BYTE_LIMIT_EXCEEDED,
    PROTECTED_MEDIA,
    UNSUPPORTED_MEDIA,
    INVALID_MEDIA,
    INVALID_RANGE,
    EXPIRED_RESOURCE,
    RESOURCE_RELEASE_UNCONFIRMED,
    VERIFICATION_UNAVAILABLE,
    COMMIT_MISMATCH,
    RATE_LIMITED,
    SOURCE_RESELECTION_REQUIRED,
    HTTP_NOT_FOUND,
    HTTP_REJECTED,
    HTTP_FAILURE,
    BUSY,
    CONSENT_DENIED,
    AUDIO_PERMISSION_DENIED,
    CAPTURE_UNAVAILABLE,
    PROFILE_UNSUPPORTED,
    CAPTURE_EMPTY,
    KEYFRAME_TIMEOUT,
    TIMESTAMP_INVALID,
    AUDIO_FAILED,
    ENCODER_FAILED,
    FINALIZATION_FAILED,
    INVALID_REQUEST,
}

internal fun saveRuntimeFailureNotice(error: SaveError): SaveRuntimeNotice.Failure =
    SaveRuntimeNotice.Failure(saveRuntimeFailureCode(error))

internal fun saveRuntimeFailureCode(error: SaveError): SaveRuntimeFailureCode = when (error) {
    SaveError.WIFI_REQUIRED -> SaveRuntimeFailureCode.WIFI_REQUIRED
    SaveError.CANCELLED -> SaveRuntimeFailureCode.CANCELLED
    SaveError.AUTHENTICATION_REQUIRED -> SaveRuntimeFailureCode.AUTHENTICATION_REQUIRED
    SaveError.STORAGE_FULL -> SaveRuntimeFailureCode.STORAGE_FULL
    SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> SaveRuntimeFailureCode.STORAGE_FAILURE
    SaveError.NETWORK_TIMEOUT, SaveError.DEADLINE_EXCEEDED -> SaveRuntimeFailureCode.NETWORK_TIMEOUT
    SaveError.NETWORK_UNAVAILABLE, SaveError.RETRY_EXHAUSTED -> SaveRuntimeFailureCode.NETWORK_UNAVAILABLE
    SaveError.TLS_REJECTED -> SaveRuntimeFailureCode.TLS_REJECTED
    SaveError.POLICY_BLOCKED -> SaveRuntimeFailureCode.POLICY_BLOCKED
    SaveError.BYTE_LIMIT_EXCEEDED -> SaveRuntimeFailureCode.BYTE_LIMIT_EXCEEDED
    SaveError.PROTECTED_MEDIA -> SaveRuntimeFailureCode.PROTECTED_MEDIA
    SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED -> SaveRuntimeFailureCode.UNSUPPORTED_MEDIA
    SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER -> SaveRuntimeFailureCode.INVALID_MEDIA
    SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED -> SaveRuntimeFailureCode.INVALID_RANGE
    SaveError.EXPIRED_RESOURCE -> SaveRuntimeFailureCode.EXPIRED_RESOURCE
    SaveError.RESOURCE_RELEASE_UNCONFIRMED -> SaveRuntimeFailureCode.RESOURCE_RELEASE_UNCONFIRMED
    SaveError.VERIFICATION_UNAVAILABLE -> SaveRuntimeFailureCode.VERIFICATION_UNAVAILABLE
    SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED -> SaveRuntimeFailureCode.COMMIT_MISMATCH
    SaveError.RATE_LIMITED -> SaveRuntimeFailureCode.RATE_LIMITED
    SaveError.SOURCE_RESELECTION_REQUIRED -> SaveRuntimeFailureCode.SOURCE_RESELECTION_REQUIRED
    SaveError.HTTP_NOT_FOUND -> SaveRuntimeFailureCode.HTTP_NOT_FOUND
    SaveError.HTTP_REJECTED -> SaveRuntimeFailureCode.HTTP_REJECTED
    SaveError.HTTP_FAILURE -> SaveRuntimeFailureCode.HTTP_FAILURE
    SaveError.BUSY -> SaveRuntimeFailureCode.BUSY
    SaveError.CONSENT_DENIED -> SaveRuntimeFailureCode.CONSENT_DENIED
    SaveError.AUDIO_PERMISSION_DENIED -> SaveRuntimeFailureCode.AUDIO_PERMISSION_DENIED
    SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED -> SaveRuntimeFailureCode.CAPTURE_UNAVAILABLE
    SaveError.PROFILE_UNSUPPORTED -> SaveRuntimeFailureCode.PROFILE_UNSUPPORTED
    SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT -> SaveRuntimeFailureCode.CAPTURE_EMPTY
    SaveError.KEYFRAME_TIMEOUT -> SaveRuntimeFailureCode.KEYFRAME_TIMEOUT
    SaveError.TIMESTAMP_INVALID -> SaveRuntimeFailureCode.TIMESTAMP_INVALID
    SaveError.AUDIO_FAILED -> SaveRuntimeFailureCode.AUDIO_FAILED
    SaveError.ENCODER_FAILED -> SaveRuntimeFailureCode.ENCODER_FAILED
    SaveError.FINALIZATION_FAILED -> SaveRuntimeFailureCode.FINALIZATION_FAILED
    SaveError.INVALID_REQUEST, SaveError.UNKNOWN_TECHNICAL_ERROR -> SaveRuntimeFailureCode.INVALID_REQUEST
}
