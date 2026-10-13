package com.easysavevideo.app.editor.ui

import com.easysavevideo.core.model.EditorCompressionMode
import com.easysavevideo.core.model.EditorSettings
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.edit.EditCompression

/** Consume preferences once, when starting a new draft; never rewrite restored or user-edited parameters. */
internal fun openEditorDraft(current: EditorDraft, item: MediaItem, settings: EditorSettings, startNew: Boolean): EditorDraft {
    val draft = if (startNew) EditorDraft(compression = when (settings.defaultCompression) {
        EditorCompressionMode.ORIGINAL_QUALITY -> EditCompression.ORIGINAL_QUALITY
        EditorCompressionMode.SAVE_SPACE -> EditCompression.SAVE_SPACE
    }) else current
    return if (draft.segments.any { it.reference == item.readRef() }) draft else addEditorSegment(draft, item)
}
