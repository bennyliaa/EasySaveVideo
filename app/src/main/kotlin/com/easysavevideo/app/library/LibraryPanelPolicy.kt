package com.easysavevideo.app.library

import com.easysavevideo.library.LibraryFolder
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle

internal data class LibraryPanelLayout(val expanded: Boolean, val detailWidthDp: Float)

internal fun libraryPanelLayout(widthDp: Float, heightDp: Float, fontScale: Float, gapDp: Float): LibraryPanelLayout {
    if (!widthDp.isFinite() || !heightDp.isFinite() || !fontScale.isFinite() || !gapDp.isFinite() ||
        widthDp <= 0 || heightDp <= 0 || fontScale <= 0 || gapDp < 0) return LibraryPanelLayout(false, 0f)
    val scale = fontScale.coerceAtLeast(1f)
    val expanded = widthDp >= maxOf(1080f, 960f * scale + gapDp) && heightDp >= 360f * scale
    return LibraryPanelLayout(expanded, if (expanded) 360f * scale else 0f)
}

/** Resolve only the selected identity in the current snapshot. Never choose an adjacent row. */
internal fun libraryCurrentDetail(selection: LibrarySelection?, items: List<MediaItem>, folders: List<LibraryFolder>): LibrarySelection? =
    when (selection) {
        is LibrarySelection.Media -> items.singleOrNull { it.itemId == selection.id && it.lifecycle == MediaLifecycle.READY }?.let(LibrarySelection::Media)
        is LibrarySelection.Folder -> folders.singleOrNull { it.folderId == selection.id }?.let(LibrarySelection::Folder)
        null -> null
    }
