package com.easysavevideo.app.save

import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SaveAuthenticationWiringTest {
    private fun source(path: String) = String(Files.readAllBytes(Paths.get("src/main/kotlin/com/easysavevideo/app", path)), Charsets.UTF_8)
    @Test fun runtimeReadsUniqueCurrentRecordAndCoordinatorWithoutTaskCommands() {
        val runtime = source("save/AndroidSaveRuntime.kt")
        val bridge = runtime.substringAfter("internal fun returnToBrowserForAuthentication(").substringBefore("internal fun returnToBrowserAfterFailure(")
        assertTrue(bridge.contains("requireMain()"));assertTrue(bridge.contains(".singleOrNull()"))
        assertTrue(bridge.contains("authenticationSnapshotCompatible(it.task, coordinator.snapshot(command.taskId))"))
        assertTrue(bridge.contains("command.accept(current, authenticationNavigationGeneration, authenticationBrowserAvailable())"))
        for (forbidden in listOf("sourceForTask(", "navigate(", "resume(", "cancel(", "submit(", "dismissSelection(", "leaseFor(", "showFailureBrowserReminder"))
            assertFalse(forbidden, bridge.contains(forbidden))
        assertTrue(runtime.contains("if (foreground != value) authenticationNavigationGeneration++"))
    }
    @Test fun runtimeCurrentAvailabilityCoversActualPendingAndNativeOwners() {
        val gate = source("save/AndroidSaveRuntime.kt").substringAfter("internal fun authenticationBrowserAvailable()").substringBefore("/** Navigation authority")
        for (required in listOf("ready && foreground && allowed()", "!executing.get()", "!resourcesDraining()",
            "pendingCommands.isEmpty()", "pendingMediaCommands.get() == 0", "!expiryActivity.active", "!initializationActivity.active",
            "!scanning", "selection == null", "currentRecordingId == null", "pendingRecording == null", "recordingService == null",
            "!preparingStream", "hlsPreparation == null", "destinations?.asking != true", "destinations?.notice == null",
            "it.busy || it.recoveryActive || it.nativeOccupied || it.previewActive || it.requiresRecovery"))
            assertTrue(required, gate.contains(required))
    }
    @Test fun actualTaskScreenAndMainRouteGateAreConnected() {
        val screen = source("save/SaveTaskScreen.kt")
        assertTrue(screen.contains("canOpenBrowserForAuthentication: () -> Boolean = { false }"))
        assertTrue(screen.contains("SaveAuthenticationGuidance(record, runtime, onBrowser, canOpenBrowserForAuthentication)"))
        assertEquals(2, Regex("onPreview, onBrowser, canOpenBrowserForAuthentication\\)").findAll(screen).count())
        val gate = source("MainActivity.kt").substringAfter("canOpenBrowserForAuthentication = {").substringBefore("onBrowser =")
        for (required in listOf("isForeground", "hasFocusedWindow", "!isDestroyed", "!isFinishing",
            "navController?.currentDestination?.route == EsvDestination.TASKS.route", "saves.selection == null",
            "saves.destinations?.notice == null", "previewReceipt == null", "libraryPreviewReference == null", "!browser.hasPendingFileChoice"))
            assertTrue(required, gate.contains(required))
    }
    @Test fun onlyAcceptedExplicitClickCallsCurrentNavigationAndDisposedCommandIsRetired() {
        val ui = source("save/SaveAuthenticationGuidance.kt")
        assertTrue(ui.contains("remember(identity, generation)"))
        assertTrue(ui.contains("AuthenticationBrowserCommand(identity, generation)"))
        assertTrue(ui.contains("onDispose { command.retire() }"))
        assertTrue(ui.contains("val mediaIdle = !runtime.mediaTaskActivity.active"))
        assertTrue(ui.contains("mediaIdle && currentCanOpen() && runtime.authenticationBrowserAvailable()"))
        assertTrue(ui.contains("rememberUpdatedState(canOpenBrowser)"));assertTrue(ui.contains("rememberUpdatedState(onBrowser)"))
        assertTrue(ui.contains("if (open == null || !currentCanOpen())"))
        assertTrue(ui.contains("if (result == AuthenticationNavigationResult.ACCEPTED) open?.invoke()"))
        for (forbidden in listOf("resume(", "cancel(", "sourceForTask(", "navigate(", "LaunchedEffect")) assertFalse(ui.contains(forbidden))
    }
    @Test fun pairedCurrentCompositionResourcesDoNotPromiseOriginOrAutomaticResume() {
        fun entries(folder: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Paths.get("src/main/res", folder, "save_authentication_navigation.xml").toFile())
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i -> nodes.item(i).let { it.attributes.getNamedItem("name").nodeValue to it.textContent } }
        }
        val en = entries("values");val zh = entries("values-zh-rCN")
        assertEquals(6, en.size);assertEquals(en.keys, zh.keys)
        (en.values + zh.values).forEach { assertTrue(it.isNotBlank()) }
        assertTrue(en.getValue("save_auth_browser_boundary").contains("No page is opened automatically and no task is resumed"))
        assertTrue(zh.getValue("save_auth_browser_boundary").contains("不会自动打开网页或继续任务"))
        assertEquals(AuthenticationNavigationResult.entries.size, AuthenticationNavigationResult.entries.map(::authenticationNavigationText).toSet().size)
    }
}
