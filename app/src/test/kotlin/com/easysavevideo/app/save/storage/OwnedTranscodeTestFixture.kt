package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.core.model.*
import com.easysavevideo.library.thumbnail.ThumbnailNativeResourceGate
import com.easysavevideo.save.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/** Real core admission/entered execution/gate/bridge; fake filesystem and no decoder/media proof. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OwnedTranscodeTestFixture(rawSize: Int = 8192) {
    val f = NormalizationDescriptorFixture(rawSize, commitId = "task")
    val gate = DirectLocalProcessingGate(EpochClock { f.now })
    private val nativeGate = ThumbnailNativeResourceGate()
    private val batchOwner = Any()
    private var batch: ThumbnailNativeResourceGate.Ownership? = null
    val admission = SaveExecutionAdmission(maxArtifactBytes = f.limits.maxArtifactBytes,
        onFirstDirect = { nativeGate.claim(batchOwner).also { if (it) batch = nativeGate.ownership(batchOwner) } },
        onLastDirectReleased = { nativeGate.released(batchOwner); batch = null })
    val bridge = AndroidDirectExecutionBridge(admission, gate) { batch }
    lateinit var scope: AndroidDirectExecutionBridge.Scope
    lateinit var token: NormalizationIoReservations.Token
    var probe = true
    var beforePrepare: () -> Unit = {}
    var body: (OwnedTranscodeTestFixture, AndroidDirectExecutionBridge.Scope) -> Unit = { _, _ -> }
    private var callbackFailure: Throwable? = null
    private var called = false
    init {
        f.record = null
        f.transcodeScopeVerifier = { it.requireIssuer(bridge) }
        f.io.before = { check(!Thread.holdsLock(f.lock)) { "IO_INSIDE_OWNER_MONITOR" } }
    }
    fun prepare(): OwnedDirectTranscodeAttempt = f.attempts.prepareTranscode(f.owner, f.context, f.limits, token, scope)
    fun run(action: (OwnedTranscodeTestFixture, AndroidDirectExecutionBridge.Scope) -> Unit) {
        body = action
        val journal = object : ControlledDirectJournal {
            override fun findByIdempotencyKey(key: String) = f.record?.takeIf { it.request.idempotencyKey == key }
            override fun read(taskId: String) = f.record?.takeIf { it.task.taskId == taskId }
            override fun insert(record: SaveRecord, maxEntries: Int) = record.also { f.record = it }
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
                if (f.record?.task?.revision != expectedRevision) return false
                f.record = next
                next.transfer?.let { f.authority = f.authority.copy(sourceTransferSha256 = sourceTransferFingerprint(it)) }
                return true
            }
            override fun listUnfinished(limit: Int) = listOfNotNull(f.record).filter { !it.task.state.isTerminal }
            override fun listRecent(limit: Int) = listOfNotNull(f.record)
        }
        val storage = object : ControlledDirectStorage {
            override fun onTransferReleased(execution: AcceptedDirectAttempt) = Unit
            override fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String) = DirectWorkspacePresence.ABSENT
            override fun requiresProcessRecovery() = f.attempts.releaseUnconfirmed()
            override fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt,
                permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRawSelection {
                f.context = context
                scope = bridge.capture(execution, permit, context)
                token = f.attempts.reservations.reserve(f.owner, NormalizationIoReservations.Stage.RAW_PROBE, { scope.requireLive() }, scope = scope)
                if (probe) f.attempts.probeRaw(f.owner, context, token) { input -> input.readAt(0, ByteArray(1), 0, 1) }
                return object : DirectRawSelection {
                    override val kind = DirectRawKind.STRICT_MOV
                    override fun close() { f.attempts.closeRawSelection(token); scope.close() }
                }
            }
            override fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
                permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion {
                called = true
                try { beforePrepare(); body(this@OwnedTranscodeTestFixture, scope) }
                catch (failure: Throwable) { callbackFailure = failure }
                throw SaveFailure(SaveError.METHOD_UNSUPPORTED) // Test never issues conversion/proof success.
            }
            override fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt,
                permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext) = DirectRecoveryAction.REVIEW
            override fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt,
                permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): Unit = error("UNEXPECTED_ADOPT")
            override fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred,
                execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
                context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence = error("UNEXPECTED_VERIFY")
            override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
                execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
                context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion = error("UNEXPECTED_VERIFY")
            override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
                execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
                context: OperationContext): NormalizedCommitHandle = error("UNEXPECTED_COMMIT")
            override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
                output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt,
                permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle = error("UNEXPECTED_PUBLISH")
            override fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
                invocationContext: OperationContext, ioContext: OperationContext) = Unit
        }
        val store = object : PrivateMediaStore {
            override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact(f.owner.artifactId, commitId)
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt = error("UNEXPECTED_COMMIT")
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? = null
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) = Unit
        }
        val transfer = object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                progress.onBytes(f.rawBytes.size.toLong())
                return TransferResult(artifact.artifactId, f.rawBytes.size.toLong(), "application/octet-stream", f.rawBytes.size.toLong())
            }
        }
        val coordinator = SaveCoordinator(journal, transfer, ArtifactVerifier { _, _, _ -> error("UNEXPECTED_VERIFIER") },
            store, EpochClock { f.now }, f.limits, admission = admission,
            controlledDirect = ControlledDirectPipeline(gate, storage, true))
        val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/owned"))
        val claim = requireNotNull(admission.tryAcquireDirect())
        assertTrue(coordinator.submit(SaveRequest(f.owner.taskId, "key", source), f.context, claim) is SaveSubmission.Accepted)
        try { coordinator.execute(f.owner.taskId, claim) }
        finally { admission.release(claim) }
        callbackFailure?.let { throw it }
        assertTrue("Real core normalize callback must be reached", called)
    }
    fun session(): TransformerSession = TransformerSession(
        TransformerSessionLimits(30_000, 30_000, 2_000, 8, 4, 32L * 1024 * 1024 * 1024, 1, 2, 1),
        callerCheckpoint = { f.checkOperation(f.context) }, callerStopReason = { null })
    fun <T> entered(session: TransformerSession, action: () -> T): TransformerOutcome<T> {
        val lane = TransformerLaneOwner()
        try { return executeTransformerSession(lane, session, action) }
        finally { lane.lane.shutdown(); assertTrue(lane.lane.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
