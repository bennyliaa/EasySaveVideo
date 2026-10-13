package com.easysavevideo.app.ui

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Source wiring assertions; no Compose navigation, restoration or device rendering is executed. */
class DiscoveryReadingAnchorWiringTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    @Test fun actualGridUsesStableResultIdsAndOnlyTheNewRestorationAdapter() {
        val screen = source("ui/DiscoveryScreen.kt")
        assertTrue(screen.contains("val grid = rememberLazyGridState()"))
        assertEquals(1, Regex("PreserveDiscoveryReadingAnchor\\(").findAll(screen).count())
        assertTrue(screen.contains("pageKey = com.easysavevideo.app.discovery.DiscoveryReadingAnchorPolicy.key(page, state.profile.queryKey, state.demo)"))
        assertTrue(screen.contains("resultIds = state.results.map { it.resultId }"))
        assertTrue(screen.contains("items(state.results, key = { it.resultId })"))
        assertTrue(screen.contains("LazyVerticalGrid(GridCells.Fixed(columns), state = grid"))
        assertFalse(screen.contains("grid.scrollToItem(0)"))
        val adapter = screen.substringAfter("PreserveDiscoveryReadingAnchor(").substringBefore("LaunchedEffect(page?.stamp")
        assertTrue(adapter.contains("controller.state.let { current ->"))
        assertTrue(adapter.contains("key(current.page, current.profile.queryKey, current.demo)"))
        assertFalse(adapter.contains("filterDraft"))
        assertFalse(adapter.contains("state.profile.query,"))
    }
    @Test fun restorationUsesSaveableCodecAndRetiresOldClaimsBeforeAllowingCaptures() {
        val adapter = source("ui/DiscoveryReadingAnchor.kt")
        assertTrue(adapter.contains("rememberSaveable(stateSaver = discoveryReadingAnchorSaver)"))
        assertTrue(adapter.contains("DiscoveryReadingAnchorPolicy::encode"))
        assertTrue(adapter.contains("restore = DiscoveryReadingAnchorPolicy::decode"))
        assertTrue(adapter.contains("remember(pageKey, visible, resultIds, restoredFromStamp)"))
        assertTrue(adapter.contains("onDispose { claim?.retire() }"))
        assertTrue(adapter.contains("LaunchedEffect(claim)"))
        assertTrue(adapter.contains("latestVisible && claim.isCurrent(latestCurrentPageKey())"))
        assertEquals(2, Regex("if \\(!current\\(\\)\\) return@LaunchedEffect").findAll(adapter).count())
        val scroll = adapter.indexOf("grid.scrollToItem(position.index, position.scrollOffset)")
        val watch = adapter.indexOf("snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }")
        assertTrue(scroll > 0 && watch > scroll)
        assertTrue(adapter.substring(watch).contains("if (current())"))
        assertTrue(adapter.substring(watch).contains("capture(pageKey, resultIds, index, offset)?.let { anchor = it }"))
    }
    @Test fun hiddenAndBackgroundGridCannotCaptureOrRestoreThroughThisAdapter() {
        val screen = source("ui/DiscoveryScreen.kt")
        val call = screen.substringAfter("PreserveDiscoveryReadingAnchor(").substringBefore("LaunchedEffect(page?.stamp")
        for (gate in listOf("active", "bodyState == DiscoveryBodyState.CONTENT", "!showPending", "!optionsIsModal",
            "localControlConfirm == null", "safeDisableConfirm == null", "rightsDetails == null")) assertTrue(gate, call.contains(gate))
        val adapter = source("ui/DiscoveryReadingAnchor.kt")
        assertTrue(adapter.contains("pageKey?.takeIf { visible }?.let(::DiscoveryReadingRestorationClaim)"))
        assertTrue(adapter.contains("if (claim == null || pageKey == null) return@LaunchedEffect"))
        assertTrue(adapter.contains("rememberUpdatedState(visible)"))
        assertTrue(adapter.contains("rememberUpdatedState(currentPageKey)"))
    }
    @Test fun restorationHasNoSearchDemandHistoryStorageOrTimerAuthority() {
        val adapter = source("ui/DiscoveryReadingAnchor.kt")
        for (forbidden in listOf("AndroidDiscoveryController", "getNext", "movePage(", "recordSeen(", "submitFilterDraft(",
            "startSearch(", "saveViewport(", "SharedPreferences", "AndroidDiscoveryStore", "delay(", "Handler", "Executor",
            "LaunchedEffect(Unit)", "state.profile.query", "filterDraft", "Log.", "println")) assertFalse(forbidden, adapter.contains(forbidden))
        val build = File("build.gradle.kts").readText()
        for (input in listOf("discovery/DiscoveryReadingAnchorPolicy.kt", "ui/DiscoveryReadingAnchor.kt", "ui/DiscoveryScreen.kt"))
            assertTrue(input, build.contains(input))
        val screen = source("ui/DiscoveryScreen.kt")
        // Existing visibility and paging owners remain in the screen, separate from restoration.
        assertTrue(screen.contains("if (tracker.update(id, fraction.coerceIn(0.0, 1.0), now)) controller.recordSeen(page, result, SeenEvent.SHOWN)"))
        assertTrue(screen.contains("delay(100)"))
    }
    @Test fun userScrollMutationCancellationDoesNotDisableFutureCapture() = runBlocking {
        var afterRestore = false
        restoreDiscoveryReadingPosition { throw CancellationException("inner scroll mutation") }
        afterRestore = true
        assertTrue(afterRestore)
        try {
            restoreDiscoveryReadingPosition { throw IllegalStateException("ordinary failure") }
            fail("Non-cancellation failures must propagate")
        } catch (_: IllegalStateException) { }
    }
    @Test fun parentEffectCancellationCannotContinueIntoCapture() = runBlocking {
        var afterRestore = false
        val oldEffect = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            restoreDiscoveryReadingPosition { throw CancellationException("obsolete page") }
            afterRestore = true
        }
        oldEffect.join()
        assertTrue(oldEffect.isCancelled)
        assertFalse(afterRestore)
    }

    @Test fun onlySuccessfulExistingRestoreCreatesReadonlyProvenance() {
        val runtime = source("discovery/AndroidDiscoveryController.kt")
        assertTrue(runtime.contains("val restoredPageFromStamp: SessionStamp? = null"))
        assertEquals(1, Regex("readingRestoreProof = DiscoveryReadingAnchorPolicy.restoreProof").findAll(runtime).count())
        val initialize = runtime.substringAfter("private fun initialize(").substringBefore("private fun ")
        assertTrue(initialize.indexOf("readingRestoreProof = null") < initialize.indexOf("if (!sourceConfigurationLoaded)"))
        assertTrue(initialize.contains("if (!c.restore(SESSION, requireExplicitSearch = recoveringUnavailable))"))
        assertTrue(initialize.contains("readingRestoreProof = DiscoveryReadingAnchorPolicy.restoreProof(previous, c.currentCheckpoint())"))
        assertTrue(initialize.indexOf("readingRestoreProof = DiscoveryReadingAnchorPolicy.restoreProof") >
            initialize.indexOf("if (!c.restore(SESSION, requireExplicitSearch = recoveringUnavailable))"))
        assertTrue(runtime.contains("restoredPageFromStamp = if (unavailable) null else DiscoveryReadingAnchorPolicy.restoredFromStamp(readingRestoreProof, updated, page)"))
        assertTrue(runtime.substringAfter("private fun guarded(").substringBefore("override fun close()").contains("readingRestoreProof = null"))
        val screen = source("ui/DiscoveryScreen.kt")
        assertTrue(screen.contains("restoredFromStamp = state.restoredPageFromStamp"))
        assertTrue(source("ui/DiscoveryReadingAnchor.kt").contains("resolve(anchor, pageKey, resultIds, restoredFromStamp)"))
    }

}
