package com.easysavevideo.app.discovery.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.VideoSearchResult
import com.easysavevideo.discovery.thumbnail.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AndroidDiscoveryThumbnailFrame(bitmap: Bitmap) : DiscoveryThumbnailFrame {
    private var bitmap: Bitmap? = bitmap
    override val width = bitmap.width
    override val height = bitmap.height
    override val allocationBytes = bitmap.allocationByteCount.toLong()
    fun displayImage(): ImageBitmap? = bitmap?.asImageBitmap()
    // A separately retained immutable display handle can still be in a Compose/GPU draw.
    // Release this borrowed reference; never recycle a bitmap that another handle can draw.
    override fun close() { bitmap = null }
}

/** Process lane, deliberately scoped to this one image source, not a generic scheduler. */
internal class CommonsThumbnailLane(
    private val execute: (Runnable) -> Unit,
    private val deliver: (Runnable) -> Boolean,
) : DiscoveryThumbnailLane {
    private class Work { var completion: Runnable? = null; val delivered = AtomicBoolean(false) }
    private val lock = Any()
    private var active: Work? = null
    private var poisoned = false
    private val current = ThreadLocal<Work>()
    private val available = CopyOnWriteArrayList<() -> Unit>()
    fun subscribeAvailable(callback: () -> Unit): AutoCloseable {
        available += callback
        return AutoCloseable { available.remove(callback) }
    }
    fun poison() { synchronized(lock) { poisoned = true } }
    override fun submit(task: Runnable): Boolean {
        val work = synchronized(lock) {
            if (active != null || poisoned) return false
            Work().also { active = it }
        }
        try {
            execute(Runnable {
                current.set(work)
                try { task.run() }
                finally {
                    current.remove()
                    val completion = synchronized(lock) { work.completion }
                    if (completion == null) poison()
                    else if (!deliver(Runnable {
                        if (!work.delivered.compareAndSet(false, true)) return@Runnable
                        // The worker, response close and cancellation drain already returned.
                        synchronized(lock) { if (active === work) active = null }
                        completion.run()
                        if (synchronized(lock) { active == null && !poisoned }) available.forEach { it() }
                    })) poison()
                }
            })
        } catch (_: Exception) { poison(); throw IllegalStateException("THUMBNAIL_SUBMISSION") }
        return true
    }
    fun postCompletion(task: Runnable) {
        val work = checkNotNull(current.get())
        synchronized(lock) { check(active === work && work.completion == null); work.completion = task }
    }
}

/** Handler/lane/poison/HTTP cooldown survive controller and page replacement. */
private object CommonsImageProcess {
    val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "esv-discovery-image").apply { isDaemon = true } }
    val lane = CommonsThumbnailLane({ executor.execute(it) }, { main.post(it) })
    private var reader: CommonsThumbnailReader? = null
    @Synchronized fun reader(context: Context): CommonsThumbnailReader = reader
        ?: CommonsThumbnailReader(context.applicationContext).also { reader = it }
}

internal data class DiscoveryThumbnailImageState(val status: DiscoveryThumbnailStatus, val image: ImageBitmap? = null)

internal class AndroidDiscoveryThumbnails : AutoCloseable {
    private data class Display(val binding: DiscoveryThumbnailBinding, val image: ImageBitmap, val bytes: Long)
    private val generation = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val capabilities = ConcurrentHashMap<String, DiscoveryThumbnailCapability>()
    private val images = linkedMapOf<String, Display>() // Main-thread drawing lifetime only.
    private var revision by mutableStateOf(0L)
    private var appliedGeneration = -1L
    private var visibleGeneration by mutableStateOf(0L)
    val requestGeneration: Long get() = visibleGeneration
    private var viewportEnabled = false
    private var page: PageSnapshot? = null
    private var results = emptyList<VideoSearchResult>()
    private val dispatcher = object : DiscoveryThumbnailDispatcher {
        override fun requireCurrentThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
        override fun post(task: Runnable) = CommonsImageProcess.lane.postCompletion(task)
        override fun later(task: Runnable, delayMillis: Long) {
            check(CommonsImageProcess.main.postDelayed(task, delayMillis))
        }
        override fun remove(task: Runnable) { CommonsImageProcess.main.removeCallbacks(task) }
    }
    private val session = DiscoveryThumbnailSession(
        DiscoveryThumbnailBudget(256, 144, CommonsThumbnailPolicy.MAX_FRAME, 16, 16, 10_000),
        CommonsImageProcess.lane, dispatcher, CommonsImageProcess.lane::poison,
        { binding, frame ->
            if (frame is AndroidDiscoveryThumbnailFrame && !closed.get() && appliedGeneration == generation.get() &&
                capabilities[binding.resultId]?.binding == binding) {
                val next = frame.displayImage()
                val previous = images[binding.resultId]?.bytes ?: 0L
                val bytes = images.values.sumOf { it.bytes } - previous + frame.allocationBytes
                if (next != null && bytes <= CommonsThumbnailPolicy.MAX_RETAINED &&
                    (binding.resultId in images || images.size < 16)) {
                    images[binding.resultId] = Display(binding, next, frame.allocationBytes)
                    revision++
                }
            }
        })

