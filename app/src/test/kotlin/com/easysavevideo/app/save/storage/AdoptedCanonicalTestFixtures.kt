package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real host temporary files and real read-only RAFs. Metadata and execution/batch authority are
 * explicitly synthetic. This fixture proves neither Android syscall behavior nor a real live bridge.
 */
internal class AdoptedCanonicalTestFixture(deadlineMillis: Long = 100_000) : AutoCloseable {
    val root = Files.createTempDirectory("esv-adopted-owner-").toFile()
    val ownerKey = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", "commit")
    val key = NormalizationKey(ownerKey, "22222222-2222-4222-8222-222222222222")
    val artifact = StagingArtifact(ownerKey.artifactId, ownerKey.commitId)
    val staging = File(root, "staging")
    val primary = File(staging, artifact.artifactId + ".part")
    val workspace = File(staging, artifact.artifactId + ".normalization")
    val generation = File(workspace, key.normalizationId)
    val rawFile = File(generation, "raw.bin")
    val outputFile = File(generation, "output.mp4")
    val rawBytes = ByteArray(8192) { (it % 251).toByte() }
    val outputBytes = ByteArray(4096) { ((it + 11) % 251).toByte() }
    val raw = normalizationTestFingerprint(rawBytes)
    val canonical = normalizationTestFingerprint(outputBytes)
    val lock = Any()
    val cancellation = CancellationSource()
    val context = OperationContext("adopted-test", deadlineMillis, cancellation,
        resourceBudget = ResourceBudget(1, NORMALIZATION_MAX_BYTES))
    val gate = DirectLocalProcessingGate(EpochClock { 0 })
    val resource = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/owned.mov")).asDurableReference()
    val transfer = TransferResult(artifact.artifactId, raw.size, "video/quicktime", raw.size)
    val proof = DirectNormalizationProof(key.normalizationId, ownerKey.taskId, resource.sourceIdentity,
        artifact.artifactId, artifact.commitId, raw.size, raw.sha256, sourceTransferFingerprint(transfer),
        canonical.size, canonical.sha256, normalizationTestConservation())
    val output = ArtifactOutputDescriptor.NormalizedDirect(transfer, proof)
    val convertedTransfer = transfer.copy(responseMimeType = "video/webm")
    val conversionExpectation = DirectConversionExpectation(
        DirectConversionVideoExpectation(16, 16, 25, 120_000, 3, 0, 80_000, 0), null)
    val conversionProof = DirectConversionProof(key.normalizationId, ownerKey.taskId, resource.sourceIdentity,
        artifact.artifactId, artifact.commitId, raw.size, raw.sha256, sourceTransferFingerprint(convertedTransfer),
        canonical.size, canonical.sha256, conversionExpectation, DirectConversionEncodingSettings.forExpectation(conversionExpectation),
        DirectConversionCompletionFacts(true, true, true, true, true, true, null, null, null, null, null, null))
    val convertedOutput = ArtifactOutputDescriptor.ConvertedDirect(convertedTransfer, conversionProof)
    val revision = 12L
    @Volatile var record: SaveRecord? = SaveRecord(SaveRequest(ownerKey.taskId, "request-key", resource),
        TaskSnapshot(ownerKey.taskId, revision, TaskState.RUNNING, TaskMethod.DIRECT), artifact.commitId,
        context.operationId, context.deadlineEpochMillis, context.retryBudget, context.attempt,
        phase = SavePhase.VERIFYING, artifact = artifact, transfer = transfer,
        bytesTransferred = raw.size, directNormalization = proof)
    @Volatile var catalog: OwnedMedia? = OwnedMedia(ownerKey.taskId, artifact, "media", OwnedState.STAGING)
    val ledger = NormalizationTestLedger().apply {
        row = NormalizationWorkspace(key, 5, NormalizationLifecycle.ADOPTED,
            NormalizationDisposition.CONTINUE, raw, proof.sourceTransferSha256, canonical, proof.digest())
    }
    @Volatile var storageClosed = false
    @Volatile var priorHandles = false
    @Volatile var recordFailure = false
    @Volatile var catalogFailure = false
    @Volatile var now = 0L
    var authorityChecks = 0
    var authorityCheck: (AdoptedVerificationBindings.SyntheticRequest) -> Unit = {}
    val bindings = AdoptedVerificationBindings(gate, AdoptedVerificationBindings.SyntheticAuthority { request ->
        check(!Thread.holdsLock(lock)) { "SYNTHETIC_AUTHORITY_UNDER_STORAGE_LOCK" }
        check(request.artifact === artifact && (request.output === output || request.output === convertedOutput) && request.context === context &&
            request.expectedRecordRevision == revision) { "SYNTHETIC_REQUEST_NOT_GRANTED" }
        authorityChecks++
        authorityCheck(request)
    })
    val io = AdoptedCanonicalTestIo(lock)
    val owner: AdoptedCanonicalOutputOwner

