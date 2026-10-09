package com.easysavevideo.app.recording.segments

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Journal-derived random names only; no directory enumeration or guessed recovery candidates. */
internal class RecordedSegmentFiles(root: File, private val syncDirectory: (File) -> Unit,
    private val openInput: (File) -> FileInputStream = ::FileInputStream) {
    // Even helper hashing/sync descriptors remain strongly owned when actual close is unknown.
    private val unconfirmed = ArrayList<Any>()
    val requiresRecovery: Boolean get() = unconfirmed.isNotEmpty()
    private val directory = File(root, "recording-segments").also {
        if (Files.isSymbolicLink(it.toPath()) || (!it.isDirectory && !it.mkdirs()) || it.canonicalFile != it.absoluteFile) fail()
    }
    init { syncDirectory(root) }
    fun create(id: String): Pair<File, String> {
        val file = path(id)
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !file.createNewFile()) fail()
        sync(file); syncDirectory(directory)
        return file to identity(file)
    }
    fun resolve(id: String, expectedIdentity: String): File = path(id).also {
        if (identity(it) != expectedIdentity) fail()
    }
    fun bytesAvailable(): Long = (directory.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0)
    fun seal(id: String, expectedIdentity: String, expectedBytes: Long, checkpoint: () -> Unit = {}): String {
        val file = resolve(id, expectedIdentity)
        sync(file)
        return hash(id, expectedIdentity, expectedBytes, checkpoint)
    }
    fun hash(id: String, expectedIdentity: String, expectedBytes: Long, checkpoint: () -> Unit = {}): String {
        if (expectedBytes !in 1..MAX_SEGMENT_BYTES) fail()
        val file = resolve(id, expectedIdentity)
        val digest = MessageDigest.getInstance("SHA-256")
        val input = openInput(file)
        try {
            if (input.channel.size() != expectedBytes) fail()
            val buffer = ByteArray(64 * 1024); var read = 0L
            while (read < expectedBytes) {
                checkpoint()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - read).toInt())
                if (count <= 0) fail()
                digest.update(buffer, 0, count); read += count
            }
            if (input.read() != -1 || input.channel.size() != expectedBytes) fail()
        } finally { close(input) }
        if (resolve(id, expectedIdentity).length() != expectedBytes) fail()
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
    fun remove(id: String, expectedIdentity: String?, bytes: Long?, sha256: String?) {
        val file = path(id)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { syncDirectory(directory); return }
        if (expectedIdentity == null) fail() // creation-to-identity crash is unresolved, not guessed ownership
        resolve(id, expectedIdentity)
        if (bytes != null && sha256 != null && hash(id, expectedIdentity, bytes) != sha256) fail()
        if (!file.delete()) fail()
        syncDirectory(directory)
    }
    private fun identity(file: File): String {
        val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attr.isRegularFile || attr.isSymbolicLink) fail()
        return attr.fileKey()?.toString()?.takeIf { it.length in 1..256 } ?: fail()
    }
    private fun sync(file: File) {
        val output = FileOutputStream(file, true)
        try { output.fd.sync() } finally {
            var failed = false
            try { output.close() } catch (_: Exception) { failed = true }
            if (runCatching { output.fd.valid() }.getOrDefault(true)) { unconfirmed += output; fail() }
            if (failed) fail()
        }
    }
    private fun close(input: FileInputStream) {
        var failed = false
        try { input.close() } catch (_: Exception) { failed = true }
        if (runCatching { input.fd.valid() }.getOrDefault(true)) { unconfirmed += input; fail() }
        if (failed) fail()
    }
    private fun path(id: String): File {
        if (requiresRecovery) fail()
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() ||
            Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory.absoluteFile || !directory.isDirectory) fail()
        return File(directory, "$id.mp4").also {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != directory) fail()
        }
    }
    companion object { const val MAX_SEGMENT_BYTES = 2L * 1024 * 1024 * 1024 }
    private fun fail(): Nothing = throw IOException("RECORDED_SEGMENT_IDENTITY")
}
