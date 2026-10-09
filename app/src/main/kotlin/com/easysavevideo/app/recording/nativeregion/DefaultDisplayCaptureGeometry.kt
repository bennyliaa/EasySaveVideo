package com.easysavevideo.app.recording.nativeregion

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import com.easysavevideo.recording.CaptureDisplayGeometry
import com.easysavevideo.recording.CaptureInsets
import com.easysavevideo.recording.CaptureScopeEvidence

/** Full default-display coordinates, including bars. Insets are metadata, never subtracted. */
object DefaultDisplayCaptureGeometry {
    fun scopeEvidence(): CaptureScopeEvidence = if (Build.VERSION.SDK_INT >= 34)
        CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY else CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY

    fun read(context: Context): CaptureDisplayGeometry? = try {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null || !display.isValid) null else {
            val visual = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            read(display, visual)
        }
    } catch (_: RuntimeException) { null }

    /** Reuse the capture owner's visual context instead of allocating one for every frame. */
    internal fun read(display: Display, visual: Context): CaptureDisplayGeometry? = try {
        if (!display.isValid || display.displayId != Display.DEFAULT_DISPLAY) null else {
            val metrics = visual.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val rotation = when (display.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> -1
            }
            if (rotation < 0) null else CaptureDisplayGeometry(bounds.width(), bounds.height(), rotation,
                CaptureInsets(insets.left, insets.top, insets.right, insets.bottom))
        }
    } catch (_: RuntimeException) { null }
}
