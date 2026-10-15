package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pure ownership and source wiring tests. Not window, IME, compilation or device evidence. */
class PrivateDialogWiringTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, "kotlin/com/easysavevideo/app/$path").readText()

    @Test fun booleanAbaNeverRestoresAnEarlierRequestIdentity() {
        val state = BrowserDialogRequestState()
        var visible by state
        assertFalse(visible)
        visible = true
        val first = state.identity
        assertNotNull(first)
        visible = false
        visible = true
        assertNotSame(first, state.identity)
        val second = state.identity
        visible = true
        assertNotSame(second, state.identity)
        visible = false
        assertNull(state.identity)
    }

    @Test fun requestReplacementAndDisposalRejectQueuedOldActions() {
        val first = Any()
        var current = first
        val old = EsvDialogRequestOwner { current === first }
        assertTrue(old.isCurrent())
        current = Any()
        assertFalse(old.isCurrent())
        current = first
        old.close()
        assertFalse(old.isCurrent())
        old.close()
        assertFalse(old.isCurrent())
    }

    @Test fun independentReplacementOwnerDoesNotReactivateClosedOwner() {
        val old = EsvDialogRequestOwner { true }
        old.close()
        val replacement = EsvDialogRequestOwner { true }
        assertFalse(old.isCurrent())
        assertTrue(replacement.isCurrent())
    }

    @Test fun privateBranchCannotFallBackToRawDialogWithoutEnvironment() {
        val screen = source("ui/BrowserScreen.kt")
        val wrapper = screen.substringAfter("internal fun EsvAppDialog(").substringBefore("@Composable private fun EsvDialog(")
        assertTrue(wrapper.contains("val privateMode = LocalPrivatePresentation.current"))
        assertTrue(wrapper.contains("if (privateMode) {"))
        assertTrue(wrapper.contains("PrivateAppDialog(requestIdentity, dismiss, properties, owner::isCurrent, guardedContent)"))
        assertTrue(wrapper.contains("} else {\n        Dialog(onDismissRequest = dismiss, properties = properties, content = guardedContent)"))
        assertFalse(wrapper.contains("LocalPrivateAppDialogEnvironment.current"))
        assertTrue(wrapper.contains("remember(privateMode, EsvDialogReferenceKey(requestIdentity))"))
        assertTrue(wrapper.contains("onDispose { owner.close() }"))
        assertTrue(wrapper.contains("owner.isCurrent() && inputAdmission()"))
        // Native dismissal is admitted before the protected host revokes its own input token.
        assertTrue(wrapper.contains("val dismiss = { if (owner.isCurrent()) onDismissRequest() }"))
    }

    @Test fun finiteDialogConsumersAllUsePrivateAwareHost() {
        val browser = source("ui/BrowserScreen.kt")
        val js = source("ui/BrowserJsDialog.kt")
        val save = source("save/SaveTaskScreen.kt")
        val destinations = source("library/destination/OrdinaryDestinationUi.kt")
        listOf(js, save, destinations).forEach {
            assertFalse(it.contains("import androidx.compose.ui.window.Dialog\n"))
            assertFalse(Regex("(?<![A-Za-z])Dialog\\(").containsMatchIn(it))
            assertTrue(it.contains("EsvAppDialog("))
            assertTrue(it.contains("LocalPrivateInputAdmission.current"))
        }
        assertEquals(2, Regex("EsvAppDialog\\(").findAll(save).count())
        assertEquals(2, Regex("EsvAppDialog\\(").findAll(destinations).count())
        assertTrue(browser.contains("else runtime.currentSelectionPresentationIdentity?.let { request ->"))
        assertTrue(browser.contains("EsvDialog(choice, { isFilePromptCurrent(choice) && modalOwnerCurrent() }"))
        assertTrue(browser.contains("onFileCancel: (SystemBrowserHost.FileChoice) -> Unit"))
    }

    @Test fun exactInputGenerationIsCapturedBeforeDraftChangesAndGo() {
        val browser = source("ui/BrowserScreen.kt")
        assertTrue(browser.contains("val inputAdmission = LocalPrivateInputAdmission.current"))
        assertTrue(browser.contains("fun clearOwnerCurrent(): Boolean = inputAllowed() &&"))
        assertTrue(browser.contains("onValueChange = { if (clearOwnerCurrent())"))
        assertTrue(browser.contains("if (!inputAllowed() || !canNavigateBrowserAddress"))
        assertTrue(browser.contains("if (!inputAllowed()) return"))
        assertTrue(browser.contains("fun privateModeActionAllowed(): Boolean = inputAllowed() && modalOwnerCurrent()"))
        assertFalse(browser.contains("val currentClearEnvironment by rememberUpdatedState"))
        assertFalse(browser.contains("PrivateBrowserInputBoundary"))
        val js = source("ui/BrowserJsDialog.kt")
        assertTrue(js.contains("if (admission() && BrowserJsDialogPolicy.validInput(it)) input = it"))
        assertTrue(js.contains("if (admission()) finish(request, true,"))
        assertTrue(js.contains("if (admission()) finish(request, false, null, true)"))
        assertTrue(js.contains("onDispose { finish(request, false, null, false) }"))
    }

    @Test fun saveAndDestinationEventsRequireExactRuntimeOwner() {
        val save = source("save/SaveTaskScreen.kt")
        assertTrue(save.contains("runtime.currentSelectionPresentationIdentity === request"))
        assertTrue(save.contains("runtime.selection === selection"))
        assertTrue(save.contains("runtime.hlsPreparation === prepared"))
        assertTrue(save.contains("if (ownsPreparation()) runtime.chooseHlsVariant(variant.identity)"))
        val runtime = source("library/destination/AndroidOrdinaryDestinationRuntime.kt")
        assertTrue(runtime.contains("private set(value) { noticePresentation = value?.let(::NoticePresentation) }"))
        assertTrue(runtime.contains("if (currentSelectionIdentity !== expectedIdentity) return"))
        assertTrue(runtime.contains("if (currentNoticeIdentity !== expectedIdentity) return"))
        val ui = source("library/destination/OrdinaryDestinationUi.kt")
        assertTrue(ui.contains("if (admission()) runtime.clearNotice(request)"))
        assertTrue(ui.contains("if (admission()) runtime.cancelSelection(request)"))
        assertTrue(ui.contains("if (admission()) runtime.choose(request, OrdinaryDestinationChoice.APP_LIBRARY)"))
    }

    @Test fun bookmarkAndDebugControlsCaptureTheOwningAdmission() {
        val panel = source("browser/bookmarks/BookmarkPanel.kt")
        assertTrue(panel.contains("canAct: () -> Boolean = { true }"))
        listOf("if (canAct()) onOpen(item)", "if (canAct()) runtime.remove(item)",
            "if (canAct()) runtime.load()", "if (canAct()) onClose()", "if (canAct()) onDismiss()")
            .forEach { assertTrue(it, panel.contains(it)) }
        assertTrue(panel.contains("BookmarkNoticeRow(notice, runtime::dismissNotice, canAct = canAct)"))
        val browser = source("ui/BrowserScreen.kt")
        assertTrue(browser.contains("canAct = ::rootActionAllowed"))
        assertTrue(browser.contains("canAct = admission"))
        assertTrue(browser.contains("DevelopmentEntitlementPanel(canAct = admission)"))
        fun variant(name: String) = File(root, "../$name/kotlin/com/easysavevideo/app/entitlements/BuildEntitlements.kt").readText()
        val debug = variant("debug")
        val release = variant("release")
        assertTrue(debug.contains("if (canAct()) { BuildEntitlements.source.select(preset); selected = preset }"))
        assertTrue(release.contains("fun DevelopmentEntitlementPanel(canAct: () -> Boolean = { true }) = Unit"))
        assertFalse(release.contains("FakeEntitlementSource"))
    }

    @Test fun staleInlineDestinationNoticeCannotDismissAReplacement() {
        val ui = source("library/destination/OrdinaryDestinationUi.kt")
        val inline = ui.substringAfter("fun OrdinaryDestinationNotice(").substringBefore("fun OrdinaryDestinationDialog(")
        assertTrue(inline.contains("val request = runtime.currentNoticeIdentity"))
        assertTrue(inline.contains("admission() && request != null && runtime.currentNoticeIdentity === request"))
        assertFalse(inline.contains("rememberUpdatedState"))
    }

    @Test fun reparentedPrivateViewsRebindBeforeEitherAndroidViewAttaches() {
        val ui = source("ui/BrowserScreen.kt")
        val factories = ui.split("AndroidView(factory = { context ->").drop(1)
        assertEquals(2, factories.size)
        factories.forEach { tail ->
            val factory = tail.substringBefore("}, modifier = Modifier.fillMaxSize())")
            assertTrue(factory.indexOf("removeView(view)") >= 0)
            assertTrue(factory.indexOf("removeView(view)") < factory.indexOf("browser.beforePrivateViewAttachment(view)"))
            assertTrue(factory.contains("if (!privateMode || browser.beforePrivateViewAttachment(view)) view else View(context)"))
            assertFalse(factory.contains("capturePrivatePresentationClaim"))
            assertFalse(factory.contains("bindPrivateInput"))
        }
    }

    @Test fun sameWebViewSlotsRemainWhileRecordingControlsAreMountedGlobally() {
        val ui = source("ui/BrowserScreen.kt")
        assertEquals(1, Regex("key\\(selected\\?\\.context\\?\\.tabId, revision, view\\)").findAll(ui).count())
        assertEquals(2, Regex("AndroidView\\(factory").findAll(ui).count())
        assertFalse(ui.contains("recordingUi?.handleBack()"))
        assertFalse(ui.contains("currentRecordingPresentation"))
        val controls = source("ui/RecordingSessionControls.kt")
        val main = source("MainActivity.kt")
        assertEquals(1, Regex("RecordingSessionControls\\(recordingUi\\)").findAll(main).count())
        assertTrue(controls.contains("fun RecordingSessionControls("))
        assertTrue(controls.contains("onClick = ui.onContinue"))
        assertTrue(controls.contains("onClick = ui.onStopSave"))
        assertTrue(controls.contains("onClick = ui.onDiscard"))
        assertTrue(controls.contains("if (ui.canDiscard && confirmDiscard)"))
        assertTrue(controls.contains("PrivateRecordingStage.VERIFYING"))
        assertTrue(controls.contains("PrivateRecordingStage.COMMITTING"))
    }
}
