package com.easysavevideo.app.editor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class EditPublicationCancellationTest {
    @Test fun cancellationBeforeAdmissionPermanentlyExcludesPublication() {
        val token = EditPublicationCancellation { 10 }
        assertEquals(EditCancelResult.ACCEPTED, token.cancel())
        assertEquals(EditPublicationAdmission.CANCELLED, token.admitPublication(20))
        assertTrue(token.isCancelled); assertFalse(token.publicationAdmitted)
        assertThrows(IllegalStateException::class.java) { token.committed() }
    }
    @Test fun admittedPublicationMakesCancelAndDeadlineTooLateWithoutFalseCancelledFlag() {
        var now = 10L; val token = EditPublicationCancellation { now }
        assertEquals(EditPublicationAdmission.ADMITTED, token.admitPublication(20))
        now = 30
        assertEquals(EditCancelResult.TOO_LATE, token.cancel())
        assertFalse(token.isCancelled); assertTrue(token.publicationAdmitted)
        assertEquals(EditPublicationAdmission.ALREADY_ADMITTED, token.admitPublication(20))
        token.committed(); assertTrue(token.publicationCommitted)
        assertEquals(EditCancelResult.TOO_LATE, token.cancel())
    }
    @Test fun expiredDeadlineCannotAdmitAtOrBeyondBoundary() {
        for (now in listOf(20L, 21L, Long.MAX_VALUE)) {
            val token = EditPublicationCancellation { now }
            assertEquals(EditPublicationAdmission.DEADLINE_EXCEEDED, token.admitPublication(20))
            assertTrue(token.isCancelled); assertFalse(token.publicationAdmitted)
        }
    }
    @Test fun concurrentCancelAndAdmissionHaveExactlyOneWinnerForBothEntryPaths() {
        // Ordinary editor and recorded bridge use the very same token/storage admission contract.
        for (entry in listOf("ordinary", "recorded")) repeat(100) {
            val token = EditPublicationCancellation { 10 }; val start = CountDownLatch(1)
            val cancel = AtomicReference<EditCancelResult>(); val admit = AtomicReference<EditPublicationAdmission>()
            val a = Thread { start.await(); cancel.set(token.cancel()) }
            val b = Thread { start.await(); admit.set(token.admitPublication(20)) }
            a.start(); b.start(); start.countDown(); a.join(2000); b.join(2000)
            assertFalse(entry, a.isAlive || b.isAlive)
            when (admit.get()) {
                EditPublicationAdmission.ADMITTED -> { assertEquals(EditCancelResult.TOO_LATE, cancel.get()); assertFalse(token.isCancelled) }
                EditPublicationAdmission.CANCELLED -> { assertEquals(EditCancelResult.ACCEPTED, cancel.get()); assertTrue(token.isCancelled) }
                else -> fail("Unexpected race outcome for $entry")
            }
        }
    }
    @Test fun failedOrUncertainAdmittedTransactionCannotReopenTheSameToken() {
        val token = EditPublicationCancellation { 1 }
        assertEquals(EditPublicationAdmission.ADMITTED, token.admitPublication(10))
        // No committed acknowledgement, as with an IO/transaction failure. Recovery must inspect DB.
        assertFalse(token.publicationCommitted); assertEquals(EditCancelResult.TOO_LATE, token.cancel())
        assertEquals(EditPublicationAdmission.ALREADY_ADMITTED, token.admitPublication(10))
    }
    @Test fun oldTokenCancellationCannotAffectSuccessor() {
        val old = EditPublicationCancellation { 1 }; old.cancel()
        val next = EditPublicationCancellation { 1 }
        repeat(20) { old.cancel(); old.admitPublication(10) }
        assertEquals(EditPublicationAdmission.ADMITTED, next.admitPublication(10))
    }
}
