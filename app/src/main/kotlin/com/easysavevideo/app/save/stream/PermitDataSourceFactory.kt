package com.easysavevideo.app.save.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Decorate ONLY the existing LocalOnlyDataSourceFactory. Never pass a network/file fallback.
 * Playlist reads have no media permit. Segment identity is resolved afresh at every open, not by
 * HlsExtractorFactory's first URI. Retries, ranges and concurrent media reads are fail-closed.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class PermitDataSourceFactory(
    private val localOnly: DataSource.Factory,
    private val session: LocalAssetReadSession,
    private val resourceCount: Int,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Source(localOnly.createDataSource())

    private inner class Source(private val delegate: DataSource) : DataSource {
        private var opened = false
        private var permit: LocalAssetReadSession.Permit? = null
        override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun open(spec: DataSpec): Long {
            session.check()
            if (opened) fail()
            val text = spec.uri.toString()
            val match = SEGMENT.matchEntire(text)
            val rawIndex = match?.groupValues?.get(1)
            val index = rawIndex?.toIntOrNull()
            if (text != PLAYLIST && (index == null || index !in 0 until resourceCount || rawIndex != index.toString())) fail()
            if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || spec.httpBody != null || spec.httpRequestHeaders.isNotEmpty()) fail()
            if (index != null && spec.position != 0L) fail()
            try {
                val length = delegate.open(spec)
                opened = true
                if (index != null) permit = session.open(index, spec.position, spec.length, length)
                return length
            } catch (failure: Throwable) {
                session.revoke()
                try { delegate.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                opened = false
                throw failure
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            session.check()
            if (!opened || offset < 0 || length < 0 || offset > buffer.size - length) fail()
            if (length == 0) return 0
            try {
                val count = delegate.read(buffer, offset, minOf(length, 64 * 1024))
                if (count == C.RESULT_END_OF_INPUT) permit?.let(session::sourceEof)
                else {
                    if (count <= 0 || count > length) fail()
                    permit?.let { session.received(it, buffer, offset, count) }
                }
                return count
            } catch (failure: Throwable) { session.revoke(); throw failure }
        }

        override fun close() {
            val held = permit
            permit = null
            try {
                delegate.close() // Existing owner remains responsible for actual-release fencing.
                if (held != null) session.close(held)
            } catch (failure: Throwable) { session.revoke(); throw failure }
            finally { opened = false }
        }
        private fun fail(): Nothing { session.revoke(); throw IOException("LOCAL_ASSET_REJECTED") }
    }
    private companion object {
        const val PLAYLIST = "esv-local:///index.m3u8"
        val SEGMENT = Regex("esv-local:///s([0-9]{1,5})\\.ts")
    }
}
