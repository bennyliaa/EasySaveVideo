package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source wiring complements the executable gate tests; neither claims Android/Compose acceptance. */
class SourceSettingsEntryContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun sources() = source("MainActivity.kt").substringAfter("else if (route == EsvDestination.SOURCES) {")
        .substringBefore("else if (route == EsvDestination.DISCOVER)")

    @Test fun sourcesUsesTheExistingSharedControllerAndLeavesDiscoveryEntryIntact() {
        val entry = sources()
        assertTrue(entry.contains("sourceSettings = discovery.sourceSettings, canAct = sourcePresentationCurrent"))
        assertTrue(source("ui/ProviderManagementScreen.kt").contains("SearxngSourceSettings(sourceSettings, canAct = canAct,"))
        assertTrue(source("ui/DiscoveryScreen.kt").contains("SearxngSourceSettings(controller.sourceSettings)"))
        for (forbidden in listOf("SearxngConfigurationController(", "AndroidSearxngConfigurationOwner.get(",
            "SearxngConfigurationStore(", "submitFilterDraft(", "search(", "enableDemo(", "reload()"))
            assertFalse(forbidden, entry.contains(forbidden))
    }
    @Test fun sourcesCapturesRouteAndLifecycleIdentityButReadsTheLivePredicateAtActionTime() {
        val entry = sources()
        assertTrue(entry.contains("val sourceGeneration = newTabInputGeneration"))
        assertTrue(entry.contains("remember(chromeActions, routeEntry, sourceGeneration)"))
        for (required in listOf("chromeActions.isCurrent()", "routeOwnsPresentation()", "navController === controller",
            "sourceGeneration == newTabInputGeneration", "isForeground", "!isDestroyed && !isFinishing",
            "activeDestination == EsvDestination.SOURCES", "!browser.isPrivateMode",
            "browser.state.selectedTab?.incognito != true", "!browser.pickerInFlight", "saves.selection == null",
            "saves.destinations?.admitting != true", "libraryPreviewReference == null")) assertTrue(required, entry.contains(required))
        assertFalse(entry.contains("hasFocusedWindow")) // A child Dialog legitimately owns window focus.
        assertFalse(entry.contains("rememberUpdatedState")) // An old route must not acquire a newer owner.
        assertTrue(source("MainActivity.kt").substringAfter("override fun onPause()")
            .substringBefore("override fun onDestroy()").contains("newTabInputGeneration++"))
    }
    @Test fun commonsCallbacksRecheckCurrentRuntimeAndRecoverySnapshot() {
        val entry = sources()
        assertTrue(entry.contains("!discovery.filterControlsBusy && discovery.state.commonsEnabled != enabled"))
        assertTrue(entry.contains("providerManagement.state.active && !providerManagement.state.busy"))
        assertTrue(entry.contains("providerManagement.state.snapshot == managementSnapshot"))
        assertTrue(entry.contains("onRejectCandidate = { if (managementCurrent()) providerManagement.rejectCandidate() }"))
        assertTrue(entry.contains("onRollback = { if (managementCurrent()) providerManagement.rollback() }"))
    }
    @Test fun sourceDialogsUseLocalIdentityAndControllerGenerationOnEverySubmission() {
        val ui = source("ui/SearxngSourceSettings.kt")
        assertTrue(ui.contains("remember(controller, canAct) { CapturedPrivateActions(false, { true }, canAct) }"))
        assertTrue(ui.contains("DisposableEffect(actions) { onDispose { actions.close(); onDialogChanged(false) } }"))
        assertEquals(3, Regex("remember\\(actions\\) \\{ mutableStateOf<SearxngConfigurationClaim\\?>").findAll(ui).count())
        assertTrue(ui.contains("actions.isCurrent() && edit === claim && controller.isCurrent(claim)"))
        assertTrue(ui.contains("actions.isCurrent() && reset === claim && controller.isCurrent(claim)"))
        assertTrue(ui.contains("if (resetCurrent(claim) && controller.reset(claim))"))
        assertTrue(ui.contains("if (isCurrent() && controller.replace(claim, url, enabled, acknowledged, format))"))
        assertTrue(ui.contains("if (deletionCurrent(claim) && controller.remove(claim))"))
        assertFalse(ui.contains("controller.replace(claim, \"\""))
        assertTrue(ui.contains("if (edit === claim) { edit = null; onDialogChanged(false) }"))
        assertTrue(ui.contains("if (reset === claim) { reset = null; onDialogChanged(false) }"))
        assertTrue(ui.contains("actions.isCurrent() && deletion === claim && controller.isCurrent(claim)"))
        assertTrue(ui.contains("claim.kind == SearxngConfigurationAction.DELETE"))
        assertTrue(ui.contains("if (deletion === claim) { deletion = null; deletionHost = \"\"; onDialogChanged(false) }"))
        assertFalse(ui.contains("invalidateClaims()")) // UI dismissal does not cancel accepted shared writes.
        assertFalse(ui.contains("controller.close()"))
    }
    @Test fun inputAndCheckboxCallbacksUseLiveChecksAndRootActionsCannotReplaceAnOpenPrompt() {
        val ui = source("ui/SearxngSourceSettings.kt")
        assertTrue(ui.contains("fun idle() = actions.isCurrent() && canStart() && edit == null && reset == null && deletion == null"))
        assertTrue(ui.contains("if (idle()) controller.beginEdit(slot.id)?.let"))
        assertTrue(ui.contains("if (idle()) controller.beginAdd()?.let"))
        assertTrue(ui.contains("if (idle()) controller.beginDelete(slot.id)?.let"))
        assertTrue(ui.contains("if (idle()) controller.beginReset()?.let"))
        assertTrue(ui.contains("if (idle()) controller.reload()"))
        assertTrue(ui.contains("if (isCurrent() && text.length <= SearxngConfigurationPolicy.MAX_URL_BYTES"))
        assertTrue(ui.contains("if (isCurrent()) { enabled = it; acknowledged = false }"))
        assertTrue(ui.contains("if (isCurrent()) acknowledged = it"))
    }
    @Test fun recoveryDialogUsesAnOccurrenceIdentityAndChecksLatestPublishedState() {
        val ui = source("ui/ProviderManagementScreen.kt")
        assertTrue(ui.contains("confirmation = ProviderManagementConfirmation(operation, it)"))
        assertTrue(ui.contains("confirmation === prompt && latestState.active"))
        assertTrue(ui.contains("!latestState.busy && latestState.snapshot == prompt.snapshot"))
        assertTrue(ui.contains("if (current() && allowed)"))
        assertEquals(2, Regex("if \\(confirmation === prompt\\) confirmation = null").findAll(ui).count())
    }
    @Test fun theTwoSourceSectionsShareSynchronousModalAdmissionWithoutBlockingTheirOwnDialog() {
        val provider = source("ui/ProviderManagementScreen.kt")
        val editor = source("ui/SearxngSourceSettings.kt")
        assertTrue(provider.contains("var searxngDialogOpen by remember(actions)"))
        assertTrue(provider.contains("val sourceCanStart = remember(actions)"))
        assertTrue(provider.contains("fun idle() = actions.isCurrent() && confirmation == null && !searxngDialogOpen"))
        assertTrue(provider.contains("canStart = sourceCanStart, onDialogChanged = sourceDialogChanged"))
        assertTrue(provider.contains("LaunchedEffect(actions, state.active, confirmation, searxngDialogOpen)"))
        assertTrue(editor.contains("edit = claim; onDialogChanged(true)"))
        assertTrue(editor.contains("reset = claim; onDialogChanged(true)"))
        assertTrue(editor.contains("deletion = claim; onDialogChanged(true)"))
        assertTrue(editor.contains("deletion?.takeIf { !deletionCurrent(it) }?.let(::dismissDeletion)"))
        assertTrue(editor.contains("edit?.takeIf { !editCurrent(it) }?.let(::dismissEdit)"))
        assertTrue(editor.contains("reset?.takeIf { !resetCurrent(it) }?.let(::dismissReset)"))
        assertTrue(editor.contains("controller.reset(claim)) dismissReset(claim)"))
        val editGate = editor.substringAfter("fun editCurrent(").substringBefore("fun resetCurrent(")
        val resetGate = editor.substringAfter("fun resetCurrent(").substringBefore("fun dismissEdit(")
        assertFalse(editGate.contains("canStart()")); assertFalse(resetGate.contains("canStart()"))
    }
    @Test fun changedHeadingsHaveMatchingLanguageKeysAndNoExclusiveCommonsClaim() {
        fun resources(locale: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$locale/provider_dialog.xml"))
                .getElementsByTagName("string")
            return (0 until nodes.length).associate { index -> nodes.item(index).let { it.attributes.getNamedItem("name").nodeValue to it.textContent } }
        }
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(en.keys, zh.keys); assertEquals(59, en.size)
        assertEquals("Sources", en.getValue("provider_dialog_0")); assertEquals("来源管理", zh.getValue("provider_dialog_0"))
        assertFalse(en.getValue("provider_dialog_2").contains("only")); assertFalse(zh.getValue("provider_dialog_2").contains("唯一"))
        val formats = Regex("%(?:[0-9]+\\$)?[dsf]")
        en.keys.forEach { key -> assertEquals(key, formats.findAll(en.getValue(key)).map { it.value }.toList(),
            formats.findAll(zh.getValue(key)).map { it.value }.toList()) }
    }
    @Test fun everyChangedProductionSourceReadByContractsIsAGradleTestInput() {
        val build = File("build.gradle.kts").readText()
        for (name in listOf("MainActivity.kt", "ui/ProviderManagementScreen.kt", "ui/SearxngSourceSettings.kt"))
            assertTrue(name, build.contains("\"src/main/kotlin/com/easysavevideo/app/$name\""))
    }
}
