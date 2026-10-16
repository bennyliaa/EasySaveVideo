package com.easysavevideo.app.library.thumbnail

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.easysavevideo.library.thumbnail.ThumbnailFrame
import com.easysavevideo.library.thumbnail.ThumbnailLimits
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The existing ordinary thumbnail lane, also used by private controlled-input covers. */
internal object SharedThumbnailNativeLane {
    // Never replace this worker after a timeout: a native call may still be running on it.
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "esv-library-thumbnail").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    fun execute(task: Runnable) = executor.execute(task)
}

/** Injectable fixed-fixture boundary, not an alternative decoder or an input-address API. */
internal interface SharedThumbnailRetriever : AutoCloseable {
    fun publishedInput(input: FileInputStream)
    fun controlledInput(input: MediaDataSource)
    fun singleFrame(): ThumbnailFrame?
}

internal class AndroidSingleFrameRetriever(private val requireArgb: Boolean = false) : SharedThumbnailRetriever {
    private val media = MediaMetadataRetriever()
    private val limits = ThumbnailLimits()
    private var untransferredBitmap: Bitmap? = null
    private var bitmapRecycleAttempted = false
    override fun publishedInput(input: FileInputStream) = media.setDataSource(input.fd, 0L, input.channel.size())
    override fun controlledInput(input: MediaDataSource) = media.setDataSource(input)
    override fun singleFrame(): ThumbnailFrame? {
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        val frame = media.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            limits.width, limits.height, params) ?: return null
        untransferredBitmap = frame // Strong owner precedes metadata access, validation and recycle.
        if (requireArgb && frame.config != Bitmap.Config.ARGB_8888 || frame.width !in 1..limits.width ||
            frame.height !in 1..limits.height || frame.width.toLong() * frame.height > limits.maxPixels ||
            frame.allocationByteCount.toLong() !in 1..limits.maxFrameBytes) {
            recycleUntransferred()
            return null
        }
        return AndroidThumbnailFrame(frame).also { untransferredBitmap = null }
    }
    override fun close() {
        media.release()
        recycleUntransferred()
    }
    private fun recycleUntransferred() {
        val owned = untransferredBitmap ?: return
        check(!bitmapRecycleAttempted) { "THUMBNAIL_RELEASE_UNCONFIRMED" }
        bitmapRecycleAttempted = true
        owned.recycle()
        untransferredBitmap = null
    }
}

/**
 * Install this owner in the process claim before [decode]. Constructor, setup and release faults
 * retain their exact owner. No timeout, exception or cancelled flag constitutes native release.
 */
internal class SharedThumbnailExtraction(
    private val create: () -> SharedThumbnailRetriever = { AndroidSingleFrameRetriever() },
) : AutoCloseable {
    private var acquisitionStarted = false
    private var constructorUnknown = false
    private var releaseAttempted = false
    private var native: SharedThumbnailRetriever? = null
    private var frame: ThumbnailFrame? = null
    var released: Boolean = false; private set
    var unavailable: Boolean = false; private set

    fun decode(cancelled: () -> Boolean, configure: (SharedThumbnailRetriever) -> Unit) {
        check(!acquisitionStarted && !released)
        if (cancelled()) return
        acquisitionStarted = true // Registered before a possibly partial native constructor.
        try {
            val created = try { create() } catch (error: Exception) { constructorUnknown = true; throw error }
            native = created
            configure(created)
            if (!cancelled()) frame = created.singleFrame()
        } catch (_: Exception) { unavailable = true }
    }

    override fun close() {
        if (released) return
        if (constructorUnknown || releaseAttempted) error("THUMBNAIL_RELEASE_UNCONFIRMED")
        releaseAttempted = true
        // Keep the strong reference if close throws. Never speculatively invoke it a second time.
        native?.close()
        native = null
        released = true
    }

    /** Transfer exactly once only after actual native release. */
    fun takeFrame(): ThumbnailFrame? {
        check(released)
        return frame.also { frame = null }
    }
}
