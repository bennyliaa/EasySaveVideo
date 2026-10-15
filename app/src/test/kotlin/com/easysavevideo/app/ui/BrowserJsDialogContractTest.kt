package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource wiring checks, not claims of on-device WebView or Compose rendering coverage. */
class BrowserJsDialogContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, "kotlin/com/easysavevideo/app/$path").readText()
    @Test fun bothLocalesMatchKeysAndFormatArguments() {
        fun strings(locale: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/browser_js_dialog.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(10, en.size); assertEquals(en.keys, zh.keys)
        val format = Regex("%[0-9]+\\\$[sd]")
        en.keys.forEach { assertEquals(format.findAll(en.getValue(it)).map { m -> m.value }.toList(), format.findAll(zh.getValue(it)).map { m -> m.value }.toList()) }
    }
    @Test fun consecutiveDialogsRestartAtSourceAndMessage() {
        val ui = source("ui/BrowserJsDialog.kt")
        assertTrue(ui.contains("key(request)"))
        assertTrue(ui.contains("EsvAppDialog(request"))
        assertTrue(ui.contains("rememberScrollState()"))
        assertTrue(ui.indexOf("key(request)") < ui.indexOf("EsvAppDialog(request"))
        assertTrue(ui.indexOf("key(request)") < ui.indexOf("rememberScrollState()"))
    }
    @Test fun backgroundNavigationOnlyCancelsItsOwnView() {
        val host = source("browser/SystemBrowserHost.kt")
        val load = host.substringAfter("override fun load(request: BrowserRequest)").substringBefore("fun back()")
        assertFalse(load.contains("jsDialogs.cancel()"))
        assertTrue(load.contains("ownerOf(request.context) ?: return"))
        assertTrue(load.contains("if (displayed && !dismissTransientUi() || !owner.isCurrent(request)) return"))
        assertTrue(load.contains("recreateEntry(request.context)"))
        val recreate = host.substringAfter("private fun recreateEntry(").substringBefore("private fun ensureEntry(")
        assertTrue(recreate.contains("authenticationEntry(ref)"))
        assertTrue(recreate.contains("releaseView(old.view)"))
        val release = host.substringAfter("private fun releaseView(").substringBefore("private fun current(")
        assertTrue(release.contains("jsDialogs.cancel(view)"))
        assertFalse(release.contains("jsDialogs.cancel()"))
    }
    @Test fun promptInputIsEphemeralMaskedAndSecure() {
        val ui = source("ui/BrowserJsDialog.kt")
        assertTrue(ui.contains("remember(request)")); assertFalse(ui.contains("rememberSaveable"))
        assertTrue(ui.contains("SecureFlagPolicy.SecureOn")); assertTrue(ui.contains("KeyboardType.Password"))
        assertTrue(ui.contains("PasswordVisualTransformation()")); assertTrue(ui.contains("autoCorrectEnabled = false"))
        assertFalse(ui.contains("Log.")); assertFalse(ui.contains("println("))
    }
    @Test fun boundedDialogHasOneScrollOwnerWithoutWeightedStarvation() {
        val ui = source("ui/BrowserJsDialog.kt")
        assertEquals(1, Regex("\\.verticalScroll\\(").findAll(ui).count())
        assertTrue(ui.contains("heightIn(max = maxHeight.coerceAtMost(600.dp))")); assertFalse(ui.contains(".weight("))
    }
    @Test fun previewIsNotAnInteractiveDialogSurface() {
        assertTrue(source("MainActivity.kt").contains("interactiveJsDialogs = false"))
        val screen = source("ui/BrowserScreen.kt")
        assertTrue(screen.contains("onDispose { browser.setJsDialogUiVisible(false) }"))
        assertTrue(screen.contains("setJsDialogUiBlocked(settingsOpen || bookmarksOpen || historyOpen || confirmCookies"))
        assertTrue(screen.contains("BrowserJsDialog(request, browser::finishJsDialog, isRequestCurrent"))
    }
    @Test fun hostCompletesThreeKindsAndRefusesBeforeUnload() {
        val host = source("browser/SystemBrowserHost.kt")
        listOf("ALERT", "CONFIRM", "PROMPT").forEach { assertTrue(host.contains("BrowserJsDialogKind.$it")) }
        assertTrue(host.contains("override fun onJsBeforeUnload"))
        val pause = host.substringAfter("fun pause()").substringBefore("fun hideFullscreen")
        assertTrue(pause.contains("foreground = false")); assertTrue(pause.contains("jsDialogs.cancel()"))
        val close = host.substringAfter("override fun close()")
        assertTrue(close.contains("closing = true")); assertTrue(close.contains("completeClose()"))
        assertTrue(close.contains("closed = true")); assertTrue(close.contains("jsDialogs.cancel()"))
        val beforeUnload = host.substringAfter("override fun onJsBeforeUnload").substringBefore("override fun close()")
        assertTrue(beforeUnload.contains("result.cancel(); return true"))
        assertTrue(host.contains("jsDialogs.cancel(view)"))
        assertTrue(host.contains("owner = view"))
        assertTrue(host.contains("entry.jsDialogCount < BrowserJsDialogPolicy.MAX_PER_DOCUMENT"))
    }
}
