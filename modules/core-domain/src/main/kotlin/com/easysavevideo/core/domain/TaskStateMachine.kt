package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/** Serialized owner/CAS revision primitive; persistent executors must apply it in their transaction. */
class TaskStateMachine(initial: TaskSnapshot) {
    var snapshot: TaskSnapshot = initial
        private set

    fun transition(
        expectedRevision: Long,
        next: TaskState,
        errorCode: ErrorCode? = null,
        waitingUser: UserWait? = null,
        progressPercent: Int? = snapshot.progressPercent,
    ): Boolean {
        val current = snapshot
        if (current.revision != expectedRevision || current.state.isTerminal) return false
        if (next !in allowed(current.state)) return false
        require(current.revision < Long.MAX_VALUE)
        snapshot = current.copy(revision = current.revision + 1, state = next,
            errorCode = errorCode, waitingUser = waitingUser, progressPercent = progressPercent)
        return true
    }

    fun cancel(expectedRevision: Long): Boolean = transition(expectedRevision, TaskState.CANCELLED)

    fun expireWaitingUser(nowMillis: Long): Boolean {
        val wait = snapshot.waitingUser ?: return false
        return nowMillis >= wait.expiresAtEpochMillis && cancel(snapshot.revision)
    }

    private fun allowed(from: TaskState): Set<TaskState> = when (from) {
        TaskState.QUEUED -> setOf(TaskState.RUNNING, TaskState.FAILED, TaskState.CANCELLED, TaskState.INTERRUPTED)
        TaskState.RUNNING -> setOf(TaskState.WAITING_USER, TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED, TaskState.INTERRUPTED)
        TaskState.WAITING_USER -> setOf(TaskState.RUNNING, TaskState.FAILED, TaskState.CANCELLED, TaskState.INTERRUPTED)
        TaskState.INTERRUPTED -> setOf(TaskState.WAITING_USER, TaskState.FAILED, TaskState.CANCELLED)
        TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED -> emptySet()
    }
}
