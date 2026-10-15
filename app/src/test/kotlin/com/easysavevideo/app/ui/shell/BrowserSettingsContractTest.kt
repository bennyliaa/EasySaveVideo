package com.easysavevideo.app.ui.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source contracts, not Android WebView or persistence execution tests. */
class BrowserSettingsContractTest {
    private fun source(p:String)=File("src/main/kotlin/com/easysavevideo/app/$p").readText()
    @Test fun bothEntriesUseExactlyOneSharedStatelessDetail() {
        val central=source("ui/shell/EsvSettingsScreen.kt");val browser=source("ui/BrowserScreen.kt")
        assertTrue(central.contains("SettingsCategory.BROWSER -> BrowserSettingsDetail(settings.browser,browserEditable,onChange)"))
        val panel=browser.substringAfter("@Composable private fun SettingsPanel(").substringBefore("@Composable private fun Toggle(")
        assertTrue(panel.contains("val admission = LocalPrivateInputAdmission.current"))
        assertTrue(panel.contains("key(admission) {"))
        assertTrue(panel.contains("BrowserSettingsDetail(settings.browser, editable && admission()) { if (admission()) update(it) }"))
        assertEquals(1,Regex("BrowserSettingsDetail\\(").findAll(browser).count())
        assertFalse(browser.contains("settings.copy(browser"))
        assertTrue(browser.contains("onSettings: (SettingsEdit.BrowserEdit) -> Unit"))
        assertEquals(2,Regex("editable = settingsEditable").findAll(browser).count())
    }
    @Test fun allSharedCallbacksUseDisposableAuthorityAndLatestInputs() {
        val s=source("ui/shell/BrowserSettingsDetail.kt")
        for(x in listOf("rememberUpdatedState(editable)","rememberUpdatedState(onEdit)","BrowserSettingsActions({enabledNow})", "onDispose { actions.close() }")) assertTrue(s.contains(x))
        for(e in listOf("BrowserJavaScript(it)","BrowserThirdPartyCookies(it)","BrowserRestoreTabs(it)","BrowserActiveViews(-1)","BrowserActiveViews(1)")) assertTrue(s.contains("actions.edit(SettingsEdit.$e)"))
        for(x in listOf("rememberSaveable","verticalScroll","getSharedPreferences","applySettings(","clearCookies(","reload(")) assertFalse(s.contains(x))
        assertTrue(s.contains("heightIn(min=48.dp)"));assertTrue(s.contains("role=Role.Switch"));assertTrue(s.contains("FlowRow("))
    }
    @Test fun bothMainOwnersAdmitSuccessfulLoadForegroundAliveAndExpectedRoute() {
        val s=source("MainActivity.kt")
        assertEquals(2,Regex("applyBrowserSettingsEdit\\(settings, edit, settingsReady && persistence.settingsLoaded, isForeground, !isDestroyed && !isFinishing,").findAll(s).count())
        assertTrue(s.contains("activeDestination, EsvDestination.SETTINGS)"));assertTrue(s.contains("activeDestination, EsvDestination.BROWSER)"))
        assertTrue(s.contains("if (edit is SettingsEdit.BrowserEdit) browser.applySettings(value.browser)"))
        assertTrue(s.contains("browser.applySettings(value.browser); persistence.saveSettings(value)"))
        assertFalse(s.contains("settingsWithBrowser("))
    }
    @Test fun loadedBrowserMergesBeforeHostSaveRestoreAndReady() {
        val s=source("MainActivity.kt").substringAfter("persistence.start { loaded, checkpoint, failed ->")
        val merge=s.indexOf("mergeLoadedBrowserSettings(settings, loaded, persistence.settingsLoaded)")
        val host=s.indexOf("browser.applySettings(settings.browser)")
        val save=s.indexOf("persistence.saveSettings(settings)")
        assertTrue(merge>=0 && merge<host && host<save)
        assertTrue(host<s.indexOf("settingsReady = true"));assertTrue(host<s.indexOf("browser.restore(checkpoint)"))
    }
    @Test fun actualHostSemanticsAndStoreKeysRemainInTheExistingOwners() {
        val host=source("browser/SystemBrowserHost.kt")
        assertTrue(host.contains("fun applySettings(value: BrowserSettings)"))
        for(x in listOf("javaScriptEnabled", "setAcceptThirdPartyCookies", "maxActiveWebViews"))assertTrue(host.contains(x))
        assertTrue(source("MainActivity.kt").contains("if (settings.browser.restoreTabs) browser.restore(checkpoint)"))
        assertTrue(source("storage/BrowserPersistence.kt").contains("restoreTabs"))
        val store=source("storage/AndroidStores.kt")
        for(k in listOf("javascript","third_party_cookies","restore_tabs","active_views"))assertTrue(store.contains("\"$k\""))
        val ui=source("ui/shell/BrowserSettingsDetail.kt")
        for(n in listOf(34,35,36,40,43))assertTrue(ui.contains("R.string.flow_browserscreen_$n"))
    }
    @Test fun existingLocalizedImpactWarningsAreNotWeakened() {
        fun strings(locale:String)=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$locale/flow_strings.xml"))
            .getElementsByTagName("string").let { n -> (0 until n.length).associate { val x=n.item(it);x.attributes.getNamedItem("name").nodeValue to x.textContent } }
        val en=strings("values");val zh=strings("values-zh-rCN")
        assertTrue(en.getValue("flow_browserscreen_36").contains("removes saved tab addresses, without clearing cookies"))
        assertTrue(en.getValue("flow_browserscreen_35").contains("does not promise incognito or per-tab isolation"))
        assertTrue(en.getValue("flow_browserscreen_43").contains("explicitly denied"))
        assertTrue(en.getValue("flow_browserscreen_40").contains("Up to 12 tabs"))
        for(n in listOf(34,35,36,40,43))assertTrue(zh.getValue("flow_browserscreen_$n").isNotBlank())
    }
    @Test fun newResourcesArePairedAndNoPermissionsOrUserAgentAreAdded() {
        fun names(locale:String)=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$locale/settings_browser.xml"))
            .getElementsByTagName("string").let { n -> (0 until n.length).map { n.item(it).attributes.getNamedItem("name").nodeValue }.toSet() }
        assertEquals(3,names("values").size);assertEquals(names("values"),names("values-zh-rCN"))
        val ui=source("ui/shell/BrowserSettingsDetail.kt")
        for(x in listOf("requestPermissions", "userAgentString", "grant(", "setGeolocationEnabled", "setAcceptCookie("))assertFalse(ui.contains(x))
    }
}
