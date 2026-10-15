package com.easysavevideo.app.browser.history

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static native wiring checks only. Actual state-machine behavior is tested separately;
 * Android filesystem faults, Activity rotation and WebView callbacks still need device tests. */
class HistoryRuntimeWiringContractTest {
    private fun source(path: String): String {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    }
    @Test fun processOwnerUsesOneExecutorAndApplicationContextWithoutActivityClose() {
        val runtime = source("browser/history/AndroidHistoryRuntime.kt")
        assertTrue(runtime.contains("private constructor(context: Context)"))
        assertEquals(1, Regex("Executors.newSingleThreadExecutor\\(\\)").findAll(runtime).count())
        assertTrue(runtime.contains("@Volatile private var instance: AndroidHistoryRuntime? = null"))
        assertTrue(runtime.contains("AndroidHistoryRuntime(context.applicationContext).also { instance = it }"))
        assertTrue(runtime.contains("check(Looper.myLooper() == Looper.getMainLooper())"))
        assertTrue(runtime.contains("check(main.post { result(); watch() })"))
        assertFalse(runtime.contains("controller.close()")); assertFalse(runtime.contains("worker.shutdown"))
    }
    @Test fun approvedRetentionAndSlowMarkerUseReviewedController() {
        val runtime = source("browser/history/AndroidHistoryRuntime.kt")
        for (required in listOf("HistoryController(AndroidHistoryStorage(context), navigation",
            "HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS)", "init { load() }",
            "controller.operationGeneration()", "controller.slow(operation)", "main.postDelayed(it, 3_000)",
            "controller.row(item)", "controller.addressForOpen(token)", "controller.requestRemove(token)",
            "controller.requestClear()", "controller.cancel(confirmation)", "controller.confirm(confirmation)",
            "controller.record(visit)")) assertTrue(required, runtime.contains(required))
        assertFalse(runtime.contains("AlarmManager")); assertFalse(runtime.contains("WorkManager"))
    }
    @Test fun hostStartsAndFinishesRecordingOnlyAtRealAcceptedCallbackBoundary() {
        val host = source("browser/SystemBrowserHost.kt")
        assertTrue(host.contains("private val ordinaryHistory: AndroidHistoryRuntime? = null"))
        assertTrue(host.contains("private fun entryLive(entry: Entry): Boolean = !closed && !closing"))
        assertTrue(host.contains("private fun isSelected(entry: Entry): Boolean = !privacyBlocked && entry.owner === controller"))
        val start = host.substringAfter("override fun onPageStarted").substringBefore("override fun onPageFinished")
        for (required in listOf("view !== entry.view", "entry.admission?.onStarted(address.value) != true",
            "val acceptedStart = owner.acceptPageStarted", "entry.historyRecorder?.started(request",
            "acceptedStart = acceptedStart", "currentEntry = current(entry) === request && !entry.suppressed",
            "active = entryLive(entry) && isSelected(entry) && foreground && browserSurfaceAllowed()")) assertTrue(required, start.contains(required))
        val finish = host.substringAfter("override fun onPageFinished").substringBefore("override fun doUpdateVisitedHistory")
        for (required in listOf("view !== entry.view", "verifiedMainAddress(entry, url)",
            "entry.admission?.accepts(address.value) != true", "val acceptedFinish = owner.acceptPageFinished",
            "if (acceptedFinish)", "entry.historyRecorder?.finished(request", "view, view.url, address.value",
            "currentEntry = current(entry) === request && !entry.suppressed", "acceptedFinish = true",
            "active = entryLive(entry) && isSelected(entry) && foreground && browserSurfaceAllowed()")) assertTrue(required, finish.contains(required))
        assertTrue(finish.indexOf("owner.acceptPageFinished") < finish.indexOf("entry.historyRecorder?.finished"))
    }
    @Test fun restoredStateAndHistoryApiCannotArmOrProduceVisits() {
        val host = source("browser/SystemBrowserHost.kt")
        val ensure = host.substringAfter("private fun ensureEntry").substringBefore("private fun trimViews")
        assertTrue(ensure.contains("ordinaryHistory?.recorder(ref, view)")); assertTrue(ensure.contains("view.restoreState(it)"))
        assertFalse(ensure.contains("controlledNavigationRequested"))
        assertTrue(ensure.contains("if (session != null) null else ordinaryHistory?.recorder(ref, view)"))
        assertTrue(ensure.contains("if (session == null) history.remove(ref.tabId)?.let { view.restoreState(it) }"))
        // Shared moveHistory replaces separate back/forward arming sites; reload routes via loadOwned.
        val move = host.substringAfter("private fun moveHistory(").substringBefore("fun reload()")
        assertTrue(move.contains("if (delta < 0) entry.view.goBack() else entry.view.goForward()"))
        assertTrue(move.contains("entry.historyRecorder?.controlledNavigationRequested()"))
        assertTrue(move.indexOf("historyRecorder?.controlledNavigationRequested()") < move.indexOf("entry.view.goBack()"))
        assertTrue(host.substringAfter("fun reload()").substringBefore("fun stopSelected()").contains("loadOwned(request,"))
        assertTrue(host.contains("private fun canAct(): Boolean = !closed && !closing"))
        val history = host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError")
        assertFalse(history.contains("historyRecorder"))
        assertEquals(2, Regex("historyRecorder\\?\\.controlledNavigationRequested\\(\\)").findAll(host).count())
    }
    @Test fun hostDetachPauseAndStopInvalidatePendingEntryPermitButNotProcessOwner() {
        val host = source("browser/SystemBrowserHost.kt")
        for ((start, end) in listOf("fun pause()" to "fun hideFullscreen()", "private fun leaveCurrentTab()" to "private fun touched()",
            "override fun stop(" to "fun selectedView()", "override fun destroy(" to "private fun releaseView(",
            "override fun close()" to "unmatched end marker")) {
            val block = host.substringAfter(start).substringBefore(end)
            assertTrue(start, block.contains("historyRecorder?.cancel()"))
        }
        assertFalse(host.contains("ordinaryHistory?.close()"))
        assertTrue(host.substringAfter("fun resume()").substringBefore("fun pause()").contains("ordinaryHistory?.load()"))
    }
    @Test fun historyOpenRequiresOrdinaryForegroundCommandAndActualAcceptedNativeEntry() {
        val host = source("browser/SystemBrowserHost.kt")
        val open = host.substringAfter("fun openOrdinaryHistory(").substringBefore("fun bookmarkPage()")
        for (required in listOf("requireMain()", "!canAct() || privateMode || !foreground || !browserCommandAllowed()", "!browserSurfaceAllowed()",
            "state.selectedTab?.incognito != false", "BrowserInputPolicy.address(address)?.value != address",
            "navigate(address)", "BrowserLoadState.LOADING", "current(entry) != null")) assertTrue(required, open.contains(required))
        assertFalse(open.contains("openTab(")); assertFalse(open.contains("selectTab("))
    }
}
