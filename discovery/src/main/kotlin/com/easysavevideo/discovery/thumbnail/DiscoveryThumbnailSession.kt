package com.easysavevideo.discovery.thumbnail

import com.easysavevideo.discovery.SessionStamp
import com.easysavevideo.discovery.VideoSearchResult
import java.util.concurrent.atomic.AtomicBoolean

fun interface DiscoveryThumbnailLane {
    /** false means task will never run; true transfers exactly-once execution to this dedicated lane. */
    fun submit(task: Runnable): Boolean
}
/** Trusted UI loop. later/remove must be reliable; later never runs inline and post marshals to
 * the same owner thread. post must not silently drop tasks, including after session.close.
 * remove is idempotent. A post that fails before transferring ownership cannot admit another read; its watchdog fences it.
 */
interface DiscoveryThumbnailDispatcher {
    fun requireCurrentThread()
    fun post(task: Runnable)
    fun later(task: Runnable, delayMillis: Long)
    fun remove(task: Runnable)
}
enum class DiscoveryThumbnailStatus { INACTIVE, NOT_VISIBLE, NOT_REQUESTED, WAITING, DELIVERED, UNAVAILABLE, BUSY, QUEUE_FULL, FENCED }

/** UI-thread controller. No image cache, network access, retry loop or UI integration.
 * onFrame borrows one bounded frame synchronously; it is ALWAYS closed when the callback returns.
 * A future renderer must use an admitted bounded owner, not retain this borrowed object.
 * Timeouts/cancellation never assert native termination or start a replacement owner.
 * The dedicated lane must not be the provider/network/library command lane. onStalled must
 * revoke shared reader admission before any future session is created (as in Library's lane).
 */
