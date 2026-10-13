package com.easysavevideo.app.editor.ui

import com.easysavevideo.app.editor.editorFeatures
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import com.easysavevideo.library.*
import com.easysavevideo.library.edit.*
import org.junit.Assert.*
import org.junit.Test

class EditorDefaultsTest {
    private val item = MediaItem("owned", 3, 5, null, "fixture", 0, MediaDateKind.SAVED, 1024, 1000, 320, 240)
    private val second = item.copy(itemId = "second")
    private val space = EditorSettings(EditorCompressionMode.SAVE_SPACE)
    private val original = EditorSettings()
    @Test fun newDraftConsumesLatestPreferenceAndCompletionStartsFresh() {
        val first = openEditorDraft(EditorDraft(), item, space, true)
        assertEquals(EditCompression.SAVE_SPACE, first.compression)
        val next = openEditorDraft(first, second, original, true)
        assertEquals(EditCompression.ORIGINAL_QUALITY, next.compression)
        assertEquals(listOf(second.readRef()), next.segments.map { it.reference })
    }
    @Test fun appendingRepeatedSelectionAndChangedSettingsKeepUserChoice() {
        val first = openEditorDraft(EditorDraft(), item, space, true).copy(compression = EditCompression.ORIGINAL_QUALITY)
        assertSame(first, openEditorDraft(first, item, space, false))
        val next = openEditorDraft(first, second, space, false)
        assertEquals(EditCompression.ORIGINAL_QUALITY, next.compression)
        assertEquals(2, next.segments.size)
    }
    @Test fun removingAllSegmentsDoesNotReplaceExplicitCompression() {
        val first = openEditorDraft(EditorDraft(), item, original, true).copy(compression = EditCompression.SAVE_SPACE)
        val empty = removeEditorSegment(first, requireNotNull(first.selectedSegmentId))
        assertEquals(EditCompression.SAVE_SPACE, openEditorDraft(empty, second, original, false).compression)
    }
    @Test fun restoredCheckpointKeepsItsOwnModeAgainstOppositeDefaults() {
        for (mode in EditCompression.entries) {
            val first = addEditorSegment(EditorDraft(compression = mode), item)
            val checkpoint = requireNotNull(EditorDraftCheckpointCodec.decode(requireNotNull(EditorDraftCheckpointCodec.capture(EditorViewState(draft = first)))))
            val restored = requireNotNull(resolveEditorCheckpoint(checkpoint) { item })
            val opposite = if (mode == EditCompression.SAVE_SPACE) original else space
            val opened = openEditorDraft(restored, second, opposite, false)
            assertEquals(mode, opened.compression)
            assertEquals(2, opened.segments.size)
        }
    }
    @Test fun preferenceDoesNotGrantCompressionEntitlement() {
        val draft = openEditorDraft(EditorDraft(), item, space, true)
        val required = editorFeatures(draft.toEditRequest("fixture"), mapOf(item.readRef() to 1_000_000L))
        assertEquals(setOf(Feature.VIDEO_COMPRESS), required)
        val decision = FeatureGate().evaluate(FeatureRequest(Feature.VIDEO_COMPRESS), EntitlementSnapshot(),
            PlatformCapabilities(required), BuildFeaturePolicy(), 100L)
        assertFalse(decision.allowed)
        assertEquals(EditCompression.SAVE_SPACE, draft.compression)
    }
}
