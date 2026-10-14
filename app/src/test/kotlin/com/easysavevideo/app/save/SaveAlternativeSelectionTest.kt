package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailedIdentity
import org.junit.Assert.*
import org.junit.Test

/** Production owner logic on the JVM; not evidence of Android page or preparation acceptance. */
class SaveAlternativeSelectionTest {
    private val failed = SaveFailedIdentity("failed-direct", 4, TaskState.FAILED,
        TaskMethod.DIRECT, SaveError.UNSUPPORTED_MEDIA)
    private val newer = SaveFailedIdentity("other-failed-direct", 7, TaskState.FAILED,
        TaskMethod.DIRECT, SaveError.METHOD_UNSUPPORTED)
    private data class Selection(val id: Int)

    private fun ticket(owner: SaveAlternativeSelection, identity: SaveFailedIdentity = failed,
        expiresAt: Long = 1_000): SaveAlternativeSelection.Ticket {
        owner.arm(identity, expiresAt)
        return requireNotNull(owner.beginInspection(100))
    }

    private fun bound(owner: SaveAlternativeSelection, selection: Any = Selection(1),
        identity: SaveFailedIdentity = failed, expiresAt: Long = 1_000): SaveAlternativeSelection.Association {
        val ticket = ticket(owner, identity, expiresAt)
        assertTrue(owner.bind(ticket, selection, 101, true))
        return requireNotNull(owner.association(selection, 102, true))
    }

    @Test fun ordinaryInspectionNeverGetsAnAlternative() {
        val owner = SaveAlternativeSelection()
        repeat(3) { assertNull(owner.beginInspection(100)) }
        assertNull(owner.association(Selection(1), 100, true))
    }

