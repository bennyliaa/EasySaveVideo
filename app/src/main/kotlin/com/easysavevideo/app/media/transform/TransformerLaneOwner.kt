package com.easysavevideo.app.media.transform

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One process lane. A timed-out owner remains strongly reachable until process exit. */
internal class TransformerReleaseFence {
    private var held: Any? = null
    @Volatile var requiresRestart = false; private set
    @Synchronized fun claim(owner: Any): Boolean {
        if (requiresRestart || held != null) return false
        held = owner; return true
    }
    @Synchronized fun release(owner: Any) {
        if (requiresRestart) return
        check(held === owner); held = null
    }
    @Synchronized fun unconfirmed() { requiresRestart = true }
    val occupied: Boolean @Synchronized get() = held != null
}

/**
 * The existing lane/fence ownership unit, held once by SharedTransformerRuntime in production.
 * Kept free of Android initialization so its real queue and fence can be tested in isolation.
 * It does not acquire product/storage authority or infer native cleanup from task completion.
 */
internal class TransformerLaneOwner {
    val fence = TransformerReleaseFence()
    val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { work -> Thread(work, "esv-edit-native").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
}
