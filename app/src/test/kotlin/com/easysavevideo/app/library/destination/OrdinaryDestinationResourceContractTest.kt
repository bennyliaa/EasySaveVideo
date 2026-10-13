package com.easysavevideo.app.library.destination

import com.easysavevideo.app.R
import com.easysavevideo.library.destination.OrdinaryDestinationPreference
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM resource/source contracts, not Compose rendering or SAF/device acceptance. */
class OrdinaryDestinationResourceContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val prefix = "ordinary_destination_"
    private val expectedKeys = setOf(
        "title", "description", "selected", "app_library", "ask", "album_copy", "folder_copy",
        "folder_notice", "reselect_folder", "select_folder", "forget_folder", "cancel_folder",
        "change_notice", "close_notice", "admitting", "acknowledge", "prompt_title", "prompt_description",
        "remembered_folder_copy", "prompt_folder_notice", "cancel_creation", "history_title",
        "task_summary", "cancel_copy", "history_notice", "reconcile"
    ).map { prefix + it }.toSet()
    private fun source(relative: String): String = File(root, "kotlin/com/easysavevideo/app/$relative").readText()
    private val ui by lazy { source("library/destination/OrdinaryDestinationUi.kt") }
    private val mapper by lazy { source("library/destination/OrdinaryDestinationResources.kt") }
    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/ordinary_destination.xml"))
            .getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val key = node.attributes.getNamedItem("name").nodeValue
            assertNull("duplicate resource $key", result.put(key, node.textContent))
        }
        return result
    }

    @Test fun exactOwnedKeysArePairedAndOnlyExpectedPositionalArgumentsArePresent() {
        val contracts = mapOf(prefix + "selected" to listOf("%1\$s"), prefix + "task_summary" to listOf("%1\$s"))
        for (locale in listOf("values", "values-zh-rCN")) {
            val text = strings(locale)
            assertEquals(expectedKeys, text.keys)
            text.forEach { (key, value) ->
                assertTrue(key, value.isNotBlank())
                assertFalse(key, value.contains('\uFFFD'))
                val formats = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[a-zA-Z%]").findAll(value).map { it.value }.toList()
                assertEquals(key, contracts[key].orEmpty(), formats)
                assertEquals(key, formats.size, value.count { it == '%' })
            }
        }
    }

    @Test fun selectedLabelsAndRawShortTaskIdsAreSingleUninterpretedFormatArguments() {
        val taskIds = listOf("abc12345-rest", "id%s原文12-rest", "short")
        for (locale in listOf("values", "values-zh-rCN")) {
            val text = strings(locale)
            for (key in listOf("app_library", "ask", "album_copy", "folder_copy")) {
                val label = text.getValue(prefix + key)
                val rendered = String.format(Locale.US, text.getValue(prefix + "selected"), label)
                assertEquals(text.getValue(prefix + "selected").replace("%1\$s", label), rendered)
                assertTrue(rendered.endsWith(label))
            }
            for (taskId in taskIds) {
                val shortId = taskId.take(8)
                assertEquals(text.getValue(prefix + "task_summary").replace("%1\$s", shortId),
                    String.format(Locale.US, text.getValue(prefix + "task_summary"), shortId))
            }
        }
        assertTrue(ui.contains("stringResource(R.string.ordinary_destination_selected, label) else label"))
        assertTrue(ui.contains("stringResource(R.string.ordinary_destination_task_summary, status.creationId.take(8))"))
    }

    @Test fun everyPreferenceMapsToItsOwnCurrentResource() {
        val expected = mapOf(
            OrdinaryDestinationPreference.APP_LIBRARY to R.string.ordinary_destination_app_library,
            OrdinaryDestinationPreference.ASK to R.string.ordinary_destination_ask,
            OrdinaryDestinationPreference.ALBUM_COPY to R.string.ordinary_destination_album_copy,
            OrdinaryDestinationPreference.FOLDER_COPY to R.string.ordinary_destination_folder_copy
        )
        assertEquals(OrdinaryDestinationPreference.entries.toSet(), expected.keys)
        expected.forEach { (preference, resource) -> assertEquals(resource, ordinaryDestinationLabelResource(preference)) }
        assertEquals(expected.size, expected.values.toSet().size)
        assertTrue(ui.contains("val label = stringResource(ordinaryDestinationLabelResource(value))"))
        assertFalse(ui.contains("ordinaryDestinationLabel(value)"))
    }

    @Test fun fixedCopyUsesCompositionResourcesOnTheExistingRealEntryPoints() {
        val references = Regex("R\\.string\\.(ordinary_destination_[a-z_]+)").findAll(ui + mapper)
            .map { it.groupValues[1] }.filterNot { it.startsWith(prefix + "notice_") || it.startsWith(prefix + "copy_") }.toSet()
        assertEquals(expectedKeys, references)
        assertTrue(ui.contains("import androidx.compose.ui.res.stringResource"))
        // All app-generated display text resolves through the current Composition.
        assertFalse(ui.contains('"'))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(ui + mapper))
        listOf("Locale.getDefault", "getString(", "remember {", "LaunchedEffect", "DisposableEffect")
            .forEach { assertFalse(it, (ui + mapper).contains(it)) }
        assertTrue(source("ui/BrowserScreen.kt").contains("destinations?.let { OrdinaryDestinationSettings(it) }"))
        assertTrue(source("MainActivity.kt").contains("if (isForeground) saves.destinations?.let { OrdinaryDestinationDialog(it) }"))
        assertTrue(source("save/SaveTaskScreen.kt").contains("runtime.destinations?.let { OrdinaryDestinationHistory(it) }"))
        assertEquals(2, Regex("OrdinaryDestinationNotice\\(runtime\\)").findAll(ui).count())
    }

    @Test fun realCallbacksAndDismissalActionsAreUnchangedAndNeverInvokeOnComposition() {
        val callbacks = Regex("onClick\\s*=\\s*(\\{[^}]+\\}|runtime::[A-Za-z]+)").findAll(ui)
            .map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf(
            "{ runtime.updatePreference(value) }", "runtime::requestFolder", "runtime::forgetFolder",
            "runtime::cancelFolderSelection", "runtime::clearNotice", "runtime::clearNotice",
            "{ runtime.choose(OrdinaryDestinationChoice.APP_LIBRARY) }",
            "{ runtime.choose(OrdinaryDestinationChoice.ALBUM_COPY) }",
            "{ runtime.choose(OrdinaryDestinationChoice.FOLDER_COPY) }", "runtime::cancelSelection",
            "{ runtime.cancelCopy(status.creationId) }", "runtime::reconcile"
        ), callbacks)
        assertEquals(listOf("runtime::clearNotice", "runtime::cancelSelection"),
            Regex("onDismissRequest\\s*=\\s*(runtime::[A-Za-z]+)").findAll(ui).map { it.groupValues[1] }.toList())
        assertEquals(listOf("updatePreference", "choose", "choose", "choose", "cancelCopy"),
            Regex("runtime\\.([A-Za-z]+)\\(").findAll(ui).map { it.groupValues[1] }.toList())
    }

    @Test fun busyFolderAndSavedRowGatesAndTypedDisplayConsumersRemainUnchanged() {
        val ready = "runtime.ready && !runtime.savingPreference && !runtime.choosingFolder"
        assertEquals(listOf(ready, ready, ready, "runtime.hasRememberedFolder"),
            Regex("enabled = ([^,\\n)]+)").findAll(ui).map { it.groupValues[1].trim() }.toList())
        listOf(
            "OrdinaryDestinationPreference.entries.forEach { value ->", "if (runtime.preference == value)",
            "if (runtime.hasRememberedFolder) EsvButton", "if (runtime.choosingFolder) EsvButton",
            "if (runtime.admitting) EsvText", "if (!runtime.asking)", "val notice = runtime.notice ?: return",
            "runtime.statuses.filter { it.internalSaved }.take(8).forEach { status ->",
            "if (ordinaryCopyCanCancel(status)) EsvButton", "runtime.notice?.let {", "EsvText(stringResource(ordinaryDestinationNoticeResource(it)),",
            "EsvText(stringResource(ordinaryDestinationNoticeResource(notice)))", "val display = ordinaryCopyDisplay(status)",
            "EsvText(stringResource(ordinaryCopyStateResource(display.state),",
            "stringResource(ordinaryCopyKindResource(display.kind))), style = v.captionTextStyle)",
            "heightIn(max = 600.dp)", "verticalScroll(rememberScrollState())"
        ).forEach { assertTrue(it, ui.contains(it)) }
    }

    @Test fun bothLanguagesRetainOriginalCopyAndPermissionBoundaries() {
        val en = strings("values")
        val zh = strings("values-zh-rCN")
        assertTrue(en.getValue(prefix + "description").contains("whether copying succeeds, fails, or is canceled"))
        assertTrue(zh.getValue(prefix + "description").contains("成功、失败或取消都保留 App媒体库原件"))
        assertTrue(en.getValue(prefix + "prompt_description").contains("original is kept in every outcome"))
        assertTrue(zh.getValue(prefix + "prompt_description").contains("所有结果都保留 App媒体库原件"))
        assertTrue(en.getValue(prefix + "change_notice").contains("does not revoke its old system permission"))
        assertTrue(zh.getValue(prefix + "change_notice").contains("不会撤销旧系统授权"))
        assertTrue(en.getValue(prefix + "change_notice").contains("Vault is still disabled"))
        assertTrue(zh.getValue(prefix + "change_notice").contains("保险箱仍未启用"))
        assertTrue(en.getValue(prefix + "folder_notice").contains("may sync to the cloud"))
        assertTrue(zh.getValue(prefix + "folder_notice").contains("可能同步到云端"))
        assertTrue(en.getValue(prefix + "task_summary").contains("at creation"))
        assertTrue(zh.getValue(prefix + "task_summary").contains("创建时"))
    }
}
