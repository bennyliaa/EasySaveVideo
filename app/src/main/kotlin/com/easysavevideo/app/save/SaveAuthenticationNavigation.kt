package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.SaveWaitReason

/** Detached navigation claim only: no origin, URL, credential, capture token or task reference. */
internal data class AuthenticationTaskIdentity(val taskId: String, val revision: Long, val state: TaskState,
    val waitReason: SaveWaitReason?, val method: TaskMethod, val error: SaveError?) {
    companion object {
        fun from(record: SaveRecord) = AuthenticationTaskIdentity(record.task.taskId, record.task.revision,
            record.task.state, record.waitReason, record.request.method, record.error)
    }
    override fun toString() = "AuthenticationTaskIdentity([redacted])"
}

internal fun canReturnForAuthentication(task: AuthenticationTaskIdentity): Boolean =
    task.state == TaskState.WAITING_USER && task.waitReason == SaveWaitReason.AUTHENTICATION &&
        task.method in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)

/** Cold-recovered waiting rows may have no coordinator memory entry yet. A present entry must match.
 * This only permits navigation; it does not establish source/authentication/resume authority. */
internal fun authenticationSnapshotCompatible(published: TaskSnapshot, cached: TaskSnapshot?): Boolean =
    cached == null || cached == published

internal enum class AuthenticationNavigationResult { ACCEPTED, ALREADY_USED, STALE, NOT_ALLOWED, UNAVAILABLE }

/** One explicit action in a view lifetime; rejected attempts have no side effects. */
internal class AuthenticationBrowserCommand(private val expected: AuthenticationTaskIdentity, private val generation: Long) {
    private var used = false
    private var retired = false
    val taskId: String get() = expected.taskId
    fun retire() { retired = true }
    fun accept(current: AuthenticationTaskIdentity?, currentGeneration: Long, available: Boolean): AuthenticationNavigationResult {
        if (retired || generation != currentGeneration) return AuthenticationNavigationResult.STALE
        if (used) return AuthenticationNavigationResult.ALREADY_USED
        if (current != expected) return AuthenticationNavigationResult.STALE
        if (!canReturnForAuthentication(expected)) return AuthenticationNavigationResult.NOT_ALLOWED
        if (!available) return AuthenticationNavigationResult.UNAVAILABLE
        used = true
        return AuthenticationNavigationResult.ACCEPTED
    }
}
