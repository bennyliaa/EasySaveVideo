package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorStatusResourcesTest {
    @Test fun completionRequiresPublishedReference() {
        assertEquals(R.string.editor_status_10, editorPhaseResource(EditorPhase.SUCCEEDED, false))
        assertEquals(R.string.editor_status_9, editorPhaseResource(EditorPhase.SUCCEEDED, true))
        EditorPhase.entries.filter { it != EditorPhase.SUCCEEDED }.forEach { phase ->
            assertNotEquals(R.string.editor_status_9, editorPhaseResource(phase, true))
            assertEquals(editorPhaseResource(phase, false), editorPhaseResource(phase, true))
        }
    }
}
