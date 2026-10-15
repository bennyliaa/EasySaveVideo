package com.easysavevideo.app.media.transform

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Private lifecycle seam: the production session is the retained owner, not a DTO/authority flag. */
internal interface TransformerSessionControl {
    val releaseTimeoutMillis: Long
    fun checkpoint()
    fun requireNewInvocation() = Unit
    fun enterWorker(entry: TransformerWorkerEntry)
    fun abandon()
    fun endDiagnostics()
    fun finish(): Boolean
    fun recordCleanupFailure()
    fun cleanupFailed(): Boolean
    fun contentFailed(): Boolean
    fun releaseAcknowledged(): Boolean
}

/** A non-copyable fact issued only inside the accepted, checkpointed execution worker. */
internal class TransformerWorkerEntry private constructor(private val session: TransformerSessionControl) {
    private val worker = Thread.currentThread()
    @Volatile private var retired = false
    @Volatile private var cleaning = false
    fun requireActive(candidate: TransformerSessionControl) {
        if (candidate !== session || retired || cleaning) throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    /** Retained bounded finalization may run on the native callback thread, never after retirement. */
    fun requireActiveOrCleaning(candidate: TransformerSessionControl) {
        if (candidate !== session || retired) throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    fun requireCleanup(candidate: TransformerSessionControl) {
        if (candidate !== session || retired || !cleaning || Thread.currentThread() !== worker)
            throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    private fun beginCleanup() { cleaning = true }
    private fun retire() { retired = true }

    companion object {
        internal fun <T> execute(owner: TransformerLaneOwner, session: TransformerSessionControl, work: () -> T): TransformerOutcome<T> {
            val fence = owner.fence
            try { session.checkpoint(); session.requireNewInvocation() }
            catch (failure: Throwable) { return TransformerOutcome.Failed(failure, TransformerRelease.CONFIRMED) }
            if (!fence.claim(session)) return TransformerOutcome.Failed(TransformerFailure(TransformerError.CAPACITY),
                if (fence.requiresRestart) TransformerRelease.UNCONFIRMED else TransformerRelease.CONFIRMED)
            val result = CompletableFuture<TransformerOutcome<T>>()
            try {
                owner.lane.execute {
                    var entry: TransformerWorkerEntry? = null
                    var value: T? = null
                    var primary: Throwable? = null
                    val outcome: TransformerOutcome<T> = try {
                        try {
                            session.checkpoint() // Queued cancellation never issues an entry.
                            entry = TransformerWorkerEntry(session)
                            session.enterWorker(requireNotNull(entry))
                            value = work()
                        } catch (failure: Throwable) { primary = transformerPrimaryFailure(failure) }
                        entry?.beginCleanup()
                        try { session.endDiagnostics() }
                        catch (failure: Throwable) { if (primary == null) primary = failure; session.recordCleanupFailure() }
                        val released = try { session.finish() }
                        catch (failure: Throwable) { if (primary == null) primary = failure; session.recordCleanupFailure(); false }
                        try { session.endDiagnostics() }
                        catch (failure: Throwable) { if (primary == null) primary = failure; session.recordCleanupFailure() }
                        if (primary == null && session.contentFailed()) primary = TransformerFailure(TransformerError.EXECUTION_FAILED)
                        if (!released || session.cleanupFailed()) {
                            fence.unconfirmed()
                            TransformerOutcome.Failed(primary ?: TransformerFailure(TransformerError.EXECUTION_FAILED), TransformerRelease.UNCONFIRMED)
                        } else {
                            fence.release(session)
                            if (primary != null) TransformerOutcome.Failed(requireNotNull(primary), TransformerRelease.CONFIRMED)
                            else { @Suppress("UNCHECKED_CAST") TransformerOutcome.Completed(value as T) }
                        }
                    } catch (failure: Throwable) {
                        // Even a broken cleanup callback must acknowledge the original worker.
                        fence.unconfirmed()
                        TransformerOutcome.Failed(primary ?: failure, TransformerRelease.UNCONFIRMED)
                    } finally { entry?.retire() }
                    result.complete(outcome) // Entry is already permanently retired before publication.
                }
            } catch (failure: RejectedExecutionException) {
                fence.release(session)
                return TransformerOutcome.Failed(TransformerFailure(TransformerError.CAPACITY), TransformerRelease.CONFIRMED)
            } catch (failure: Throwable) {
                return stoppedTransformerOutcome(owner, session, result, failure)
            }
            try {
                while (true) {
                    session.checkpoint()
                    try { return result.get(TransformerMechanismPolicy.POLL_MILLIS, TimeUnit.MILLISECONDS).also { session.checkpoint() } }
                    catch (_: TimeoutException) { }
                }
            } catch (failure: Throwable) { return stoppedTransformerOutcome(owner, session, result, failure) }
        }
    }
}

/** Real shared scheduling/wait algorithm; no alternate lane or caller-provided entry flag. */
internal fun <T> executeTransformerSession(owner: TransformerLaneOwner, session: TransformerSessionControl, work: () -> T): TransformerOutcome<T> =
    TransformerWorkerEntry.execute(owner, session, work)

private fun stoppedTransformerOutcome(owner: TransformerLaneOwner, session: TransformerSessionControl,
    result: CompletableFuture<*>, failure: Throwable): TransformerOutcome.Failed {
    try { session.abandon() } catch (_: Throwable) { session.recordCleanupFailure() }
    TransformerCleanupWait.awaitCompletion(result, session.releaseTimeoutMillis, failure is InterruptedException)
    // Only this invocation's actual worker result can acknowledge release. A historical empty
    // session.finish() or an unrelated invocation's state is never cleanup evidence here.
    val workerOutcome = if (result.isDone) try { result.getNow(null) as? TransformerOutcome<*> }
        catch (_: Throwable) { null } else null
    val workerReleased = when (workerOutcome) {
        is TransformerOutcome.Completed -> true
        is TransformerOutcome.Failed -> workerOutcome.release == TransformerRelease.CONFIRMED
        null -> false
    }
    val acknowledged = workerReleased && !session.cleanupFailed()
    if (!acknowledged) owner.fence.unconfirmed()
    return TransformerOutcome.Failed(transformerPrimaryFailure(failure),
        if (acknowledged) TransformerRelease.CONFIRMED else TransformerRelease.UNCONFIRMED)
}

/** Future transport is not a product error boundary; preserve Direct's original stop/failure. */
internal fun transformerPrimaryFailure(failure: Throwable): Throwable {
    var value = failure
    repeat(8) {
        if (value !is ExecutionException && value !is java.util.concurrent.CompletionException) return value
        val cause = value.cause ?: return value
        if (cause === value) return value
        value = cause
    }
    return value
}