    init {
        check(generation.mkdirs())
        primary.writeBytes(outputBytes)
        rawFile.writeBytes(rawBytes)
        for (directory in listOf(root, staging, workspace, generation)) io.directory(directory)
        io.file(primary, outputBytes.size.toLong())
        io.file(rawFile, rawBytes.size.toLong())
        owner = newOwner()
    }

    fun newOwner(registry: AdoptedVerificationBindings = bindings) = AdoptedCanonicalOutputOwner(root, registry,
        ledger, { id -> if (recordFailure) throw IOException("HOST_RECORD") else record?.takeIf { it.task.taskId == id } },
        { id -> if (catalogFailure) throw IOException("HOST_CATALOG") else catalog?.takeIf { it.artifact.commitId == id } },
        lock, { storageClosed }, { if (priorHandles) throw SaveFailure(SaveError.BUSY) }, ::checkOperation, io)

    fun checkOperation(operation: OperationContext) {
        when (operation.stopReason(now)) {
            OperationStopReason.CANCELLED -> throw SaveFailure(SaveError.CANCELLED)
            OperationStopReason.DEADLINE_EXCEEDED -> throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            OperationStopReason.RETRY_EXHAUSTED -> throw SaveFailure(SaveError.RETRY_EXHAUSTED)
            null -> if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        }
    }

    fun <T> bound(block: (AdoptedVerificationBindings.Binding) -> T): T = gate.withBoundPermit(Any(), context) { permit ->
        bindings.withSyntheticBinding(permit, artifact, output, context, revision, block)
    }

    fun useConverted() {
        record = record!!.copy(transfer = convertedTransfer, directNormalization = null, directConversion = conversionProof)
        ledger.row = ledger.row!!.copy(sourceTransferSha256 = conversionProof.sourceTransferSha256, proofSha256 = conversionProof.digest())
    }
    fun <T> boundConverted(block: (AdoptedVerificationBindings.Binding) -> T): T = gate.withBoundPermit(Any(), context) { permit ->
        bindings.withSyntheticBinding(permit, artifact, convertedOutput, context, revision, block)
    }

    /** Teardown closes host resources directly, never as evidence of production owner retirement. */
    override fun close() {
        io.dispose()
        check(root.deleteRecursively()) { "HOST_TEMP_CLEANUP" }
    }
}

