package com.easysavevideo.app.vault.thumbnail

import android.media.MediaDataSource
import com.easysavevideo.app.library.thumbnail.SharedThumbnailRetriever
import com.easysavevideo.app.vault.VaultMediaLimits
import com.easysavevideo.app.vault.VaultThumbnailReadPort
import java.io.IOException

internal interface VaultThumbnailControlledInput : AutoCloseable {
    fun configure(retriever: SharedThumbnailRetriever)
}

/** Android's random-access input is backed only by the existing authenticated controlled reader. */
internal class AndroidVaultThumbnailDataSource(read: VaultThumbnailReadPort) : MediaDataSource(), VaultThumbnailControlledInput {
    private val transport = VaultThumbnailTransport(read)
    override fun configure(retriever: SharedThumbnailRetriever) = retriever.controlledInput(this)
    override fun getSize(): Long = transport.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        transport.readAt(position, buffer, offset, size)
    override fun close() = transport.close()
}

/** Production transport logic is independently executable with public fake read ports on the JVM. */
internal class VaultThumbnailTransport(private val read: VaultThumbnailReadPort) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var open = false
    init {
        read.requireCurrent()
        read.transportOpened()
        open = true
    }

    fun size(): Long = callback(false) {
        val size = read.authenticatedSize
        if (size !in 1..VaultMediaLimits.MAX_BYTES) throw thumbnailIo()
        size
    }

    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = callback(true) { remainingBytes ->
        // Subtraction avoids signed overflow in both array and authenticated-extent arithmetic.
        if (position < 0L || offset < 0 || length < 0 || offset > buffer.size - length) throw thumbnailIo()
        val size = read.authenticatedSize
        if (size !in 1..VaultMediaLimits.MAX_BYTES) throw thumbnailIo()
        if (length == 0) return@callback 0
        if (position >= size) return@callback -1
        val requested = minOf(length.toLong(), size - position,
            VaultMediaLimits.BUFFER_BYTES.toLong(), remainingBytes).toInt()
        if (requested <= 0) throw thumbnailIo()
        val count = read.readAt(position, buffer, offset, requested)
        if (count !in 1..requested) throw thumbnailIo() // Truncation/no progress is never EOF.
        count
    }

    private inline fun <T> callback(byteRead: Boolean, action: (Long) -> T): T {
        val admitted = synchronized(lock) {
            if (closed || !open) throw thumbnailIo()
            try { read.beginCallback(byteRead) } catch (_: Exception) { throw thumbnailIo() }
        }
        try {
            read.requireCurrent() // Includes zero-size and EOF queries after callback admission.
            return action(admitted.remainingBytes)
        } catch (_: Exception) { throw thumbnailIo() }
        finally { try { admitted.close() } catch (_: Exception) { throw thumbnailIo() } } // Runtime count lasts until the actual callback returns.
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (open) { open = false; read.transportClosed() }
        }
    }
}

internal fun thumbnailIo(): IOException = IOException("VAULT_THUMBNAIL_UNAVAILABLE")
