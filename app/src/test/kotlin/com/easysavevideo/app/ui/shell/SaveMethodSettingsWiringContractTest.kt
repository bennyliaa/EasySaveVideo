package com.easysavevideo.app.ui.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Honest source/resource checks supplement behavior tests; no device-rendering claim. */
class SaveMethodSettingsWiringContractTest {
    private fun source(path: String) = File("src/main/$path").readText()

    @Test fun centralSaveSettingsExposeThreeIndependentDisposableSwitches() {
        val central = source("kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt")
        assertTrue(central.contains("saveMethodsEditable: Boolean = false"))
        assertTrue(central.contains("SaveMethodSettingsDetail(settings.save,saveMethodsEditable,onChange)"))
        val ui = source("kotlin/com/easysavevideo/app/ui/shell/SaveMethodSettingsDetail.kt")
        for (needed in listOf("rememberUpdatedState(editable)", "rememberUpdatedState(onEdit)",
            "SaveMethodSettingsActions({enabledNow}) { editNow(it) }", "onDispose { actions.close() }",
            "TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD", "heightIn(min=48.dp)",
            "enabled=editable,role=Role.Switch", "SettingsEdit.SaveMethodEnabled(method,it)", "LocalEsvVisuals.current"))
            assertTrue(needed, ui.contains(needed))
        for (forbidden in listOf("rememberSaveable", "verticalScroll", "getSharedPreferences", "canSave(", "cancel(", "stop(", "discard("))
            assertFalse(forbidden, ui.contains(forbidden))
    }

    @Test fun additivePrimitiveKeysReadLegacyDefaultsAndWriteOnlyLiveKnownMethodBits() {
        val store = source("kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        for ((key, property) in listOf("direct" to "directEnabled", "stream" to "streamEnabled", "record" to "recordEnabled")) {
            assertTrue(store.contains("$property = readSaveMethodPreference { prefs.getBoolean(\"save_${key}_enabled\", true) }"))
            assertTrue(store.contains("putBoolean(\"save_${key}_enabled\", liveMethods.$property)"))
            assertFalse(store.contains("putBoolean(\"save_${key}_enabled\", settings.save.$property)"))
        }
        val save = store.substringAfter("fun save(settings: AppSettings, includeSaveNetworkPreference: Boolean")
        assertTrue(save.contains("saveMethodPreferencesForWrite(includeSaveConcurrencyPreference, loadedSuccessfully,"))
        assertTrue(save.indexOf("SettingsWriteBoundary.commit(") < save.indexOf("saveMethodPreferencesForWrite("))
        assertTrue(save.indexOf("saveMethodPreferencesForWrite(") < save.indexOf(".commit()"))
        val persistence = source("kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt")
        assertTrue(persistence.contains("AndroidSettingsStore(context, acceptedVaultPolicy, acceptedSaveMethodSettings)"))
        assertTrue(persistence.contains("onFailure { writeFailure(ErrorCode.SETTINGS_UNAVAILABLE) }"))
    }

    @Test fun methodEditsHaveNoActiveTaskCancellationNetworkOrEntitlementSideEffects() {
        val owner = source("kotlin/com/easysavevideo/app/save/SaveSettingsOwner.kt")
        val edit = owner.substringAfter("fun updateMethodEnabled(").substringBefore("\n    }")
        assertTrue(edit.contains("!knownMethods")); assertTrue(edit.contains("current.withMethodEnabled(method, enabled)"))
        for (forbidden in listOf("networkPolicy", "canSave", "cancel", "stop", "discard", "executionAdmission"))
            assertFalse(forbidden, edit.contains(forbidden))
        assertTrue(owner.contains("knownMethods && current.isMethodEnabled(method)"))
        assertTrue(owner.contains("TaskMethod.BROWSER, TaskMethod.EDIT -> true"))
    }

    @Test fun applicationCompositionMergesRetainedChoicesAndWritesOnlyAcceptedEdits() {
        val main = source("kotlin/com/easysavevideo/app/MainActivity.kt")
        assertTrue(main.contains("acceptedSaveMethodSettings = { saves.acceptedSaveMethodSettings() }"))
        assertTrue(main.contains("applySaveMethodSettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded,"))
        assertTrue(main.contains("saves.updateSaveMethodEnabled(edit.method, edit.enabled)?.let { accepted -> allowed.copy(save = accepted) }"))
        assertTrue(main.contains("saveMethodsEditable = settingsReady && persistence.settingsLoaded && isForeground &&"))
        assertTrue(main.contains("settings = settings.copy(save = saves.initializeSaveSettings(loaded.save),"))
        assertTrue(main.contains("val changedNetwork = edit is SettingsEdit.WifiOnly"))
        assertTrue(main.contains("persistence.saveSettings(value, confirmNetworkPreference = changedNetwork)"))
    }

    @Test fun turnedOffMethodsHaveTheirOwnBilingualNoticeRatherThanAnEntitlementMessage() {
        val notice = source("kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeResources.kt")
        assertTrue(notice.contains("SaveRuntimeFixedNotice.METHOD_DISABLED -> R.string.save_runtime_notice_method_disabled"))
        for (locale in listOf("values", "values-zh-rCN")) {
            val xml = source("res/$locale/save_runtime_notice.xml")
            assertTrue(xml.contains("name=\"save_runtime_notice_method_disabled\""))
        }
        val runtime = source("kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt")
        assertTrue(runtime.contains("fun isSaveMethodDisabled(method: TaskMethod): Boolean = saveSettingsOwner.acceptedMethodSettings()?.isMethodEnabled(method) == false"))
        val choose = runtime.substringAfter("fun choose(candidate:").substringBefore("private fun armSelectionEvaluation")
        assertTrue(choose.indexOf("isSaveMethodDisabled(candidate.method)") < choose.indexOf("!canSave(candidate.method)"))
    }

    @Test fun pairedLocalesExplainNewTasksExistingWorkAllOffAndSafety() {
        fun strings(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/save_method_settings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).let { node ->
                node.attributes.getNamedItem("name").nodeValue to node.textContent } }
        }
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(8, en.size); assertEquals(en.keys, zh.keys)
        assertTrue((en.values + zh.values).all { it.isNotBlank() })
        assertTrue(en.getValue("save_methods_new_tasks").contains("new tasks only"))
        assertTrue(en.getValue("save_methods_new_tasks").contains("Stop and discard remain available"))
        assertTrue(en.getValue("save_methods_all_off").contains("Browsing and search remain available"))
        assertTrue(en.getValue("save_methods_safety").contains("permissions, entitlement and safety checks"))
        assertTrue(zh.getValue("save_methods_new_tasks").contains("仅影响新任务"))
        assertTrue(zh.getValue("save_methods_all_off").contains("浏览和搜索仍可使用"))
    }
}
