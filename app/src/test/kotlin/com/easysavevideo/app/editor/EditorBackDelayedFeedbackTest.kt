package com.easysavevideo.app.editor

import com.easysavevideo.app.editor.ui.EditorStopFeedback
import org.junit.Assert.*
import org.junit.Test

/** Uses the production timer guard and publication token, without changing timer semantics. */
class EditorBackDelayedFeedbackTest {
    private class Scheduler : EditorTimerScheduler {
        class Ticket(val delay: Long, val action: () -> Unit, var closed: Boolean = false)
        val tickets = mutableListOf<Ticket>()
        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val ticket = Ticket(delayMillis, action); tickets += ticket
            return AutoCloseable { ticket.closed = true }
        }
    }

    @Test fun admittedPublicationKeepsCheckingResultAfterTenSecondStopNotice() {
        for (trigger in listOf("explicit", "background", "deadline")) {
            val scheduler = Scheduler(); val token = EditPublicationCancellation { 1 }
            var displayed: EditorStopFeedback? = null; var stopRequests = 0
            lateinit var guard: EditorDeadlineGuard
            fun cancel() {
                stopRequests++
                assertEquals(EditCancelResult.TOO_LATE, token.cancel())
                guard.cancelled(token)
            }
            guard = EditorDeadlineGuard(scheduler, ::cancel, { identity ->
                displayed = editorUnconfirmedStopFeedback(identity, token)
            })
            guard.start(token, 90_000)
            assertEquals(EditPublicationAdmission.ADMITTED, token.admitPublication(10))
            if (trigger == "deadline") scheduler.tickets.first().action() else cancel()
            assertEquals(trigger, 1, stopRequests)
            assertEquals(listOf(90_000L, 10_000L), scheduler.tickets.map { it.delay })
            assertNull(displayed)
            scheduler.tickets.last().action()
            assertEquals(trigger, EditorStopFeedback.CHECKING_RESULT, displayed)
            assertFalse(token.isCancelled)
            assertTrue(token.publicationAdmitted)
            assertFalse(token.publicationCommitted)
            assertTrue(scheduler.tickets.none { it.closed })
            assertThrows(IllegalStateException::class.java) { guard.start(Any(), 10) }
            token.committed()
            assertEquals(EditorStopFeedback.CHECKING_RESULT, editorUnconfirmedStopFeedback(token, token))
            guard.completed(token)
            assertTrue(scheduler.tickets.all { it.closed })
        }
    }

    @Test fun acceptedAndAlreadyCancelledRequestsStillShowWaitingForActualRelease() {
        for (alreadyCancelled in listOf(false, true)) {
            val scheduler = Scheduler(); val token = EditPublicationCancellation { 1 }
            var displayed: EditorStopFeedback? = null
            val guard = EditorDeadlineGuard(scheduler, {}, { identity -> displayed = editorUnconfirmedStopFeedback(identity, token) })
            guard.start(token, 1_000)
            if (alreadyCancelled) token.cancel()
            assertEquals(if (alreadyCancelled) EditCancelResult.ALREADY_CANCELLED else EditCancelResult.ACCEPTED, token.cancel())
            repeat(100) { guard.cancelled(token) }
            assertEquals(2, scheduler.tickets.size)
            scheduler.tickets.last().action()
            assertEquals(EditorStopFeedback.WAITING_FOR_RELEASE, displayed)
            assertTrue(token.isCancelled); assertFalse(token.publicationAdmitted)
            assertEquals(EditPublicationAdmission.CANCELLED, token.admitPublication(10))
            assertThrows(IllegalStateException::class.java) { guard.start(Any(), 10) }
        }
    }

    @Test fun oldTokenNoticeIsInertAfterCompletionOrReplacement() {
        val scheduler = Scheduler(); val old = EditPublicationCancellation { 1 }; val next = EditPublicationCancellation { 1 }
        var active: EditPublicationCancellation? = old
        var deliveries = 0
        val guard = EditorDeadlineGuard(scheduler, {}, { identity ->
            editorUnconfirmedStopFeedback(identity, active)?.let { deliveries++ }
        })
        guard.start(old, 1_000); old.cancel(); guard.cancelled(old)
        val oldTickets = scheduler.tickets.toList()
        guard.completed(old); active = null
        oldTickets.forEach { it.action() }
        assertEquals(0, deliveries)
        assertNull(editorUnconfirmedStopFeedback(old, active))
        active = next; guard.start(next, 1_000)
        assertNull(editorUnconfirmedStopFeedback(old, active))
        oldTickets.forEach { it.action() }
        assertEquals(0, deliveries)
        assertFalse(next.isCancelled)
    }
}
