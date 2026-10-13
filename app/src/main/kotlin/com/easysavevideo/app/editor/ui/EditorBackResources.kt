package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.R

/** Never carry a stop message into a terminal result or a later operation's phase. */
internal fun editorStopFeedbackResource(phase: EditorPhase, feedback: EditorStopFeedback?): Int? = when {
    phase == EditorPhase.CANCELLING && feedback == EditorStopFeedback.REQUESTED -> R.string.editor_back_stop_requested
    phase == EditorPhase.PUBLISHING && feedback == EditorStopFeedback.CHECKING_RESULT -> R.string.editor_back_stop_too_late
    phase == EditorPhase.WAITING_NATIVE_RELEASE && feedback == EditorStopFeedback.WAITING_FOR_RELEASE -> R.string.editor_back_stop_waiting
    else -> null
}
