package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.MovRemuxEvidence
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic host syscall fixture, not Android filesystem/FD/SQLite acceptance.
 * Tests use the production descriptor adapter and ownership state machines.
 */
internal class MemoryNormalizationDescriptorIo : NormalizationDescriptorIo {
    data class Node(val inode: Long, var kind: NormalizationDescriptorIo.Kind,
        var bytes: ByteArray = byteArrayOf(), var device: Long = 1, var links: Long = 1,
        var modified: Long = 1, var changed: Long = 1)
    class Descriptor(val path: File, val node: Node, val mode: NormalizationDescriptorIo.Open) : NormalizationDescriptorIo.Handle {
        @Volatile var active = true
    }
    data class Call(val operation: String, val path: File, val descriptor: Descriptor? = null,
        val mode: NormalizationDescriptorIo.Open? = null, val count: Int = 0, val position: Long = 0,
        val thread: Thread = Thread.currentThread())
    enum class CloseMode { NORMAL, RETURNS_VALID, INVALIDATES_THEN_THROWS, THROWS_VALID }
    private val nodes = linkedMapOf<String, Node>()
    private var nextInode = 1L
    val calls = CopyOnWriteArrayList<Call>()
    val handles = CopyOnWriteArrayList<Descriptor>()
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    var closeMode: (Descriptor) -> CloseMode = { CloseMode.NORMAL }
    var maxRead = Int.MAX_VALUE
    var maxWrite = Int.MAX_VALUE
    var readResult: Int? = null
    var writeResult: Int? = null
    var listingReadFailure = false
    var listingCloseFailure = false
    private fun name(path: File) = path.absoluteFile.toPath().normalize().toString()
    fun node(path: File): Node = nodes[name(path)] ?: throw IOException("HOST_MISSING")
    fun exists(path: File) = nodes.containsKey(name(path))
    fun directory(path: File) {
        if (exists(path)) return
        path.parentFile?.let(::directory)
        nodes[name(path)] = Node(nextInode++, NormalizationDescriptorIo.Kind.DIRECTORY)
    }
    fun file(path: File, bytes: ByteArray) {
        directory(path.parentFile!!)
        nodes[name(path)] = Node(nextInode++, NormalizationDescriptorIo.Kind.REGULAR, bytes.copyOf())
    }
    fun other(path: File) { directory(path.parentFile!!); nodes[name(path)] = Node(nextInode++, NormalizationDescriptorIo.Kind.OTHER) }
    fun remove(path: File) { nodes.remove(name(path)) }
    fun replaceDirectory(path: File) { nodes[name(path)] = Node(nextInode++, NormalizationDescriptorIo.Kind.DIRECTORY) }
    fun alias(source: File, destination: File, reportedLinks: Long = 2) {
        val value = node(source); value.links = reportedLinks; nodes[name(destination)] = value
    }
    fun mutate(path: File, bytes: ByteArray, preserveTimes: Boolean = false) {
        node(path).also { it.bytes = bytes.copyOf(); if (!preserveTimes) { it.modified++; it.changed++ } }
    }
    fun bytes(path: File) = node(path).bytes.copyOf()
    fun move(source: File, destination: File) {
        check(!exists(destination)) { "HOST_REPLACE_FORBIDDEN" }
        val value = node(source); check(value.device == node(destination.parentFile!!).device)
        nodes.remove(name(source)); nodes[name(destination)] = value
    }
    private fun stat(node: Node) = NormalizationDescriptorIo.Stat(node.device, node.inode, node.kind, node.links,
        node.bytes.size.toLong(), 0, node.modified, 0, node.changed)
    private fun descriptor(handle: NormalizationDescriptorIo.Handle): Descriptor = (handle as Descriptor).also { check(it.active) { "HOST_CLOSED" } }
    private inline fun <T> call(value: Call, body: () -> T): T {
        calls += value; before(value); val result = body(); after(value); return result
    }
    override fun lstat(path: File): NormalizationDescriptorIo.Stat? = call(Call("lstat", path)) { nodes[name(path)]?.let(::stat) }
    override fun open(path: File, mode: NormalizationDescriptorIo.Open): NormalizationDescriptorIo.Handle = call(Call("open", path, mode = mode)) {
        if (mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE) {
            if (exists(path)) throw IOException("HOST_EXISTS")
            check(node(path.parentFile!!).kind == NormalizationDescriptorIo.Kind.DIRECTORY); file(path, byteArrayOf())
        }
        val value = node(path)
        if (value.kind == NormalizationDescriptorIo.Kind.OTHER) throw IOException("HOST_NOFOLLOW")
        if (mode == NormalizationDescriptorIo.Open.DIRECTORY && value.kind != NormalizationDescriptorIo.Kind.DIRECTORY) throw IOException("HOST_NOT_DIRECTORY")
        Descriptor(path, value, mode).also { handles += it }
    }
    override fun fstat(handle: NormalizationDescriptorIo.Handle): NormalizationDescriptorIo.Stat {
        val fd = descriptor(handle); return call(Call("fstat", fd.path, fd)) { stat(fd.node) }
    }
    override fun pread(handle: NormalizationDescriptorIo.Handle, bytes: ByteArray, offset: Int, count: Int, position: Long): Int {
        val fd = descriptor(handle)
        return call(Call("pread", fd.path, fd, count = count, position = position)) {
            check(fd.mode == NormalizationDescriptorIo.Open.READ || fd.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING)
            val result = readResult ?: minOf(count, maxRead, maxOf(0L, fd.node.bytes.size - position).toInt())
            if (result in 1..count && position + result <= fd.node.bytes.size) fd.node.bytes.copyInto(bytes, offset, position.toInt(), position.toInt() + result)
            result
        }
    }
    override fun pwrite(handle: NormalizationDescriptorIo.Handle, bytes: ByteArray, offset: Int, count: Int, position: Long): Int {
        val fd = descriptor(handle)
        return call(Call("pwrite", fd.path, fd, count = count, position = position)) {
            check(fd.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING || fd.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE)
            val result = writeResult ?: minOf(count, maxWrite)
            if (result in 1..count) {
                check(position >= 0 && position + result <= 4 * 1024 * 1024) { "HOST_FIXTURE_ALLOCATION_BOUND" }
                if (fd.node.bytes.size < position + result) fd.node.bytes = fd.node.bytes.copyOf((position + result).toInt())
                bytes.copyInto(fd.node.bytes, position.toInt(), offset, offset + result); fd.node.modified++; fd.node.changed++
            }
            result
        }
    }
    override fun truncate(handle: NormalizationDescriptorIo.Handle, size: Long) {
        val fd = descriptor(handle)
        call(Call("truncate", fd.path, fd, position = size)) {
            check(fd.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING); check(size in 0..4L * 1024 * 1024)
            fd.node.bytes = fd.node.bytes.copyOf(size.toInt()); fd.node.modified++; fd.node.changed++
        }
    }
    override fun fsync(handle: NormalizationDescriptorIo.Handle) {
        val fd = descriptor(handle); call(Call("fsync", fd.path, fd)) { Unit }
    }
    override fun close(handle: NormalizationDescriptorIo.Handle) {
        val fd = handle as Descriptor
        call(Call("close", fd.path, fd)) {
            check(fd.active) { "HOST_CLOSE_RETRIED" }
            when (closeMode(fd)) {
                CloseMode.NORMAL -> fd.active = false
                CloseMode.RETURNS_VALID -> Unit
                CloseMode.INVALIDATES_THEN_THROWS -> { fd.active = false; throw IOException("HOST_CLOSE_UNCONFIRMED") }
                CloseMode.THROWS_VALID -> throw IOException("HOST_CLOSE_UNCONFIRMED")
            }
        }
    }
    override fun valid(handle: NormalizationDescriptorIo.Handle) = (handle as Descriptor).active
    override fun mkdir(path: File) = call(Call("mkdir", path)) {
        if (exists(path)) throw IOException("HOST_EXISTS")
        check(node(path.parentFile!!).kind == NormalizationDescriptorIo.Kind.DIRECTORY); directory(path)
    }
    override fun unlink(path: File) = call(Call("unlink", path)) { check(node(path).kind != NormalizationDescriptorIo.Kind.DIRECTORY); remove(path) }
    override fun rmdir(path: File) = call(Call("rmdir", path)) {
        check(nodes.keys.none { File(it).parentFile?.let(::name) == name(path) })
        check(node(path).kind == NormalizationDescriptorIo.Kind.DIRECTORY); remove(path)
    }
    override fun children(path: File, maximum: Int, closeStarted: () -> Unit, closeConfirmed: () -> Unit): List<String> = call(Call("children", path)) {
        check(node(path).kind == NormalizationDescriptorIo.Kind.DIRECTORY)
        val listingToken = Any()
        try {
            if (listingReadFailure) throw IOException("HOST_LISTING_READ")
            nodes.keys.asSequence().map(::File).filter { it.parentFile?.let(::name) == name(path) }.take(maximum + 1).map { it.name }.toList()
        } finally {
            closeStarted()
            call(Call("childrenClose", path)) {
                if (listingCloseFailure) throw NormalizationDirectoryReleaseUnconfirmed(listingToken)
            }
            closeConfirmed()
        }
    }
}

