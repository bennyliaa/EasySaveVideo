package com.easysavevideo.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.theme.*

@Composable
fun LibraryUnavailableScreen(onBack: () -> Unit) {
    val v = LocalEsvVisuals.current
    BackHandler(onBack = onBack)
    EsvBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().widthIn(max = 960.dp).padding(v.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(stringResource(R.string.final_copy_62), style = v.titleTextStyle)
            EsvText(stringResource(R.string.final_copy_63))
            EsvButton(stringResource(R.string.final_copy_64), onClick = onBack)
        }
    }
}
