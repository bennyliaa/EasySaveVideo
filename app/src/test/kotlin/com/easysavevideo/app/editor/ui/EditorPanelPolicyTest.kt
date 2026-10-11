package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

class EditorPanelPolicyTest {
    private val item = MediaItem("owned-fixture", 3, 5, null, "Owned fixture", 0, MediaDateKind.SAVED, 1024, 1000, 320, 240)
    private val draft = addEditorSegment(EditorDraft(), item)
    private val geometry = EditorDisplayGeometry(320, 240, 1_000_000)
    private val state = EditorViewState(draft = draft, displayGeometries = mapOf(item.readRef() to geometry))

    @Test fun threePanelsRequireWidthHeightAndFontBudgets() {
        assertTrue(editorPanelLayout(1400f, 800f, 1f, 16f).expanded)
        assertFalse(editorPanelLayout(1000f, 800f, 1f, 16f).expanded)
        assertFalse(editorPanelLayout(1400f, 300f, 1f, 16f).expanded)
        assertFalse(editorPanelLayout(1400f, 800f, 2f, 16f).expanded)
        val wide = editorPanelLayout(2500f, 1100f, 2f, 16f)
        assertTrue(wide.expanded)
        assertTrue(2500 - wide.previewWidthDp - wide.sequenceWidthDp - 32 >= 440 * 2)
    }
    @Test fun invalidGeometryCannotEnablePanels() {
        assertFalse(editorPanelLayout(Float.NaN, 800f, 1f, 16f).expanded)
        assertFalse(editorPanelLayout(1400f, 800f, 0f, 16f).expanded)
        assertFalse(editorPanelLayout(1400f, 800f, 1f, -1f).expanded)
    }
    @Test fun noExplicitRequestOrMissingVerifiedGeometryNeverOpensInput() {
        assertNull(editorActivePreview(state, null))
        assertNull(editorActivePreview(state.copy(displayGeometries = emptyMap()), item.readRef()))
        assertEquals(item.readRef(), editorActivePreview(state, item.readRef()))
    }
    @Test fun staleInputRevisionAndSelectionCannotReadAnotherItem() {
        assertNull(editorActivePreview(state, item.readRef().copy(revision = 4)))
        val second = item.copy(itemId = "second")
        val selectedOther = addEditorSegment(draft, second).copy(selectedSegmentId = 2)
        assertNull(editorActivePreview(state.copy(draft = selectedOther), item.readRef()))
    }
    @Test fun hiddenBusyAndBlockedPageCannotKeepPlaying() {
        assertNull(editorActivePreview(state.copy(foreground = false), item.readRef()))
        EditorPhase.entries.filter { state.copy(phase = it).busy || it == EditorPhase.BLOCKED }.forEach {
            assertNull(editorActivePreview(state.copy(phase = it), item.readRef()))
        }
    }
    @Test fun onlySuccessfulPublishedOutputCanPreview() {
        val output = MediaReadRef("owned-output", 7, 8)
        EditorPhase.entries.forEach {
            val actual = editorActivePreview(state.copy(phase = it, completedReference = output), output)
            if (it == EditorPhase.SUCCEEDED) assertEquals(output, actual) else assertNull(actual)
        }
    }
    @Test fun layoutTransitionsKeepDraftAndExactRequestedReference() {
        val before = state.draft
        listOf(1400f to 800f, 400f to 800f, 1400f to 300f, 1400f to 800f).forEach { (w,h) ->
            editorPanelLayout(w,h,1f,16f)
            assertSame(before, state.draft)
            assertEquals(item.readRef(), editorActivePreview(state,item.readRef()))
            assertEquals(1, state.draft.segments.size)
        }
    }
}
