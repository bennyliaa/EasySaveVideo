package com.easysavevideo.app.library

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks accompany real LibraryRuntimeTest execution, not Android/Compose/SAF acceptance. */
class LibraryPickerPageReturnSourceContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun section(text: String, start: String, end: String): String {
        assertTrue(text.contains(start))
        val rest = text.substringAfter(start)
        assertTrue(rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test fun requestRegistersExactRuntimeSelectionAndHostOwnershipBeforeTheOnlyPickerLaunch() {
        val export = source("library/export/AndroidExportRuntime.kt")
        val choose = section(export, "fun chooseBatchFolder(", "fun canConfirmBatchFolder(")
        assertTrue(choose.contains("selection:LibraryBatchSelection,library:AndroidLibraryRuntime"))
        assertTrue(choose.indexOf("library.beginPickerReturn(selection,selected.lifecycle") < choose.indexOf("host.launch("))
        assertTrue(choose.contains("directoryHost===host && directorySelection===selected && !closed && !cancelled.get() && directoryGate.active(ticket)"))
        assertTrue(choose.contains("onStale={if(directorySelection===selected) {cancel();state=state.copy(directoryNotice=DirectoryExportNotice.STALE)}}"))
        assertEquals(1, Regex("ACTION_OPEN_DOCUMENT_TREE").findAll(choose).count())
        assertFalse(choose.contains("registry.accept")); assertFalse(choose.contains("DirectoryBatchCopy"))
        val callback = choose.substringAfter("}) { result,data ->")
        assertTrue(callback.contains("selected.lifecycle.receive()"))
        assertTrue(callback.contains("directoryConfirmation=selected.confirmation"))
        assertFalse(callback.contains("selection.current()")); assertFalse(callback.contains(".isCurrent()"))
        assertFalse(callback.contains("confirmBatchFolderCopy"))
    }

    @Test fun resumeAndResultKeysDriveActualForegroundEffectEvenWithoutObservedFalse() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("private var libraryResumeSequence by mutableLongStateOf(0L)"))
        assertTrue(section(activity, "override fun onResume()", "override fun onPostResume()").contains("libraryResumeSequence++"))
        val effect = section(activity, "LaunchedEffect(library, isForeground, libraryResumeSequence, exports?.state?.directoryConfirmation,", "LaunchedEffect(saves.ready")
        assertTrue(effect.contains("library?.setForeground(isForeground && showingLibrary && !showingEditor && libraryPreviewReference == null)"))
        assertTrue(activity.contains("exports.chooseBatchFolder(batchSelection,library,isCurrent={"))
        assertTrue(section(activity, "override fun onDestroy()", "super.onDestroy()").contains("saves.libraryExports?.detachBatchFolderPicker(folderPickerHost)"))
    }

    @Test fun confirmationRequiresAllExistingProofPlusRevalidatedLeaseBeforeAnySubmission() {
        val export = source("library/export/AndroidExportRuntime.kt")
        for ((start, end) in listOf("fun canConfirmBatchFolder(" to "fun confirmBatchFolderCopy(",
            "fun confirmBatchFolderCopy(" to "private fun batchReferences(")) {
            val body = section(export, start, end)
            assertTrue(body.contains("!closed && selection.uri!=null && !cancelled.get() && selection.current() && selection.pageReturn?.isCurrent()==true"))
        }
        val confirm = section(export, "fun confirmBatchFolderCopy(", "private fun batchReferences(")
        assertTrue(confirm.indexOf("consumeDirectoryBatchConfirmation(") < confirm.indexOf("selection.pageReturn?.close()"))
        assertTrue(confirm.indexOf("selection.pageReturn?.close()") < confirm.indexOf("submit {"))
        val strict = source("library/LibraryBatchSelection.kt")
        assertTrue(strict.contains("selection.query==state.query && LibraryBatchSelection.admit(selection.references,state)!=null"))
    }

    @Test fun cancellationDetachAndHostReplacementRetireOnlyTheOwnedLease() {
        val export = source("library/export/AndroidExportRuntime.kt")
        assertTrue(section(export, "internal fun attachBatchFolderPicker(", "internal fun detachBatchFolderPicker(")
            .contains("if(directoryHost!==host && directorySelection!=null)cancel()"))
        val detach = section(export, "internal fun detachBatchFolderPicker(", "fun chooseBatchFolder(")
        assertTrue(detach.contains("if(directoryHost!==host)return")); assertTrue(detach.contains("if(directorySelection!=null)cancel()"))
        val cancel = section(export, "fun cancel()", "fun retry()")
        assertTrue(cancel.indexOf("selection.pageReturn?.close()") < cancel.indexOf("selection.lifecycle.cancel()"))
        assertTrue(source("library/AndroidLibraryRuntime.kt").contains("if (pickerPageReturn !== lease) return"))
    }

    @Test fun existingBoundedReadGuardsAndStaleCursorFallbackAreNotWeakened() {
        val runtime = source("library/AndroidLibraryRuntime.kt")
        assertTrue(runtime.contains("load(lease.selection.query, retryPending = true, returningPicker = lease)"))
        assertTrue(runtime.contains("lease.intentEpoch == userIntentEpoch"))
        assertTrue(runtime.contains("lease.selection.query == state.query"))
        assertTrue(runtime.contains("generation != token ||"))
        assertTrue(runtime.contains("(returningPicker != null && !ownsPickerReturn(returningPicker))"))
        assertTrue(runtime.contains("if (failure.code == LibraryError.STALE_PAGE && query.cursor != null) load(query.copy(cursor = null))"))
        assertTrue(runtime.contains("else if (failure.code == LibraryError.NOT_FOUND && query.folderId != null) load(query.copy(folderId = null, cursor = null))"))
        assertTrue(runtime.contains("ownsPickerReturn(lease) && lease.revalidated && batchSelectionIsCurrent(lease.selection, state)"))
        assertFalse(runtime.contains("confirmBatchFolderCopy"))
        val lease = source("library/LibraryPickerPageReturn.kt")
        assertFalse(lease.contains("android.net")); assertFalse(lease.contains("Serializable"))
        assertFalse(lease.contains("Uri")); assertFalse(lease.contains("savedState"))
    }
}
