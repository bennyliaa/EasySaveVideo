package com.easysavevideo.app.recording.nativeregion

import android.graphics.Bitmap
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RegionPreviewConfirmation
import com.easysavevideo.recording.RegionPreviewSnapshot

/**
 * Capture operations run on the native worker. invalidate can also run synchronously on a
 * system-stop/UI callback thread and must revoke metadata without closing native resources.
 * Immutable frame drawing acquisition is UI-owned; implementations must be thread-safe.
 */
interface NativeRegionPreviewPort {
    val geometryGeneration: Long
    fun eligibility(): NativeRegionPreviewEligibility
    /** Ownership transfers only on true. At most one frame is offered by a native session. */
    fun offer(frame: NativeRegionPreviewFrame): Boolean
    /** Exact snapshot confirmation, UI retirement AND a fresh resumed browser draw are required. */
    fun takeConfirmedSelection(nowMonotonicUs: Long): NativeRegionPreviewSelection?
    /** Durable descriptor/journal preparation. No codec, audio recorder or file exists before true. */
    fun confirmed(descriptor: CaptureDescriptor): Boolean
    /** Revokes UI publication/acquisition. It never certifies native release. */
    fun invalidate(error: RecordingError)
}

sealed interface NativeRegionPreviewEligibility {
    data object Waiting : NativeRegionPreviewEligibility
    /** First admissible microsecond, strictly after the actual host-readiness edge. */
    data class Eligible(val earliestFrameMonotonicUs: Long) : NativeRegionPreviewEligibility {
        init { require(earliestFrameMonotonicUs > 0 && earliestFrameMonotonicUs <= Long.MAX_VALUE / 1000) }
    }
    data object Cancelled : NativeRegionPreviewEligibility
}

data class NativeRegionPreviewSelection(
    val confirmation: RegionPreviewConfirmation,
    val earliestRecordingFrameMonotonicUs: Long,
) {
    init {
        require(earliestRecordingFrameMonotonicUs > confirmation.captureReadyAtMonotonicUs &&
            earliestRecordingFrameMonotonicUs <= Long.MAX_VALUE / 1000)
    }
}

/**
 * One immutable RAM-only raster. Closing revokes new readers, but never recycles shared pixels:
 * Compose disposal is not a RenderThread/display-list completion fence. Garbage collection owns
 * physical reclamation after every Bitmap/ImageBitmap/render-node reference has retired.
 */
class NativeRegionPreviewFrame internal constructor(val snapshot: RegionPreviewSnapshot, bitmap: Bitmap) : AutoCloseable {
    private val pixels = RegionPreviewPixelLease(bitmap)
    init { require(!bitmap.isMutable && bitmap.width == snapshot.rasterWidth && bitmap.height == snapshot.rasterHeight) }
    fun acquireDrawing(): Drawing? = pixels.acquire()?.let(::Drawing)
    override fun close() = pixels.close()

    class Drawing internal constructor(private val lease: RegionPreviewPixelLease.Reader<Bitmap>) : AutoCloseable {
        val bitmap: Bitmap get() = lease.value
        override fun close() = lease.close()
    }
}
