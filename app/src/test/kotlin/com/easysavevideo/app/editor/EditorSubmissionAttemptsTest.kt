package com.easysavevideo.app.editor

import com.easysavevideo.app.editor.ui.EditorPhase
import com.easysavevideo.app.editor.ui.EditorSubmitGuard
import com.easysavevideo.app.editor.ui.EditorViewState
import com.easysavevideo.app.library.destination.OrdinaryDestinationReceipt
import com.easysavevideo.app.library.destination.OrdinaryDestinationSelectionResult
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import org.junit.Assert.*
import org.junit.Test

class EditorSubmissionAttemptsTest {
    @Test fun synchronousRejectionAcknowledgesOriginalLatchAndAllowsRetry() {
        val attempts = EditorSubmissionAttempts()
        val clicks = EditorSubmitGuard()
        assertTrue(clicks.trySubmit(0, true))
        val attempt = requireNotNull(attempts.begin(0))
        assertTrue(clicks.isPending(0))
        assertEquals(1L, attempts.finish(attempt))
        assertNull(attempts.finish(attempt))
        assertFalse(clicks.isPending(1))
        assertTrue(clicks.trySubmit(1, true))
        assertNotNull(attempts.begin(1))
    }

    @Test fun duplicateSubmitFromRecreatedCompositionCannotUnlockOrReplacePendingAttempt() {
        val attempts = EditorSubmissionAttempts()
        val original = requireNotNull(attempts.begin(7))
        val recreatedClicks = EditorSubmitGuard()
        assertTrue(recreatedClicks.trySubmit(7, true))
        assertNull(attempts.begin(7))
        assertNull(attempts.begin(8))
        assertTrue(attempts.isCurrent(original))
        assertEquals(8L, attempts.finish(original))
    }

    @Test fun pendingRuntimeStateKeepsAllEditingDisabledAcrossStateCopiesAndRecreation() {
        val state = EditorViewState(submissionRevision = 3, submissionPending = true)
        assertFalse(state.editable)
        assertFalse(state.copy(notice = "fixture").editable)
        assertTrue(state.copy().submissionPending)
        val recreatedClicks = EditorSubmitGuard()
        assertFalse(recreatedClicks.trySubmit(state.submissionRevision, state.editable))
        assertTrue(state.copy(submissionRevision = 4, submissionPending = false).editable)
    }

    @Test fun lifecycleRetirementIsOnceAndOldCompletionCannotUnlockNewAttempt() {
        val attempts = EditorSubmissionAttempts()
        val old = requireNotNull(attempts.begin(10))
        assertEquals(11L, attempts.finish(old))
        val fresh = requireNotNull(attempts.begin(11))
        repeat(4) { assertNull(attempts.finish(old)) }
        assertTrue(attempts.isCurrent(fresh))
        assertFalse(attempts.isCurrent(old))
        assertEquals(12L, attempts.finish(fresh))
        assertNull(attempts.finish(fresh))
    }

    @Test fun everyDestinationTerminalReleasesOnlyItsMatchingEditorLatch() {
        val admission = OrdinaryDestinationAdmission("creation", OrdinaryDestinationChoice.APP_LIBRARY,
            "operation", "grant")
        val results = listOf(OrdinaryDestinationSelectionResult.Accepted(admission),
            OrdinaryDestinationSelectionResult.Cancelled, OrdinaryDestinationSelectionResult.Rejected,
            OrdinaryDestinationSelectionResult.Failed)
        results.forEach { result ->
            val attempts = EditorSubmissionAttempts()
            var state = EditorViewState(submissionPending = true)
            val attempt = requireNotNull(attempts.begin(state.submissionRevision))
            var creations = 0
            val receipt = OrdinaryDestinationReceipt({ creations++ }, {
                val revision = attempts.finish(attempt)
                if (revision != null) state = state.copy(submissionRevision = revision, submissionPending = false)
            })
            assertFalse(state.editable)
            receipt.complete(result)
            assertEquals(1L, state.submissionRevision)
            assertTrue(state.editable)
            val fresh = requireNotNull(attempts.begin(state.submissionRevision))
            state = state.copy(submissionPending = true)
            results.forEach { assertFalse(receipt.complete(it)) }
            assertEquals(1L, state.submissionRevision)
            assertFalse(state.editable)
            assertTrue(attempts.isCurrent(fresh))
            assertEquals(if (result is OrdinaryDestinationSelectionResult.Accepted) 1 else 0, creations)
        }
    }

    @Test fun acceptedMediaTaskStillLocksControlsAfterAdmissionAcknowledgement() {
        val attempts = EditorSubmissionAttempts()
        val attempt = requireNotNull(attempts.begin(0))
        val processing = EditorViewState(phase = EditorPhase.PREPARING, submissionPending = true)
        val state = processing.copy(submissionRevision = requireNotNull(attempts.finish(attempt)), submissionPending = false)
        assertTrue(state.busy)
        assertFalse(state.editable)
        assertFalse(state.submissionPending)
    }

    @Test fun synchronousReceiptCompletionCannotBeInstalledAsCurrentPendingOwner() {
        val attempts = EditorSubmissionAttempts()
        val old = requireNotNull(attempts.begin(0))
        val receipt = OrdinaryDestinationReceipt({}, { attempts.finish(old) })
        receipt.complete(OrdinaryDestinationSelectionResult.Rejected)
        val next = requireNotNull(attempts.begin(1))
        assertFalse(attempts.isCurrent(old))
        assertTrue(attempts.isCurrent(next))
        assertNull(attempts.finish(old))
    }
}
