package com.easysavevideo.app.library.export

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.easysavevideo.library.export.ExportInput
import com.easysavevideo.library.export.ExportRelease
import com.easysavevideo.library.export.ExportWrite
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Destination-only mechanics shared by ordinary export and Vault. No source/root/journal. */
internal class AndroidExportHandles(private val resolver: ContentResolver, private val preserveUncertainOpen: Boolean = false) {
    private val lock = Any()
    private val handles = mutableMapOf<String, MutableSet<Any>>()
    private val retainedCount = java.util.concurrent.atomic.AtomicInteger()
    fun observeDrain(): ExportRelease = if (retainedCount.get() == 0) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED

    fun openWrite(operationId: String, uri: Uri): ExportWrite {
        val opening = Any()
        retain(operationId, opening)
        val descriptor = try { resolver.openFileDescriptor(uri, "wt") ?: throw IOException("EXPORT_OPEN_FAILED") }
        catch (failure: Exception) { if (!preserveUncertainOpen) release(operationId, opening); throw failure }
        val outputStream = try { FileOutputStream(descriptor.fileDescriptor) } catch (failure: Exception) {
            retainOnFailedClose(operationId, descriptor); release(operationId, opening); throw failure
        }
        val owner = object : ExportWrite {
            override val stream = outputStream
            private var closeStarted = false
            @Synchronized override fun close(): ExportRelease {
                if (preserveUncertainOpen && closeStarted) {
                    val released = try { !outputStream.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                    if (released) release(operationId, this)
                    return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
                }
                closeStarted = true
                var syncFailed = false
                try {
                    outputStream.flush()
                    // Pipes/providers need not support fsync. Full readback is separate proof.
                    if (OsConstants.S_ISREG(Os.fstat(outputStream.fd).st_mode)) outputStream.fd.sync()
                } catch (_: Exception) { syncFailed = true }
                try { outputStream.close() } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
                val released = try { !outputStream.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                if (released) release(operationId, this)
                if (syncFailed) throw IOException("EXPORT_FLUSH_FAILED")
                return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            }
        }
        retain(operationId, owner)
        release(operationId, opening)
        return owner
    }
    fun openReadback(operationId: String, uri: Uri): ExportInput {
        val opening = Any()
        retain(operationId, opening)
        val descriptor = try { resolver.openFileDescriptor(uri, "r") ?: throw IOException("EXPORT_READBACK_FAILED") }
        catch (failure: Exception) { if (!preserveUncertainOpen) release(operationId, opening); throw failure }
        // File-not-found, permission loss, transient IO or corrupt bytes are failures, not Unsupported.
        val input = try { FileInputStream(descriptor.fileDescriptor) } catch (failure: Exception) {
            retainOnFailedClose(operationId, descriptor); release(operationId, opening); throw failure
        }
        val owner = object : ExportInput {
            override val stream = input
            private var closeStarted = false
            @Synchronized override fun close(): ExportRelease {
                if (preserveUncertainOpen && closeStarted) {
                    val released = try { !input.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                    if (released) release(operationId, this)
                    return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
                }
                closeStarted = true
                try { input.close() } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
                val released = try { !input.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                if (released) release(operationId, this)
                return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            }
        }
        retain(operationId, owner)
        release(operationId, opening)
        return owner
    }
    private fun retain(id: String, value: Any) { synchronized(lock) { if (handles.getOrPut(id) { mutableSetOf() }.add(value)) retainedCount.incrementAndGet() } }
    private fun release(id: String, value: Any) { synchronized(lock) { handles[id]?.let { if (it.remove(value)) retainedCount.decrementAndGet(); if (it.isEmpty()) handles.remove(id) } } }
    fun released(id: String): Boolean = synchronized(lock) { handles[id].isNullOrEmpty() }
    private fun retainOnFailedClose(id: String, descriptor: ParcelFileDescriptor) {
        try { descriptor.close() } catch (_: Exception) { }
        if (try { descriptor.fileDescriptor.valid() } catch (_: Exception) { true }) retain(id, descriptor)
    }
}
