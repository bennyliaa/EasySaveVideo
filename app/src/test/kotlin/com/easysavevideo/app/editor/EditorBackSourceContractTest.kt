package com.easysavevideo.app.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Real production-source wiring checks supplement pure tests; these are not Android UI tests. */
class EditorBackSourceContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val screen get() = source("editor/ui/EditorScreen.kt")
    private val runtime get() = source("editor/AndroidEditRuntime.kt")
    private val gate get() = source("editor/EditorBackPolicy.kt")
    private fun section(text: String, start: String, end: String): String {
        assertTrue("Missing start: $start", text.contains(start))
        val rest = text.substringAfter(start)
        assertTrue("Missing end: $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test fun headerAndSystemBackShareProtectedRequestWithCropAndDialogFirst() {
        assertTrue(screen.contains("BackHandler(onBack = requestBack)"))
        assertTrue(screen.contains("EsvButton(stringResource(R.string.advanced_copy_2), requestBack, modifier = modifier)"))
        val request = section(screen, "val requestBack: () -> Unit", "LaunchedEffect(state.foreground, state.phase, backDialog)")
        assertTrue(request.indexOf("cropSegmentId != null -> cropSegmentId = null") < request.indexOf("onRequestBack()"))
        assertTrue(request.indexOf("dialog != null -> dismissBackDialog(dialog)") < request.indexOf("onRequestBack()"))
        assertTrue(request.contains("EditorBackRequest.Legacy -> { requestedPreview = null; onBack() }"))
        assertFalse(request.contains("onCancel()"))
        assertTrue(screen.contains("val cancelEdit: () -> Unit = { requestedPreview = null; onCancel() }"))
        assertTrue(screen.contains("Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false))"))
    }

    @Test fun dialogDismissalAndContinueOnlyRevokeAndCloseTheMatchingDialog() {
        val dismiss = section(screen, "fun dismissBackDialog", "val requestBack")
        assertTrue(dismiss.contains("onDismissBack(it.confirmation)"))
        assertTrue(dismiss.contains("if (backDialog === dialog) backDialog = null"))
        assertFalse(dismiss.contains("onCancel")); assertFalse(dismiss.contains("onBack()"))
        val dialog = section(screen, "private fun EditorBackDialogContent", "private fun EditorCard")
        assertTrue(dialog.contains("Dialog(onDismissRequest = onDismiss"))
        assertTrue(dialog.contains("R.string.editor_back_continue else R.string.editor_back_check_status"))
        assertTrue(dialog.contains("onClick = onDismiss"))
        assertTrue(dialog.contains("if (confirming) EsvButton"))
        assertFalse(dialog.contains("onBack")); assertFalse(dialog.contains("onCancel"))
        assertFalse(dialog.contains("backTo")); assertFalse(dialog.contains("navigate"))
    }

    @Test fun stopConfirmationHasNoNavigationAndLifecycleDisposalRevokesItsAuthority() {
        assertTrue(screen.contains("var backDialog by remember(backPage)"))
        val shown = section(screen, "backDialog?.let { dialog ->", "/** A fresh identity")
        assertTrue(shown.contains("DisposableEffect(dialog) { onDispose"))
        assertTrue(shown.contains("onDismissBack(it.confirmation)"))
        assertTrue(shown.contains("if (backDialog === dialog && request != null)"))
        assertTrue(shown.contains("onConfirmBackStop(request.confirmation)"))
        assertFalse(shown.contains("onBack()")); assertFalse(shown.contains("onCancel()"))
        val transition = section(screen, "LaunchedEffect(state.foreground, state.phase, backDialog)", "val error =")
        assertTrue(transition.contains("backDialog?.let(::dismissBackDialog)"))
        assertFalse(transition.contains("onBack"))
        assertFalse(runtime.contains("backTo(")); assertFalse(runtime.contains("navigate("))
    }

    @Test fun runtimeValidatesAndConsumesBeforeAnyCancelOrRetirementSideEffect() {
        val confirm = section(runtime, "internal fun confirmBackStop", "internal fun consumeLegacyBack")
        val validation = confirm.indexOf("backGate.consume(page, confirmation, state.foreground, activeCancellation, activeOperation) ?: return null")
        assertTrue(validation >= 0)
        for (sideEffect in listOf("admissionGeneration++", "retireSubmission()", "cancelActive(cancellation)")) {
            assertTrue(sideEffect, validation < confirm.indexOf(sideEffect))
        }
        assertFalse(confirm.contains("EditorPhase.PUBLISHING"))
        assertFalse(confirm.contains("EditCancelResult.TOO_LATE"))
        val consume = section(gate, "fun consume(page:", "/** A queued old Return")
        assertTrue(consume.indexOf("pending = null") < consume.indexOf("return issued.cancellation"))
        assertTrue(consume.contains("cancellation !== issued.cancellation || operation != issued.operation"))
        assertFalse(consume.contains("cancellation.cancel()"))
        val request = section(runtime, "internal fun requestBack", "internal fun dismissBack")
        assertFalse(request.contains("cancel(")); assertFalse(request.contains("retireSubmission"))
    }

    @Test fun hostChecksExactEntryOwnerForegroundAndGenerationBeforeLegacyAndProtectedActions() {
        val activity = source("MainActivity.kt")
        val editorHost = section(activity, "else if (route == EsvDestination.EDITOR && editor != null)",
            "else if (route == EsvDestination.LIBRARY && library != null)")
        assertTrue(editorHost.contains("remember(editor, controller) { controller.currentBackStackEntry }"))
        assertTrue(editorHost.contains("remember(editor, editorBackEntry, editorBackGeneration, isForeground)"))
        assertTrue(editorHost.contains("onDispose { editor.closeBackPage(editorBackPage) }"))
        for (check in listOf("isForeground && !isDestroyed && !isFinishing", "saves.editor === editor",
            "navController === controller", "controller.currentBackStackEntry === editorBackEntry",
            "newTabInputGeneration == editorBackGeneration", "editor.isBackPageCurrent(editorBackPage)")) {
            assertTrue(check, editorHost.contains(check))
        }
        assertTrue(editorHost.contains("if (editorBackHostCurrent()) editor.requestBack(editorBackPage) else EditorBackRequest.Ignored"))
        assertTrue(editorHost.contains("if (editorBackHostCurrent()) editor.confirmBackStop(editorBackPage, it)"))
        val legacy = editorHost.substringAfter("onBack = {").substringBefore("openPreviewMedia =")
        assertTrue(legacy.indexOf("editorBackHostCurrent() && editor.consumeLegacyBack(editorBackPage)") < legacy.indexOf("editor.cancelDraftRestoration()"))
        assertTrue(legacy.contains("editor.cancel(); backTo(EsvDestination.LIBRARY); library?.refresh()"))
        assertTrue(activity.contains("navigationEnabled = editor?.state?.busy != true"))
    }

    @Test fun backgroundAndDeadlineStillCancelAndRevokeDoesNotReplaceTheExistingStop() {
        val foreground = section(runtime, "fun setForeground", "fun changeDraft")
        assertTrue(foreground.contains("if (!value) backGate.revoke()"))
        assertTrue(foreground.contains("if (!value) { admissionGeneration++; cancel() }"))
        assertTrue(runtime.contains("onDeadline = { cancel() }"))
        val legacy = section(runtime, "fun cancel()", "private fun cancelActive")
        assertTrue(legacy.indexOf("retireSubmission()") < legacy.indexOf("val cancellation = activeCancellation"))
        assertTrue(legacy.contains("cancelActive(cancellation)"))
        val cancel = section(runtime, "private fun cancelActive", "private fun featuresAllowed")
        assertTrue(cancel.contains("val result = cancellation.cancel(); deadlines.cancelled(cancellation)"))
        assertTrue(cancel.contains("result == EditCancelResult.TOO_LATE"))
        assertFalse(cancel.contains("publicationAdmitted"))
    }

    @Test fun delayedNoticeUsesMatchingAtomicTokenWithoutChangingTimersOrPublication() {
        val timer = source("editor/EditorDeadlineGuard.kt")
        assertTrue(timer.contains("private val stopNoticeMillis: Long = 10_000"))
        assertTrue(timer.contains("if (active === identity) { onDeadline(); cancelled(identity) }"))
        assertTrue(timer.contains("if (active !== identity || pendingStop != null) return"))
        assertTrue(timer.contains("if (active === identity) onUnconfirmedStop(identity)"))
        assertTrue(timer.contains("deadline?.close(); pendingStop?.close()"))
        val delayed = section(runtime, "onUnconfirmedStop = { identity ->", "private var recovered")
        assertTrue(delayed.contains("editorUnconfirmedStopFeedback(identity, activeCancellation)"))
        assertTrue(delayed.contains("EditorStopFeedback.CHECKING_RESULT) EditorPhase.PUBLISHING else EditorPhase.WAITING_NATIVE_RELEASE"))
        for (forbidden in listOf("cancel()", "deadlines.completed", "releaseGlobal", "activeCancellation =", "retireSubmission")) {
            assertFalse(forbidden, delayed.contains(forbidden))
        }
        val display = gate.substringAfter("internal fun editorUnconfirmedStopFeedback")
        assertTrue(display.contains("activeCancellation !== identity"))
        assertTrue(display.contains("activeCancellation.publicationAdmitted"))
        assertFalse(display.contains("cancel()")); assertFalse(display.contains("committed()"))
        val storage = source("editor/storage/AndroidEditStorage.kt")
        assertTrue(storage.contains("publication.admitPublication(context.deadlineEpochMillis)"))
        val workflow = source("editor/workflow/EditWorkflow.kt")
        assertTrue(workflow.indexOf("stage(EditWorkStage.PUBLISHING)") < workflow.indexOf("port.publish(output, proof, context)"))
    }

    @Test fun capabilityObjectsStayOutOfViewStateCheckpointsAndRuntimeHostReferences() {
        val presentation = source("editor/ui/EditorPresentation.kt")
        val checkpoint = source("editor/ui/EditorDraftCheckpoint.kt")
        for (source in listOf(presentation, checkpoint)) {
            assertFalse(source.contains("EditorBackPage")); assertFalse(source.contains("EditorBackConfirmation"))
            assertFalse(source.contains("EditorBackRequest"))
        }
        assertFalse(gate.contains("() ->"))
        assertFalse(Regex("(?:val|var)\\s+\\w+\\s*:\\s*(?:Activity|NavBackStackEntry|NavController)").containsMatchIn(gate))
        val runtimeApi = section(runtime, "internal fun openBackPage", "fun cancel()")
        assertFalse(runtimeApi.contains("() ->"))
        assertFalse(runtimeApi.contains("Activity")); assertFalse(runtimeApi.contains("NavBackStackEntry"))
        assertFalse(screen.contains("rememberSaveable"))
    }

    @Test fun newPresentationUsesCurrentComposeLocaleThemeAndScrollableAccessibleControls() {
        val dialog = section(screen, "private fun EditorBackDialogContent", "private fun EditorCard")
        assertTrue(dialog.contains("LocalEsvVisuals.current"))
        assertTrue(dialog.contains("stringResource(if (confirming)"))
        assertTrue(dialog.contains("v.colors.elevated, RoundedCornerShape(v.sheetCorner)"))
        assertTrue(dialog.contains("paneTitle = title"))
        assertTrue(dialog.contains("verticalScroll(rememberScrollState())"))
        assertEquals(2, Regex("heightIn\\(min = 48.dp\\)").findAll(dialog).count())
        assertTrue(screen.contains("editorStopFeedbackResource(state.phase, state.stopFeedback)"))
        val resources = source("editor/ui/EditorBackResources.kt")
        assertTrue(resources.contains("phase == EditorPhase.PUBLISHING && feedback == EditorStopFeedback.CHECKING_RESULT"))
        val names = listOf("values", "values-zh-rCN").map { locale ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "src/main/res/$locale/editor_back.xml"))
            val strings = doc.getElementsByTagName("string")
            (0 until strings.length).associate { i ->
                val node = strings.item(i)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }.also { assertEquals(10, it.size); assertTrue(it.values.all(String::isNotBlank)) }
        }
        assertEquals(names[0].keys, names[1].keys)
        assertTrue(names[0].getValue("editor_back_publication_body").contains("actual result"))
        assertTrue(names[1].getValue("editor_back_publication_body").contains("实际结果"))
    }
}
