package com.easysavevideo.app.editor.engine

import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditError
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream

/** Implement only with the current catalog's revision+generation checked read APIs. */
interface EditorInputPort {
    fun open(reference: MediaReadRef): AndroidSaveStorage.LibraryMediaInput
    fun retainNativeReader(reference: MediaReadRef): AutoCloseable
    fun openRecorded(reference: RecordedSegmentReadRef): EditorOwnedInput = throw EditFailure(EditError.INVALID_REQUEST)
    fun retainRecorded(reference: RecordedSegmentReadRef): AutoCloseable = throw EditFailure(EditError.INVALID_REQUEST)
    fun openTyped(reference: EditInputRef): EditorOwnedInput = when (reference) {
        is MediaReadRef -> open(reference).let { input -> EditorOwnedInput(input.stream, input.sizeBytes, input.sha256, input::close) }
        is RecordedSegmentReadRef -> openRecorded(reference)
    }
    fun retainTyped(reference: EditInputRef): AutoCloseable = when (reference) {
        is MediaReadRef -> retainNativeReader(reference)
        is RecordedSegmentReadRef -> retainRecorded(reference)
    }
}

/** Owned descriptor, independent of catalog, Save receipts and all external locators. */
class EditorOwnedInput internal constructor(val stream: FileInputStream, val sizeBytes: Long, val sha256: String,
    private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
    override fun toString() = "EditorOwnedInput([redacted])"
}

/**
 * Issued by the storage owner for one private, empty staging artifact. Never accept a path from UI,
 * an intent, a Save receipt, or imported metadata. The engine alone consumes this capability.
 * openWriter must retain the storage writer fence; retainNativeWriter must retain the independent
 * native writer fence. Neither may let storage remove/reuse the artifact until actual close.
 */
class EditorOwnedOutput internal constructor(
    internal val file: File,
    internal val openWriter: () -> FileOutputStream,
    internal val retainNativeWriter: () -> AutoCloseable,
    internal val verifyIdentity: () -> Unit,
    internal val availableBytes: () -> Long,
) {
    override fun toString() = "EditorOwnedOutput([redacted])"
}

/** Success proves engine release only. Independent verification and owned publication are required. */
data class EditorEngineEvidence(val bytesWritten: Long, val videoSamples: Long, val audioSamples: Long)
