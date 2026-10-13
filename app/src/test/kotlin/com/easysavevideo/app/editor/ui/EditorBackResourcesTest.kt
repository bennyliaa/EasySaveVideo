package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.R
import org.junit.Assert.*
import org.junit.Test

class EditorBackResourcesTest {
    @Test fun stopFeedbackIsExposedOnlyForItsMatchingPhase() {
        EditorPhase.entries.forEach { phase ->
            EditorStopFeedback.entries.forEach { feedback ->
                val expected = when (phase to feedback) {
                    EditorPhase.CANCELLING to EditorStopFeedback.REQUESTED -> R.string.editor_back_stop_requested
                    EditorPhase.PUBLISHING to EditorStopFeedback.CHECKING_RESULT -> R.string.editor_back_stop_too_late
                    EditorPhase.WAITING_NATIVE_RELEASE to EditorStopFeedback.WAITING_FOR_RELEASE -> R.string.editor_back_stop_waiting
                    else -> null
                }
                assertEquals("$phase / $feedback", expected, editorStopFeedbackResource(phase, feedback))
            }
            assertNull(editorStopFeedbackResource(phase, null))
        }
    }

    @Test fun terminalAndNewOperationTransitionsDropOldFeedback() {
        for (old in listOf(
            EditorViewState(phase = EditorPhase.CANCELLING, stopFeedback = EditorStopFeedback.REQUESTED),
            EditorViewState(phase = EditorPhase.PUBLISHING, stopFeedback = EditorStopFeedback.CHECKING_RESULT),
            EditorViewState(phase = EditorPhase.WAITING_NATIVE_RELEASE, stopFeedback = EditorStopFeedback.WAITING_FOR_RELEASE),
        )) {
            assertEquals(old.stopFeedback, old.copy(foreground = false).stopFeedback)
            for (phase in listOf(EditorPhase.SUCCEEDED, EditorPhase.FAILED, EditorPhase.CANCELLED,
                EditorPhase.BLOCKED, EditorPhase.IDLE, EditorPhase.PREPARING, EditorPhase.PROCESSING)) {
                assertNull(phase.name, old.copy(phase = phase).stopFeedback)
            }
        }
    }

    @Test fun explicitFeedbackUpdateCanAccompanyTheNewWaitingOrPublicationPhase() {
        val cancelling = EditorViewState(phase = EditorPhase.CANCELLING, stopFeedback = EditorStopFeedback.REQUESTED)
        val waiting = cancelling.copy(phase = EditorPhase.WAITING_NATIVE_RELEASE, stopFeedback = EditorStopFeedback.WAITING_FOR_RELEASE)
        assertEquals(R.string.editor_back_stop_waiting, editorStopFeedbackResource(waiting.phase, waiting.stopFeedback))
        val checking = waiting.copy(phase = EditorPhase.PUBLISHING, stopFeedback = EditorStopFeedback.CHECKING_RESULT)
        assertEquals(R.string.editor_back_stop_too_late, editorStopFeedbackResource(checking.phase, checking.stopFeedback))
    }
}
