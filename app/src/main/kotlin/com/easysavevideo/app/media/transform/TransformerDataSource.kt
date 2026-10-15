package com.easysavevideo.app.media.transform

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Exact private routing to registered capabilities; each reader has an independent logical cursor. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerDataSource(items: List<TransformerEditorItem>, private val session: TransformerSession) : DataSource.Factory {
    private val sources = items.associate { it.address to it.source }
    override fun createDataSource(): DataSource = object : DataSource {
        private var input: TransformerReadHandle? = null
        private var current: Uri? = null
        private var position = 0L
        private var remaining = 0L
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun getUri(): Uri? = current
        override fun open(dataSpec: DataSpec): Long {
            session.checkpoint()
            val source = sources[dataSpec.uri.toString()] ?: reject()
            if (input != null || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null ||
                dataSpec.httpRequestHeaders.isNotEmpty() || dataSpec.position < 0 || dataSpec.length < -1) reject()
            val opened = session.openInput(source); input = opened
            try {
                opened.verifyIdentity()
                if (dataSpec.position > opened.extent) reject()
                remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) opened.extent - dataSpec.position else dataSpec.length
                if (remaining > opened.extent - dataSpec.position) reject()
                position = dataSpec.position; current = dataSpec.uri
                return remaining
            } catch (failure: Throwable) { close(); throw failure }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            session.checkpoint()
            if (offset < 0 || length < 0 || offset > buffer.size - length) reject()
            val source = input ?: reject()
            source.verifyIdentity()
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val requested = minOf(length.toLong(), remaining, 64 * 1024L).toInt()
            val read = source.readAt(position, buffer, offset, requested)
            if (read <= 0 || read > requested) reject()
            session.transferred(read)
            position += read; remaining -= read
            return read
        }
        override fun close() {
            input?.let { session.closeInput(it) }
            input = null; current = null; position = 0; remaining = 0
        }
    }
    private fun reject(): Nothing = throw IOException("TRANSFORM_SOURCE_REJECTED")
}
