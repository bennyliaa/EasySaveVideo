package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native wiring contracts; synthetic JVM checks do not prove callback ordering on a device. */
class SaveSameDocumentContractTest {
    private fun source(path: String): String {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    }
    @Test fun runtimeCapturesOnlyHostAcceptedPageAndRetainsCandidateFences() {
        val runtime = source("save/AndroidSaveRuntime.kt")
        val capture = runtime.substringAfter("private fun inspectionPage").substringBefore("fun inspectFromUserClick")
        assertTrue(capture.contains("browser.saveInspectionPage()")); assertFalse(capture.contains("tab.address"))
        assertTrue(runtime.contains("captured.matches(inspectionPage(browser), foreground)"))
        assertTrue(runtime.contains("captured.candidates.none { it === candidate }"))
        assertTrue(runtime.contains("foreground && selection === captured && captured.isCurrent()"))
        assertTrue(runtime.contains("selection !== captured || !captured.isCurrent()"))
    }
    @Test fun hostAcceptanceAndSnapshotKeepLifecycleAndRevisionGates() {
        val host = source("browser/SystemBrowserHost.kt")
        val capture = host.substringAfter("internal fun saveInspectionPage()").substringBefore("private val timeout")
        for (required in listOf("requireMain()", "state.selectedTab", "!canAct() || !foreground", "entry.context != tab.context",
            "entry.suppressed", "current(entry) == null", "entry.admission?.hasStarted() != true", "BrowserLoadState.READY",
            "entry.inspectionDocument.address", "entry.view.url", "saveInspectionRevision", "entry.inspectionDocument.revision")) assertTrue(required, capture.contains(required))
        val started = host.substringAfter("override fun onPageStarted").substringBefore("override fun onPageFinished")
        assertTrue(started.contains("invalidateDocumentAuthority(entry)"))
        val invalidation = host.substringAfter("private fun invalidateDocumentAuthority(entry: Entry)").substringBefore("internal fun captureSaveRequestContext")
        assertTrue(invalidation.contains("entry.inspectionDocument.started()"))
        val finished = host.substringAfter("override fun onPageFinished").substringBefore("override fun doUpdateVisitedHistory")
        assertTrue(finished.contains("val owner = entry.owner"))
        assertTrue(finished.indexOf("owner.acceptPageFinished") >= 0)
        assertTrue(finished.indexOf("owner.acceptPageFinished") < finished.indexOf("entry.inspectionDocument.finished"))
        val history = host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError")
        assertTrue(history.contains("!entryLive(entry)"));
        val live = host.substringAfter("private fun entryLive(entry: Entry)").substringBefore("private fun isSelected")
        assertTrue(live.contains("it.key == entry.context.tabId && it.value === entry"))
        assertTrue(live.contains("!closed && !closing"))
        assertTrue(host.substringAfter("private fun canAct()").substringBefore("\n").contains("!closed && !closing")); assertTrue(history.contains("entry.inspectionDocument.history(url, view.url, isReload"))
        assertTrue(history.contains("current(entry) != null && !entry.suppressed && entry.admission?.hasStarted() == true"))
        assertTrue(history.contains("tab?.loadState == BrowserLoadState.READY"))
    }
    @Test fun detectorExecutionResponseSelectionAndCancellationRemainFencedWithoutAutomaticFullScan() {
        val detector = source("save/PageSaveDetector.kt")
        assertTrue(detector.substringBefore("view.evaluateJavascript").contains("if (!stillCurrent() || view.url != pageUrl)"))
        assertTrue(detector.substringAfter("view.evaluateJavascript").contains("if (!stillCurrent() || view.url != pageUrl)"))
        for (required in listOf("generation++", "token != generation || delivered", "root.optString(\"page\") != pageUrl", "System.currentTimeMillis() + 60_000, stillCurrent"))
            assertTrue(required, detector.contains(required))
        assertFalse(source("browser/SystemBrowserHost.kt").contains("inspectFromUserClick("))
        assertFalse(source("browser/BrowserInspectionDocument.kt").contains("evaluateJavascript"))
    }
}
