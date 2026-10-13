package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts only; these are not on-device rendering or WebView tests. */
class BrowserVideoPresenceContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, "kotlin/com/easysavevideo/app/$path").readText()
    @Test fun pairedLocalesSayEvidenceNotSaveCapability() {
        fun strings(locale: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/video_presence.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(3, en.size); assertEquals(en.keys, zh.keys)
        assertTrue(en.getValue("video_presence_found").contains("element"))
        assertTrue(zh.getValue("video_presence_found").contains("元素"))
        assertTrue(en.getValue("video_presence_manual").contains("manually"))
    }
    @Test fun nativeExecutionAndCallbackBothRevalidateCurrentTarget() {
        val host = source("browser/SystemBrowserHost.kt")
        val evaluate = host.substringAfter("evaluate = { target, reply ->").substringBefore("changed =")
        assertEquals(2, Regex("syncVideoPresence\\(\\)").findAll(evaluate).count())
        assertEquals(2, Regex("target == videoPresenceTarget\\(\\)").findAll(evaluate).count())
        assertTrue(evaluate.contains("evaluateJavascript(BrowserVideoPresenceScript.SCRIPT)"))
        val gate = host.substringAfter("private fun videoPresenceTarget()").substringBefore("private fun syncVideoPresence")
        listOf("closed", "!foreground", "!videoPresenceUiVisible", "!browserSurfaceAllowed()", "!settings.javaScriptEnabled", "BrowserLoadState.READY",
            "entry.suppressed", "current(entry) == null", "entry.bookmarkAddress != entry.view.url", "tab.epoch",
            "interactionRevision", "entry.presenceNavigationRevision").forEach { assertTrue(it, gate.contains(it)) }
    }
    @Test fun appNavigationAndScreenDisposalRetireAutomaticWork() {
        val main = source("MainActivity.kt")
        assertEquals(2, Regex("setVideoPresenceUiVisible\\(false\\)").findAll(main).count())
        assertTrue(main.contains("interactiveJsDialogs = false"))
        assertTrue(main.contains("browserSurfaceAllowed = { activeDestination == EsvDestination.BROWSER && saves.segmentRecording?.previewActive != true }"))
        val screen = source("ui/BrowserScreen.kt")
        assertTrue(screen.contains("onDispose { browser.setVideoPresenceUiVisible(false) }"))
        assertTrue(screen.contains("setVideoPresenceUiVisible(interactiveJsDialogs && !settingsOpen && !bookmarksOpen"))
        assertTrue(screen.contains("filePrompt == null && !saveScanning && saveRuntime?.selection == null"))
    }
    @Test fun pauseCloseDocumentHistoryAndSettingsAreWired() {
        val host = source("browser/SystemBrowserHost.kt")
        assertTrue(host.contains("foreground = false; jsDialogs.cancel(); videoPresenceController.update(null)"))
        assertTrue(host.contains("closed = true; jsDialogs.cancel(); videoPresenceController.close()"))
        assertTrue(host.contains("entry.jsDocument++; entry.presenceBudget = BrowserVideoPresenceBudget()"))
        assertTrue(host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError").contains("syncVideoPresence()"))
        assertTrue(host.substringAfter("fun applySettings").substringBefore("fun clearCookies").contains("syncVideoPresence()"))
        assertTrue(host.substringAfter("private fun publish").substringBefore("private fun requireMain").contains("syncVideoPresence()"))
    }
    @Test fun manualFallbackNeverDependsOnPresenceAndRecordingOnlyNavigates() {
        val screen = source("ui/BrowserScreen.kt")
        val manual = screen.substringAfter("if (moreControls) Row").substringBefore("bookmarks?.let")
        assertTrue(manual.contains("R.string.video_presence_manual")); assertTrue(manual.contains("onClick = onSave"))
        assertFalse(manual.contains("BrowserVideoPresence.PRESENT"))
        assertTrue(screen.contains("browser.videoPresence == BrowserVideoPresence.PRESENT && onRecording != null"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("onRecording = { navigate(EsvDestination.RECORD) }"))
        assertTrue(main.contains("onSave = { saves.inspectFromUserClick(browser) }"))
    }
    @Test fun backgroundNavigationCannotRetireForegroundEvidence() {
        val host = source("browser/SystemBrowserHost.kt")
        val gate = host.substringAfter("private fun videoPresenceTarget()").substringBefore("private fun syncVideoPresence")
        assertTrue(gate.contains("entry.presenceNavigationRevision")); assertFalse(gate.contains("bookmarkNavigationRevision"))
        assertTrue(host.substringAfter("override fun onPageFinished").substringBefore("override fun doUpdateVisitedHistory").contains("entry.presenceNavigationRevision++"))
        assertTrue(host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError").contains("entry.presenceNavigationRevision++"))
    }
    @Test fun lightweightScriptDoesNotReuseFullDetectorOrAccessMediaProperties() {
        val script = source("browser/BrowserVideoPresenceScript.kt").substringAfter("val SCRIPT =")
        listOf("PageSaveDetector", "currentSrc", ".src", "cookie", "location", "contentDocument", "shadowRoot", "fetch(", "setTimeout(", "setInterval(", "MutationObserver", "addJavascriptInterface").forEach {
            assertFalse(it, script.contains(it))
        }
        assertTrue(script.contains("i < 512")); assertTrue(script.contains("0xFFFFFFFF"))
        val host = source("browser/SystemBrowserHost.kt")
        assertFalse(host.contains("inspectFromUserClick("))
    }
}
