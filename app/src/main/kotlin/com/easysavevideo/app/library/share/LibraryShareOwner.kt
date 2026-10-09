package com.easysavevideo.app.library.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.MediaReadRef
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.nio.ByteBuffer

/**
 * Process-wide owner of bounded ordinary copies. Recipients receive seekable read-only virtual
 * proxy descriptors, never staging/original disk descriptors. Expiry/revocation denies future
 * opens/reads. Cleanup waits for onRelease plus actual private input-FD closure; a timeout never
 * proves release. Recipients may retain already delivered bytes independently and indefinitely.
 */
internal class LibraryShareOwner private constructor(private val context: Context) {
    private class Copy(val operationId: String, val token: String, val bytes: Long, val expires: Long) {
        var ready = false
        var revoked = false
        var staging = true
        var uncertain = false
        val uncertainOwners = mutableListOf<Any>()
        var opens = 0
        var expiryTask: Runnable? = null
        val transfers = mutableSetOf<Transfer>()
    }
    private class Transfer(val copy: Copy, val reader: FileInputStream) {
        @Volatile var stop = false
        var releaseAttempted = false
        val proxyReader = ShareProxyReader(copy.bytes,
            readAt = { data, offset, start, count -> reader.channel.read(ByteBuffer.wrap(data, start, count), offset) },
            closeInput = {
                try { reader.close() } catch (_: Exception) { }
                try { !reader.fd.valid() } catch (_: Exception) { false }
            },
            allowed = { !stop && !releaseAttempted && SystemClock.elapsedRealtime() < copy.expires })
    }
    private val directory = File(context.noBackupFilesDir, "library_share_v1").absoluteFile
    private val authority = context.packageName + ".library.share"
    private val lock = Any()
    private val copies = linkedMapOf<String, Copy>()
    private val storageManager = requireNotNull(context.getSystemService(StorageManager::class.java))
    private val ownerFile: RandomAccessFile
    private val ownerLock: java.nio.channels.FileLock
    private var cleanupBlocked = false
    private var proxyIssuances = 0
    init {
        require(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdirs()))
        require(directory.canonicalFile == directory)
        val lease = File(directory, ".owner")
        require(!Files.isSymbolicLink(lease.toPath()))
        ownerFile = RandomAccessFile(lease, "rw")
        ownerLock = try { ownerFile.channel.tryLock() ?: throw IOException("SHARE_BUSY") }
        catch (_: Exception) {
            try { ownerFile.close() } catch (_: Exception) { failedOwner = ownerFile }
            throw IOException("SHARE_BUSY")
        }
        // Process death closes app-owned disk FDs. Recipients hold virtual proxy descriptors,
        // not snapshot disk FDs; none is adopted as a new capability by this process.
        recoverPreviousProcess()
    }
    fun prepare(storage: AndroidSaveStorage, ref: MediaReadRef, operationId: String, cancelled: () -> Boolean = { false }): Intent {
        require(SharePolicy.validToken(operationId))
        val source = storage.openLibraryMedia(ref)
        var inputReleased = false
        var copy: Copy? = null
        var output: FileOutputStream? = null
        try {
            copy = synchronized(lock) {
                expireLocked()
                if (cancelled()) throw ShareFailure(ShareError.CANCELLED)
                if (cleanupBlocked) throw ShareFailure(ShareError.CLEANUP_PENDING)
                if (proxyIssuances >= SharePolicy.MAX_PROXY_ISSUANCES) throw ShareFailure(ShareError.PROCESS_CAPACITY)
                if (source.sizeBytes > SharePolicy.MAX_COPY_BYTES) throw ShareFailure(ShareError.SOURCE_TOO_LARGE)
                if (!SharePolicy.admit(copies.size, copies.values.sumOf { it.bytes }, source.sizeBytes)) throw ShareFailure(ShareError.COPY_CAPACITY)
                Copy(operationId, UUID.randomUUID().toString(), source.sizeBytes, SystemClock.elapsedRealtime() + SharePolicy.TTL_MILLIS).also { copies[it.token] = it }
            }
            val selected = copy
            // Durable source/destination intent precedes creating any copy bytes.
            FileOutputStream(path(selected.token, ".intent")).use { raw ->
                val out = DataOutputStream(raw)
                out.writeInt(1); out.writeUTF(selected.token); out.writeUTF(ref.itemId)
                out.writeLong(ref.revision); out.writeLong(ref.representationGeneration)
                out.writeLong(source.sizeBytes); out.writeUTF(source.sha256); out.flush(); raw.fd.sync()
            }
            syncDirectory()
            output = FileOutputStream(path(selected.token, ".part"))
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(SharePolicy.BUFFER_BYTES)
            var total = 0L
            while (true) {
                synchronized(lock) { if (cancelled() || selected.revoked || SystemClock.elapsedRealtime() >= selected.expires) throw ShareFailure(ShareError.CANCELLED) }
                val count = source.stream.read(buffer, 0, minOf(buffer.size.toLong(), source.sizeBytes - total + 1).toInt())
                if (count < 0) break
                if (count == 0 || count > source.sizeBytes - total) throw IOException("SHARE_SOURCE_MISMATCH")
                output.write(buffer, 0, count); digest.update(buffer, 0, count); total += count
            }
            if (total != source.sizeBytes || digest.digest().hex() != source.sha256) throw IOException("SHARE_SOURCE_MISMATCH")
            output.flush(); output.fd.sync(); output.close()
            if (output.fd.valid()) throw ShareFailure(ShareError.RELEASE_UNCONFIRMED)
            output = null
            val readback = FileInputStream(path(selected.token, ".part"))
            try { ShareCopyVerifier.verify(readback, source.sizeBytes, source.sha256, cancelled) }
            finally {
                try { readback.close() } catch (_: Exception) { }
                if (readback.fd.valid()) {
                    synchronized(lock) { selected.uncertain = true; selected.uncertainOwners += readback }
                    throw ShareFailure(ShareError.RELEASE_UNCONFIRMED)
                }
            }
            try { source.close() } catch (_: Exception) { }
            inputReleased = !source.stream.fd.valid()
            if (!inputReleased) throw ShareFailure(ShareError.RELEASE_UNCONFIRMED)
            Files.move(path(selected.token, ".part").toPath(), path(selected.token, ".mp4").toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory()
            synchronized(lock) {
                selected.staging = false
                if (cancelled() || selected.revoked || SystemClock.elapsedRealtime() >= selected.expires) {
                    selected.revoked = true; cleanupLocked(selected); throw ShareFailure(ShareError.CANCELLED)
                }
                selected.ready = true
            }
            synchronized(lock) {
                val expiry = Runnable { synchronized(lock) { selected.revoked = true; stopLocked(selected); cleanupLocked(selected) } }
                selected.expiryTask = expiry
                MaintenanceLane.handler.postDelayed(expiry, SharePolicy.TTL_MILLIS)
            }
            val uri = uri(selected.token)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Shared video", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // No chosen app is contacted here. Caller launches this only from deliberate foreground UI.
            return Intent.createChooser(send, "分享普通视频副本").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        } catch (failure: Exception) {
            synchronized(lock) { copy?.revoked = true }
            throw if (failure is ShareFailure) failure else ShareFailure(ShareError.IO)
        } finally {
            var outputReleased = true
            output?.let { stream ->
                try { stream.close() } catch (_: Exception) { }
                outputReleased = try { !stream.fd.valid() } catch (_: Exception) { false }
            }
            if (!inputReleased) {
                try { source.close() } catch (_: Exception) { }
                inputReleased = try { !source.stream.fd.valid() } catch (_: Exception) { false }
            }
            synchronized(lock) { copy?.let {
                it.staging = false
                if (!inputReleased || !outputReleased) {
                    it.uncertain = true; it.revoked = true
                    if (!inputReleased) it.uncertainOwners += source
                    if (!outputReleased) output?.let(it.uncertainOwners::add)
                }
                if (it.revoked) cleanupLocked(it)
            } }
        }
    }
    fun revokeOperation(operationId: String): Boolean = synchronized(lock) {
        copies.values.filter { it.operationId == operationId }.forEach { it.revoked = true; stopLocked(it); cleanupLocked(it) }
        copies.values.none { it.operationId == operationId }
    }
    fun revokeAll(): Boolean = synchronized(lock) {
        copies.values.toList().forEach { it.revoked = true; stopLocked(it); cleanupLocked(it) }
        if (copies.isEmpty() && cleanupBlocked) recoverPreviousProcess()
        copies.isEmpty() && !cleanupBlocked
    }
    fun metadata(uri: Uri): Long? = synchronized(lock) {
        expireLocked()
        val copy = resolve(uri) ?: return@synchronized null
        if (!copy.ready || copy.revoked) null else copy.bytes
    }
    fun open(uri: Uri): ParcelFileDescriptor = ShareProxyAdmission.open(lock, reserve = {
        expireLocked()
        val copy = resolve(uri) ?: throw FileNotFoundException("SHARE_UNAVAILABLE")
        val active = copies.values.sumOf { it.transfers.size }
        if (proxyIssuances >= SharePolicy.MAX_PROXY_ISSUANCES || !SharePolicy.canOpen(copy.ready, copy.revoked, SystemClock.elapsedRealtime(), copy.expires, copy.opens, active))
            throw FileNotFoundException("SHARE_UNAVAILABLE")
        val file = path(copy.token, ".mp4")
        if (!regular(file) || file.length() != copy.bytes) throw FileNotFoundException("SHARE_UNAVAILABLE")
        val input = FileInputStream(file)
        val transfer = Transfer(copy, input)
        copy.opens++; copy.transfers += transfer
        // Android12 FuseAppLoop can retain callback entries after release. Bound lifetime
        // issuance as well as live descriptors, and detach callback payloads on release.
        proxyIssuances++
        transfer
    }, open = { transfer ->
        val callback = CopyProxyCallback(transfer)
        try {
            // Framework open can wait for callbacks. Never hold the owner lock needed by
            // an earlier descriptor's onRelease while entering this blocking framework call.
            storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, CallbackLane.handler)
        } catch (_: Exception) {
            callback.onRelease()
            throw FileNotFoundException("SHARE_UNAVAILABLE")
        }
    })
    private inner class CopyProxyCallback(transfer: Transfer) : ProxyFileDescriptorCallback() {
        @Volatile private var owned: Transfer? = transfer
        override fun onGetSize(): Long {
            val current = owned ?: throw ErrnoException("SHARE_CLOSED", OsConstants.EBADF)
            requireActive(current)
            return current.copy.bytes
        }
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val current = owned ?: throw ErrnoException("SHARE_CLOSED", OsConstants.EBADF)
            return synchronized(current) {
                requireActive(current)
                try { current.proxyReader.read(offset, size, data) }
                catch (_: Exception) { throw ErrnoException("SHARE_READ_FAILED", OsConstants.EIO) }
            }
        }
        override fun onRelease() {
            val current = synchronized(this) { owned.also { owned = null } } ?: return
            releaseTransfer(current)
        }
    }
    private fun requireActive(transfer: Transfer) {
        if (transfer.stop || transfer.releaseAttempted || SystemClock.elapsedRealtime() >= transfer.copy.expires)
            throw ErrnoException("SHARE_REVOKED", OsConstants.EBADF)
    }
    private fun releaseTransfer(transfer: Transfer) = synchronized(transfer) {
        if (transfer.releaseAttempted) return@synchronized
        transfer.releaseAttempted = true
        val released = transfer.proxyReader.release()
        synchronized(lock) {
            if (released) transfer.copy.transfers -= transfer else {
                transfer.copy.uncertain = true
                transfer.copy.uncertainOwners += transfer.reader
            }
            // Exhausting the open budget never revokes an already admitted reader. Mark fully
            // spent only after every admitted descriptor has really released its private handle.
            if (transfer.copy.opens >= SharePolicy.MAX_OPENS && transfer.copy.transfers.isEmpty()) transfer.copy.revoked = true
            cleanupLocked(transfer.copy)
        }
    }
    private fun resolve(value: Uri): Copy? {
        if (value.scheme != "content" || value.authority != authority || value.query != null || value.fragment != null) return null
        val parts = value.pathSegments
        if (parts.size != 2 || parts[0] != "share" || !SharePolicy.validToken(parts[1]) || value != uri(parts[1])) return null
        return copies[parts[1]]
    }
    private fun uri(token: String): Uri = Uri.Builder().scheme("content").authority(authority).appendPath("share").appendPath(token).build()
    private fun expireLocked() { copies.values.toList().forEach { if (SystemClock.elapsedRealtime() >= it.expires) { it.revoked = true; stopLocked(it); cleanupLocked(it) } } }
    private fun stopLocked(copy: Copy) {
        context.revokeUriPermission(uri(copy.token), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The remote virtual descriptor may stay open. Future callbacks refuse access, while
        // the private staged-file owner remains until onRelease and verified input close.
        copy.transfers.forEach { it.stop = true }
    }
    private fun cleanupLocked(copy: Copy) {
        if (!copy.revoked || copy.staging || copy.uncertain || copy.transfers.isNotEmpty()) return
        try {
            context.revokeUriPermission(uri(copy.token), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            listOf(".part", ".mp4", ".intent").forEach { suffix ->
                val file = path(copy.token, suffix)
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && (!regular(file) || !file.delete())) throw IOException("SHARE_CLEANUP_PENDING")
            }
            syncDirectory()
            copy.expiryTask?.let { MaintenanceLane.handler.removeCallbacks(it) }
            copy.expiryTask = null
            copies.remove(copy.token)
        } catch (_: Exception) { /* Capacity remains held; retry revoke or next startup. */ }
    }
    private fun recoverPreviousProcess() {
        try {
            val entries = ArrayList<File>()
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (entry in stream) {
                    if (entry.fileName.toString() == ".owner") continue
                    if (entries.size >= SharePolicy.MAX_COPIES * 3) throw IOException("SHARE_RECOVERY_REQUIRED")
                    val file = entry.toFile(); val name = file.name
                    val suffix = listOf(".intent", ".part", ".mp4").firstOrNull { name.endsWith(it) } ?: throw IOException("SHARE_RECOVERY_REQUIRED")
                    val token = name.removeSuffix(suffix)
                    if (!SharePolicy.validToken(token) || !regular(file)) throw IOException("SHARE_RECOVERY_REQUIRED")
                    entries += file
                }
            }
            entries.forEach { file ->
                context.revokeUriPermission(uri(file.name.substringBefore('.')), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!file.delete()) throw IOException("SHARE_CLEANUP_PENDING")
            }
            syncDirectory()
            cleanupBlocked = false
        } catch (_: Exception) { cleanupBlocked = true }
    }
    private fun path(token: String, suffix: String): File {
        if (!SharePolicy.validToken(token) || suffix !in setOf(".intent", ".part", ".mp4") ||
            Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory) throw IOException("SHARE_INVALID_PATH")
        return File(directory, token + suffix).also { if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != directory) throw IOException("SHARE_INVALID_PATH") }
    }
    private fun regular(file: File) = !Files.isSymbolicLink(file.toPath()) && file.isFile && file.canonicalFile.parentFile == directory
    private fun syncDirectory() {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    // Lazily created process singletons. Failed owner initialization cannot multiply threads.
    private object CallbackLane {
        private val thread = HandlerThread("esv-share-proxy").apply { start() }
        val handler = Handler(thread.looper)
    }
    private object MaintenanceLane {
        private val thread = HandlerThread("esv-share-cleanup").apply { start() }
        val handler = Handler(thread.looper)
    }
    companion object {
        @Volatile private var singleton: LibraryShareOwner? = null
        @Volatile private var failedOwner: Any? = null
        fun get(context: Context): LibraryShareOwner = singleton ?: synchronized(this) {
            if (failedOwner != null) throw ShareFailure(ShareError.RELEASE_UNCONFIRMED)
            singleton ?: LibraryShareOwner(context.applicationContext).also { singleton = it }
        }
    }
}
