package com.easysavevideo.app.save.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Only opaque synthetic IDs are parsed. No path, file URI, authority alias or network delegate. */
internal object LocalHlsAddress {
    const val PLAYLIST = "esv-local:///index.m3u8"
    private val segment = Regex("esv-local:///s([0-9]{1,5})\\.ts")
    fun index(value: String, count: Int): Int? {
        val text = segment.matchEntire(value)?.groupValues?.get(1) ?: return null
        val index = text.toIntOrNull() ?: return null
        return index.takeIf { it in 0 until count && text == it.toString() }
    }
    fun segment(index: Int) = "esv-local:///s$index.ts"
}

/** Captures an allowlisted app-private inode and length, not a directory-wide file capability. */
internal class LocalHlsFile(file: File, root: File, val size: Long, val sha256: String,
    private val closeUnconfirmed: () -> Unit = {}) {
    private val originalPath = file.toPath().toAbsolutePath()
    val path = originalPath.normalize()
    private val key: Any
    private val modified: java.nio.file.attribute.FileTime
    init {
        val ownedRoot = root.toPath().toRealPath()
        requireLocal(originalPath == path && path.startsWith(ownedRoot) && path != ownedRoot && path.toRealPath() == path)
        val attr = attributes()
        requireLocal(attr.isRegularFile && !attr.isSymbolicLink && attr.size() == size && size > 0)
        key = attr.fileKey() ?: throw IOException("LOCAL_IDENTITY_UNAVAILABLE")
        modified = attr.lastModifiedTime()
        requireLocal(sha256.matches(Regex("[0-9a-f]{64}")))
    }
    fun verifyIdentity() {
        val attr = attributes()
        requireLocal(attr.isRegularFile && !attr.isSymbolicLink && attr.fileKey() == key &&
            attr.size() == size && attr.lastModifiedTime() == modified && path.toRealPath() == path)
    }
    fun open(): SeekableByteChannel {
        verifyIdentity()
        val channel = Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        try { verifyIdentity(); requireLocal(channel.size() == size); return channel }
        catch (failure: Throwable) { close(channel); throw failure }
    }
    fun verifyHash(checkpoint: () -> Unit) {
        val channel = open()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            var read = 0L
            while (read < size) {
                checkpoint()
                val count = channel.read(ByteBuffer.wrap(bytes, 0, minOf(bytes.size.toLong(), size - read).toInt()))
                requireLocal(count > 0)
                digest.update(bytes, 0, count); read += count
            }
            requireLocal(digest.digest().joinToString("") { "%02x".format(it) } == sha256)
            verifyIdentity()
        } finally { close(channel) }
    }
    fun close(channel: SeekableByteChannel) {
        try { channel.close() } catch (_: Exception) { closeUnconfirmed(); throw IOException("LOCAL_RELEASE_UNCONFIRMED") }
    }
    private fun attributes() = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    override fun toString() = "LocalHlsFile([private])"
}

internal fun requireLocal(value: Boolean) { if (!value) throw IOException("LOCAL_INPUT_REJECTED") }

/** Shared bounded handle/read accounting; revocation closes the capability to future opens. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalOnlyDataSourceFactory(
    playlist: ByteArray,
    private val files: List<LocalHlsFile>,
    private val checkpoint: () -> Unit,
) : DataSource.Factory {
    private val playlist = playlist.copyOf()
    private val revoked = AtomicBoolean(false)
    val openHandles = AtomicInteger(0)
    val closeUnconfirmed = AtomicBoolean(false)
    private val opens = AtomicInteger(0)
    private val readBytes = AtomicLong(0)
    private val maxReads = files.sumOf { it.size } * 4 + playlist.size.toLong() * 8
    fun revoke() { revoked.set(true) }
    fun check() { requireLocal(!revoked.get()); checkpoint() }
    fun verifyFiles() { files.forEach { check(); it.verifyIdentity() } }
    override fun createDataSource(): DataSource = LocalOnlyDataSource()
    private inner class LocalOnlyDataSource : BaseDataSource(false) {
        private var channel: SeekableByteChannel? = null
        private var memory: ByteArray? = null
        private var memoryPosition = 0
        private var remaining = 0L
        private var uri: Uri? = null
        private var opened = false
        private var file: LocalHlsFile? = null
        override fun open(dataSpec: DataSpec): Long {
            check(); requireLocal(!opened && channel == null && uri == null)
            requireLocal(opens.incrementAndGet() <= files.size * 4 + 8)
            requireLocal(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
                dataSpec.httpRequestHeaders.isEmpty() && dataSpec.position >= 0 &&
                (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length >= 0))
            val address = dataSpec.uri.toString()
            val index = LocalHlsAddress.index(address, files.size)
            requireLocal(address == LocalHlsAddress.PLAYLIST || index != null)
            transferInitializing(dataSpec)
            val size = if (index == null) playlist.size.toLong() else files[index].size
            requireLocal(dataSpec.position <= size)
            remaining = size - dataSpec.position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                requireLocal(dataSpec.length <= remaining); remaining = dataSpec.length
            }
            if (index == null) { memory = playlist; memoryPosition = dataSpec.position.toInt() }
            else {
                file = files[index]
                channel = file!!.open().also { openHandles.incrementAndGet() }
                channel!!.position(dataSpec.position)
            }
            uri = dataSpec.uri; opened = true
            transferStarted(dataSpec)
            return remaining
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(); requireLocal(opened && offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = minOf(remaining, length.toLong(), 64L * 1024).toInt()
            val actual = memory?.let {
                it.copyInto(buffer, offset, memoryPosition, memoryPosition + count); memoryPosition += count; count
            } ?: channel!!.read(ByteBuffer.wrap(buffer, offset, count))
            requireLocal(actual > 0)
            requireLocal(readBytes.addAndGet(actual.toLong()) <= maxReads)
            remaining -= actual; bytesTransferred(actual)
            if (remaining == 0L) file?.verifyIdentity()
            return actual
        }
        override fun getUri(): Uri? = uri
        override fun close() {
            val held = channel; channel = null
            try {
                if (held != null) {
                    try { held.close(); openHandles.decrementAndGet() }
                    catch (_: Exception) { closeUnconfirmed.set(true); throw IOException("LOCAL_RELEASE_UNCONFIRMED") }
                }
            } finally {
                memory = null; file = null; uri = null; remaining = 0
                if (opened) { opened = false; transferEnded() }
            }
        }
    }
}
