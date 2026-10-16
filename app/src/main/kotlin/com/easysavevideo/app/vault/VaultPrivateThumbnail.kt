package com.easysavevideo.app.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.easysavevideo.app.R
import com.easysavevideo.app.library.thumbnail.AndroidThumbnailFrame
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.library.thumbnail.ThumbnailState
import com.easysavevideo.library.thumbnail.ThumbnailUnavailable

/** Generic semantics and an opaque guarded draw only; composing a row never requests a decode. */
@Composable internal fun VaultPrivateThumbnail(state: ThumbnailState<VaultThumbnailFrame>, modifier: Modifier = Modifier) {
    val visuals = LocalEsvVisuals.current
    val ready = (state as? ThumbnailState.Ready<VaultThumbnailFrame>)?.frame?.takeIf { it.display.isCurrent() }
    val description = stringResource(when {
        ready != null -> R.string.vault_thumbnail_ready
        state is ThumbnailState.Waiting -> R.string.vault_thumbnail_loading
        state is ThumbnailState.Unavailable && state.reason == ThumbnailUnavailable.RELEASE_UNCONFIRMED -> R.string.vault_thumbnail_cleanup
        state is ThumbnailState.Unavailable && state.reason == ThumbnailUnavailable.INACTIVE -> R.string.vault_thumbnail_inactive
        else -> R.string.vault_thumbnail_unavailable
    })
    val label = stringResource(R.string.vault_thumbnail_description)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(visuals.colors.surface)
        .clearAndSetSemantics { contentDescription = label; stateDescription = description }, contentAlignment = Alignment.Center) {
        if (ready != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    ready.drawIfCurrent { opaque ->
                        (opaque as? AndroidThumbnailFrame)?.draw(canvas.nativeCanvas, size.width, size.height)
                    }
                }
            }
        } else {
            EsvText(description, modifier = Modifier.padding(visuals.spacingSmall),
                style = visuals.captionTextStyle, color = visuals.colors.muted)
        }
    }
}
