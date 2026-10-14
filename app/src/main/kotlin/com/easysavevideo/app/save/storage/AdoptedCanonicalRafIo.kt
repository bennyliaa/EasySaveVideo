package com.easysavevideo.app.save.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Genuine RAF and separately accounted directory-resource seam. Host fixtures may inject metadata
 * and blocking/failing boundaries, but must use a real temporary-file RandomAccessFile in "r" mode.
 * A thrown constructor has unknown resource outcome; the owning reservation retains that failure.
 */
internal interface AdoptedCanonicalRafIo {
    interface Directory {
        /** At most maximum + 1 children are observed, never unbounded materialization. */
        fun children(maximum: Int): List<String>
        fun close()
    }
    fun lstat(path: File): NormalizationDescriptorIo.Stat?
    fun openReadOnly(path: File): RandomAccessFile
    fun descriptor(input: RandomAccessFile): FileDescriptor
    fun fstat(descriptor: FileDescriptor): NormalizationDescriptorIo.Stat
    fun valid(descriptor: FileDescriptor): Boolean
    fun close(input: RandomAccessFile)
    fun openDirectory(path: File): Directory
}

/**
 * RAF's public constructor does NOT expose Os.open's NOFOLLOW/NONBLOCK flags. Pre/post lstat/fstat,
 * the private UID namespace, lifetime directory ownership and cooperating mutation reservations are
 * the threat model. This is not protection against a malicious same-UID namespace writer.
 */
internal object AndroidAdoptedCanonicalRafIo : AdoptedCanonicalRafIo {
    private fun stat(s: StructStat) = NormalizationDescriptorIo.Stat(s.st_dev, s.st_ino,
        when { OsConstants.S_ISREG(s.st_mode) -> NormalizationDescriptorIo.Kind.REGULAR
            OsConstants.S_ISDIR(s.st_mode) -> NormalizationDescriptorIo.Kind.DIRECTORY
            else -> NormalizationDescriptorIo.Kind.OTHER }, s.st_nlink, s.st_size,
        s.st_mtim.tv_sec, s.st_mtim.tv_nsec, s.st_ctim.tv_sec, s.st_ctim.tv_nsec)
    private inline fun <T> call(action: () -> T): T = try { action() }
        catch (failure: ErrnoException) { throw normalizationErrnoFailure(failure.errno) }
    override fun lstat(path: File): NormalizationDescriptorIo.Stat? = try { stat(Os.lstat(path.path)) }
        catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) null else throw normalizationErrnoFailure(failure.errno)
        }
    override fun openReadOnly(path: File) = RandomAccessFile(path, "r")
    override fun descriptor(input: RandomAccessFile): FileDescriptor = input.fd
    override fun fstat(descriptor: FileDescriptor) = call { stat(Os.fstat(descriptor)) }
    override fun valid(descriptor: FileDescriptor) = descriptor.valid()
    override fun close(input: RandomAccessFile) { input.close() }
    override fun openDirectory(path: File): AdoptedCanonicalRafIo.Directory =
        OpenDirectory(Files.newDirectoryStream(path.toPath()))
    private class OpenDirectory(private val stream: DirectoryStream<Path>) : AdoptedCanonicalRafIo.Directory {
        override fun children(maximum: Int): List<String> {
            require(maximum >= 0)
            val result = ArrayList<String>(maximum + 1)
            for (child in stream) { result += child.fileName.toString(); if (result.size > maximum) break }
            return result
        }
        override fun close() { stream.close() }
    }
}
