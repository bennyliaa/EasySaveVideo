package com.easysavevideo.app.library.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Only durable catalog locators enter this adapter. A display name is never a filesystem name. */
internal class OrdinaryMediaFiles(
    directory: File,
    private val syncDirectory: (File) -> Unit,
    private val unlink: (File) -> Boolean = File::delete,
) {
    private val directory = directory.absoluteFile
    private val canonicalDirectory = directory.canonicalFile
    private val opaqueId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun readable(locator: String, expectedBytes: Long): File = path(locator).also {
        if (expectedBytes <= 0 || !regular(it) || it.length() != expectedBytes) throw IOException("MEDIA_UNAVAILABLE")
    }

    /** Called only after a durable tombstone and after every item lease is released. */
    fun remove(locator: String) {
        val file = path(locator)
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!regular(file)) throw IOException("MEDIA_UNAVAILABLE")
            if (!unlink(file)) throw IOException("DELETE_PENDING")
        }
        // A crash between unlink and this fence retries the fence, never reports an early success.
        try { syncDirectory(directory) } catch (_: Exception) { throw IOException("DELETE_PENDING") }
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("DELETE_PENDING")
    }

    private fun path(locator: String): File {
        if (!opaqueId.matches(locator) || Files.isSymbolicLink(directory.toPath()) ||
            !directory.isDirectory || directory.canonicalFile != canonicalDirectory) throw IOException("INVALID_LOCATOR")
        val file = File(directory, "$locator.mp4")
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != canonicalDirectory)
            throw IOException("INVALID_LOCATOR")
        return file
    }
    private fun regular(file: File): Boolean = !Files.isSymbolicLink(file.toPath()) && file.isFile
}
