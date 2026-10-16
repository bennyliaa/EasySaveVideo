package com.easysavevideo.app.library.thumbnail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.thumbnail.*
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * No Bitmap/ImageBitmap escapes this handle. Cache eviction and a late Compose draw synchronize
 * here: a closed handle draws nothing. Once submitted to Canvas, a bitmap is never explicitly
 * recycled: the platform renderer can still retain it after the Java draw call returns. Eviction
 * drops our reference. The cache byte limit bounds app-owned frames, not GC/native/GPU memory.
 */
class AndroidThumbnailFrame internal constructor(private var bitmap: Bitmap?) : ThumbnailFrame {
    override val width: Int = requireNotNull(bitmap).width
    override val height: Int = requireNotNull(bitmap).height
    override val allocationBytes: Long = requireNotNull(bitmap).allocationByteCount.toLong()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var submittedToCanvas = false
    @Synchronized internal fun draw(canvas: Canvas, availableWidth: Float, availableHeight: Float) {
        val current = bitmap ?: return
        val scale = minOf(availableWidth / width, availableHeight / height)
        val renderedWidth = width * scale; val renderedHeight = height * scale
        val left = (availableWidth - renderedWidth) / 2f; val top = (availableHeight - renderedHeight) / 2f
        submittedToCanvas = true
        canvas.drawBitmap(current, null, RectF(left, top, left + renderedWidth, top + renderedHeight), paint)
    }
    @Synchronized override fun close() {
        if (!submittedToCanvas) bitmap?.recycle()
        bitmap = null
    }
}

/**
 * One active screen session, one process-native worker, no disk cache and no path/URI API.
 * Construct/update/request/close on main. A replacement session first invalidates the prior one,
 * while a stalled old native job remains owned by the process gate and cannot multiply workers.
 */
class AndroidLibraryThumbnails(
    openPublishedMedia: (MediaReadRef) -> FileInputStream,
    retainNativeReader: (MediaReadRef) -> AutoCloseable,
) : AutoCloseable {
    private val backend = AndroidThumbnailBackend(openPublishedMedia, retainNativeReader)
    private val ui = AndroidThumbnailUiDispatcher(backend::captureCompletionRelease, ::forgetReleasedSession)
    private var version by mutableStateOf(0L)
    private var disposed = false
    private val session = BoundedThumbnailSession(backend, backend, ui,
        onStalled = OrdinaryMediaNativeResources::unconfirmed,
        onChanged = { version++; forgetReleasedSession() })

    init {
        ui.requireCurrentThread()
        currentSession?.close()
        currentSession = this
        retainedSessions.add(this)
    }

    fun updatePage(items: List<MediaItem>, enabled: Boolean) = session.updatePage(items, enabled)
    fun request(item: MediaItem) = session.request(item)
    fun cancel(item: MediaItem) = session.cancel(item)
    fun state(item: MediaItem): ThumbnailState<AndroidThumbnailFrame> {
        @Suppress("UNUSED_VARIABLE") val observed = version
        return session.state(item)
    }
    /** Main-thread exact purge; completion observes real backend release through the session. */
    private fun retireExact(reference: MediaReadRef, onActuallyRetired: () -> Unit) =
        session.retireExact(reference, onActuallyRetired)

    private fun forgetReleasedSession() {
        if (disposed && !session.hasActiveJob && !session.hasUnconfirmedResources && session.retainedCount == 0) retainedSessions.remove(this)
    }

    override fun close() {
        ui.requireCurrentThread()
        if (disposed) return
        disposed = true
        session.close()
        if (currentSession === this) currentSession = null
        forgetReleasedSession()
    }
    companion object {
        private var currentSession: AndroidLibraryThumbnails? = null
        private val retainedSessions = linkedSetOf<AndroidLibraryThumbnails>()

        /** Includes disposed sessions whose native completion has not returned yet. */
        fun retireExactSource(reference: MediaReadRef, onActuallyRetired: () -> Unit) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val owners = retainedSessions.toList()
            if (owners.isEmpty()) { onActuallyRetired(); return }
            var remaining = owners.size
            owners.forEach { owner -> owner.retireExact(reference) {
                remaining--
                if (remaining == 0) onActuallyRetired()
            } }
        }
    }
}

