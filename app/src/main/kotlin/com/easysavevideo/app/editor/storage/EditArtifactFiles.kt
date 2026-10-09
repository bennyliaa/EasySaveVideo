package com.easysavevideo.app.editor.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

/** Only generated identifiers from durable edit_job ownership rows enter these file methods. */
internal class EditArtifactFiles(
    root: File,
    private val syncDirectory: (File) -> Unit,
    private val promoteNoReplace: (File, File) -> Unit,
) {
    private val staging = directory(File(root, "edit-staging"))
    private val media = directory(File(root, "media"))
    init { syncDirectory(root) }
    fun assertStagingAbsent(artifactId: String) {
        if (Files.exists(path(staging, artifactId, ".part").toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("EDIT_STAGING_EXISTS")
    }
    fun assertFinalAbsent(mediaId: String) {
        if (Files.exists(path(media, mediaId, ".mp4").toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("EDIT_FINAL_EXISTS")
    }
    fun create(artifactId: String): File = path(staging, artifactId, ".part").also {
        if (Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) || !it.createNewFile()) throw IOException("EDIT_STAGING_EXISTS")
        FileOutputStream(it).use { output -> output.fd.sync() }
        syncDirectory(staging)
    }
    fun staging(artifactId: String): File = path(staging, artifactId, ".part").also(::regular)
    fun availableBytes(): Long = (staging.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)
    fun sync(artifactId: String) { FileOutputStream(staging(artifactId), true).use { it.fd.sync() } }
    fun promote(artifactId: String, mediaId: String, bytes: Long, sha256: String, checkActive: () -> Unit = {}) {
        val source = staging(artifactId)
        requireExact(source, bytes, sha256, checkActive)
        checkActive()
        val destination = path(media, mediaId, ".mp4")
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("EDIT_FINAL_EXISTS")
        promoteNoReplace(source, destination)
        syncDirectory(media); syncDirectory(staging)
        requireExact(destination, bytes, sha256, checkActive)
    }
    /** Caller proves no published item or reader/writer owns either exact candidate. No directory scan. */
    fun cleanup(artifactId: String, mediaId: String, promotedProof: Pair<Long, String>? = null) {
        val source = path(staging, artifactId, ".part")
        val destination = path(media, mediaId, ".mp4")
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            // Rename ownership is inferred only from durable verified intent AND vanished staging.
            // Both names present means collision, not our promoted candidate. Keep both for recovery.
            if (promotedProof == null || Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("EDIT_FINAL_OWNERSHIP_UNKNOWN")
            requireExact(destination, promotedProof.first, promotedProof.second) {}
            removeOwned(destination); syncDirectory(media)
        }
        removeOwned(source); syncDirectory(staging)
    }
    private fun removeOwned(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        regular(file)
        if (!file.delete()) throw IOException("EDIT_CLEANUP_PENDING")
    }
    private fun requireExact(file: File, bytes: Long, expectedHash: String, checkActive: () -> Unit) {
        regular(file)
        if (bytes <= 0 || file.length() != bytes || !expectedHash.matches(Regex("[a-f0-9]{64}"))) throw IOException("EDIT_EXTENT_MISMATCH")
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024); var read = 0L
            while (read < bytes) {
                checkActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - read).toInt())
                if (count <= 0) throw IOException("EDIT_EXTENT_MISMATCH")
                digest.update(buffer, 0, count); read += count
            }
            if (input.read() != -1) throw IOException("EDIT_EXTENT_MISMATCH")
        }
        val hash = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        if (hash != expectedHash || file.length() != bytes) throw IOException("EDIT_HASH_MISMATCH")
    }
    private fun path(parent: File, id: String, suffix: String): File {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) throw IOException("EDIT_INVALID_LOCATOR")
        if (Files.isSymbolicLink(parent.toPath()) || !parent.isDirectory) throw IOException("EDIT_DIRECTORY_UNAVAILABLE")
        return File(parent, id + suffix).also {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != parent) throw IOException("EDIT_INVALID_LOCATOR")
        }
    }
    private fun regular(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) throw IOException("EDIT_FILE_UNAVAILABLE")
    }
    private fun directory(file: File): File {
        if (Files.isSymbolicLink(file.toPath()) || (!file.isDirectory && !file.mkdirs())) throw IOException("EDIT_DIRECTORY_UNAVAILABLE")
        val canonical = file.canonicalFile
        if (canonical != file.absoluteFile) throw IOException("EDIT_DIRECTORY_UNAVAILABLE")
        return canonical
    }
}
