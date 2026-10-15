package com.easysavevideo.app.ui

import com.easysavevideo.app.browser.BrowserCookieClearResult
import com.easysavevideo.app.browser.BrowserNotice
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts complement production operation tests; no Android/UI acceptance. */
class BrowserCookieClearContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String): String {
        val local = File(root, "kotlin/com/easysavevideo/app/$path")
        if (local.isFile) return local.readText()
        // External draft harness only; the project test uses its complete actual source tree.
        return File(System.getProperty("esv.cookie.clear.baseRoot"),
            "app/src/main/kotlin/com/easysavevideo/app/$path").readText()
    }
    private fun method(text: String, start: String, end: String) = text.substringAfter(start).substringBefore(end)
    private fun assertOrdered(text: String, first: String, second: String, lastSecond: Boolean = false) {
        val start = text.indexOf(first)
        val end = if (lastSecond) text.lastIndexOf(second) else text.indexOf(second)
        assertTrue("Missing first marker: $first", start >= 0)
        assertTrue("Missing second marker: $second", end >= 0)
        assertTrue("Expected $first before $second", start < end)
    }
    private val keys = setOf("rejected", "not_started", "removed", "none_removed", "unconfirmed")
        .map { "browser_cookie_clear_$it" }.toSet()
    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/browser_cookie_clear.xml"))
            .getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent))
        }
        return result
    }
    @Test fun actualHostUsesProductionHelperAndExactlyOneCapturedDefaultManager() {
        val host = source("browser/SystemBrowserHost.kt")
        val body = method(host, "    fun clearCookies(", "    fun clearCache()")
        assertEquals(1, Regex("requestBrowserCookieClear\\(").findAll(body).count())
        assertEquals(1, Regex("CookieManager.getInstance\\(\\)").findAll(body).count())
        assertTrue(body.contains("val manager = CookieManager.getInstance()"))
        assertTrue(body.contains("manager.removeAllCookies { onRemoved(it) }"))
        assertTrue(body.contains("override fun flush() = manager.flush()"))
        assertOrdered(body, "acquire = {", "CookieManager.getInstance()")
        assertTrue(body.contains("prepare = ::cancelFiles"))
    }
    @Test fun privateModeRejectsBeforeDefaultManagerWithoutBlockingRetainedPrivateSession() {
        val body = method(source("browser/SystemBrowserHost.kt"), "    fun clearCookies(", "    fun clearCache()")
        assertTrue(body.contains("if (!canAct() || privateMode)"))
        assertOrdered(body, "onComplete(false); return", "CookieManager.getInstance()")
        assertFalse(body.contains("privateSession != null")); assertFalse(body.contains("privateRuntime."))
        val backend = source("browser/privacy/AndroidPrivateProfileBackend.kt")
        assertTrue(backend.contains("fun cookieManager(session: PrivateBrowsingSession): CookieManager")); assertFalse(backend.contains("CookieManager.getInstance()"))
    }
    @Test fun confirmationUsesOriginalDialogAdmissionAndCapturedRootFeedbackOnly() {
        val screen = source("ui/BrowserScreen.kt")
        val dialog = method(screen, "    if (confirmCookies) {", "    filePrompt?.let")
        assertTrue(dialog.contains("EsvDialog(request, { cookiesRequest.identity === request && modalOwnerCurrent() }"))
        assertTrue(dialog.contains("onDismiss = { confirmCookies = false }"))
        assertTrue(dialog.contains("if (admission()) { confirmCookies = false; browser.clearCookies(feedbackCurrent = ::rootActionAllowed) {} }"))
        assertFalse(dialog.contains("feedbackCurrent = admission"))
        assertEquals(1, Regex("browser.clearCookies\\(").findAll(screen).count())
        for (key in 23..26) assertTrue(dialog.contains("R.string.flow_browserscreen_$key"))
        assertTrue(screen.contains("fun rootActionAllowed(): Boolean = inputAllowed() && modalOwnerCurrent() &&"))
        assertTrue(screen.contains("canSubmitNewTabInput(newTabInputGeneration)"))
    }
    @Test fun currentHostAndCapturedOwnerGateFeedbackWithoutCancellingSubmittedWork() {
        val body = method(source("browser/SystemBrowserHost.kt"), "    fun clearCookies(", "    fun clearCache()")
        for (term in listOf("val interaction = interactionRevision", "val presentation = privatePresentationGeneration",
            "feedbackCurrent() && browserSurfaceAllowed()", "interactionRevision == interaction",
            "privatePresentationGeneration === presentation", "!foreground || !jsDialogUiVisible",
            "cookieClearFeedback.isCurrent(request) && currentPresentation() && cookieClearFeedback.isCurrent(request)",
            "if (cookieClearFeedback.consume(request, ::currentPresentation)) notice = BrowserNotice.CookieClear(result)",
            "onComplete(result.removed)")) assertTrue(term, body.contains(term))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("newTabInputGeneration++"))
        assertTrue(main.contains("override fun onPause()"))
        val operation = source("browser/BrowserCookieClear.kt")
        val callback = operation.substringAfter("port.removeAll { changed ->").substringBefore("} catch (_: Exception) { submissionFailed() }")
        assertFalse(callback.contains("isAllowed()")); assertTrue(callback.contains("port.flush()"))
        assertOrdered(callback, "claimed = true", "port.flush()")
    }
    @Test fun terminalObserverIsClearedBeforeDeliveryAndNotInsidePlatformCatch() {
        val operation = source("browser/BrowserCookieClear.kt")
        val deliver = method(operation, "    fun deliver(", "    fun submissionFailed()")
        assertOrdered(deliver, "observer = null", "completion(result)")
        assertFalse(operation.contains("catch (_: Throwable)"))
        assertFalse(operation.contains("catch (_: Error)"))
        assertFalse(operation.contains("runCatching"))
        assertOrdered(operation, "submitting = false", "deliver(result)", lastSecond = true)
    }
    @Test fun pairedResourcesAreExactBoundedAndDistinct() {
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(keys, en.keys); assertEquals(keys, zh.keys)
        for (key in keys) {
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            assertNotEquals(en.getValue(key), zh.getValue(key))
            assertFalse(en.getValue(key).contains('%')); assertFalse(zh.getValue(key).contains('\uFFFD'))
        }
        assertTrue(en.getValue("browser_cookie_clear_unconfirmed").contains("may already have been removed"))
        assertTrue(zh.getValue("browser_cookie_clear_unconfirmed").contains("可能已被移除"))
        assertTrue(en.getValue("browser_cookie_clear_none_removed").contains("no cookies removed"))
    }
    @Test fun everyTypedOutcomeHasItsOwnDisplayResourceWithoutRawPlatformText() {
        val display = source("ui/BrowserNoticeResources.kt")
        assertTrue(display.contains("is BrowserNotice.CookieClear -> stringResource(browserCookieClearResource(notice.result))"))
        for (result in BrowserCookieClearResult.entries) {
            assertTrue(display.contains("BrowserCookieClearResult.${result.name} -> R.string.browser_cookie_clear_${result.name.lowercase()}"))
            assertEquals("CookieClear(result=${result.name})", BrowserNotice.CookieClear(result).toString())
        }
        assertFalse(display.contains("getString(")); assertFalse(display.contains("remember"))
    }
    @Test fun noExpandedDataPermissionPersistenceOrAutomaticRetryScope() {
        val body = method(source("browser/SystemBrowserHost.kt"), "    fun clearCookies(", "    fun clearCache()")
        val operation = source("browser/BrowserCookieClear.kt")
        for (forbidden in listOf("deleteAllData", "clearCache(", "clearHistory(", "removeSessionCookies", "privateRuntime.",
            "postDelayed", "schedule(", "CookieSyncManager", "getCookie(", "Log.", "println(", "errors.", "requestPermission")) {
            assertFalse(forbidden, body.contains(forbidden)); assertFalse(forbidden, operation.contains(forbidden))
        }
        val settings = source("ui/shell/PrivacySettingsDetail.kt")
        assertFalse(settings.contains("clearCookies("))
    }
    @Test fun directSourceAndResourceReadsAreRegisteredAsGradleTestInputs() {
        val build = File(requireNotNull(requireNotNull(root.absoluteFile.parentFile).parentFile), "build.gradle.kts").readText()
        assertTrue(build.contains("\"browser_cookie_clear.xml\""))
        for (path in listOf("browser/BrowserCookieClear.kt", "browser/BrowserNotice.kt", "browser/SystemBrowserHost.kt",
            "browser/privacy/AndroidPrivateProfileBackend.kt", "ui/BrowserScreen.kt", "ui/BrowserNoticeResources.kt",
            "MainActivity.kt", "ui/shell/PrivacySettingsDetail.kt")) {
            assertTrue(path, build.contains("\"src/main/kotlin/com/easysavevideo/app/$path\""))
        }
        assertTrue(build.contains("inputs.files(contractResourceInputs)"))
        assertTrue(build.contains("inputs.files(contractSourceInputs)"))
    }

}