internal class AdoptedCanonicalTestIo(private val ownerLock: Any) : AdoptedCanonicalRafIo {
    data class Call(val operation: String, val path: File? = null)
    enum class CloseMode { NORMAL, RETURNS_VALID, INVALIDATES_THEN_THROWS, THROWS_VALID }
    private val metadata = linkedMapOf<String, NormalizationDescriptorIo.Stat>()
    private val descriptors = IdentityHashMap<FileDescriptor, NormalizationDescriptorIo.Stat>()
    private val opened = IdentityHashMap<RandomAccessFile, File>()
    val calls = CopyOnWriteArrayList<Call>()
    val handles = CopyOnWriteArrayList<RandomAccessFile>()
    private val directories = CopyOnWriteArrayList<DirectoryStream<Path>>()
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    var closeMode = CloseMode.NORMAL
    var directoryCloseFailure = false
    var fstatTransform: (NormalizationDescriptorIo.Stat) -> NormalizationDescriptorIo.Stat = { it }
    private var nextInode = 1L
    private fun key(path: File) = path.absoluteFile.toPath().normalize().toString()
    fun stat(path: File): NormalizationDescriptorIo.Stat = metadata[key(path)] ?: throw IOException("HOST_MISSING")
    fun replaceStat(path: File, stat: NormalizationDescriptorIo.Stat) { metadata[key(path)] = stat }
    fun removeStat(path: File) { metadata.remove(key(path)) }
    fun directory(path: File) {
        metadata[key(path)] = NormalizationDescriptorIo.Stat(1, nextInode++, NormalizationDescriptorIo.Kind.DIRECTORY, 1, 0, 1, 2, 3, 4)
    }
    fun file(path: File, size: Long) {
        metadata[key(path)] = NormalizationDescriptorIo.Stat(1, nextInode++, NormalizationDescriptorIo.Kind.REGULAR, 1, size, 1, 2, 3, 4)
    }
    private inline fun <T> call(operation: String, path: File? = null, block: () -> T): T {
        check(!Thread.holdsLock(ownerLock)) { "HOST_IO_UNDER_STORAGE_LOCK:$operation" }
        val call = Call(operation, path)
        calls += call
        before(call)
        val result = block()
        after(call)
        return result
    }
    override fun lstat(path: File): NormalizationDescriptorIo.Stat? = call("lstat", path) { metadata[key(path)] }
    override fun openReadOnly(path: File): RandomAccessFile = call("open", path) {
        RandomAccessFile(path, "r").also {
            handles += it
            opened[it] = path
            descriptors[it.fd] = stat(path)
        }
    }
    override fun descriptor(input: RandomAccessFile): FileDescriptor = call("descriptor", opened[input]) { input.fd }
    override fun fstat(descriptor: FileDescriptor): NormalizationDescriptorIo.Stat = call("fstat") {
        check(descriptor.valid()) { "HOST_CLOSED_FSTAT" }
        fstatTransform(descriptors[descriptor] ?: throw IOException("HOST_UNKNOWN_DESCRIPTOR"))
    }
    override fun valid(descriptor: FileDescriptor): Boolean = call("valid") { descriptor.valid() }
    override fun close(input: RandomAccessFile) = call("close", opened[input]) {
        when (closeMode) {
            CloseMode.NORMAL -> input.close()
            CloseMode.RETURNS_VALID -> Unit
            CloseMode.INVALIDATES_THEN_THROWS -> { input.close(); throw IOException("HOST_CLOSE_UNCONFIRMED") }
            CloseMode.THROWS_VALID -> throw IOException("HOST_CLOSE_UNCONFIRMED")
        }
    }
    override fun openDirectory(path: File): AdoptedCanonicalRafIo.Directory = call("openDirectory", path) {
        val stream = Files.newDirectoryStream(path.toPath()).also { directories += it }
        object : AdoptedCanonicalRafIo.Directory {
            override fun children(maximum: Int): List<String> = call("children", path) {
                require(maximum >= 0)
                stream.asSequence().take(maximum + 1).map { it.fileName.toString() }.toList()
            }
            override fun close() = call("closeDirectory", path) {
                stream.close()
                if (directoryCloseFailure) throw IOException("HOST_DIRECTORY_CLOSE_UNCONFIRMED")
            }
        }
    }
    fun count(operation: String) = calls.count { it.operation == operation }
    fun dispose() {
        handles.forEach { try { it.close() } catch (_: Exception) { } }
        directories.forEach { try { it.close() } catch (_: Exception) { } }
    }
}
