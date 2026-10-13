package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import com.easysavevideo.library.destination.OrdinaryDestinationPreference
import org.junit.Assert.*
import org.junit.Test

class OrdinaryDestinationReceiptTest {
    private val admission = OrdinaryDestinationAdmission("creation", OrdinaryDestinationChoice.APP_LIBRARY,
        "operation", "grant")
    private val results = listOf(OrdinaryDestinationSelectionResult.Accepted(admission),
        OrdinaryDestinationSelectionResult.Cancelled, OrdinaryDestinationSelectionResult.Rejected,
        OrdinaryDestinationSelectionResult.Failed)

    @Test fun sourceFenceSeparatesCurrentChangedAndFailureWithoutRethrowingOrRetrying() {
        assertEquals(OrdinaryDestinationSourceCheck.CURRENT, ordinaryDestinationSourceCheck { true })
        assertEquals(OrdinaryDestinationSourceCheck.CHANGED, ordinaryDestinationSourceCheck { false })
        var calls = 0
        assertEquals(OrdinaryDestinationSourceCheck.FAILED, ordinaryDestinationSourceCheck {
            calls++; throw IllegalStateException("fixture")
        })
        assertEquals(1, calls)
    }

    @Test fun everyTerminalResultIsAcknowledgedExactlyOnceAndOnlyAcceptedCreates() {
        results.forEach { result ->
            val accepted = mutableListOf<OrdinaryDestinationAdmission>()
            val completed = mutableListOf<OrdinaryDestinationSelectionResult>()
            val receipt = OrdinaryDestinationReceipt(accepted::add, completed::add)
            assertTrue(receipt.complete(result))
            results.forEach { assertFalse(receipt.complete(it)) }
            assertEquals(listOf(result), completed)
            assertEquals(if (result is OrdinaryDestinationSelectionResult.Accepted) listOf(admission) else emptyList<OrdinaryDestinationAdmission>(), accepted)
        }
    }

    @Test fun waitingForAskOrDurableAdmissionDoesNotAcknowledgeEarly() {
        var callbacks = 0
        val gate = OrdinaryDestinationSelection()
        val ticket = requireNotNull(gate.begin(OrdinaryDestinationPreference.ASK))
        val receipt = OrdinaryDestinationReceipt({ callbacks++ }, { callbacks++ })
        assertNull(automaticDestination(ticket.preference))
        assertEquals(0, callbacks)
        assertNull(gate.begin(OrdinaryDestinationPreference.ASK))
        assertTrue(gate.choose(ticket))
        assertFalse(gate.choose(ticket))
        assertEquals(0, callbacks)
        assertTrue(gate.finish(ticket))
        assertTrue(receipt.complete(OrdinaryDestinationSelectionResult.Accepted(admission)))
        assertEquals(2, callbacks)
    }

    @Test fun cancellationBeforeChoiceCannotCreateAndAcknowledgesOnce() {
        val gate = OrdinaryDestinationSelection()
        val ticket = requireNotNull(gate.begin(OrdinaryDestinationPreference.ASK))
        var terminalCount = 0
        val receipt = OrdinaryDestinationReceipt({ fail("Cancelled selection cannot create") }, {
            assertEquals(OrdinaryDestinationSelectionResult.Cancelled, it); terminalCount++
        })
        assertTrue(gate.cancel(ticket))
        assertTrue(receipt.complete(OrdinaryDestinationSelectionResult.Cancelled))
        assertFalse(gate.choose(ticket))
        assertFalse(gate.finish(ticket))
        assertFalse(receipt.complete(OrdinaryDestinationSelectionResult.Failed))
        assertEquals(1, terminalCount)
    }

