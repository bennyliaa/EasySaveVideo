package com.easysavevideo.commons.maintenance

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.discovery.JvmDiscoveryTimer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Injection only. No production URL, HTTP adapter, account, or downloaded implementation exists. */
fun interface ProviderUpdateSource { fun create(): ProviderUpdateCall }
interface ProviderUpdateCall {
    /** Called once on the isolated worker. The body is a single signed binary object of the explicitly configured kind. */
    fun execute(): InputStream
    /** Must release transport resources. Even a broken/hung cancellation hook retains its slot. */
    fun cancel()
}
enum class ProviderUpdateError {
    SOURCE_UNAVAILABLE, SOURCE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, BUSY, CANCELLED, DEADLINE_EXCEEDED,
}
enum class ProviderUpdateKind { PACKAGE, REPOSITORY_POLICY }

sealed class ProviderUpdateOutcome {
    data class Completed(val result: ProviderRepositoryResult) : ProviderUpdateOutcome()
    data class Failure(val code: ProviderUpdateError) : ProviderUpdateOutcome()
}

/**
 * One physical source worker, one resource-cancellation hook and one result callback per admitted
 * request. All three retain the slot until they terminate, even after logical deadline/cancellation.
 * A hung source cannot prevent timely logical completion or leak workers through retries.
 *
 * Publication and terminal cancellation share a short, no-I/O gate only around in-memory activation,
 * never around source code or storage. Restored pending candidates are disabled until rejected.
 * If cancellation wins, no package may subsequently become usable. Signed bytes may remain as
 * disabled recovery/high-water evidence. If commit wins, cancellation cannot report a contradictory
 * cancelled result for that transaction.
 */
