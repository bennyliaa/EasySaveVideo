package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test

class EditorResponsiveControlsTest {
    @Test fun narrowAndLargeFontControlsStackWithoutShrinkingText() {
        assertTrue(editorStackPair(320f, 1f, 8f))
        assertFalse(editorStackPair(600f, 1f, 8f))
        assertTrue(editorStackPair(600f, 2f, 8f))
        assertFalse(editorStackPair(900f, 2f, 8f))
    }
    @Test fun invalidGeometryUsesConservativeStack() {
        assertTrue(editorStackPair(Float.NaN, 1f, 8f))
        assertTrue(editorStackPair(600f, Float.POSITIVE_INFINITY, 8f))
        assertTrue(editorStackPair(0f, 1f, 8f))
        assertTrue(editorStackPair(600f, 1f, -1f))
    }
    @Test fun unpublishedAndStoppingPhasesNeverExposeCompletedPreview() {
        val ref = MediaReadRef("owned-output", 5, 8)
        EditorPhase.entries.filter { it != EditorPhase.SUCCEEDED }.forEach {
            assertNull(editorPublishedReference(EditorViewState(phase = it, completedReference = ref)))
        }
        assertNull(editorPublishedReference(EditorViewState(phase = EditorPhase.SUCCEEDED)))
    }
    @Test fun onlyExactPublishedReferenceIsExposedAndPhaseTransitionRevokesIt() {
        val ref = MediaReadRef("owned-output", 5, 8)
        val published = EditorViewState(phase = EditorPhase.SUCCEEDED, completedReference = ref)
        assertSame(ref, editorPublishedReference(published))
        assertNull(editorPublishedReference(published.copy(phase = EditorPhase.PUBLISHING)))
        assertSame(ref, editorPublishedReference(published.copy(foreground = false)))
        assertFalse(published.copy(foreground = false).foreground)
    }
}
