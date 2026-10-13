package com.easysavevideo.app.library.export

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM source wiring contracts supplement pure policies, not Android/Compose/SAF acceptance. */
class LibraryBatchRecoverySourceContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val runtime get() = source("library/export/AndroidExportRuntime.kt")
    private fun section(text: String, start: String, end: String): String {
        assertTrue("Missing start: $start", text.contains(start))
        val rest = text.substringAfter(start)
        assertTrue("Missing end: $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test fun uiCapturesOpaqueExactSelectionTokenForBothEnablementAndConfirmation() {
        val ui = source("library/LibraryExportStatus.kt")
        assertTrue(ui.contains("val directoryConfirmation = state.directoryConfirmation"))
        assertTrue(ui.contains("if(directoryConfirmation!=null)EsvButton"))
        assertTrue(ui.contains("{ exports.confirmBatchFolderCopy(directoryConfirmation) }"))
        assertTrue(ui.contains("foreground && exports.canConfirmBatchFolder(directoryConfirmation)"))
        assertFalse(ui.contains("exports::confirmBatchFolderCopy"))
        assertFalse(runtime.contains("fun confirmBatchFolderCopy()"))
        assertTrue(runtime.contains("val directoryConfirmation:DirectoryBatchConfirmation? = null"))
        val selection = section(runtime, "private class DirectorySelection", "private var directorySelection")
        assertTrue(selection.contains("val confirmation=DirectoryBatchConfirmation()"))
        val choose = section(runtime, "fun chooseBatchFolder", "fun canConfirmBatchFolder")
        assertTrue(choose.contains("directoryConfirmation=selected.confirmation"))
        assertEquals(1, Regex("ACTION_OPEN_DOCUMENT_TREE").findAll(choose).count())
        assertFalse(choose.contains("DirectoryBatchCopy")); assertFalse(choose.contains("registry.accept"))
        val token = source("library/export/DirectoryBatchConfirmation.kt")
        assertTrue(token.contains("class DirectoryBatchConfirmation internal constructor()"))
        assertFalse(token.contains("data class")); assertFalse(token.contains("Serializable"))
        assertFalse(token.contains("android.net")); assertFalse(token.contains("MediaReadRef"))
    }

    @Test fun productionConsumesOnceBeforeSubmissionAndOnlyCurrentPendingProofFailureCancels() {
        val consume = source("library/export/DirectoryBatchConfirmation.kt")
        assertTrue(consume.indexOf("expected !== current || !lifecycle.canConfirm(true)") < consume.indexOf("if (!isCurrent())"))
        assertTrue(consume.contains("if (lifecycle.confirm(true)) DirectoryConfirmationDecision.CONFIRMED"))
        val confirm = section(runtime, "fun confirmBatchFolderCopy(expected:", "private fun batchReferences")
        val decision = confirm.indexOf("consumeDirectoryBatchConfirmation(expected,selection.confirmation,selection.lifecycle)")
        assertTrue(decision >= 0)
        assertTrue(decision < confirm.indexOf("submit {"))
        assertTrue(confirm.contains("!closed && selection.uri!=null && !cancelled.get() && selection.current()"))
        assertTrue(confirm.contains("DirectoryConfirmationDecision.IGNORED -> return"))
        assertTrue(confirm.contains("DirectoryConfirmationDecision.STALE -> {cancel();state=state.copy(directoryNotice=DirectoryExportNotice.STALE);return}"))
        assertTrue(confirm.contains("directoryConfirmation=null,directoryNotice=DirectoryExportNotice.COPYING"))
        assertFalse(confirm.contains("canConfirmBatchFolder("))
        assertTrue(confirm.contains("val uri=requireNotNull(selection.uri);val cancellation=cancelled"))
    }

    @Test fun rejectedLaneAndTerminalFailureReleaseSelectionAndUiConsentForAnExplicitFreshAttempt() {
        val submit = section(runtime, "private fun submit(block:", "private fun submitControl")
        val release = "directorySelection?.let{directoryGate.cancel(it.ticket)};directorySelection=null;directoryBatchId=null"
        assertEquals(2, Regex(Regex.escape(release)).findAll(submit).count())
        val rejection = submit.substringAfter("catch (_: java.util.concurrent.RejectedExecutionException)")
        assertTrue(rejection.contains(release))
        assertTrue(rejection.contains("busy = false, canCancel = false,directoryConfirmation=null"))
        assertFalse(rejection.contains("worker.execute"))
        val admit = section(runtime, "private fun admit()", "private fun request(")
        assertTrue(admit.contains("closed || state.busy || directorySelection!=null"))
        assertTrue(admit.contains("cancelled = AtomicBoolean(false)"))
        assertTrue(admit.contains("directoryNotice=null,directoryConfirmation=null"))
    }

    @Test fun workerResultsRemainExactOwnerBoundAndJournalCancellationDoesNotReplayCompletedCopies() {
        val confirm = section(runtime, "fun confirmBatchFolderCopy(expected:", "private fun batchReferences")
        assertEquals(2, Regex(Regex.escape("if(!closed && directorySelection===selection)")).findAll(confirm).count())
        assertTrue(confirm.contains("DirectoryBatchCopy(admitted).run(storage.directoryExportBatches,storage.library::item"))
        assertTrue(confirm.contains("cancelled=cancellation::get"))
        assertTrue(confirm.contains("directoryGate.active(selection.ticket)"))
        assertTrue(confirm.contains("storage.directoryExportBatches.outcome(it.operationId)"))
        assertTrue(confirm.contains("storage.directoryExportBatches.started(it.operationId)"))
        assertTrue(confirm.contains("BatchTransferStatus.CANCELLED_BEFORE_START"))
        val retry = section(runtime, "fun retry()", "fun unverifiedPage")
        assertTrue(retry.contains("stopInterrupted().forEach(coordinator::cancel)"))
        assertTrue(retry.contains("coordinator.recoverPending()"))
        assertFalse(retry.contains("DirectoryBatchPlan.create")); assertFalse(retry.contains("chooseBatchFolder"))
    }

    @Test fun actualDestinationListenerOwnsOnlyLibraryExitCancellationIncludingSuccessfulPop() {
        val activity = source("MainActivity.kt")
        assertEquals(1, Regex("cancelBatchForLeavingLibrary\\(\\)").findAll(activity).count())
        val navigate = section(activity, "private fun navigate(", "private fun backTo(")
        assertFalse(navigate.contains("cancelBatchForLeavingLibrary"))
        val back = section(activity, "private fun backTo(", "private lateinit var browser")
        assertTrue(back.contains("if (navController?.popBackStack() != true) navigate(destination)"))
        assertFalse(back.contains("cancelBatchForLeavingLibrary"))
        val listener = section(activity, "val listener = androidx.navigation.NavController.OnDestinationChangedListener", "controller.addOnDestinationChangedListener(listener)")
        assertTrue(listener.contains("val nextDestination = EsvDestination.fromRoute(destination.route)"))
        assertTrue(listener.contains("dispatchLibraryBatchRouteExit(activeDestination, nextDestination)"))
        assertTrue(listener.contains("saves.libraryExports?.cancelBatchForLeavingLibrary()"))
        assertTrue(listener.indexOf("dispatchLibraryBatchRouteExit(") < listener.indexOf("activeDestination = nextDestination"))
        val route = source("library/export/LibraryBatchRouteExit.kt")
        assertTrue(route.contains("previous != EsvDestination.LIBRARY || current == EsvDestination.LIBRARY"))
        assertFalse(route.contains("popBackStack")); assertFalse(route.contains("showingLibrary"))
        assertTrue(runtime.contains("fun cancelBatchForLeavingLibrary() {requireMain();if(batchActive && state.busy)cancel()}"))
    }

    @Test fun backgroundExpectedPickerPauseHostDetachAndCancelRetainExistingOwnershipGates() {
        val background = section(runtime, "fun cancelBatchForBackground()", "fun cancelBatchForLeavingLibrary()")
        assertTrue(background.contains("if(directorySelection?.lifecycle?.expectedPickerPause()==true)return"))
        assertTrue(background.contains("if(batchActive && state.busy)cancel()"))
        val detach = section(runtime, "internal fun detachBatchFolderPicker", "fun chooseBatchFolder")
        assertTrue(detach.contains("if(directoryHost!==host)return"))
        assertTrue(detach.contains("if(directorySelection!=null)cancel()"))
        val cancel = section(runtime, "fun cancel()", "fun retry()")
        assertTrue(cancel.contains("cancelled.set(true)")); assertTrue(cancel.contains("selection.lifecycle.cancel()"))
        assertTrue(cancel.contains("if(!selection.lifecycle.confirmedOnce)"))
        assertTrue(cancel.contains("directorySelection=null"))
        assertTrue(cancel.contains("directoryConfirmation=null,directoryNotice=DirectoryExportNotice.STOPPED"))
        assertTrue(cancel.contains("storage.directoryExportBatches.stop(directory)"))
        assertTrue(source("library/destination/DirectoryPickerLifecycle.kt").contains("phase.compareAndSet(Phase.RECEIVED,Phase.CONFIRMED)"))
    }
}
