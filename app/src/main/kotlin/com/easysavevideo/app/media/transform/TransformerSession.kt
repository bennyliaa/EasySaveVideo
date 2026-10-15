package com.easysavevideo.app.media.transform

import android.media.MediaExtractor
import android.os.Handler
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.transformer.Transformer
import com.easysavevideo.core.model.OperationStopReason
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Strong ownership survives caller cancellation and every watchdog. No timeout means released. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerSession(
    val limits: TransformerSessionLimits,
    private val callerCheckpoint: () -> Unit,
    private val callerStopReason: () -> OperationStopReason?,
) : TransformerSessionControl {
    private val workerEntry = AtomicReference<TransformerWorkerEntry?>()
    private var finishStarted = false
    @Synchronized override fun requireNewInvocation() {
        if (finishStarted || workerEntry.get() != null || closeFailed.get() || abandoned.get())
            throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    @Synchronized override fun enterWorker(entry: TransformerWorkerEntry) {
        entry.requireActive(this)
        requireNewInvocation()
        if (!workerEntry.compareAndSet(null, entry)) throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    fun requireEntered() {
        val entry = workerEntry.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
        entry.requireActive(this)
        checkpoint()
        entry.requireActive(this)
    }
    override val releaseTimeoutMillis: Long get() = limits.releaseTimeoutMillis
    override fun endDiagnostics() { if (hasTransformer) TransformerSilentDiagnostics.end(this) }
    override fun recordCleanupFailure() { closeFailed.set(true) }
    override fun cleanupFailed(): Boolean = closeFailed.get()
    override fun contentFailed(): Boolean = muxerContentFailed.get()
    override fun releaseAcknowledged(): Boolean = outcomeReleased
    val handler get() = SharedTransformerRuntime.handler
    val abandoned = AtomicBoolean(false)
    val closeFailed = AtomicBoolean(false)
    val releaseOrder = TransformerNativeReleaseOrder()
    val transformer = AtomicReference<Transformer?>()
    val completion = CompletableFuture<androidx.media3.transformer.ExportResult>()
    val nativeThreads = ConcurrentHashMap.newKeySet<Thread>()
    val playbackThreads = ConcurrentHashMap.newKeySet<Thread>()
    val loadersCreated = AtomicInteger()
    val loadersReleased = AtomicInteger()
    val codecsCreated = AtomicInteger()
    val codecsReleased = AtomicInteger()
    val processorsCreated = AtomicInteger()
    val processorsReleased = AtomicInteger()
    val executors = Collections.synchronizedList(ArrayList<ThreadPoolExecutor>())
    val retainedObjects = Collections.synchronizedList(ArrayList<Any>())
    private val readers = LinkedHashMap<TransformerOwnedSource, AutoCloseable>()
    private var releasingSource: TransformerOwnedSource? = null
    @Synchronized fun requireSourceRelease(source: TransformerOwnedSource) {
        val entry = workerEntry.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
        entry.requireCleanup(this)
        if (releasingSource !== source || !readers.containsKey(source)) throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    // The slot is consumed before calling the target. A throwing acquisition is not proof that
    // its native pin was never acquired, even when no retention object was returned to us.
    private var writerAcquisitionStarted = false
    private var writerAcquisitionPending = false
    private var writerAcquisitionUnknown = false
    private var writerTarget: TransformerOwnedTarget? = null
    private var writerRetention: AutoCloseable? = null
    private var releasingWriter: TransformerOwnedTarget? = null
    private var cleanupDeadlineNanos: Long? = null

    fun retainWriter(target: TransformerOwnedTarget) {
        requireEntered() // A rejection before the target call is positively no acquisition.
        synchronized(this) {
            val entry = workerEntry.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
            entry.requireActive(this)
            if (finishStarted || writerAcquisitionStarted) throw TransformerFailure(TransformerError.INVALID_STATE)
            writerAcquisitionStarted = true
            writerAcquisitionPending = true
            writerTarget = target
        }
        val retention = try { target.retainNativeWriter() }
        catch (failure: Throwable) {
            synchronized(this) { writerAcquisitionPending = false; writerAcquisitionUnknown = true }
            closeFailed.set(true)
            throw failure
        }
        synchronized(this) {
            // Register the actual return before a stop check; only real cleanup can release it.
            writerRetention = retention
            writerAcquisitionPending = false
        }
        requireEntered()
    }
    @Synchronized fun requireWriterRelease(target: TransformerOwnedTarget) {
        val entry = workerEntry.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
        entry.requireCleanup(this)
        if (writerTarget !== target || writerRetention == null || releasingWriter !== target ||
            writerAcquisitionPending || writerAcquisitionUnknown) throw TransformerFailure(TransformerError.INVALID_STATE)
    }
    /** Existing exact retention only; null means cleanup has not begun. Absolute nanoTime units. */
    @Synchronized fun requireWriterFinalization(target: TransformerOwnedTarget): Long? {
        val entry = workerEntry.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
        entry.requireActiveOrCleaning(this)
        if (writerTarget !== target || writerRetention == null || writerAcquisitionPending || writerAcquisitionUnknown)
            throw TransformerFailure(TransformerError.INVALID_STATE)
        return cleanupDeadlineNanos
    }
    @Synchronized private fun freezeCleanupDeadline(): Long = cleanupDeadlineNanos
        ?: (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.releaseTimeoutMillis)).also { cleanupDeadlineNanos = it }
    private var inputOpens = 0
    private var transferredBytes = 0L
    private val openInputs = Collections.synchronizedSet(LinkedHashSet<TransformerReadHandle>())
    @Volatile var probeExtractor: MediaExtractor? = null
    @Volatile var muxerCreated = false
    @Volatile var muxerReleased = false
    val muxerContentFailed = AtomicBoolean(false)
    @Volatile var outcomeReleased = false
    @Volatile var lastProgressNanos = System.nanoTime()
    @Volatile var hasTransformer = false
    val limitNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis)
    private val cancelPosted = AtomicBoolean(false)
    private val cancelReturned = CompletableFuture<Boolean>()

    override fun checkpoint() {
        callerCheckpoint() // Preserve the caller's original stop/storage failure without wrapping it.
        callerStopReason()?.let { throw TransformerStopped(it) }
        if (abandoned.get() || Thread.currentThread().isInterrupted) throw TransformerStopped(OperationStopReason.CANCELLED)
        if (System.nanoTime() >= limitNanos ||
            (hasTransformer && System.nanoTime() - lastProgressNanos > TimeUnit.MILLISECONDS.toNanos(limits.stallTimeoutMillis)))
            throw TransformerStopped(OperationStopReason.DEADLINE_EXCEEDED)
    }
    @Synchronized fun retain(reference: TransformerOwnedSource) {
        if (readers.containsKey(reference)) return
        if (readers.size >= limits.maxLoaders) throw TransformerFailure(TransformerError.SOURCE_LIMIT)
        readers[reference] = reference.retainNativeReader()
    }
    fun openInput(ref: TransformerOwnedSource): TransformerReadHandle = synchronized(openInputs) {
        checkpoint()
        if (++inputOpens > limits.maxInputOpens || openInputs.size >= limits.maxOpenInputs) throw TransformerFailure(TransformerError.SOURCE_LIMIT)
        ref.openReader().also { openInputs.add(it) }
    }
    @Synchronized fun transferred(bytes: Int) {
        if (bytes <= 0 || transferredBytes > limits.maxTransferredBytes - bytes)
            throw TransformerFailure(TransformerError.SOURCE_LIMIT)
        transferredBytes += bytes
    }
    fun closeInput(input: TransformerReadHandle) {
        try { input.close(); if (!input.releaseConfirmed) throw TransformerFailure(TransformerError.INVALID_STATE); openInputs.remove(input) } catch (failure: Throwable) { closeFailed.set(true); throw failure }
    }
    fun newExecutor(name: String, queueSize: Int, ownsGl: Boolean = false): ThreadPoolExecutor = synchronized(executors) {
        checkpoint()
        if (executors.size >= limits.maxExecutors) throw TransformerFailure(TransformerError.SOURCE_LIMIT)
        object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueSize),
            { job -> Thread(job, name).apply { isDaemon = true } }, AbortPolicy()) {
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                super.afterExecute(task, failure)
                if (ownsGl && TransformerTaskFailureProbe.failed(task, failure)) {
                    // Pinned Media3 catches Exception, not Error, in GL tasks. Its release-task
                    // Future is ignored, so termination alone cannot establish successful cleanup.
                    releaseOrder.glFailed(); closeFailed.set(true)
                }
            }
        }.also { executors.add(it) }
    }
    fun newDownloadExecutor(): ReleasableExecutor {
        val executor = newExecutor("esv-edit-reader", 16)
        return ReleasableExecutor.from(executor) { it.shutdown() }
    }
    override fun abandon() { freezeCleanupDeadline(); abandoned.set(true); postCancel() }
    fun postCancel() {
        freezeCleanupDeadline() // The first abandon/cancel and finish share one fixed deadline.
        if (!cancelPosted.compareAndSet(false, true)) return
        if (!handler.post {
            try { transformer.getAndSet(null)?.cancel(); cancelReturned.complete(true) }
            catch (_: Throwable) { closeFailed.set(true); cancelReturned.complete(false) }
        }) { closeFailed.set(true); cancelReturned.complete(false) }
    }
    private fun nativeReleaseConfirmed(): Boolean =
        !closeFailed.get() && synchronized(this) { !writerAcquisitionPending && !writerAcquisitionUnknown } &&
            probeExtractor == null &&
            (!hasTransformer || TransformerSilentDiagnostics.consistent(this)) &&
            (!hasTransformer || (cancelReturned.isDone && cancelReturned.getNow(false))) &&
            (!muxerCreated || muxerReleased) && codecsCreated.get() == codecsReleased.get() &&
            loadersCreated.get() == loadersReleased.get() && playbackThreads.size >= loadersCreated.get() &&
            processorsCreated.get() == processorsReleased.get() &&
            nativeThreads.none { it.isAlive } && synchronized(executors) { executors.all { it.isTerminated } } &&
            synchronized(openInputs) { openInputs.isEmpty() }

    override fun finish(): Boolean {
        val end = synchronized(this) {
            // Reject an early caller before consuming the real worker's cleanup opportunity.
            workerEntry.get()?.requireCleanup(this)
            if (finishStarted) return false
            finishStarted = true
            freezeCleanupDeadline()
        }
        if (hasTransformer) postCancel()
        while (!nativeReleaseConfirmed() && System.nanoTime() < end) {
            if (closeFailed.get()) return false
            try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        if (!nativeReleaseConfirmed()) return false
        // This is the only point that releases native duplicate/source or staging writer fences.
        try {
            val retainedWriter = synchronized(this) { writerRetention?.let { requireNotNull(writerTarget) to it } }
            if (retainedWriter != null) {
                val (target, retention) = retainedWriter
                synchronized(this) { releasingWriter = target }
                try { retention.close() } finally { synchronized(this) { releasingWriter = null } }
                synchronized(this) { writerRetention = null; writerTarget = null }
            }
            val retained = synchronized(this) { readers.entries.map { it.key to it.value } }
            for ((source, retention) in retained) {
                synchronized(this) { releasingSource = source }
                try { retention.close() } finally { synchronized(this) { releasingSource = null } }
                synchronized(this) { readers.remove(source) }
            }
        } catch (_: Throwable) { closeFailed.set(true); return false }
        outcomeReleased = true
        return true
    }
}
