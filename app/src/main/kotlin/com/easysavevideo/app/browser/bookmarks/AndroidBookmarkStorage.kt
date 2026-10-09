package com.easysavevideo.app.browser.bookmarks

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Accessed only by the process-owned serial worker. Never logs functional data. */
class AndroidBookmarkStorage(context: Context) : BookmarkStorage {
    private val app = context.applicationContext
    // Resolve/create the private no-backup directory on the worker, not the UI thread.
    private val file: AtomicFile by lazy { AtomicFile(File(app.noBackupFilesDir, "browser-bookmarks.bin")) }
    override fun load(): List<BrowserBookmark> {
        val stream = try { file.openRead() } catch (missing: FileNotFoundException) {
            val base = file.baseFile.toPath()
            val parent = base.parent
            // exists()==false is ambiguous on denied/broken paths. Only a positively
            // accessible directory and definite absence of both committed files is first use.
            if (!Files.isDirectory(parent)) throw missing
            Files.newDirectoryStream(parent).use {
                if (!Files.notExists(base, LinkOption.NOFOLLOW_LINKS) ||
                    !Files.notExists(File(file.baseFile.path + ".bak").toPath(), LinkOption.NOFOLLOW_LINKS)) throw missing
            }
            return emptyList()
        }
        val bytes = stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk, 0, minOf(chunk.size, BookmarkCodec.MAX_BYTES + 1 - buffer.size()))
                if (read < 0) break
                check(read > 0)
                buffer.write(chunk, 0, read)
                require(buffer.size() <= BookmarkCodec.MAX_BYTES)
            }
            buffer.toByteArray()
        }
        return BookmarkCodec.decode(bytes)
    }
    override fun save(items: List<BrowserBookmark>) {
        val bytes = BookmarkCodec.encode(items)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        // AtomicFile can internally log/return on rename failures; don't claim success blindly.
        check(load() == items)
    }
}
