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
        assertTrue(ui.contains("Dialog(onDismissRequest"))
        assertTrue(ui.contains("rememberScrollState()"))
        assertTrue(ui.indexOf("key(request)") < ui.indexOf("Dialog(onDismissRequest"))
        assertTrue(ui.indexOf("key(request)") < ui.indexOf("rememberScrollState()"))
    }
    @Test fun backgroundNavigationOnlyCancelsItsOwnView() {
        val host = source("browser/SystemBrowserHost.kt")
        val load = host.substringAfter("override fun load(request: BrowserRequest)").substringBefore("fun back()")
        assertFalse(load.contains("jsDialogs.cancel()"))
        assertTrue(load.contains("it.context == request.context"))
        assertTrue(load.contains("jsDialogs.cancel(it.view)"))
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
        assertTrue(screen.contains("setJsDialogUiBlocked(settingsOpen || bookmarksOpen || confirmCookies"))
        assertTrue(screen.contains("BrowserJsDialog(request, browser::finishJsDialog)"))
    }
    @Test fun hostCompletesThreeKindsAndRefusesBeforeUnload() {
        val host = source("browser/SystemBrowserHost.kt")
        listOf("ALERT", "CONFIRM", "PROMPT").forEach { assertTrue(host.contains("BrowserJsDialogKind.$it")) }
        assertTrue(host.contains("override fun onJsBeforeUnload"))
        assertTrue(host.contains("foreground = false; jsDialogs.cancel()"))
        assertTrue(host.contains("closed = true; jsDialogs.cancel()"))
        assertTrue(host.contains("jsDialogs.cancel(view)"))
        assertTrue(host.contains("owner = view"))
        assertTrue(host.contains("entry.jsDialogCount < BrowserJsDialogPolicy.MAX_PER_DOCUMENT"))
    }
}
