package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files

/** One cooperating storage writer across all instances/processes; the OS releases this lock on process death. */
internal class PrivateDirectoryLease(directory: File) : AutoCloseable {
    private val handle: RandomAccessFile
    private val lock: FileLock
    private var closed = false
    init {
        val file = File(directory, ".owner.lock")
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory.canonicalFile)
            throw SaveFailure(SaveError.STORAGE_FAILURE)
        handle = RandomAccessFile(file, "rw")
        try { lock = handle.channel.tryLock() ?: throw SaveFailure(SaveError.BUSY) }
        catch (_: OverlappingFileLockException) { handle.close(); throw SaveFailure(SaveError.BUSY) }
        catch (failure: Exception) { handle.close(); throw failure }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { lock.release() } finally { handle.close() }
    }
}
