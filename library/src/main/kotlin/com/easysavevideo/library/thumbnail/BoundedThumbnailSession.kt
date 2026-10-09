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

/**
 * UI-thread state machine. Neither timeout nor cancellation asserts worker/native termination.
 * A timed-out job stays active until its completion callback; no replacement is submitted.
 * Decoder work NEVER occupies the ordinary library repository/command lane.
 */
class BoundedThumbnailSession<F : ThumbnailFrame>(
    private val decoder: ThumbnailDecoder<F>,
    private val worker: ThumbnailWorkLane,
    private val ui: ThumbnailUiDispatcher,
    private val onStalled: () -> Unit,
    private val limits: ThumbnailLimits = ThumbnailLimits(),
    onChanged: () -> Unit = {},
) : AutoCloseable {
    private data class Job(val reference: MediaReadRef, val generation: Long, val cancelled: AtomicBoolean = AtomicBoolean()) {
        var timeout: Runnable? = null
    }
    private val page = LinkedHashMap<MediaReadRef, MediaItem>()
    private val cache = LinkedHashMap<MediaReadRef, F>(16, 0.75f, true)
    private val states = mutableMapOf<MediaReadRef, ThumbnailState<F>>()
    private val queue = ArrayDeque<Job>()
    private var generation = 0L
    private var enabled = false
    private var disposed = false
    private var fenced = false
    private var active: Job? = null
    private var changed: (() -> Unit)? = onChanged
    var retainedBytes: Long = 0; private set
    val retainedCount: Int get() = cache.size
    val queuedCount: Int get() = queue.size
    val hasActiveJob: Boolean get() = active != null

    fun updatePage(items: List<MediaItem>, enabled: Boolean) {
        ui.requireCurrentThread()
        if (disposed) return
        require(items.size <= LibraryLimits.MAX_PAGE_SIZE)
        val next = items.filter { it.lifecycle == MediaLifecycle.READY }.associateBy { it.readRef() }
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

    fun state(item: MediaItem): ThumbnailState<F> {
        ui.requireCurrentThread()
        if (disposed || !enabled) return ThumbnailState.Unavailable(ThumbnailUnavailable.INACTIVE)
        if (item.lifecycle != MediaLifecycle.READY || page[item.readRef()] != item)
            return ThumbnailState.Unavailable(ThumbnailUnavailable.NOT_CURRENT)
        val ref = item.readRef()
        cache[ref]?.let { return ThumbnailState.Ready(it) }
        if (fenced) return ThumbnailState.Unavailable(ThumbnailUnavailable.RELEASE_UNCONFIRMED)
        return states[ref] ?: ThumbnailState.Waiting
    }

    fun request(item: MediaItem) {
        ui.requireCurrentThread()
        if (disposed || !enabled || fenced || item.lifecycle != MediaLifecycle.READY) return
        val ref = item.readRef()
        if (page[ref] != item || ref in cache || ref in states) return
        if (queue.size >= limits.maxQueued) {
            states[ref] = ThumbnailState.Unavailable(ThumbnailUnavailable.QUEUE_FULL)
        } else {
            states[ref] = ThumbnailState.Waiting
            queue.addLast(Job(ref, generation))
            pump()
        }
        changed?.invoke()
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
        val accepted = worker.submit(Runnable {
            val result = try { decoder.decode(job.reference) { job.cancelled.get() } }
            catch (_: Exception) { ThumbnailDecodeResult.ReleaseUnconfirmed }
            ui.post(Runnable { complete(job, result) })
        })
        if (!accepted) {
            ui.remove(timeout)
            active = null
            states[job.reference] = ThumbnailState.Unavailable(ThumbnailUnavailable.BUSY)
            pump()
        }
    }

    private fun complete(job: Job, result: ThumbnailDecodeResult<F>) {
        ui.requireCurrentThread()
        job.timeout?.let(ui::remove)
        if (result == ThumbnailDecodeResult.ReleaseUnconfirmed && !fenced) {
            fenced = true
            queue.clear(); states.clear()
            onStalled()
        }
        val frame = (result as? ThumbnailDecodeResult.Released<F>)?.frame
        val current = !disposed && enabled && !fenced && active === job && generation == job.generation &&
            !job.cancelled.get() && job.reference in page
        if (frame != null) {
            if (current && valid(frame)) cache(job.reference, frame)
            else {
                frame.close()
                if (current) states[job.reference] = ThumbnailState.Unavailable(ThumbnailUnavailable.DECODE_FAILED)
            }
        } else if (current) {
            val failure = (result as? ThumbnailDecodeResult.Released<F>)?.failure ?: ThumbnailUnavailable.RELEASE_UNCONFIRMED
            states[job.reference] = ThumbnailState.Unavailable(failure)
        }
        if (active === job) active = null
        changed?.invoke()
        pump()
    }

    private fun valid(frame: F): Boolean = frame.width in 1..limits.width && frame.height in 1..limits.height &&
        frame.width.toLong() * frame.height <= limits.maxPixels && frame.allocationBytes in 1..limits.maxFrameBytes

    private fun cache(ref: MediaReadRef, frame: F) {
        while (cache.isNotEmpty() && (cache.size >= limits.maxCacheEntries || retainedBytes + frame.allocationBytes > limits.maxCacheBytes)) {
            val oldest = cache.keys.first()
            removeCached(oldest)
            states[oldest] = ThumbnailState.Unavailable(ThumbnailUnavailable.CACHE_LIMIT)
        }
        cache[ref] = frame
        retainedBytes += frame.allocationBytes
        states.remove(ref)
    }

    private fun removeCached(ref: MediaReadRef) {
        cache.remove(ref)?.let { retainedBytes -= it.allocationBytes; it.close() }
    }

    override fun close() {
        ui.requireCurrentThread()
        if (disposed) return
        disposed = true; enabled = false; generation++
        active?.cancelled?.set(true)
        // Keep its watchdog: a hung job after disposal still needs a process-wide admission fence.
        queue.clear(); states.clear(); page.clear()
        cache.keys.toList().forEach(::removeCached)
        changed = null
    }
}
