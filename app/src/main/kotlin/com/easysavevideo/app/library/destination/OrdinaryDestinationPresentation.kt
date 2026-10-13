package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import com.easysavevideo.library.export.ExportPhase

/** App-generated display state only. No localized text, user data, or persistent wire values. */
enum class OrdinaryDestinationNoticeCode {
    LOCATION_OR_SOURCE_NOT_READY,
    SOURCE_CHANGED_BEFORE_SELECTION,
    REMEMBERED_FOLDER_REQUIRED,
    SOURCE_CHANGED_BEFORE_CREATION,
    FOLDER_PICKER_DETACHED,
    FOLDER_PICKER_NOT_READY,
    FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED,
    FOLDER_PICKER_PENDING,
    FOLDER_PICKER_ASSOCIATION_INVALID,
    FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED,
    FOLDER_PICKER_LAUNCH_FAILED,
    FOLDER_SELECTION_CANCELLED,
    FOLDER_RESULT_UNCONFIRMED,
    FOLDER_GRANT_CAPACITY_REACHED,
    FOLDER_ADOPTION_FAILED,
    FOLDER_SELECTED,
    FOLDER_SELECTION_CHANGED,
    FOLDER_CHANGE_CANCELLED,
    FOLDER_FORGOTTEN,
    FOLDER_PERMISSION_INVALIDATED,
    COPY_STATUS_UNAVAILABLE,
    COPY_CANCELLATION_REQUESTED,
    COPY_CANCELLATION_UNAVAILABLE,
    REMEMBERED_FOLDER_UNAVAILABLE,
    LOCATION_UNAVAILABLE,
    LOCATION_BUSY,
}

internal enum class OrdinaryCopyKind { ALBUM, FOLDER }
internal enum class OrdinaryCopyState {
    NOT_SELECTED, WAITING_FOR_LIBRARY, WAITING_TO_COPY, VERIFIED_SAVED, INCOMPLETE, CHECK_REQUIRED
}
internal data class OrdinaryCopyDisplay(val kind: OrdinaryCopyKind, val state: OrdinaryCopyState)

/** Preserve the original display precedence, including the album label for APP_LIBRARY. */
internal fun ordinaryCopyDisplay(status: OrdinaryDestinationStatus): OrdinaryCopyDisplay {
    val kind = when (status.choice) {
        OrdinaryDestinationChoice.APP_LIBRARY, OrdinaryDestinationChoice.ALBUM_COPY -> OrdinaryCopyKind.ALBUM
        OrdinaryDestinationChoice.FOLDER_COPY -> OrdinaryCopyKind.FOLDER
    }
    val state = when {
        status.choice == OrdinaryDestinationChoice.APP_LIBRARY -> OrdinaryCopyState.NOT_SELECTED
        !status.internalSaved -> OrdinaryCopyState.WAITING_FOR_LIBRARY
        status.copyPhase == null || status.copyPhase == ExportPhase.PREPARED -> OrdinaryCopyState.WAITING_TO_COPY
        status.copyPhase == ExportPhase.SUCCEEDED -> OrdinaryCopyState.VERIFIED_SAVED
        status.copyPhase == ExportPhase.FAILED || status.copyPhase == ExportPhase.CANCELLED -> OrdinaryCopyState.INCOMPLETE
        else -> OrdinaryCopyState.CHECK_REQUIRED
    }
    return OrdinaryCopyDisplay(kind, state)
}
