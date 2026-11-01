package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

sealed interface SearxngConfigurationRead {
    data object Missing : SearxngConfigurationRead
    data class Present(val config: SearxngSourceConfiguration) : SearxngConfigurationRead {
        override fun toString() = "SearxngConfigurationRead.Present([redacted])"
    }
    data object Malformed : SearxngConfigurationRead
    data object Unavailable : SearxngConfigurationRead
}

fun SearxngConfigurationRead.readableConfiguration(): SearxngSourceConfiguration? = when (this) {
    SearxngConfigurationRead.Missing -> SearxngConfigurationPolicy.empty()
    is SearxngConfigurationRead.Present -> config
    SearxngConfigurationRead.Malformed, SearxngConfigurationRead.Unavailable -> null
}

sealed interface SearxngConfigurationWrite {
    data class Committed(val config: SearxngSourceConfiguration) : SearxngConfigurationWrite {
        override fun toString() = "SearxngConfigurationWrite.Committed([redacted])"
    }
    data class Conflict(val current: SearxngConfigurationRead) : SearxngConfigurationWrite
    data object Unavailable : SearxngConfigurationWrite
}

interface SearxngConfigurationStore {
    fun load(): SearxngConfigurationRead
    /** Only replace a readable, exactly matched prior generation. Unknown data is never overwritten. */
    fun replace(expectedGeneration: String, replacement: SearxngSourceConfiguration): SearxngConfigurationWrite
}

/**
 * Single-process app store. All instances share a lock so concurrent Activity/controller instances
 * cannot win the same generation. Reads never create, repair, remove, or overwrite any file.
 * Writes fsync a bounded sibling then use a same-filesystem atomic rename; no non-atomic fallback.
 */
class FileSearxngConfigurationStore(private val file: File) : SearxngConfigurationStore {
    override fun load(): SearxngConfigurationRead = synchronized(processLock) { loadLocked() }

    override fun replace(expectedGeneration: String, replacement: SearxngSourceConfiguration): SearxngConfigurationWrite =
        synchronized(processLock) {
            val loaded = loadLocked()
            val old = loaded.readableConfiguration()
            if (old == null || old.generation != expectedGeneration ||
                !SearxngConfigurationPolicy.isNextRevision(old, replacement)) return@synchronized SearxngConfigurationWrite.Conflict(loaded)
            val bytes = try { SearxngConfigurationCodec.encode(replacement) }
            catch (_: Exception) { return@synchronized SearxngConfigurationWrite.Unavailable }
            var temporary: File? = null
            try {
                val directory = file.absoluteFile.parentFile ?: return@synchronized SearxngConfigurationWrite.Unavailable
                if (!directory.isDirectory && !directory.mkdirs()) return@synchronized SearxngConfigurationWrite.Unavailable
                temporary = File.createTempFile(".searxng-", ".pending", directory)
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                SearxngConfigurationWrite.Committed(replacement)
            } catch (_: Exception) {
                // A failed rename may have an uncertain result. Establish success only from exact bytes.
                val observed = loadLocked()
                if (observed is SearxngConfigurationRead.Present && observed.config == replacement)
                    SearxngConfigurationWrite.Committed(replacement)
                else SearxngConfigurationWrite.Unavailable
            } finally {
                // Only this operation's uncommitted temporary file may be removed.
                try { temporary?.delete() } catch (_: SecurityException) { }
            }
        }

    private fun loadLocked(): SearxngConfigurationRead {
        val attributes = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) {
            // Windows reports ENOENT for a child of a regular-file ancestor. Missing is only
            // established after the nearest existing ancestor is a real directory.
            var ancestor=file.absoluteFile.parentFile
            while(ancestor!=null) {
                try {
                    val parent=Files.readAttributes(ancestor.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                    if(!parent.isDirectory || parent.isSymbolicLink) return SearxngConfigurationRead.Unavailable
                    return SearxngConfigurationRead.Missing
                } catch(_:NoSuchFileException) {ancestor=ancestor.parentFile}
                catch(_:Exception) {return SearxngConfigurationRead.Unavailable}
            }
            return SearxngConfigurationRead.Unavailable
        }
        catch (_: Exception) { return SearxngConfigurationRead.Unavailable }
        if (!attributes.isRegularFile || attributes.size() !in 1..SearxngConfigurationCodec.MAX_BYTES.toLong())
            return SearxngConfigurationRead.Malformed
        val bytes = try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(SearxngConfigurationCodec.MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = stream.read(buffer, count, buffer.size - count)
                    if (read == -1) break
                    if (read == 0) throw IOException("CONFIG_READ")
                    count += read
                }
                if (count > SearxngConfigurationCodec.MAX_BYTES) return SearxngConfigurationRead.Malformed
                buffer.copyOf(count)
            }
        } catch (_: Exception) { return SearxngConfigurationRead.Unavailable }
        return try { SearxngConfigurationRead.Present(SearxngConfigurationCodec.decode(bytes)) }
        catch (_: Exception) { SearxngConfigurationRead.Malformed }
    }

    companion object { private val processLock = Any() }
}
