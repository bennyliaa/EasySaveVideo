package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.*
import com.easysavevideo.library.edit.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

class EditorDraftCheckpointTest {
    private val item = MediaItem("owned", 3, 5, null, "Fixture name not saved", 0, MediaDateKind.SAVED, 1024, 1000, 320, 240)
    private val second = item.copy(itemId = "second")
    private val crop = NormalizedCrop(0.1, 0.1, 0.9, 0.9)
    private val draft = addEditorSegment(addEditorSegment(EditorDraft(), item), second).let {
        updateEditorSegment(it, 1) { s -> s.copy(startSeconds = "0.25", endSeconds = "0.75", crop = crop) }
    }.copy(compression = EditCompression.SAVE_SPACE, selectedSegmentId = 1)
    private val encoded get() = requireNotNull(EditorDraftCheckpointCodec.capture(EditorViewState(draft = draft)))
    private fun lookup(id: String) = listOf(item,second).singleOrNull { it.itemId == id }

    @Test fun rotationCheckpointRestoresExactOrderParametersAndSelection() {
        val checkpoint = requireNotNull(EditorDraftCheckpointCodec.decode(encoded))
        val restored = requireNotNull(resolveEditorCheckpoint(checkpoint, ::lookup))
        assertEquals(draft.segments.map { it.reference }, restored.segments.map { it.reference })
        assertEquals(draft.selectedSegmentId, restored.selectedSegmentId)
        assertEquals(draft.nextSegmentId, restored.nextSegmentId)
        assertEquals(EditCompression.SAVE_SPACE, restored.compression)
        assertEquals("0.25", restored.segments[0].startSeconds)
        assertEquals(crop, restored.segments[0].crop)
        assertTrue(encoded.length <= EditorDraftCheckpointCodec.MAX_ENCODED)
    }
    @Test fun checkpointExcludesNamesOutputsGeometryAndTaskOwnership() {
        val state = EditorViewState(draft = draft, phase = EditorPhase.SUCCEEDED,
            completedReference = MediaReadRef("output-never-saved", 9, 9),
            displayGeometries = mapOf(item.readRef() to EditorDisplayGeometry(320,240,1_000_000)))
        val text = String(Base64.getDecoder().decode(EditorDraftCheckpointCodec.capture(state)))
        assertFalse(text.contains(item.displayName))
        assertFalse(text.contains("output-never-saved"))
        val restored = resolveEditorCheckpoint(requireNotNull(EditorDraftCheckpointCodec.decode(encoded)), ::lookup)
        val coldState = EditorViewState(draft = requireNotNull(restored))
        assertNull(coldState.completedReference)
        assertTrue(coldState.displayGeometries.isEmpty())
        assertFalse(coldState.busy)
        assertNull(editorActivePreview(coldState, item.readRef()))
    }
    @Test fun malformedVersionTruncatedTrailingAndOversizedStateAreRejected() {
        assertNull(EditorDraftCheckpointCodec.decode("!"))
        assertNull(EditorDraftCheckpointCodec.decode("a".repeat(EditorDraftCheckpointCodec.MAX_ENCODED + 1)))
        val bytes = Base64.getDecoder().decode(encoded)
        assertNull(EditorDraftCheckpointCodec.decode(Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 1))))
        assertNull(EditorDraftCheckpointCodec.decode(Base64.getEncoder().encodeToString(bytes + byteArrayOf(1))))
        bytes[3] = 2
        assertNull(EditorDraftCheckpointCodec.decode(Base64.getEncoder().encodeToString(bytes)))
    }
    @Test fun missingDeletedAndChangedReferencesNeverReauthorizeOldDraft() {
        val checkpoint = requireNotNull(EditorDraftCheckpointCodec.decode(encoded))
        assertNull(resolveEditorCheckpoint(checkpoint) { null })
        assertNull(resolveEditorCheckpoint(checkpoint) { id -> lookup(id)?.copy(lifecycle = MediaLifecycle.DELETED) })
        assertNull(resolveEditorCheckpoint(checkpoint) { id -> lookup(id)?.copy(revision = 4) })
        assertNull(resolveEditorCheckpoint(checkpoint) { id -> lookup(id)?.copy(representationGeneration = 6) })
    }
    @Test fun illegalCountsExhaustedSegmentIdsAndInvalidAuthorityAreRejected() {
        val bytes = Base64.getDecoder().decode(encoded)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.readInt(); input.readUTF(); input.readLong()
        val nextOffset = bytes.size - input.available(); input.readLong(); input.readBoolean()
        val countOffset = bytes.size - input.available(); input.readInt(); input.readLong(); input.readUTF()
        val revisionOffset = bytes.size - input.available()
        val exhausted = bytes.copyOf(); ByteBuffer.wrap(exhausted).putLong(nextOffset, Long.MAX_VALUE)
        val excess = bytes.copyOf(); ByteBuffer.wrap(excess).putInt(countOffset, EditLimits.MAX_SEGMENTS + 1)
        val invalidRef = bytes.copyOf(); ByteBuffer.wrap(invalidRef).putLong(revisionOffset, 0)
        listOf(exhausted, excess, invalidRef).forEach {
            assertNull(EditorDraftCheckpointCodec.decode(Base64.getEncoder().encodeToString(it)))
        }
    }
    @Test fun resolverUsesOnlyMetadataLookupAndNoFallbackSelection() {
        var lookups = 0
        val restored = resolveEditorCheckpoint(requireNotNull(EditorDraftCheckpointCodec.decode(encoded))) { id -> lookups++;lookup(id) }
        assertNotNull(restored)
        assertEquals(2, lookups)
        assertNull(resolveEditorCheckpoint(requireNotNull(EditorDraftCheckpointCodec.decode(encoded))) { second })
    }
    @Test fun liveOwnerAndNewUserSelectionAlwaysWinEvenAfterClearingDraft() {
        val occupied = EditorDraftRestoreGuard()
        assertNull(occupied.begin(true))
        val guard = EditorDraftRestoreGuard(); val token = requireNotNull(guard.begin(false))
        assertTrue(guard.canApply(token,false))
        guard.userChanged() // New selection, including a subsequently cleared draft.
        assertFalse(guard.canApply(token,false))
        assertFalse(guard.canApply(token,true))
        assertNull(guard.begin(false))
    }
    @Test fun repeatedRestoreCannotCreateASecondAdmission() {
        val guard = EditorDraftRestoreGuard(); val first = requireNotNull(guard.begin(false))
        assertNull(guard.begin(false))
        assertTrue(guard.canApply(first,false))
    }
    @Test fun activeCheckpointRestoresReviewOnlyAndCapabilityDenialIsExplicit() {
        val busy = EditorViewState(draft = draft, phase = EditorPhase.PROCESSING)
        assertTrue(requireNotNull(EditorDraftCheckpointCodec.decode(requireNotNull(EditorDraftCheckpointCodec.capture(busy)))).priorBusy)
        assertEquals(EditorDraftRestoration.INTERRUPTED_REVIEW, editorCheckpointRestorationStatus(true,true))
        assertEquals(EditorDraftRestoration.CAPABILITY_UNAVAILABLE, editorCheckpointRestorationStatus(true,false))
        assertEquals(EditorDraftRestoration.CAPABILITY_UNAVAILABLE, editorCheckpointRestorationStatus(false,false))
        assertEquals(EditorDraftRestoration.RESTORED_REVIEW, editorCheckpointRestorationStatus(false,true))
    }
    @Test fun incompleteUserTimeInputIsPreservedWithoutBecomingExecutable() {
        val partial = updateEditorSegment(draft,1) { it.copy(endSeconds = ".") }
        val checkpoint = requireNotNull(EditorDraftCheckpointCodec.decode(requireNotNull(EditorDraftCheckpointCodec.capture(EditorViewState(draft=partial)))))
        val restored = requireNotNull(resolveEditorCheckpoint(checkpoint,::lookup))
        assertEquals(".", restored.segments[0].endSeconds)
        assertNotNull(editorDraftError(restored))
    }
    @Test fun emptyDraftHasNoCheckpointAndEveryRestoreStateHasFeedback() {
        assertNull(EditorDraftCheckpointCodec.capture(EditorViewState()))
        EditorDraftRestoration.entries.forEach {
            if (it == EditorDraftRestoration.NONE) assertNull(editorRestorationResource(it)) else assertNotNull(editorRestorationResource(it))
        }
    }
}
