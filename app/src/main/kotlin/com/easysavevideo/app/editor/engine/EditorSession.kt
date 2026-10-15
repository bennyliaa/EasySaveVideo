package com.easysavevideo.app.editor.engine

import android.media.MediaExtractor
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*

/** Typed Editor facade. Catalog authority and legacy probe/hash stay outside the shared mechanism. */
internal class EditorSession(
    private val inputs: EditorInputPort,
    val context: OperationContext,
    timeoutMillis: Long,
) {
    val native = TransformerSession(
        TransformerSessionLimits(timeoutMillis, EditLimits.STALL_TIMEOUT_MILLIS, EditLimits.RELEASE_TIMEOUT_MILLIS,
            12 * EditLimits.MAX_RECORDED_SEGMENTS, EditorEnginePolicy.MAX_OPEN_INPUTS,
            EditLimits.MAX_TOTAL_SOURCE_BYTES * 8, EditLimits.MAX_RECORDED_SEGMENTS,
            EditorEnginePolicy.MAX_CODECS, EditLimits.MAX_RECORDED_SEGMENTS + 2),
        callerCheckpoint = {
            if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw EditFailure(EditError.CANCELLED)
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) throw EditFailure(EditError.TIMEOUT)
        },
        callerStopReason = { null }, // Editor retains its existing product stop precedence above.
    )
    private val sources = LinkedHashMap<EditInputRef, EditorTransformerSource>()
    private val opened = java.util.IdentityHashMap<EditorOwnedInput, EditorTransformerRead>()
    var probeExtractor: MediaExtractor?
        get() = native.probeExtractor
        set(value) { native.probeExtractor = value }
    fun checkpoint() = native.checkpoint()
    @Synchronized fun source(reference: EditInputRef): TransformerOwnedSource =
        sources.getOrPut(reference) { EditorTransformerSource(inputs, reference) }
    fun retain(reference: EditInputRef) = native.retain(source(reference))
    @Synchronized fun openInput(reference: EditInputRef): EditorOwnedInput {
        val reader = native.openInput(source(reference)) as EditorTransformerRead
        opened[reader.input] = reader
        return reader.input
    }
    @Synchronized fun closeInput(input: EditorOwnedInput) {
        val reader = opened[input] ?: throw EditFailure(EditError.INVALID_STATE)
        native.closeInput(reader)
        opened.remove(input)
    }
}
