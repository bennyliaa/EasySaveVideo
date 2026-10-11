package com.easysavevideo.app.library.thumbnail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
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
fun LibraryThumbnail(item: MediaItem, thumbnails: AndroidLibraryThumbnails, modifier: Modifier = Modifier, visible: Boolean = false) {
    val v = LocalEsvVisuals.current
    val state = thumbnails.state(item)
    SideEffect { if (visible) thumbnails.request(item) else thumbnails.cancel(item) }
    DisposableEffect(thumbnails, item.itemId, item.revision, item.representationGeneration) {
        onDispose { thumbnails.cancel(item) }
    }
    val description = stringResource(when (state) {
        is ThumbnailState.Ready -> R.string.thumbnail_ready
        ThumbnailState.Waiting -> R.string.thumbnail_waiting
        is ThumbnailState.Unavailable -> when (state.reason) {
            ThumbnailUnavailable.INACTIVE -> R.string.thumbnail_inactive
            ThumbnailUnavailable.RELEASE_UNCONFIRMED -> R.string.thumbnail_stalled
            else -> R.string.thumbnail_unavailable
        }
    })
    val contentLabel = stringResource(R.string.thumbnail_description, item.displayName)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(v.colors.surface)
        .semantics(mergeDescendants = true) {
            contentDescription = contentLabel
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
