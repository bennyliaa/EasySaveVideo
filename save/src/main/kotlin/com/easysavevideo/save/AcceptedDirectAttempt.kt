package com.easysavevideo.save

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A final, core-issued projection of the actual entered Claim and accepted Coordinator invocation.
 * It is neither durable proof nor permission that can be reconstructed from IDs or a SaveRecord.
 * Assertions read atomic/volatile state and the operation's memory-only cancellation token;
 * they never acquire upper-layer locks or call admission, Coordinator, storage, SQL or clocks.
 */
class AcceptedDirectAttempt private constructor(
    private val entered: SaveExecutionAdmission.EnteredClaim,
    private val runtimeIdentity: Any,
    private val stopLatch: AtomicBoolean,
    val context: OperationContext,
    val resumedFrom: SaveRecord?,
    /** True proves a prior in-memory Runtime. False NEVER proves a genuine process restart. */
    val priorRuntimeInProcess: Boolean,
) {
    private val live = AtomicBoolean(true)
    private data class CompletionState(val retired: Boolean = false, val record: SaveRecord? = null)
    private val completion = AtomicReference(CompletionState())
    /** Scheduling seam for core race tests only; cannot issue or mutate a historical fact. */
    internal var completionRegistrationObserver: (() -> Unit)? = null
    val taskId: String get() = entered.taskId
    val operationId: String get() = entered.operationId
    val attempt: Int get() = context.attempt
    /** The original transfer returned and its core release probes passed in THIS execution. */
    val transferredHere: TransferResult? get() = completion.get().record?.transfer

    /**
     * Readonly provenance of a transfer that actually returned/released and whose exact complete
     * Save proposal was confirmed. This works after stop/exit but grants NO current IO authority.
     * A foreign Admission cannot read it; issuer validation is memory-only and never tests liveness.
     */
    fun recordedTransferFrom(expectedAdmission: SaveExecutionAdmission): SaveRecord? {
        entered.assertIssuer(expectedAdmission)
        return completion.get().record
    }

    fun assertAdmission(expected: SaveExecutionAdmission) { entered.assertAdmission(expected); requireLive(context) }
    fun requireLive(registeredContext: OperationContext) {
        entered.requireLive()
        if (!live.get() || completion.get().retired || stopLatch.get() || context.cancelToken.isCancelled || registeredContext.operationId != context.operationId ||
            registeredContext.attempt != context.attempt || registeredContext.cancelToken !== context.cancelToken ||
            registeredContext.retryBudget != context.retryBudget || registeredContext.resourceBudget != context.resourceBudget ||
            registeredContext.deadlineEpochMillis > context.deadlineEpochMillis)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
    internal fun revoke() { live.set(false) }
    /** Exit/replacement seals first registration atomically; stop alone does not erase facts. */
    internal fun retire() {
        completion.updateAndGet { if (it.retired) it else it.copy(retired = true) }
        live.set(false)
    }
    internal fun recordTransferCompletion(record: SaveRecord) {
        val raw = record.transfer ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val artifact = record.artifact ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (record.task.taskId != taskId || record.operationId != operationId || record.attempt != attempt ||
            record.request.method != TaskMethod.DIRECT || record.task.state != TaskState.RUNNING ||
            record.phase != SavePhase.EXECUTING || record.hasDirectOutputProof() ||
            record.verification != null || record.receipt != null || raw.streamExpectation != null ||
            raw.recordingExpectation != null || raw.artifactId != artifact.artifactId ||
            raw.bytesOnDisk <= 0 || raw.expectedTotalBytes?.let { it != raw.bytesOnDisk } == true)
            throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        // The raw-stage value graph consists only of immutable scalar/value records. Detach its
        // record/request/task/artifact/transfer/checkpoint shells once; no native or mutable set is retained.
        val detached = record.copy(request = record.request.copy(), task = record.task.copy(),
            artifact = artifact.copy(), transfer = raw.copy(), checkpoint = record.checkpoint?.copy())
        while (true) {
            val prior = completion.get()
            if (prior.retired) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            // Cancellation can already have revoked new-work authority, but the real entered
            // invocation must still own this completion. An exited/rebound claim cannot register.
            entered.assertRuntime(runtimeIdentity)
            prior.record?.let {
                if (it != detached) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                return
            }
            completionRegistrationObserver?.invoke()
            if (completion.compareAndSet(prior, prior.copy(record = detached))) return
        }
    }
    internal fun assertRuntime(expected: Any) {
        if (runtimeIdentity !== expected) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        entered.assertRuntime(expected); requireLive(context)
    }
    internal companion object {
        fun issue(entered: SaveExecutionAdmission.EnteredClaim, runtime: Any, stopLatch: AtomicBoolean,
            context: OperationContext, resumedFrom: SaveRecord?, priorRuntimeInProcess: Boolean): AcceptedDirectAttempt {
            entered.assertRuntime(runtime)
            if (stopLatch.get() || context.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED)
            val result = AcceptedDirectAttempt(entered, runtime, stopLatch, context, resumedFrom, priorRuntimeInProcess)
            if (stopLatch.get() || context.cancelToken.isCancelled) { result.revoke(); throw SaveFailure(SaveError.CANCELLED) }
            return result
        }
    }
}
