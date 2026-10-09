package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskState
import org.junit.Assert.*
import org.junit.Test

class SaveTaskCapacityTest {
    @Test fun unfinishedTasksRemainAccessiblePastOneHundredRecentTerminalRows() {
        val recent = List(100) { "terminal-$it" }
        val ids = SaveTaskCapacity.visibleTaskIds(listOf("old-running", "old-waiting"), recent)
        assertEquals(listOf("old-running", "old-waiting"), ids.take(2))
        assertEquals(102, ids.size)
    }
    @Test fun snapshotDoesNotDuplicateRecentActiveTasks() {
        assertEquals(listOf("active", "done"), SaveTaskCapacity.visibleTaskIds(listOf("active"), listOf("done", "active")))
    }
    @Test fun oneHundredTerminalTasksDoNotExhaustActiveCapacity() {
        val states = List(100) { listOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)[it % 3] }
        assertEquals(0, SaveTaskCapacity.activeCount(states))
        assertTrue(SaveTaskCapacity.hasRoom(SaveTaskCapacity.activeCount(states), 100))
    }
    @Test fun unfinishedTasksStillConsumeCapacity() {
        val states = List(100) { TaskState.WAITING_USER }
        assertEquals(100, SaveTaskCapacity.activeCount(states))
        assertFalse(SaveTaskCapacity.hasRoom(SaveTaskCapacity.activeCount(states), 100))
    }
    @Test fun mixedHistoricalAndActiveWorkOnlyCountsActive() {
        val states = List(100) { TaskState.SUCCEEDED } + List(99) { TaskState.QUEUED }
        assertEquals(99, SaveTaskCapacity.activeCount(states))
        assertTrue(SaveTaskCapacity.hasRoom(SaveTaskCapacity.activeCount(states), 100))
    }
}
