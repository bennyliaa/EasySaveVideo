package com.easysavevideo.app.ui

import com.easysavevideo.app.save.PageSaveSelection
import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

class BrowserInspectorLayoutTest {
    private fun pane(width: Float = 1200f, height: Float = 800f, font: Float = 1f) =
        responsivePaneLayout(width, height, font, 16f)

    @Test fun selectionMovesBetweenOneHostWithoutChangingItsIdentity() {
        val selection = PageSaveSelection(BrowserContextRef("tab", "session"), 4,
            "https://example.invalid/owned", emptyList(), Long.MAX_VALUE) { true }
        listOf(pane(), pane(400f), pane(height = 300f), pane(font = 2f), pane()).forEach {
            val layout = browserInspectorLayout(it, true, false, false)
            assertTrue(layout.sidePane.xor(layout.modal))
            assertTrue(selection.isCurrent())
            assertEquals(4L, selection.epoch)
        }
    }
    @Test fun noSelectionHasNoHost() {
        assertEquals(BrowserInspectorLayout(false, false), browserInspectorLayout(pane(), false, false, false))
    }
    @Test fun otherPanelsAndStreamPreparationKeepModalPath() {
        assertEquals(BrowserInspectorLayout(false, true), browserInspectorLayout(pane(), true, false, true))
        assertEquals(BrowserInspectorLayout(false, true), browserInspectorLayout(pane(), false, true, false))
        assertEquals(BrowserInspectorLayout(false, true), browserInspectorLayout(pane(), true, true, false))
    }
    @Test fun onlyReadyIdlePageCanStartInspection() {
        assertTrue(browserInspectEnabled(true, false, false, false))
        assertFalse(browserInspectEnabled(false, false, false, false))
        assertFalse(browserInspectEnabled(true, true, false, false))
        assertFalse(browserInspectEnabled(true, false, true, false))
        assertFalse(browserInspectEnabled(true, false, false, true))
    }
    @Test fun navigationFenceRemainsInvalidAcrossAllPlacements() {
        var epoch = 4L
        var context = BrowserContextRef("tab", "session")
        val original = context
        val selection = PageSaveSelection(original, 4, "https://example.invalid/owned", emptyList(), Long.MAX_VALUE) {
            epoch == 4L && context == original
        }
        assertTrue(selection.isCurrent())
        epoch = 5
        listOf(pane(), pane(400f)).forEach {
            browserInspectorLayout(it, true, false, false)
            assertFalse(selection.isCurrent())
        }
        epoch = 4; context = BrowserContextRef("tab", "different-session")
        assertFalse(selection.isCurrent())
    }
    @Test fun expiredCandidateIsNeverRevivedByWideLayout() {
        val expired = PageSaveSelection(BrowserContextRef("tab", "session"), 4,
            "https://example.invalid/owned", emptyList(), 1) { true }
        assertTrue(browserInspectorLayout(pane(), true, false, false).sidePane)
        assertFalse(expired.isCurrent())
        assertFalse(browserInspectEnabled(true, false, true, false))
    }
}
