package com.easysavevideo.app.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.library.MediaReadRef

/** Geometry only; the runtime remains the owner of input, execution and publication. */
internal fun editorStackPair(widthDp: Float, fontScale: Float, gapDp: Float): Boolean {
    if (!widthDp.isFinite() || widthDp <= 0 || !fontScale.isFinite() || fontScale <= 0 ||
        !gapDp.isFinite() || gapDp < 0) return true
    return widthDp < 360f * fontScale.coerceAtLeast(1f) + gapDp
}

internal fun editorPublishedReference(state: EditorViewState): MediaReadRef? =
    state.completedReference.takeIf { state.phase == EditorPhase.SUCCEEDED }

@Composable
internal fun EditorResponsivePair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val v = LocalEsvVisuals.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (editorStackPair(maxWidth.value, LocalDensity.current.fontScale, v.spacingSmall.value)) {
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}
