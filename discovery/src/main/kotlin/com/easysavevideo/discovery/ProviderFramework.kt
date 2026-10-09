package com.easysavevideo.discovery

import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface CancellationHandle { fun cancel() }

/** Cancellation signals immediately; hooks run off caller/timer threads. Hooks must return promptly. */
class DiscoveryCancellation : CancellationToken {
    private val lock = Any()
    private val callbacks = mutableListOf<() -> Unit>()
    private val signals = mutableListOf<() -> Unit>()
    @Volatile internal var isDrained = false
        private set
    @Volatile internal var drained: (() -> Unit)? = null
    @Volatile override var isCancelled: Boolean = false
        private set
    fun onCancel(callback: () -> Unit): CancellationHandle {
        val immediately = synchronized(lock) {
            if (isCancelled) true else {
                if (callbacks.size >= 8) throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
                callbacks += callback; false
            }
        }
        if (immediately) safely(callback)
        return CancellationHandle { synchronized(lock) { callbacks.remove(callback) } }
    }
    /** Trusted runtime-only signal handlers must never block or invoke provider code. */
    internal fun onSignal(callback: () -> Unit): CancellationHandle {
        val immediately = synchronized(lock) { if (isCancelled) true else { signals += callback; false } }
        if (immediately) callback()
        return CancellationHandle { synchronized(lock) { signals.remove(callback) } }
    }
    internal fun releaseCompletedAttempt(): Boolean = synchronized(lock) {
        if (isCancelled) false else { callbacks.clear(); true }
    }
    fun cancel() {
        val (pendingSignals, pending) = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            (signals.toList() to callbacks.toList()).also { signals.clear(); callbacks.clear() }
        }
        pendingSignals.forEach { it() }
        if (pending.isEmpty()) { isDrained = true; drained?.invoke(); return }
        // At most eight registered resource hooks per operation; a stuck hook cannot block another
        // attempt's transport cancellation or a sibling source's deadline. Runtime slots remain held
        // until every hook and worker exits, preventing repeated retries from leaking more threads.
        val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
        pending.forEach { callback -> Thread({
            try { safely(callback) } finally { if (remaining.decrementAndGet() == 0) { isDrained = true; drained?.invoke() } }
        }, "esv-discovery-cancel").apply { isDaemon = true; start() } }
    }
    private fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { } catch (_: LinkageError) { } }
}

data class SearchRequest(val stamp: SessionStamp, val profile: SearchProfile, val progress: ProviderProgress?) {
    override fun toString(): String = "SearchRequest([redacted])"
}
data class DiscoveryOperation(val context: OperationContext, val cancellation: DiscoveryCancellation) {
    init { require(context.cancelToken === cancellation) }
    fun check(nowMillis: Long) {
        if (cancellation.isCancelled) throw DiscoveryFailure(DiscoveryError.CANCELLED)
        if (nowMillis >= context.deadlineEpochMillis) throw DiscoveryFailure(DiscoveryError.DEADLINE_EXCEEDED)
    }
}

data class ProviderPage(
    val results: List<RawVideoResult> = emptyList(),
    val candidates: List<CandidateUrl> = emptyList(),
    val nextCursor: String? = null,
    val cursorExpiresAtEpochMillis: Long? = null,
    val exhausted: Boolean = false,
    val responseBytes: Long = 0,
) { override fun toString(): String = "ProviderPage([redacted])" }

/**
 * No production implementation is registered by default. Implementations must enforce TLS, declared hosts,
 * every redirect and resolved address, 5s connect timeout and the supplied body/deadline/cancel budget.
 * Provider data never carries command, filesystem, playback or SaveEngine authority.
 */
interface VideoSearchProvider {
    val descriptor: ProviderDescriptor
    /** A deterministic, no-I/O capability refusal. It never changes the requested filters. */
    fun refusal(profile: SearchProfile): DiscoveryError? = null
    fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage
}

class ProviderRegistry(providers: List<VideoSearchProvider> = emptyList()) {
    private val providers = linkedMapOf<String, VideoSearchProvider>()
    init { require(providers.size <= 32); providers.forEach { register(it) } }
    @Synchronized fun register(provider: VideoSearchProvider) {
        require(providers.size < 32 || provider.descriptor.id in providers)
        require(provider.descriptor.id !in providers) { "Duplicate provider ID" }
        providers[provider.descriptor.id] = provider
    }
    @Synchronized fun remove(providerId: String): VideoSearchProvider? = providers.remove(providerId)
    @Synchronized fun all(): List<VideoSearchProvider> = providers.values.toList()
    @Synchronized fun find(providerId: String): VideoSearchProvider? = providers[providerId]
}

interface DiscoveryTimer { fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle }
class JvmDiscoveryTimer : DiscoveryTimer, AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "esv-discovery-timer").apply { isDaemon = true } }
    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
        val future = executor.schedule(action, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return CancellationHandle { future.cancel(false) }
    }
    override fun close() { executor.shutdownNow() }
}

