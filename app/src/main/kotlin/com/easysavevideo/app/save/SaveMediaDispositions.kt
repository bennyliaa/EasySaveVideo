package com.easysavevideo.app.save

import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle

/** Display-only reads: a failed lookup stays unknown without suppressing other saved tasks. */
internal fun readSaveMediaDispositions(
    mediaIds: List<String>,
    currentItem: (String) -> MediaItem?,
): Map<String, MediaLifecycle> = buildMap {
    for (id in mediaIds) {
        val item = try { currentItem(id) } catch (_: LibraryFailure) { null }
        if (item != null) put(id, item.lifecycle)
    }
}