private class AndroidThumbnailUiDispatcher(private val captureCompletionRelease: () -> (() -> Unit), private val afterCompletion: () -> Unit) : ThumbnailUiDispatcher {
    private val main = Handler(Looper.getMainLooper())
    override fun requireCurrentThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun post(task: Runnable) {
        val release = captureCompletionRelease()
        main.post { release(); task.run(); afterCompletion() }
    }
    override fun later(task: Runnable, delayMillis: Long) { main.postDelayed(task, delayMillis) }
    override fun remove(task: Runnable) { main.removeCallbacks(task) }
}

private class AndroidThumbnailBackend(
    private val openPublishedMedia: (MediaReadRef) -> FileInputStream,
    private val retainNativeReader: (MediaReadRef) -> AutoCloseable,
) : ThumbnailDecoder<AndroidThumbnailFrame>, ThumbnailWorkLane {
    /** The process gate keeps every surviving Java/native/read authority strongly reachable. */
    private class NativeOwner { val resources = ThumbnailReadResources() }
    private val limits = ThumbnailLimits()

    override fun submit(task: Runnable): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = NativeOwner()
        // This MAIN-thread claim serializes with recording's main-thread check-and-admit step.
        if (!OrdinaryMediaNativeResources.claim(owner)) return false
        return try {
            nativeLane.execute {
                workerOwner.set(owner)
                try { task.run() }
                finally { workerOwner.remove() }
            }
            true
        } catch (_: RejectedExecutionException) {
            // No native work was submitted, so no decoder/descriptor can exist for this owner.
            OrdinaryMediaNativeResources.released(owner)
            false
        }
    }

    /** Keep admission through the main-thread handoff, bounding pending completed bitmaps too. */
    fun captureCompletionRelease(): () -> Unit {
        val owner = workerOwner.get()
        return { if (owner != null && owner.resources.released) OrdinaryMediaNativeResources.released(owner) }
    }

    override fun decode(reference: MediaReadRef, cancelled: () -> Boolean): ThumbnailDecodeResult<AndroidThumbnailFrame> {
        val owner = workerOwner.get() ?: return ThumbnailDecodeResult.ReleaseUnconfirmed
        var bitmap: Bitmap? = null
        var released = false
        try {
            if (!cancelled()) {
                // Both storage calls validate exact READY item/revision/generation. The independent
                // lease fences a native duplicate even if the ordinary descriptor closes elsewhere.
                owner.resources.catalogFence = retainNativeReader(reference)
                val input = if (!cancelled()) openPublishedMedia(reference).also { owner.resources.input = it } else null
                if (!cancelled() && input != null) {
                    val media = MediaMetadataRetriever()
                    owner.resources.nativeReader = AutoCloseable { media.release() }
                    media.setDataSource(input.fd, 0L, input.channel.size())
                    if (!cancelled()) {
                        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                        bitmap = media.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, limits.width, limits.height, params)
                    }
                }
            }
        } catch (_: Exception) {
            // Technical unavailable state only. No media name/path/URI or exception text is logged.
        } finally {
            // release may hang or throw. In that case do NOT close subsequent authorities and
            // do NOT clear process ownership; watchdog timeout does not imply thread exit.
            released = owner.resources.releaseConfirmed()
            if (!released) OrdinaryMediaNativeResources.unconfirmed()
            // Confirmed resources stay admitted until main consumes or discards this one result.
        }
        if (!released) {
            bitmap?.recycle()
            return ThumbnailDecodeResult.ReleaseUnconfirmed
        }
        val frame = bitmap
        if (frame == null) return ThumbnailDecodeResult.Released(null)
        if (cancelled() || frame.width !in 1..limits.width || frame.height !in 1..limits.height ||
            frame.width.toLong() * frame.height > limits.maxPixels || frame.allocationByteCount.toLong() !in 1..limits.maxFrameBytes) {
            frame.recycle()
            return ThumbnailDecodeResult.Released(null)
        }
        return ThumbnailDecodeResult.Released(AndroidThumbnailFrame(frame))
    }

    private companion object {
        val workerOwner = ThreadLocal<NativeOwner>()
        // A blocked native call cannot be interrupted reliably. Never replace this process lane.
        val nativeLane = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, "esv-library-thumbnail").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
