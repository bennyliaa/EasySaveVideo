package com.easysavevideo.app.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring complements pure receipt/owner tests. This is not Android/device UI execution. */
class EditorDestinationAcknowledgementContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val editor get() = source("editor/AndroidEditRuntime.kt")
    private val destination get() = source("library/destination/AndroidOrdinaryDestinationRuntime.kt")
    private fun section(text: String, start: String, end: String): String {
        assertTrue("Missing start: $start", text.contains(start))
        val suffix = text.substringAfter(start)
        assertTrue("Missing end: $end", suffix.contains(end))
        return suffix.substringBefore(end)
    }

    @Test fun originalSelectSignatureRemainsAndOptInReceiptCoversEarlyRejectionAndBusyGate() {
        val original = section(destination, "fun select(isCurrent: () -> Boolean, accepted: (OrdinaryDestinationAdmission) -> Unit)",
            "internal fun select")
        assertTrue(original.contains("select(isCurrent, onTerminal = {}, accepted = accepted)"))
        val select = section(destination, "internal fun select", "fun choose")
        assertTrue(select.contains("!ready || savingPreference || choosingFolder"))
        assertTrue(select.contains("ordinaryDestinationSourceCheck(isCurrent)"))
        assertTrue(select.contains("if (pending == null) notice ="))
        assertTrue(select.contains("receipt.complete(if (source == OrdinaryDestinationSourceCheck.FAILED)"))
        assertTrue(select.contains("OrdinaryDestinationSelectionResult.Failed else OrdinaryDestinationSelectionResult.Rejected"))
        val busy = select.substringAfter("if (ticket == null)").substringBefore("pending = Pending")
        assertTrue(busy.contains("receipt.complete(OrdinaryDestinationSelectionResult.Rejected)"))
        assertFalse(busy.contains("notice ="))
        assertTrue(select.contains("if (automatic == null) asking = true else choose(automatic)"))
    }

    @Test fun dialogDismissAndCancelShareTerminalPathAndOwnerSpecificCancelIsFenced() {
        val ui = source("library/destination/OrdinaryDestinationUi.kt")
        assertTrue(ui.contains("val request = runtime.currentSelectionIdentity ?: return"))
        assertTrue(ui.contains("EsvAppDialog(request, { runtime.currentSelectionIdentity === request },"))
        assertTrue(ui.contains("onDismissRequest = { runtime.cancelSelection(request) }"))
        assertTrue(ui.contains("val admission = LocalPrivateInputAdmission.current"))
        assertTrue(ui.contains("onClick = { if (admission()) runtime.cancelSelection(request) }"))
        val capturedCancel = section(destination, "internal fun cancelSelection(expectedIdentity: Any)", "internal fun clearNotice")
        assertTrue(capturedCancel.contains("requireMain()"))
        assertTrue(capturedCancel.contains("if (currentSelectionIdentity !== expectedIdentity) return"))
        assertTrue(capturedCancel.contains("cancelSelection()"))
        val cancel = section(destination, "fun cancelSelection()", "private fun selectionCurrent")
        assertTrue(cancel.contains("pending?.let { completeSelection(it, OrdinaryDestinationSelectionResult.Cancelled) }"))
        assertTrue(cancel.contains("internal fun cancelSelection(receipt: OrdinaryDestinationReceipt)"))
        assertTrue(cancel.contains("if (selection.receipt !== receipt) return"))
        assertFalse(cancel.contains("gate.cancel()"))
    }

    @Test fun completionConsumesOwnerAndSetsNoticeBeforeAnyExternalCallback() {
        val complete = section(destination, "private fun completeSelection", "fun updatePreference")
        assertTrue(complete.contains("if (pending !== selection) return false"))
        assertTrue(complete.contains("gate.finish(selection.ticket)"))
        assertTrue(complete.contains("gate.cancel(selection.ticket)"))
        assertTrue(complete.indexOf("pending = null; asking = false; admitting = false; notice = nextNotice") <
            complete.indexOf("return selection.receipt.complete(result)"))
        assertEquals("}", complete.substringAfter("return selection.receipt.complete(result)").trim())
        val receipt = source("library/destination/OrdinaryDestinationReceipt.kt")
        assertTrue(receipt.indexOf("acceptedCallback = null") < receipt.indexOf("onAccepted?.invoke"))
        assertTrue(receipt.indexOf("terminalCallback = null") < receipt.indexOf("onAccepted?.invoke"))
        assertTrue(receipt.contains("finally {\n            onTerminal(result)"))
    }

    @Test fun delayedAdmissionSuccessFailureAndSourceChangeAllUseMatchingOwnerFence() {
        val choose = section(destination, "fun choose", "fun cancelSelection()")
        assertTrue(choose.contains("if (!selectionCurrent(selection, OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_SELECTION)) return"))
        assertTrue(choose.contains("if (pending !== selection || !gate.active(selection.ticket)) return@post"))
        assertTrue(choose.contains("if (!selectionCurrent(selection, OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION)) return@post"))
        assertTrue(choose.contains("completeSelection(selection, OrdinaryDestinationSelectionResult.Accepted(admitted))"))
        assertTrue(choose.contains("enqueueAdmission(onFailure = { failureNotice ->\n            completeSelection(selection, OrdinaryDestinationSelectionResult.Failed, failureNotice)"))
        val sourceCheck = section(destination, "private fun selectionCurrent", "private fun completeSelection")
        assertTrue(sourceCheck.contains("ordinaryDestinationSourceCheck(selection.current)"))
        assertTrue(sourceCheck.contains("CURRENT -> pending === selection && gate.active(selection.ticket)"))
        assertTrue(sourceCheck.contains("completeSelection(selection, OrdinaryDestinationSelectionResult.Rejected, changedNotice)"))
        assertTrue(sourceCheck.contains("completeSelection(selection, OrdinaryDestinationSelectionResult.Failed,"))
        val enqueue = section(destination, "private fun enqueueAdmission", "private fun requireMain")
        assertTrue(enqueue.contains("enqueueOn(worker, onFailure, action)"))
        assertTrue(enqueue.contains("onFailure(OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE)"))
        assertTrue(enqueue.contains("onFailure(OrdinaryDestinationNoticeCode.LOCATION_BUSY)"))
        assertFalse(enqueue.contains("if (onFailure()) notice = OrdinaryDestinationNoticeCode"))
    }

    @Test fun unavailableAskFolderChoiceDoesNotPrematurelyUnlockWaitingAttempt() {
        val missing = section(destination, "if (choice == OrdinaryDestinationChoice.FOLDER_COPY && selectedFolder == null)",
            "if (!gate.choose(selection.ticket))")
        val asking = missing.substringAfter("if (selection.ticket.preference == OrdinaryDestinationPreference.ASK)").substringBefore("} else")
        assertTrue(asking.contains("notice = OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED"))
        assertFalse(asking.contains("completeSelection"))
        assertTrue(missing.contains("else completeSelection(selection, OrdinaryDestinationSelectionResult.Rejected"))
    }

    @Test fun runtimeOwnsAttemptBeforeSelectingAndStaleAcknowledgementCannotMutateNewState() {
        val submit = section(editor, "fun submit(draft: EditorDraft)", "private fun finishSubmission")
        assertTrue(submit.indexOf("submissionAttempts.begin(state.submissionRevision) ?: return") < submit.indexOf("if (!recovered"))
        assertTrue(submit.contains("finishSubmission(attempt); return"))
        assertTrue(submit.indexOf("state = state.copy(submissionPending = true)") < submit.indexOf("destinations.select("))
        assertTrue(submit.contains("isCurrent = { submissionAttempts.isCurrent(attempt) &&"))
        val current = submit.substringAfter("isCurrent = {").substringBefore("onTerminal =")
        assertFalse(current.contains("state.editable"))
        assertTrue(current.contains("state.draft == draft && admissionGeneration == generation"))
        assertTrue(submit.contains("onTerminal = { finishSubmission(attempt) }"))
        assertTrue(submit.contains("if (submissionAttempts.isCurrent(attempt)) submitSelected(draft, admission)"))
        assertTrue(submit.contains("if (submissionAttempts.isCurrent(attempt)) pendingDestination = receipt"))
        val finish = section(editor, "private fun finishSubmission", "private fun retireSubmission")
        assertTrue(finish.indexOf("submissionAttempts.finish(attempt) ?: return") < finish.indexOf("pendingDestination = null"))
        assertTrue(finish.contains("state = state.copy(submissionRevision = revision, submissionPending = false)"))
        assertFalse(finish.contains("notice ="))
        assertFalse(finish.contains("inspectMissingSources"))
    }

    @Test fun backgroundCancelAndDraftReplacementRetireOnlyEditorsOwnDestinationReceipt() {
        val retire = section(editor, "private fun retireSubmission", "private fun submitSelected")
        assertTrue(retire.contains("val attempt = submissionAttempts.current ?: return"))
        assertTrue(retire.indexOf("finishSubmission(attempt)") < retire.indexOf("receipt?.let(destinations::cancelSelection)"))
        assertFalse(retire.contains("destinations.cancelSelection()"))
        assertFalse(retire.contains("inspectMissingSources"))
        val open = section(editor, "fun open(", "fun setForeground")
        assertTrue(open.indexOf("retireSubmission()") < open.indexOf("val draft = openEditorDraft"))
        val foreground = section(editor, "fun setForeground", "fun changeDraft")
        assertTrue(foreground.contains("if (!value) { admissionGeneration++; cancel() }"))
        val cancel = section(editor, "fun cancel()", "private fun featuresAllowed")
        assertTrue(cancel.indexOf("retireSubmission()") < cancel.indexOf("val cancellation = activeCancellation"))
        val restore = section(editor, "internal fun restoreDraftCheckpoint", "internal fun savedDraftCheckpoint")
        assertTrue(restore.contains("fun ownerPresent() = state.draft.segments.isNotEmpty() || state.busy || state.submissionPending"))
        val change = section(editor, "fun changeDraft", "private fun inspectMissingSources")
        assertTrue(change.contains("if (!state.editable || activeCancellation != null) return"))
    }

    @Test fun acceptedMissingGeometryStillStartsOriginalProbeButWaitingAndNonAcceptedTerminalsDoNot() {
        val inspect = section(editor, "private fun inspectMissingSources", "fun submit(draft")
        assertTrue(inspect.contains("selectionAccepted: Boolean = false"))
        assertTrue(inspect.contains("(state.submissionPending && !selectionAccepted)"))
        assertTrue(inspect.contains("state = state.copy(phase = EditorPhase.PROBING)"))
        assertTrue(inspect.contains("main.post { attempt() }"))
        val accepted = section(editor, "private fun submitSelected", "/** Restores editable parameters only.")
        assertFalse(accepted.contains("state.editable"))
        assertFalse(accepted.contains("submissionRevision ="))
        val missing = accepted.substringAfter("if (request.segments.any { it.source !in state.displayGeometries })").substringBefore("val required")
        assertTrue(missing.contains("inspectMissingSources(selectionAccepted = true); return"))
        assertEquals(1, Regex("inspectMissingSources\\(selectionAccepted = true\\)").findAll(editor).count())
        val terminal = section(editor, "private fun finishSubmission", "private fun submitSelected")
        assertFalse(terminal.contains("inspectMissingSources"))
    }

    @Test fun runtimePendingSurvivesCompositionRecreationAndLocalLatchPrecedesSubmitCallback() {
        val presentation = source("editor/ui/EditorPresentation.kt")
        assertTrue(presentation.contains("val submissionPending: Boolean = false"))
        assertTrue(presentation.contains("phase != EditorPhase.BLOCKED && !submissionPending"))
        assertTrue(presentation.contains("submissionPending: Boolean = this.submissionPending"))
        val screen = source("editor/ui/EditorScreen.kt")
        assertTrue(screen.contains("val pendingSubmit = state.submissionPending || pendingSubmissionRevision == state.submissionRevision"))
        assertTrue(screen.contains("val editable = state.editable && !pendingSubmit"))
        val click = screen.substringAfter("if (guard.trySubmit(state.submissionRevision, editable && error == null))").substringBefore("}, enabled")
        assertTrue(click.indexOf("pendingSubmissionRevision = state.submissionRevision") < click.indexOf("onSubmit(draft)"))
    }
}
