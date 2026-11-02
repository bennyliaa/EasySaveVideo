package com.easysavevideo.commons

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.discovery.JvmDiscoveryTimer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed class CommonsOutcome {
    data class Success(val page: CommonsMetadataPage) : CommonsOutcome()
    data class Failure(val code: CommonsError, val retryAtEpochMillis: Long? = null) : CommonsOutcome()
}

/**
 * Exactly one bounded background worker. Cancel/deadline completion is independent of blocked I/O.
 * A hung transport or callback keeps the slot until it actually exits; retry never leaks workers.
 * Admitted-work callbacks run off the caller/UI thread; immediate rejections are synchronous.
 * UI owners marshal results onto their dispatcher.
 */
class CommonsProviderRuntime(
    private val provider: CommonsMetadataProvider,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    timer: DiscoveryTimer? = null,
) : AutoCloseable {
    private val ownsTimer = timer == null
    private val timer: DiscoveryTimer = timer ?: JvmDiscoveryTimer()
    private class Job(val thread: Thread) {
        @Volatile var cancel: (() -> Unit)? = null
        val terminal = AtomicReference<CommonsOutcome?>()
        val dispatched = AtomicBoolean(false)
        val workerDone = AtomicBoolean(false)
        val callbackDone = AtomicBoolean(false)
    }
    private val active = AtomicReference<Job?>()
    private val closed = AtomicBoolean(false)
    fun execute(request: CommonsSearchRequest, operation: DiscoveryOperation,
                callback: (CommonsOutcome) -> Unit): CancellationHandle {
        // Rejected work is reported synchronously and must not do UI-blocking work in its callback.
        if (closed.get()) { callback(CommonsOutcome.Failure(CommonsError.CANCELLED)); return CancellationHandle {} }
        lateinit var job: Job
        var deadlineHandle: CancellationHandle? = null
        var cancellationHandle: CancellationHandle? = null
        fun release() { if (job.workerDone.get() && job.callbackDone.get()) active.compareAndSet(job, null) }
        fun dispatch() {
            if (job.dispatched.compareAndSet(false, true)) {
                Thread({
                    try { callback(job.terminal.get()!!) } catch (_: Exception) { } catch (_: LinkageError) { }
                    finally { job.callbackDone.set(true); release() }
                }, "esv-commons-result").apply { isDaemon = true; start() }
            }
        }
        fun stop(code: CommonsError) {
            if (job.terminal.compareAndSet(null, CommonsOutcome.Failure(code))) {
                operation.cancellation.cancel(); job.thread.interrupt(); dispatch()
            }
        }
        val worker = Thread({
            val result = try {
                CommonsOutcome.Success(provider.search(request, operation))
            } catch (failure: CommonsFailure) { CommonsOutcome.Failure(failure.code, failure.retryAtEpochMillis)
            } catch (_: InterruptedException) { CommonsOutcome.Failure(CommonsError.CANCELLED)
            } catch (_: Exception) { CommonsOutcome.Failure(CommonsError.SERVICE_ERROR)
            } catch (_: LinkageError) { CommonsOutcome.Failure(CommonsError.SERVICE_ERROR)
            } finally { job.workerDone.set(true) }
            job.terminal.compareAndSet(null, result)
            deadlineHandle?.cancel(); cancellationHandle?.cancel()
            dispatch(); release()
        }, "esv-commons-metadata").apply { isDaemon = true }
        job = Job(worker)
        job.cancel = { stop(CommonsError.CANCELLED) }
        if (!active.compareAndSet(null, job)) { callback(CommonsOutcome.Failure(CommonsError.BUSY)); return CancellationHandle {} }
        if (closed.get()) {
            job.workerDone.set(true); stop(CommonsError.CANCELLED); return CancellationHandle {}
        }
        val delay = minOf(provider.requestDeadlineMillis, operation.context.deadlineEpochMillis - clock.nowMillis()).coerceAtLeast(0)
        try {
            cancellationHandle = operation.cancellation.onCancel { stop(CommonsError.CANCELLED) }
            deadlineHandle = timer.schedule(delay) { stop(CommonsError.DEADLINE_EXCEEDED) }
        } catch (_: Exception) {
            cancellationHandle?.cancel(); job.workerDone.set(true)
            stop(if (closed.get()) CommonsError.CANCELLED else CommonsError.SERVICE_ERROR)
            release(); return CancellationHandle {}
        }
        worker.start()
        return CancellationHandle { stop(CommonsError.CANCELLED); deadlineHandle?.cancel() }
    }
    override fun close() {
        closed.set(true)
        active.get()?.cancel?.invoke()
        if (ownsTimer) (timer as? AutoCloseable)?.close()
    }
}
