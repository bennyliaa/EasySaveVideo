package com.easysavevideo.app.save.storage

/** Fixed constructor-only steps. No exception text, file identity or database values. */
enum class SaveStorageInitializationStage {
    STORAGE_DIRECTORY, STORAGE_OWNER_FENCE, STORAGE_LEASE, STORAGE_HELPER,
    STORAGE_PROTOCOL, STORAGE_LIBRARY, STORAGE_PENDING_DELETES, STORAGE_SNAPSHOT, STORAGE_DATABASE
}

internal class SaveStorageInitializationProbe(private val changed: (SaveStorageInitializationStage) -> Unit) {
    private var active = true
    private var current: SaveStorageInitializationStage? = null
    private var failed = false
    private fun report(stage: SaveStorageInitializationStage) {
        try { changed(stage) } catch (_: Throwable) { /* Observation only. */ }
    }
    fun complete() { active = false }
    fun <T> at(stage: SaveStorageInitializationStage, action: () -> T): T {
        if (!active || failed) return action()
        val parent = current
        current = stage
        report(stage)
        try {
            val value = action()
            if (!failed && parent != null) report(parent)
            return value
        } catch (original: Exception) {
            failed = true
            throw original
        } finally { current = parent }
    }
}
