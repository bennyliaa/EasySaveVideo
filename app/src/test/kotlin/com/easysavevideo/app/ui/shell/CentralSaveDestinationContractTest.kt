package com.easysavevideo.app.ui.shell

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Actual actions are covered by DestinationSettingsActionsTest; these check source/UI integration. */
class CentralSaveDestinationContractTest {
    private fun path(relative:String)=Path.of(relative).takeIf(Files::exists)?:Path.of("app",relative)
    private fun read(relative:String)=String(Files.readAllBytes(path(relative)),Charsets.UTF_8)
    @Test fun savingCategoryComposesTheExistingDestinationControlAfterNetworkPreference() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt")
            .substringAfter("SettingsCategory.SAVE -> {").substringBefore("SettingsCategory.EDITOR ->")
        assertTrue(s.contains("SettingsEdit.WifiOnly(it)"));assertTrue(s.contains("saveDestination()"))
        assertTrue(s.indexOf("ui_save_wifi_description")<s.indexOf("saveDestination()"))
    }
    @Test fun centralEntryHasNoNewPreferenceOwnerOrDuplicateNoticeDialog() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/shell/CentralSaveDestinationSettings.kt")
        assertTrue(s.contains("OrdinaryDestinationSettings(runtime, generation, canAct, showNotice = false)"))
        assertTrue(s.contains("if (runtime == null)"))
        for(bad in listOf("rememberSaveable","getSharedPreferences","requestFolder()","updatePreference(","Dialog(","setPreference("))assertFalse(s.contains(bad))
    }
    @Test fun oldBrowserCallShapeStillUsesTheSameProductionComponent() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt")
        val panel=s.substringAfter("@Composable private fun SettingsPanel(").substringBefore("@Composable private fun Toggle(")
        assertTrue(panel.contains("val admission = LocalPrivateInputAdmission.current"))
        assertTrue(panel.contains("destinations?.let { OrdinaryDestinationSettings(it, canAct = admission) }"))
        assertEquals(1,Regex("OrdinaryDestinationSettings\\(").findAll(s).count())
        val ui=read("src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt")
        assertTrue(ui.contains("actionScopeKey: Long = 0"));assertTrue(ui.contains("showNotice: Boolean = true"))
        assertTrue(ui.contains("if (showNotice) OrdinaryDestinationNotice(runtime, actions::dismiss)"))
    }
    @Test fun uiReadsActualSelectedPreferenceAndRetiresOnlyButtonAuthority() {
        val ui=read("src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt")
            .substringBefore("fun OrdinaryDestinationNotice")
        assertTrue(ui.contains("selected = runtime.preference == value"))
        assertTrue(ui.contains("onDispose { actions.close() }"))
        assertTrue(ui.contains("runtime.savingPreference"));assertTrue(ui.contains("settings_destination_saving"))
        for(bad in listOf("preference = value","onDispose { runtime.cancelFolderSelection", "rememberSaveable"))assertFalse(ui.contains(bad))
    }
    @Test fun everyButtonUsesTheViewOwnerAndExistingRuntimePort() {
        val ui=read("src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt").substringBefore("fun OrdinaryDestinationNotice")
        for(call in listOf("actions.select(value)","actions::choose","actions::forget","actions::cancel","actions::dismiss"))assertTrue(ui.contains(call))
        for(port in listOf("runtime::updatePreference","runtime::requestFolder","runtime::cancelFolderSelection","runtime::forgetFolder","runtime::clearNotice"))assertTrue(ui.contains(port))
    }
    @Test fun mainEntryRequiresCurrentForegroundSettingsAndDoesNotLaunchPickerOnEntry() {
        val s=read("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").substringAfter("saveDestination = {").substringBefore("entitlements = {")
        for(required in listOf("CentralSaveDestinationSettings(saves.destinations, generation", "generation == newTabInputGeneration", "isForeground", "EsvDestination.SETTINGS", "!isDestroyed && !isFinishing"))assertTrue(s.contains(required))
        for(bad in listOf("requestFolder(","updatePreference(","forgetFolder(","Intent(","launch("))assertFalse(s.contains(bad))
    }
    /** Static wiring contract only; actual Android SAF lifecycle still requires device validation. */
    @Test fun acceptedPickerTicketOutlivesSettingsViewAndCommitsThroughExistingStorage() {
        val s=read("src/main/kotlin/com/easysavevideo/app/library/destination/AndroidOrdinaryDestinationRuntime.kt")
        val launch=s.substringAfter("fun requestFolder()").substringBefore("private fun folderLaunchFailed")
        for(label in listOf("current", "related")) assertTrue(launch.contains("$label = { folderHost === host && folderTicket === ticket && choosingFolder }"))
        for(bad in listOf("generation", "isForeground", "canAct")) assertFalse(launch.contains(bad))
        val receive=s.substringAfter("private fun receiveFolder").substringBefore("fun cancelFolderSelection")
        val commit="folderGate.commit(ticket) { storage.destinations.setFolderPreference(id) }"
        assertTrue(receive.contains("if (!folderGate.receive(ticket)) return"))
        assertTrue(receive.contains(commit))
        val after=receive.substringAfter(commit)
        assertTrue(after.indexOf("storage.destinations.selection()") < after.indexOf("applySelection(saved)"))
        assertTrue(after.contains("main.post"))
        val ui=read("src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt")
        assertTrue(ui.contains("onDispose { actions.close() }"))
        assertFalse(ui.contains("onDispose { runtime."))
    }
    @Test fun localizedWaitingStatesArePairedAndDoNotClaimOptimisticSuccess() {
        fun parse(locale:String):Map<String,String> {
            val n=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path("src/main/res/$locale/settings_destination.xml").toFile()).getElementsByTagName("string")
            return (0 until n.length).associate{val x=n.item(it);x.attributes.getNamedItem("name").nodeValue to x.textContent}
        }
        val en=parse("values");val zh=parse("values-zh-rCN")
        assertEquals(3,en.size);assertEquals(en.keys,zh.keys)
        assertTrue(en.getValue("settings_destination_saving").contains("only after storage confirms"))
        for(k in en.keys){assertTrue(en.getValue(k).isNotBlank());assertTrue(zh.getValue(k).isNotBlank())}
    }
}
