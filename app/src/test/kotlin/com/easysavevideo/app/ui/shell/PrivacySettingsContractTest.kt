package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.browser.BrowserCacheClearResult
import com.easysavevideo.app.browser.requestBrowserCacheClear
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Resource/source contracts complement actual controller-port tests, not mounted Android UI. */
class PrivacySettingsContractTest {
    private fun path(relative: String): Path = Path.of(relative).takeIf(Files::exists) ?: Path.of("app",relative)
    private fun read(relative: String) = String(Files.readAllBytes(path(relative)),Charsets.UTF_8)
    @Test fun cacheNativeAdapterCallsOnceAndReturnsOnlyRequested() {
        var calls=0
        assertEquals(BrowserCacheClearResult.REQUESTED,requestBrowserCacheClear{calls++});assertEquals(1,calls)
    }
    @Test fun cacheNativeRuntimeAndLinkageFailuresAreBoundedAndDoNotLeakText() {
        assertEquals(BrowserCacheClearResult.FAILED,requestBrowserCacheClear{throw IllegalStateException("fixture detail")})
        assertEquals(BrowserCacheClearResult.FAILED,requestBrowserCacheClear{throw UnsatisfiedLinkError("fixture detail")})
    }
    @Test fun privacyCategoryRetainsStableKeyAndExistingBackSemantics() {
        for(split in listOf(false,true)) {
            val p=settingsPresentation("privacy",split)
            assertEquals(SettingsCategory.PRIVACY,p.active);assertTrue(p.detail)
            assertEquals(if(split)SettingsBackAction.EXIT else SettingsBackAction.CATEGORIES,settingsBackAction(false,p))
        }
    }
    @Test fun labelsArePairedAndFormatArgumentsMatch() {
        fun parse(locale:String):Map<String,String> {
            val d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path("src/main/res/$locale/settings_privacy.xml").toFile())
            val n=d.getElementsByTagName("string")
            return (0 until n.length).associate { val x=n.item(it);x.attributes.getNamedItem("name").nodeValue to x.textContent }
        }
        val en=parse("values");val zh=parse("values-zh-rCN")
        assertEquals(12,en.size);assertEquals(en.keys,zh.keys)
        for(key in en.keys) {
            assertTrue(en.getValue(key).isNotBlank());assertTrue(zh.getValue(key).isNotBlank())
            val format=Regex("%[0-9]+\\$[ds]")
            assertEquals(format.findAll(en.getValue(key)).map{it.value}.toList(),format.findAll(zh.getValue(key)).map{it.value}.toList())
        }
        assertTrue(en.getValue("settings_privacy_cache_requested").contains("does not report completion"))
        assertTrue(en.getValue("settings_privacy_history_scope").contains("cannot be undone"))
    }
    @Test fun nativeCacheEntryReusesExactOperationWithoutClearingOtherData() {
        val s=read("src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt")
        val cache=s.substringAfter("internal fun clearCacheWithResult()").substringBefore("fun persistCurrentSession")
        assertTrue(cache.contains("requireMain()"));assertTrue(cache.contains("requestBrowserCacheClear {"))
        assertTrue(cache.contains("existing.clearCache(true)"));assertTrue(cache.contains("temporary.clearCache(true)"))
        assertTrue(cache.contains("finally { releaseView(temporary) }"))
        for(bad in listOf("removeAllCookies", "clearHistory", "deleteAllData", "loadUrl", "evaluateJavascript", "SharedPreferences")) assertFalse(cache.contains(bad))
        assertTrue(s.contains("fun clearCache() { clearCacheWithResult() }"))
    }
    @Test fun detailsExposeNoHistoryPayloadAndNoUnconfirmedCleanup() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/shell/PrivacySettingsDetail.kt")
        assertTrue(s.contains("historyState.items.size"));assertTrue(s.contains("historyState.notice"))
        assertTrue(s.contains("onDispose { actions.close() }"));assertTrue(s.contains("actions.reconcile()"))
        assertTrue(s.contains("onDismissRequest = { actions.cancel(pending) }"))
        assertTrue(s.contains("actions.confirm(pending)"));assertTrue(s.contains("actions.canConfirm(pending)"))
        for(bad in listOf("item.address", "item.title", "historyState.items[", "rememberSaveable", "history.confirm(", "history.load(", "clearCookies", "confirmLocalControl")) assertFalse(s.contains(bad))
    }
    @Test fun onlyConfirmedActionsReachExactPortsAndDoNotUseSavedState() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/shell/PrivacySettingsActions.kt")
        assertTrue(s.contains("state.prompt === expected"));assertTrue(s.contains("historyState() === expected.snapshot"))
        assertTrue(s.indexOf("publish(state.copy(prompt = null))") < s.indexOf("confirmHistory(expected.confirmation)"))
        for(bad in listOf("SavedStateHandle", "getSharedPreferences", "clearCookies", "DiscoveryControlRequest", "Thread(", "GlobalScope")) assertFalse(s.contains(bad))
    }
    @Test fun activityUsesCurrentGenerationAndExcludesCompetingPlatformDialogs() {
        val s=read("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").substringAfter("privacy = {").substringBefore("onSources = ::openProviderManagement")
        for(required in listOf("generation == newTabInputGeneration", "isForeground", "EsvDestination.SETTINGS", "!isDestroyed && !isFinishing", "browser.jsDialog == null", "browser.fullScreenView == null", "!browser.pickerInFlight", "clearCache = browser::clearCacheWithResult")) assertTrue(required,s.contains(required))
        for(bad in listOf("clearCookies", "confirmLocalControl", "clearCacheWithResult()", "requestClear()")) assertFalse(s.contains(bad))
    }
}
