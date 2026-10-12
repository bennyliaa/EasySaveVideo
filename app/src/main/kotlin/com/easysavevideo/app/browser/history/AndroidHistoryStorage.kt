package com.easysavevideo.app.browser.history

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Private no-backup functional store. One serial worker; no logging, network or sharing routes. */
class AndroidHistoryStorage(context: Context) : HistoryStorage {
    private val app = context.applicationContext
    private val file by lazy { AtomicFile(File(app.noBackupFilesDir, "browser-history.bin")) }
    override fun load(): List<BrowserHistoryItem> {
        val stream = try { file.openRead() } catch (missing: FileNotFoundException) {
            val base = file.baseFile.toPath(); val parent = base.parent
            if (!Files.isDirectory(parent)) throw missing
            Files.newDirectoryStream(parent).use {
                if (!Files.notExists(base, LinkOption.NOFOLLOW_LINKS) ||
                    !Files.notExists(File(file.baseFile.path + ".bak").toPath(), LinkOption.NOFOLLOW_LINKS)) throw missing
            }
            return emptyList()
        }
        val bytes = stream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk, 0, minOf(chunk.size, HistoryCodec.MAX_BYTES + 1 - out.size()))
                if (n < 0) break
                check(n > 0); out.write(chunk, 0, n); require(out.size() <= HistoryCodec.MAX_BYTES)
            }
            out.toByteArray()
        }
        return HistoryCodec.decode(bytes)
    }
    override fun save(items: List<BrowserHistoryItem>) {
        val bytes = HistoryCodec.encode(items)
        val stream = file.startWrite()
        try {
            stream.write(bytes); stream.fd.sync(); file.finishWrite(stream)
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
        check(load() == items)
    }
}
