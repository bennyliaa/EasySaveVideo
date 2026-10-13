package com.easysavevideo.app.ui

import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class InputClearWiringTest {
    private fun source(path: String) = String(Files.readAllBytes(Paths.get("src/main", path)), Charsets.UTF_8)
    private val browser get() = source("kotlin/com/easysavevideo/app/ui/BrowserScreen.kt")
    private val discovery get() = source("kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt")
    @Test fun browserClearBodiesCannotSubmitOrNavigateAndReadLatestEnvironment() {
        val source = browser
        assertTrue(source.contains("val currentClearEnvironment by rememberUpdatedState"))
        assertTrue(source.contains("canSubmitNewTabInput(newTabInputGeneration)"))
        assertTrue(source.contains("browser.saveInspectionRevision"))
        assertTrue(source.contains("!saveScanning && saveRuntime?.scanning != true"))
        assertTrue(source.contains("!browser.pickerInFlight && !browser.hasPendingFileChoice"))
        assertTrue(source.contains("browser.jsDialog == null && browser.fullScreenView == null && !browser.pickerInFlight"))
        val bodies = source.substringAfter("fun clearAddressDraft()").substringBefore("DisposableEffect(browser, interactiveJsDialogs)")
        for (forbidden in listOf("navigate(", "submitVideoInput(", "onVideoSearchDraft(", "clearHistory(", "newTab("))
            assertFalse(forbidden, bodies.contains(forbidden))
        assertTrue(bodies.contains("editing = true"));assertTrue(bodies.contains("videoInputError = false"))
        assertTrue(source.contains("addressClearGate.accept(addressClearClaim"))
        assertTrue(source.contains("videoClearGate.accept(videoClearClaim"))
    }
    @Test fun everyEditSourceInvalidatesObservableClaimAndSubmissionRetiresClear() {
        val source = browser
        assertTrue(source.contains("mutableStateOf(addressClearGate.token)"))
        assertTrue(source.contains("mutableStateOf(videoClearGate.token)"))
        assertTrue(source.contains("address = it.take(8192); addressClearToken = addressClearGate.invalidate()"))
        assertTrue(source.contains("videoInput = it.take(8193); videoInputError = false; videoClearToken = videoClearGate.invalidate()"))
        assertTrue(source.contains("videoMode = mode; videoInputError = false; videoClearToken = videoClearGate.invalidate()"))
        val sync = source.substringAfter("LaunchedEffect(selected?.address?.value)").substringBefore("fun go()")
        assertTrue(sync.contains("addressClearToken = addressClearGate.invalidate()"))
        val submit = source.substringAfter("fun submitVideoInput()").substringBefore("LaunchedEffect(selected?.address?.value)")
        assertTrue(submit.indexOf("videoSubmission.claim") < submit.indexOf("videoClearGate.retire()"))
        assertTrue(source.contains("onDispose { addressClearGate.retire(); videoClearGate.retire() }"))
    }
    @Test fun discoveryUsesItsStrictControllerPathAndDoesNotRebaseAnOldClear() {
        assertTrue(discovery.contains("enabled = editable && draft.query.isNotEmpty()"))
        assertTrue(discovery.contains("controller.clearFilterQuery(it, queryClearLifecycle)"))
        assertTrue(discovery.contains("val queryClearLifecycle = controller.clearQueryLifecycle"))
        assertTrue(discovery.contains("val currentQueryClearInteractive by rememberUpdatedState"))
        assertTrue(discovery.contains("onClear = { if (currentQueryClearInteractive())"))
        assertTrue(discovery.contains("active && canClearQuery() && !(options && !paneLayout.split) && localControlConfirm == null"))
        assertTrue(discovery.contains("safeDisableConfirm == null && rightsDetails == null"))
        val controller = source("kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt")
        val body = controller.substringAfter("fun clearFilterQuery(").substringBefore("fun confirmFilterSafety(")
        assertTrue(body.contains("closed.get() || !foreground || !visible"))
        assertTrue(body.contains("DiscoveryFilterPresentationPolicy.clearQuery"))
        assertTrue(body.contains("updateFilterDraft(current, next)"))
        assertTrue(body.contains("expectedLifecycle, pageLifecycle.get(), foreground && visible"))
        assertFalse(body.contains("editFilterText("));assertFalse(body.contains("submitFilterDraft("));assertFalse(body.contains("c.start("))
    }
    @Test fun threeActionsUseVisiblePairedCopyAndMinimumTargets() {
        assertTrue(browser.contains("InputClearButton(R.string.input_clear_address"))
        assertTrue(browser.contains("InputClearButton(R.string.input_clear_new_tab"))
        assertTrue(discovery.contains("InputClearButton(R.string.input_clear_discovery"))
        val button = source("kotlin/com/easysavevideo/app/ui/InputClearButton.kt")
        assertTrue(button.contains("EsvButton(stringResource(R.string.input_clear)"))
        assertTrue(button.contains("widthIn(min = 48.dp).heightIn(min = 48.dp)"))
        assertTrue(button.contains("contentDescription = label"))
        fun entries(folder: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Paths.get("src/main/res", folder, "input_clear.xml").toFile())
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i -> nodes.item(i).let { it.attributes.getNamedItem("name").nodeValue to it.textContent } }
        }
        val en = entries("values");val zh = entries("values-zh-rCN")
        assertEquals(4, en.size);assertEquals(en.keys, zh.keys)
        (en.values + zh.values).forEach { assertTrue(it.isNotBlank()) }
    }
    @Test fun hostInteractionRevisionIsObservableEvenWhenRepeatedInvalidGoChangesNoTab() {
        val host = source("kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt")
        assertTrue(host.contains("private var interactionRevision by mutableLongStateOf(0L)"))
        assertTrue(host.contains("val saveInspectionRevision: Long get() = interactionRevision"))
        assertTrue(host.contains("internal val hasPendingFileChoice: Boolean get() = fileChoice != null"))
    }

    @Test fun actualDiscoveryAndBrowserHostsRejectGlobalSaveModalsAtClick() {
        val main = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        val gate = main.substringAfter("canClearQuery = {").substringBefore("onSettings")
        for (required in listOf("isForeground", "activeDestination == EsvDestination.DISCOVER", "!saves.scanning",
            "saves.selection == null", "!saves.preparingStream", "saves.hlsPreparation == null",
            "saves.destinations?.asking != true", "saves.destinations?.notice == null",
            "saves.segmentRecording?.previewActive != true", "previewReceipt == null", "libraryPreviewReference == null"))
            assertTrue(required, gate.contains(required))
        assertTrue(discovery.contains("canClearQuery: () -> Boolean = { false }"))
        assertTrue(browser.contains("destinations?.asking != true && destinations?.notice == null"))
    }

}
