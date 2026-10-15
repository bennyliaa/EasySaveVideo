package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Production UI callbacks and supplementary source/resources; not Android input/capture evidence. */
class PrivateRecordingPresentationTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(name: String) = File(root, "src/main/kotlin/com/easysavevideo/app/ui/$name.kt").readText()
    private val running = setOf(PrivateRecordingStage.ARMING, PrivateRecordingStage.RECORDING,
        PrivateRecordingStage.STOPPING, PrivateRecordingStage.VERIFYING, PrivateRecordingStage.COMMITTING)
    @Test fun backNeverRequestsStopOrDiscardOfSubmittedRecording() {
        for (stage in PrivateRecordingStage.entries) {
            var close = 0; var stop = 0; var discard = 0; var start = 0
            PrivateRecordingPresentationUi(1L, stage, PrivateRecordingAudioChoice.SILENT, false, false,
                { start++ }, { start++ }, { stop++ }, { discard++ }, { close++ }).handleBack()
            assertEquals(stage.name, 0, stop); assertEquals(stage.name, 0, discard); assertEquals(0, start)
            assertEquals(stage.name, if (stage in running) 0 else 1, close)
        }
    }
    @Test fun discardDefaultsRemainAvailableThroughVerificationAndCommitButNotPublication() {
        for (stage in PrivateRecordingStage.entries) {
            val ui = PrivateRecordingPresentationUi(1L, stage, PrivateRecordingAudioChoice.SILENT,
                false, false, {}, {}, {}, {}, {})
            assertEquals(stage.name, stage in running, ui.canDiscard)
        }
        val alreadyPublished = PrivateRecordingPresentationUi(1L, PrivateRecordingStage.COMMITTING,
            PrivateRecordingAudioChoice.SILENT, false, false, {}, {}, {}, {}, {}, canDiscard = false)
        assertFalse(alreadyPublished.canDiscard)
    }
    @Test fun controlsAreAppShellReusableAndDoNotDependOnPrivateMode() {
        val browser = source("BrowserScreen"); val controls = source("RecordingSessionControls")
        assertFalse(browser.contains("recordingPresentation")); assertFalse(browser.contains("privateRecording"))
        assertTrue(controls.contains("fun RecordingSessionControls(")); assertFalse(controls.contains("privateMode"))
        assertEquals(2, Regex("AndroidView\\(factory").findAll(browser).count())
        assertEquals(2, Regex("browser.beforePrivateViewAttachment\\(view\\)").findAll(browser).count())
    }
    @Test fun normalNavigationDialogsFullscreenAndCurrentInputKeepTheirOriginalOwners() {
        val browser = source("BrowserScreen")
        for (part in listOf("if (interactiveJsDialogs) browser.jsDialog", "filePrompt?.let", "browser.fullScreenView?.let",
            "if (showEmpty)", "inputAllowed()", "canNavigateBrowserAddress(", "browser.beforePrivateViewAttachment(view)"))
            assertTrue(part, browser.contains(part))
        assertFalse(browser.contains("onStopSave")); assertFalse(browser.contains("handleBack()"))
    }
    @Test fun explicitContinueStopAndDiscardDoNotUseEffectsToStartOrStop() {
        val controls = source("RecordingSessionControls")
        assertFalse(controls.contains("LaunchedEffect")); assertFalse(controls.contains("DisposableEffect"))
        for (part in listOf("enabled = ui.canStart", "enabled = ui.canContinue", "onClick = ui.onContinue",
            "onClick = ui.onStopSave", "onClick = ui.onDiscard", "if (ui.canDiscard)",
            "remember(ui.presentationId) { mutableStateOf(false) }", "PrivateRecordingStage.VERIFYING",
            "PrivateRecordingStage.COMMITTING", "PrivateRecordingStage.SUCCEEDED")) assertTrue(part, controls.contains(part))
        assertFalse(source("PrivateRecordingPresentationUi").contains("android."))
    }
    @Test fun pairedResourcesExplainContinuousScopeLegacyRiskAndUnpublishedDiscard() {
        fun resources(locale: String): Map<String,String> {
            val f = DocumentBuilderFactory.newInstance()
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = f.newDocumentBuilder().parse(File(root, "src/main/res/$locale/private_recording.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(en.keys, zh.keys)
        assertTrue(en.getValue("private_record_disclosure").contains("scope you choose in Android"))
        assertTrue(en.getValue("private_record_disclosure").contains("other capture apps"))
        assertTrue(en.getValue("private_record_disclosure").contains("do not stop recording"))
        assertTrue(en.getValue("private_record_audio_help").contains("Microphone audio is not enabled"))
        assertTrue(en.getValue("private_record_discard_help").contains("Previously saved files remain"))
        assertTrue(en.getValue("private_record_legacy_recents").contains("cannot guarantee deletion"))
        assertTrue(en.getValue("private_record_limits").contains("2 hours and 2 GiB"))
        assertTrue(en.getValue("private_record_saved").contains("can no longer be discarded"))
    }
    @Test fun uncertainSubmittedOwnerCannotDismissRecoveryOnBack() {
        var close = 0; var stop = 0; var discard = 0
        val ui = PrivateRecordingPresentationUi(-1L, PrivateRecordingStage.BLOCKED,
            PrivateRecordingAudioChoice.SILENT, false, false, {}, {}, { stop++ }, { discard++ },
            { close++ }, canDiscard = false)
        assertTrue(ui.recoveryBlocked); ui.handleBack()
        assertEquals(0, close); assertEquals(0, stop); assertEquals(0, discard)
        val controls = source("RecordingSessionControls")
        assertTrue(controls.contains("!ui.recoveryBlocked"))
        assertTrue(controls.contains("R.string.private_record_recovery_required"))
    }

}
