package com.easysavevideo.app.ui

import com.easysavevideo.app.R

/** Finite display keys only; no lifecycle, error-code, storage, or resource ownership. */
internal enum class RuntimeMessageCopy(val resource: Int) {
    MAIN_PERSISTENCE_INCOMPLETE(R.string.runtime_message_main_persistence_incomplete),
    MAIN_RECORDED_RESULT_CHANGED(R.string.runtime_message_main_recorded_result_changed),
    MAIN_RECORDED_VERSION_UNAVAILABLE(R.string.runtime_message_main_recorded_version_unavailable),
    MAIN_EDITOR_BUSY(R.string.runtime_message_main_editor_busy),
    MAIN_EDITOR_DRAFT_REJECTED(R.string.runtime_message_main_editor_draft_rejected),
    MAIN_DISPLAY_GEOMETRY_UNAVAILABLE(R.string.runtime_message_main_display_geometry_unavailable),
    MAIN_RECORDING_RECOVERY_NOT_READY(R.string.runtime_message_main_recording_recovery_not_ready),
    MAIN_TABS_RECOVERY_INCOMPLETE(R.string.runtime_message_main_tabs_recovery_incomplete),
    MAIN_SETTINGS_RECOVERY_INCOMPLETE(R.string.runtime_message_main_settings_recovery_incomplete),
    MAIN_FILE_PICKER_UNAVAILABLE(R.string.runtime_message_main_file_picker_unavailable),
    MAIN_SYSTEM_BROWSER_UNAVAILABLE(R.string.runtime_message_main_system_browser_unavailable),
    EDITOR_STOP_WAITING(R.string.editor_back_stop_waiting),
    EDITOR_INTERRUPTED_RECOVERY(R.string.runtime_message_editor_interrupted_recovery),
    EDITOR_RECOVERY_INCOMPLETE(R.string.runtime_message_editor_recovery_incomplete),
    EDITOR_WAIT_MEDIA_TASKS(R.string.runtime_message_editor_wait_media_tasks),
    EDITOR_PROBE_RELEASE_UNCONFIRMED(R.string.runtime_message_editor_probe_release_unconfirmed),
    EDITOR_INVALID_PARAMETERS(R.string.runtime_message_editor_invalid_parameters),
    EDITOR_WAIT_SOURCE_CHECK(R.string.runtime_message_editor_wait_source_check),
    EDITOR_UNCHANGED_PARAMETERS(R.string.runtime_message_editor_unchanged_parameters),
    EDITOR_WAIT_EXISTING_RESOURCES(R.string.runtime_message_editor_wait_existing_resources),
    EDITOR_MEDIA_BUSY(R.string.runtime_message_editor_media_busy),
    EDITOR_PUBLISHED(R.string.runtime_message_editor_published),
    EDITOR_STOP_REQUESTED(R.string.runtime_message_editor_stop_requested),
    EDITOR_ENTITLEMENT_REQUIRED(R.string.runtime_message_editor_entitlement_required),
    EDITOR_QUEUE_FULL(R.string.runtime_message_editor_queue_full),
    EDITOR_RELEASE_UNCONFIRMED(R.string.runtime_message_editor_release_unconfirmed),
    EDITOR_PUBLICATION_CHECKING(R.string.runtime_message_editor_publication_checking),
    EDITOR_ERROR_UNSUPPORTED_PROFILE(R.string.runtime_message_editor_error_unsupported_profile),
    EDITOR_ERROR_INCOMPATIBLE_SEGMENTS(R.string.runtime_message_editor_error_incompatible_segments),
    EDITOR_ERROR_SOURCE_UNAVAILABLE(R.string.runtime_message_editor_error_source_unavailable),
    EDITOR_ERROR_INSUFFICIENT_SPACE(R.string.runtime_message_editor_error_insufficient_space),
    EDITOR_ERROR_CANCELLED(R.string.runtime_message_editor_error_cancelled),
    EDITOR_ERROR_RELEASE_UNCONFIRMED(R.string.runtime_message_editor_error_release_unconfirmed),
    EDITOR_ERROR_TIMEOUT(R.string.runtime_message_editor_error_timeout),
    EDITOR_ERROR_VERIFICATION_FAILED(R.string.runtime_message_editor_error_verification_failed),
    EDITOR_ERROR_LIMIT(R.string.runtime_message_editor_error_limit),
    EDITOR_ERROR_INCOMPLETE(R.string.runtime_message_editor_error_incomplete);
}

/** Same display-only key/argument contract as the existing discovery copy adapters. */
internal fun interface RuntimeMessageText {
    fun render(key: RuntimeMessageCopy, arguments: Array<out Any>): String
    fun get(key: RuntimeMessageCopy, vararg arguments: Any): String = render(key, arguments)
}
