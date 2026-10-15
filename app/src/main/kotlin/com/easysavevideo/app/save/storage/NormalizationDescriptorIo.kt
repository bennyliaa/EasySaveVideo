package com.easysavevideo.app.save.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files

/** Injectable syscall boundary. Handles never leave the storage-owned implementation. */
internal interface NormalizationDescriptorIo {
    interface Handle
    enum class Open { READ, WRITE_EXISTING, CREATE_EXCLUSIVE, DIRECTORY }
    enum class Kind { REGULAR, DIRECTORY, OTHER }
    data class Stat(
        val device: Long, val inode: Long, val kind: Kind, val links: Long, val size: Long,
        val modifiedSeconds: Long, val modifiedNanos: Long, val changedSeconds: Long, val changedNanos: Long,
    ) {
        fun sameInode(other: Stat) = device == other.device && inode == other.inode && kind == other.kind
    }
    fun lstat(path: File): Stat?
    /** Only NormalizationOpenRejected certifies that construction returned no live handle. */
    fun open(path: File, mode: Open): Handle
    fun fstat(handle: Handle): Stat
    fun pread(handle: Handle, bytes: ByteArray, offset: Int, count: Int, position: Long): Int
    fun pwrite(handle: Handle, bytes: ByteArray, offset: Int, count: Int, position: Long): Int
    fun truncate(handle: Handle, size: Long)
    fun fsync(handle: Handle)
    fun close(handle: Handle)
    fun valid(handle: Handle): Boolean
    fun mkdir(path: File)
    fun unlink(path: File)
    fun rmdir(path: File)
    /** Must stop at maximum + 1, rather than materializing an unbounded directory. */
    fun children(path: File, maximum: Int, closeStarted: () -> Unit = {}, closeConfirmed: () -> Unit = {}): List<String>
}

/** Positive no-handle acknowledgement from the syscall adapter, never inferred by a caller. */
internal class NormalizationOpenRejected(val failure: Exception) : IOException("NORMALIZATION_OPEN_REJECTED", failure)

/** Public DirectoryStream does not expose its FD. Successful close return is its release contract.
 * An uncertain close retains the actual stream; it must never be retried or abandoned to GC. */
internal class NormalizationDirectoryReleaseUnconfirmed(val retainedStream: Any) : IOException("NORMALIZATION_DIRECTORY_RELEASE_UNCONFIRMED")

/** Host tests pass synthetic errno values; Android supplies its public constants at runtime. */
internal fun normalizationErrnoFailure(errno: Int, noSpace: Int = OsConstants.ENOSPC, quota: Int = OsConstants.EDQUOT): Exception =
    if (errno == noSpace || errno == quota) SaveFailure(SaveError.STORAGE_FULL) else IOException("NORMALIZATION_IO")

internal object AndroidNormalizationDescriptorIo : NormalizationDescriptorIo {
    private class Descriptor(val fd: FileDescriptor) : NormalizationDescriptorIo.Handle
    private fun fd(handle: NormalizationDescriptorIo.Handle) = (handle as Descriptor).fd
    private fun stat(s: StructStat) = NormalizationDescriptorIo.Stat(s.st_dev, s.st_ino,
        when { OsConstants.S_ISREG(s.st_mode) -> NormalizationDescriptorIo.Kind.REGULAR
            OsConstants.S_ISDIR(s.st_mode) -> NormalizationDescriptorIo.Kind.DIRECTORY
            else -> NormalizationDescriptorIo.Kind.OTHER }, s.st_nlink, s.st_size,
        s.st_mtim.tv_sec, s.st_mtim.tv_nsec, s.st_ctim.tv_sec, s.st_ctim.tv_nsec)
    private inline fun <T> call(block: () -> T): T = try { block() } catch (e: ErrnoException) { throw normalizationErrnoFailure(e.errno) }
    override fun lstat(path: File): NormalizationDescriptorIo.Stat? = try { stat(Os.lstat(path.path)) }
        catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) null else throw normalizationErrnoFailure(e.errno) }
    override fun open(path: File, mode: NormalizationDescriptorIo.Open): NormalizationDescriptorIo.Handle {
        val flags = OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or when (mode) {
            NormalizationDescriptorIo.Open.READ -> OsConstants.O_RDONLY
            NormalizationDescriptorIo.Open.WRITE_EXISTING -> OsConstants.O_RDWR
            NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE -> OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL
            // Android public API has no O_DIRECTORY; pre/post lstat + fstat enforce directory type.
            NormalizationDescriptorIo.Open.DIRECTORY -> OsConstants.O_RDONLY
        }
        // Catch only the syscall's explicit rejection. A later wrapper-construction failure is
        // unknown acquisition and must retain the pending obligation at the owning caller.
        val opened = try { Os.open(path.path, flags, 384 /* 0600; only exclusive creation uses mode */) }
            catch (failure: ErrnoException) { throw NormalizationOpenRejected(normalizationErrnoFailure(failure.errno)) }
        return Descriptor(opened)
    }
    override fun fstat(handle: NormalizationDescriptorIo.Handle) = call { stat(Os.fstat(fd(handle))) }
    override fun pread(handle: NormalizationDescriptorIo.Handle, bytes: ByteArray, offset: Int, count: Int, position: Long) =
        call { Os.pread(fd(handle), bytes, offset, count, position) }
    override fun pwrite(handle: NormalizationDescriptorIo.Handle, bytes: ByteArray, offset: Int, count: Int, position: Long) =
        call { Os.pwrite(fd(handle), bytes, offset, count, position) }
    override fun truncate(handle: NormalizationDescriptorIo.Handle, size: Long) = call { Os.ftruncate(fd(handle), size) }
    override fun fsync(handle: NormalizationDescriptorIo.Handle) = call { Os.fsync(fd(handle)) }
    override fun close(handle: NormalizationDescriptorIo.Handle) = call { Os.close(fd(handle)) }
    override fun valid(handle: NormalizationDescriptorIo.Handle) = fd(handle).valid()
    override fun mkdir(path: File) = call { Os.mkdir(path.path, 448 /* 0700 */) }
    override fun unlink(path: File) = call { Os.remove(path.path) }
    override fun rmdir(path: File) = call { Os.remove(path.path) }
    override fun children(path: File, maximum: Int, closeStarted: () -> Unit, closeConfirmed: () -> Unit): List<String> {
        val stream = Files.newDirectoryStream(path.toPath())
        try {
            val result = ArrayList<String>(maximum + 1)
            for (child in stream) { result += child.fileName.toString(); if (result.size > maximum) break }
            return result
        } finally {
            closeStarted()
            try { stream.close() } catch (_: Exception) { throw NormalizationDirectoryReleaseUnconfirmed(stream) }
            closeConfirmed()
        }
    }
}