class DiscoveryThumbnailSession(
    private val budget: DiscoveryThumbnailBudget,
    private val lane: DiscoveryThumbnailLane,
    private val dispatcher: DiscoveryThumbnailDispatcher,
    private val onStalled: () -> Unit,
    onFrame: (DiscoveryThumbnailBinding, DiscoveryThumbnailFrame) -> Unit,
) : AutoCloseable {
    private class Job(val capability: DiscoveryThumbnailCapability) {
        val cancelled = AtomicBoolean()
        val started = AtomicBoolean()
        var completed = false
        var timer: Runnable? = null
    }
    private var consumer: ((DiscoveryThumbnailBinding, DiscoveryThumbnailFrame) -> Unit)? = onFrame
    private var page = emptyMap<String, DiscoveryThumbnailBinding>()
    private var visible = emptySet<String>()
    private val states = mutableMapOf<String, DiscoveryThumbnailStatus>()
    private val queue = ArrayDeque<Job>()
    private var foreground = false
    private var closed = false
    private var fenced = false
    private var active: Job? = null
    val queuedCount: Int get() = queue.size
    /** Read/callback ownership outstanding; false does NOT certify release if status is FENCED. */
    val hasActiveRead: Boolean get() = active != null

    fun updatePage(session: SessionStamp, pageRevision: Long, results: List<VideoSearchResult>, foreground: Boolean) {
        dispatcher.requireCurrentThread()
        if (closed) return
        require(results.size <= 100 && results.map { it.resultId }.distinct().size == results.size)
        require(pageRevision > 0)
        val next = results.associate { it.resultId to DiscoveryThumbnailBinding.of(session, pageRevision, it) }
        if (page == next && this.foreground == foreground) return
        cancelAll()
        page = next
        this.foreground = foreground
        // New page/session/background transitions require a fresh viewport report.
        visible = emptySet()
    }

    fun setVisible(resultIds: Set<String>) {
        dispatcher.requireCurrentThread()
        if (closed) return
        require(resultIds.size <= budget.maxVisible && resultIds.all { it in page })
        val next = if (foreground) resultIds.toSet() else emptySet()
        val removed = visible - next
        queue.removeAll { it.capability.binding.resultId in removed }
        active?.takeIf { it.capability.binding.resultId in removed }?.cancelled?.set(true)
        removed.forEach(states::remove)
        visible = next
    }

    fun status(binding: DiscoveryThumbnailBinding): DiscoveryThumbnailStatus {
        dispatcher.requireCurrentThread()
        if (closed || !foreground) return DiscoveryThumbnailStatus.INACTIVE
        if (page[binding.resultId] != binding || binding.resultId !in visible) return DiscoveryThumbnailStatus.NOT_VISIBLE
        if (fenced) return DiscoveryThumbnailStatus.FENCED
        return states[binding.resultId] ?: DiscoveryThumbnailStatus.NOT_REQUESTED
    }

    fun request(capability: DiscoveryThumbnailCapability) {
        dispatcher.requireCurrentThread()
        val binding = capability.binding
        if (!current(binding) || capability.isRevoked || binding.resultId in states) return
        if (queue.size >= budget.maxQueued) {
            states[binding.resultId] = DiscoveryThumbnailStatus.QUEUE_FULL
            return
        }
        states[binding.resultId] = DiscoveryThumbnailStatus.WAITING
        queue.addLast(Job(capability))
        pump()
    }

    private fun current(binding: DiscoveryThumbnailBinding): Boolean =
        !closed && foreground && !fenced && page[binding.resultId] == binding && binding.resultId in visible

    private fun pump() {
        if (closed || !foreground || fenced || active != null) return
        val job = queue.removeFirstOrNull() ?: return
        active = job
        val timer = Runnable {
            dispatcher.requireCurrentThread()
            if (active === job && !job.completed) fence()
        }
        job.timer = timer
        dispatcher.later(timer, budget.timeoutMillis)
        val accepted = try { lane.submit(Runnable {
            // Defensive against a broken executor executing the same task twice.
            if (!job.started.compareAndSet(false, true)) return@Runnable
            val result = try { job.capability.read { job.cancelled.get() } }
            catch (_: Exception) { DiscoveryThumbnailRead.ReleaseUnconfirmed }
            val deliveryClaimed = AtomicBoolean()
            try { dispatcher.post(Runnable {
                if (deliveryClaimed.compareAndSet(false, true)) complete(job, result)
            }) }
            catch (_: Exception) {
                // UI dispatch is unavailable. Release the frame here; retain active ownership and
                // its watchdog rather than asserting that UI/native lifecycle has completed.
                if (deliveryClaimed.compareAndSet(false, true)) {
                    job.cancelled.set(true)
                    try { (result as? DiscoveryThumbnailRead.Released)?.frame?.close() } catch (_: Exception) { }
                }
            }
        }) } catch (_: Exception) {
            // Submission may have happened before throwing. Never invent a replacement worker.
            fence()
            return
        }
        if (!accepted) {
            dispatcher.remove(timer)
            active = null
            job.completed = true
            states[job.capability.binding.resultId] = DiscoveryThumbnailStatus.BUSY
            pump()
        }
    }

    private fun complete(job: Job, result: DiscoveryThumbnailRead) {
        dispatcher.requireCurrentThread()
        if (job.completed) return // duplicate UI delivery must not close/re-publish a frame twice
        job.completed = true
        job.timer?.let(dispatcher::remove)
        if (result == DiscoveryThumbnailRead.ReleaseUnconfirmed) fence()
        val frame = (result as? DiscoveryThumbnailRead.Released)?.frame
        val binding = job.capability.binding
        try {
            if (active === job && current(binding) && !job.cancelled.get() && !job.capability.isRevoked) {
                if (frame != null && valid(frame)) {
                    states[binding.resultId] = DiscoveryThumbnailStatus.DELIVERED
                    try { consumer?.invoke(binding, frame) }
                    catch (_: Exception) { if (current(binding)) states[binding.resultId] = DiscoveryThumbnailStatus.UNAVAILABLE }
                } else states[binding.resultId] = DiscoveryThumbnailStatus.UNAVAILABLE
            } else if (current(binding) && job.capability.isRevoked && !job.cancelled.get()) {
                states[binding.resultId] = DiscoveryThumbnailStatus.UNAVAILABLE
            }
        } catch (_: Exception) {
            fence()
        } finally {
            try { frame?.close() } catch (_: Exception) { fence() }
            if (active === job) active = null
            pump()
        }
    }

    private fun valid(frame: DiscoveryThumbnailFrame): Boolean =
        frame.width in 1..budget.width && frame.height in 1..budget.height && frame.allocationBytes in 1..budget.maxFrameBytes

    private fun fence() {
        if (fenced) return
        fenced = true
        active?.cancelled?.set(true)
        queue.clear(); states.clear()
        try { onStalled() } catch (_: Exception) { /* Notification cannot undo the fence. */ }
    }

    private fun cancelAll() {
        active?.cancelled?.set(true)
        queue.clear(); states.clear()
    }

    override fun close() {
        dispatcher.requireCurrentThread()
        if (closed) return
        closed = true; foreground = false
        cancelAll(); page = emptyMap(); visible = emptySet(); consumer = null
        // Keep the watchdog while a cancelled native owner may still be alive.
    }
}
