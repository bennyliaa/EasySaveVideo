package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.core.model.TaskState
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only scans create no priority edge. Only an actually due batch owns queued/running cleanup. */
internal class MediaTaskActivityExpiry(private val changed: () -> Unit) {
    private val pending = AtomicBoolean(false)
    val active: Boolean get() = pending.get()

    fun scheduleIfDue(tasks: List<TaskSnapshot>, nowMillis: Long,
        dispatch: (work: () -> Unit, rejected: () -> Unit) -> Unit,
        cleanup: () -> Unit): Boolean {
        if (tasks.none { it.state == TaskState.WAITING_USER &&
                it.waitingUser?.let { wait -> nowMillis >= wait.expiresAtEpochMillis } == true } ||
            !pending.compareAndSet(false, true)) return false
        notifyChanged()
        val claimed = AtomicBoolean(false)
        fun release() { pending.set(false); notifyChanged() }
        val rejected = { if (claimed.compareAndSet(false, true)) release() }
        val work = {
            if (claimed.compareAndSet(false, true)) {
                try { cleanup() } finally { release() }
            }
        }
        try { dispatch(work, rejected) }
        catch (failure: Exception) { rejected(); throw failure }
        return true
    }

    private fun notifyChanged() {
        try { changed() } catch (_: Exception) { /* Optional priority observation. */ }
    }
}
