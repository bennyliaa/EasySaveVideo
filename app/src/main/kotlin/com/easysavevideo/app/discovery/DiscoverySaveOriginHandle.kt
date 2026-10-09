package com.easysavevideo.app.discovery

import java.util.concurrent.atomic.AtomicBoolean

typealias DiscoverySaveOriginCompletion = (DiscoveryOriginTracker, DiscoveryOriginSaveProof, String,
    DiscoveryOriginMediaOutcome, (DiscoveryOriginSaveOutcome) -> Unit) -> Unit

/** No Activity, WebView, URL, cookie, or retained media task is part of this short-lived handle. */
class DiscoverySaveOriginHandle internal constructor(
    private val tracker: DiscoveryOriginTracker,
    private val proof: DiscoveryOriginSaveProof,
    private val dispatch: DiscoverySaveOriginCompletion,
) {
    private val terminal = AtomicBoolean(false)

    fun bindTask(taskId: String): Boolean = !terminal.get() && tracker.bindTask(proof, taskId)

    fun abandon() {
        terminal.set(true)
        tracker.abandon(proof)
    }

    fun complete(taskId: String, media: DiscoveryOriginMediaOutcome, onOutcome: (DiscoveryOriginSaveOutcome) -> Unit) {
        if (!terminal.compareAndSet(false, true)) return
        val delivered = AtomicBoolean(false)
        val callback: (DiscoveryOriginSaveOutcome) -> Unit = { outcome ->
            if (delivered.compareAndSet(false, true)) onOutcome(outcome)
        }
        try {
            dispatch(tracker, proof, taskId, media, callback)
        } catch (_: Exception) {
            tracker.abandon(proof)
            callback(DiscoveryOriginSaveOutcome(media, DiscoveryOriginHistoryOutcome.HISTORY_FAILED))
        }
    }
    override fun toString() = "DiscoverySaveOriginHandle([redacted])"
}
