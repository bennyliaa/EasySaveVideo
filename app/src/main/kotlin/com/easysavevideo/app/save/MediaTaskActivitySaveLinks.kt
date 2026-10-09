package com.easysavevideo.app.save

import com.easysavevideo.save.SaveRecord

/**
 * Volatile association with newly accepted tasks only. No resume, journal recovery or list refresh
 * can create an entry. Remove-before-callback makes every completion one-shot even if history fails.
 * This component cannot invoke Save execution or change a media task's state.
 */
internal class MediaTaskActivitySaveLinks<T : Any>(
    private val bind: (T, String) -> Boolean,
    private val complete: (T, String, Boolean) -> Unit,
    private val abandon: (T) -> Unit,
    private val capacity: Int = 100,
) {
    private val lock = Any()
    private val links = LinkedHashMap<String, T>()
    init { require(capacity > 0) }

    fun accepted(taskId: String, origin: T?, existing: Boolean = false): Boolean {
        if (origin == null) return false
        val accepted = synchronized(lock) {
            if (existing || taskId in links || links.size >= capacity) false
            else try {
                if (bind(origin, taskId)) { links[taskId] = origin; true } else false
            } catch (_: Exception) { false }
        }
        if (!accepted) discard(origin)
        return accepted
    }

    fun afterExecution(taskId: String, finalRecord: () -> SaveRecord?) {
        val origin = synchronized(lock) { links.remove(taskId) } ?: return
        val committed = try { mediaTaskHasCommittedSave(taskId, finalRecord()) } catch (_: Exception) { false }
        try { complete(origin, taskId, committed) }
        catch (_: Exception) { discard(origin) }
    }

    fun cancel(taskId: String) {
        val origin = synchronized(lock) { links.remove(taskId) } ?: return
        discard(origin)
    }

    fun discard(origin: T?) {
        if (origin != null) try { abandon(origin) } catch (_: Exception) { /* Optional association only. */ }
    }
}
