package com.easysavevideo.app.editor

import org.junit.Assert.*
import org.junit.Test

class EditorDeadlineGuardTest {
    private class Scheduler : EditorTimerScheduler {
        data class Ticket(val delay: Long, val action: () -> Unit, var removed: Boolean = false)
        val tickets = arrayListOf<Ticket>()
        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val ticket = Ticket(delayMillis, action); tickets += ticket
            return AutoCloseable { ticket.removed = true }
        }
    }
    @Test fun overallDeadlineRequestsCancellationThenBoundedPendingNotice() {
        val scheduler = Scheduler(); val events = arrayListOf<String>()
        val guard = EditorDeadlineGuard(scheduler, { events += "cancel" }, { events += "pending" })
        guard.start(Any(), 90000); scheduler.tickets[0].action()
        assertEquals(listOf("cancel"), events); assertEquals(10000L, scheduler.tickets[1].delay)
        scheduler.tickets[1].action(); assertEquals(listOf("cancel", "pending"), events)
    }
    @Test fun repeatedCancelDoesNotRenewNoticeOrGrowQueue() {
        val scheduler = Scheduler(); val token = Any(); val guard = EditorDeadlineGuard(scheduler, {}, {})
        guard.start(token, 1000); repeat(100) { guard.cancelled(token) }
        assertEquals(2, scheduler.tickets.size)
    }
    @Test fun completionRemovesBothTimersAndStaleDeliveryIsInert() {
        val scheduler = Scheduler(); var signals = 0; val token = Any()
        val guard = EditorDeadlineGuard(scheduler, { signals++ }, { signals++ })
        guard.start(token, 1000); guard.cancelled(token); guard.completed(token)
        assertTrue(scheduler.tickets.all { it.removed }); scheduler.tickets.forEach { it.action() }
        assertEquals(0, signals)
    }
    @Test fun priorTaskCallbacksCannotCancelOrMarkItsSuccessorPending() {
        val scheduler = Scheduler(); var signals = 0; val first = Any(); val second = Any()
        val guard = EditorDeadlineGuard(scheduler, { signals++ }, { signals++ })
        guard.start(first, 1000); guard.cancelled(first); val old = scheduler.tickets.toList()
        guard.completed(first); guard.start(second, 1000); old.forEach { it.action() }
        guard.cancelled(first); guard.completed(first); assertEquals(0, signals)
        scheduler.tickets.last().action(); assertEquals(1, signals)
    }
    @Test fun pendingNoticeNeverCompletesOrReleasesAnOperation() {
        val scheduler = Scheduler(); val token = Any(); val guard = EditorDeadlineGuard(scheduler, {}, {})
        guard.start(token, 1000); guard.cancelled(token); scheduler.tickets.last().action()
        try { guard.start(Any(), 1000); fail("Still owned") } catch (_: IllegalStateException) { }
        guard.completed(token); guard.start(Any(), 1000)
    }
}
