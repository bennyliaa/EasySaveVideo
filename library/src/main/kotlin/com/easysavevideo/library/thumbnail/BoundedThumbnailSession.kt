package com.easysavevideo.library.thumbnail

import com.easysavevideo.library.LibraryLimits
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.library.MediaReadRef
import java.util.concurrent.atomic.AtomicBoolean

/** Pixel, retained-memory, and pending-work limits are independent of the catalog page size. */
data class ThumbnailLimits(
    val width: Int = 256,
    val height: Int = 144,
    val maxPixels: Long = 36_864,
    val maxFrameBytes: Long = 147_456,
    val maxCacheBytes: Long = 2_359_296,
    val maxCacheEntries: Int = 16,
    val maxQueued: Int = 16,
    val timeoutMillis: Long = 3_000,
) {
    init {
        require(width in 1..256 && height in 1..256 && width.toLong() * height <= maxPixels)
        require(maxPixels in 1..65_536 && maxFrameBytes in 1..262_144)
        require(maxCacheEntries in 1..16 && maxCacheBytes in maxFrameBytes..2_359_296)
        require(maxQueued in 1..16 && timeoutMillis in 1..10_000)
    }
}

/** close must be idempotent. A displayed handle must remain safe to draw after close (no image). */
interface ThumbnailFrame : AutoCloseable {
    val width: Int
    val height: Int
    val allocationBytes: Long
    override fun close()
}

enum class ThumbnailUnavailable { INACTIVE, NOT_CURRENT, BUSY, QUEUE_FULL, CACHE_LIMIT, DECODE_FAILED, RELEASE_UNCONFIRMED }
sealed interface ThumbnailState<out F : ThumbnailFrame> {
    data object Waiting : ThumbnailState<Nothing>
    data class Ready<F : ThumbnailFrame>(val frame: F) : ThumbnailState<F>
    data class Unavailable(val reason: ThumbnailUnavailable) : ThumbnailState<Nothing>
}

/** Confirmed means all native handles and both read authorities were actually released. */
sealed interface ThumbnailDecodeResult<out F : ThumbnailFrame> {
    data class Released<F : ThumbnailFrame>(val frame: F?, val failure: ThumbnailUnavailable = ThumbnailUnavailable.DECODE_FAILED) : ThumbnailDecodeResult<F>
    data object ReleaseUnconfirmed : ThumbnailDecodeResult<Nothing>
}

fun interface ThumbnailDecoder<F : ThumbnailFrame> {
    fun decode(reference: MediaReadRef, cancelled: () -> Boolean): ThumbnailDecodeResult<F>
}
fun interface ThumbnailWorkLane { fun submit(task: Runnable): Boolean }
interface ThumbnailUiDispatcher {
    fun requireCurrentThread()
    fun post(task: Runnable)
    fun later(task: Runnable, delayMillis: Long)
    fun remove(task: Runnable)
}

/** Identity is only a presentation key; the decoder owns all read authority. */
interface ThumbnailItemAdapter<K : Any, I : Any> {
    val maxPageSize: Int get() = LibraryLimits.MAX_PAGE_SIZE
    fun key(item: I): K
    fun isEligible(item: I): Boolean
    fun sameRetiredIdentity(first: K, second: K): Boolean = first == second
}
enum class ThumbnailStartResult { ACCEPTED, REJECTED, UNKNOWN }
fun interface AsyncThumbnailDecoder<K : Any, F : ThumbnailFrame> {
    /** REJECTED proves no acquisition/callback. UNKNOWN retains the exact job. */
    fun start(reference: K, cancelled: () -> Boolean,
        complete: (ThumbnailDecodeResult<F>, settled: () -> Unit) -> Unit): ThumbnailStartResult
}

