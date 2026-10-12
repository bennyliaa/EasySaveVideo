package com.easysavevideo.app.diagnostics

import com.easysavevideo.core.model.ErrorCode
import com.easysavevideo.core.model.ErrorModule
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** A detached, immutable, content-free export. It never references a live log file. */
class DiagnosticSnapshot internal constructor(val text: String) {
    init {
        require(text.isNotEmpty() && text.length <= DiagnosticSnapshotReader.MAX_BYTES)
        require(text.all { it.code in 0..127 }) // The closed output grammar is ASCII.
    }
}

sealed interface DiagnosticSnapshotResult {
    data class Ready(val snapshot: DiagnosticSnapshot) : DiagnosticSnapshotResult
    data object Empty : DiagnosticSnapshotResult
    data object Failed : DiagnosticSnapshotResult
}

/**
 * Reads only AndroidErrorSink's daily buckets, never functional storage or arbitrary filenames.
 * A snapshot is limited to the newest five buckets within today and the preceding six UTC days,
 * and at most 256 KiB of input in total. Newer buckets consume that budget first. Only complete
 * valid records are reserialized; a partial last line at an extent/budget boundary is discarded.
 * Appends beyond each initially captured extent are not included, even if they complete a line.
 * Missing/replaced/truncated selected files and any I/O failure discard the entire snapshot.
 * This reader neither creates nor modifies files and never exposes exception text.
 */
class DiagnosticSnapshotReader internal constructor(
    private val directory: File,
    private val nowMillis: () -> Long,
    private val openFile: (Path) -> SeekableByteChannel,
) {
    constructor(directory: File, nowMillis: () -> Long = System::currentTimeMillis) : this(
        directory, nowMillis, { Files.newByteChannel(it, setOf(READ, NOFOLLOW_LINKS)) },
    )

    fun read(): DiagnosticSnapshotResult = try {
        readSnapshot()
    } catch (_: Exception) {
        DiagnosticSnapshotResult.Failed
    }

    private fun readSnapshot(): DiagnosticSnapshotResult {
        val now = nowMillis()
        check(now >= 0)
        // The caller supplies the app-owned technical_errors directory. Resolve trusted Android
        // storage aliases in its parent, but never follow a link at the log directory itself.
        check(directory.name == DIRECTORY_NAME)
        val parent = checkNotNull(directory.absoluteFile.parentFile).canonicalFile.toPath()
        val root = parent.resolve(DIRECTORY_NAME)
        val directoryAttributes = attributesOrMissing(root) ?: return DiagnosticSnapshotResult.Empty
        requireDirectory(directoryAttributes)
        val today = now / DAY_MILLIS
        val selected = ArrayList<Bucket>(MAX_FILES)
        for (age in 0L..6L) {
            val day = today - age
            if (day < 0) break
            val path = root.resolve("$day.log")
            val attributes = attributesOrMissing(path) ?: continue
            requireFile(attributes)
            selected += Bucket(day, path, attributes)
            if (selected.size == MAX_FILES) break
        }
        verifyDirectory(root, directoryAttributes)
        val output = StringBuilder()
        var remaining = MAX_BYTES
        for (bucket in selected) {
            // Also verify selected files that cannot contribute because the input budget is full.
            verifyFile(bucket)
            if (remaining == 0) continue
            openFile(bucket.path).use { channel ->
                verifyFile(bucket)
                check(channel.size() >= bucket.attributes.size())
                val limit = minOf(bucket.attributes.size(), remaining.toLong()).toInt()
                consume(channel, limit, bucket.day, now, output)
                remaining -= limit
                check(channel.size() >= bucket.attributes.size())
                verifyFile(bucket)
            }
        }
        // A failure in a later bucket must not publish earlier successfully parsed records.
        selected.forEach(::verifyFile)
        verifyDirectory(root, directoryAttributes)
        return if (output.isEmpty()) DiagnosticSnapshotResult.Empty
        else DiagnosticSnapshotResult.Ready(DiagnosticSnapshot(output.toString()))
    }

    private fun consume(channel: SeekableByteChannel, limit: Int, day: Long, now: Long, output: StringBuilder) {
        val bytes = ByteBuffer.allocate(minOf(READ_BUFFER_BYTES, maxOf(limit, 1)))
        val line = StringBuilder(MAX_LINE_BYTES)
        var dropped = false
        var remaining = limit
        while (remaining > 0) {
            bytes.clear()
            bytes.limit(minOf(bytes.capacity(), remaining))
            val count = channel.read(bytes)
            // A regular blocking file must make progress; do not spin or accept an early EOF.
            if (count <= 0) throw IOException()
            remaining -= count
            bytes.flip()
            while (bytes.hasRemaining()) {
                val value = bytes.get().toInt() and 0xff
                if (value == '\n'.code) {
                    if (!dropped) sanitize(line.toString(), day, now)?.let { record ->
                        check(output.length <= MAX_BYTES - record.length)
                        output.append(record)
                    }
                    line.setLength(0)
                    dropped = false
                } else if (!dropped) {
                    if (value !in 32..126 || line.length == MAX_LINE_BYTES) {
                        line.setLength(0)
                        dropped = true
                    } else line.append(value.toChar())
                }
            }
        }
        // Intentionally do not parse the un-terminated tail, or read one byte beyond the limit.
    }

    private fun sanitize(line: String, day: Long, now: Long): String? {
        val match = RECORD.matchEntire(line) ?: return null
        val timestamp = match.groupValues[1].toLongOrNull() ?: return null
        if (timestamp > now || timestamp / DAY_MILLIS != day) return null
        val module = MODULES[match.groupValues[2]] ?: return null
        val code = CODES[match.groupValues[3]] ?: return null
        val api = match.groupValues[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val http = match.groupValues[5].toIntOrNull()?.takeIf { it == 0 || it in 100..599 } ?: return null
        return "$timestamp ERROR ${module.name} ${code.name} api=$api http=$http\n"
    }

    private fun attributesOrMissing(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun requireDirectory(attributes: BasicFileAttributes) {
        check(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() != null)
    }

    private fun requireFile(attributes: BasicFileAttributes) {
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.fileKey() != null)
        check(attributes.size() >= 0)
    }

    private fun verifyDirectory(path: Path, before: BasicFileAttributes) {
        val after = checkNotNull(attributesOrMissing(path))
        requireDirectory(after)
        check(before.fileKey() == after.fileKey())
    }

    private fun verifyFile(bucket: Bucket) {
        val after = checkNotNull(attributesOrMissing(bucket.path))
        requireFile(after)
        check(bucket.attributes.fileKey() == after.fileKey())
        check(after.size() >= bucket.attributes.size())
        // Ordinary appends may change mtime and length. Same-size rewrites are not appends.
        check(after.size() != bucket.attributes.size() ||
            after.lastModifiedTime() == bucket.attributes.lastModifiedTime())
    }

    private data class Bucket(val day: Long, val path: Path, val attributes: BasicFileAttributes)

    companion object {
        const val MAX_BYTES = 256 * 1024
        private const val DIRECTORY_NAME = "technical_errors"
        private const val MAX_FILES = 5
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val MAX_LINE_BYTES = 192
        private const val READ_BUFFER_BYTES = 4096
        private val MODULES = ErrorModule.entries.associateBy { it.name }
        private val CODES = ErrorCode.entries.associateBy { it.name }
        private val RECORD = Regex("(0|[1-9][0-9]{0,18}) ERROR ([A-Z_]+) ([A-Z_]+) api=([1-9][0-9]{0,9}) http=(0|[1-5][0-9]{2})")
    }
}
