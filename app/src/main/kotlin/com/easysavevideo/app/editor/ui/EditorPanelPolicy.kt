package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.MediaReadRef

internal data class EditorPanelLayout(val expanded: Boolean, val previewWidthDp: Float, val sequenceWidthDp: Float)

internal fun editorPanelLayout(widthDp: Float, heightDp: Float, fontScale: Float, gapDp: Float): EditorPanelLayout {
    if (!widthDp.isFinite() || !heightDp.isFinite() || !fontScale.isFinite() || !gapDp.isFinite() ||
        widthDp <= 0 || heightDp <= 0 || fontScale <= 0 || gapDp < 0) return EditorPanelLayout(false, 0f, 0f)
    val scale = fontScale.coerceAtLeast(1f)
    val expanded = widthDp >= maxOf(1240f, 1080f * scale + 2 * gapDp) && heightDp >= 480f * scale
    return EditorPanelLayout(expanded, if (expanded) 360f * scale else 0f, if (expanded) 280f * scale else 0f)
}

/** Explicit request only, never a default selected item or provisional output. */
internal fun editorActivePreview(state: EditorViewState, requested: MediaReadRef?): MediaReadRef? {
    if (requested == null || !state.foreground || state.busy || state.phase == EditorPhase.BLOCKED) return null
    if (requested == editorPublishedReference(state)) return requested
    val selected = state.draft.selectedSegment ?: return null
    return requested.takeIf { it == selected.reference && it in state.displayGeometries }
}
