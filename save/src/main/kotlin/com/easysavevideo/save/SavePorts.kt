package com.easysavevideo.save

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingSnapshot

/** All methods are bounded local IO. The implementation must atomically enforce unique idempotency keys. */
interface SaveJournal {
    fun findByIdempotencyKey(key: String): SaveRecord?
    fun read(taskId: String): SaveRecord?
    /** Returns the inserted record or the existing record for that key; never silently replace a task. */
    fun insert(record: SaveRecord, maxEntries: Int): SaveRecord
    /**
     * A SUCCEEDED CAS is the sole publication point: in the same transaction, verify the matching
     * provisional media/index receipt and make it visible. Never publish for CANCELLED/FAILED.
     * This final transaction must be short; no hashing, network, native verification or large file IO.
     */
    fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean
    fun listUnfinished(limit: Int): List<SaveRecord>
    /** Recent tasks including terminal records, bounded for task-center rendering. */
    fun listRecent(limit: Int): List<SaveRecord>
}

/** Port owns and closes network/input/output resources before returning, including exceptional returns. */
interface DirectTransferPort {
    /** True only when an earlier port call could not prove resource release; no further work may start. */
    fun requiresProcessRecovery(): Boolean = false
    fun transfer(
        resource: SelectedMediaResource,
        artifact: StagingArtifact,
        checkpoint: DownloadCheckpoint?,
        context: OperationContext,
        limits: SaveLimits,
        progress: TransferProgress,
    ): TransferResult
}
interface TransferProgress {
    /** Report network bytes, not artifact length. Restarted/retried bytes consume the same total budget. */
    fun onBytes(delta: Long)
    fun onCheckpoint(checkpoint: DownloadCheckpoint)
    /** Called before truncating/replacing a partial file. Lifetime network-byte consumption is not reset. */
    fun onRestart()
}
/**
 * Must enforce operation deadline/cancel and close native resources when the call actually finishes.
 * If a native call cannot be interrupted, a bounded caller may return failure and fence its late result,
 * but must retain its single occupied native lane until actual release (never spawn replacement lanes).
 * A timeout result is not evidence that native resources have already been released.
 */
fun interface ArtifactVerifier {
    /** An uninterruptible abandoned native call keeps the lane occupied until it really finishes. */
    fun requiresProcessRecovery(): Boolean = false
    fun verify(artifact: StagingArtifact, transfer: TransferResult, context: OperationContext): VerificationEvidence
    /** Old adapters may verify unchanged outputs only; normalized bytes need explicit support. */
    fun verify(artifact: StagingArtifact, output: ArtifactOutputDescriptor, context: OperationContext): VerificationEvidence =
        when (output) {
            is ArtifactOutputDescriptor.Transferred -> verify(artifact, output.transfer, context)
            is ArtifactOutputDescriptor.NormalizedDirect -> throw SaveFailure(SaveError.METHOD_UNSUPPORTED)
            is ArtifactOutputDescriptor.ConvertedDirect -> throw SaveFailure(SaveError.METHOD_UNSUPPORTED)
        }
}

/**
 * Private same-filesystem storage. No server filename or external SAF target is accepted by this port.
 * prepare is idempotent by commitId; commit persists intent, flushes, promotes, and creates an INVISIBLE provisional index.
 * FS and SQL are NOT one transaction: retries and inspectCommitted must reconcile the same commitId.
 * commit/inspect run OUTSIDE the cancel gate, must honor cancellation/deadline, and NEVER publish media.
 * Only SaveJournal.compareAndSet(SUCCEEDED) may atomically publish the provisional index and task.
 * On uncertain failure preserve owned material for recovery; do not delete another/previous media item.
 */
interface PrivateMediaStore {
    fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact
    fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt
    /** Recheck durable file/index agreement, including content identity, rather than returning a cached flag. */
    fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection?
    /** Delete only journal-owned, uncommitted staging. Committed media and unrelated files are preserved. */
    fun cleanup(artifact: StagingArtifact, committed: Boolean)
}

/** Scheduling primitive; production adapters must wait interruptibly and must not busy-spin. */
fun interface SaveRetryDelay { fun await(delayMillis: Long, context: OperationContext) }
class ThreadSaveRetryDelay(private val clock: com.easysavevideo.core.model.EpochClock = com.easysavevideo.core.model.EpochClock { System.currentTimeMillis() }) : SaveRetryDelay {
    override fun await(delayMillis: Long, context: OperationContext) {
        val end = System.nanoTime() + delayMillis * 1_000_000
        while (true) {
            if (context.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED)
            if (clock.nowMillis() >= context.deadlineEpochMillis) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            val remaining = minOf((end - System.nanoTime()) / 1_000_000, context.deadlineEpochMillis - clock.nowMillis())
            if (remaining <= 0) return
            try { Thread.sleep(minOf(remaining, 50)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw SaveFailure(SaveError.CANCELLED) }
        }
    }
}

data class SaveMethodAvailability(val available: Boolean, val reason: SaveError?) {
    init { require(available == (reason == null)) }
}
interface StreamSavePort : DirectTransferPort {
    fun availability(): SaveMethodAvailability
    override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
        checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
        progress: TransferProgress): TransferResult = throw SaveFailure(SaveError.METHOD_UNSUPPORTED)
}
object UnsupportedStreamSave : StreamSavePort {
    override fun availability() = SaveMethodAvailability(false, SaveError.METHOD_UNSUPPORTED)
}
/** Capture is a user-authorized session, never an HTTP transfer or a retryable source. */
fun interface RecordSavePort {
    fun availability(): SaveMethodAvailability
    fun requiresProcessRecovery(): Boolean = false
    /** Returns only after writer and native resources are actually closed; cancellation never returns publishable output. */
    fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
        context: OperationContext, limits: SaveLimits): TransferResult = throw SaveFailure(SaveError.METHOD_UNSUPPORTED)
    /** Request-scoped, nonblocking memory commands. Stale identifiers must not control another session. */
    fun snapshot(requestId: String): RecordingSnapshot? = null
    fun pause(requestId: String): RecordingSnapshot? = null
    fun resume(requestId: String): RecordingSnapshot? = null
    fun stopAndSave(requestId: String): RecordingSnapshot? = null
    fun cancel(requestId: String): RecordingSnapshot? = null
}
object UnsupportedRecordSave : RecordSavePort {
    override fun availability() = SaveMethodAvailability(false, SaveError.METHOD_UNSUPPORTED)
}
object SaveCapabilities {
    fun availability(method: TaskMethod): SaveMethodAvailability = if (method == TaskMethod.DIRECT)
        SaveMethodAvailability(true, null) else SaveMethodAvailability(false, SaveError.METHOD_UNSUPPORTED)
}
