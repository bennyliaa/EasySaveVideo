package com.easysavevideo.app.recording

import com.easysavevideo.app.editor.EditorTimerScheduler
import org.junit.Assert.*
import org.junit.Test

class RecordingProcessingWatchdogTest {
    private class Scheduler : EditorTimerScheduler {
        data class Ticket(val delay: Long, val callback: () -> Unit, var closed: Boolean = false)
        val tickets = mutableListOf<Ticket>()
        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val ticket = Ticket(delayMillis, action); tickets += ticket
            return AutoCloseable { ticket.closed = true }
        }
    }
    @Test fun blockedRawVerificationHasDeadlineThenBoundedWaitingNoticeWithoutRelease() {
        val clock = Scheduler(); val events = mutableListOf<String>()
        val watchdog = RecordingProcessingWatchdog(clock, { events += "cancel" }, { events += "waiting" })
        val job = Any(); watchdog.processing(job, 5000)
        clock.tickets[0].callback(); assertEquals(listOf("cancel"), events)
        assertEquals(10_000L, clock.tickets[1].delay); clock.tickets[1].callback()
        assertEquals(listOf("cancel", "waiting"), events)
        assertThrows(IllegalStateException::class.java) { watchdog.processing(Any(), 1000) }
    }
    @Test fun explicitPostCaptureCancelDoesNotWaitWholeProcessingBudget() {
        val clock = Scheduler(); val watchdog = RecordingProcessingWatchdog(clock, {}, {})
        val job = Any(); watchdog.processing(job, 90 * 60 * 1000L); repeat(100) { watchdog.stopping(job) }
        assertEquals(listOf(90 * 60 * 1000L, 10_000L), clock.tickets.map { it.delay })
    }
    @Test fun retainedCleanupGetsTimerEvenWithoutCaptureOrProcessing() {
        val clock = Scheduler(); var waiting = 0
        val watchdog = RecordingProcessingWatchdog(clock, {}, { waiting++ })
        watchdog.stopping(Any()); assertEquals(listOf(15_000L, 10_000L), clock.tickets.map { it.delay })
        clock.tickets.last().callback(); assertEquals(1, waiting)
    }
    @Test fun laterOverallDeadlineNeverDowngradesAlreadyVisibleCleanupRecovery() {
        val clock = Scheduler(); val events = mutableListOf<String>()
        val watchdog = RecordingProcessingWatchdog(clock, { events += "cancelling" }, { events += "recovery" })
        watchdog.stopping(Any())
        clock.tickets.single { it.delay == 10_000L }.callback()
        clock.tickets.single { it.delay == 15_000L }.callback()
        assertEquals(listOf("recovery"), events)
    }
    @Test fun earlierExplicitCancelNoticeSurvivesOriginalLongProcessingDeadline() {
        val clock = Scheduler(); val events = mutableListOf<String>()
        val watchdog = RecordingProcessingWatchdog(clock, { events += "cancelling" }, { events += "recovery" })
        val job = Any(); watchdog.processing(job, 90_000); watchdog.stopping(job)
        clock.tickets.single { it.delay == 10_000L }.callback()
        clock.tickets.single { it.delay == 90_000L }.callback()
        assertEquals(listOf("recovery"), events)
    }
    @Test fun queuedCancellationAndActualCompletionFenceAllLateTimers() {
        val clock = Scheduler(); var events = 0
        val watchdog = RecordingProcessingWatchdog(clock, { events++ }, { events++ })
        val old = Any(); watchdog.processing(old, 9000, cancelled = true)
        val callbacks = clock.tickets.toList(); watchdog.completed(old); assertTrue(callbacks.all { it.closed })
        watchdog.processing(Any(), 1000); callbacks.forEach { it.callback() }; watchdog.completed(old)
        assertEquals(0, events)
    }
    @Test fun waitingNoticeCannotAllowReplacementUntilActualOwnerCompletes() {
        val clock = Scheduler(); val watchdog = RecordingProcessingWatchdog(clock, {}, {})
        val old = Any(); watchdog.stopping(old); clock.tickets.last().callback()
        assertThrows(IllegalStateException::class.java) { watchdog.stopping(Any()) }
        watchdog.completed(old); watchdog.stopping(Any())
    }
}
