package com.easysavevideo.app.library.destination

import com.easysavevideo.app.R
import com.easysavevideo.library.destination.OrdinaryDestinationPreference

/** Resource IDs only; the caller resolves text from the current Composition. */
internal fun ordinaryDestinationLabelResource(preference: OrdinaryDestinationPreference): Int = when (preference) {
    OrdinaryDestinationPreference.APP_LIBRARY -> R.string.ordinary_destination_app_library
    OrdinaryDestinationPreference.ASK -> R.string.ordinary_destination_ask
    OrdinaryDestinationPreference.ALBUM_COPY -> R.string.ordinary_destination_album_copy
    OrdinaryDestinationPreference.FOLDER_COPY -> R.string.ordinary_destination_folder_copy
}

internal fun ordinaryDestinationNoticeResource(notice: OrdinaryDestinationNoticeCode): Int = when (notice) {
    OrdinaryDestinationNoticeCode.LOCATION_OR_SOURCE_NOT_READY -> R.string.ordinary_destination_notice_location_or_source_not_ready
    OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_SELECTION -> R.string.ordinary_destination_notice_source_changed_before_selection
    OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED -> R.string.ordinary_destination_notice_remembered_folder_required
    OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION -> R.string.ordinary_destination_notice_source_changed_before_creation
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_DETACHED -> R.string.ordinary_destination_notice_folder_picker_detached
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_NOT_READY -> R.string.ordinary_destination_notice_folder_picker_not_ready
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED -> R.string.ordinary_destination_notice_folder_picker_associations_exhausted
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_PENDING -> R.string.ordinary_destination_notice_folder_picker_pending
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_INVALID -> R.string.ordinary_destination_notice_folder_picker_association_invalid
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED -> R.string.ordinary_destination_notice_folder_picker_association_storage_failed
    OrdinaryDestinationNoticeCode.FOLDER_PICKER_LAUNCH_FAILED -> R.string.ordinary_destination_notice_folder_picker_launch_failed
    OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CANCELLED -> R.string.ordinary_destination_notice_folder_selection_cancelled
    OrdinaryDestinationNoticeCode.FOLDER_RESULT_UNCONFIRMED -> R.string.ordinary_destination_notice_folder_result_unconfirmed
    OrdinaryDestinationNoticeCode.FOLDER_GRANT_CAPACITY_REACHED -> R.string.ordinary_destination_notice_folder_grant_capacity_reached
    OrdinaryDestinationNoticeCode.FOLDER_ADOPTION_FAILED -> R.string.ordinary_destination_notice_folder_adoption_failed
    OrdinaryDestinationNoticeCode.FOLDER_SELECTED -> R.string.ordinary_destination_notice_folder_selected
    OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CHANGED -> R.string.ordinary_destination_notice_folder_selection_changed
    OrdinaryDestinationNoticeCode.FOLDER_CHANGE_CANCELLED -> R.string.ordinary_destination_notice_folder_change_cancelled
    OrdinaryDestinationNoticeCode.FOLDER_FORGOTTEN -> R.string.ordinary_destination_notice_folder_forgotten
    OrdinaryDestinationNoticeCode.FOLDER_PERMISSION_INVALIDATED -> R.string.ordinary_destination_notice_folder_permission_invalidated
    OrdinaryDestinationNoticeCode.COPY_STATUS_UNAVAILABLE -> R.string.ordinary_destination_notice_copy_status_unavailable
    OrdinaryDestinationNoticeCode.COPY_CANCELLATION_REQUESTED -> R.string.ordinary_destination_notice_copy_cancellation_requested
    OrdinaryDestinationNoticeCode.COPY_CANCELLATION_UNAVAILABLE -> R.string.ordinary_destination_notice_copy_cancellation_unavailable
    OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_UNAVAILABLE -> R.string.ordinary_destination_notice_remembered_folder_unavailable
    OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE -> R.string.ordinary_destination_notice_location_unavailable
    OrdinaryDestinationNoticeCode.LOCATION_BUSY -> R.string.ordinary_destination_notice_location_busy
}

internal fun ordinaryCopyKindResource(kind: OrdinaryCopyKind): Int = when (kind) {
    OrdinaryCopyKind.ALBUM -> R.string.ordinary_destination_copy_kind_album
    OrdinaryCopyKind.FOLDER -> R.string.ordinary_destination_copy_kind_folder
}

internal fun ordinaryCopyStateResource(state: OrdinaryCopyState): Int = when (state) {
    OrdinaryCopyState.NOT_SELECTED -> R.string.ordinary_destination_copy_not_selected
    OrdinaryCopyState.WAITING_FOR_LIBRARY -> R.string.ordinary_destination_copy_waiting_for_library
    OrdinaryCopyState.WAITING_TO_COPY -> R.string.ordinary_destination_copy_waiting_to_copy
    OrdinaryCopyState.VERIFIED_SAVED -> R.string.ordinary_destination_copy_verified_saved
    OrdinaryCopyState.INCOMPLETE -> R.string.ordinary_destination_copy_incomplete
    OrdinaryCopyState.CHECK_REQUIRED -> R.string.ordinary_destination_copy_check_required
}
