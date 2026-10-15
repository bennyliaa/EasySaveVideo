package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class NormalizationFileIdentity(
    val stat: NormalizationDescriptorIo.Stat,
    val parents: List<Pair<File, NormalizationDescriptorIo.Stat>>,
)
internal data class NormalizationBoundFile(val identity: NormalizationFileIdentity, val fingerprint: NormalizationFingerprint)

/** Exact generated roles; all filesystem work is outside the shared storage monitor. */
internal class AndroidNormalizationFiles(
    private val root: File,
    private val durable: DurableFileOps,
    internal val ownerLock: Any,
    private val freeBytes: () -> Long,
    private val closed: () -> Boolean,
    internal val io: NormalizationDescriptorIo = AndroidNormalizationDescriptorIo,
    internal val reservations: NormalizationIoReservations = NormalizationIoReservations(ownerLock),
) : NormalizationFileOps {
    private val descriptors = java.util.Collections.newSetFromMap(IdentityHashMap<Descriptor, Boolean>())
    private val asyncDescriptors = java.util.Collections.newSetFromMap(IdentityHashMap<AsyncAnchor, Boolean>())
    private val pending = mutableMapOf<NormalizationKey, Int>()
    private var directoryLeases = 0
    private val failedReleases = mutableListOf<Any>()
    private val directoryIdentities = mutableMapOf<File, NormalizationDescriptorIo.Stat>()
    @Volatile private var poisoned = false
    private val liveResources = AtomicInteger()
    private val activeCalls = AtomicInteger()
    private val closing = AtomicInteger()

    init { require(reservations.ownerLock === ownerLock) }
    /** Immutable operation-local callbacks. Never install a mutable shared callback or ThreadLocal. */
    internal fun forOperation(check: () -> Unit, reservation: NormalizationIoReservations.Token? = null): Operation =
        Operation(check, reservation)
    internal fun hasAnyLeases(): Boolean = liveResources.get() != 0 || activeCalls.get() != 0
    internal fun hasLeases(key: NormalizationKey): Boolean = synchronized(ownerLock) {
        directoryLeases != 0 || pending[key] != null || descriptors.any { it.key == key } || asyncDescriptors.any { it.key == key }
    }
    /** Admission-safe memory reads. Pending healthy IO is not itself release uncertainty. */
    internal fun releaseUnconfirmed(): Boolean = poisoned
    internal fun requireAvailable() {
        if (poisoned) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
    }
    override fun requireReleased(key: NormalizationKey) = synchronized(ownerLock) {
        requireAvailable()
        if (directoryLeases != 0 || pending[key] != null || descriptors.any { it.key == key } || asyncDescriptors.any { it.key == key }) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    override fun availableBytes(): Long = forOperation({}).availableBytes()
    override fun snapshot(key: NormalizationKey) = forOperation({}).snapshot(key)
    override fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory) = forOperation({}).createDirectory(key, directory)
    override fun createEmptyOutput(key: NormalizationKey) = forOperation({}).createEmptyOutput(key)
    override fun syncOutput(key: NormalizationKey) = forOperation({}).syncOutput(key)
    override fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory) = forOperation({}).syncDirectory(key, directory)
    override fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole,
        expected: NormalizationFingerprint) = forOperation({}).moveNoReplace(key, source, destination, expected)
    override fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint) = forOperation({}).deleteExact(key, role, expected)
    override fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory) = forOperation({}).removeEmptyDirectory(key, directory)
    internal fun capture(key: NormalizationKey, role: NormalizationRole, maximum: Long = NORMALIZATION_MAX_BYTES,
        empty: Boolean = false) = forOperation({}).capture(key, role, maximum, empty)
    internal fun validate(key: NormalizationKey, role: NormalizationRole, identity: NormalizationFileIdentity,
        descriptor: Descriptor? = null, expected: NormalizationDescriptorIo.Stat = identity.stat) =
        forOperation({}).validate(key, role, identity, descriptor, expected)
    internal fun open(key: NormalizationKey, role: NormalizationRole, mode: NormalizationDescriptorIo.Open,
        identity: NormalizationFileIdentity, check: () -> Unit, released: () -> Unit = {}): Descriptor =
        forOperation(check).open(key, role, mode, identity, released)
    internal fun fingerprint(key: NormalizationKey, role: NormalizationRole, check: () -> Unit = {},
        sync: Boolean = false) = forOperation(check).fingerprint(key, role, sync)

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
    private fun poison(retained: Any, token: NormalizationIoReservations.Token?) {
        synchronized(ownerLock) {
            failedReleases.add(retained)
            if (!poisoned) { poisoned = true; failedOwners.add(this) }
            if (token != null) reservations.markUncertain(token, retained)
        }
    }
    private fun beginResource(key: NormalizationKey, token: NormalizationIoReservations.Token?): NormalizationIoReservations.Obligation? = synchronized(ownerLock) {
        requireAvailable()
        val obligation = token?.let(reservations::retain)
        pending[key] = (pending[key] ?: 0) + 1
        liveResources.incrementAndGet()
        obligation
    }
    private fun constructed(key: NormalizationKey) = synchronized(ownerLock) {
        val count = pending[key] ?: fail()
        if (count == 1) pending.remove(key) else pending[key] = count - 1
    }
    private fun releasedResource(obligation: NormalizationIoReservations.Obligation?) {
        if (obligation != null) reservations.release(obligation)
        check(liveResources.decrementAndGet() >= 0)
    }

    internal inner class Operation internal constructor(
        private val checkOperation: () -> Unit,
        private val token: NormalizationIoReservations.Token?,
    ) : NormalizationFileOps {
        // One existing OUTPUT-opening operation, not a new registry or caller-writable cause port.
        private val openingFailureLock = Any()
        private var openingAttempt: OwnedDirectTranscodeAttempt? = null
        private var openingClaim: Any? = null
        @Volatile private var openingDescriptor: AsyncOutputDescriptor? = null
        private var pendingOpeningFailure: Throwable? = null
        private var selectedOpeningFailure: Throwable? = null
        private var openingFailureThread: Thread? = null
        internal fun ownsOpeningDescriptor(attempt: OwnedDirectTranscodeAttempt, claim: Any, descriptor: AsyncAnchor): Boolean =
            synchronized(openingFailureLock) { openingAttempt === attempt && openingClaim === claim && openingDescriptor === descriptor }
        internal fun pendingOpeningFailureFor(attempt: OwnedDirectTranscodeAttempt, claim: Any): Throwable? = synchronized(openingFailureLock) {
            if (openingAttempt !== attempt || openingClaim !== claim || openingFailureThread !== Thread.currentThread()) null else pendingOpeningFailure
        }
        internal fun selectedOpeningFailureFor(attempt: OwnedDirectTranscodeAttempt, claim: Any): Throwable? = synchronized(openingFailureLock) {
            if (openingAttempt !== attempt || openingClaim !== claim) null else selectedOpeningFailure
        }
        private fun selectOpeningFailure(failure: Throwable): Throwable = synchronized(openingFailureLock) {
            if (selectedOpeningFailure != null) return@synchronized failure
            pendingOpeningFailure = failure; openingFailureThread = Thread.currentThread()
            val chosen = try {
                val attempt = openingAttempt; val claim = openingClaim
                if (attempt == null || claim == null) failure else attempt.captureOpeningFailure(this, claim) ?: failure
            } catch (_: Throwable) { failure } // Failure observation must never prevent mandatory poison.
            finally { pendingOpeningFailure = null; openingFailureThread = null }
            selectedOpeningFailure = chosen
            chosen
        }
        private fun check() { requireAvailable(); token?.requireLive(); checkOperation() }
        private fun allow(vararg stages: NormalizationIoReservations.Stage) {
            if (token != null && token.stage !in stages) fail()
        }
        private fun readable(role: NormalizationRole) {
            allow(NormalizationIoReservations.Stage.RAW_PROBE, NormalizationIoReservations.Stage.PREPARE,
                NormalizationIoReservations.Stage.REMUX, NormalizationIoReservations.Stage.TRANSCODE, NormalizationIoReservations.Stage.FINISH,
                NormalizationIoReservations.Stage.INSPECT, NormalizationIoReservations.Stage.ADOPT, NormalizationIoReservations.Stage.CLEANUP)
            if (token?.key == null && token != null && role != NormalizationRole.PRIMARY) fail()
        }
        private fun <T> call(key: NormalizationKey? = null, block: () -> T): T {
            check()
            if (key != null && token != null) {
                if (token.key == null) reservations.requireExact(token, key.owner, null, NormalizationIoReservations.Stage.RAW_PROBE)
                else reservations.requireExact(token, key.owner, key)
            }
            activeCalls.incrementAndGet()
            try {
                val action = { check(); block().also { check() } }
                return if (token != null) reservations.withIo(token, action) else action()
            } catch (failure: IOException) {
                if (synchronized(openingFailureLock) { selectedOpeningFailure !== failure }) check()
                throw failure
            }
            finally { activeCalls.decrementAndGet() }
        }
        override fun requireReleased(key: NormalizationKey) { check(); this@AndroidNormalizationFiles.requireReleased(key) }
        override fun availableBytes(): Long = call { freeBytes().coerceAtLeast(0) }
        private fun checkedDirectory(path: File, required: Boolean, device: Long? = null): NormalizationDescriptorIo.Stat? {
            check()
            val stat = io.lstat(path) ?: return if (required) fail() else null
            check()
            if (stat.kind != NormalizationDescriptorIo.Kind.DIRECTORY || (device != null && stat.device != device)) fail()
            synchronized(ownerLock) {
                val prior = directoryIdentities[path]
                if (prior != null && !prior.sameInode(stat)) fail()
                directoryIdentities[path] = stat
            }
            return stat
        }
        private fun enumerate(key: NormalizationKey, path: File, maximum: Int): List<String> {
            val obligation = beginResource(key, token)
            synchronized(ownerLock) { directoryLeases++ }
            var started = false
            var confirmed = false
            try {
                val result = io.children(path, maximum, {
                    started = true; closing.incrementAndGet()
                }, {
                    confirmed = true; closing.decrementAndGet()
                })
                if (!started || !confirmed) throw NormalizationDirectoryReleaseUnconfirmed(io)
                check()
                return result
            } catch (failure: Throwable) {
                if (!confirmed) {
                    // A throwing constructor has no positive no-handle/close acknowledgement.
                    poison(failure, token)
                    throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                }
                throw failure
            } finally {
                if (confirmed) {
                    synchronized(ownerLock) { directoryLeases--; constructed(key) }
                    releasedResource(obligation)
                }
            }
        }
        private fun children(key: NormalizationKey, path: File, allowed: Set<String>) {
            val entries = enumerate(key, path, allowed.size)
            if (entries.size > allowed.size || entries.toSet().size != entries.size || entries.any { it !in allowed }) fail()
        }
        private fun parents(key: NormalizationKey, role: NormalizationRole): List<Pair<File, NormalizationDescriptorIo.Stat>> {
            check()
            val rootStat = checkedDirectory(root, true)!!
            val result = mutableListOf(root to rootStat)
            val staging = directory(key, NormalizationDirectory.STAGING)
            result += staging to checkedDirectory(staging, true, rootStat.device)!!
            val workspace = directory(key, NormalizationDirectory.WORKSPACE)
            val ws = checkedDirectory(workspace, role != NormalizationRole.PRIMARY, rootStat.device)
            if (ws != null) {
                children(key, workspace, setOf(key.normalizationId))
                val generation = directory(key, NormalizationDirectory.GENERATION)
                val gen = checkedDirectory(generation, role != NormalizationRole.PRIMARY, rootStat.device)
                if (gen != null) children(key, generation, setOf("raw.bin", "output.mp4"))
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
                check()
                val stat = io.lstat(path(key, role)) ?: continue
                if (!seen.add(stat.device to stat.inode)) fail()
            }
        }
        fun capture(key: NormalizationKey, role: NormalizationRole, maximum: Long = NORMALIZATION_MAX_BYTES,
            empty: Boolean = false): NormalizationFileIdentity = call(key) {
            readable(role)
            val parents = parents(key, role)
            val stat = io.lstat(path(key, role)) ?: fail()
            regular(stat, parents.first().second.device, maximum, empty)
            rejectAliases(key)
            NormalizationFileIdentity(stat, parents)
        }
        fun validate(key: NormalizationKey, role: NormalizationRole, identity: NormalizationFileIdentity,
            descriptor: Descriptor? = null, expected: NormalizationDescriptorIo.Stat = identity.stat): Unit = call(key) {
            readable(role)
            val currentParents = parents(key, role)
            if (currentParents.size != identity.parents.size || currentParents.zip(identity.parents).any { (a, b) -> a.first != b.first || !a.second.sameInode(b.second) }) fail()
            val leaf = io.lstat(path(key, role)) ?: fail()
            if (leaf != expected || !leaf.sameInode(identity.stat) || leaf.links != 1L || leaf.kind != NormalizationDescriptorIo.Kind.REGULAR) fail()
            if (descriptor != null && io.fstat(descriptor.handle) != expected) fail()
            rejectAliases(key)
        }
        private fun acquire(key: NormalizationKey, path: File, mode: NormalizationDescriptorIo.Open,
            released: () -> Unit = {}): Descriptor {
            check()
            val obligation = beginResource(key, token)
            val handle = try { io.open(path, mode) } catch (rejected: NormalizationOpenRejected) {
                constructed(key)
                releasedResource(obligation)
                check()
                throw rejected.failure
            } catch (failure: Throwable) {
                poison(failure, token)
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
            return synchronized(ownerLock) {
                Descriptor(key, handle, released, obligation, token).also { descriptors.add(it); constructed(key) }
            }
        }
        fun open(key: NormalizationKey, role: NormalizationRole, mode: NormalizationDescriptorIo.Open,
            identity: NormalizationFileIdentity, released: () -> Unit = {}): Descriptor {
            var opened: Descriptor? = null
            try {
                return call(key) {
                    readable(role); check(); validate(key, role, identity)
                    if (mode !in setOf(NormalizationDescriptorIo.Open.READ, NormalizationDescriptorIo.Open.WRITE_EXISTING)) fail()
                    if (mode == NormalizationDescriptorIo.Open.WRITE_EXISTING) {
                        allow(NormalizationIoReservations.Stage.REMUX)
                        if (role != NormalizationRole.OUTPUT || identity.stat.size != 0L) fail()
                    }
                    val descriptor = acquire(key, path(key, role), mode, released).also { opened = it }
                    validate(key, role, identity, descriptor); check(); descriptor
                }
            } catch (failure: Throwable) {
                opened?.let { closeAfterFailure(it, failure) }
                throw failure
            }
        }
        /** Read-only original PRIMARY anchor; never reuses the synchronous thread-bound Descriptor. */
        fun openAsyncRaw(key: NormalizationKey, identity: NormalizationFileIdentity,
            attempt: OwnedDirectTranscodeAttempt, released: () -> Unit): AsyncRawDescriptor {
            var opened: AsyncRawDescriptor? = null
            try {
                return call(key) {
                    allow(NormalizationIoReservations.Stage.TRANSCODE)
                    val exact = token ?: fail()
                    validate(key, NormalizationRole.PRIMARY, identity)
                    val obligation = beginResource(key, exact) ?: fail()
                    val handle = try { io.open(path(key, NormalizationRole.PRIMARY), NormalizationDescriptorIo.Open.READ) }
                    catch (rejected: NormalizationOpenRejected) {
                        constructed(key); releasedResource(obligation); check(); throw rejected.failure
                    } catch (failure: Throwable) {
                        poison(failure, exact)
                        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    }
                    val descriptor = try { synchronized(ownerLock) {
                        AsyncRawDescriptor(key, handle, identity, obligation, exact, checkOperation, released, attempt)
                            .also { asyncDescriptors.add(it); constructed(key); opened = it }
                    } } catch (failure: Throwable) {
                        poison(handle, exact)
                        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    }
                    descriptor.verifyIdentity()
                    validate(key, NormalizationRole.PRIMARY, identity)
                    descriptor
                }
            } catch (failure: Throwable) {
                try { opened?.requestClose() } catch (_: Throwable) { /* Exact resource remains poisoned. */ }
                throw failure
            }
        }

        /** The prepared OUTPUT inode is opened once, without create or truncation. */
        fun openAsyncOutput(key: NormalizationKey, identity: NormalizationFileIdentity,
            attempt: OwnedDirectTranscodeAttempt, openingClaim: Any, released: () -> Unit): AsyncOutputDescriptor {
            var opened: AsyncOutputDescriptor? = null
            try {
                return call(key) {
                    allow(NormalizationIoReservations.Stage.TRANSCODE)
                    val exact = token ?: fail()
                    if (identity.stat.size != 0L || attempt.reservation !== exact || attempt.key != key) fail()
                    attempt.claimOutputOpen(this@AndroidNormalizationFiles, exact, identity, openingClaim)
                    synchronized(openingFailureLock) {
                        if (this.openingAttempt != null) fail()
                        this.openingAttempt = attempt; this.openingClaim = openingClaim
                    }
                    validate(key, NormalizationRole.OUTPUT, identity)
                    val obligation = beginResource(key, exact) ?: fail()
                    val handle = try { io.open(path(key, NormalizationRole.OUTPUT), NormalizationDescriptorIo.Open.WRITE_EXISTING) }
                    catch (rejected: NormalizationOpenRejected) {
                        constructed(key); releasedResource(obligation)
                        throw selectOpeningFailure(rejected.failure)
                    } catch (failure: Throwable) {
                        val mapped = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                        val chosen = try { selectOpeningFailure(mapped) } finally { poison(failure, exact) }
                        throw chosen
                    }
                    val descriptor = try { synchronized(ownerLock) {
                        AsyncOutputDescriptor(key, handle, identity, obligation, exact, attempt, released)
                            .also { asyncDescriptors.add(it); constructed(key); opened = it; openingDescriptor = it }
                    } } catch (failure: Throwable) {
                        val mapped = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                        val chosen = try { selectOpeningFailure(mapped) } finally { poison(handle, exact) }
                        throw chosen
                    }
                    // Before publication only the original operation may inspect this anchor.
                    check(); if (io.fstat(handle) != identity.stat) fail(); check()
                    validate(key, NormalizationRole.OUTPUT, identity)
                    check(); descriptor
                }
            } catch (failure: Throwable) {
                val chosen = selectOpeningFailure(failure)
                try { opened?.requestClose() } catch (_: Throwable) { /* Retain exact uncertainty. */ }
                throw chosen
            }
        }

        fun fingerprint(key: NormalizationKey, role: NormalizationRole, sync: Boolean = false): NormalizationBoundFile = call(key) {
            val identity = capture(key, role, empty = true)
            val descriptor = open(key, role, NormalizationDescriptorIo.Open.READ, identity)
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
        override fun snapshot(key: NormalizationKey): NormalizationFilesSnapshot = call(key) {
            if (token != null && token.key == null) fail()
            requireReleased(key); parents(key, NormalizationRole.PRIMARY)
            val workspace = io.lstat(directory(key, NormalizationDirectory.WORKSPACE)) != null
            val generation = workspace && io.lstat(directory(key, NormalizationDirectory.GENERATION)) != null
            fun hash(role: NormalizationRole): NormalizationFingerprint? = if (io.lstat(path(key, role)) == null) null else fingerprint(key, role).fingerprint
            NormalizationFilesSnapshot(workspace, generation, hash(NormalizationRole.PRIMARY),
                if (generation) hash(NormalizationRole.RAW) else null, if (generation) hash(NormalizationRole.OUTPUT) else null)
        }
        override fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.PREPARE)
            require(directory != NormalizationDirectory.STAGING); snapshot(key)
            val path = directory(key, directory)
            if (io.lstat(path) != null) fail()
            check(); io.mkdir(path); check()
            checkedDirectory(path, true, checkedDirectory(root, true)!!.device)
            Unit
        }
        override fun createEmptyOutput(key: NormalizationKey): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.PREPARE)
            if (!snapshot(key).generationExists) fail()
            val parents = parents(key, NormalizationRole.OUTPUT)
            val path = path(key, NormalizationRole.OUTPUT)
            if (io.lstat(path) != null) fail()
            val descriptor = acquire(key, path, NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE)
            try {
                val identity = capture(key, NormalizationRole.OUTPUT, empty = true)
                if (identity.stat.size != 0L || identity.parents.zip(parents).any { (a, b) -> !a.second.sameInode(b.second) }) fail()
                validate(key, NormalizationRole.OUTPUT, identity, descriptor)
                descriptor.call { check(); io.fsync(descriptor.handle); check() }
            } catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
            descriptor.close()
            if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        override fun syncOutput(key: NormalizationKey): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.PREPARE, NormalizationIoReservations.Stage.FINISH)
            requireReleased(key); fingerprint(key, NormalizationRole.OUTPUT, sync = true); Unit
        }
        override fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.PREPARE, NormalizationIoReservations.Stage.FINISH,
                NormalizationIoReservations.Stage.ADOPT, NormalizationIoReservations.Stage.CLEANUP)
            requireReleased(key); parents(key, NormalizationRole.PRIMARY)
            val path = directory(key, directory)
            val expected = checkedDirectory(path, true, checkedDirectory(root, true)!!.device)!!
            val descriptor = acquire(key, path, NormalizationDescriptorIo.Open.DIRECTORY)
            try {
                descriptor.call {
                    if (!io.fstat(descriptor.handle).sameInode(expected)) fail()
                    check(); io.fsync(descriptor.handle); check()
                    if (!io.fstat(descriptor.handle).sameInode(expected) || checkedDirectory(path, true)?.sameInode(expected) != true) fail()
                    parents(key, NormalizationRole.PRIMARY)
                }
            } catch (failure: Throwable) { closeAfterFailure(descriptor, failure) }
            descriptor.close()
            if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        override fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole,
            expected: NormalizationFingerprint): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.ADOPT)
            require((source == NormalizationRole.PRIMARY && destination == NormalizationRole.RAW) ||
                (source == NormalizationRole.OUTPUT && destination == NormalizationRole.PRIMARY))
            val snapshot = snapshot(key)
            if (snapshot[source] != expected || snapshot[destination] != null) fail()
            val original = capture(key, source, empty = true)
            parents(key, destination); validate(key, source, original)
            if (io.lstat(path(key, destination)) != null) fail()
            check(); durable.promoteNoReplace(path(key, source), path(key, destination)); check()
            val moved = capture(key, destination, empty = true)
            if (!original.stat.sameInode(moved.stat) || io.lstat(path(key, source)) != null || fingerprint(key, destination).fingerprint != expected) fail()
        }
        override fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.CLEANUP)
            requireReleased(key)
            val observed = fingerprint(key, role)
            if (observed.fingerprint != expected) fail()
            validate(key, role, observed.identity)
            check(); io.unlink(path(key, role)); check()
            if (io.lstat(path(key, role)) != null) fail()
        }
        override fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory): Unit = call(key) {
            allow(NormalizationIoReservations.Stage.CLEANUP)
            require(directory != NormalizationDirectory.STAGING); snapshot(key)
            val path = directory(key, directory)
            checkedDirectory(path, true)
            if (enumerate(key, path, 0).isNotEmpty()) fail()
            check(); io.rmdir(path); check()
            if (io.lstat(path) != null) fail()
            synchronized(ownerLock) { directoryIdentities.remove(path) }
            Unit
        }
    }
    private fun closeAfterFailure(descriptor: Descriptor, failure: Throwable): Nothing {
        try { descriptor.close() } catch (_: Exception) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
        if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        throw failure
    }

    /** One exact registry and obligation lifetime for both asynchronous anchors. */
    internal abstract inner class AsyncAnchor internal constructor(
        val key: NormalizationKey,
        protected val handle: NormalizationDescriptorIo.Handle,
        protected val identity: NormalizationFileIdentity,
        protected val obligation: NormalizationIoReservations.Obligation,
        protected val token: NormalizationIoReservations.Token,
        private val released: () -> Unit,
        private val failureOwner: OwnedDirectTranscodeAttempt? = null,
    ) {
        private val failureLock = Any()
        private var pendingBoundaryFailure: Throwable? = null
        private var selectedBoundaryFailure: Throwable? = null
        private var failureThread: Thread? = null
        internal fun pendingFailureFor(attempt: OwnedDirectTranscodeAttempt): Throwable? = synchronized(failureLock) {
            if (failureOwner !== attempt || failureThread !== Thread.currentThread()) null else pendingBoundaryFailure
        }
        internal fun selectedFailureFor(attempt: OwnedDirectTranscodeAttempt): Throwable? = synchronized(failureLock) {
            if (failureOwner !== attempt) null else selectedBoundaryFailure
        }
        protected fun selectBeforeInvalidation(failure: Throwable): Throwable = synchronized(failureLock) {
            if (selectedBoundaryFailure != null) return@synchronized failure
            pendingBoundaryFailure = failure; failureThread = Thread.currentThread()
            val chosen = try { failureOwner?.captureDescriptorFailure(this) ?: failure }
            catch (_: Throwable) { failure } // Observation cannot bypass a required uncertainty fence.
            finally { pendingBoundaryFailure = null; failureThread = null }
            selectedBoundaryFailure = chosen
            chosen
        }
        private val descriptorLock = Any()
        private var calls = 0
        private var closeRequested = false
        private var closeAttempted = false
        @Volatile var releaseConfirmed = false; private set
        protected fun requireMember() = synchronized(ownerLock) {
            if (!asyncDescriptors.contains(this) || releaseConfirmed) fail()
        }
        protected fun live(check: () -> Unit) {
            check(); requireAvailable(); token.requireLive(); requireMember()
        }
        protected fun <T> call(check: () -> Unit, cleanup: Boolean = false, block: () -> T): T {
            check(); requireMember()
            synchronized(descriptorLock) {
                if (closeRequested || closeAttempted || releaseConfirmed) fail()
                calls++
            }
            activeCalls.incrementAndGet()
            var primary: Throwable? = null
            try {
                val action = { check(); block().also { check() } }
                return if (cleanup) {
                    val output = this as? AsyncOutputDescriptor ?: fail()
                    reservations.withTranscodeFinalizationIo(output, obligation, action)
                } else reservations.withIo(token, action)
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                activeCalls.decrementAndGet()
                val closeNow = synchronized(descriptorLock) {
                    calls--
                    (closeRequested && calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
                }
                if (closeNow) try { closeUnderlying() }
                catch (cleanupFailure: Throwable) { if (primary == null) throw cleanupFailure else primary.addSuppressed(cleanupFailure) }
            }
        }
        protected fun markUncertain() = poison(this, token)
        /** Release-only, nonblocking, and idempotent. Never probes after a throwing close. */
        fun requestClose() {
            synchronized(ownerLock) {
                if (releaseConfirmed) return
                if (!asyncDescriptors.contains(this)) fail()
            }
            val closeNow = synchronized(descriptorLock) {
                if (releaseConfirmed) return
                if (!closeRequested) { closeRequested = true; closing.incrementAndGet() }
                (calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
            }
            if (closeNow) closeUnderlying()
        }
        private fun closeUnderlying() {
            try {
                io.close(handle)
                if (io.valid(handle)) throw IOException("NORMALIZATION_CLOSE_UNCONFIRMED")
                synchronized(ownerLock) {
                    if (!asyncDescriptors.remove(this)) fail()
                    releaseConfirmed = true; closing.decrementAndGet()
                }
                releasedResource(obligation)
            } catch (failure: Throwable) {
                val mapped = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                val chosen = try { selectBeforeInvalidation(mapped) } finally { poison(this, token) }
                throw chosen
            }
            released()
        }
        /** All path checks use captured parent identities, with no directory enumeration. */
        protected fun stat(role: NormalizationRole, expected: NormalizationDescriptorIo.Stat,
            check: () -> Unit, event: () -> Unit, read: (() -> NormalizationDescriptorIo.Stat?) -> NormalizationDescriptorIo.Stat?) {
            fun observe(block: () -> NormalizationDescriptorIo.Stat?): NormalizationDescriptorIo.Stat? {
                check(); event(); return read(block).also { check() }
            }
            for ((parent, captured) in identity.parents) {
                val current = observe { io.lstat(parent) } ?: fail()
                if (current.kind != NormalizationDescriptorIo.Kind.DIRECTORY || !current.sameInode(captured)) fail()
            }
            val leaf = observe { io.lstat(path(key, role)) } ?: fail()
            if (leaf != expected || !leaf.sameInode(identity.stat) || leaf.links != 1L ||
                leaf.kind != NormalizationDescriptorIo.Kind.REGULAR) fail()
            if (observe { io.fstat(handle) } != expected) fail()
            val seen = mutableSetOf<Pair<Long, Long>>()
            for (candidate in NormalizationRole.entries) {
                val value = observe { io.lstat(path(key, candidate)) } ?: continue
                if (!seen.add(value.device to value.inode)) fail()
            }
            check()
        }
    }

    /** Original PRIMARY read surface retains its prior liveness and poison semantics. */
    internal inner class AsyncRawDescriptor internal constructor(
        key: NormalizationKey, handle: NormalizationDescriptorIo.Handle, identity: NormalizationFileIdentity,
        obligation: NormalizationIoReservations.Obligation, token: NormalizationIoReservations.Token,
        private val memoryCheck: () -> Unit, released: () -> Unit,
        failureOwner: OwnedDirectTranscodeAttempt? = null,
    ) : AsyncAnchor(key, handle, identity, obligation, token, released, failureOwner) {
        private fun live() = live(memoryCheck)
        private fun <T> syscall(block: () -> T): T = try { block() }
            catch (failure: Throwable) {
                val chosen = try { selectBeforeInvalidation(failure) } finally { markUncertain() }
                throw chosen
            }
        private fun stat() = stat(NormalizationRole.PRIMARY, identity.stat, ::live, {}, { syscall(it) })
        fun verifyIdentity() = call(::live) { stat() }
        fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int = call(::live) {
            if (position < 0 || position > identity.stat.size || length <= 0 || length > CHUNK ||
                length.toLong() > identity.stat.size - position || offset < 0 || offset > target.size || length > target.size - offset) fail()
            stat(); live()
            val count = syscall { io.pread(handle, target, offset, length, position) }
            if (count <= 0 || count > length) throw IOException("NORMALIZATION_READ_PROGRESS")
            live(); stat(); count
        }
        fun verifySealing(attempt: OwnedDirectTranscodeAttempt) = call({ sealing(attempt) }) { sealStat(attempt) }
        private fun sealing(attempt: OwnedDirectTranscodeAttempt) {
            attempt.requireSealingAnchor(this); requireMember(); requireAvailable(); token.requireLive()
        }
        private fun sealStat(attempt: OwnedDirectTranscodeAttempt) = stat(NormalizationRole.PRIMARY, identity.stat,
            { sealing(attempt) }, { attempt.sealEvent(this) }, { syscall(it) })
        fun hashRetained(attempt: OwnedDirectTranscodeAttempt): NormalizationBoundFile = call({ sealing(attempt) }) {
            attempt.sealEvent(this); sealStat(attempt)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK); var position = 0L
            while (position < identity.stat.size) {
                val requested = minOf(CHUNK.toLong(), identity.stat.size - position).toInt()
                attempt.sealRequest(this, requested.toLong()); sealStat(attempt)
                attempt.sealPhysical(this, requested.toLong()); attempt.sealEvent(this); sealing(attempt)
                val actual = syscall { io.pread(handle, buffer, 0, requested, position) }
                sealing(attempt)
                if (actual <= 0 || actual > requested) throw IOException("NORMALIZATION_READ_PROGRESS")
                digest.update(buffer, 0, actual); position += actual; sealStat(attempt)
            }
            sealStat(attempt)
            NormalizationBoundFile(identity, NormalizationFingerprint(identity.stat.size, digest.digest().joinToString("") { "%02x".format(it) }))
        }
    }

    /** One retained O_RDWR OUTPUT; all cursor, stat, mutation and hash work shares ioLock. */
    internal inner class AsyncOutputDescriptor internal constructor(
        key: NormalizationKey, handle: NormalizationDescriptorIo.Handle, identity: NormalizationFileIdentity,
        obligation: NormalizationIoReservations.Obligation, token: NormalizationIoReservations.Token,
        private val attempt: OwnedDirectTranscodeAttempt, released: () -> Unit,
    ) : AsyncAnchor(key, handle, identity, obligation, token, released, attempt) {
        private val ioLock = Any()
        private var expected = identity.stat
        private var position = 0L
        private var mutationGeneration = 0L
        private var forcedGeneration = -1L
        private var acknowledgedMutationSize: Long? = null
        @Volatile private var uncertain = false
        /** Called outside the reservation monitor; authentic RAW/foreign obligations cannot pass. */
        internal fun requireFinalizationObligation(candidate: NormalizationIoReservations.Obligation) {
            requireMember()
            if (candidate !== obligation || candidate.token !== token || uncertain) fail()
            requireAvailable(); attempt.requireOutputAccess(this, OwnedDirectTranscodeAttempt.OutputPhase.FINALIZING)
        }
        internal fun markAcquisitionUncertain() {
            requireMember(); uncertain = true; attempt.outputUncertain(this); markUncertain()
        }
        private fun check(phase: OwnedDirectTranscodeAttempt.OutputPhase) {
            requireMember(); requireAvailable()
            if (uncertain) fail()
            attempt.requireOutputAccess(this, phase)
            if (phase != OwnedDirectTranscodeAttempt.OutputPhase.FINALIZING) token.requireLive()
        }
        private fun event(phase: OwnedDirectTranscodeAttempt.OutputPhase) = attempt.outputEvent(this, phase)
        private fun <T> observed(phase: OwnedDirectTranscodeAttempt.OutputPhase, block: () -> T): T {
            check(phase); event(phase)
            return block().also { check(phase) }
        }
        private fun stat(phase: OwnedDirectTranscodeAttempt.OutputPhase) {
            // A positive mutation may race stop before its metadata acknowledgement. Only the
            // exact pinned cleanup phase may validate that known result; unknown progress never enters here.
            acknowledgedMutationSize?.let { size ->
                if (phase != OwnedDirectTranscodeAttempt.OutputPhase.FINALIZING) fail()
                refreshed(size, phase)
            }
            stat(NormalizationRole.OUTPUT, expected, { check(phase) }, { event(phase) }, { it() })
        }
        private fun <T> operation(phase: OwnedDirectTranscodeAttempt.OutputPhase, block: () -> T): T =
            call({ check(phase) }, phase == OwnedDirectTranscodeAttempt.OutputPhase.FINALIZING) {
                synchronized(ioLock) { check(phase); block().also { check(phase) } }
            }
        fun verifyIdentity(phase: OwnedDirectTranscodeAttempt.OutputPhase) = operation(phase) { stat(phase) }
        fun size(phase: OwnedDirectTranscodeAttempt.OutputPhase): Long = operation(phase) { stat(phase); expected.size }
        fun position(phase: OwnedDirectTranscodeAttempt.OutputPhase): Long = operation(phase) { stat(phase); position }
        fun available(phase: OwnedDirectTranscodeAttempt.OutputPhase): Long = operation(phase) {
            stat(phase); observed(phase) { freeBytes().coerceAtLeast(0) }
        }
        fun seek(value: Long, phase: OwnedDirectTranscodeAttempt.OutputPhase) = operation(phase) {
            attempt.outputRange(value, 0); stat(phase); position = value
        }
        private fun mutation(phase: OwnedDirectTranscodeAttempt.OutputPhase, block: () -> Unit) {
            check(phase); event(phase)
            try { block() } catch (failure: Throwable) {
                // Entered mutation has unknown progress. No subsequent metadata/force/hash is safe.
                val chosen = try { selectBeforeInvalidation(failure) } finally {
                    uncertain = true
                    try { attempt.outputUncertain(this) } finally { markUncertain() }
                }
                throw chosen
            }
        }
        private fun refreshed(size: Long, phase: OwnedDirectTranscodeAttempt.OutputPhase) {
            val next = observed(phase) { io.fstat(handle) }
            if (!next.sameInode(identity.stat) || next.kind != NormalizationDescriptorIo.Kind.REGULAR ||
                next.links != 1L || next.size != size || size !in 0..attempt.outputMaximum) fail()
            // Validate against the new expected snapshot before publishing it.
            stat(NormalizationRole.OUTPUT, next, { check(phase) }, { event(phase) }, { it() })
            expected = next; acknowledgedMutationSize = null; mutationGeneration = Math.addExact(mutationGeneration, 1L)
        }
        fun write(source: ByteBuffer, phase: OwnedDirectTranscodeAttempt.OutputPhase): Int = operation(phase) {
            val requested = source.remaining()
            attempt.outputRange(position, requested.toLong())
            if (requested == 0) return@operation 0
            stat(phase)
            val staging = ByteArray(minOf(CHUNK, requested))
            while (source.hasRemaining()) {
                check(phase)
                val count = minOf(CHUNK, source.remaining())
                val nextSize = maxOf(expected.size, Math.addExact(position, count.toLong()))
                val headroom = maxOf(nextSize - expected.size, count.toLong())
                if (observed(phase) { freeBytes().coerceAtLeast(0) } < headroom) throw SaveFailure(SaveError.STORAGE_FULL)
                source.duplicate().get(staging, 0, count)
                attempt.outputPhysical(this, phase, count.toLong())
                var actual = 0
                mutation(phase) { actual = io.pwrite(handle, staging, 0, count, position) }
                if (actual < 0 || actual > count) {
                    val failure = IOException("NORMALIZATION_WRITE_PROGRESS")
                    val chosen = try { selectBeforeInvalidation(failure) } finally {
                        uncertain = true
                        try { attempt.outputUncertain(this) } finally { markUncertain() }
                    }
                    throw chosen
                }
                if (actual == 0) throw IOException("NORMALIZATION_WRITE_PROGRESS")
                // Only positive acknowledgements advance the caller's buffer, including on later failure.
                position += actual; source.position(source.position() + actual)
                val acknowledgedSize = maxOf(expected.size, position)
                acknowledgedMutationSize = acknowledgedSize
                check(phase); refreshed(acknowledgedSize, phase)
            }
            requested
        }
        fun truncate(value: Long, phase: OwnedDirectTranscodeAttempt.OutputPhase) = operation(phase) {
            attempt.outputRange(value, 0); stat(phase)
            if (value < expected.size) {
                mutation(phase) { io.truncate(handle, value) }
                if (position > value) position = value
                acknowledgedMutationSize = value
                check(phase); refreshed(value, phase)
            }
        }
        fun force(phase: OwnedDirectTranscodeAttempt.OutputPhase) = operation(phase) {
            stat(phase)
            // A force failure invalidates content, but is not uncertainty about write or close.
            observed(phase) { io.fsync(handle) }; stat(phase); forcedGeneration = mutationGeneration
        }
        fun requireForced() = synchronized(ioLock) {
            if (uncertain || mutationGeneration != forcedGeneration || expected.size !in 1..attempt.outputMaximum) fail()
        }
        fun verifySealing() = operation(OwnedDirectTranscodeAttempt.OutputPhase.SEALING) { stat(OwnedDirectTranscodeAttempt.OutputPhase.SEALING) }
        fun hashRetained(): NormalizationBoundFile = operation(OwnedDirectTranscodeAttempt.OutputPhase.SEALING) {
            val phase = OwnedDirectTranscodeAttempt.OutputPhase.SEALING
            requireForced(); event(phase); stat(phase)
            val frozen = expected
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(CHUNK); var offset = 0L
            while (offset < frozen.size) {
                val requested = minOf(CHUNK.toLong(), frozen.size - offset).toInt()
                attempt.sealRequest(this, requested.toLong()); stat(phase)
                attempt.sealPhysical(this, requested.toLong())
                val actual = observed(phase) { io.pread(handle, buffer, 0, requested, offset) }
                if (actual <= 0 || actual > requested) throw IOException("NORMALIZATION_READ_PROGRESS")
                digest.update(buffer, 0, actual); offset += actual; stat(phase)
            }
            stat(phase)
            NormalizationBoundFile(NormalizationFileIdentity(frozen, identity.parents),
                NormalizationFingerprint(frozen.size, digest.digest().joinToString("") { "%02x".format(it) }))
        }
    }

    /** Only the opening worker performs close; unknown close is final, retained, and never retried. */
    internal inner class Descriptor internal constructor(
        val key: NormalizationKey, internal val handle: NormalizationDescriptorIo.Handle, private val released: () -> Unit,
        private val obligation: NormalizationIoReservations.Obligation?, private val token: NormalizationIoReservations.Token?,
    ) : AutoCloseable {
        private val thread = Thread.currentThread()
        private val descriptorLock = Any()
        private var calls = 0
        @Volatile internal var closeRequested = false; private set
        private var closeAttempted = false
        @Volatile var releaseConfirmed = false; private set
        internal fun <T> call(block: () -> T): T {
            synchronized(descriptorLock) {
                requireAvailable()
                if (Thread.currentThread() !== thread || closeRequested || calls != 0) fail()
                calls++
            }
            try { return block() }
            finally {
                val closeNow = synchronized(descriptorLock) {
                    calls--
                    (closeRequested && calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
                }
                if (closeNow) closeUnderlying()
            }
        }
        override fun close() {
            val closeNow = synchronized(descriptorLock) {
                if (releaseConfirmed) return
                if (!closeRequested) { closeRequested = true; closing.incrementAndGet() }
                (Thread.currentThread() === thread && calls == 0 && !closeAttempted).also { if (it) closeAttempted = true }
            }
            if (closeNow) closeUnderlying()
            else if (!releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        private fun closeUnderlying() {
            try {
                io.close(handle)
                if (io.valid(handle)) throw IOException("NORMALIZATION_CLOSE_UNCONFIRMED")
                synchronized(ownerLock) { releaseConfirmed = true; descriptors.remove(this); closing.decrementAndGet() }
                releasedResource(obligation)
            } catch (failure: Throwable) {
                poison(failure, token)
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