/** Source-compatible ordinary synchronous facade over the same typed bounded algorithm. */
class BoundedThumbnailSession<F : ThumbnailFrame>(
    decoder: ThumbnailDecoder<F>, worker: ThumbnailWorkLane, ui: ThumbnailUiDispatcher,
    onStalled: () -> Unit, limits: ThumbnailLimits = ThumbnailLimits(), onChanged: () -> Unit = {},
) : AutoCloseable {
    private val delegate = TypedBoundedThumbnailSession(
        AsyncThumbnailDecoder<MediaReadRef, F> { reference, cancelled, complete ->
            val result = try { decoder.decode(reference, cancelled) }
                catch (_: Exception) { ThumbnailDecodeResult.ReleaseUnconfirmed }
            complete(result, {})
            ThumbnailStartResult.ACCEPTED
        }, worker, ui, onStalled, object : ThumbnailItemAdapter<MediaReadRef, MediaItem> {
            override fun key(item: MediaItem) = item.readRef()
            override fun isEligible(item: MediaItem) = item.lifecycle == MediaLifecycle.READY
            override fun sameRetiredIdentity(first: MediaReadRef, second: MediaReadRef) =
                first.itemId == second.itemId && first.representationGeneration == second.representationGeneration
        }, limits, onChanged)
    val retainedBytes get() = delegate.retainedBytes
    val retainedCount get() = delegate.retainedCount
    val queuedCount get() = delegate.queuedCount
    val hasActiveJob get() = delegate.hasActiveJob && !delegate.hasUnconfirmedResources
    val hasUnconfirmedResources get() = delegate.hasUnconfirmedResources
    fun updatePage(items: List<MediaItem>, enabled: Boolean) = delegate.updatePage(items, enabled)
    fun state(item: MediaItem) = delegate.state(item)
    fun request(item: MediaItem) = delegate.request(item)
    fun cancel(item: MediaItem) = delegate.cancel(item)
    fun retireExact(reference: MediaReadRef, onActuallyRetired: () -> Unit) = delegate.retireExact(reference, onActuallyRetired)
    override fun close() = delegate.close()
}

/**
 * UI-thread state machine. Neither timeout nor cancellation asserts worker/native termination.
 * A timed-out job stays active until its completion callback; no replacement is submitted.
 * Decoder work NEVER occupies the ordinary library repository/command lane.
 */
