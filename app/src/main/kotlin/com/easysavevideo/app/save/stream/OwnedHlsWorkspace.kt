package com.easysavevideo.app.save.stream

import com.easysavevideo.save.*
import com.easysavevideo.save.network.HlsSegmentSink
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** Owned namespace comes from the same durable storage protocol as the destination MP4. */
internal class OwnedHlsWorkspace(private val directory: File, private val outputReservationBytes: Long,
    private val freeBytes: () -> Long) {
    init {
        require(outputReservationBytes > 0)
        checkDirectory()
    }
    private fun checkDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory)
            throw SaveFailure(SaveError.STORAGE_FAILURE)
    }
    fun newSegment(): Segment {
        checkDirectory()
        val id = UUID.randomUUID().toString()
        return Segment(id, File(directory, "$id.ts"))
    }
    fun newOutput(): File {
        checkDirectory()
        if (freeBytes() < outputReservationBytes) throw SaveFailure(SaveError.STORAGE_FULL)
        return File(directory, "output.mp4").also { file ->
            if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || !file.createNewFile())
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
    }
    inner class Segment(val id: String, val file: File) : HlsSegmentSink {
        private var output: FileOutputStream? = null
        private var opened = false
        override fun open(): FileOutputStream {
            checkDirectory()
            if (opened || Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || !file.createNewFile())
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            opened = true
            return FileOutputStream(file, false).also { output = it }
        }
        override fun availableBytes(): Long = (freeBytes() - outputReservationBytes).coerceAtLeast(0)
        override fun sync() { (output ?: throw SaveFailure(SaveError.STORAGE_FAILURE)).fd.sync() }
        override fun length(): Long {
            if (Files.isSymbolicLink(file.toPath())) throw SaveFailure(SaveError.STORAGE_FAILURE)
            return file.length()
        }
        override fun toString() = "OwnedHlsSegment([redacted])"
    }
}