sealed class ProviderOutcome {
    data class Success(val page: ValidatedProviderPage) : ProviderOutcome()
    data class Failure(val code: DiscoveryError, val retryable: Boolean = false, val retryAtEpochMillis: Long? = null) : ProviderOutcome()
}
interface ProviderRuntime {
    fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                callback: (ProviderOutcome) -> Unit): CancellationHandle
}

/**
 * Synchronous/uncooperative providers are isolated on at most one daemon thread per registered ID.
 * Timeout reports immediately; a stuck worker retains its source and global budget slots until it really exits. Java cannot
 * safely kill such work. A platform/network adapter still must implement actual resource cancellation.
 */
class IsolatedProviderRuntime(
    private val pipeline: ValidationPipeline,
    private val timer: DiscoveryTimer,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
) : ProviderRuntime, AutoCloseable {
    private data class Running(val operation: DiscoveryOperation, val thread: Thread,
        val workerFinished: AtomicBoolean = AtomicBoolean(false), val callbackFinished: AtomicBoolean = AtomicBoolean(false))
    private val running = ConcurrentHashMap<String, Running>()
    private val closed = AtomicBoolean(false)
    override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                         callback: (ProviderOutcome) -> Unit): CancellationHandle {
        if (closed.get()) { callback(ProviderOutcome.Failure(DiscoveryError.CANCELLED)); return CancellationHandle {} }
        val terminal = AtomicReference<ProviderOutcome?>()
        val delivered = AtomicBoolean(false)
        var deadline: CancellationHandle? = null
        var signal: CancellationHandle? = null
        lateinit var job: Running
        fun releaseCancelledSlot() {
            if (job.workerFinished.get() && job.callbackFinished.get() && operation.cancellation.isDrained)
                running.remove(provider.descriptor.id, job)
        }
        fun deliver(outcome: ProviderOutcome) {
            if (delivered.compareAndSet(false, true)) {
                try { callback(outcome) } catch (_: Exception) { } catch (_: LinkageError) { } finally { job.callbackFinished.set(true); releaseCancelledSlot() }
            }
        }
        val thread = Thread({
            val outcome = try {
                operation.check(clock.nowMillis())
                val raw = provider.search(request, operation)
                operation.check(clock.nowMillis())
                ProviderOutcome.Success(pipeline.validate(provider.descriptor.id, raw, request.profile.filters, operation))
            } catch (failure: DiscoveryFailure) { ProviderOutcome.Failure(failure.code, failure.retryable, failure.retryAtEpochMillis)
            } catch (_: InterruptedException) { ProviderOutcome.Failure(DiscoveryError.CANCELLED)
            } catch (_: Exception) { ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE)
            } catch (_: LinkageError) { ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE)
            } finally {
                val completedBeforeCancellation = operation.cancellation.releaseCompletedAttempt()
                job.workerFinished.set(true)
                if (completedBeforeCancellation) running.remove(provider.descriptor.id, job) else releaseCancelledSlot()
            }
            signal?.cancel(); deadline?.cancel()
            terminal.compareAndSet(null, outcome)
            deliver(terminal.get()!!)
        }, "esv-provider-${provider.descriptor.id}").apply { isDaemon = true }
        job = Running(operation, thread)
        if (synchronized(running) { running.size >= minOf(3, operation.context.resourceBudget.maxConcurrentWork) || running.putIfAbsent(provider.descriptor.id, job) != null }) {
            callback(ProviderOutcome.Failure(DiscoveryError.BUSY, retryable = true)); return CancellationHandle {}
        }
        operation.cancellation.drained = { releaseCancelledSlot() }
        signal = operation.cancellation.onSignal {
            thread.interrupt()
            terminal.compareAndSet(null, ProviderOutcome.Failure(DiscoveryError.CANCELLED))
            val outcome = terminal.get()!!
            if (outcome is ProviderOutcome.Failure && outcome.code in setOf(DiscoveryError.CANCELLED, DiscoveryError.DEADLINE_EXCEEDED)) {
                Thread({ deliver(outcome) }, "esv-discovery-completion").apply { isDaemon = true; start() }
            }
        }
        fun stop(code: DiscoveryError) {
            if (terminal.compareAndSet(null, ProviderOutcome.Failure(code))) {
                operation.cancellation.cancel(); thread.interrupt()
            }
        }
        deadline = timer.schedule(operation.context.deadlineEpochMillis - clock.nowMillis()) { stop(DiscoveryError.DEADLINE_EXCEEDED) }
        thread.start()
        return CancellationHandle { stop(DiscoveryError.CANCELLED); deadline?.cancel() }
    }
    override fun close() {
        closed.set(true)
        running.values.forEach { it.operation.cancellation.cancel(); it.thread.interrupt() }
    }
}
