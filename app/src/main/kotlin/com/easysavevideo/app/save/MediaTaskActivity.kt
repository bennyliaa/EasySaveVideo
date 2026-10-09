package com.easysavevideo.app.save

/** Process-local facts from the application media owner, never a screen or journal row count. */
data class MediaTaskActivitySignals(
    val initializing: Boolean = false,
    val executing: Boolean = false,
    val preparingStream: Boolean = false,
    val selectedStream: Boolean = false,
    val pendingCommandCount: Int = 0,
    val pendingRecording: Boolean = false,
    val recordingOwner: Boolean = false,
    val recordingService: Boolean = false,
    val segmentRecording: Boolean = false,
    val resourcesDraining: Boolean = false,
) {
    init { require(pendingCommandCount >= 0) }
    fun snapshot(): MediaTaskActivitySnapshot {
        val recording = pendingRecording || recordingOwner || recordingService || segmentRecording
        return MediaTaskActivitySnapshot(
            saveActive = initializing || (executing && !recording) || preparingStream || selectedStream ||
                pendingCommandCount > 0 || resourcesDraining,
            recordingActive = recording,
            resourcesDraining = resourcesDraining,
        )
    }
}

data class MediaTaskActivitySnapshot(
    val saveActive: Boolean = false,
    val recordingActive: Boolean = false,
    val resourcesDraining: Boolean = false,
) {
    val active: Boolean get() = saveActive || recordingActive || resourcesDraining
}

/**
 * Registration and replay are atomic with updates. Closing a screen subscription only detaches
 * that observer; it never releases media ownership. Closed or reentrant stale deliveries cannot
 * overwrite the latest state of another observer. Callbacks must be short and non-blocking.
 */
class MediaTaskActivityTracker {
    private val lock = Any()
    private var value = MediaTaskActivitySnapshot()
    private var revision = 0L
    private val listeners = LinkedHashSet<Listener>()
    private class Listener(val callback: (MediaTaskActivitySnapshot) -> Unit) {
        var lastRevision = -1L
    }
    val snapshot: MediaTaskActivitySnapshot get() = synchronized(lock) { value }

    fun update(signals: MediaTaskActivitySignals) = synchronized(lock) {
        val next = signals.snapshot()
        if (value == next) return@synchronized
        value = next
        val version = ++revision
        listeners.toList().forEach { deliver(it, next, version) }
    }

    fun addListener(callback: (MediaTaskActivitySnapshot) -> Unit): AutoCloseable = synchronized(lock) {
        val listener = Listener(callback)
        listeners += listener
        deliver(listener, value, revision)
        AutoCloseable { synchronized(lock) { listeners -= listener } }
    }

    private fun deliver(listener: Listener, snapshot: MediaTaskActivitySnapshot, version: Long) {
        if (listener !in listeners || listener.lastRevision >= version) return
        listener.lastRevision = version
        // Observation is optional. A discovery/UI failure must never fail a media task.
        try { listener.callback(snapshot) } catch (_: Exception) { /* Isolated observer. */ }
    }
}
