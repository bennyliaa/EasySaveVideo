package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.R

/** Completion copy requires the exact published reference, not only a phase. */
internal fun editorPhaseResource(phase: EditorPhase, hasPublishedReference: Boolean): Int = when (phase) {
    EditorPhase.IDLE -> R.string.editor_status_0
    EditorPhase.PROBING -> R.string.editor_status_1
    EditorPhase.PREPARING -> R.string.editor_status_2
    EditorPhase.PROCESSING -> R.string.editor_status_3
    EditorPhase.VERIFYING -> R.string.editor_status_4
    EditorPhase.PUBLISHING -> R.string.editor_status_5
    EditorPhase.CANCELLING -> R.string.editor_status_6
    EditorPhase.WAITING_NATIVE_RELEASE -> R.string.editor_status_7
    EditorPhase.BLOCKED -> R.string.editor_status_8
    EditorPhase.SUCCEEDED -> if (hasPublishedReference) R.string.editor_status_9 else R.string.editor_status_10
    EditorPhase.FAILED -> R.string.editor_status_11
    EditorPhase.CANCELLED -> R.string.editor_status_12
}
