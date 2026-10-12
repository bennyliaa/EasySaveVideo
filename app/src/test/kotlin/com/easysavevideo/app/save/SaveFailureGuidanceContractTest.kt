package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source/translation integration contracts. These are not WebView, device or rendering tests. */
class SaveFailureGuidanceContractTest {
    private fun root() = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private fun source(path: String) = File(root(), "src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun resources(folder: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root(), "src/main/res/$folder/save_failure_guidance.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun everyAdviceAndCommandResultHasPairedNonemptyCopy() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(28, en.size); assertEquals(en.keys, zh.keys)
        en.keys.forEach { assertTrue(en.getValue(it).isNotBlank()); assertTrue(zh.getValue(it).isNotBlank()) }
        assertEquals(SaveFailureAdvice.entries.size, SaveFailureAdvice.entries.map(::saveFailureAdviceResource).toSet().size)
        assertEquals(FailureNavigationResult.entries.size, FailureNavigationResult.entries.map(::failureNavigationResultResource).toSet().size)
    }
    @Test fun translatedGuidanceKeepsCriticalNoBypassAndNoSuccessBoundaries() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertTrue(en.getValue("save_failure_protected").contains("Do not switch methods"))
        assertTrue(zh.getValue("save_failure_protected").contains("不要换用其他方式"))
        assertTrue(en.getValue("save_failure_authentication").contains("Do not use another method"))
        assertTrue(zh.getValue("save_failure_authentication").contains("不要换用其他方式"))
        assertTrue(en.getValue("save_failure_fresh_boundary").contains("failed task unchanged"))
        assertTrue(zh.getValue("save_failure_fresh_boundary").contains("不会改变失败任务"))
        assertTrue(en.getValue("save_failure_invalid_output").contains("not a saved video"))
        assertTrue(zh.getValue("save_failure_invalid_output").contains("不是已保存视频"))
        assertTrue(en.getValue("save_failure_record_consent").contains("fresh consent"))
        assertTrue(zh.getValue("save_failure_record_consent").contains("重新授权"))
    }
    @Test fun nativeInspectionStillUsesCurrentPageSnapshotAndCandidateLeaseChecks() {
        val runtime = source("save/AndroidSaveRuntime.kt")
        assertTrue(runtime.contains("captured.matches(inspectionPage(browser), foreground)"))
        assertTrue(runtime.contains("browser.saveInspectionRevision"))
        assertTrue(runtime.contains("captured.candidates.none { it === candidate }"))
        assertTrue(runtime.contains("!captured.isCurrent()"))
        assertTrue(runtime.contains("fun chooseHlsVariant"))
        val detector = source("save/PageSaveDetector.kt")
        assertTrue(detector.contains("if (!stillCurrent() || view.url != pageUrl)"))
        assertTrue(detector.contains("token != generation || delivered"))
        assertTrue(detector.contains("System.currentTimeMillis() + 60_000"))
    }
    @Test fun navigationBridgeHasNoExecutionOrOldSourceAuthorityAndKeepsTaskGates() {
        val block = source("save/AndroidSaveRuntime.kt").substringAfter("internal fun returnToBrowserAfterFailure")
            .substringBefore("fun dismissFailureBrowserReminder")
        for (forbidden in listOf(".submit(", ".cancel(", ".resume(", "inspectFromUserClick(", "prepareRecording(", "sourceForTask(",
            "leaseFor(", ".resource", "storage.", "worker.execute", "coordinator.record(")) assertFalse(forbidden, block.contains(forbidden))
        for (required in listOf("FailedTaskIdentity::from", "command.accept", "ready && foreground", "!resourcesDraining()",
            "pendingCommands.isEmpty()", "!executing.get()", "pendingRecording == null", "hlsPreparation == null", "dismissSelection()"))
            assertTrue(required, block.contains(required))
        assertTrue(block.indexOf("FailureNavigationResult.ACCEPTED") < block.indexOf("dismissSelection()"))
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("onBrowser = { navigate(EsvDestination.BROWSER) }"))
    }
    @Test fun uiCannotStartRecordingOrSaveAndNavigationDoesNotPretendToInspect() {
        val ui = source("save/SaveFailureGuidanceUi.kt")
        for (forbidden in listOf("onRecord", "prepareRecording", "inspectFromUserClick", "runtime.choose", "runtime.resume", "runtime.cancel"))
            assertFalse(ui.contains(forbidden))
        assertTrue(ui.contains("if (result == FailureNavigationResult.ACCEPTED) onBrowser()"))
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertTrue(en.getValue("save_failure_browser_hint").contains("Inspect and choose"))
        assertTrue(zh.getValue("save_failure_browser_hint").contains("识别并选择"))
    }
}
