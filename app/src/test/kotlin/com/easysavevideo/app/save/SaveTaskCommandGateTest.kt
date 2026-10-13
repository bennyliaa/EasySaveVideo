package com.easysavevideo.app.save

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

class SaveTaskCommandGateTest {
    private fun waiting(id: String = "task", revision: Long = 3) = TaskSnapshot(id, revision,
        TaskState.WAITING_USER, TaskMethod.DIRECT, waitingUser = UserWait(10_000, ResumeAction.RETRY))

    @Test fun sameTaskHasOneSynchronousResumeOwner() {
        val gate = SaveTaskCommandGate()
        val first = gate.tryResume(waiting())!!
        repeat(5) { assertNull(gate.tryResume(waiting())) }
        assertTrue(gate.isCurrent(first))
        assertEquals(setOf("task"), gate.pendingTaskIds())
    }

    @Test fun differentTasksHaveIndependentCommandOwners() {
        val gate = SaveTaskCommandGate()
        val first = gate.tryResume(waiting())!!
        val second = gate.tryResume(waiting("second"))!!
        assertTrue(gate.release(first))
        assertTrue(gate.isCurrent(second))
        assertEquals(setOf("second"), gate.pendingTaskIds())
    }

    @Test fun onlyWaitingUserCanAcquireResume() {
        val gate = SaveTaskCommandGate()
        TaskState.entries.filter { it != TaskState.WAITING_USER }.forEach { state ->
            assertNull(gate.tryResume(waiting().copy(state = state, waitingUser = null)))
        }
        assertTrue(gate.pendingTaskIds().isEmpty())
    }

    @Test fun currentWaitingRevisionAndMethodMustMatchExactly() {
        val gate = SaveTaskCommandGate()
        val row = waiting()
        val attempt = gate.tryResume(row)!!
        assertTrue(gate.canResume(attempt, row))
        for (changed in listOf(null, waiting(revision = 4), waiting("other"),
            row.copy(method = TaskMethod.STREAM), row.copy(waitingUser = UserWait(20_000, ResumeAction.RETRY)),
            row.copy(state = TaskState.RUNNING, waitingUser = null))) assertFalse(gate.canResume(attempt, changed))
    }

    @Test fun cancelSupersedesResumeAndCancelsOnlyItsOperationToken() {
        val gate = SaveTaskCommandGate()
        val resume = gate.tryResume(waiting())!!
        val other = gate.tryResume(waiting("other"))!!
        val cancel = gate.tryCancel("task")!!
        assertTrue(resume.cancelToken.isCancelled)
        assertFalse(other.cancelToken.isCancelled)
        assertFalse(gate.isCurrent(resume))
        assertTrue(gate.isCurrent(cancel))
        assertNull(gate.tryResume(waiting()))
        assertNull(gate.tryCancel("task"))
    }

    @Test fun oldResumeCompletionOrRejectionCannotReleaseNewCancel() {
        val gate = SaveTaskCommandGate()
        val resume = gate.tryResume(waiting())!!
        val cancel = gate.tryCancel("task")!!
        repeat(3) { assertFalse(gate.release(resume)) }
        assertEquals(setOf("task"), gate.pendingTaskIds())
        assertTrue(gate.release(cancel))
        assertTrue(gate.pendingTaskIds().isEmpty())
    }

    @Test fun rejectedAndFinishedCommandsAllowAnExplicitRetryWithoutOldReceiptInterference() {
        val gate = SaveTaskCommandGate()
        val first = gate.tryResume(waiting())!!
        assertTrue(gate.release(first))
        val second = gate.tryResume(waiting())!!
        assertNotSame(first, second)
        assertFalse(gate.release(first))
        assertTrue(gate.isCurrent(second))
        val cancel = gate.tryCancel("task")!!
        assertTrue(gate.release(cancel))
        val third = gate.tryResume(waiting())!!
        assertFalse(gate.release(second))
        assertFalse(gate.release(cancel))
        assertTrue(gate.isCurrent(third))
    }

    @Test fun backgroundRetiresResumeUntilCompletionWithoutTurningPauseIntoCancel() {
        val gate = SaveTaskCommandGate()
        val attempt = gate.tryResume(waiting())!!
        gate.retireResumesForBackground()
        assertFalse(gate.isCurrent(attempt))
        assertFalse(gate.canResume(attempt, waiting()))
        assertFalse(attempt.cancelToken.isCancelled)
        assertEquals(setOf("task"), gate.pendingTaskIds())
        assertNull(gate.tryResume(waiting()))
        assertTrue(gate.release(attempt))
        assertTrue(gate.canResume(gate.tryResume(waiting())!!, waiting()))
    }

    @Test fun backgroundNeverRetiresExplicitCancelAndCancelCanSupersedeRetiredResume() {
        val gate = SaveTaskCommandGate()
        val resume = gate.tryResume(waiting())!!
        val otherCancel = gate.tryCancel("other")!!
        gate.retireResumesForBackground()
        val cancel = gate.tryCancel("task")!!
        assertTrue(resume.cancelToken.isCancelled)
        assertTrue(gate.isCurrent(cancel))
        assertTrue(gate.isCurrent(otherCancel))
    }

    @Test fun runningResultRequiresExactNewOperationTaskMethodAndRevision() {
        val row = waiting()
        val attempt = SaveTaskCommandGate().tryResume(row)!!
        val operation = OperationContext("new-operation", 10_000, attempt.cancelToken)
        val request = SaveRequest("task", "key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/media"))).asDurableReference()
        val accepted = SaveRecord(request, row.copy(revision = 4, state = TaskState.RUNNING, waitingUser = null),
            "task", operation.operationId, 10_000, 0)
        assertTrue(attempt.ownsResumed(accepted, operation))
        for (other in listOf(null, accepted.copy(operationId = "older-operation"),
            accepted.copy(task = accepted.task.copy(revision = 5)),
            accepted.copy(request = request.copy(requestId = "other"), task = accepted.task.copy(taskId = "other")),
            accepted.copy(request = request.copy(method = TaskMethod.STREAM), task = accepted.task.copy(method = TaskMethod.STREAM)),
            accepted.copy(task = row, waitReason = SaveWaitReason.BACKGROUND))) assertFalse(attempt.ownsResumed(other, operation))
    }

    @Test fun reservationReleaseCannotClearANewerOwnerAfterItsOwnRelease() {
        var executing = true
        var releases = 0
        val reservation = SaveResumeExecutionReservation { executing = false; releases++ }
        reservation.release()
        assertFalse(executing)
        executing = true // A different media owner reserves the existing global lane.
        repeat(3) { reservation.release() }
        assertTrue(executing)
        assertEquals(1, releases)
    }

    @Test fun reservationReleaseIsConsumedEvenWhenItsCallbackThrows() {
        var releases = 0
        val reservation = SaveResumeExecutionReservation { releases++; throw IllegalStateException() }
        try { reservation.release(); fail("Expected release failure") } catch (_: IllegalStateException) { }
        reservation.release()
        assertEquals(1, releases)
    }
}
