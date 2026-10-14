package com.easysavevideo.app.ui.shell

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts, not Compose, SQLite, device or public-provider execution. */
class DiscoveryPrivacySettingsContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path.kt").readText()
    private fun strings(folder: String): Map<String,String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$folder/discovery_privacy.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun centralAndOldPanelReuseOneControllerOwnerAndPromptAdapter() {
        val central = source("ui/shell/DiscoveryPrivacySettingsDetail")
        val old = source("ui/DiscoveryScreen")
        for (text in listOf(central, old)) {
            assertTrue(text.contains("DiscoveryPrivacyActions("))
            assertTrue(text.contains("controller::confirmLocalControl"))
            assertTrue(text.contains("controller.localControlState.busy"))
            assertTrue(text.contains("DiscoveryPrivacyOperationStatus(controller"))
            assertTrue(text.contains("onDispose"))
        }
        assertTrue(old.contains("val localControlConfirm = localControlPrompt?.request"))
        assertTrue(old.contains("localControlActions.confirm(prompt)"))
        assertFalse(old.contains("controller.confirmLocalControl(request)"))
    }
    @Test fun settingsUsesExistingPrivacyRouteAndCurrentActivityGuardWithoutStartingDiscovery() {
        val body = source("MainActivity").substringAfter("privacy = {").substringBefore("onSources = ::openProviderManagement")
        for (part in listOf("DiscoveryPrivacySettingsDetail(discovery, generation, canAct = canUsePrivacy)",
            "generation == newTabInputGeneration", "isForeground", "EsvDestination.SETTINGS", "!isDestroyed && !isFinishing",
            "browser.jsDialog == null", "browser.fullScreenView == null", "!browser.pickerInFlight")) assertTrue(part, body.contains(part))
        for (part in listOf("setVisibility(", "initialize(", "search(", "retry(", "confirmLocalControl(", "clearCookies(")) assertFalse(part, body.contains(part))
    }
    @Test fun localCountsAndCommandsCannotBecomeNetworkDemandInTheCentralView() {
        val text = source("ui/shell/DiscoveryPrivacySettingsDetail")
        assertTrue(text.contains("controller::refreshHistoryStatistics"))
        assertTrue(text.contains("state.historyStatistics?.takeIf { it.target == state.controlTarget }"))
        assertTrue(text.contains("provider.hasContinuation"))
        for (part in listOf("state.profile.query", "target.profileId", "canonicalPageUrl", ".cursor", "setVisibility(", "requestMore(", "initialize(", "search(", "retry(", "clearCookies(", "history.confirm(", "clearCache("))
            assertFalse(part, text.contains(part))
    }
    @Test fun independentCompletionLaneSurvivesControllerUiQueueCleanup() {
        val text = source("discovery/AndroidDiscoveryController")
        assertTrue(text.contains("private val localControlMain = Handler(Looper.getMainLooper())"))
        assertTrue(text.contains("localControlMain.post(completion)"))
        val close = text.substringAfter("override fun close()")
        assertTrue(close.contains("localControlOwner.close()"))
        assertTrue(close.contains("main.removeCallbacksAndMessages(null)"))
        assertFalse(close.contains("localControlMain.removeCallbacks"))
        val control = text.substringAfter("fun confirmLocalControl(").substringBefore("private fun currentControlTarget")
        assertTrue(control.contains("osSignals.revalidate()"))
        assertTrue(control.contains("currentControlTarget(c)"))
        assertTrue(control.contains("DiscoveryPresentationPolicy.executeControl(captured"))
        assertTrue(control.contains("afterWork = { result ->"))
        assertTrue(control.contains("refresh(readOnly = true)"))
        assertFalse(control.contains("requestMore("))
    }
    @Test fun resultsUseOperationReceiptsNotNewTargetOrEnqueueAsSuccess() {
        val detail = source("ui/shell/DiscoveryPrivacySettingsDetail")
        assertTrue(detail.contains("controller.localControlState.lastOutcome?.let { receipt ->"))
        assertTrue(detail.contains("when (receipt.outcome)"))
        assertTrue(detail.contains("controller.dismissLocalControlResult(receipt)"))
        assertFalse(detail.contains("lastOutcome?.takeIf"))
        assertTrue(detail.contains("DiscoveryControlOutcome.COMMITTED"))
        val owner = source("discovery/DiscoveryLocalControlOwner")
        assertTrue(owner.contains("snapshot.active !== operation"))
        assertTrue(owner.contains("snapshot.lastOutcome !== expected"))
    }
    @Test fun pairedTranslationsNameActualSeenScopeAndSeparateIrreversibleActions() {
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(27, en.size); assertEquals(en.keys, zh.keys)
        en.keys.forEach { key ->
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            val format = Regex("%[0-9]+\\$[ds]")
            assertEquals(key, format.findAll(en.getValue(key)).map { it.value }.toList(), format.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        assertTrue(en.getValue("discovery_privacy_scope").contains("not a list of typed keywords"))
        assertTrue(zh.getValue("discovery_privacy_scope").contains("不是输入过的关键词列表"))
        for (key in listOf("discovery_privacy_current_confirm", "discovery_privacy_all_confirm", "discovery_privacy_progress_confirm")) {
            assertTrue(en.getValue(key).contains("cannot be undone")); assertTrue(zh.getValue(key).contains("不可撤销"))
            assertTrue(en.getValue(key).contains("preserved"))
        }
        assertTrue(en.getValue("discovery_privacy_progress_confirm").contains("no request starts now"))
    }
    @Test fun ordinaryHistoryAndCacheRemainIndependentAndNoStateIsPersistedByNewUi() {
        val ordinary = source("ui/shell/PrivacySettingsActions")
        assertFalse(ordinary.contains("DiscoveryControlRequest")); assertFalse(ordinary.contains("confirmLocalControl"))
        for (text in listOf(source("ui/shell/DiscoveryPrivacySettingsDetail"), source("ui/shell/DiscoveryPrivacyActions"))) {
            for (part in listOf("rememberSaveable", "SavedStateHandle", "SharedPreferences", "SQLite", "CookieManager", "clearCache", "requestClear")) assertFalse(part, text.contains(part))
        }
    }
    @Test fun buildTracksEveryNewUiResourceAndProductionAdapter() {
        val text = File("build.gradle.kts").readText()
        for (part in listOf("DiscoveryPrivacySettingsDetail.kt", "DiscoveryPrivacyActions.kt", "DiscoveryLocalControlOwner.kt", "discovery_privacy.xml")) assertTrue(part, text.contains(part))
    }
}
