package com.easysavevideo.app.editor

import com.easysavevideo.app.editor.ui.EditorPhase
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Pure commands and real atomic publication tokens; no Android UI execution is claimed. */
class EditorBackPolicyTest {
    private fun confirmation(gate: EditorBackGate, page: EditorBackPage, token: EditPublicationCancellation,
        operation: String = "operation", phase: EditorPhase = EditorPhase.PROCESSING): EditorBackConfirmation =
        (gate.request(page, true, phase, token, operation) as EditorBackRequest.ConfirmStop).confirmation

    @Test fun everyPhaseHasAnExplicitPolicyAndPublishingIsOnlyStatus() {
        EditorPhase.entries.forEach { phase ->
            val expected = when (phase) {
                EditorPhase.PROCESSING, EditorPhase.VERIFYING -> EditorBackDecision.CONFIRM_STOP
                EditorPhase.PUBLISHING -> EditorBackDecision.CHECK_PUBLICATION
                else -> EditorBackDecision.LEGACY
            }
            assertEquals(phase.name, expected, editorBackDecision(phase))
        }
        for (admitted in listOf(false, true)) {
            val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation { 1 }
            if (admitted) token.admitPublication(10)
            assertSame(EditorBackRequest.CheckPublication, gate.request(page, true, EditorPhase.PUBLISHING, token, "operation"))
            assertFalse(token.isCancelled)
            assertEquals(admitted, token.publicationAdmitted)
            assertFalse(gate.consumeLegacy(page, true, EditorPhase.PUBLISHING))
        }
    }

    @Test fun backgroundMissingOwnerAndForgedPageCannotIssueACommand() {
        val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation()
        assertSame(EditorBackRequest.Ignored, gate.request(page, false, EditorPhase.PROCESSING, token, "operation"))
        assertSame(EditorBackRequest.Ignored, gate.request(EditorBackPage(), true, EditorPhase.PROCESSING, token, "operation"))
        assertSame(EditorBackRequest.Ignored, gate.request(page, true, EditorPhase.PROCESSING, null, "operation"))
        assertSame(EditorBackRequest.Ignored, gate.request(page, true, EditorPhase.PROCESSING, token, null))
        assertFalse(token.isCancelled)
    }

    @Test fun repeatedBackUsesOneDialogAndContinueOrDismissCannotCancel() {
        val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation { 1 }
        val first = confirmation(gate, page, token)
        repeat(100) { assertSame(first, confirmation(gate, page, token)) }
        gate.dismiss(page, first)
        assertNull(gate.consume(page, first, true, token, "operation"))
        assertFalse(token.isCancelled)
        val second = confirmation(gate, page, token)
        assertNotSame(first, second)
        gate.dismiss(page, first) // Delayed old onDispose must not retire the new dialog.
        assertSame(token, gate.consume(page, second, true, token, "operation"))
    }

    @Test fun confirmationIsConsumedBeforeAnActionCanReenterIt() {
        val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation { 1 }
        val command = confirmation(gate, page, token)
        val accepted = gate.consume(page, command, true, token, "operation")
        assertSame(token, accepted)
        assertNull(gate.consume(page, command, true, token, "operation"))
        assertEquals(EditCancelResult.ACCEPTED, accepted!!.cancel())
        repeat(20) { assertNull(gate.consume(page, command, true, token, "operation")) }
    }

    @Test fun matchingTokenAndOperationAreBothRequiredAndStaleFailureIsOneShot() {
        val gate = EditorBackGate(); val page = gate.openPage()
        val old = EditPublicationCancellation(); val next = EditPublicationCancellation()
        val command = confirmation(gate, page, old)
        assertNull(gate.consume(page, command, true, next, "operation"))
        assertNull(gate.consume(page, command, true, old, "operation"))
        val newCommand = confirmation(gate, page, next, "next")
        assertNull(gate.consume(page, newCommand, true, next, "other"))
        assertFalse(old.isCancelled); assertFalse(next.isCancelled)
    }

    @Test fun oldOperationCompletionCannotCancelItsSuccessorEvenWithSameOperationText() {
        val gate = EditorBackGate(); val page = gate.openPage()
        val old = EditPublicationCancellation(); val next = EditPublicationCancellation()
        val oldCommand = confirmation(gate, page, old)
        assertNull(gate.consume(page, oldCommand, true, null, null))
        val nextCommand = confirmation(gate, page, next)
        assertNull(gate.consume(page, oldCommand, true, next, "operation"))
        assertSame(next, gate.consume(page, nextCommand, true, next, "operation"))
        assertFalse(next.isCancelled)
    }

