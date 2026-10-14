package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCardNavigationTest {
    private val stamp = SessionStamp("fixture", 1)
    private val target = DiscoveryControlTarget("profile", stamp, 2, 3)
    private val result = VideoSearchResult("identity", "https://example.test/video", "Fixture title", "source",
        "source", "video", null, null, null, null, setOf("provider"), setOf("identity"))
    private val page = PageSnapshot(stamp, 2, 0, listOf(result.resultId), 20)
    private val state = DiscoveryUiState(ready = true, profile = SearchProfile("profile", "fixture"),
        page = page, results = listOf(result), controlTarget = target, commonsConfigured = false)
    private val request = requireNotNull(discoveryCardOpenRequest(state, result, 5))
    private class Effects {
        val events = mutableListOf<String>()
        var opened: Triple<DiscoveryControlTarget, PageSnapshot, VideoSearchResult>? = null
        var recorded: Pair<PageSnapshot, VideoSearchResult>? = null
        fun open(target: DiscoveryControlTarget, page: PageSnapshot, result: VideoSearchResult): Boolean {
            events += "browser"; opened = Triple(target, page, result); return true
        }
        fun record(page: PageSnapshot, result: VideoSearchResult) { events += "OPENED"; recorded = page to result }
    }
    private fun dispatch(request: DiscoveryCardOpenRequest, current: DiscoveryUiState,
        lifecycle: Long, interactive: Boolean, open: (DiscoveryControlTarget, PageSnapshot, VideoSearchResult) -> Boolean,
        recordOpened: (PageSnapshot, VideoSearchResult) -> Unit, showBrowser: () -> Unit = {}): Boolean =
        dispatchDiscoveryCardOpen(request, { current }, { lifecycle }, { interactive }, open, recordOpened,
            showBrowser = showBrowser)
    private fun rejected(current: DiscoveryUiState = state, lifecycle: Long = 5, interactive: Boolean = true,
        intent: DiscoveryCardOpenRequest = request) {
        val effects = Effects()
        assertFalse(dispatch(intent, current, lifecycle, interactive, effects::open, effects::record))
        assertTrue(effects.events.isEmpty()); assertNull(effects.opened); assertNull(effects.recorded)
    }
    @Test fun currentRenderedCardOpensExactOriginalPageThenRecordsOnlyOpened() {
        val effects = Effects()
        assertTrue(dispatch(request, state, 5, true, effects::open, effects::record, showBrowser = { effects.events += "navigate" }))
        assertEquals(listOf("browser", "navigate", "OPENED"), effects.events)
        assertEquals(Triple(target, page, result), effects.opened)
        assertEquals(page to result, effects.recorded)
    }
    @Test fun nativeRejectionNeverRecordsOpenedOrImpliesSaving() {
        var attempts = 0; var events = 0
        assertFalse(dispatch(request, state, 5, true,
            open = { _, _, _ -> attempts++; false }, recordOpened = { _, _ -> events++ },
            showBrowser = { fail("Rejected native command must not route") }))
        assertEquals(1, attempts); assertEquals(0, events)
    }
    @Test fun backgroundOrOtherCurrentRouteRejectsBeforeAnyEffect() = rejected(interactive = false)
    @Test fun leaveAndReturnToSameRouteRejectsOldLifecycle() = rejected(lifecycle = 7)
    @Test fun newQuerySessionCannotOpenOldCard() = rejected(current = state.copy(
        controlTarget = target.copy(stamp = stamp.copy(epoch = 2))))
    @Test fun reinitializedControllerWithSamePageRejectsOldOwner() = rejected(current = state.copy(
        controlTarget = target.copy(controllerGeneration = 4)))
    @Test fun sameSessionNewViewRevisionRejectsOldCard() = rejected(current = state.copy(
        controlTarget = target.copy(viewRevision = 3)))
    @Test fun newProfileRejectsOldCardEvenWhenPageFieldsAreEqual() = rejected(current = state.copy(
        profile = state.profile.copy(profileId = "other")))
    @Test fun anotherMaterializedPageCannotOpenOldCard() = rejected(current = state.copy(page = page.copy(pageIndex = 1)))
    @Test fun changedResultWithSameIdCannotSupplyOldUrlOrMetadata() {
        rejected(current = state.copy(results = listOf(result.copy(canonicalPageUrl = "https://example.test/changed"))))
        rejected(current = state.copy(results = listOf(result.copy(title = "Changed"))))
    }
    @Test fun missingPageTargetOrResultNeverGuessesAnotherItem() {
        rejected(current = state.copy(page = null)); rejected(current = state.copy(controlTarget = null))
        rejected(current = state.copy(results = emptyList()))
    }
    @Test fun unreadyOrUnavailableStateCannotOpenRetainedRows() {
        rejected(current = state.copy(ready = false)); rejected(current = state.copy(storageUnavailable = true))
    }
    @Test fun malformedCardTargetCannotCrossTheHostPageBoundary() {
        assertFalse(discoveryTargetMatchesPage(target.copy(controllerGeneration = -1), page, result))
        assertFalse(discoveryTargetMatchesPage(target.copy(stamp = stamp.copy(epoch = 2)), page, result))
        assertFalse(discoveryTargetMatchesPage(target.copy(viewRevision = 9), page, result))
        assertFalse(discoveryTargetMatchesPage(target, page.copy(orderedResultIds = emptyList()), result))
        rejected(intent = request.copy(page = page.copy(orderedResultIds = emptyList())),
            current = state.copy(page = page.copy(orderedResultIds = emptyList())))
    }
    @Test fun requestCaptureRequiresTheActualReadyPageAndCompleteResult() {
        assertNull(discoveryCardOpenRequest(state.copy(ready = false), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(storageUnavailable = true), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(page = null), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(controlTarget = null), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(results = emptyList()), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(profile = SearchProfile("other", "fixture")), result, 5))
        assertNull(discoveryCardOpenRequest(state.copy(page = page.copy(orderedResultIds = emptyList())), result, 5))
    }
    @Test fun innocuousStatusRefreshKeepsSameCurrentPageOpenable() {
        val effects = Effects()
        assertTrue(dispatch(request, state.copy(notice = "Updated status"), 5, true, effects::open, effects::record))
    }
    @Test fun acceptedNavigationRetiresLifecycleBeforeRepeatedClick() {
        var lifecycle = 5L; val effects = Effects()
        fun click() = dispatchDiscoveryCardOpen(request, { state }, { lifecycle }, { true }, effects::open,
            effects::record, showBrowser = { lifecycle++; effects.events += "navigate" })
        assertTrue(click()); assertFalse(click())
        assertEquals(listOf("browser", "navigate", "OPENED"), effects.events)
    }
    @Test fun pendingNewQueryRejectsUnchangedOldPageTargetAndLifecycle() {
        var pending = true; val effects = Effects()
        fun click() = dispatchDiscoveryCardOpen(request, { state }, { 5 }, { true }, effects::open,
            effects::record, controlsBusy = { pending }, showBrowser = { effects.events += "navigate" })
        assertFalse(click()); assertTrue(effects.events.isEmpty())
        pending = false
        assertTrue(click()); assertEquals(listOf("browser", "navigate", "OPENED"), effects.events)
    }
    @Test fun nativeCallbackChangingRouteCannotBeOverwrittenByOldAcceptedClick() {
        var currentRouteIsDiscovery = true; val effects = Effects()
        assertFalse(dispatchDiscoveryCardOpen(request, { state }, { 5 }, { currentRouteIsDiscovery },
            open = { t, p, r -> currentRouteIsDiscovery = false; effects.open(t, p, r) },
            recordOpened = effects::record, showBrowser = { effects.events += "navigate" }))
        assertEquals(listOf("browser"), effects.events)
    }
    @Test fun nativeCallbackChangingOwnerOrPendingQueryCannotPublishOldNavigation() {
        for (change in 0..2) {
            var current = state; var lifecycle = 5L; var pending = false; val effects = Effects()
            assertFalse(dispatchDiscoveryCardOpen(request, { current }, { lifecycle }, { true },
                open = { t, p, r ->
                    when (change) { 0 -> current = state.copy(controlTarget = target.copy(controllerGeneration = 9))
                        1 -> lifecycle++; else -> pending = true }
                    effects.open(t, p, r)
                }, recordOpened = effects::record, controlsBusy = { pending }, showBrowser = { effects.events += "navigate" }))
            assertEquals(listOf("browser"), effects.events)
        }
    }
    @Test fun payloadDoesNotAppearInDiagnosticString() {
        val text = request.toString()
        assertFalse(text.contains(result.canonicalPageUrl)); assertFalse(text.contains(result.title!!))
        assertFalse(text.contains(target.profileId))
    }
}
