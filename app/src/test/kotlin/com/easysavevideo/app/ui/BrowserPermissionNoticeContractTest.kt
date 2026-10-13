package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.app.browser.BrowserInputPolicy
import com.easysavevideo.app.browser.BrowserNotice
import com.easysavevideo.app.browser.BrowserPermissionKind
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** JVM value/source/resource contracts, not on-device WebView callback or Compose acceptance. */
class BrowserPermissionNoticeContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, "kotlin/com/easysavevideo/app/$path").readText()
    private val keys = setOf("browser_permission_camera_microphone_denied",
        "browser_permission_location_denied", "browser_permission_unknown_origin")
    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(
            File(root, "res/$locale/browser_permission_notice.xml")).getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent))
        }
        return result
    }
    private fun key(kind: BrowserPermissionKind): String = when (browserPermissionDeniedResource(kind)) {
        R.string.browser_permission_camera_microphone_denied -> "browser_permission_camera_microphone_denied"
        R.string.browser_permission_location_denied -> "browser_permission_location_denied"
        else -> error("Unexpected permission notice resource")
    }
    @Test fun exactlyThreeOwnedResourcesHaveMatchingPositionalContracts() {
        for (locale in listOf("values", "values-zh-rCN")) {
            val values = strings(locale)
            assertEquals(keys, values.keys)
            values.forEach { (key, value) ->
                assertTrue(key, value.isNotBlank())
                assertFalse(value.contains('\uFFFD'))
                assertEquals(key, if (key.endsWith("unknown_origin")) emptyList<String>() else listOf("%1\$s"),
                    Regex("%[0-9]+\\$[ds]").findAll(value).map { it.value }.toList())
            }
        }
    }
    @Test fun twoDeniedKindsHaveDistinctResources() {
        assertEquals(2, BrowserPermissionKind.entries.size)
        assertEquals(R.string.browser_permission_camera_microphone_denied,
            browserPermissionDeniedResource(BrowserPermissionKind.CAMERA_OR_MICROPHONE))
        assertEquals(R.string.browser_permission_location_denied,
            browserPermissionDeniedResource(BrowserPermissionKind.LOCATION))
    }
    @Test fun legacyTextIsAnExactValueWithoutParsingOrFormatting() {
        val input = listOf("", "Already reported", "原文 %s %1\$s\nnext", "https://example.invalid title.mp4",
            "未知来源 请求位置；此版本未启用，已拒绝。")
        input.forEach {
            val notice = BrowserNotice.Text(it)
            assertSame(it, notice.value)
            assertEquals(notice, BrowserNotice.Text(it))
        }
        val display = source("ui/BrowserNoticeResources.kt")
        assertTrue(display.contains("is BrowserNotice.Text -> notice.value"))
        assertFalse(display.contains("substring"))
        assertFalse(display.contains("Regex"))
    }
    @Test fun samePermissionTokenCanResolveBothLanguagesWithoutMutation() {
        val en = strings("values"); val zh = strings("values-zh-rCN")
        for (kind in BrowserPermissionKind.entries) {
            for (origin in listOf("https://example.invalid:8443", null)) {
                val notice = BrowserNotice.PermissionDenied(kind, origin)
                fun render(values: Map<String, String>): String = String.format(Locale.ROOT,
                    values.getValue(key(notice.kind)), notice.origin ?: values.getValue("browser_permission_unknown_origin"))
                val english = render(en); val chinese = render(zh)
                assertNotEquals(english, chinese)
                assertTrue(english.startsWith(origin ?: "Unknown source"))
                assertTrue(chinese.startsWith(origin ?: "未知来源"))
                assertTrue(english.endsWith("the request was denied."))
                assertTrue(chinese.endsWith("此版本未启用，已拒绝。"))
                assertEquals(english, render(en))
                assertEquals(BrowserNotice.PermissionDenied(kind, origin), notice)
            }
        }
    }
    @Test fun diagnosticRepresentationsDoNotIncludeRawTextOrOrigins() {
        val raw = "Private webpage title and filename.mp4"
        val origin = "https://private.example.invalid"
        val text = BrowserNotice.Text(raw)
        val permission = BrowserNotice.PermissionDenied(BrowserPermissionKind.LOCATION, origin)
        assertEquals("BrowserNotice.Text", text.toString())
        assertEquals("BrowserNotice.PermissionDenied(LOCATION)", permission.toString())
        assertSame(raw, text.value)
        assertSame(origin, permission.origin)
    }
    @Test fun originProjectionStillDropsPathQueryFragmentAndUntrustedSchemes() {
        assertEquals("https://example.invalid", BrowserInputPolicy.origin(
            "https://EXAMPLE.invalid:443/private/file.mp4?token=secret#fragment"))
        for (input in listOf(null, "", "file:///private/file.mp4", "data:text/plain,private",
            "https://user:password@example.invalid/private", "javascript:alert(1)")) {
            assertNull(BrowserInputPolicy.origin(input))
        }
    }
    @Test fun mediaCallbackStillDeniesExactlyOnceBeforePublishingDisplayData() {
        val callback = source("browser/SystemBrowserHost.kt").substringAfter(
            "override fun onPermissionRequest(request: PermissionRequest) {").substringBefore("\n        }").trimIndent()
        assertEquals("""
            request.deny()
            notice = BrowserNotice.PermissionDenied(BrowserPermissionKind.CAMERA_OR_MICROPHONE,
                BrowserInputPolicy.origin(request.origin.toString()))
        """.trimIndent(), callback)
    }
    @Test fun locationCallbackStillDeniesExactlyOnceWithoutRememberingTheDecision() {
        val callback = source("browser/SystemBrowserHost.kt").substringAfter(
            "override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {")
            .substringBefore("\n        }").trimIndent()
        assertEquals("""
            callback.invoke(origin, false, false)
            notice = BrowserNotice.PermissionDenied(BrowserPermissionKind.LOCATION, BrowserInputPolicy.origin(origin))
        """.trimIndent(), callback)
    }
    @Test fun oneNoticeStateAndExistingDismissalRemainSharedByAllKinds() {
        val host = source("browser/SystemBrowserHost.kt")
        assertTrue(host.contains("internal var notice by mutableStateOf<BrowserNotice?>(null); private set"))
        assertTrue(host.contains("fun dismissNotice() { notice = null }"))
        assertTrue(host.contains("fun showNotice(text: String) { notice = BrowserNotice.Text(text) }"))
        assertEquals(2, Regex("notice = BrowserNotice\\.PermissionDenied\\(").findAll(host).count())
        assertFalse(host.contains("browser_permission_"))
    }
    @Test fun onlyDisplayBoundaryResolvesCurrentComposeLocaleWithoutCachingTheMessage() {
        val screen = source("ui/BrowserScreen.kt")
        assertEquals(1, Regex("browserNoticeText\\(message\\)").findAll(screen).count())
        assertTrue(screen.contains("browser.notice?.let { message ->"))
        assertTrue(screen.contains("onClick = browser::dismissNotice"))
        val display = source("ui/BrowserNoticeResources.kt")
        assertTrue(display.contains("@Composable"))
        assertTrue(display.contains("stringResource(browserPermissionDeniedResource(notice.kind),"))
        assertTrue(display.contains("notice.origin ?: stringResource(R.string.browser_permission_unknown_origin)"))
        for (forbidden in listOf("remember", "getString(", "Locale.getDefault", "request.", "callback.", "Log.", "println(")) {
            assertFalse(forbidden, display.contains(forbidden))
        }
        val locale = source("ui/shell/EsvLocaleHost.kt")
        assertTrue(locale.contains("Build.VERSION.SDK_INT >= 33 || language == AppLanguage.SYSTEM"))
        assertTrue(locale.contains("LocalContext provides localized"))
        assertTrue(locale.contains("LocalConfiguration provides localized.resources.configuration"))
    }
}
