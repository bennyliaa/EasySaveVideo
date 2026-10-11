package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.EditCompression
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.NormalizedCrop
import java.io.*
import java.util.Base64

enum class EditorDraftRestoration { NONE, RESTORING, OWNER_KEPT, RESTORED_REVIEW,
    INTERRUPTED_REVIEW, INVALID, STALE, UNAVAILABLE, CAPABILITY_UNAVAILABLE, USER_CHANGED }

internal data class EditorCheckpointSegment(val id: Long, val reference: MediaReadRef,
    val start: String, val end: String, val crop: NormalizedCrop)

/** Only bounded draft parameters and stable references. No names, paths, output or task authority. */
internal class EditorDraftCheckpoint(val segments: List<EditorCheckpointSegment>, val compression: EditCompression,
    val selectedId: Long?, val nextId: Long, val priorBusy: Boolean) {
    override fun toString() = "EditorDraftCheckpoint([redacted])"
}

internal object EditorDraftCheckpointCodec {
    const val MAX_ENCODED = 12_000
    private const val MAX_BYTES = 8_192
    fun capture(state: EditorViewState): String? = runCatching {
        if (state.draft.segments.isEmpty()) return null
        require(state.draft.nextSegmentId < Long.MAX_VALUE)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(state.draft.compression.name)
            out.writeLong(state.draft.selectedSegmentId ?: 0); out.writeLong(state.draft.nextSegmentId)
            out.writeBoolean(state.busy); out.writeInt(state.draft.segments.size)
            state.draft.segments.forEach { segment ->
                require(segment.startSeconds.length <= 16 && segment.endSeconds.length <= 16)
                out.writeLong(segment.id); out.writeUTF(segment.reference.itemId)
                out.writeLong(segment.reference.revision); out.writeLong(segment.reference.representationGeneration)
                out.writeUTF(segment.startSeconds); out.writeUTF(segment.endSeconds)
                with(segment.crop) { out.writeDouble(left); out.writeDouble(top); out.writeDouble(right); out.writeDouble(bottom) }
            }
        }
        require(bytes.size() <= MAX_BYTES)
        Base64.getEncoder().encodeToString(bytes.toByteArray()).also { require(it.length <= MAX_ENCODED) }
    }.getOrNull()

    fun decode(encoded: String): EditorDraftCheckpoint? = runCatching {
        require(encoded.length in 1..MAX_ENCODED)
        val bytes = Base64.getDecoder().decode(encoded); require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val compression = EditCompression.valueOf(input.readUTF())
            val selected = input.readLong().also { require(it >= 0) }.takeIf { it != 0L }
            val next = input.readLong(); require(next in 1 until Long.MAX_VALUE)
            val busy = input.readBoolean(); val count = input.readInt()
            require(count in 1..EditLimits.MAX_SEGMENTS)
            val segments = List(count) {
                val id = input.readLong(); require(id > 0)
                val itemId = input.readUTF(); require(itemId.length in 1..256)
                val ref = MediaReadRef(itemId, input.readLong(), input.readLong())
                val start = input.readUTF(); val end = input.readUTF(); require(start.length <= 16 && end.length <= 16)
                val crop = NormalizedCrop(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
                EditorCheckpointSegment(id, ref, start, end, crop)
            }
            require(input.available() == 0 && segments.map { it.id }.distinct().size == count)
            require(next > segments.maxOf { it.id } && (selected == null || segments.any { it.id == selected }))
            EditorDraftCheckpoint(segments, compression, selected, next, busy)
        }
    }.getOrNull()
}

/** Catalog metadata only; a snapshot does not grant a reader, entitlement or execution lease. */
internal fun resolveEditorCheckpoint(checkpoint: EditorDraftCheckpoint, lookup: (String) -> MediaItem?): EditorDraft? {
    val items = checkpoint.segments.map { saved ->
        val item = lookup(saved.reference.itemId) ?: return null
        if (item.lifecycle != MediaLifecycle.READY || item.readRef() != saved.reference || editorSourceError(item) != null) return null
        EditorSegment(saved.id, item, saved.start, saved.end, saved.crop)
    }
    return EditorDraft(items, checkpoint.compression, checkpoint.selectedId, checkpoint.nextId)
}

/** Current owner and every newer user draft action outrank an old asynchronous checkpoint. */
internal class EditorDraftRestoreGuard {
    private var revision = 0L
    fun userChanged() { revision++ }
    fun begin(ownerPresent: Boolean): Long? = if (ownerPresent || revision != 0L) null else ++revision
    fun canApply(token: Long, ownerPresent: Boolean): Boolean = token == revision && !ownerPresent
}

internal fun editorCheckpointRestorationStatus(priorBusy: Boolean, capabilitiesAllowed: Boolean): EditorDraftRestoration =
    if (!capabilitiesAllowed) EditorDraftRestoration.CAPABILITY_UNAVAILABLE
    else if (priorBusy) EditorDraftRestoration.INTERRUPTED_REVIEW else EditorDraftRestoration.RESTORED_REVIEW