    @Test fun nextSuccessfulInspectionBindsTheExactReviewedIdentityAndDeadline() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val ticket = ticket(owner)
        assertSame(failed, ticket.failed)
        assertTrue(owner.bind(ticket, selection, 200, true))
        val association = requireNotNull(owner.association(selection, 300, true))
        assertSame(failed, association.failed)
        assertEquals(ticket.generation, association.generation)
        assertEquals(1_000, association.expiresAt)
        assertSame(association, owner.association(selection, 999, true))
    }

    @Test fun pendingIntentIsConsumedBeforeInspectionReturnsEvenIfDetectionFails() {
        val owner = SaveAlternativeSelection()
        val oldTicket = ticket(owner)
        // No successful detector callback. The next explicit inspection is independent.
        assertNull(owner.beginInspection(200))
        assertFalse(owner.bind(oldTicket, Selection(1), 201, true))
        assertNull(owner.association(Selection(2), 202, true))
        assertNull(owner.beginInspection(203))
    }

    @Test fun unsuccessfulCurrentInspectionCannotLaterBindOrPassItsIntentOn() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        assertFalse(owner.bind(ticket, selection, 200, false))
        assertFalse(owner.bind(ticket, selection, 201, true))
        assertNull(owner.association(selection, 202, true))
        assertNull(owner.beginInspection(203))
    }

    @Test fun inspectionCanBindOnlyOnceAndCannotReplaceItsSelection() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        assertTrue(owner.bind(ticket, selection, 200, true))
        assertFalse(owner.bind(ticket, Selection(2), 201, true))
        assertFalse(owner.bind(ticket, selection, 202, true))
        assertNotNull(owner.association(selection, 203, true))
    }

    @Test fun valueEqualSelectionsAreNotTheSameSelection() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val equalButDistinct = Selection(1)
        assertEquals(selection, equalButDistinct)
        assertNotSame(selection, equalButDistinct)
        val expected = bound(owner, selection)
        assertNull(owner.association(equalButDistinct, 200, true))
        assertSame(expected, owner.association(selection, 201, true))
    }

    @Test fun newInspectionRetiresTheBoundAssociationWithoutReusingItsIntent() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        bound(owner, selection)
        assertNull(owner.beginInspection(200))
        assertNull(owner.association(selection, 201, true))
    }

    @Test fun newFailureReplacesPendingIntent() {
        val owner = SaveAlternativeSelection()
        owner.arm(failed, 1_000)
        owner.arm(newer, 2_000)
        val ticket = requireNotNull(owner.beginInspection(100))
        assertSame(newer, ticket.failed)
        assertEquals(2_000, ticket.expiresAt)
    }

    @Test fun newFailureRetiresInFlightInspectionAndLateBindCannotConsumeNewPending() {
        val owner = SaveAlternativeSelection()
        val oldTicket = ticket(owner)
        owner.arm(newer, 2_000)
        assertFalse(owner.bind(oldTicket, Selection(1), 200, true))
        val next = requireNotNull(owner.beginInspection(201))
        assertSame(newer, next.failed)
        assertNotEquals(oldTicket.generation, next.generation)
    }

    @Test fun newFailureRetiresBoundSelectionAndStaleLookupCannotConsumeNewPending() {
        val owner = SaveAlternativeSelection()
        val oldSelection = Selection(1)
        bound(owner, oldSelection)
        owner.arm(newer, 2_000)
        assertNull(owner.association(oldSelection, 200, false))
        assertSame(newer, requireNotNull(owner.beginInspection(201)).failed)
    }

    @Test fun lateOldBindCannotReplaceOrInvalidateNewBoundOwner() {
        val owner = SaveAlternativeSelection()
        val oldTicket = ticket(owner)
        val oldSelection = Selection(1)
        val newSelection = Selection(2)
        val newAssociation = bound(owner, newSelection, newer, 2_000)
        assertFalse(owner.bind(oldTicket, oldSelection, 1_000, false))
        assertNull(owner.association(oldSelection, 1_000, false))
        assertSame(newAssociation, owner.association(newSelection, 1_001, true))
    }

    @Test fun aForeignTicketWithTheSameGenerationAndIdentityHasNoAuthority() {
        val owner = SaveAlternativeSelection()
        val other = SaveAlternativeSelection()
        val ownTicket = ticket(owner)
        val foreignTicket = ticket(other)
        assertEquals(ownTicket.generation, foreignTicket.generation)
        assertSame(ownTicket.failed, foreignTicket.failed)
        val selection = Selection(1)
        assertFalse(owner.bind(foreignTicket, selection, 200, true))
        assertTrue(owner.bind(ownTicket, selection, 201, true))
    }

    @Test fun aReconstructedTicketCannotBindOrConsumeTheActualTicket() {
        val owner = SaveAlternativeSelection()
        val actual = ticket(owner)
        val reconstructed = SaveAlternativeSelection.Ticket(actual.failed, actual.generation, actual.expiresAt)
        val selection = Selection(1)
        assertFalse(owner.bind(reconstructed, selection, 200, true))
        assertTrue(owner.bind(actual, selection, 201, true))
    }

    @Test fun pendingExpirationIsExclusiveAndPermanentlyConsumesTheIntent() {
        for (now in listOf(1_000L, 1_001L, Long.MAX_VALUE)) {
            val owner = SaveAlternativeSelection()
            owner.arm(failed, 1_000)
            assertNull(owner.beginInspection(now))
            assertNull(owner.beginInspection(999))
        }
    }

    @Test fun ticketExpirationCannotBeReversedByALaterEarlierClockValue() {
        for (now in listOf(1_000L, 1_001L)) {
            val owner = SaveAlternativeSelection()
            val ticket = ticket(owner)
            val selection = Selection(1)
            assertFalse(owner.bind(ticket, selection, now, true))
            assertFalse(owner.bind(ticket, selection, 999, true))
            assertNull(owner.association(selection, 999, true))
            assertNull(owner.beginInspection(999))
        }
    }

    @Test fun associationExpirationIsExclusiveAndPermanentlyRetiresIt() {
        for (now in listOf(1_000L, 1_001L)) {
            val owner = SaveAlternativeSelection()
            val selection = Selection(1)
            bound(owner, selection)
            assertNull(owner.association(selection, now, true))
            assertNull(owner.association(selection, 999, true))
        }
    }

    @Test fun inspectionAndBindingMaySucceedRightBeforeTheSameDeadline() {
        val owner = SaveAlternativeSelection()
        owner.arm(failed, 1_000)
        val ticket = requireNotNull(owner.beginInspection(999))
        val selection = Selection(1)
        assertTrue(owner.bind(ticket, selection, 999, true))
        assertNotNull(owner.association(selection, 999, true))
        assertNull(owner.association(selection, 1_000, true))
    }

    @Test fun lossOfPageFreshnessPermanentlyRetiresOnlyTheMatchingAssociation() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        bound(owner, selection)
        assertNull(owner.association(selection, 200, false))
        assertNull(owner.association(selection, 201, true))
        assertNull(owner.beginInspection(202))
    }

    @Test fun invalidValueEqualSelectionCannotRetireTheActualSelection() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val association = bound(owner, selection)
        assertNull(owner.association(Selection(1), 200, false))
        assertSame(association, owner.association(selection, 201, true))
    }

    @Test fun clearBeforeInspectionRetiresPendingIntent() {
        val owner = SaveAlternativeSelection()
        owner.arm(failed, 1_000)
        owner.clear()
        owner.clear()
        assertNull(owner.beginInspection(100))
    }

    @Test fun clearDuringInspectionRejectsEveryLateCompletion() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        owner.clear()
        repeat(3) { assertFalse(owner.bind(ticket, selection, 200, true)) }
        assertNull(owner.association(selection, 201, true))
        assertNull(owner.beginInspection(202))
    }

    @Test fun clearDuringPreparationPreventsCapturedAssociationFromBeingDelivered() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val captured = bound(owner, selection)
        owner.clear()
        assertNull(owner.association(selection, 200, true))
        assertSame(failed, captured.failed)
        // The captured value carries facts only; retaining it cannot restore owner authority.
        assertNull(owner.association(selection, 201, true))
    }

    @Test fun oldHandoffLookupCannotRetireTheNextOwnerEvenIfSelectionObjectIsReused() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val consumed = bound(owner, selection)
        owner.clear() // Synchronous retirement at handoff, before another intent can be armed.
        val next = bound(owner, selection, newer, 2_000)
        val current = requireNotNull(owner.association(selection, 200, true))
        assertNotEquals(consumed.generation, current.generation)
        assertSame(next, current)
        assertSame(newer, current.failed)
    }

    @Test fun replacementClearAndInspectionNeverMutateTheOldFailedIdentity() {
        val expected = failed.copy()
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val association = bound(owner, selection)
        owner.arm(newer, 2_000)
        owner.beginInspection(200)
        owner.clear()
        assertEquals(expected, failed)
        assertSame(failed, association.failed)
    }

    @Test fun ticketAndAssociationDebugTextDoesNotDiscloseTaskIdentity() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        val ticket = ticket(owner)
        assertTrue(owner.bind(ticket, selection, 200, true))
        val association = requireNotNull(owner.association(selection, 201, true))
        assertEquals("SaveAlternativeSelection.Ticket([redacted])", ticket.toString())
        assertEquals("SaveAlternativeSelection.Association([redacted])", association.toString())
    }

    @Test fun expiredAlternativeDeliveryIsRejectedInsteadOfBecomingOrdinary() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        assertEquals(SaveAlternativeInspectionDelivery.REJECTED,
            resolveAlternativeInspection(owner, ticket, selection, 1_000, true))
        assertNull(owner.association(selection, 999, true))
    }

    @Test fun alternativeFoundAfterClearIsRejectedInsteadOfBecomingOrdinary() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        owner.clear()
        assertEquals(SaveAlternativeInspectionDelivery.REJECTED,
            resolveAlternativeInspection(owner, ticket, selection, 200, true))
        assertNull(owner.association(selection, 201, true))
    }

    @Test fun lateAlternativeFoundCannotReplaceOrClearTheNewOwner() {
        val owner = SaveAlternativeSelection()
        val oldTicket = ticket(owner)
        val newSelection = Selection(2)
        val newAssociation = bound(owner, newSelection, newer, 2_000)
        assertEquals(SaveAlternativeInspectionDelivery.REJECTED,
            resolveAlternativeInspection(owner, oldTicket, Selection(1), 200, true))
        assertSame(newAssociation, owner.association(newSelection, 201, true))
    }

    @Test fun currentOrdinaryDeliveryStaysOrdinaryAndInvalidOrdinaryIsRejected() {
        val owner = SaveAlternativeSelection()
        val selection = Selection(1)
        assertEquals(SaveAlternativeInspectionDelivery.ORDINARY,
            resolveAlternativeInspection(owner, null, selection, 200, true))
        assertEquals(SaveAlternativeInspectionDelivery.REJECTED,
            resolveAlternativeInspection(owner, null, selection, 201, false))
        assertNull(owner.association(selection, 202, true))
    }

    @Test fun currentAlternativeDeliveryBindsOnceAndRepeatedDeliveryIsRejected() {
        val owner = SaveAlternativeSelection()
        val ticket = ticket(owner)
        val selection = Selection(1)
        assertEquals(SaveAlternativeInspectionDelivery.ALTERNATIVE,
            resolveAlternativeInspection(owner, ticket, selection, 200, true))
        val association = requireNotNull(owner.association(selection, 201, true))
        assertEquals(SaveAlternativeInspectionDelivery.REJECTED,
            resolveAlternativeInspection(owner, ticket, selection, 202, true))
        assertSame(association, owner.association(selection, 203, true))
    }
}
