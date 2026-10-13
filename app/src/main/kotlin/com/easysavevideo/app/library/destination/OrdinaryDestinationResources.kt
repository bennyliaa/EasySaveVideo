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
