package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource wiring evidence, not Compose rendering or native WebView acceptance. */
class DiscoveryAddressContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private fun source(name: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$name.kt").readText()
    private fun assertBefore(text: String, first: String, second: String) {
        val start = text.indexOf(first); val end = text.indexOf(second)
        assertTrue("Missing: $first", start >= 0); assertTrue("Missing: $second", end >= 0)
        assertTrue("$first must precede $second", start < end)
    }
    private fun resources(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "src/main/res/$locale/discovery_address.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun addressEditorPrecedesTitleQueryAndBodyInBoundedScrollableHeader() {
        val ui = source("ui/DiscoveryScreen")
        assertBefore(ui, "heightIn(max = paneLayout.headerMaxHeightDp.dp).verticalScroll(headerScroll)", "OutlinedTextField(renderedAddress")
        assertBefore(ui, "OutlinedTextField(renderedAddress", "R.string.flow_discoveryscreen_0")
        assertBefore(ui, "OutlinedTextField(renderedAddress", "OutlinedTextField(draft.query")
        assertTrue(ui.contains("if (addressLayout.stackSearch)"))
        assertTrue(ui.contains("KeyboardActions(onGo = { openAddress() })"))
        assertTrue(ui.contains("onClick = openAddress"))
        assertTrue(ui.contains("R.string.flow_address_description")); assertTrue(ui.contains("R.string.input_clear_address"))
    }
    @Test fun visitTextIsEphemeralWhileEachActionHasObservableRetiredGate() {
        val ui = source("ui/DiscoveryScreen")
        listOf("addressText by remember(addressVisit)", "addressFeedback by remember(addressVisit)",
            "remember(addressAction) { DiscoveryAddressInput() }", "mutableStateOf(addressInput.gate.token)",
            "onDispose { addressInput.gate.retire() }", "tokenChanged = { addressToken = it }", "value.take(8193)").forEach {
            assertTrue(it, ui.contains(it))
        }
        assertFalse(ui.contains("addressText by rememberSaveable"))
        val input = source("ui/DiscoveryAddressInput")
        assertTrue(input.contains("BrowserInputPolicy.address(text, allowBareHost = true)"))
        assertTrue(input.contains("text.none { it.code < 32 || it.code == 127 }"))
        val continuation = input.substringAfter("val draftCurrent =").substringBefore("if (!valid)")
        assertFalse(continuation.contains("actionCurrent"))
        assertFalse(continuation.contains("canEditAddress"))
        assertTrue(continuation.contains("gate.active && gate.token === attempt && interactive()"))
        listOf("rememberSaveable", "offerBrowserQuery", "submitFilterDraft", "recordSeen", "navigate(", "Log.").forEach {
            assertFalse(it, input.contains(it))
        }
    }
    @Test fun availabilityUsesRealPresentationOwnersWithoutProviderStorageQueryOrCaptureReadiness() {
        val main = source("MainActivity")
        val address = main.substringAfter("val addressPresentationCurrent = {").substringBefore("DiscoveryScreen(discovery")
        listOf("chromeActions.isCurrent()", "routeOwnsPresentation()", "navController === controller",
            "addressGeneration == newTabInputGeneration", "isForeground", "hasFocusedWindow", "!isDestroyed", "!isFinishing",
            "!browser.isPrivateMode", "incognito == false", "filePrompt == null", "!browser.pickerInFlight",
            "!browser.hasPendingFileChoice", "browser.jsDialog == null", "browser.fullScreenView == null",
            "!saves.scanning", "saves.selection == null", "!saves.preparingStream", "saves.hlsPreparation == null",
            "saves.destinations?.asking != true", "saves.destinations?.admitting != true", "saves.destinations?.notice == null",
            "saves.segmentRecording?.previewActive != true", "previewReceipt == null", "libraryPreviewReference == null").forEach {
            assertTrue(it, address.contains(it))
        }
        listOf("state.ready", "storageUnavailable", "controlTarget", "filterControlsBusy", "filterSearchPending",
            "provider", "currentRecordingId", "recordingActive", "continuousRecording").forEach { assertFalse(it, address.contains(it)) }
        val ui = source("ui/DiscoveryScreen")
        assertTrue(ui.contains("active && canOpenAddress() && !(options && !addressSplit)"))
        assertTrue(ui.contains("localControlState.prompt == null && safeDisableConfirm == null && rightsDetails == null"))
        assertFalse(ui.contains("rememberUpdatedState(optionsIsModal"))
        assertTrue(ui.contains("val addressActionCurrent = { addressInteractive() && canEditAddress() }"))
        assertTrue(ui.contains("actionCurrent = addressActionCurrent"))
        assertTrue(ui.contains("addressInput.edit(addressActionCurrent())"))
        assertTrue(ui.contains("addressInput.clear(renderedAddressToken, addressActionCurrent(), renderedAddress.isNotEmpty())"))
    }
    @Test fun mainSeparatesVisitAndActionAndRoutesOnlyAcceptedCurrentSource() {
        val main = source("MainActivity")
        assertTrue(main.contains("import com.easysavevideo.app.ui.canClearBrowserDraft"))
        assertTrue(main.contains("remember(chromeActions, routeEntry, addressGeneration, addressTab?.context)"))
        assertTrue(main.contains("remember(addressVisit, addressTab?.epoch, addressRevision)"))
        val dispatch = main.substringAfter("onOpenAddress = { input, draftCurrent ->").substringBefore("settingsEditable =")
        assertTrue(main.contains("canEditAddress = { canClearBrowserDraft(addressTab, browser.state.selectedTab, addressRevision,"))
        assertTrue(dispatch.contains("canClearBrowserDraft(addressTab, browser.state.selectedTab, addressRevision,"))
        assertTrue(dispatch.contains("val sourceCurrent = { addressPresentationCurrent() && draftCurrent() }"))
        assertTrue(dispatch.contains("browser.openOrdinaryAddress(input"))
        assertTrue(dispatch.contains("if (accepted && sourceCurrent()) navigate(EsvDestination.BROWSER)"))
        assertFalse(dispatch.contains("openDiscoveryResult")); assertFalse(dispatch.contains("recordSeen"))
        assertFalse(dispatch.contains("offerBrowserQuery"))
        assertBefore(main, "onOpenAddress =", "RecordingSessionControls(recordingUi)")
    }
    @Test fun hostUsesExactOrdinaryRequestAndPostTouchRevisionWithoutSurfaceGateOrCardAuthority() {
        val host = source("browser/SystemBrowserHost")
        val adapter = host.substringAfter("internal fun openOrdinaryAddress(").substringBefore("/** A history row")
        listOf("if (openingOrdinaryAddress) return false", "finally { openingOrdinaryAddress = false }",
            "!privateMode && controller === ordinaryController", "expectedContext", "expectedEpoch",
            "interactionRevision != expectedInteractionRevision", "val acceptedRevision = interactionRevision",
            "ordinaryController.beginNavigation", "return loadOwned(request, continuation =",
            "interactionRevision == acceptedRevision && ordinaryController.isCurrent(request)",
            "input.any { it.code < 32 || it.code == 127 }").forEach { assertTrue(it, adapter.contains(it)) }
        listOf("browserSurfaceAllowed", "openDiscoveryResult", "discoveryOrigins.open", "enterPrivate", "exitPrivate").forEach {
            assertFalse(it, adapter.contains(it))
        }
        assertTrue(host.contains("override fun load(request: BrowserRequest) { loadOwned(request) }"))
    }
    @Test fun realLoadChecksContinuationAroundNativeStepsAndInvokesTestedExactSettlement() {
        val load = source("browser/SystemBrowserHost").substringAfter("private fun loadOwned(").substringBefore("fun back():")
        assertTrue(load.contains("continuation: (() -> Boolean)? = null"))
        assertTrue(load.contains("continuation?.invoke() != false && canAct() && owner.isCurrent(request)"))
        assertTrue(load.contains("if (continuation != null && !continuing()) return false"))
        assertBefore(load, "recreateEntry(request.context)", "capturedEntry = entry")
        assertBefore(load, "entry.view.stopLoading()", "!bound(entry)")
        assertTrue(load.contains("entry.historyRecorder?.controlledNavigationRequested()\n                if (continuation != null && !bound(entry)) return false\n                entry.view.loadUrl"))
        assertTrue(load.contains("if (continuation != null && !accepted)"))
        assertTrue(load.contains("settleOrdinaryAddressRequest(owner, request)"))
        assertTrue(load.contains("entries[request.context.tabId] === entry"))
        assertTrue(load.contains("entry.request === request && owner.isCurrent(request)"))
        assertTrue(load.contains("setOf(BrowserLoadState.LOADING, BrowserLoadState.READY)"))
        assertFalse(load.contains("address?.value =="))
    }
    @Test fun pairedResourcesGuideKeywordsToSeparateSearchAndRemainRegisteredInputs() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(5, en.size); assertEquals(en.keys, zh.keys)
        assertTrue(en.getValue("discovery_address_invalid").contains("separate video search field"))
        assertTrue(zh.getValue("discovery_address_invalid").contains("单独的视频搜索框"))
        en.values.forEach { assertFalse(it.any { char -> char in '\u4e00'..'\u9fff' }) }
        val build = File(root, "build.gradle.kts").readText()
        assertTrue(build.contains("\"discovery_address.xml\""))
        assertTrue(build.contains("\"src/main/kotlin/com/easysavevideo/app/ui/DiscoveryAddressInput.kt\""))
    }
}
