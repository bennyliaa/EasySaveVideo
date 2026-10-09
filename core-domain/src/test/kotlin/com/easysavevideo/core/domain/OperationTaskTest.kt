package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OperationTaskTest {
    @Test fun retriesShareAbsoluteBudgetAndCancellation() {
        val cancel = CancellationSource()
        val original = OperationContext("op", 100, cancel, retryBudget = 2)
        val second = original.nextAttempt(10)!!
        assertEquals(original.deadlineEpochMillis, second.deadlineEpochMillis)
        assertSame(original.cancelToken, second.cancelToken)
        assertSame(original.resourceBudget, second.resourceBudget)
        val third = second.nextAttempt(20)!!
        assertNull(third.nextAttempt(21))
        assertNull(second.nextAttempt(100))
        cancel.cancel()
        assertEquals(OperationStopReason.CANCELLED, third.stopReason(30))
        assertNull(original.nextAttempt(30))
    }

    @Test fun terminalCancellationRejectsEveryLateTransition() {
        val task = TaskStateMachine(TaskSnapshot("task", 0, TaskState.RUNNING, TaskMethod.DIRECT))
        assertTrue(task.cancel(0))
        TaskState.entries.forEach { assertFalse(task.transition(1, it)) }
        assertEquals(TaskState.CANCELLED, task.snapshot.state)
        assertEquals(1L, task.snapshot.revision)
    }

    @Test fun staleRevisionCannotCommitSuccess() {
        val task = TaskStateMachine(TaskSnapshot("task", 0, TaskState.QUEUED, TaskMethod.BROWSER))
        assertTrue(task.transition(0, TaskState.RUNNING))
        assertFalse(task.transition(0, TaskState.SUCCEEDED))
        assertTrue(task.transition(1, TaskState.SUCCEEDED))
        assertEquals(2L, task.snapshot.revision)
    }

    @Test fun interruptedWorkMustBeReviewedOrFailedBeforeRunningAgain() {
        val task = TaskStateMachine(TaskSnapshot("task", 0, TaskState.INTERRUPTED, TaskMethod.RECORD))
        assertFalse(task.transition(0, TaskState.RUNNING))
        assertFalse(task.transition(0, TaskState.SUCCEEDED))
        assertTrue(task.transition(0, TaskState.WAITING_USER, waitingUser = UserWait(100, ResumeAction.REVIEW_RECOVERY)))
        assertFalse(task.expireWaitingUser(99))
        assertTrue(task.expireWaitingUser(100))
        assertEquals(TaskState.CANCELLED, task.snapshot.state)
    }

    @Test fun waitingUserRequiresExpiryAndRecoveryAction() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskSnapshot("task", 0, TaskState.WAITING_USER, TaskMethod.DIRECT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskSnapshot("task", 0, TaskState.RUNNING, TaskMethod.DIRECT, waitingUser = UserWait(100, ResumeAction.RETRY))
        }
    }
}
