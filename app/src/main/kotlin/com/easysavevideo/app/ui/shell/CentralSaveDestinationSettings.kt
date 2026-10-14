package com.easysavevideo.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationSettings
import com.easysavevideo.app.theme.*

/** The existing global destination-dialog host continues to display operation results once. */
@Composable internal fun CentralSaveDestinationSettings(runtime: AndroidOrdinaryDestinationRuntime?,
    generation: Long, canAct: () -> Boolean) {
    if (runtime == null) EsvText(stringResource(R.string.settings_destination_unavailable),
        style = LocalEsvVisuals.current.captionTextStyle)
    else OrdinaryDestinationSettings(runtime, generation, canAct, showNotice = false)
}
