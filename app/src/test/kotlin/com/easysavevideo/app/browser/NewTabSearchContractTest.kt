package com.easysavevideo.app.browser

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts only; these do not substitute for Android lifecycle/UI tests. */
class NewTabSearchContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private fun source(name: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$name.kt").readText()
    private fun resources(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "src/main/res/$locale/new_tab_search.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun bothLocalesHaveAllKeysAndDescribeDraftHandoffAccurately() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(8, en.size); assertEquals(en.keys, zh.keys)
        assertEquals("Go to video search", en["new_tab_continue"])
        assertEquals("前往视频搜索", zh["new_tab_continue"])
        assertTrue(en.getValue("new_tab_draft_help").contains("press Search there"))
        assertTrue(zh.getValue("new_tab_draft_help").contains("点击“搜索”才开始"))
        en.values.forEach { assertFalse(it.any { char -> char in '\u4e00'..'\u9fff' }) }
    }
    @Test fun newTabInputIsEphemeralAndHasExplicitCorrectableModes() {
        val ui = source("ui/BrowserScreen")
        assertTrue(ui.contains("videoInput by remember(selected?.context?.tabId)"))
        assertFalse(ui.contains("videoInput by rememberSaveable"))
        assertTrue(ui.contains("NewTabInputMode.entries.filter { !privateMode || it != NewTabInputMode.VIDEO }.forEach"))
        // Ordinary mode retains all choices; private queries cannot enter persistent Discovery.
        assertTrue(ui.contains("if (privateMode && input is NewTabInput.Query) { browser.showNotice(privateQueryNotice); return }"))
        assertTrue(ui.contains("new_tab_video_input"))
        assertTrue(ui.contains("keyboardActions = KeyboardActions(onGo = { submitVideoInput() })"))
    }
    @Test fun submitRejectsRetiredTabAndInactiveOrObscuredUi() {
        val submit = source("ui/BrowserScreen").substringAfter("fun submitVideoInput()").substringBefore("LaunchedEffect")
        listOf("NewTabInputPolicy.canSubmit(selected, browser.state.selectedTab, canSubmitNewTabInput(newTabInputGeneration))", "settingsOpen", "bookmarksOpen", "filePrompt != null").forEach { assertTrue(it, submit.contains(it)) }
        assertTrue(submit.contains("browser.navigate(input.value)"))
        assertTrue(submit.contains("onVideoSearchDraft(input.value)"))
    }
    @Test fun activityChecksForegroundAndDestinationBeforeDraftHandoff() {
        val activity = source("MainActivity")
        val handoff = activity.substringAfter("onVideoSearchDraft = { query ->").substringBefore("onManageProviders")
        assertTrue(handoff.contains("isForeground && activeDestination == EsvDestination.BROWSER"))
        assertTrue(handoff.indexOf("navigate(EsvDestination.DISCOVER)") < handoff.indexOf("offerBrowserQuery(query)"))
        assertTrue(activity.contains("canSubmitNewTabInput = { generation -> generation == newTabInputGeneration && isForeground && activeDestination == EsvDestination.BROWSER }"))
    }
    @Test fun draftHandoffCannotStartSearchOrBypassReadyAndBusyGates() {
        val controller = source("discovery/AndroidDiscoveryController")
        val handoff = controller.substringAfter("fun offerBrowserQuery").substringBefore("var filterSearchPending")
        listOf("closed.get()", "!foreground", "!visible", "!state.ready", "state.storageUnavailable", "filterControlsBusy",
            "pendingBrowserQuery != expected", "updateFilterDraft(current, draft.copy(query = expected))").forEach { assertTrue(it, handoff.contains(it)) }
        listOf("submitFilterDraft", "c.start(", "coordinator.start", "store.").forEach { assertFalse(it, handoff.contains(it)) }
        assertTrue(handoff.contains("pendingBrowserQuery = null"))
    }
    @Test fun pendingDraftIsRetiredOnLeavingBackgroundAndClose() {
        val controller = source("discovery/AndroidDiscoveryController")
        val visibility = controller.substringAfter("fun setVisibility").substringBefore("fun setTaskPriority")
        assertTrue(visibility.contains("if (!isForeground || !discoveryVisible) {"))
        assertTrue(visibility.contains("foreground = isForeground; visible = discoveryVisible"))
        val leaving = visibility.substringAfter("if (!isForeground || !discoveryVisible) {")
            .substringBefore("foreground = isForeground; visible = discoveryVisible")
        assertTrue(leaving.contains("thumbnails.withdraw()"))
        assertTrue(leaving.contains("pendingBrowserQuery = null"))
        assertTrue(leaving.indexOf("thumbnails.withdraw()") < leaving.indexOf("pendingBrowserQuery = null"))
        assertTrue(leaving.contains("pageLifecycle.incrementAndGet()"))
        assertTrue(leaving.indexOf("pendingBrowserQuery = null") < leaving.indexOf("pageLifecycle.incrementAndGet()"))
        val close = controller.substringAfter("override fun close()")
        assertTrue(close.contains("pendingBrowserQuery = null"))
        assertTrue(source("ui/DiscoveryScreen").contains("if (pendingBrowserQuery != null) controller.applyBrowserQuery(pendingBrowserQuery)"))
    }
    @Test fun newerAcceptedEditResetOrSearchRetiresDeferredHandoff() {
        val controller = source("discovery/AndroidDiscoveryController")
        val edit = controller.substringAfter("fun updateFilterDraft").substringBefore("fun editFilterText")
        assertTrue(edit.contains("filterDraft = value"))
        assertTrue(edit.contains("pendingBrowserQuery = null"))
        assertTrue(edit.indexOf("filterDraft = value") < edit.indexOf("pendingBrowserQuery = null"))
        val submit = controller.substringAfter("fun submitFilterDraft").substringBefore("private fun configurationCurrent")
        assertTrue(submit.indexOf("pendingBrowserQuery = null") < submit.indexOf("filterSearchPending = true"))
    }
    @Test fun disposedVisitsAndPreBackgroundCallbacksAreRetired() {
        assertTrue(source("ui/BrowserScreen").contains("onDispose { videoSubmission.retire() }"))
        val pause = source("MainActivity").substringAfter("override fun onPause() {").substringBefore("override fun onDestroy()")
        val retire = pause.indexOf("newTabInputGeneration++")
        assertTrue(retire >= 0)
        assertTrue(pause.indexOf("isForeground = false") > retire)
        assertTrue(pause.indexOf("browser.pause()") > retire)
        assertTrue(source("MainActivity").contains("onLeaving = {\n            newTabInputGeneration++"))
    }
}