    @Test fun backgroundResumeReentryAndNewActivityPermanentlyRevokeOldPageAndDialog() {
        for (boundary in listOf("background", "page-disposal", "new-activity")) {
            val gate = EditorBackGate(); val oldPage = gate.openPage(); val token = EditPublicationCancellation()
            val oldCommand = confirmation(gate, oldPage, token)
            when (boundary) {
                "background" -> gate.revoke()
                "page-disposal" -> gate.closePage(oldPage)
            }
            val newPage = gate.openPage()
            val newCommand = confirmation(gate, newPage, token)
            gate.closePage(oldPage); gate.dismiss(oldPage, oldCommand)
            assertTrue(boundary, gate.isCurrent(newPage))
            assertNull(boundary, gate.consume(oldPage, oldCommand, true, token, "operation"))
            assertSame(boundary, EditorBackRequest.Ignored, gate.request(oldPage, true, EditorPhase.PROCESSING, token, "operation"))
            assertFalse(boundary, gate.consumeLegacy(oldPage, true, EditorPhase.IDLE))
            assertSame(boundary, token, gate.consume(newPage, newCommand, true, token, "operation"))
        }
    }

    @Test fun foregroundAloneCannotReviveAnInvalidCommandAndForgedCommandsCannotRetireCurrent() {
        val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation()
        val command = confirmation(gate, page, token)
        assertNull(gate.consume(page, EditorBackConfirmation(), true, token, "operation"))
        assertNull(gate.consume(page, command, false, token, "operation"))
        assertNull(gate.consume(page, command, true, token, "operation"))
        assertFalse(token.isCancelled)
    }

    @Test fun validConfirmationCanAdvanceToPublishingAndOnlyAtomicAdmissionDecidesOutcome() {
        for (phase in listOf(EditorPhase.PROCESSING, EditorPhase.VERIFYING)) {
            for (expected in EditCancelResult.entries) {
                val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation { 1 }
                val command = confirmation(gate, page, token, phase = phase)
                // The worker can now be in PUBLISHING. No fresh Back request replaces this dialog.
                when (expected) {
                    EditCancelResult.ACCEPTED -> Unit
                    EditCancelResult.ALREADY_CANCELLED -> token.cancel()
                    EditCancelResult.TOO_LATE -> token.admitPublication(10)
                }
                val owner = gate.consume(page, command, true, token, "operation")
                assertSame(token, owner)
                assertEquals(expected, owner!!.cancel())
                assertEquals(expected == EditCancelResult.TOO_LATE, token.publicationAdmitted)
            }
        }
    }

    @Test fun aNewStatusOrLegacyRequestRetiresTheSupersededStopDialog() {
        for (phase in listOf(EditorPhase.PUBLISHING, EditorPhase.CANCELLING, EditorPhase.SUCCEEDED)) {
            val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation()
            val command = confirmation(gate, page, token)
            gate.request(page, true, phase, token, "operation")
            assertNull(gate.consume(page, command, true, token, "operation"))
            assertFalse(token.isCancelled)
        }
    }

    @Test fun confirmedStopAndPublicationRaceStillHaveExactlyOneAtomicWinner() {
        repeat(100) {
            val gate = EditorBackGate(); val page = gate.openPage(); val token = EditPublicationCancellation { 1 }
            val command = confirmation(gate, page, token, phase = EditorPhase.VERIFYING)
            val validated = gate.consume(page, command, true, token, "operation")!!
            val start = CountDownLatch(1)
            val admission = AtomicReference<EditPublicationAdmission>()
            val worker = Thread { start.await(); admission.set(token.admitPublication(10)) }
            worker.start(); start.countDown()
            val result = validated.cancel()
            worker.join(2_000); assertFalse(worker.isAlive)
            when (admission.get()) {
                EditPublicationAdmission.ADMITTED -> { assertEquals(EditCancelResult.TOO_LATE, result); assertFalse(token.isCancelled) }
                EditPublicationAdmission.CANCELLED -> { assertEquals(EditCancelResult.ACCEPTED, result); assertTrue(token.isCancelled) }
                else -> fail("Unexpected atomic race outcome")
            }
            assertNull(gate.consume(page, command, true, token, "operation"))
        }
    }

    @Test fun laterExplicitReturnRetainsLegacyPhasesAndIsItselfOneShot() {
        EditorPhase.entries.forEach { phase ->
            val gate = EditorBackGate(); val page = gate.openPage()
            val expected = editorBackDecision(phase) == EditorBackDecision.LEGACY
            assertFalse(gate.consumeLegacy(page, false, phase))
            assertEquals(phase.name, expected, gate.consumeLegacy(page, true, phase))
            if (expected) {
                assertFalse(gate.consumeLegacy(page, true, phase))
                assertFalse(gate.isCurrent(page))
            }
        }
    }
}
