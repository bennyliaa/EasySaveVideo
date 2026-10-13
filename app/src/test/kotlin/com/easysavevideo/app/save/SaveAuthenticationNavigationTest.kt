package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.core.model.UserWait
import com.easysavevideo.core.model.ResumeAction
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveWaitReason
import org.junit.Assert.*
import org.junit.Test

class SaveAuthenticationNavigationTest {
    private val task = AuthenticationTaskIdentity("task", 4, TaskState.WAITING_USER,
        SaveWaitReason.AUTHENTICATION, TaskMethod.DIRECT, SaveError.AUTHENTICATION_REQUIRED)
    private fun command(expected: AuthenticationTaskIdentity = task) = AuthenticationBrowserCommand(expected, 7)
    @Test fun onlyAuthenticationWaitingNetworkTasksAreEligible() {
        for (state in TaskState.entries) for (reason in SaveWaitReason.entries + listOf(null)) for (method in TaskMethod.entries) {
            val current = task.copy(state = state, waitReason = reason, method = method)
            val expected = state == TaskState.WAITING_USER && reason == SaveWaitReason.AUTHENTICATION &&
                method in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)
            assertEquals(expected, canReturnForAuthentication(current))
            assertEquals(if (expected) AuthenticationNavigationResult.ACCEPTED else AuthenticationNavigationResult.NOT_ALLOWED,
                command(current).accept(current, 7, true))
        }
    }
    @Test fun acceptedNavigationIsOneShotAndDoesNotChangeTheTask() {
        val snapshot = task.copy();val command = command()
        assertEquals(AuthenticationNavigationResult.ACCEPTED, command.accept(task, 7, true))
        repeat(10) { assertEquals(AuthenticationNavigationResult.ALREADY_USED, command.accept(task, 7, true)) }
        assertEquals(snapshot, task)
    }
    @Test fun currentRevisionAndEveryCapturedStateFieldMustMatch() {
        val changed = listOf(null, task.copy(taskId = "other"), task.copy(revision = 5),
            task.copy(state = TaskState.RUNNING), task.copy(waitReason = SaveWaitReason.NETWORK),
            task.copy(method = TaskMethod.STREAM), task.copy(error = SaveError.EXPIRED_RESOURCE))
        changed.forEach { assertEquals(AuthenticationNavigationResult.STALE, command().accept(it, 7, true)) }
    }
    @Test fun unavailableAttemptDoesNotConsumeNavigation() {
        val command = command()
        repeat(3) { assertEquals(AuthenticationNavigationResult.UNAVAILABLE, command.accept(task, 7, false)) }
        assertEquals(AuthenticationNavigationResult.ACCEPTED, command.accept(task, 7, true))
    }
    @Test fun backgroundAndReturnNeverReviveOldGeneration() {
        val command = command()
        assertEquals(AuthenticationNavigationResult.STALE, command.accept(task, 8, false))
        assertEquals(AuthenticationNavigationResult.STALE, command.accept(task, 9, true))
        assertEquals(AuthenticationNavigationResult.ACCEPTED, AuthenticationBrowserCommand(task, 9).accept(task, 9, true))
    }
    @Test fun disposedOrReplacedViewCannotNavigateEvenIfTaskDidNotChange() {
        val command = command();command.retire()
        repeat(3) { assertEquals(AuthenticationNavigationResult.STALE, command.accept(task, 7, true)) }
    }
    @Test fun authenticationReasonRemainsDomainAuthorityAndErrorIsOnlyFreshnessData() {
        for (error in SaveError.entries + listOf(null)) {
            val current = task.copy(error = error)
            assertTrue(canReturnForAuthentication(current))
            assertEquals(AuthenticationNavigationResult.ACCEPTED, command(current).accept(current, 7, true))
        }
    }
    @Test fun identityHasNoSourceOrCredentialAndDebugTextIsRedacted() {
        assertEquals("AuthenticationTaskIdentity([redacted])", task.toString())
        assertFalse(task.toString().contains(task.taskId))
        val fields = AuthenticationTaskIdentity::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("url", true) || it.contains("origin", true) || it.contains("credential", true) })
    }
    @Test fun coldRecoveredWaitingRowCanNavigateButPresentChangedSnapshotCannot() {
        val published = TaskSnapshot("task", 4, TaskState.WAITING_USER, TaskMethod.DIRECT,
            waitingUser = UserWait(1000, ResumeAction.RETRY))
        assertTrue(authenticationSnapshotCompatible(published, null))
        assertTrue(authenticationSnapshotCompatible(published, published.copy()))
        assertFalse(authenticationSnapshotCompatible(published, published.copy(revision = 5)))
        assertFalse(authenticationSnapshotCompatible(published, published.copy(state = TaskState.CANCELLED, waitingUser = null)))
        assertFalse(authenticationSnapshotCompatible(published, published.copy(taskId = "other")))
        assertEquals(AuthenticationNavigationResult.ACCEPTED, command().accept(task, 7, true))
    }
    @Test fun observableActivityBecomesIdleWithoutConsumingTheWaitingCommand() {
        val command = command()
        val pending = MediaTaskActivitySignals(pendingCommandCount = 1).snapshot()
        val idle = MediaTaskActivitySignals().snapshot()
        assertEquals(AuthenticationNavigationResult.UNAVAILABLE, command.accept(task, 7, !pending.active))
        assertEquals(AuthenticationNavigationResult.ACCEPTED, command.accept(task, 7, !idle.active))
    }

}
