package com.easysavevideo.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
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
            EsvText("普通媒体库", style = v.titleTextStyle)
            EsvText("本地媒体存储尚未就绪。已有资料不会被清除；可以先返回浏览器，稍后再试。")
            EsvButton("返回", onClick = onBack)
        }
    }
}