class ProviderUpdateRuntime(
    private val repository: ProviderRepository,
    private val source: ProviderUpdateSource? = null,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    timer: DiscoveryTimer? = null,
    private val maximumDurationMillis: Long = 15_000,
    private val kind: ProviderUpdateKind = ProviderUpdateKind.PACKAGE,
) : AutoCloseable {
    init { require(maximumDurationMillis in 1..15_000) { "INVALID_DEADLINE" } }
    private val ownsTimer = timer == null
    private val timer = timer ?: JvmDiscoveryTimer()
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Job?>()
    private class Job(val thread: Thread, val deadline: Long) {
        val publicationLock = Any()
        val resourceLock = Any()
        val terminal = AtomicReference<ProviderUpdateOutcome?>()
        val stopRequested = AtomicReference<ProviderUpdateError?>()
        val dispatched = AtomicBoolean(false)
        val workerDone = AtomicBoolean(false)
        val cancellationDone = AtomicBoolean(false)
        val callbackDone = AtomicBoolean(false)
        var call: ProviderUpdateCall? = null
        var creationFinished = false
        var cancelRequested = false
        var cancelStarted = false
        @Volatile var stop: (() -> Unit)? = null
        @Volatile var deadlineHandle: CancellationHandle? = null
    }
    private class Abort(val code: ProviderUpdateError) : RuntimeException(code.name, null, false, false)

    fun execute(deadlineEpochMillis: Long, callback: (ProviderUpdateOutcome) -> Unit): CancellationHandle {
        fun rejected(code: ProviderUpdateError): CancellationHandle {
            callback(ProviderUpdateOutcome.Failure(code)); return CancellationHandle {}
        }
        if (closed.get()) return rejected(ProviderUpdateError.CANCELLED)
        val actualSource = source ?: return rejected(ProviderUpdateError.SOURCE_UNAVAILABLE)
        val now = clock.nowMillis()
        val durationEnd = if (now > Long.MAX_VALUE - maximumDurationMillis) Long.MAX_VALUE else now + maximumDurationMillis
        val deadline = minOf(deadlineEpochMillis, durationEnd)
        if (deadline <= now) return rejected(ProviderUpdateError.DEADLINE_EXCEEDED)
        lateinit var job: Job
        fun release() {
            if (job.workerDone.get() && job.cancellationDone.get() && job.callbackDone.get()) active.compareAndSet(job, null)
        }
        fun dispatch() {
            if (job.dispatched.compareAndSet(false, true)) {
                Thread({
                    try { callback(job.terminal.get()!!) } catch (_: Exception) { } catch (_: LinkageError) { }
                    finally { job.callbackDone.set(true); release() }
                }, "esv-provider-update-result").apply { isDaemon = true; start() }
            }
        }
        fun cancelResource() {
            val call = synchronized(job.resourceLock) {
                job.cancelRequested = true
                if (job.call == null) {
                    if (job.creationFinished) job.cancellationDone.set(true)
                    null
                } else if (job.cancelStarted) null else {
                    job.cancelStarted = true
                    job.call
                }
            }
            if (call != null) Thread({
                try { call.cancel() } catch (_: Exception) { } catch (_: LinkageError) { }
                finally { job.cancellationDone.set(true); release() }
            }, "esv-provider-update-cancel").apply { isDaemon = true; start() }
            release()
        }
        fun stop(code: ProviderUpdateError) {
            job.stopRequested.compareAndSet(null, code)
            val won = synchronized(job.publicationLock) {
                job.terminal.compareAndSet(null, ProviderUpdateOutcome.Failure(code))
            }
            if (won) {
                job.thread.interrupt()
                cancelResource()
                job.deadlineHandle?.cancel()
                dispatch()
            }
        }
        fun check() {
            job.stopRequested.get()?.let { throw Abort(it) }
            if (job.terminal.get() != null || closed.get() || Thread.currentThread().isInterrupted)
                throw Abort(ProviderUpdateError.CANCELLED)
            if (clock.nowMillis() >= job.deadline) throw Abort(ProviderUpdateError.DEADLINE_EXCEEDED)
        }
        val worker = Thread({
            val outcome = try {
                check()
                val call = actualSource.create()
                val cancellationPending = synchronized(job.resourceLock) {
                    job.call = call; job.creationFinished = true; job.cancelRequested
                }
                if (cancellationPending) cancelResource()
                check()
                val bytes = call.execute().use { body ->
                    check()
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        check()
                        val count = body.read(buffer, 0, minOf(buffer.size, ProviderRepository.MAX_PACKAGE_BYTES - output.size() + 1))
                        check()
                        if (count < 0) break
                        if (count == 0) throw Abort(ProviderUpdateError.INVALID_RESPONSE)
                        if (count > buffer.size || output.size() + count > ProviderRepository.MAX_PACKAGE_BYTES)
                            throw Abort(ProviderUpdateError.RESPONSE_TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                    if (output.size() == 0) throw Abort(ProviderUpdateError.INVALID_RESPONSE)
                    output.toByteArray()
                }
                check()
                val installer: (ByteArray, () -> Unit, (() -> ProviderRepositoryResult) -> ProviderRepositoryResult) -> ProviderRepositoryResult =
                    when (kind) {
                        ProviderUpdateKind.PACKAGE -> repository::installGuarded
                        ProviderUpdateKind.REPOSITORY_POLICY -> repository::installPolicyGuarded
                    }
                val installed = installer(bytes, ::check) { publish ->
                    synchronized(job.publicationLock) {
                        check()
                        val result = publish()
                        job.terminal.compareAndSet(null, ProviderUpdateOutcome.Completed(result))
                        result
                    }
                }
                if (installed is ProviderRepositoryResult.Failure && installed.code == ProviderRepositoryError.CANCELLED) {
                    check()
                    throw Abort(ProviderUpdateError.CANCELLED)
                }
                ProviderUpdateOutcome.Completed(installed)
            } catch (failure: Abort) {
                ProviderUpdateOutcome.Failure(failure.code)
            } catch (_: InterruptedException) {
                ProviderUpdateOutcome.Failure(ProviderUpdateError.CANCELLED)
            } catch (_: Exception) {
                ProviderUpdateOutcome.Failure(ProviderUpdateError.SOURCE_FAILURE)
            } catch (_: LinkageError) {
                ProviderUpdateOutcome.Failure(ProviderUpdateError.SOURCE_FAILURE)
            } finally {
                synchronized(job.resourceLock) { job.creationFinished = true }
                cancelResource()
                job.workerDone.set(true)
            }
            synchronized(job.publicationLock) { job.terminal.compareAndSet(null, outcome) }
            job.deadlineHandle?.cancel()
            dispatch(); release()
        }, "esv-provider-update").apply { isDaemon = true }
        job = Job(worker, deadline)
        job.stop = { stop(ProviderUpdateError.CANCELLED) }
        if (!active.compareAndSet(null, job)) return rejected(ProviderUpdateError.BUSY)
        if (closed.get()) {
            synchronized(job.resourceLock) { job.creationFinished = true }
            job.workerDone.set(true)
            stop(ProviderUpdateError.CANCELLED)
            cancelResource(); release()
            return CancellationHandle {}
        }
        try {
            job.deadlineHandle = timer.schedule((deadline - clock.nowMillis()).coerceAtLeast(0)) { stop(ProviderUpdateError.DEADLINE_EXCEEDED) }
        } catch (_: Exception) {
            synchronized(job.resourceLock) { job.creationFinished = true }
            job.workerDone.set(true)
            stop(ProviderUpdateError.SOURCE_FAILURE)
            cancelResource(); release()
            return CancellationHandle {}
        }
        worker.start()
        return CancellationHandle { stop(ProviderUpdateError.CANCELLED) }
    }

    override fun close() {
        closed.set(true)
        active.get()?.stop?.invoke()
        if (ownsTimer) (timer as? AutoCloseable)?.close()
    }
    override fun toString(): String = "ProviderUpdateRuntime([redacted])"
}
