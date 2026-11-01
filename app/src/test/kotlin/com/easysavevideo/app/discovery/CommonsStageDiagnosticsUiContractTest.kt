package com.easysavevideo.app.discovery

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts supplement, never substitute for Android build or device execution. */
class CommonsStageDiagnosticsUiContractTest {
    private val controller = File("src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt").readText()
    private val activity = File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
    private val panel = File("src/debug/kotlin/com/easysavevideo/app/ui/CommonsStageDiagnosticsPanel.kt").readText()
    private fun method(start: String, end: String) = controller.substringAfter(start).substringBefore(end)

    @Test fun debugRuntimeGuardAndEmptyReleaseEntryAreIndependent() {
        assertTrue(controller.contains("DebugCommonsStageDiagnosticsOwner(BuildConfig.DEBUG)"))
        assertTrue(panel.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(activity.contains("if (BuildConfig.DEBUG) com.easysavevideo.app.ui.CommonsStageDiagnosticsPanel(discovery)"))
        val release = File("src/release/kotlin/com/easysavevideo/app/ui/CommonsStageDiagnosticsPanel.kt").readText()
        assertTrue(release.contains("AndroidDiscoveryController) { }"))
        for (token in listOf("EsvButton", "EsvText", "startCommons", "R.string", "setEnabled")) assertFalse(token, release.contains(token))
        assertFalse(File("src/main/res/values/commons_stage_diagnostics.xml").exists())
        assertFalse(File("src/release/res/values/commons_stage_diagnostics.xml").exists())
    }

    @Test fun startStopAndPreviewHaveNoSearchOrIoSideEffects() {
        val actions = method("internal fun startCommonsStageDiagnostics", "private fun captureCommonsStageDiagnosticsAuthorization")
        val executable = actions.lineSequence().filterNot {
            it.trimStart().startsWith("/") || it.trimStart().startsWith("*")
        }.joinToString("\n")
        for (forbidden in listOf("submit {", "refresh()", ".start(SESSION", "requestMore", "store.", "initialize("))
            assertFalse(forbidden, executable.contains(forbidden))
        assertEquals(3, Regex("!BuildConfig.DEBUG").findAll(actions).count())
        assertTrue(actions.contains("expectedRevision != value.revision"))
        assertTrue(actions.contains("lifecycle != pageLifecycle.get()"))
        assertTrue(actions.contains("target != state.controlTarget"))
        assertTrue(actions.contains("filterControlsBusy"))
    }

    @Test fun exactCapabilityIsCapturedBeforeBothExplicitSearchesAreQueued() {
        for (body in listOf(method("fun submitFilterDraft", "private fun configurationCurrent"),
            method("fun search(query:", "fun updateSort"))) {
            val capture = body.indexOf("val diagnosticsCapture = captureCommonsStageDiagnosticsAuthorization()")
            assertTrue(capture >= 0)
            assertTrue(capture < body.indexOf("submit {", capture))
            assertTrue(body.contains("diagnosticsCapture = diagnosticsCapture"))
        }
        val initialization = method("private fun initialize", "/** The write intent")
        assertTrue(initialization.contains("commonsStageDiagnostics = commonsStageDiagnosticsOwner.recorder"))
        assertFalse(initialization.contains("diagnosticsCapture ="))
        assertTrue(initialization.contains("revokeCommonsStageDiagnostics()"))
    }

    @Test fun backgroundSourceNavigationCloseAndPanelDisposalSealSynchronously() {
        for (body in listOf(method("private fun fenceSourceConfiguration", "private fun loadedSourceConfiguration"),
            method("fun setVisibility", "/** Save/Record"), method("override fun close()", "private companion object"))) {
            assertTrue(body.contains("revokeCommonsStageDiagnostics()"))
        }
        assertTrue(controller.contains("value.revision >= commonsStageDiagnosticsPreview.revision"))
        assertTrue(controller.contains("if (Looper.myLooper() == Looper.getMainLooper()) publish() else main.post(publish)"))
        assertTrue(panel.contains("onDispose { controller.stopCommonsStageDiagnostics(currentRevision) }"))
        assertTrue(activity.contains("discovery.setVisibility(isForeground, false)"))
        assertTrue(activity.contains("SaveEvaluationPanel(saveEvaluation"))
    }

    @Test fun previewRendersOnlyClosedTechnicalFieldsIncludingActualSafeHttpStatus() {
        for (required in listOf("sample.stage.name", "sample.outcome.name", "sample.elapsedNanos", "sample.timing.name",
            "sample.failure?.name", "sample.httpStatus?.toString()", "snapshot.activeStages.size",
            "snapshot.droppedSamples", "snapshot.ignoredEvents", "snapshot.clockAnomalies")) assertTrue(required, panel.contains(required))
        for (forbidden in listOf(".query", ".url", ".title", ".providerId", "Throwable", "Log.", "onExport", "File("))
            assertFalse(forbidden, panel.contains(forbidden))
    }

    @Test fun debugLocalizedResourcesMatchAndExplainPartialObservations() {
        fun strings(locale: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                File("src/debug/res/$locale/commons_stage_diagnostics.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                node.getAttribute("name") to node.textContent
            }
        }
        val english = strings("values")
        val chinese = strings("values-zh-rCN")
        assertEquals(english.keys, chinese.keys)
        for (key in english.keys) {
            val formats = Regex("%[0-9]+\\$[sd]")
            assertEquals(key, formats.findAll(english.getValue(key)).map { it.value }.toList(),
                formats.findAll(chinese.getValue(key)).map { it.value }.toList())
        }
        assertTrue(english.getValue("commons_stage_row").contains("HTTP=%9\$s"))
        assertTrue(english.getValue("commons_stage_not_observed").contains("retained trace"))
        assertTrue(english.getValue("commons_stage_scope").contains("denial or slowdown may stop at headers"))
        assertTrue(english.getValue("commons_stage_scope").contains("Dropped samples can hide a phase"))
        assertTrue(english.getValue("commons_stage_scope").contains("OPERATION is the runtime callback outcome"))
        assertTrue(chinese.getValue("commons_stage_scope").contains("不代表候选已入池或显示"))
        val checklist = File("../docs/discovery/COMMONS_STAGE_DEBUG_UI_20261019.md").readText()
        assertTrue(checklist.contains("NOT EXECUTED"))
        assertTrue(checklist.contains("queued"))
    }
}
