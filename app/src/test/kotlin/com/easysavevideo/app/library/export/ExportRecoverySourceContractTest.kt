package com.easysavevideo.app.library.export

import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Real wiring/resource contracts supplement isolated logic tests; no Android lifecycle acceptance. */
class ExportRecoverySourceContractTest {
    private fun file(path: String) = String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
    private fun source(path: String) = file("src/main/kotlin/com/easysavevideo/app/$path")
    private val runtime get() = source("library/export/AndroidExportRuntime.kt")
    private val controller get() = source("library/export/ExportRecoveryCheck.kt")
    private val ui get() = source("library/LibraryExportStatus.kt")
    private fun section(text: String, start: String, end: String): String {
        assertTrue("Missing $start", text.contains(start))
        val rest = text.substringAfter(start)
        assertTrue("Missing $end", rest.contains(end))
        return rest.substringBefore(end)
    }
    private fun ordered(text: String, vararg pieces: String) {
        var offset = 0
        for (piece in pieces) {
            val found = text.indexOf(piece, offset)
            assertTrue("Missing/out of order: $piece", found >= 0)
            offset = found + piece.length
        }
    }

    @Test fun startupAndExplicitRetryUseOneBoundedControllerAndOriginalJournalPorts() {
        val setup = section(runtime, "private val recoveryChecks =", "internal fun observeDestinationState")
        assertTrue(setup.contains("enqueue = { work -> worker.execute(work) }"))
        assertTrue(setup.contains("post = { update -> main.post(update) }"))
        assertTrue(setup.contains("isRuntimeCurrent = { !closed }"))
        assertTrue(setup.contains("stopInterrupted().forEach(coordinator::cancel)"))
        assertTrue(setup.contains("recoverPending = { coordinator.recoverPending() }"))
        assertTrue(setup.contains("releaseFinishedGrant = port::releaseFinishedGrant"))
        assertTrue(setup.contains("readReviews = { readReviews() }"))
        assertEquals(1, Regex("coordinator\\.recoverPending\\(\\)").findAll(runtime).count())
        assertTrue(runtime.contains("private val worker = lane(\"esv-library-export\", 2)"))
        assertTrue(setup.substringAfter("init {").contains("recoveryChecks.start()"))
        val retry = section(runtime, "fun retry()", "fun unverifiedPage")
        ordered(retry, "requireMain()", "!admit()", "canCancel = false", "recoveryChecks.start()")
        for (forbidden in listOf("request(", "journal.create", "coordinator.run", "chooseBatchFolder", "submit {",
            "offerExportRecoveryRetry", "foreground", "activeDestination")) assertFalse(retry.contains(forbidden))
        assertTrue(source("library/destination/AndroidOrdinaryDestinationRuntime.kt")
            .contains("fun reconcile() { requireMain(); exports?.retry(); refresh() }"))
    }

    @Test fun fullRecoverySequenceFinishesReviewCollectionBeforeAnyCheckedResult() {
        val work = section(controller, "enqueue {", "return true")
        ordered(work, "claim(attempt)", "stopInterrupted()", "recoverPending()", "releaseFinishedGrant(record)",
            "val reviews = readReviews()", "complete(attempt, ExportRecoveryResult(reviews, interrupted), null)")
        assertTrue(work.contains("complete(attempt, null, ExportRecoveryFailure.INCOMPLETE)"))
        assertTrue(work.contains("complete(attempt, null, ExportRecoveryFailure.QUEUE_BUSY)"))
        assertFalse(work.contains("UUID")); assertFalse(work.contains("ExportRequest("))
    }

    @Test fun onlyExactCurrentAttemptCanPublishAndOrdinaryInvalidationDoesNotOwnBusy() {
        assertTrue(controller.contains("active === attempt"))
        assertTrue(controller.contains("!closed && isRuntimeCurrent()"))
        assertTrue(controller.contains("if (!owns(attempt)) return@post"))
        assertTrue(controller.contains("if (!owns(attempt) || attempt.started) return false"))
        assertTrue(controller.contains("onUpdate(snapshot, null, false)"))
        assertTrue(controller.contains("onUpdate(snapshot, if (failed == null) result else null, true)"))
        val setup = section(runtime, "private val recoveryChecks =", "init {")
        assertTrue(setup.contains("busy = if (ownsBusy) check.status == ExportRecoveryStatus.CHECKING else reviewed.busy"))
        assertTrue(setup.contains("canCancel = if (ownsBusy) false else reviewed.canCancel"))
        assertTrue(setup.contains("result?.let { state.withReviews(it.reviews) } ?: state"))
    }

    @Test fun startupCheckingBlocksAdmitBeforePostedSnapshotAndCloseRetiresOnlyItsRecovery() {
        val admit = section(runtime, "private fun admit()", "private fun request(")
        assertTrue(admit.contains("closed || state.busy || directorySelection!=null || recoveryChecks.checking()"))
        assertTrue(admit.contains("activeShareBatch=null;batchActive=false"))
        assertTrue(admit.contains("operationId=null"))
        val close = section(runtime, "override fun close()", "private fun admit()")
        ordered(close, "if (state.busy && !recoveryChecks.checking()) cancel()", "closed = true", "recoveryChecks.close()", "worker.shutdown()")
        assertTrue(controller.contains("override fun close() { closed = true; active = null }"))
    }

