package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source checks supplement JVM policy tests; they do not claim Compose/device acceptance. */
class SearxngHtmlSettingsContractTest {
    private fun ui() = File("src/main/kotlin/com/easysavevideo/app/ui/SearxngSourceSettings.kt").readText()
    private fun resources(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$locale/searxng_settings.xml"))
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index -> nodes.item(index).let {
            it.attributes.getNamedItem("name").nodeValue to it.textContent
        } }
    }

    @Test fun dynamicListShowsCountEmptyStateAndCapacityAndAddsOnlyThroughAnOwnedClaim() {
        val source = ui()
        for (required in listOf("config.slots.forEachIndexed", "config.slots.isEmpty()",
            "R.string.searxng_settings_count, config.slots.size, SearxngConfigurationPolicy.MAX_INSTANCES",
            "R.string.searxng_settings_empty", "R.string.searxng_settings_add", "R.string.searxng_settings_capacity",
            "it < SearxngConfigurationPolicy.MAX_INSTANCES", "state.editable && idle() && hasCapacity",
            "if (idle()) controller.beginAdd()?.let", "if (idle()) controller.beginEdit(slot.id)?.let"))
            assertTrue(required, source.contains(required))
        assertFalse(source.contains("SLOT_IDS"))
        assertFalse(source.contains("searxng_settings_configure"))
        assertFalse(source.contains("searxng_settings_not_configured"))
        assertFalse(source.contains("https://")) // No prefilled or silently trusted public address.
    }

    @Test fun newInstanceRequiresExplicitFormatChoiceAndPrivacyAcknowledgementWhileStartingDisabled() {
        val source = ui()
        assertTrue(source.contains("val adding = claim.kind == SearxngConfigurationAction.ADD"))
        assertTrue(source.contains("mutableStateOf(original?.searchUrl.orEmpty())"))
        assertTrue(source.contains("mutableStateOf(original?.enabled ?: false)"))
        assertTrue(source.contains("mutableStateOf(original?.format ?: SearxngResultFormat.JSON_VIDEOS)"))
        assertTrue(source.contains("var formatChosen by remember(claim) { mutableStateOf(!adding) }"))
        assertTrue(source.contains("var acknowledged by remember(claim) { mutableStateOf(false) }"))
        assertTrue(source.contains("Column(Modifier.selectableGroup())"))
        assertTrue(source.contains("role = Role.RadioButton"))
        assertTrue(source.contains("formatChosen && format == choice, current"))
        assertTrue(source.contains("if (isCurrent() && (!formatChosen || format != choice))"))
        assertTrue(source.contains("format = choice; formatChosen = true; acknowledged = false"))
        assertTrue(source.contains("current && url.isNotEmpty() && formatChosen"))
        assertTrue(source.contains("if (!formatChosen) EsvText(stringResource(R.string.searxng_settings_choose_format)"))
        assertTrue(source.contains("enabled = current && formatChosen && validation is SearxngConfigurationEdit.Accepted"))
        assertTrue(source.contains("if (isCurrent() && formatChosen && validation is SearxngConfigurationEdit.Accepted)"))
        assertTrue(source.contains("SearxngConfigurationPolicy.add(config, url, enabled, acknowledged, format)"))
        assertTrue(source.contains("SearxngConfigurationPolicy.replace(config, it.id, url, enabled, acknowledged, format)"))
        assertTrue(source.contains("SearxngConfigurationPolicy.acknowledgementRequired(original, url, enabled, format)"))
        assertTrue(source.contains("controller.add(claim, url, enabled, acknowledged, format)"))
        assertTrue(source.contains("controller.replace(claim, url, enabled, acknowledged, format)"))
        assertTrue(source.contains("EsvText(stringResource(formatLabel(slot.format))"))
    }

    @Test fun individualDeletionRequiresItsOwnHostnameSpecificConfirmationAndCannotUseAnEditorClaim() {
        val source = ui()
        assertTrue(source.contains("var deletion by remember(actions) { mutableStateOf<SearxngConfigurationClaim?>(null) }"))
        assertTrue(source.contains("var deletionHost by remember(actions) { mutableStateOf(\"\") }"))
        assertTrue(source.contains("if (idle()) controller.beginDelete(slot.id)?.let"))
        assertTrue(source.contains("deletionHost = slot.hostname.orEmpty(); deletion = claim; onDialogChanged(true)"))
        assertTrue(source.contains("actions.isCurrent() && deletion === claim && controller.isCurrent(claim)"))
        assertTrue(source.contains("claim.kind == SearxngConfigurationAction.DELETE"))
        assertTrue(source.contains("Dialog(onDismissRequest = { dismissDeletion(claim) })"))
        assertTrue(source.contains("R.string.searxng_settings_remove_description, deletionHost"))
        assertTrue(source.contains("if (deletionCurrent(claim) && controller.remove(claim)) dismissDeletion(claim)"))
        assertTrue(source.contains("if (deletion === claim) { deletion = null; deletionHost = \"\"; onDialogChanged(false) }"))
        assertTrue(source.contains("deletion?.takeIf { !deletionCurrent(it) }?.let(::dismissDeletion)"))
        val editor = source.substringAfter("private fun SearxngEndpointEditor(")
        assertFalse(editor.contains("controller.remove("))
        assertFalse(editor.contains("beginDelete("))
        assertFalse(source.contains("controller.replace(claim, \"\""))
    }

    @Test fun everyDialogKeepsCapturedOwnershipGenerationAndPurposeAndClearAllStaysSeparate() {
        val source = ui()
        assertTrue(source.contains("remember(controller, canAct) { CapturedPrivateActions(false, { true }, canAct) }"))
        assertTrue(source.contains("DisposableEffect(actions) { onDispose { actions.close(); onDialogChanged(false) } }"))
        assertTrue(source.contains("fun idle() = actions.isCurrent() && canStart() && edit == null && reset == null && deletion == null"))
        assertEquals(3, Regex("remember\\(actions\\) \\{ mutableStateOf<SearxngConfigurationClaim\\?>").findAll(source).count())
        assertTrue(source.contains("claim.kind in setOf(SearxngConfigurationAction.ADD, SearxngConfigurationAction.EDIT)"))
        assertTrue(source.contains("claim.kind == SearxngConfigurationAction.RESET"))
        assertTrue(source.contains("if (idle()) controller.beginReset()?.let"))
        assertTrue(source.contains("Dialog(onDismissRequest = { dismissReset(claim) })"))
        assertTrue(source.contains("if (resetCurrent(claim) && controller.reset(claim)) dismissReset(claim)"))
        assertTrue(source.contains("fun isCurrent() = canAct() && controller.isCurrent(claim)"))
        assertTrue(source.contains("edit?.takeIf { !editCurrent(it) }?.let(::dismissEdit)"))
        assertTrue(source.contains("reset?.takeIf { !resetCurrent(it) }?.let(::dismissReset)"))
        for (forbidden in listOf("rememberUpdatedState", "invalidateClaims()", "controller.close()"))
            assertFalse(forbidden, source.contains(forbidden))
    }

    @Test fun settingsMutationsDoNotCreateProvidersProbeSearchOrChangeSafeSearch() {
        val source = ui()
        for (forbidden in listOf("SearxngHtmlSearchProvider(", "SearxngSearchProvider(", "SystemSearxngTransport(",
            "enabled = true", "safeSearch = false", "submitFilterDraft(", ".search(", "searchlinks",
            "HttpURLConnection", "OkHttpClient", "URLConnection", "InetAddress", "java.net.URL", ".probe("))
            assertFalse(forbidden, source.contains(forbidden))
    }

    @Test fun bothLanguagesDiscloseUnverifiedConfigurationLoggingBudgetAndNoAutomaticFallback() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(en.keys, zh.keys); assertEquals(48, en.size)
        val formats = Regex("%(?:[0-9]+\\$)?[dsf]")
        en.keys.forEach { key ->
            assertTrue(key, en.getValue(key).isNotBlank()); assertTrue(key, zh.getValue(key).isNotBlank())
            assertEquals(key, formats.findAll(en.getValue(key)).map { it.value }.toList(),
                formats.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        for (key in listOf("privacy", "editor_privacy", "acknowledge")) {
            assertTrue(en.getValue("searxng_settings_$key").contains("may log"))
            assertTrue(zh.getValue("searxng_settings_$key").contains("可能记录"))
        }
        assertTrue(en.getValue("searxng_settings_verification_notice").contains("Configured does not mean verified"))
        assertTrue(zh.getValue("searxng_settings_verification_notice").contains("已配置不代表已验证"))
        assertTrue(en.getValue("searxng_settings_budget_notice").contains("may not be visited"))
        assertTrue(zh.getValue("searxng_settings_budget_notice").contains("部分已启用实例可能不会被访问"))
        assertTrue(en.getValue("searxng_settings_format_notice").contains("awaiting validation"))
        assertTrue(en.getValue("searxng_settings_format_notice").contains("never switch to HTML automatically"))
        assertTrue(zh.getValue("searxng_settings_format_notice").contains("不能证明存在视频"))
        assertTrue(zh.getValue("searxng_settings_format_notice").contains("不会自动切换到 HTML"))
        assertTrue(en.getValue("searxng_settings_safe_search").contains("rejected without sending a query"))
        assertTrue(zh.getValue("searxng_settings_safe_search").contains("不会改变已请求的安全搜索设置"))
        assertTrue(en.getValue("searxng_settings_malformed").contains("original file is preserved"))
        assertTrue(zh.getValue("searxng_settings_malformed").contains("原始文件已保留"))
        assertTrue(en.getValue("searxng_settings_endpoint_hint").contains("ending in /search"))
        assertTrue(en.getValue("searxng_settings_endpoint_hint").contains("base path"))
        assertTrue(zh.getValue("searxng_settings_endpoint_hint").contains("基础路径"))
        assertTrue(en.getValue("searxng_settings_remove_description").contains("%1\$s"))
        assertTrue(en.getValue("searxng_settings_clear_description").contains("every configured instance"))
        for (value in en.values) {
            assertFalse(value.contains("all three"))
            assertFalse(value.contains("leave empty to remove"))
            assertFalse(value.contains("up to 3"))
        }
    }

    @Test fun newResourcesAreUsedAndRegisteredAsGradleContractInputs() {
        val source = ui()
        resources("values").keys.forEach { key -> assertTrue(key, source.contains("R.string.$key")) }
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("\"searxng_settings.xml\""))
        assertTrue(build.contains("\"src/main/kotlin/com/easysavevideo/app/ui/SearxngSourceSettings.kt\""))
    }
}
