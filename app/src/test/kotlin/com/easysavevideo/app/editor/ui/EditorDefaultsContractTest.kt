package com.easysavevideo.app.editor.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Wiring checks supplement pure policy tests; they do not claim on-device preferences/UI execution. */
class EditorDefaultsContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    @Test fun storeReadsAndWritesSameStableAdditiveKey() {
        val store = source("storage/AndroidStores.kt")
        assertTrue(store.contains("EditorCompressionMode.fromKey(prefs.getString(\"editor_default_compression\", null))"))
        assertTrue(store.contains(".putString(\"editor_default_compression\", settings.editor.defaultCompression.key)"))
        assertTrue(store.contains("getSharedPreferences(\"browser_settings_v1\""))
    }
    @Test fun bothEntrypointsUseCurrentSettingsAndOnlyNewDraftsConsumeThem() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("editor.open(item, available, settings.editor)"))
        assertTrue(activity.contains("editor.open(item, library.state.items, settings.editor)"))
        assertTrue(activity.contains("private var settingsReady by mutableStateOf(false)"))
        assertTrue(activity.contains("onEdit = if (editor == null || !settingsReady) null"))
        assertTrue(activity.contains("onOpenVerifiedEditor = if (!settingsReady ||"))
        assertTrue(activity.substringAfter("persistence.start { loaded, checkpoint, failed ->").contains("settingsReady = true"))
        val runtime = source("editor/AndroidEditRuntime.kt")
        assertTrue(runtime.contains("startNew = !draftStarted || state.phase == EditorPhase.SUCCEEDED"))
        assertTrue(runtime.substringAfter("fun changeDraft").substringBefore("private fun inspectMissingSources").contains("draftStarted = true"))
        val restore = runtime.substringAfter("internal fun restoreDraftCheckpoint").substringBefore("internal fun savedDraftCheckpoint")
        assertTrue(restore.contains("draftStarted = true")); assertFalse(restore.contains("openEditorDraft("))
        assertFalse(restore.contains("settings.editor"))
        assertTrue(runtime.contains("if (!featuresAllowed(required))"))
        assertTrue(runtime.contains("if (!featuresAllowed(needed))"))
    }
    @Test fun bothLocalesHaveSameNonblankLabelsAndExistingRadioGroupSemantics() {
        val expected = setOf("ui_settings_editor", "ui_editor_default_compression", "ui_editor_original_quality",
            "ui_editor_save_space", "ui_editor_default_compression_description")
        for (locale in listOf("values", "values-zh-rCN")) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "src/main/res/$locale/editor_defaults.xml")).getElementsByTagName("string")
            assertEquals(expected, (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet())
            for (i in 0 until nodes.length) assertTrue(nodes.item(i).textContent.isNotBlank())
        }
        val section = source("ui/shell/EsvSettingsScreen.kt").substringAfter("SettingsCategory.EDITOR -> {").substringBefore("SettingsCategory.DIAGNOSTICS -> diagnostics()")
        assertTrue(section.contains("selectableGroup()")); assertTrue(section.contains("role=Role.RadioButton"))
        assertTrue(section.contains("settingsWithEditorCompression(settings,mode)")); assertTrue(section.contains("heightIn(min=48.dp)"))
    }
}