    private val laneSubscription = CommonsImageProcess.lane.subscribeAvailable {
        if (!closed.get() && appliedGeneration == generation.get())
            capabilities.values.forEach(session::retryReleasedLane)
    }
    init { session.observeStatus { revision++ } }
    fun currentGeneration(): Long = generation.get()
    fun isCurrent(expected: Long): Boolean = !closed.get() && generation.get() == expected

    /** Thread-safe immediate signal only; no network/decoder wait and no off-UI map access. */
    fun withdraw() {
        val next = generation.incrementAndGet()
        capabilities.values.forEach { it.close() }
        CommonsImageProcess.main.post {
            if (generation.get() == next) {
                visibleGeneration = next
                viewportEnabled = false
                images.clear(); capabilities.clear(); revision++
                page?.let { session.updatePage(it.stamp, it.revision, results, false) }
            }
        }
    }

    fun hideViewport() {
        dispatcher.requireCurrentThread()
        if (viewportEnabled) { viewportEnabled = false; withdraw() }
    }

    fun update(page: PageSnapshot, results: List<VideoSearchResult>, visibleIds: List<String>, enabled: Boolean,
        expectedGeneration: Long, issue: (DiscoveryThumbnailBinding, VideoSearchResult, Long) -> DiscoveryThumbnailCapability?) {
        dispatcher.requireCurrentThread()
        if (closed.get() || expectedGeneration != generation.get()) return
        val epoch = expectedGeneration
        if (this.page != page || this.results != results || appliedGeneration != epoch) {
            capabilities.values.forEach { it.close() }; capabilities.clear(); images.clear()
            session.updatePage(page.stamp, page.revision, results, false)
        }
        this.page = page; this.results = results; appliedGeneration = epoch
        viewportEnabled = enabled
        session.updatePage(page.stamp, page.revision, results, enabled)
        val visible = if (enabled) visibleIds.distinct().filter { id -> results.any { it.resultId == id } }.take(16).toSet() else emptySet()
        val removed = capabilities.keys.filter { it !in visible }
        removed.forEach { capabilities.remove(it)?.close(); images.remove(it) }
        images.keys.removeAll { it !in visible }
        session.setVisible(visible)
        if (enabled) for (result in results.filter { it.resultId in visible }) {
            val binding = DiscoveryThumbnailBinding.of(page.stamp, page.revision, result)
            val capability = capabilities[result.resultId] ?: CommonsThumbnailPolicy.issueAtGeneration(epoch, generation::get) {
                issue(binding, result, epoch)
            }?.also { capabilities[result.resultId] = it }
            if (capability != null) session.request(capability)
        }
        revision++
    }

    fun state(page: PageSnapshot?, result: VideoSearchResult): DiscoveryThumbnailImageState {
        dispatcher.requireCurrentThread()
        @Suppress("UNUSED_VARIABLE") val observed = revision
        if (page == null || closed.get() || appliedGeneration != generation.get())
            return DiscoveryThumbnailImageState(DiscoveryThumbnailStatus.INACTIVE)
        val binding = DiscoveryThumbnailBinding.of(page.stamp, page.revision, result)
        val status = session.status(binding)
        val image = images[result.resultId]?.takeIf { it.binding == binding }?.image
        return DiscoveryThumbnailImageState(status, image)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        laneSubscription.close()
        withdraw()
        // Dedicated Handler is never removed by AndroidDiscoveryController.close().
        CommonsImageProcess.main.post { session.close(); images.clear(); capabilities.clear(); revision++ }
    }

    companion object {
        fun read(context: Context, url: String, cancellation: com.easysavevideo.discovery.DiscoveryCancellation) =
            CommonsImageProcess.reader(context).read(url, cancellation)
    }
}