class TypedBoundedThumbnailSession<K : Any, I : Any, F : ThumbnailFrame>(
    private val decoder: AsyncThumbnailDecoder<K, F>,
    private val worker: ThumbnailWorkLane,
    private val ui: ThumbnailUiDispatcher,
    private val onStalled: () -> Unit,
    private val adapter: ThumbnailItemAdapter<K, I>,
    private val limits: ThumbnailLimits = ThumbnailLimits(),
    onChanged: () -> Unit = {},
) : AutoCloseable {
    private class Job<K : Any, F : ThumbnailFrame>(val reference: K, val generation: Long, val cancelled: AtomicBoolean = AtomicBoolean()) {
        var timeout: Runnable? = null
        val started = AtomicBoolean()
        val resultClaimed = AtomicBoolean()
        val deliveryClaimed = AtomicBoolean()
        val settlementClaimed = AtomicBoolean()
        @Volatile var result: ThumbnailDecodeResult<F>? = null
        @Volatile var uncertain = false
        val orphanFrames = mutableListOf<F>()
    }
    private val page = LinkedHashMap<K, I>()
    private val cache = LinkedHashMap<K, F>(16, 0.75f, true)
    private val states = mutableMapOf<K, ThumbnailState<F>>()
    private val queue = ArrayDeque<Job<K, F>>()
    // Exact source retirement is stronger than offscreen cancellation. A single media workflow
    // can fence one exact reference for this session; caller owns any future refreshed session.
    private var retiredReference: K? = null
    private var retirement: (() -> Unit)? = null
    private var unconfirmedReference: K? = null
    private val unconfirmedFrames = linkedSetOf<K>()
    private val retainedUnconfirmedFrames = mutableListOf<Pair<K, F>>()
    private val retainedCallbackJobs = mutableListOf<Job<K, F>>()
    private var generation = 0L
    private var enabled = false
    private var disposed = false
    @Volatile private var fenced = false
    private var active: Job<K, F>? = null
    private var changed: (() -> Unit)? = onChanged
    var retainedBytes: Long = 0; private set
    val retainedCount: Int get() = cache.size + retainedUnconfirmedFrames.size
    val queuedCount: Int get() = queue.size
    val hasActiveJob: Boolean get() = active != null
    val hasUnconfirmedResources: Boolean get() = unconfirmedReference != null || unconfirmedFrames.isNotEmpty() || active?.uncertain == true || synchronized(retainedCallbackJobs) { retainedCallbackJobs.isNotEmpty() }

    fun updatePage(items: List<I>, enabled: Boolean) {
        ui.requireCurrentThread()
        if (disposed) return
        require(items.size <= adapter.maxPageSize)
        val next = items.filter { adapter.isEligible(it) && !isRetired(adapter.key(it)) }.associateBy { adapter.key(it) }
        if (this.enabled == enabled && page == next) return
        generation++
        this.enabled = enabled
        active?.cancelled?.set(true)
        queue.clear()
        states.clear()
        page.clear(); page.putAll(next)
        val obsolete = cache.keys.filter { !enabled || it !in page }
        obsolete.forEach(::removeCached)
        changed?.invoke()
    }

    fun state(item: I): ThumbnailState<F> {
        ui.requireCurrentThread()
        if (disposed || !enabled) return ThumbnailState.Unavailable(ThumbnailUnavailable.INACTIVE)
        if (!adapter.isEligible(item) || page[adapter.key(item)] != item)
            return ThumbnailState.Unavailable(ThumbnailUnavailable.NOT_CURRENT)
        val ref = adapter.key(item)
        cache[ref]?.let { return ThumbnailState.Ready(it) }
        if (fenced) return ThumbnailState.Unavailable(ThumbnailUnavailable.RELEASE_UNCONFIRMED)
        return states[ref] ?: ThumbnailState.Waiting
    }

    fun request(item: I) {
        ui.requireCurrentThread()
        if (disposed || !enabled || fenced || !adapter.isEligible(item)) return
        val ref = adapter.key(item)
        if (page[ref] != item || ref in cache || ref in states) return
        if (queue.size >= limits.maxQueued) {
            states[ref] = ThumbnailState.Unavailable(ThumbnailUnavailable.QUEUE_FULL)
        } else {
            states[ref] = ThumbnailState.Waiting
            queue.addLast(Job<K, F>(ref, generation))
            pump()
        }
        changed?.invoke()
    }

    /** Offscreen cancellation drops queued work; active native ownership lasts until real completion. */
    fun cancel(item: I) {
        ui.requireCurrentThread()
        if (disposed || !adapter.isEligible(item)) return
        val ref = adapter.key(item)
        val removed = queue.removeAll { it.reference == ref }
        val stateRemoved = states.remove(ref) != null
        val cancelled = active?.takeIf { it.reference == ref }?.cancelled?.getAndSet(true) == false
        // Retain completed frames only in the existing bounded page LRU; do not pretend release.
        if (removed || stateRemoved || cancelled) changed?.invoke()
    }

    /**
     * Purges this exact page/cache authority immediately. ACK means the app-owned frame was
     * closed and the exact native worker reported Released, never that GPU memory was erased.
     * A native ReleaseUnconfirmed result keeps the callback pending even after disposal.
     */
    fun retireExact(reference: K, onActuallyRetired: () -> Unit) {
        ui.requireCurrentThread()
        if (retirement != null) {
            require(isRetired(reference))
            retirement = onActuallyRetired // Same exact source, replacement host observer.
            acknowledgeRetirement()
            return
        }
        retiredReference = reference
        retirement = onActuallyRetired
        page.keys.filter(::isRetired).forEach(page::remove)
        queue.removeAll { isRetired(it.reference) }
        states.keys.filter(::isRetired).forEach(states::remove)
        cache.keys.filter(::isRetired).forEach(::removeCached)
        active?.takeIf { isRetired(it.reference) }?.cancelled?.set(true)
        changed?.invoke()
        acknowledgeRetirement()
    }

    private fun isRetired(reference: K): Boolean = retiredReference?.let {
        adapter.sameRetiredIdentity(it, reference)
    } == true

    private fun acknowledgeRetirement() {
        if (retiredReference == null) return
        if (unconfirmedReference?.let(::isRetired) == true || unconfirmedFrames.any(::isRetired) || active?.reference?.let(::isRetired) == true ||
            cache.keys.any(::isRetired) || page.keys.any(::isRetired) || queue.any { isRetired(it.reference) }) return
        val callback = retirement ?: return
        retirement = null
        callback()
    }

    private fun pump() {
        if (disposed || !enabled || fenced || active != null) return
        val job = queue.removeFirstOrNull() ?: return
        active = job
        val timeout = Runnable {
            if (active === job && !fenced) {
                job.cancelled.set(true)
                fenced = true
                queue.clear()
                states.clear()
                // The adapter keeps the same native owner/descriptor. There is no worker replacement.
                onStalled()
                changed?.invoke()
            }
        }
        job.timeout = timeout
        ui.later(timeout, limits.timeoutMillis)
        val task = Runnable {
            if (!job.started.compareAndSet(false, true)) return@Runnable
            val start = try {
                decoder.start(job.reference, { job.cancelled.get() }) { result, settled ->
                    receive(job, result, settled)
                }
            } catch (_: Exception) { ThumbnailStartResult.UNKNOWN }
            if (start == ThumbnailStartResult.REJECTED && !job.resultClaimed.get()) {
                receive(job, ThumbnailDecodeResult.Released(null, ThumbnailUnavailable.BUSY), {})
            } else if (start == ThumbnailStartResult.UNKNOWN && !job.deliveryClaimed.get()) {
                job.uncertain = true // No replacement; an accepted async callback may still arrive.
            }
        }
        val accepted = try { worker.submit(task) } catch (_: Exception) {
            // An executor can run/enqueue then throw; no rejection receipt means retained UNKNOWN.
            job.uncertain = true
            true
        }
        if (!accepted && !job.started.get() && !job.resultClaimed.get()) {
            ui.remove(timeout)
            active = null
            states[job.reference] = ThumbnailState.Unavailable(ThumbnailUnavailable.BUSY)
            pump()
        } else if (!accepted) {
            job.uncertain = true // A dishonest execute-then-reject cannot erase acquired ownership.
        }
    }

    private fun receive(job: Job<K, F>, result: ThumbnailDecodeResult<F>, settled: () -> Unit) {
        synchronized(job) {
            if (!job.resultClaimed.compareAndSet(false, true)) {
                val frame = (result as? ThumbnailDecodeResult.Released<F>)?.frame
                val adopted = (job.result as? ThumbnailDecodeResult.Released<F>)?.frame
                if (frame != null && frame !== adopted && job.orphanFrames.none { it === frame }) {
                    // Claim this never-adopted orphan before any external close, including failure.
                    job.orphanFrames.add(frame)
                    try { frame.close() } catch (_: Exception) {
                        job.uncertain = true; fenced = true
                        synchronized(retainedCallbackJobs) {
                            if (retainedCallbackJobs.none { it === job }) retainedCallbackJobs.add(job)
                        }
                        try { ui.post(Runnable { onStalled(); changed?.invoke() }) } catch (_: Exception) { }
                    }
                }
                return
            }
            job.result = result // The exact result is strongly retained before the Main handoff.
        }
        try {
            ui.post(Runnable {
                if (!job.deliveryClaimed.compareAndSet(false, true)) return@Runnable
                try {
                    complete(job, result)
                    if (result is ThumbnailDecodeResult.Released) {
                        if (job.uncertain || unconfirmedFrames.isNotEmpty()) {
                            job.uncertain = true
                            fenced = true
                            return@Runnable
                        }
                        if (job.settlementClaimed.compareAndSet(false, true)) settled()
                    }
                    // The active obligation covers frame transfer AND actual consumer settlement.
                    // Reentrant observers cannot pump a replacement before this exact receipt.
                    if (active === job) active = null
                    acknowledgeRetirement()
                    changed?.invoke()
                    pump()
                } catch (_: Exception) {
                    job.uncertain = true
                    fenced = true
                    // Result, frame and settlement stay owned; never invoke settlement twice.
                    synchronized(retainedCallbackJobs) {
                        if (retainedCallbackJobs.none { it === job }) retainedCallbackJobs.add(job)
                    }
                    try { onStalled(); changed?.invoke() } catch (_: Exception) { }
                }
            })
        } catch (_: Exception) {
            // execute-then-throw is already settled if delivery won. Otherwise keep this exact job.
            if (!job.deliveryClaimed.get()) job.uncertain = true
        }
    }

    private fun complete(job: Job<K, F>, result: ThumbnailDecodeResult<F>) {
        ui.requireCurrentThread()
        job.timeout?.let(ui::remove)
        if (result == ThumbnailDecodeResult.ReleaseUnconfirmed) unconfirmedReference = job.reference
        if (result == ThumbnailDecodeResult.ReleaseUnconfirmed && !fenced) {
            fenced = true
            queue.clear(); states.clear()
            onStalled()
        }
        val frame = (result as? ThumbnailDecodeResult.Released<F>)?.frame
        val current = !disposed && enabled && !fenced && !job.uncertain && active === job && generation == job.generation &&
            !job.cancelled.get() && job.reference in page
        if (frame != null) {
            if (current && valid(frame)) {
                try { cache(job.reference, frame) } catch (_: Exception) {
                    // Eviction can fail before this new frame was inserted. Keep or really
                    // close this incoming owner too; the failed old frame stays in cache.
                    discardFrame(job.reference, frame)
                }
            } else {
                discardFrame(job.reference, frame)
                if (current && !fenced) states[job.reference] = ThumbnailState.Unavailable(ThumbnailUnavailable.DECODE_FAILED)
            }
        } else if (current) {
            val failure = (result as? ThumbnailDecodeResult.Released<F>)?.failure ?: ThumbnailUnavailable.RELEASE_UNCONFIRMED
            states[job.reference] = ThumbnailState.Unavailable(failure)
        }
    }

    private fun discardFrame(reference: K, frame: F) {
        try { frame.close() } catch (_: Exception) {
            retainedUnconfirmedFrames.add(reference to frame)
            retainedBytes += frame.allocationBytes
            unconfirmedFrames.add(reference); fenced = true
            queue.clear(); states.clear(); onStalled()
        }
    }

    private fun valid(frame: F): Boolean = frame.width in 1..limits.width && frame.height in 1..limits.height &&
        frame.width.toLong() * frame.height <= limits.maxPixels && frame.allocationBytes in 1..limits.maxFrameBytes

    private fun cache(ref: K, frame: F) {
        while (cache.isNotEmpty() && (cache.size >= limits.maxCacheEntries || retainedBytes + frame.allocationBytes > limits.maxCacheBytes)) {
            val oldest = cache.keys.first()
            removeCached(oldest)
            states[oldest] = ThumbnailState.Unavailable(ThumbnailUnavailable.CACHE_LIMIT)
        }
        cache[ref] = frame
        retainedBytes += frame.allocationBytes
        states.remove(ref)
    }

    private fun removeCached(ref: K) {
        cache[ref]?.let {
            check(ref !in unconfirmedFrames) { "THUMBNAIL_RELEASE_UNCONFIRMED" }
            try { it.close() } catch (failure: Exception) {
                unconfirmedFrames.add(ref); fenced = true; onStalled()
                throw failure
            }
            cache.remove(ref); retainedBytes -= it.allocationBytes
        }
    }

    override fun close() {
        ui.requireCurrentThread()
        if (disposed) return
        disposed = true; enabled = false; generation++
        active?.cancelled?.set(true)
        // Keep its watchdog: a hung job after disposal still needs a process-wide admission fence.
        queue.clear(); states.clear(); page.clear()
        cache.keys.toList().forEach(::removeCached)
        acknowledgeRetirement()
        changed = null
    }
}
