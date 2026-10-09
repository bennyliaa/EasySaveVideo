package com.easysavevideo.app.editor.engine

import android.media.MediaExtractor
import android.os.Handler
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.transformer.Transformer
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Strong ownership survives caller cancellation and every watchdog. No timeout means released. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EditorSession(
    val inputs: EditorInputPort,
    val context: OperationContext,
    timeoutMillis: Long,
    val handler: Handler,
    val output: EditorOwnedOutput? = null,
) {
    val abandoned = AtomicBoolean(false)
    val closeFailed = AtomicBoolean(false)
    val releaseOrder = EditorNativeReleaseOrder()
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
    private val readers = LinkedHashMap<EditInputRef, AutoCloseable>()
    private var inputOpens = 0
    private var transferredBytes = 0L
    private val openInputs = Collections.synchronizedSet(LinkedHashSet<EditorOwnedInput>())
    @Volatile var probeExtractor: MediaExtractor? = null
    @Volatile var nativeWriter: AutoCloseable? = null
    @Volatile var muxerCreated = false
    @Volatile var muxerReleased = false
    val muxerContentFailed = AtomicBoolean(false)
    @Volatile var outcomeReleased = false
    @Volatile var lastProgressNanos = System.nanoTime()
    @Volatile var hasTransformer = false
    val limitNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val cancelPosted = AtomicBoolean(false)
    private val cancelReturned = CompletableFuture<Boolean>()

    fun checkpoint() {
        if (abandoned.get() || context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw EditFailure(EditError.CANCELLED)
        if (System.currentTimeMillis() >= context.deadlineEpochMillis || System.nanoTime() >= limitNanos) throw EditFailure(EditError.TIMEOUT)
        if (hasTransformer && System.nanoTime() - lastProgressNanos > TimeUnit.MILLISECONDS.toNanos(EditLimits.STALL_TIMEOUT_MILLIS))
            throw EditFailure(EditError.TIMEOUT)
    }
    @Synchronized fun retain(reference: EditInputRef) {
        if (readers.containsKey(reference)) return
        if (readers.size >= EditLimits.MAX_RECORDED_SEGMENTS) throw EditFailure(EditError.SOURCE_LIMIT)
        readers[reference] = inputs.retainTyped(reference)
    }
    fun openInput(ref: EditInputRef): EditorOwnedInput = synchronized(openInputs) {
        checkpoint()
        if (++inputOpens > 12 * EditLimits.MAX_RECORDED_SEGMENTS || openInputs.size >= EditorEnginePolicy.MAX_OPEN_INPUTS) throw EditFailure(EditError.SOURCE_LIMIT)
        inputs.openTyped(ref).also { openInputs.add(it) }
    }
    @Synchronized fun transferred(bytes: Int) {
        if (bytes <= 0 || transferredBytes > EditLimits.MAX_TOTAL_SOURCE_BYTES * 8 - bytes)
            throw EditFailure(EditError.SOURCE_LIMIT)
        transferredBytes += bytes
    }
    fun closeInput(input: EditorOwnedInput) {
        try { input.close(); openInputs.remove(input) } catch (failure: Throwable) { closeFailed.set(true); throw failure }
    }
    fun newExecutor(name: String, queueSize: Int, ownsGl: Boolean = false): ThreadPoolExecutor = synchronized(executors) {
        checkpoint()
        if (executors.size >= EditLimits.MAX_RECORDED_SEGMENTS + 2) throw EditFailure(EditError.SOURCE_LIMIT)
        object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueSize),
            { job -> Thread(job, name).apply { isDaemon = true } }, AbortPolicy()) {
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                super.afterExecute(task, failure)
                if (ownsGl && EditorTaskFailureProbe.failed(task, failure)) {
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
    fun abandon() { abandoned.set(true); postCancel() }
    fun postCancel() {
        if (!cancelPosted.compareAndSet(false, true)) return
        if (!handler.post {
            try { transformer.getAndSet(null)?.cancel(); cancelReturned.complete(true) }
            catch (_: Throwable) { closeFailed.set(true); cancelReturned.complete(false) }
        }) { closeFailed.set(true); cancelReturned.complete(false) }
    }
    private fun nativeReleaseConfirmed(): Boolean =
        !closeFailed.get() && probeExtractor == null &&
            (!hasTransformer || EditorSilentDiagnostics.consistent(this)) &&
            (!hasTransformer || (cancelReturned.isDone && cancelReturned.getNow(false))) &&
            (!muxerCreated || muxerReleased) && codecsCreated.get() == codecsReleased.get() &&
            loadersCreated.get() == loadersReleased.get() && playbackThreads.size >= loadersCreated.get() &&
            processorsCreated.get() == processorsReleased.get() &&
            nativeThreads.none { it.isAlive } && synchronized(executors) { executors.all { it.isTerminated } } &&
            synchronized(openInputs) { openInputs.isEmpty() }

    fun finish(): Boolean {
        if (hasTransformer) postCancel()
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EditLimits.RELEASE_TIMEOUT_MILLIS)
        while (!nativeReleaseConfirmed() && System.nanoTime() < end) {
            if (closeFailed.get()) return false
            try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        if (!nativeReleaseConfirmed()) return false
        // This is the only point that releases native duplicate/source or staging writer fences.
        try {
            nativeWriter?.close(); nativeWriter = null
            synchronized(this) { readers.values.forEach { it.close() }; readers.clear() }
        } catch (_: Throwable) { closeFailed.set(true); return false }
        outcomeReleased = true
        return true
    }
}
