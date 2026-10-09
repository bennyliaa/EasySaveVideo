package com.easysavevideo.app.recording

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * API31+ hardware-renderer submission barrier. This proves only a host draw was submitted, not
 * that a video frame is ready or that the system selected this application. Native capture must
 * still latch a source frame newer than the resulting monotonic edge and show its actual pixels.
 */
@Composable
fun RecordingPreviewHostDraw(
    view: View,
    request: RecordingPreviewHostRequest?,
    enabled: Boolean,
    onDrawn: (RecordingPreviewHostRequest) -> Unit,
    onUnavailable: () -> Unit,
) {
    val currentDrawn = rememberUpdatedState(onDrawn)
    val currentUnavailable = rememberUpdatedState(onUnavailable)
    DisposableEffect(view, request, enabled) {
        val fence = RecordingPreviewDrawFence()
        var registered = false
        val observer = view.viewTreeObserver
        var commit: Runnable? = null
        val draw = ViewTreeObserver.OnDrawListener {
            if (request != null && enabled && fence.reserve(view.hasWindowFocus())) {
                val callback = Runnable {
                    // Renderer callbacks can be off-main. Publication/consent stays main-owned;
                    // a stale callback cannot substitute for the current request generation.
                    view.post {
                        if (fence.arrive(view.isAttachedToWindow, view.hasWindowFocus())) currentDrawn.value(request)
                        else if (!fence.isRetired() && view.isAttachedToWindow) view.postInvalidateOnAnimation()
                    }
                }
                commit = callback
                observer.registerFrameCommitCallback(callback)
            }
        }
        if (request != null && enabled) {
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated || !observer.isAlive) {
                view.post { if (!fence.isRetired()) currentUnavailable.value() }
            } else {
                observer.addOnDrawListener(draw); registered = true
                view.postInvalidateOnAnimation()
            }
        }
        onDispose {
            fence.close()
            if (observer.isAlive) {
                if (registered) observer.removeOnDrawListener(draw)
                commit?.let(observer::unregisterFrameCommitCallback)
            }
        }
    }
}
