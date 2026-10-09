package com.easysavevideo.save

import com.easysavevideo.core.model.*
import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure

data class SaveErrorPolicy(val retryable: Boolean = false, val waitReason: SaveWaitReason? = null)
object SaveErrors {
    fun policy(code: SaveError) = when (code) {
        SaveError.NETWORK_TIMEOUT, SaveError.HTTP_FAILURE, SaveError.INCOMPLETE_TRANSFER -> SaveErrorPolicy(retryable = true)
        SaveError.NETWORK_UNAVAILABLE, SaveError.RATE_LIMITED -> SaveErrorPolicy(waitReason = SaveWaitReason.NETWORK)
        SaveError.AUTHENTICATION_REQUIRED, SaveError.EXPIRED_RESOURCE -> SaveErrorPolicy(waitReason = SaveWaitReason.AUTHENTICATION)
        SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE -> SaveErrorPolicy(waitReason = SaveWaitReason.STORAGE)
        SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED, SaveError.RESOURCE_RELEASE_UNCONFIRMED, SaveError.SOURCE_RESELECTION_REQUIRED -> SaveErrorPolicy(waitReason = SaveWaitReason.RECOVERY)
        else -> SaveErrorPolicy()
    }
    fun coreCode(code: SaveError): ErrorCode = when (code) {
        SaveError.CANCELLED -> ErrorCode.OPERATION_CANCELLED
        SaveError.DEADLINE_EXCEEDED -> ErrorCode.DEADLINE_EXCEEDED
        SaveError.RETRY_EXHAUSTED -> ErrorCode.RETRY_EXHAUSTED
        SaveError.NETWORK_UNAVAILABLE -> ErrorCode.NETWORK_UNAVAILABLE
        SaveError.NETWORK_TIMEOUT -> ErrorCode.NETWORK_TIMEOUT
        SaveError.HTTP_FAILURE, SaveError.HTTP_NOT_FOUND, SaveError.HTTP_REJECTED, SaveError.RATE_LIMITED -> ErrorCode.HTTP_FAILURE
        SaveError.TLS_REJECTED -> ErrorCode.TLS_REJECTED
        SaveError.POLICY_BLOCKED, SaveError.PROTECTED_MEDIA -> ErrorCode.POLICY_BLOCKED
        SaveError.AUTHENTICATION_REQUIRED, SaveError.EXPIRED_RESOURCE -> ErrorCode.SESSION_UNAVAILABLE
        SaveError.METHOD_UNSUPPORTED, SaveError.VERIFICATION_UNAVAILABLE -> ErrorCode.PLATFORM_UNAVAILABLE
        SaveError.RECOVERY_REQUIRED, SaveError.COMMIT_MISMATCH, SaveError.RESOURCE_RELEASE_UNCONFIRMED, SaveError.SOURCE_RESELECTION_REQUIRED -> ErrorCode.RECOVERY_FAILED
        SaveError.INVALID_REQUEST, SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED, SaveError.INVALID_MEDIA,
        SaveError.UNSUPPORTED_MEDIA, SaveError.BUSY -> ErrorCode.INVALID_STATE
        else -> ErrorCode.UNKNOWN_TECHNICAL_ERROR
    }
    /** No exception message or user data crosses this boundary. */
    fun classify(error: Exception): SaveError = when (error) {
        is SaveFailure -> error.code
        is RecordingFailure -> when (error.code) {
            RecordingError.CANCELLED -> SaveError.CANCELLED
            RecordingError.CONSENT_DENIED -> SaveError.CONSENT_DENIED
            RecordingError.AUDIO_PERMISSION_DENIED -> SaveError.AUDIO_PERMISSION_DENIED
            RecordingError.CAPTURE_UNAVAILABLE -> SaveError.CAPTURE_UNAVAILABLE
            RecordingError.FGS_START_REJECTED -> SaveError.FGS_START_REJECTED
            RecordingError.PROFILE_UNSUPPORTED -> SaveError.PROFILE_UNSUPPORTED
            RecordingError.CAPTURE_EMPTY -> SaveError.CAPTURE_EMPTY
            RecordingError.CAPTURE_TOO_SHORT -> SaveError.CAPTURE_TOO_SHORT
            RecordingError.KEYFRAME_TIMEOUT -> SaveError.KEYFRAME_TIMEOUT
            RecordingError.TIMESTAMP_INVALID, RecordingError.CLOCK_INVALID -> SaveError.TIMESTAMP_INVALID
            RecordingError.ENCODER_FAILED -> SaveError.ENCODER_FAILED
            RecordingError.AUDIO_FAILED -> SaveError.AUDIO_FAILED
            RecordingError.FINALIZATION_FAILED -> SaveError.FINALIZATION_FAILED
            RecordingError.RESOURCE_RELEASE_UNCONFIRMED -> SaveError.RESOURCE_RELEASE_UNCONFIRMED
            RecordingError.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
            RecordingError.BYTE_LIMIT -> SaveError.BYTE_LIMIT_EXCEEDED
            RecordingError.STORAGE_LOW -> SaveError.STORAGE_FULL
            RecordingError.BUSY -> SaveError.BUSY
            else -> SaveError.INVALID_REQUEST
        }
        else -> SaveError.UNKNOWN_TECHNICAL_ERROR
    }
}
