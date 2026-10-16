package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Subordinate files only. AndroidVaultDatabase remains the sole directory/lifetime owner. */
internal class VaultMediaFiles(private val directory: () -> File, private val syncDirectory: (File) -> Unit,
    private val openChannel: (File, Boolean) -> FileChannel = { file, writable ->
        if (writable) FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        else FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }) {
    private val lock = Any()
    private val outstandingResources = java.util.concurrent.atomic.AtomicInteger()
    fun observeMediaDrain(): ExportRelease = if (outstandingResources.get() == 0) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    private val owned = IdentityHashMap<VaultOwnedCiphertext, FileOwner>()
    private val ownership = VaultMediaFileOwnership()
    fun preflightReservation(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) = synchronized(lock) {
        ownership.preflight(operation, identity) {
            val parent = root()
            listOf(false, true).all { !Files.exists(path(parent, identity, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        }
    }
    fun confirmReservation(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) = synchronized(lock) { ownership.durable(operation, identity) }
    fun restoreAuthenticatedReservation(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) = synchronized(lock) { ownership.restoreAuthenticated(operation, identity) }
    fun retireReservation(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) = synchronized(lock) { ownership.retired(operation, identity) }
    fun retireClosedOwners() = synchronized(lock) { owned.entries.removeAll { it.value.closed }; Unit }


    fun create(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity): VaultOwnedCiphertext {
        synchronized(lock) { ownership.requireOwned(operation, identity); require(owned.size < VaultMediaFileOwnership.MAX_OWNERS) }
        val parent = root()
        val stage = path(parent, identity, false)
        val final = path(parent, identity, true)
        if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(final.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            synchronized(lock) { ownership.collision(identity) }
            error("VAULT_MEDIA_COLLISION")
        }
        outstandingResources.incrementAndGet() // Publish admission before opening; a throwing open remains unknown.
        val channel = openChannel(stage, true)
        val owner = FileOwner(operation.operationId, identity, parent, stage, channel, null, null, false)
        synchronized(lock) { owned[owner] = owner }
        try { owner.key = fileKey(stage); syncDirectory(parent) } catch (failure: Exception) {
            owner.closeAndObserve() // Unknown/failed close keeps the exact owner in the registry.
            throw failure
        }
        return owner
    }
    fun open(identity: VaultMediaObjectIdentity): VaultOwnedCiphertext {
        synchronized(lock) {
            owned.entries.removeAll { it.value.closed && it.value.operationId == "read_only" }
            require(owned.size < VaultMediaFileOwnership.MAX_OWNERS)
        }
        val parent = root(); val final = path(parent, identity, true)
        val key = fileKey(final)
        outstandingResources.incrementAndGet()
        val channel = openChannel(final, false)
        val owner = FileOwner("read_only", identity, parent, final, null, channel, key, true)
        synchronized(lock) { owned[owner] = owner }
        try { require(fileKey(final) == key) } catch (failure: Exception) {
            owner.closeAndObserve()
            throw failure
        }
        return owner
    }
    fun owns(file: VaultOwnedCiphertext): Boolean = synchronized(lock) { owned.containsKey(file) }
    fun evidence(file: VaultOwnedCiphertext, context: VaultOperationContext): VaultCiphertextEvidence {
        val owner = owner(file); require(owner.sealed && !owner.closed)
        val size = file.size(); require(size in 1..VaultMediaLimits.MAX_CIPHERTEXT_BYTES)
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES); val hash = MessageDigest.getInstance("SHA-256")
        var position = 0L; var zeros = 0
        try {
            while (position < size) {
                context.checkActive()
                val count = file.readAt(position, buffer, 0, minOf(buffer.size.toLong(), size - position).toInt())
                require(count >= 0)
                if (count == 0) { require(++zeros <= VaultMediaLimits.MAX_ZERO_PROGRESS); continue }
                zeros = 0; hash.update(buffer, 0, count); position += count
            }
            require(file.readAt(size, buffer, 0, 1) == -1)
            context.checkActive()
            return VaultCiphertextEvidence(size, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { buffer.fill(0) }
    }
    fun promote(file: VaultOwnedCiphertext) {
        val owner = owner(file)
        synchronized(owner) {
            require(owner.sealed && !owner.closed)
            require(owner.key != null && fileKey(owner.file) == owner.key)
            if (owner.finalized) { syncDirectory(owner.parent); return }
            val destination = path(owner.parent, owner.identity, true)
            require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            Files.move(owner.file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            owner.file = destination; owner.finalized = true
            syncDirectory(owner.parent)
            require(fileKey(destination) == owner.key)
        }
    }
    /** Caller has proved exact object is uncommitted and unpinned. No directory scan or guessing. */
    fun cleanup(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity): Boolean {
        val matching = synchronized(lock) {
            ownership.requireOwned(operation, identity)
            owned.values.filter { it.identity == identity }
        }
        val releases = matching.map { it.closeAndObserve() }
        if (releases.any { it != ExportRelease.RELEASED }) return false
        val parent = root()
        for (final in listOf(false, true)) {
            val file = path(parent, identity, final)
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val actual = fileKey(file)
                val expected = matching.filter { it.file == file }.mapNotNull { it.key }.distinct()
                if (expected.size > 1 || (expected.isNotEmpty() && expected.single() != actual)) {
                    synchronized(lock) { ownership.collision(identity) }
                    return false
                }
                Files.delete(file.toPath())
            }
        }
        syncDirectory(parent)
        return listOf(false, true).all { !Files.exists(path(parent, identity, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
    }
    fun hasUnreleased(): Boolean = synchronized(lock) { owned.values.any { !it.closed } }
    fun closeAll(): ExportRelease {
        val owners = synchronized(lock) { owned.values.toList() }
        val releases = owners.map { it.closeAndObserve() }
        return if (releases.all { it == ExportRelease.RELEASED } && observeMediaDrain() == ExportRelease.RELEASED)
            ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    }
    private fun owner(file: VaultOwnedCiphertext): FileOwner = synchronized(lock) { requireNotNull(owned[file]) }
    private fun root(): File = directory().absoluteFile.also {
        require(it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it)
    }
    private fun path(parent: File, identity: VaultMediaObjectIdentity, finalized: Boolean): File =
        File(parent, "media_${identity.objectId}_${identity.generation}.${if (finalized) "cipher" else "part"}").also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == parent)
        }
    private fun fileKey(file: File): Any {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink)
        return requireNotNull(attrs.fileKey())
    }
    private inner class FileOwner(override val operationId: String, override val identity: VaultMediaObjectIdentity,
        val parent: File, var file: File, private var writer: FileChannel?, private var reader: FileChannel?,
        var key: Any?, var finalized: Boolean) : VaultOwnedCiphertext {
        var sealed = writer == null; private set
        var closed = false; private set
        private var closeUnconfirmed = false
        private var readerOpeningUnknown = false
        private val writerClose = VaultCloseOnce({ writer?.close() }, { writer?.isOpen != true })
        private val readerClose = VaultCloseOnce({ reader?.close() }, { reader?.isOpen != true })
        private var appended = 0L
        @Synchronized override fun append(bytes: ByteArray, offset: Int, length: Int) {
            require(!sealed && !closed && !closeUnconfirmed && length in 1..VaultMediaLimits.BUFFER_BYTES)
            require(offset >= 0 && offset <= bytes.size - length && appended <= VaultMediaLimits.MAX_CIPHERTEXT_BYTES - length)
            val channel = requireNotNull(writer); val buffer = ByteBuffer.wrap(bytes, offset, length)
            var zeros = 0
            while (buffer.hasRemaining()) {
                val count = channel.write(buffer)
                require(count >= 0)
                if (count == 0) require(++zeros <= VaultMediaLimits.MAX_ZERO_PROGRESS) else { appended += count; zeros = 0 }
            }
        }
        @Synchronized override fun forceAndCloseWrite(): ExportRelease {
            if (closed || closeUnconfirmed) return ExportRelease.UNCONFIRMED
            if (sealed) return ExportRelease.RELEASED
            val channel = requireNotNull(writer)
            try {
                require(appended > 0 && key != null && fileKey(file) == key)
                channel.force(true)
                readerOpeningUnknown = true
                reader = openChannel(file, false)
                readerOpeningUnknown = false
                require(fileKey(file) == key)
                require(writerClose.closeAndObserve() == ExportRelease.RELEASED && !channel.isOpen)
                writer = null; sealed = true
                syncDirectory(parent)
                return ExportRelease.RELEASED
            } catch (_: Exception) { closeUnconfirmed = true; return ExportRelease.UNCONFIRMED }
        }
        @Synchronized override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int {
            require(sealed && !closed && !closeUnconfirmed && position >= 0 && length in 0..VaultMediaLimits.BUFFER_BYTES)
            require(offset >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            require(position <= Long.MAX_VALUE - length)
            return requireNotNull(reader).read(ByteBuffer.wrap(bytes, offset, length), position)
        }
        @Synchronized override fun size(): Long {
            require(sealed && !closed && !closeUnconfirmed)
            return requireNotNull(reader).size().also { require(it in 1..VaultMediaLimits.MAX_CIPHERTEXT_BYTES) }
        }
        @Synchronized override fun closeAndObserve(): ExportRelease {
            if (closed) return ExportRelease.RELEASED
            val writeRelease = writerClose.closeAndObserve()
            val readRelease = readerClose.closeAndObserve()
            if (readerOpeningUnknown || writeRelease != ExportRelease.RELEASED || readRelease != ExportRelease.RELEASED) {
                closeUnconfirmed = true
                return ExportRelease.UNCONFIRMED
            }
            if (writer?.isOpen == true || reader?.isOpen == true) { closeUnconfirmed = true; return ExportRelease.UNCONFIRMED }
            writer = null; reader = null; closed = true
            outstandingResources.decrementAndGet()
            return ExportRelease.RELEASED
        }
        override fun toString() = "VaultOwnedCiphertext(REDACTED)"
    }
}
