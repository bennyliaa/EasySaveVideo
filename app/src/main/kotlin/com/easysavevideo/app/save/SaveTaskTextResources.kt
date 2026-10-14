package com.easysavevideo.app.save

import com.easysavevideo.app.R
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.SaveError

internal fun taskMethodTextResource(method: TaskMethod) = when (method) {
    TaskMethod.BROWSER -> R.string.flow_method_browser
    TaskMethod.DIRECT -> R.string.flow_method_direct
    TaskMethod.STREAM -> R.string.flow_method_stream
    TaskMethod.RECORD -> R.string.flow_method_record
    TaskMethod.EDIT -> R.string.flow_method_edit
}

internal fun saveFailureTextResource(error: SaveError) = when (error) {
    SaveError.WIFI_REQUIRED -> R.string.save_wifi_required
    SaveError.CANCELLED -> R.string.flow_error_cancelled
    SaveError.AUTHENTICATION_REQUIRED -> R.string.flow_error_authentication
    SaveError.TLS_REJECTED -> R.string.flow_error_tls
    SaveError.EXPIRED_RESOURCE, SaveError.SOURCE_RESELECTION_REQUIRED,
    SaveError.RESOURCE_CHANGED -> R.string.flow_error_source_changed
    SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> R.string.flow_error_storage
    SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED, SaveError.PROTECTED_MEDIA -> R.string.flow_error_unsupported
    SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER -> R.string.flow_error_unverified
    SaveError.RESOURCE_RELEASE_UNCONFIRMED -> R.string.flow_error_release
    SaveError.RATE_LIMITED -> R.string.flow_error_rate_limit
    else -> R.string.flow_error_incomplete
}
/** Labels reflect the persisted phase; neither progress nor an accepted command implies completion. */
internal fun taskStateTextResource(record: SaveRecord) = when (record.task.state) {
    TaskState.QUEUED -> R.string.flow_status_queued
    TaskState.RUNNING -> when (record.phase) {
        SavePhase.PREPARING -> R.string.flow_status_preparing
        SavePhase.NORMALIZING -> R.string.save_status_normalizing
        SavePhase.ADOPTING -> R.string.save_status_adopting
        SavePhase.VERIFYING -> R.string.flow_status_verifying
        SavePhase.COMMITTING -> R.string.flow_status_committing
        SavePhase.CLEANUP -> R.string.flow_status_cleanup
        SavePhase.CANCELLING -> R.string.flow_status_cancelling
        else -> R.string.flow_status_running
    }
    TaskState.WAITING_USER -> R.string.flow_status_waiting
    TaskState.INTERRUPTED -> R.string.flow_status_interrupted
    TaskState.SUCCEEDED -> R.string.flow_status_completed
    TaskState.FAILED -> R.string.flow_status_failed
    TaskState.CANCELLED -> R.string.flow_status_cancelled
}
