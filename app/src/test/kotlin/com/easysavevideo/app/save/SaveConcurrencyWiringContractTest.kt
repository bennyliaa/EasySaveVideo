package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Honest source/resource contracts alongside behavioral owner and intent tests; not device acceptance. */
class SaveConcurrencyWiringContractTest {
    private fun source(path: String) = File("src/main/$path").readText()
    @Test fun additiveStoreKeyUsesLocalWrongTypeFallbackAndIndependentWriteFlags() {
        val store = source("kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        assertTrue(store.contains("readDirectConcurrencyPreference { prefs.getInt(\"save_direct_concurrency\", 1) }"))
        assertTrue(store.contains("catch (_: ClassCastException) { 1 }"))
        assertTrue(store.contains("if (includeSaveConcurrencyPreference) putInt(\"save_direct_concurrency\", settings.save.maxConcurrentDirectTasks)"))
        assertTrue(store.contains("if (includeSaveNetworkPreference) putBoolean(\"save_wifi_only\", settings.save.wifiOnly)"))
        val persistence = source("kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt")
        assertTrue(persistence.contains("PendingSettings(settings, settingsLoaded || networkPreferenceConfirmed.get(), settingsLoaded)"))
        assertTrue(persistence.contains("settingsStore.save(value.value, value.includeNetwork, value.includeConcurrency)"))
    }
    @Test fun allSuccessfulLoadsMergeWithApplicationOwnerEvenAfterEarlyWifiEdit() {
        val activity = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        val startup = activity.substringAfter("persistence.start { loaded, checkpoint, failed ->")
        assertTrue(startup.contains("if (persistence.settingsLoaded) {\n                settings = settings.copy(save = saves.initializeSaveSettings(loaded.save))"))
        assertFalse(startup.contains("!saveSettingsChanged"))
        assertTrue(startup.indexOf("saves.initializeSaveSettings(loaded.save)") < startup.indexOf("persistence.saveSettings(settings)"))
        assertTrue(startup.indexOf("saves.initializeSaveSettings(loaded.save)") < startup.indexOf("settingsReady = true"))
    }
    @Test fun concurrencyDispatchUsesCurrentGuardedSettingsAndCannotConfirmNetworkPreference() {
        val activity = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        assertTrue(activity.contains("applyDirectConcurrencySettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded,"))
        assertTrue(activity.contains("isForeground, !isDestroyed && !isFinishing, activeDestination)?.let { allowed ->"))
        assertTrue(activity.contains("saves.updateDirectConcurrency(allowed.save.maxConcurrentDirectTasks)?.let { accepted ->"))
        assertTrue(activity.contains("allowed.copy(save = accepted)"))
        assertTrue(activity.contains("val changedNetwork = edit is SettingsEdit.WifiOnly"))
        assertTrue(activity.contains("if (changedNetwork) saves.updateSaveSettings(value.save)"))
        assertTrue(activity.contains("persistence.saveSettings(value, confirmNetworkPreference = changedNetwork)"))
    }
    @Test fun runtimeOwnsFieldsAcrossActivitiesAndConcurrencyNeverUpdatesNetworkPolicy() {
        val runtime = source("kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt")
        assertTrue(runtime.contains("private val saveSettingsOwner = SaveSettingsOwner()"))
        val initialize = runtime.substringAfter("fun initializeSaveSettings(").substringBefore("\n    }")
        assertTrue(initialize.contains("saveSettingsOwner.initialize(settings)"))
        assertTrue(initialize.contains("networkPolicy.update(merged)"))
        assertTrue(initialize.contains("executionAdmission.updateMaxDirectTasks(merged.maxConcurrentDirectTasks)"))
        val wifi = runtime.substringAfter("fun updateSaveSettings(").substringBefore("\n    }")
        assertTrue(wifi.contains("saveSettingsOwner.updateWifiOnly(settings.wifiOnly)"))
        assertFalse(wifi.contains("updateConcurrency("))
        val concurrency = runtime.substringAfter("fun updateDirectConcurrency(").substringBefore("\n    }")
        assertTrue(concurrency.contains("saveSettingsOwner.updateConcurrency(value) ?: return null"))
        assertTrue(concurrency.contains("executionAdmission.updateMaxDirectTasks(updated.maxConcurrentDirectTasks)"))
        assertFalse(concurrency.contains("networkPolicy"))
        assertFalse(concurrency.contains("updateWifiOnly"))
    }
    @Test fun controlIsDisabledUntilReadyAndDisposableWithMinimumTouchTarget() {
        val ui = source("kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt")
        assertTrue(ui.contains("saveConcurrencyEditable: Boolean = false"))
        assertTrue(ui.contains("SaveConcurrencySettingsControl(settings.save.maxConcurrentDirectTasks,saveConcurrencyEditable,onChange)"))
        val control = ui.substringAfter("@Composable private fun SaveConcurrencySettingsControl")
        assertTrue(control.contains("rememberUpdatedState(editable)"))
        assertTrue(control.contains("rememberUpdatedState(onEdit)"))
        assertTrue(control.contains("SaveConcurrencySettingsActions({enabledNow}) { editNow(it) }"))
        assertTrue(control.contains("DisposableEffect(actions) { onDispose { actions.close() } }"))
        assertTrue(control.contains("heightIn(min=48.dp).selectable"))
        assertTrue(control.contains("enabled=editable,role=Role.RadioButton"))
        assertTrue(control.contains("for (option in 1..2)"))
    }
    @Test fun failedWritesKeepVisibleWarningAndNeverClaimSuccessfulPersistence() {
        val store = source("kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        assertTrue(store.contains("check(prefs.edit()")); assertTrue(store.contains(".commit())"))
        val persistence = source("kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt")
        assertTrue(persistence.contains("onFailure { writeFailure(ErrorCode.SETTINGS_UNAVAILABLE) }"))
        assertTrue(persistence.contains("main.post { if (!closed.get()) onWriteFailure() }"))
        val activity = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        assertTrue(activity.contains("settingsWriteFailed = true"))
        assertTrue(activity.contains("persistenceWarning = settingsWriteFailed, loadWarning = settingsReadFailed"))
        val ui = source("kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt")
        assertTrue(ui.contains("if(persistenceWarning) EsvText(stringResource(R.string.settings_persistence_warning)"))
    }
    @Test fun matchingLocalesExplainDirectScopeExclusiveWorkSerialVerificationAndNonPreemption() {
        val required = setOf("save_concurrency_title", "save_concurrency_waiting", "save_concurrency_one",
            "save_concurrency_two", "save_concurrency_scope", "save_concurrency_lowering")
        val localized = mutableMapOf<String, Map<String, String>>()
        for (locale in listOf("values", "values-zh-rCN")) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/save_concurrency.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
            assertEquals(required, strings.keys)
            assertTrue(strings.values.all { it.isNotBlank() })
            localized[locale] = strings
        }
        val en = localized.getValue("values")
        assertTrue(en.getValue("save_concurrency_scope").contains("Only Direct"))
        assertTrue(en.getValue("save_concurrency_scope").contains("remain exclusive"))
        assertTrue(en.getValue("save_concurrency_scope").contains("one at a time"))
        assertTrue(en.getValue("save_concurrency_lowering").contains("does not stop"))
        val zh = localized.getValue("values-zh-rCN")
        assertTrue(zh.getValue("save_concurrency_scope").contains("仅适用于 Direct"))
        assertTrue(zh.getValue("save_concurrency_scope").contains("独占"))
        assertTrue(zh.getValue("save_concurrency_scope").contains("逐个"))
        assertTrue(zh.getValue("save_concurrency_lowering").contains("不会停止"))
    }
}
