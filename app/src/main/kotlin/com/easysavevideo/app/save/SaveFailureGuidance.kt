package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveRecord

/** Advice is not an execution policy. The sole action navigates; it cannot submit or resume a task. */
internal enum class SaveFailureAdvice(val canReturnToBrowser: Boolean = false) {
    CANCELLED, PROTECTED, CONNECTION_BLOCKED, STORAGE, RECOVERY, RELEASE_PENDING, BUSY,
    AUTHENTICATION(true), SOURCE_CHANGED(true), NETWORK(true), RATE_LIMITED(true),
    REASSESS_METHOD(true), INVALID_OUTPUT(true), BUDGET, UNKNOWN,
    RECORD_CONSENT, RECORD_AUDIO, RECORD_SETUP, RECORD_OUTPUT,
}

internal fun saveFailureAdvice(state: TaskState, method: TaskMethod, error: SaveError?): SaveFailureAdvice? {
    if (state == TaskState.CANCELLED) return SaveFailureAdvice.CANCELLED
    if (state != TaskState.FAILED) return null
    // Error safeguards take precedence over method-specific advice, including inconsistent legacy rows.
    return when (error) {
        SaveError.CANCELLED -> SaveFailureAdvice.CANCELLED
        SaveError.PROTECTED_MEDIA, SaveError.POLICY_BLOCKED -> SaveFailureAdvice.PROTECTED
        SaveError.TLS_REJECTED -> SaveFailureAdvice.CONNECTION_BLOCKED
        SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> SaveFailureAdvice.STORAGE
        SaveError.RESOURCE_RELEASE_UNCONFIRMED -> SaveFailureAdvice.RELEASE_PENDING
        SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED, SaveError.VERIFICATION_UNAVAILABLE -> SaveFailureAdvice.RECOVERY
        SaveError.BUSY -> SaveFailureAdvice.BUSY
        SaveError.BYTE_LIMIT_EXCEEDED -> SaveFailureAdvice.BUDGET
        SaveError.CONSENT_DENIED -> SaveFailureAdvice.RECORD_CONSENT
        SaveError.AUDIO_PERMISSION_DENIED, SaveError.AUDIO_FAILED -> SaveFailureAdvice.RECORD_AUDIO
        SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED, SaveError.PROFILE_UNSUPPORTED -> SaveFailureAdvice.RECORD_SETUP
        SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT, SaveError.KEYFRAME_TIMEOUT,
        SaveError.TIMESTAMP_INVALID, SaveError.ENCODER_FAILED, SaveError.FINALIZATION_FAILED -> SaveFailureAdvice.RECORD_OUTPUT
        else -> if (method !in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)) {
            if (method == TaskMethod.RECORD) SaveFailureAdvice.RECORD_OUTPUT else SaveFailureAdvice.UNKNOWN
        } else when (error) {
            SaveError.AUTHENTICATION_REQUIRED, SaveError.HTTP_REJECTED -> SaveFailureAdvice.AUTHENTICATION
            SaveError.EXPIRED_RESOURCE, SaveError.HTTP_NOT_FOUND, SaveError.INVALID_RANGE,
            SaveError.RESOURCE_CHANGED, SaveError.SOURCE_RESELECTION_REQUIRED -> SaveFailureAdvice.SOURCE_CHANGED
            SaveError.NETWORK_UNAVAILABLE, SaveError.NETWORK_TIMEOUT, SaveError.HTTP_FAILURE,
            SaveError.RETRY_EXHAUSTED, SaveError.DEADLINE_EXCEEDED, SaveError.INCOMPLETE_TRANSFER -> SaveFailureAdvice.NETWORK
            SaveError.RATE_LIMITED -> SaveFailureAdvice.RATE_LIMITED
            SaveError.METHOD_UNSUPPORTED, SaveError.UNSUPPORTED_MEDIA -> SaveFailureAdvice.REASSESS_METHOD
            SaveError.INVALID_MEDIA -> SaveFailureAdvice.INVALID_OUTPUT
            else -> SaveFailureAdvice.UNKNOWN
        }
    }
}

/** No address, source, credential, capture permission or mutable task reference. */
internal data class FailedTaskIdentity(val taskId: String, val revision: Long, val state: TaskState,
    val method: TaskMethod, val error: SaveError?) {
    companion object {
        fun from(record: SaveRecord) = FailedTaskIdentity(record.task.taskId, record.task.revision,
            record.task.state, record.request.method, record.error)
    }
}
internal enum class FailureNavigationResult { ACCEPTED, ALREADY_USED, STALE_TASK, NOT_ALLOWED, UNAVAILABLE }

/** Main-thread, view-lifetime command. Rejections have no effects; one acceptance per explicit button. */
internal class FailureBrowserCommand(private val expected: FailedTaskIdentity) {
    private var used = false
    fun accept(current: FailedTaskIdentity?, available: Boolean): FailureNavigationResult {
        if (used) return FailureNavigationResult.ALREADY_USED
        if (current != expected) return FailureNavigationResult.STALE_TASK
        if (saveFailureAdvice(current.state, current.method, current.error)?.canReturnToBrowser != true)
            return FailureNavigationResult.NOT_ALLOWED
        if (!available) return FailureNavigationResult.UNAVAILABLE
        used = true
        return FailureNavigationResult.ACCEPTED
    }
    val taskId: String get() = expected.taskId
}
