package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.MediaDateKind
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.library.edit.EditCompression
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditGeometry
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.NormalizedCrop
import org.junit.Assert.*
import org.junit.Test

class EditorPresentationTest {
    private fun item(id: String = "source", durationMillis: Long? = 10_000, bytes: Long = 1_024) =
        MediaItem(id, 3, 5, null, "Local video.mp4", 0, MediaDateKind.SAVED, bytes, durationMillis, 1920, 1080)

    @Test fun selectionRetainsExactImmutableSnapshotAndAllowsRepeatedSources() {
        val source = item()
        val first = addEditorSegment(EditorDraft(), source)
        val second = addEditorSegment(first, source)
        assertEquals(1, first.segments.size)
        assertEquals(2, second.segments.size)
        assertNotEquals(second.segments[0].id, second.segments[1].id)
        assertEquals(source.readRef(), second.segments[0].reference)
        val newer = source.copy(revision = 4, representationGeneration = 6)
        assertNotEquals(newer.readRef(), second.segments[0].reference)
        assertEquals(3L, second.segments[0].item.revision)
    }

    @Test fun draftAndViewStateDefensivelyDetachCallerCollections() {
        val source = item()
        val segments = mutableListOf(EditorSegment(1, source))
        val draft = EditorDraft(segments)
        segments.clear()
        assertEquals(1, draft.segments.size)
        val items = mutableListOf(source)
        val geometries = mutableMapOf(source.readRef() to EditorDisplayGeometry(1080, 1920, 10_000_000))
        val state = EditorViewState(draft, items, displayGeometries = geometries)
        items.clear(); geometries.clear()
        assertEquals(1, state.availableItems.size)
        assertEquals(1080, state.displayGeometries.getValue(source.readRef()).width)
        try {
            @Suppress("UNCHECKED_CAST")
            (draft.segments as MutableList<EditorSegment>).clear()
            fail("Draft must not expose a mutable sequence")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun sourceAndSegmentCountsStayBounded() {
        val source = item()
        val draft = (1..EditLimits.MAX_SEGMENTS).fold(EditorDraft()) { result, _ -> addEditorSegment(result, source) }
        assertSame(draft, addEditorSegment(draft, source))
        assertEquals(EditLimits.MAX_SEGMENTS, draft.segments.size)
        assertEquals(100, EditorViewState(availableItems = (1..120).map { item("item_$it") }).availableItems.size)
        assertNotNull(editorSourceError(source.copy(lifecycle = MediaLifecycle.DELETED)))
        assertTrue(addEditorSegment(EditorDraft(), source.copy(lifecycle = MediaLifecycle.DELETE_PENDING)).segments.isEmpty())
        assertTrue(addEditorSegment(EditorDraft(), source.copy(sizeBytes = EditLimits.MAX_SOURCE_BYTES + 1)).segments.isEmpty())
        assertTrue(addEditorSegment(EditorDraft(), source.copy(durationMillis = 1_800_001)).segments.isEmpty())
    }

    @Test fun reorderPreservesCropTrimAndExactReference() {
        var draft = addEditorSegment(addEditorSegment(EditorDraft(), item("one")), item("two"))
        val second = draft.segments[1]
        val crop = NormalizedCrop(0.1, 0.2, 0.9, 0.8)
        draft = updateEditorSegment(draft, second.id) { it.copy(startSeconds = "1.25", endSeconds = "8", crop = crop) }
        val moved = moveEditorSegment(draft, second.id, -1)
        assertEquals(second.id, moved.segments.first().id)
        assertEquals(second.reference, moved.segments.first().reference)
        assertEquals("1.25", moved.segments.first().startSeconds)
        assertEquals(crop, moved.segments.first().crop)
        assertEquals(second.id, moved.selectedSegmentId)
        assertSame(moved, moveEditorSegment(moved, second.id, -1))
        assertSame(moved, moveEditorSegment(moved, second.id, Int.MAX_VALUE))
        assertSame(moved, moveEditorSegment(moved, 100, 1))
    }

    @Test fun removingSelectedSegmentKeepsAValidSelectionAndDoesNotReuseIdentity() {
        var draft = addEditorSegment(addEditorSegment(EditorDraft(), item("one")), item("two"))
        val removedId = requireNotNull(draft.selectedSegmentId)
        draft = removeEditorSegment(draft, removedId)
        assertEquals(draft.segments.single().id, draft.selectedSegmentId)
        val empty = removeEditorSegment(draft, draft.segments.single().id)
        assertTrue(empty.segments.isEmpty())
        assertNull(empty.selectedSegmentId)
        assertTrue(addEditorSegment(empty, item()).segments.single().id > removedId)
        assertSame(empty, removeEditorSegment(empty, 999))
    }

    @Test fun strictTimeParsingRejectsAmbiguityAndOverflow() {
        assertEquals(0L, parseEditorSeconds("0"))
        assertEquals(250_000L, parseEditorSeconds("0.25"))
        assertEquals(1_234_567L, parseEditorSeconds("1.234567"))
        assertEquals(1_800_000_000L, parseEditorSeconds("1800"))
        listOf("", " ", " 1", "1 ", "1.", ".25", "-1", "+1", "NaN", "Infinity", "1e3", "1,25", "1.2345678",
            "1800.000001", "9223372036854775807", "１２").forEach { assertNull("Must reject $it", parseEditorSeconds(it)) }
        listOf(0L, 1L, 250_000L, 1_234_567L, EditLimits.MAX_SOURCE_DURATION_US).forEach {
            assertEquals(it, parseEditorSeconds(editorSeconds(it)))
        }
    }

    @Test fun trimsRequireMinimumDurationAndVerifiedSourceBounds() {
        val segment = EditorSegment(1, item(), "0", "0.25")
        assertNull(editorSegmentError(segment))
        assertNotNull(editorSegmentError(segment.copy(endSeconds = "0.249999")))
        assertNotNull(editorSegmentError(segment.copy(startSeconds = "5", endSeconds = "5")))
        assertNotNull(editorSegmentError(segment.copy(endSeconds = "10.000001")))
        assertNotNull(editorSegmentError(segment.copy(endSeconds = "8"), EditorDisplayGeometry(1920, 1080, 7_000_000)))
        assertNull(editorSegmentError(segment.copy(endSeconds = "8")))
        assertNotNull(editorSegmentError(EditorSegment(2, item(durationMillis = null))))
    }

    @Test fun totalOutputAndDistinctInputBytesAreBounded() {
        val fullLength = item(durationMillis = 1_800_000)
        val longSequence = addEditorSegment(addEditorSegment(EditorDraft(), fullLength), fullLength)
        assertNotNull(editorDraftError(longSequence))
        val maxSize = item(bytes = EditLimits.MAX_SOURCE_BYTES)
        val repeats = addEditorSegment(addEditorSegment(EditorDraft(), maxSize), maxSize)
        assertNull(editorDraftError(repeats))
        val huge = addEditorSegment(addEditorSegment(addEditorSegment(EditorDraft(), maxSize), item("two", bytes = maxSize.sizeBytes)),
            item("three", bytes = 1))
        assertNotNull(editorDraftError(huge))
    }

    @Test fun submittedRequestMatchesVisibleOrderCropTrimAndQuality() {
        var draft = addEditorSegment(addEditorSegment(EditorDraft(), item("one")), item("two"))
        val selected = requireNotNull(draft.selectedSegment)
        draft = updateEditorSegment(draft, selected.id) {
            it.copy(startSeconds = "0.25", endSeconds = "1.75", crop = NormalizedCrop(0.1, 0.1, 0.9, 0.9))
        }
        draft = moveEditorSegment(draft, selected.id, -1).copy(compression = EditCompression.SAVE_SPACE)
        val request = draft.toEditRequest("operation_one")
        assertEquals(EditCompression.SAVE_SPACE, request.compression)
        assertEquals(selected.reference, request.segments.first().source)
        assertEquals(250_000L, request.segments.first().startUs)
        assertEquals(1_750_000L, request.segments.first().endUsExclusive)
        assertEquals(draft.segments.first().crop, request.segments.first().crop)
        try { EditorDraft().toEditRequest("empty"); fail("Empty draft cannot submit") } catch (_: EditFailure) { }
    }

    @Test fun doubleSubmitLatchRequiresAnAcknowledgedAttemptRevision() {
        val guard = EditorSubmitGuard()
        assertFalse(guard.trySubmit(0, false))
        assertTrue(guard.trySubmit(0, true))
        assertTrue(guard.isPending(0))
        assertFalse(guard.trySubmit(0, true))
        assertFalse(guard.trySubmit(1, false))
        assertTrue(guard.trySubmit(1, true))
        assertFalse(guard.trySubmit(1, true))
    }

    @Test fun editingAndStatusNeverTreatNativeReleaseOrUnconfirmedPublicationAsSuccess() {
        val blocked = EditorViewState(phase = EditorPhase.WAITING_NATIVE_RELEASE)
        assertTrue(blocked.busy)
        assertFalse(blocked.editable)
        assertTrue(editorPhaseText(blocked).contains("仍未完成"))
        assertFalse(EditorViewState(phase = EditorPhase.BLOCKED).editable)
        assertFalse(EditorViewState(foreground = false).editable)
        assertTrue(editorPhaseText(EditorViewState(phase = EditorPhase.SUCCEEDED)).contains("尚未确认"))
        val verified = EditorViewState(phase = EditorPhase.SUCCEEDED, completedReference = item().readRef())
        assertTrue(editorPhaseText(verified).startsWith("新视频已验证并入库"))
        EditorPhase.entries.forEach { assertTrue(editorPhaseText(EditorViewState(phase = it)).isNotBlank()) }
    }

    @Test fun eightResizeHandlesAndMoveHaveDistinctHitTargets() {
        val crop = NormalizedCrop(0.2, 0.2, 0.8, 0.8)
        val points = mapOf(
            EditorCropHandle.TOP_LEFT to (0.2 to 0.2), EditorCropHandle.TOP_RIGHT to (0.8 to 0.2),
            EditorCropHandle.BOTTOM_LEFT to (0.2 to 0.8), EditorCropHandle.BOTTOM_RIGHT to (0.8 to 0.8),
            EditorCropHandle.LEFT to (0.2 to 0.5), EditorCropHandle.TOP to (0.5 to 0.2),
            EditorCropHandle.RIGHT to (0.8 to 0.5), EditorCropHandle.BOTTOM to (0.5 to 0.8),
            EditorCropHandle.MOVE to (0.5 to 0.5),
        )
        points.forEach { (handle, point) -> assertEquals(handle, editorCropHit(crop, point.first, point.second, 0.05, 0.05)) }
        assertNull(editorCropHit(crop, 0.0, 0.0, 0.05, 0.05))
        assertNull(editorCropHit(crop, -0.01, 0.5, 0.05, 0.05))
        assertNull(editorCropHit(crop, Double.NaN, 0.5, 0.05, 0.05))
    }

    @Test fun draggingClampsInsideDisplayContentAndNeverFlipsCorners() {
        val crop = NormalizedCrop(0.2, 0.2, 0.8, 0.8)
        EditorCropHandle.entries.forEach { handle ->
            listOf(-100.0, -0.3, 0.0, 0.3, 100.0).forEach { delta ->
                val result = dragEditorCrop(crop, handle, delta, -delta)
                assertTrue(result.left >= 0 && result.top >= 0 && result.right <= 1 && result.bottom <= 1)
                assertTrue(result.left < result.right && result.top < result.bottom)
                assertTrue(result.right - result.left >= 0.02 - 1e-10)
                assertTrue(result.bottom - result.top >= 0.02 - 1e-10)
            }
        }
        val moved = dragEditorCrop(crop, EditorCropHandle.MOVE, 3.0, -3.0)
        assertEquals(0.4, moved.left, 1e-10)
        assertEquals(1.0, moved.right, 1e-10)
        assertEquals(0.0, moved.top, 1e-10)
        assertEquals(0.6, moved.bottom, 1e-10)
        assertEquals(crop, dragEditorCrop(crop, EditorCropHandle.LEFT, Double.NaN, 0.0))
        assertEquals(crop.top, dragEditorCrop(crop, EditorCropHandle.LEFT, 0.1, 0.5).top, 0.0)
    }

    @Test fun letterboxBarsAreExcludedBeforeHitTesting() {
        val content = EditGeometry.viewportContent(1_000.0, 1_000.0, 1920, 1080)
        val outsideY = (100.0 - content.top) / content.height
        assertNull(editorCropHit(NormalizedCrop.FULL, 0.5, outsideY, 0.03, 0.03))
        val videoY = (500.0 - content.top) / content.height
        assertEquals(EditorCropHandle.MOVE, editorCropHit(NormalizedCrop.FULL, 0.5, videoY, 0.03, 0.03))
    }

    @Test fun subPixelCropCannotBeConfirmedEvenWhenNormalizedCoordinatesAreValid() {
        val geometry = EditorDisplayGeometry(100, 100, 1_000_000)
        assertNull(editorCropError(NormalizedCrop.FULL, geometry))
        assertNotNull(editorCropError(NormalizedCrop(0.5, 0.5, 0.505, 0.505), geometry))
        assertNotNull(editorSegmentError(EditorSegment(1, item(), "0", "1", NormalizedCrop(0.5, 0.5, 0.505, 0.505)), geometry))
    }

    @Test fun presentationDebugStringsDoNotExposeNamesOrReferences() {
        val source = item("private_item")
        val draft = addEditorSegment(EditorDraft(), source)
        listOf(draft.toString(), draft.segments.single().toString(), EditorViewState(draft).toString()).forEach {
            assertFalse(it.contains("Local video"))
            assertFalse(it.contains("private_item"))
        }
    }
    @Test fun requestedAndSnappedRangesRemainSeparateImmutableAndCopyable() {
        val range = EditorExecutionRange(10_000, 1_000_000, 33_333, 1_000_000, 29)
        val mutable = mutableListOf(range)
        val state = EditorViewState(executionRanges = mutable)
        mutable.clear()
        assertEquals(10_000L, state.executionRanges.single().requestedStartUs)
        assertEquals(33_333L, state.executionRanges.single().executionStartUs)
        assertEquals(966_667L, state.executionRanges.single().durationUs)
        assertEquals(state.executionRanges, state.copy(phase = EditorPhase.SUCCEEDED).executionRanges)
        assertTrue(state.copy(executionRanges = emptyList()).executionRanges.isEmpty())
    }

}