    @Test fun cancellationDuringAdmissionFencesLateSuccessAndFailureFromNewSelection() {
        val gate = OrdinaryDestinationSelection()
        val old = requireNotNull(gate.begin(OrdinaryDestinationPreference.ASK))
        val oldResults = mutableListOf<OrdinaryDestinationSelectionResult>()
        val oldReceipt = OrdinaryDestinationReceipt({ fail("Retired creation") }, oldResults::add)
        assertTrue(gate.choose(old))
        assertTrue(gate.cancel(old))
        oldReceipt.complete(OrdinaryDestinationSelectionResult.Cancelled)
        val next = requireNotNull(gate.begin(OrdinaryDestinationPreference.APP_LIBRARY))
        var nextCompleted = 0
        val nextReceipt = OrdinaryDestinationReceipt({}, { nextCompleted++ })
        assertTrue(gate.choose(next))
        assertFalse(gate.finish(old))
        assertFalse(gate.cancel(old))
        assertFalse(oldReceipt.complete(OrdinaryDestinationSelectionResult.Accepted(admission)))
        assertFalse(oldReceipt.complete(OrdinaryDestinationSelectionResult.Failed))
        assertTrue(gate.active(next))
        assertEquals(0, nextCompleted)
        assertEquals(listOf(OrdinaryDestinationSelectionResult.Cancelled), oldResults)
        assertTrue(gate.finish(next))
        nextReceipt.complete(OrdinaryDestinationSelectionResult.Accepted(admission))
        assertEquals(1, nextCompleted)
    }

    @Test fun callbacksAreConsumedBeforeAcceptanceAndTerminalStillRunsWhenAcceptanceThrows() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("fixture")
        lateinit var receipt: OrdinaryDestinationReceipt
        receipt = OrdinaryDestinationReceipt({
            calls += "accepted"
            assertFalse(receipt.complete(OrdinaryDestinationSelectionResult.Cancelled))
            throw failure
        }, {
            calls += "terminal"
            assertTrue(it is OrdinaryDestinationSelectionResult.Accepted)
            assertFalse(receipt.complete(OrdinaryDestinationSelectionResult.Failed))
        })
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            receipt.complete(OrdinaryDestinationSelectionResult.Accepted(admission))
        })
        assertEquals(listOf("accepted", "terminal"), calls)
        assertFalse(receipt.complete(OrdinaryDestinationSelectionResult.Rejected))
    }

    @Test fun throwingTerminalRemainsConsumed() {
        var calls = 0
        val receipt = OrdinaryDestinationReceipt({}, { calls++; throw IllegalStateException("fixture") })
        assertThrows(IllegalStateException::class.java) { receipt.complete(OrdinaryDestinationSelectionResult.Failed) }
        assertFalse(receipt.complete(OrdinaryDestinationSelectionResult.Cancelled))
        assertEquals(1, calls)
    }

    @Test fun terminalCanStartFreshSelectionWithoutOldReceiptConsumingIt() {
        val gate = OrdinaryDestinationSelection()
        val old = requireNotNull(gate.begin(OrdinaryDestinationPreference.ASK))
        var next: OrdinaryDestinationSelection.Ticket? = null
        var nextCompleted = 0
        lateinit var nextReceipt: OrdinaryDestinationReceipt
        val oldReceipt = OrdinaryDestinationReceipt({}, {
            next = requireNotNull(gate.begin(OrdinaryDestinationPreference.ASK))
            nextReceipt = OrdinaryDestinationReceipt({}, { nextCompleted++ })
        })
        assertTrue(gate.cancel(old))
        oldReceipt.complete(OrdinaryDestinationSelectionResult.Failed)
        assertFalse(oldReceipt.complete(OrdinaryDestinationSelectionResult.Accepted(admission)))
        assertFalse(gate.cancel(old))
        assertTrue(gate.active(requireNotNull(next)))
        assertEquals(0, nextCompleted)
        assertTrue(gate.cancel(requireNotNull(next)))
        nextReceipt.complete(OrdinaryDestinationSelectionResult.Cancelled)
        assertEquals(1, nextCompleted)
    }
}
