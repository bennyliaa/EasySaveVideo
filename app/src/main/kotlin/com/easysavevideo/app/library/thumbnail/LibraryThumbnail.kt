package com.easysavevideo.app.library.thumbnail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.thumbnail.ThumbnailState
import com.easysavevideo.library.thumbnail.ThumbnailUnavailable

/** Non-interactive, accessible ordinary-library image; failure never substitutes fabricated media. */
@Composable
fun LibraryThumbnail(item: MediaItem, thumbnails: AndroidLibraryThumbnails, modifier: Modifier = Modifier) {
    val v = LocalEsvVisuals.current
    val state = thumbnails.state(item)
    SideEffect { thumbnails.request(item) }
    val description = when (state) {
        is ThumbnailState.Ready -> "本地视频缩略图"
        ThumbnailState.Waiting -> "正在生成缩略图"
        is ThumbnailState.Unavailable -> if (state.reason == ThumbnailUnavailable.RELEASE_UNCONFIRMED)
            "缩略图资源尚未确认退出，请完全退出应用后重试" else "缩略图暂不可用，可打开详情预览"
    }
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(v.colors.surface)
        .semantics(mergeDescendants = true) {
            contentDescription = "视频缩略图：${item.displayName}"
            stateDescription = description
        }, contentAlignment = Alignment.Center) {
        if (state is ThumbnailState.Ready) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas -> state.frame.draw(canvas.nativeCanvas, size.width, size.height) }
            }
        } else {
            EsvText(description, modifier = Modifier.padding(v.spacingSmall), style = v.captionTextStyle, color = v.colors.muted)
        }
    }
}