/** Existing typed ledger port only; no Android database behavior is fabricated. */
internal class NormalizationTestLedger : NormalizationLedger {
    var row: NormalizationWorkspace? = null
    var unreadable = false
    var afterWrite: (NormalizationWorkspace) -> Unit = {}
    override fun lookup(owner: NormalizationOwner): NormalizationLookup = if (unreadable) NormalizationLookup.Unreadable else
        row?.let { if (it.key.owner == owner) NormalizationLookup.Present(it) else NormalizationLookup.Unreadable } ?: NormalizationLookup.Absent
    override fun insert(row: NormalizationWorkspace): Boolean {
        if (this.row != null) return false
        this.row = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(row)); afterWrite(row); return true
    }
    override fun compareAndSet(expected: NormalizationWorkspace, next: NormalizationWorkspace): Boolean {
        expected.requireSuccessor(next); if (row != expected) return false
        row = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(next)); afterWrite(next); return true
    }
    override fun pending(after: Long, limit: Int): NormalizationPendingPage = if (unreadable) NormalizationPendingPage.Unreadable else
        NormalizationPendingPage.Readable(row?.takeIf { after == 0L && it.lifecycle != NormalizationLifecycle.CLEANED }
            ?.let { listOf(NormalizationPendingEntry(1, it)) } ?: emptyList(), null)
}

