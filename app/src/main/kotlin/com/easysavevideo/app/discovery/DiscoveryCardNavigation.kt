package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.VideoSearchResult

/** Ephemeral rendered-card intent. Never persist or log its page/result payload. */
internal data class DiscoveryCardOpenRequest(
    val target: DiscoveryControlTarget,
    val page: PageSnapshot,
    val result: VideoSearchResult,
    val lifecycle: Long,
) { override fun toString() = "DiscoveryCardOpenRequest([redacted])" }

internal fun discoveryTargetMatchesPage(target: DiscoveryControlTarget, page: PageSnapshot,
    result: VideoSearchResult): Boolean = target.controllerGeneration >= 0 && target.stamp == page.stamp && target.viewRevision == page.revision &&
    result.resultId in page.orderedResultIds

internal fun discoveryCardOpenRequest(state: DiscoveryUiState, result: VideoSearchResult,
    lifecycle: Long): DiscoveryCardOpenRequest? {
    val page = state.page ?: return null
    val target = state.controlTarget ?: return null
    if (!state.ready || state.storageUnavailable || result !in state.results ||
        target.profileId != state.profile.profileId || !discoveryTargetMatchesPage(target, page, result)) return null
    return DiscoveryCardOpenRequest(target, page, result, lifecycle)
}

/** Read the actual activity/controller owner immediately before each navigation side effect. */
internal fun dispatchDiscoveryCardOpen(request: DiscoveryCardOpenRequest, current: () -> DiscoveryUiState,
    lifecycle: () -> Long, interactive: () -> Boolean,
    open: (DiscoveryControlTarget, PageSnapshot, VideoSearchResult) -> Boolean,
    recordOpened: (PageSnapshot, VideoSearchResult) -> Unit,
    controlsBusy: () -> Boolean = { false }, showBrowser: () -> Unit = {}): Boolean {
    fun stillCurrent(): Boolean {
        val state = current()
        return interactive() && !controlsBusy() && request.lifecycle == lifecycle() &&
            state.ready && !state.storageUnavailable && request.target == state.controlTarget &&
            request.target.profileId == state.profile.profileId && request.page == state.page &&
            request.result in state.results && discoveryTargetMatchesPage(request.target, request.page, request.result)
    }
    if (!stillCurrent()) return false
    if (!open(request.target, request.page, request.result)) return false
    // Native callbacks can be reentrant. Do not steal a newer route/query after acceptance.
    if (!stillCurrent()) return false
    showBrowser()
    recordOpened(request.page, request.result)
    return true
}
