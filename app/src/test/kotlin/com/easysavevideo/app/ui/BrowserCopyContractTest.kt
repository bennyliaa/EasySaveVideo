package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.core.model.ErrorCode
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** JVM resource/source contracts, not device layout or WebView acceptance. */
class BrowserCopyContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val expectedKeys = setOf("origin", "third_party_cookies", "restore_tabs", "active_limit",
        "toggle", "on", "off", "error_tls", "error_renderer", "error_timeout", "error_network",
        "error_http", "error_unavailable").map { "browser_copy_$it" }.toSet()
    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/browser_copy.xml")).getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent))
        }
        return result
    }
    @Test fun exactOwnedKeysAndPositionalPlaceholderContractsMatch() {
        val contracts = mapOf("browser_copy_origin" to listOf("%1\$s"),
            "browser_copy_active_limit" to listOf("%1\$d"), "browser_copy_toggle" to listOf("%1\$s", "%2\$s"))
        for (locale in listOf("values", "values-zh-rCN")) {
            val strings = strings(locale)
            assertEquals(expectedKeys, strings.keys)
            strings.forEach { (key, value) ->
                assertTrue(key, value.isNotBlank()); assertFalse(value.contains('\uFFFD'))
                assertEquals(key, contracts[key].orEmpty(), Regex("%[0-9]+\\$[ds]").findAll(value).map { it.value }.toList())
            }
        }
    }
    @Test fun rawOriginAndSingularPluralLimitsRemainUninterpreted() {
        val origin = "https://example.invalid/%s?q=原文&n=1"
        for (locale in listOf("values", "values-zh-rCN")) {
            val strings = strings(locale)
            assertTrue(String.format(Locale.US, strings.getValue("browser_copy_origin"), origin).endsWith(origin))
            for (limit in 1..4) assertTrue(String.format(Locale.US,
                strings.getValue("browser_copy_active_limit"), limit).endsWith(limit.toString()))
            for (state in listOf("on", "off")) {
                val rendered = String.format(Locale.US, strings.getValue("browser_copy_toggle"),
                    "JavaScript", strings.getValue("browser_copy_$state"))
                assertTrue(rendered.startsWith("JavaScript"))
                assertTrue(rendered.endsWith(strings.getValue("browser_copy_$state")))
            }
            assertNotEquals(strings.getValue("browser_copy_on"), strings.getValue("browser_copy_off"))
        }
    }
    @Test fun finiteErrorMappingPreservesSpecificFailuresAndFallback() {
        val mapping = mapOf(ErrorCode.TLS_REJECTED to R.string.browser_copy_error_tls,
            ErrorCode.RENDERER_GONE to R.string.browser_copy_error_renderer,
            ErrorCode.DEADLINE_EXCEEDED to R.string.browser_copy_error_timeout,
            ErrorCode.NETWORK_TIMEOUT to R.string.browser_copy_error_timeout,
            ErrorCode.NETWORK_UNAVAILABLE to R.string.browser_copy_error_network,
            ErrorCode.HTTP_FAILURE to R.string.browser_copy_error_http)
        ErrorCode.entries.forEach { assertEquals(mapping[it] ?: R.string.browser_copy_error_unavailable, browserErrorResource(it)) }
        assertEquals(6, ErrorCode.entries.map(::browserErrorResource).distinct().size)
    }
    @Test fun screenConsumesResourcesWithoutChangingRawWebContentOrSettingsBounds() {
        val screen = File(root, "kotlin/com/easysavevideo/app/ui/BrowserScreen.kt").readText() +
            File(root, "kotlin/com/easysavevideo/app/ui/shell/BrowserSettingsDetail.kt").readText()
        listOf("stringResource(browserErrorResource(selected.error!!.code))",
            "stringResource(R.string.browser_copy_origin, choice.origin)",
            "BrowserPreferenceSwitch(stringResource(R.string.browser_copy_third_party_cookies),settings.acceptThirdPartyCookies,editable)",
            "BrowserPreferenceSwitch(stringResource(R.string.browser_copy_restore_tabs),settings.restoreTabs,editable)",
            "stringResource(R.string.browser_copy_active_limit,settings.maxActiveWebViews)",
            "stringResource(R.string.browser_copy_toggle, label,",
            "stringResource(if (enabled) R.string.browser_copy_on else R.string.browser_copy_off)",
            "onChange(!enabled)", "maxActiveWebViews>1", "maxActiveWebViews<4",
            "tab.title?.take(28) ?: tab.address?.value?.take(28)",
            "AndroidView(factory =", "verticalScroll(rememberScrollState())",
            "if (settingsOpen && wide)", "if (settingsOpen && !wide)",
            "filePrompt?.let { choice -> EsvDialog(onDismiss = onFileCancel)",
            "onFileConfirm(choice)").forEach { assertTrue(it, screen.contains(it)) }
        expectedKeys.forEach { assertTrue(it, screen.contains("R.string.$it")) }
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(screen))
    }
    @Test fun securityFailureAndStoppedRendererKeepTheirMeaningInBothLanguages() {
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertTrue(en.getValue("browser_copy_error_tls").contains("Connection refused"))
        assertTrue(zh.getValue("browser_copy_error_tls").contains("已拒绝连接"))
        assertTrue(en.getValue("browser_copy_error_renderer").contains("has stopped"))
        assertTrue(zh.getValue("browser_copy_error_renderer").contains("已停止"))
    }
}
