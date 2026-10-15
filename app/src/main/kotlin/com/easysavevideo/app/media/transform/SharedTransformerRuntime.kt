package com.easysavevideo.app.media.transform

import android.os.Handler
import android.os.HandlerThread

/**
 * One process-wide Transformer lane, control looper and sticky release fence.
 * The Editor uses this owner now; the future Direct adapter must reuse this exact object.
 * Session cleanup and each caller's product/error policy remain with their existing owners.
 */
internal object SharedTransformerRuntime {
    val laneOwner = TransformerLaneOwner()
    private val applicationThread by lazy { HandlerThread("esv-edit-control").apply { start() } }
    val handler by lazy { Handler(applicationThread.looper) }
}