    @Test fun ordinaryRefreshAndNoticeChangesCannotCompleteFailedRecoveryOrInventCounts() {
        val refresh = section(runtime, "private fun refreshReviews(", "private fun readReviews(")
        assertTrue(refresh.contains("val reviews = readReviews(page)"))
        assertTrue(refresh.contains("state = state.withReviews(reviews)"))
        assertFalse(refresh.contains("recoveryCheck")); assertFalse(refresh.contains("CHECKED"))
        val collect = section(runtime, "private fun readReviews(", "private fun ExportViewState.withReviews(")
        ordered(collect, "journal.listUnverified(page)", "journal.counts()", "val views =", "return ReviewSnapshot(")
        assertFalse(collect.contains("main.post")); assertFalse(collect.contains("state ="))
        val clear = section(runtime, "fun clearNotice()", "override fun close()")
        assertFalse(clear.contains("recoveryCheck"))
        val submit = section(runtime, "private fun submit(block:", "private fun submitControl")
        assertTrue(submit.contains("val reviewsAvailable = try { refreshReviews(); true } catch (_: Exception) { false }"))
        assertTrue(submit.contains("recoverable = counts?.unfinished ?: state.recoverable"))
        assertTrue(submit.contains("if (exportRecoveryReviewUnavailable(counts != null, reviewsAvailable)) recoveryChecks.requireRecovery()"))
        assertFalse(runtime.contains("maxOf(1L, state.recoverable)"))
    }

    @Test fun liveActivityRouteForegroundAndRuntimeAreReadAtClickThroughActualUiChain() {
        val activity = source("MainActivity.kt")
        val callback = section(activity, "exportRecoveryIsCurrent = { expected ->", "editorDiagnostic =")
        ordered(callback, "isForeground && activeDestination == EsvDestination.LIBRARY", "libraryPreviewReference == null && previewReceipt == null",
            "saves.segmentRecording?.previewActive != true", "saves.libraryExports === expected", "!isDestroyed && !isFinishing")
        val screen = source("library/LibraryScreen.kt")
        assertTrue(screen.contains("exportRecoveryIsCurrent: (AndroidExportRuntime) -> Boolean = { false }"))
        assertTrue(screen.contains("LibraryExportStatus(it, state.foreground) { exportRecoveryIsCurrent(it) }"))
        assertTrue(ui.contains("val currentAdmission by rememberUpdatedState(isCurrent)"))
        assertTrue(ui.contains("currentForeground && currentRuntime === exports && currentAdmission() && !exports.state.busy"))
        assertTrue(ui.contains("offerExportRecoveryRetry(exports.state.recoveryCheck, exports.state.recoverable)"))
        assertTrue(ui.contains("DisposableEffect(retryAction) { onDispose { retryAction.close() } }"))
        assertTrue(ui.contains("retryAction::invoke"))
    }

    @Test fun pageLeaveAndBackgroundRetainExistingBatchContractWithoutCancellingOrRetryingRecovery() {
        val background = section(runtime, "fun cancelBatchForBackground()", "internal fun attachBatchFolderPicker")
        assertTrue(background.contains("if(directorySelection?.lifecycle?.expectedPickerPause()==true)return"))
        assertTrue(background.contains("if(batchActive && state.busy)cancel()"))
        assertTrue(background.contains("fun cancelBatchForLeavingLibrary() {requireMain();if(batchActive && state.busy)cancel()}"))
        assertFalse(background.contains("recoveryChecks")); assertFalse(background.contains("retry()"))
        val activity = source("MainActivity.kt")
        val pause = section(activity, "override fun onPause()", "override fun onDestroy()")
        assertFalse(pause.contains("retry()")); assertFalse(pause.contains("recoveryChecks"))
    }

    @Test fun retryAndPersistentFailureAreLocalizedAndIndependentOfPositiveCounts() {
        assertTrue(ui.contains("if (offerExportRecoveryRetry(recovery, state.recoverable))"))
        assertTrue(ui.contains("ExportRecoveryStatus.FAILED ->"))
        assertTrue(ui.contains("recovery.status == ExportRecoveryStatus.CHECKING && recovery.failure != null"))
        assertTrue(ui.contains("enabled = foreground && !state.busy && recovery.status != ExportRecoveryStatus.CHECKING"))
        val values = resources("values"); val chinese = resources("values-zh-rCN")
        assertEquals(values.keys, chinese.keys)
        assertEquals(7, values.size)
        for (key in values.keys) {
            assertTrue(values.getValue(key).isNotBlank()); assertTrue(chinese.getValue(key).isNotBlank())
            assertTrue("Missing UI resource $key", ui.contains("R.string.$key"))
        }
        assertTrue(values.getValue("export_recovery_scope").contains("continue an already accepted export"))
        assertTrue(chinese.getValue("export_recovery_scope").contains("继续已接受但尚未完成的导出"))
        assertFalse(ui.contains("核对并重试未完成操作"))
    }

    @Test fun allDirectSourceAndResourceReadsAreGradleInputs() {
        val build = file("build.gradle.kts")
        val sources = section(build, "val contractSourceInputs = files(", "tasks.withType<Test>()")
        for (name in listOf("ExportRecoveryCheck.kt", "AndroidExportRuntime.kt", "LibraryExportStatus.kt",
            "MainActivity.kt", "LibraryScreen.kt", "AndroidOrdinaryDestinationRuntime.kt")) assertTrue(sources.contains(name))
        val resources = section(build, "val contractResourceInputs =", "val contractSourceInputs =")
        assertTrue(resources.contains("\"export_recovery.xml\""))
        assertTrue(resources.contains("\"values\", \"values-zh-rCN\""))
    }

    private fun resources(locale: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Paths.get("src/main/res/$locale/export_recovery.xml").toFile())
        val entries = document.getElementsByTagName("string")
        return (0 until entries.length).associate { index ->
            val entry = entries.item(index)
            entry.attributes.getNamedItem("name").nodeValue to entry.textContent
        }
    }
}
