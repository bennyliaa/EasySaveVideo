package com.easysavevideo.app.save

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveRecord
import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral command admission, separate from durable task identity and media execution ownership. */
internal class SaveTaskCommandGate {
    internal class Attempt internal constructor(val taskId: String, internal val waiting: TaskSnapshot?, internal val localRepair: Boolean = false) {
        internal val cancellation = CancellationSource()
        internal var retiredForBackground = false
        val cancelToken: CancellationToken get() = cancellation

        /** resume() may return an existing RUNNING record. Only this operation can transfer ownership. */
        fun ownsResumed(record: SaveRecord?, operation: OperationContext): Boolean = waiting != null &&
            record?.task?.taskId == taskId && record.task.method == waiting.method &&
            record.task.state == TaskState.RUNNING && record.task.revision == waiting.revision + 1 &&
            record.operationId == operation.operationId
    }

    private val owners = mutableMapOf<String, Attempt>()

    @Synchronized fun tryResume(waiting: TaskSnapshot): Attempt? {
        if (waiting.state != TaskState.WAITING_USER || owners.containsKey(waiting.taskId)) return null
        return Attempt(waiting.taskId, waiting).also { owners[waiting.taskId] = it }
    }

    /** Local state repair has no execution reservation or credential lease. */
    @Synchronized fun tryRepair(taskId: String): Attempt? {
        if (owners.containsKey(taskId)) return null
        return Attempt(taskId, null, localRepair = true).also { owners[taskId] = it }
    }

    /** Explicit Cancel supersedes Resume, including a Resume currently entering the coordinator. */
    @Synchronized fun tryCancel(taskId: String): Attempt? {
        val previous = owners[taskId]
        if (previous != null && previous.waiting == null && !previous.localRepair) return null
        previous?.cancellation?.cancel()
        return Attempt(taskId, null).also { owners[taskId] = it }
    }

    @Synchronized fun isCurrent(attempt: Attempt): Boolean =
        owners[attempt.taskId] === attempt && !attempt.retiredForBackground

    @Synchronized fun canResume(attempt: Attempt, current: TaskSnapshot?): Boolean =
        isCurrent(attempt) && attempt.waiting != null && attempt.waiting == current

    /** Foreground return cannot replay a queued click. Do not turn lifecycle pause into user Cancel. */
    @Synchronized fun retireResumesForBackground() {
        owners.values.filter { it.waiting != null || it.localRepair }.forEach { it.retiredForBackground = true }
    }

    /** A delayed completion/rejection can release only the exact command that created it. */
    @Synchronized fun release(attempt: Attempt): Boolean {
        if (owners[attempt.taskId] !== attempt) return false
        owners.remove(attempt.taskId)
        return true
    }

    @Synchronized fun pendingTaskIds(): Set<String> = owners.keys.toSet()
}

/** One release capability shared by a Resume command and the worker it hands its reservation to. */
internal class SaveResumeExecutionReservation(private val releaseExecution: () -> Unit) {
    private val released = AtomicBoolean(false)
    fun release() {
        if (released.compareAndSet(false, true)) releaseExecution()
    }
}