internal fun normalizationTestFingerprint(bytes: ByteArray) = NormalizationFingerprint(bytes.size.toLong(),
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

internal fun normalizationTestConservation(): DirectRemuxConservation {
    val hash = "a".repeat(64); val first = DirectSampleIdentity(0, 0, 8, hash, 1); val last = DirectSampleIdentity(1, 33_333, 8, hash, 0)
    val track = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, hash, 2, 16, hash, hash, hash, 0, last, DirectTrackEndpoint(66_666, 66_667))
    return DirectRemuxConservation(16, 16, 30, track, null, null, null, first)
}

internal class NormalizationDescriptorFixture(rawSize: Int = 8192, commitId: String = "commit") {
    val root = File("/owned-normalization-host-fixture")
    val owner = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", commitId)
    val knownKey = NormalizationKey(owner, "22222222-2222-4222-8222-222222222222")
    val staging = File(root, "staging")
    val primary = File(staging, owner.artifactId + ".part")
    val workspace = File(staging, owner.artifactId + ".normalization")
    val rawBytes = ByteArray(rawSize) { (it % 251).toByte() }
    val outputBytes = ByteArray(4096) { ((it + 11) % 251).toByte() }
    val raw = normalizationTestFingerprint(rawBytes)
    val output = normalizationTestFingerprint(outputBytes)
    val io = MemoryNormalizationDescriptorIo().apply { directory(staging); file(primary, rawBytes) }
    val ledger = NormalizationTestLedger()
    val lock = Any()
    val cancellation = CancellationSource()
    var context = OperationContext("normalization-test", 100_000, cancellation, resourceBudget = ResourceBudget(1, 4L * 1024 * 1024 * 1024))
    var limits = SaveLimits(NORMALIZATION_MAX_BYTES, 4L * 1024 * 1024 * 1024)
    var now = 0L
    var available = NORMALIZATION_MAX_BYTES
    var storageClosed = false
    var priorHandles = false
    var authorityUnreadable = false
    var record: SaveRecord? = SaveRecord(
        SaveRequest(owner.taskId, "key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/owned.mov")).asDurableReference()),
        TaskSnapshot(owner.taskId, 1, TaskState.RUNNING, TaskMethod.DIRECT), owner.commitId, context.operationId, context.deadlineEpochMillis, 0,
        phase = SavePhase.NORMALIZING, artifact = StagingArtifact(owner.artifactId, owner.commitId),
        transfer = TransferResult(owner.artifactId, rawBytes.size.toLong(), "video/quicktime", rawBytes.size.toLong()), bytesTransferred = rawBytes.size.toLong())
    val sourceDigest = sourceTransferFingerprint(record!!.transfer!!)
    var authority = NormalizationAuthority(owner, NormalizationTaskState.ACTIVE, NormalizationParentState.STAGING, sourceDigest, null)
    val durable = object : DurableFileOps {
        override fun syncDirectory(directory: File) { throw AssertionError("Descriptor directory barrier required") }
        override fun promoteNoReplace(source: File, destination: File) { io.move(source, destination) }
        override fun replaceOwnedStaging(source: File, destination: File) { throw AssertionError("Normalization must never replace staging") }
    }
    val files = AndroidNormalizationFiles(root, durable, lock, { available }, { storageClosed }, io)
    var transcodeScopeVerifier: (com.easysavevideo.app.save.AndroidDirectExecutionBridge.Scope) -> Unit = { throw NormalizationRecovery() }
    val attempts = NormalizationAttemptOwner(ledger, files, { if (authorityUnreadable) throw IOException("HOST_AUTHORITY"); authority },
        { id -> record?.takeIf { it.task.taskId == id } }, lock, { storageClosed },
        { if (priorHandles) throw NormalizationRecovery() }, ::checkOperation,
        requireTranscodeScope = { transcodeScopeVerifier(it) })
    fun checkOperation(operation: OperationContext) {
        when (operation.stopReason(now)) {
            OperationStopReason.CANCELLED -> throw SaveFailure(SaveError.CANCELLED)
            OperationStopReason.DEADLINE_EXCEEDED -> throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            OperationStopReason.RETRY_EXHAUSTED -> throw SaveFailure(SaveError.RETRY_EXHAUSTED)
            null -> if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        }
    }
    fun generation(key: NormalizationKey) = File(workspace, key.normalizationId)
    fun outputFile(key: NormalizationKey) = File(generation(key), "output.mp4")
    fun rawFile(key: NormalizationKey) = File(generation(key), "raw.bin")
    fun prepare() = attempts.prepare(owner, context, limits)
    fun seedWriting(output: ByteArray? = byteArrayOf()): NormalizationKey {
        val key = knownKey; io.directory(generation(key)); output?.let { io.file(outputFile(key), it) }
        ledger.row = NormalizationWorkspace(key, 2, NormalizationLifecycle.WRITING, NormalizationDisposition.CONTINUE, raw, sourceDigest)
        return key
    }
    fun evidence() = MovRemuxEvidence(raw.size, raw.sha256, output.size, output.sha256, normalizationTestConservation())
}
