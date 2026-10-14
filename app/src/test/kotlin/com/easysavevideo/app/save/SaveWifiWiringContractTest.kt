package com.easysavevideo.app.save

import com.easysavevideo.app.R
import com.easysavevideo.app.ui.shell.*
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source contracts complement fake-network tests; they are not device acceptance. */
class SaveWifiWiringContractTest {
    private fun source(path: String) = File("src/main/$path").readText()
    @Test fun preferenceKeyIsAdditiveAndUnknownLoadCannotOverwriteIt() {
        val store = source("kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        assertTrue(store.contains("prefs.getBoolean(\"save_wifi_only\", false)"))
        assertTrue(store.contains("if (includeSaveNetworkPreference) putBoolean(\"save_wifi_only\", settings.save.wifiOnly)"))
        val persistence = source("kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt")
        assertTrue(persistence.contains("settingsLoaded || networkPreferenceConfirmed.get()"))
        assertTrue(persistence.contains("settingsStore.save(value.value, value.includeNetwork, value.includeConcurrency)"))
        val activity = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        assertTrue(activity.contains("if (persistence.settingsLoaded) {"))
        assertFalse(activity.contains("!saveSettingsChanged && persistence.settingsLoaded"))
        assertTrue(activity.contains("saves.initializeSaveSettings(loaded.save)"))
        assertTrue(activity.contains("confirmNetworkPreference = changedNetwork"))
        assertTrue(activity.contains("val changedNetwork = edit is SettingsEdit.WifiOnly"))
        assertTrue(activity.contains("val value = if (edit is SettingsEdit.DiscoveryEdit)"))
        assertTrue(activity.contains("else edit.applyTo(settings)"))
        assertTrue(activity.contains("if (value != null) {"))
        assertTrue(activity.contains("applyBrowserSettingsEdit(settings, edit"))
    }
    @Test fun settingsCopyAndNavigationRetainEditorAndOtherSiblings() {
        val old = AppSettings(editor = EditorSettings(EditorCompressionMode.SAVE_SPACE))
        val next = settingsWithWifiOnly(old, true)
        assertTrue(next.save.wifiOnly)
        assertEquals(old.editor, next.editor); assertEquals(old.browser, next.browser)
        assertEquals(old.discovery, next.discovery); assertEquals(old.appearance, next.appearance)
        assertEquals(SettingsCategory.SAVE, SettingsCategory.fromKey("save"))
        for (split in listOf(false, true)) assertEquals(SettingsCategory.SAVE, settingsPresentation("save", split).active)
        assertEquals(SettingsCategory.EDITOR, SettingsCategory.fromKey("editor"))
    }
    @Test fun staleUnrelatedUiIntentCannotOverwriteNewlyLoadedRestriction() {
        val placeholder = AppSettings()
        val browserIntent = SettingsEdit.BrowserJavaScript(false)
        val themeIntent = SettingsEdit.Theme(ThemeMode.DARK)
        val editorIntent = SettingsEdit.EditorCompression(EditorCompressionMode.SAVE_SPACE)
        val loaded = placeholder.copy(save = SaveSettings(true), appearance = AppearanceSettings(language = AppLanguage.ENGLISH))
        assertTrue(browserIntent.applyTo(loaded).save.wifiOnly)
        assertEquals(AppLanguage.ENGLISH, browserIntent.applyTo(loaded).appearance.language)
        assertTrue(themeIntent.applyTo(loaded).save.wifiOnly)
        assertTrue(editorIntent.applyTo(loaded).save.wifiOnly)
        assertTrue(SettingsEdit.Language(AppLanguage.CHINESE).applyTo(loaded).save.wifiOnly)
        assertFalse(SettingsEdit.WifiOnly(false).applyTo(loaded).save.wifiOnly)
    }
    @Test fun networkGateIsSharedByDirectPreparationAndCoordinator() {
        val runtime = source("kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt")
        assertTrue(runtime.contains("SystemHttpsDirectTransfer(staging, leases, networkPolicy = networkPolicy)"))
        assertTrue(runtime.contains("AndroidHlsStreamPort(application, storage, leases, limits, clock, networkPolicy)"))
        assertTrue(runtime.contains("networkGate = SaveNetworkGate { networkPolicy.checkAdmission() }"))
        assertTrue(runtime.contains("coordinator.deferForNetwork(taskId)"))
        assertTrue(runtime.contains("record.needsNetworkTransfer() && !networkSaveAllowed()"))
        val hls = source("kotlin/com/easysavevideo/app/save/stream/AndroidHlsStreamPort.kt")
        assertTrue(hls.contains("SystemHttpsHlsFetcher(leases, clock, networkPolicy = networkPolicy)"))
    }
    @Test fun platformRoutePinsUrlDnsAndSocketWithoutGlobalBindingOrMeteringShortcut() {
        val adapter = source("kotlin/com/easysavevideo/app/save/AndroidSaveNetworkBackend.kt")
        listOf("TRANSPORT_WIFI", "reportedSaveTransports { caps.hasTransport(it) }", "manager.activeNetwork != first", "network.openConnection(uri.toURL(), Proxy.NO_PROXY)",
            "network.getAllByName(host)", "network.socketFactory.createSocket()").forEach { assertTrue(it, adapter.contains(it)) }
        assertFalse(adapter.contains("NET_CAPABILITY_NOT_METERED"))
        assertFalse(adapter.contains("bindProcessToNetwork"))
    }
    @Test fun localizedCopyExplainsManualAndStreamReselectionWithoutBackgroundClaim() {
        val required = setOf("ui_settings_save", "ui_save_wifi_only", "ui_save_wifi_description", "save_wifi_required", "save_wifi_stream_wait", "save_wifi_settings_unavailable")
        for (locale in listOf("values", "values-zh-rCN")) {
            val file = File("src/main/res/$locale/save_wifi.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            val names = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
            assertEquals(required, names)
        }
        assertEquals(R.string.save_wifi_required, saveFailureTextResource(SaveError.WIFI_REQUIRED))
        assertEquals(SaveWaitReason.NETWORK, SaveErrors.policy(SaveError.WIFI_REQUIRED).waitReason)
        assertFalse(SaveErrors.policy(SaveError.WIFI_REQUIRED).retryable)
    }
}
