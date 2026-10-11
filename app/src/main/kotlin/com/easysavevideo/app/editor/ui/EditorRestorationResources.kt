package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.R

internal fun editorRestorationResource(status: EditorDraftRestoration): Int? = when (status) {
    EditorDraftRestoration.NONE -> null
    EditorDraftRestoration.RESTORING -> R.string.editor_restore_checking
    EditorDraftRestoration.OWNER_KEPT -> R.string.editor_restore_owner
    EditorDraftRestoration.RESTORED_REVIEW -> R.string.editor_restore_review
    EditorDraftRestoration.INTERRUPTED_REVIEW -> R.string.editor_restore_interrupted
    EditorDraftRestoration.INVALID -> R.string.editor_restore_invalid
    EditorDraftRestoration.STALE -> R.string.editor_restore_stale
    EditorDraftRestoration.UNAVAILABLE -> R.string.editor_restore_unavailable
    EditorDraftRestoration.CAPABILITY_UNAVAILABLE -> R.string.editor_restore_capability
    EditorDraftRestoration.USER_CHANGED -> R.string.editor_restore_newer
}
