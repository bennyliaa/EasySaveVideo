package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord

/** Only stop authority may change while a canonical native read retains the artifact. */
internal object AdoptedCanonicalMutationPolicy {
    fun permitsStopOnly(prior: SaveRecord, next: SaveRecord): Boolean {
        if (prior.task.state.isTerminal || prior.task.revision == Long.MAX_VALUE ||
            next.task.revision != prior.task.revision + 1 ||
            next.task.taskId != prior.task.taskId || next.task.method != prior.task.method ||
            next.task.progressPercent != prior.task.progressPercent ||
            next.phase !in setOf(prior.phase, SavePhase.CANCELLING)) return false
        val stopped = next.task.state != prior.task.state && next.task.state in setOf(TaskState.WAITING_USER, TaskState.INTERRUPTED,
            TaskState.FAILED, TaskState.CANCELLED)
        val cancelling = prior.task.state == TaskState.RUNNING && next.task.state == TaskState.RUNNING &&
            next.phase == SavePhase.CANCELLING && prior.phase != SavePhase.CANCELLING &&
            next.task == prior.task.copy(revision = next.task.revision) &&
            next.error == prior.error && next.waitReason == prior.waitReason
        if (!stopped && !cancelling) return false
        // All selected data, operation/deadline/budget/attempt and publication fields stay exact.
        return prior.copy(task = next.task, phase = next.phase, error = next.error,
            waitReason = next.waitReason) == next
    }
}
