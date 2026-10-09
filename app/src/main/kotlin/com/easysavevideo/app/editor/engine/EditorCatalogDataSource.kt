package com.easysavevideo.app.editor.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.EditInputRef
import java.io.IOException

/** A private synthetic address is an index into held catalog refs. No URL/path fallback exists. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EditorCatalogDataSource(private val references: List<EditInputRef>, private val session: EditorSession) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private var input: EditorOwnedInput? = null
        private var current: Uri? = null
        private var remaining = 0L
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun getUri(): Uri? = current
        override fun open(dataSpec: DataSpec): Long {
            session.checkpoint()
            val index = references.indices.firstOrNull { dataSpec.uri.toString() == address(it) } ?: reject()
            if (input != null || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null ||
                dataSpec.httpRequestHeaders.isNotEmpty() || dataSpec.position < 0 || dataSpec.length < -1) reject()
            val opened = session.openInput(references[index]); input = opened
            try {
                if (dataSpec.position > opened.sizeBytes || opened.stream.channel.size() != opened.sizeBytes) reject()
                remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) opened.sizeBytes - dataSpec.position else dataSpec.length
                if (remaining > opened.sizeBytes - dataSpec.position) reject()
                opened.stream.channel.position(dataSpec.position); current = dataSpec.uri
                return remaining
            } catch (failure: Throwable) { close(); throw failure }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            session.checkpoint()
            if (offset < 0 || length < 0 || offset > buffer.size - length) reject()
            val source = input ?: reject()
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val read = source.stream.read(buffer, offset, minOf(length.toLong(), remaining, 64 * 1024L).toInt())
            if (read <= 0) reject()
            session.transferred(read)
            remaining -= read
            return read
        }
        override fun close() {
            input?.let { session.closeInput(it) }
            input = null; current = null; remaining = 0
        }
    }
    companion object { fun address(index: Int): String = "esv-edit:///source-$index.mp4" }
    private fun reject(): Nothing = throw IOException("EDIT_SOURCE_REJECTED")
}
