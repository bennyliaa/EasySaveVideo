package com.easysavevideo.app.save

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM source contracts: these verify wiring and branch timing, not Android execution. */
class SaveRuntimeNoticeWiringTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val runtime get() = source("save/AndroidSaveRuntime.kt")

    @Test fun everyEmissionUsesOneOfTheFourTypedFamiliesAndAllFiveClearsRemain() {
        val text = runtime
        assertTrue(text.contains("internal var notice by mutableStateOf<SaveRuntimeNotice?>(null); private set"))
        assertEquals(49, Regex("\\bnotice = ").findAll(text).count())
        assertEquals(5, Regex("\\bnotice = null").findAll(text).count())
        assertEquals(37, Regex("notice = SaveRuntimeNotice.Fixed\\(").findAll(text).count())
        assertEquals(1, Regex("notice = SaveRuntimeNotice.TaskCapacityReached\\(").findAll(text).count())
        assertEquals(1, Regex("notice = SaveRuntimeNotice.DetectionUnavailable\\(").findAll(text).count())
        assertEquals(5, Regex("notice = saveRuntimeFailureNotice\\(").findAll(text).count())
        SaveRuntimeFixedNotice.entries.forEach { kind ->
            val count = Regex("SaveRuntimeFixedNotice\\.${kind.name}\\b").findAll(text).count()
            assertEquals(kind.name, if (kind in setOf(SaveRuntimeFixedNotice.MEDIA_RESOURCES_EXITING,
                SaveRuntimeFixedNotice.RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED)) 2 else 1, count)
        }
        assertTrue(text.contains("notice = SaveRuntimeNotice.TaskCapacityReached(limits.maxJournalEntries)"))
        assertFalse(text.contains("saveErrorText("))
    }
    @Test fun networkInitializationIsReadInsideTheOriginalPostedDelivery() {
        val body = runtime.substringAfter("private fun networkSaveAllowed(").substringBefore("fun choose(")
        assertTrue(body.contains("networkPolicy.checkAdmission(); true"))
        assertTrue(body.contains("catch (_: SaveFailure)"))
        assertTrue(body.contains("if (showNotice) main.post { notice = SaveRuntimeNotice.Fixed(if (networkPolicy.initialized)\n            SaveRuntimeFixedNotice.WIFI_REQUIRED else SaveRuntimeFixedNotice.WIFI_SETTINGS_UNAVAILABLE) }"))
        assertTrue(body.trimEnd().endsWith("false\n    }"))
        assertFalse(body.contains("getString("))
    }
    @Test fun classifiedErrorsKeepExistingPostedVersusImmediateBoundariesAndCleanup() {
        val text = runtime
        assertEquals(2, Regex("notice = saveRuntimeFailureNotice\\(result.error\\)").findAll(text).count())
        assertEquals(3, Regex("notice = saveRuntimeFailureNotice\\(SaveErrors.classify\\(failure\\)\\)").findAll(text).count())
        assertTrue(text.contains("main.post { notice = saveRuntimeFailureNotice(result.error) }"))
        assertTrue(text.contains("if (token == preparationGeneration)"))
        // Catch classification is kept at the immediate HLS-selection boundary, not moved into rendering.
        assertTrue(text.contains("setExecuting(false); dismissSelection(); notice = saveRuntimeFailureNotice(SaveErrors.classify(failure))"))
        assertFalse(source("save/SaveRuntimeNoticeResources.kt").contains("SaveErrors.classify"))
    }
    @Test fun threeActualDisplayEntrypointsResolveCurrentCompositionWithoutChangingRecordingDisclosure() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("EsvLocaleHost(settings.appearance.language)"))
        assertTrue(main.contains("saveNotice = saves.notice?.let { saveRuntimeNoticeText(it) },"))
        assertTrue(main.contains("notice = recordedEditorNotice ?: saves.notice?.let { saveRuntimeNoticeText(it) },"))
        assertEquals(2, Regex("saveRuntimeNoticeText\\(it\\)").findAll(main).count())
        assertTrue(source("save/SaveTaskScreen.kt").contains("EsvText(saveRuntimeNoticeText(text), color = v.colors.error)"))
        assertTrue(source("recording/RecordingScreen.kt").contains("notice?.let { EsvDiagnosticNotice(it) }"))
        val disclosure = source("theme/EsvDiagnosticNotice.kt")
        assertTrue(disclosure.contains("remember(original) { mutableStateOf(false) }"))
        assertTrue(disclosure.contains("if (expanded) EsvText(original"))
        assertTrue(source("ui/BrowserScreen.kt").contains("saveNotice: String?"))
    }
    @Test fun renderersHaveNoCommandsOrRetainedLocaleAndContractInputsIncludeEveryNewSource() {
        val renderer = source("save/SaveRuntimeNoticeResources.kt")
        for (forbidden in listOf("application", "getDefault(", "remember", "LaunchedEffect", "main.post", "checkAdmission",
            "submit(", "resume(", "cancel(", "navigate(", "SharedPreferences", "Throwable", "Uri")) assertFalse(forbidden, renderer.contains(forbidden))
        for (required in listOf("stringResource(", "pluralStringResource(", "saveIntegerText(notice.maxUnfinishedTasks.toLong())")) assertTrue(renderer.contains(required))
        val build = File("build.gradle.kts").readText()
        for (required in listOf("save_runtime_notice.xml", "save/SaveRuntimeNotice.kt", "save/SaveRuntimeNoticeResources.kt",
            "recording/RecordingScreen.kt", "theme/EsvDiagnosticNotice.kt")) assertTrue(required, build.contains(required))
    }
}
