package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.IdentityHashMap

internal data class NormalizationFileIdentity(
    val stat: NormalizationDescriptorIo.Stat,
    val parents: List<Pair<File, NormalizationDescriptorIo.Stat>>,
)
internal data class NormalizationBoundFile(val identity: NormalizationFileIdentity, val fingerprint: NormalizationFingerprint)

/**
 * Exact generated roles, private UID directory and the storage owner's lifetime lease/lock.
 * Public path syscalls are not a sandbox against a malicious same-UID namespace writer.
 * Temporary hash/fsync descriptors have the same positive-release registry as remux handles.
 */
internal class AndroidNormalizationFiles(
    private val root: File,
    private val durable: DurableFileOps,
    internal val ownerLock: Any,
    private val freeBytes: () -> Long,
    private val closed: () -> Boolean,
    internal val io: NormalizationDescriptorIo = AndroidNormalizationDescriptorIo,
) : NormalizationFileOps {
    private val descriptors = java.util.Collections.newSetFromMap(IdentityHashMap<Descriptor, Boolean>())
    private var directoryLeases = 0
    private val failedDirectoryReleases = mutableListOf<Throwable>()
    private val directoryIdentities = mutableMapOf<File, NormalizationDescriptorIo.Stat>()
    @Volatile private var poisoned = false
    private val closing = java.util.concurrent.atomic.AtomicInteger()
    private var operationCheck: () -> Unit = {}
    internal fun <T> withOperation(check: () -> Unit, block: () -> T): T = synchronized(ownerLock) {
        val previous = operationCheck; operationCheck = check
        try { block() }
        catch (failure: IOException) {
            // EINTR/InterruptedIOException (and ordinary IO failure) never imply zero progress.
            // Recheck only cancellation/deadline; no syscall retry or authority/DB lookup here.
            // Unconfirmed release has already become SaveFailure and must not be overwritten.
            check()
            throw failure
        } finally { operationCheck = previous }
    }
    internal fun hasAnyLeases(): Boolean = synchronized(ownerLock) { descriptors.isNotEmpty() || directoryLeases != 0 }
    internal fun hasLeases(key: NormalizationKey): Boolean = synchronized(ownerLock) { directoryLeases != 0 || descriptors.any { it.key == key } }
    /** Admission-safe: memory only; never acquires the storage lock or touches the filesystem. */
    internal fun releaseUnconfirmed(): Boolean = poisoned || closing.get() != 0
    internal fun requireAvailable() {
        operationCheck()
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (releaseUnconfirmed()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    override fun requireReleased(key: NormalizationKey) = synchronized(ownerLock) {
        requireAvailable()
        if (directoryLeases != 0 || descriptors.any { it.key == key }) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    override fun availableBytes(): Long = synchronized(ownerLock) { requireAvailable(); freeBytes().coerceAtLeast(0) }

    private fun directory(key: NormalizationKey, role: NormalizationDirectory): File {
        val staging = File(root, "staging")
        val workspace = File(staging, key.owner.artifactId + ".normalization")
        return when (role) { NormalizationDirectory.STAGING -> staging; NormalizationDirectory.WORKSPACE -> workspace
            NormalizationDirectory.GENERATION -> File(workspace, key.normalizationId) }
    }
    private fun path(key: NormalizationKey, role: NormalizationRole): File = when (role) {
        NormalizationRole.PRIMARY -> File(directory(key, NormalizationDirectory.STAGING), key.owner.artifactId + ".part")
        NormalizationRole.RAW -> File(directory(key, NormalizationDirectory.GENERATION), "raw.bin")
        NormalizationRole.OUTPUT -> File(directory(key, NormalizationDirectory.GENERATION), "output.mp4")
    }
    private fun checkedDirectory(path: File, required: Boolean, device: Long? = null): NormalizationDescriptorIo.Stat? {
        val stat = io.lstat(path) ?: return if (required) fail() else null
        if (stat.kind != NormalizationDescriptorIo.Kind.DIRECTORY || (device != null && stat.device != device)) fail()
        val prior = directoryIdentities[path]
        if (prior != null && !prior.sameInode(stat)) fail()
        directoryIdentities[path] = stat
        return stat
    }
    private fun poison() {
        if (!poisoned) { poisoned = true; failedOwners.add(this) }
    }
    private fun enumerate(path: File, maximum: Int): List<String> {
        directoryLeases++
        var started = false
        var confirmed = false
        try {
            val result = io.children(path, maximum, {
                started = true; closing.incrementAndGet()
            }, {
                confirmed = true; closing.decrementAndGet()
            })
            if (!started || !confirmed) throw NormalizationDirectoryReleaseUnconfirmed(io)
            return result
        } catch (failure: Throwable) {
            if ((started && !confirmed) || failure is NormalizationDirectoryReleaseUnconfirmed) {
                // The exception strongly retains the actual DirectoryStream on Android.
                failedDirectoryReleases += failure
                poison()
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
            throw failure
        } finally {
            if (!started || confirmed) directoryLeases--
        }
    }
    private fun children(path: File, allowed: Set<String>) {
        val entries = enumerate(path, allowed.size)
        if (entries.size > allowed.size || entries.toSet().size != entries.size || entries.any { it !in allowed }) fail()
    }
    private fun parents(key: NormalizationKey, role: NormalizationRole): List<Pair<File, NormalizationDescriptorIo.Stat>> {
        requireAvailable()
        val rootStat = checkedDirectory(root, true)!!
        val result = mutableListOf(root to rootStat)
        val staging = directory(key, NormalizationDirectory.STAGING)
        result += staging to checkedDirectory(staging, true, rootStat.device)!!
        val workspace = directory(key, NormalizationDirectory.WORKSPACE)
        val ws = checkedDirectory(workspace, role != NormalizationRole.PRIMARY, rootStat.device)
        if (ws != null) {
            children(workspace, setOf(key.normalizationId))
            val generation = directory(key, NormalizationDirectory.GENERATION)
            val gen = checkedDirectory(generation, role != NormalizationRole.PRIMARY, rootStat.device)
            if (gen != null) children(generation, setOf("raw.bin", "output.mp4"))
            if (role != NormalizationRole.PRIMARY) { result += workspace to ws; result += generation to (gen ?: fail()) }
        }
        return result
    }
    private fun regular(stat: NormalizationDescriptorIo.Stat, device: Long, maximum: Long, empty: Boolean) {
        if (stat.kind != NormalizationDescriptorIo.Kind.REGULAR || stat.links != 1L || stat.device != device ||
            stat.size !in (if (empty) 0L else 1L)..maximum) fail()
    }
    private fun rejectAliases(key: NormalizationKey) {
        val seen = mutableSetOf<Pair<Long, Long>>()
        for (role in NormalizationRole.entries) {
            val stat = io.lstat(path(key, role)) ?: continue
            if (!seen.add(stat.device to stat.inode)) fail()
        }
    }
    internal fun capture(key: NormalizationKey, role: NormalizationRole, maximum: Long = NORMALIZATION_MAX_BYTES,
        empty: Boolean = false): NormalizationFileIdentity = synchronized(ownerLock) {
        val parents = parents(key, role)
        val stat = io.lstat(path(key, role)) ?: fail()
        regular(stat, parents.first().second.device, maximum, empty)
        rejectAliases(key)
        NormalizationFileIdentity(stat, parents)
    }
    internal fun validate(key: NormalizationKey, role: NormalizationRole, identity: NormalizationFileIdentity,
        descriptor: Descriptor? = null, expected: NormalizationDescriptorIo.Stat = identity.stat) = synchronized(ownerLock) {
        val currentParents = parents(key, role)
        if (currentParents.size != identity.parents.size || currentParents.zip(identity.parents).any { (a, b) -> a.first != b.first || !a.second.sameInode(b.second) }) fail()
        val leaf = io.lstat(path(key, role)) ?: fail()
        if (leaf != expected || !leaf.sameInode(identity.stat) || leaf.links != 1L || leaf.kind != NormalizationDescriptorIo.Kind.REGULAR) fail()
        if (descriptor != null && io.fstat(descriptor.handle) != expected) fail()
        rejectAliases(key)
    }
    internal fun open(key: NormalizationKey, role: NormalizationRole, mode: NormalizationDescriptorIo.Open,
        identity: NormalizationFileIdentity, check: () -> Unit, released: () -> Unit = {}): Descriptor = synchronized(ownerLock) {
        check(); validate(key, role, identity)
        if (mode !in setOf(NormalizationDescriptorIo.Open.READ, NormalizationDescriptorIo.Open.WRITE_EXISTING)) fail()
        if (mode == NormalizationDescriptorIo.Open.WRITE_EXISTING && (role != NormalizationRole.OUTPUT || identity.stat.size != 0L)) fail()
        val descriptor = register(key, io.open(path(key, role), mode), released)
        try { validate(key, role, identity, descriptor); check(); descriptor }
        catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
    }
    private fun register(key: NormalizationKey, handle: NormalizationDescriptorIo.Handle, released: () -> Unit = {}): Descriptor =
        Descriptor(key, handle, released).also { descriptors.add(it) }
    private fun closeAfterFailure(descriptor: Descriptor, failure: Throwable): Nothing {
        try { descriptor.close() } catch (_: Exception) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
        if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        throw failure
    }
    internal fun fingerprint(key: NormalizationKey, role: NormalizationRole, check: () -> Unit = operationCheck,
        sync: Boolean = false): NormalizationBoundFile = synchronized(ownerLock) {
        val identity = capture(key, role, empty = true)
        val descriptor = open(key, role, NormalizationDescriptorIo.Open.READ, identity, check)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            descriptor.call {
                if (sync) { check(); io.fsync(descriptor.handle); check() }
                val buffer = ByteArray(CHUNK)
                var position = 0L
                while (position < identity.stat.size) {
                    check(); validate(key, role, identity, descriptor)
                    val count = minOf(CHUNK.toLong(), identity.stat.size - position).toInt()
                    val actual = io.pread(descriptor.handle, buffer, 0, count, position)
                    if (actual <= 0 || actual > count) throw IOException("NORMALIZATION_READ_PROGRESS")
                    digest.update(buffer, 0, actual); position += actual
                    check(); validate(key, role, identity, descriptor)
                }
                check(); validate(key, role, identity, descriptor)
            }
        } catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
        descriptor.close()
        if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        NormalizationBoundFile(identity, NormalizationFingerprint(identity.stat.size, digest.digest().joinToString("") { "%02x".format(it) }))
    }
    override fun snapshot(key: NormalizationKey): NormalizationFilesSnapshot = synchronized(ownerLock) {
        requireReleased(key); parents(key, NormalizationRole.PRIMARY)
        val workspace = io.lstat(directory(key, NormalizationDirectory.WORKSPACE)) != null
        val generation = workspace && io.lstat(directory(key, NormalizationDirectory.GENERATION)) != null
        fun hash(role: NormalizationRole): NormalizationFingerprint? = if (io.lstat(path(key, role)) == null) null else fingerprint(key, role).fingerprint
        NormalizationFilesSnapshot(workspace, generation, hash(NormalizationRole.PRIMARY),
            if (generation) hash(NormalizationRole.RAW) else null, if (generation) hash(NormalizationRole.OUTPUT) else null)
    }
    override fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory) = synchronized(ownerLock) {
        require(directory != NormalizationDirectory.STAGING); snapshot(key)
        val path = directory(key, directory)
        if (io.lstat(path) != null) fail()
        operationCheck(); io.mkdir(path); operationCheck()
        checkedDirectory(path, true, checkedDirectory(root, true)!!.device)
        Unit
    }
    override fun createEmptyOutput(key: NormalizationKey) = synchronized(ownerLock) {
        if (!snapshot(key).generationExists) fail()
        val parents = parents(key, NormalizationRole.OUTPUT)
        val path = path(key, NormalizationRole.OUTPUT)
        if (io.lstat(path) != null) fail()
        val descriptor = register(key, io.open(path, NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE))
        try {
            val identity = capture(key, NormalizationRole.OUTPUT, empty = true)
            if (identity.stat.size != 0L || identity.parents.zip(parents).any { (a, b) -> !a.second.sameInode(b.second) }) fail()
            validate(key, NormalizationRole.OUTPUT, identity, descriptor)
            descriptor.call { operationCheck(); io.fsync(descriptor.handle); operationCheck() }
        } catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
        descriptor.close()
        if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    override fun syncOutput(key: NormalizationKey) = synchronized(ownerLock) {
        requireReleased(key)
        // READ never creates a missing O. fsync is legal on this owned read descriptor.
        fingerprint(key, NormalizationRole.OUTPUT, sync = true)
        Unit
    }
    override fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory) = synchronized(ownerLock) {
        requireReleased(key); parents(key, NormalizationRole.PRIMARY)
        val path = directory(key, directory)
        val expected = checkedDirectory(path, true, checkedDirectory(root, true)!!.device)!!
        val descriptor = register(key, io.open(path, NormalizationDescriptorIo.Open.DIRECTORY))
        try {
            descriptor.call {
                if (!io.fstat(descriptor.handle).sameInode(expected)) fail()
                operationCheck(); io.fsync(descriptor.handle); operationCheck()
                if (!io.fstat(descriptor.handle).sameInode(expected) || checkedDirectory(path, true)?.sameInode(expected) != true) fail()
                parents(key, NormalizationRole.PRIMARY)
            }
        } catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
        descriptor.close()
        if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    override fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole,
        expected: NormalizationFingerprint) = synchronized(ownerLock) {
        require((source == NormalizationRole.PRIMARY && destination == NormalizationRole.RAW) ||
            (source == NormalizationRole.OUTPUT && destination == NormalizationRole.PRIMARY))
        val snapshot = snapshot(key)
        if (snapshot[source] != expected || snapshot[destination] != null) fail()
        val original = capture(key, source, empty = true)
        parents(key, destination)
        validate(key, source, original)
        if (io.lstat(path(key, destination)) != null) fail()
        operationCheck(); durable.promoteNoReplace(path(key, source), path(key, destination)); operationCheck()
        val moved = capture(key, destination, empty = true)
        if (!original.stat.sameInode(moved.stat) || io.lstat(path(key, source)) != null || fingerprint(key, destination).fingerprint != expected) fail()
    }
    override fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint) = synchronized(ownerLock) {
        requireReleased(key)
        val observed = fingerprint(key, role)
        if (observed.fingerprint != expected) fail()
        validate(key, role, observed.identity)
        operationCheck(); io.unlink(path(key, role)); operationCheck()
        if (io.lstat(path(key, role)) != null) fail()
    }
    override fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory) = synchronized(ownerLock) {
        require(directory != NormalizationDirectory.STAGING); snapshot(key)
        val path = directory(key, directory)
        checkedDirectory(path, true)
        if (enumerate(path, 0).isNotEmpty()) fail()
        operationCheck(); io.rmdir(path); operationCheck()
        if (io.lstat(path) != null) fail()
        directoryIdentities.remove(path)
        Unit
    }

    /** A close exception is final and never retries a possibly reused integer descriptor. */
    internal inner class Descriptor internal constructor(
        val key: NormalizationKey, internal val handle: NormalizationDescriptorIo.Handle, private val released: () -> Unit,
    ) : AutoCloseable {
        private val thread = Thread.currentThread()
        private var calls = 0
        @Volatile internal var closeRequested = false; private set
        private var closeAttempted = false
        @Volatile var releaseConfirmed = false; private set
        internal fun <T> call(block: () -> T): T {
            synchronized(ownerLock) {
                requireAvailable()
                if (Thread.currentThread() !== thread || closeRequested || calls != 0) fail()
                calls++
            }
            try { return block() }
            finally {
                val closeNow = synchronized(ownerLock) {
                    calls--
                    (closeRequested && calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
                }
                if (closeNow) closeUnderlying()
            }
        }
        override fun close() {
            val closeNow = synchronized(ownerLock) {
                if (releaseConfirmed) return
                if (!closeRequested) { closeRequested = true; closing.incrementAndGet() }
                (calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
            }
            if (closeNow) closeUnderlying()
            else if (!releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        private fun closeUnderlying() {
            try {
                io.close(handle)
                if (io.valid(handle)) throw IOException("NORMALIZATION_CLOSE_UNCONFIRMED")
                synchronized(ownerLock) { releaseConfirmed = true; descriptors.remove(this); closing.decrementAndGet() }
            } catch (_: Exception) {
                synchronized(ownerLock) {
                    // Retain descriptors, callbacks and storage owner until process death.
                    // Concurrent queue avoids another monitor/lock order.
                    poison()
                }
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
            released()
        }
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
    private companion object {
        const val CHUNK = 64 * 1024
        val failedOwners = java.util.concurrent.ConcurrentLinkedQueue<AndroidNormalizationFiles>()
    }
}
