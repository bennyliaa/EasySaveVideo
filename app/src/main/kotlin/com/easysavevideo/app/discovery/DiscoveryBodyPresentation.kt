package com.easysavevideo.app.discovery

import com.easysavevideo.app.R
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryState
import com.easysavevideo.discovery.DiscoveryStatus

/** Display-only projection. Never infers connectivity, schedules requests, or changes page ownership. */
internal enum class DiscoveryBodyState {
    CONTENT, PENDING_CANDIDATES, STORAGE_UNAVAILABLE, RESTORING, SEARCHING, WAITING_FOR_PAGE,
    NO_SOURCES, FILLING, BUFFERED, BACKOFF, EXHAUSTED, SUSPENDED, INVALID_QUERY,
    SOURCE_CONFIGURATION, UNSUPPORTED_FILTER, FAILED, CAPACITY_REACHED, IDLE,
}

internal fun discoveryBodyState(
    hasResults: Boolean,
    hasPendingCandidates: Boolean,
    showPending: Boolean,
    ready: Boolean,
    storageUnavailable: Boolean,
    searchPending: Boolean,
    nextPagePending: Boolean,
    status: DiscoveryStatus?,
): DiscoveryBodyState {
    // Preserve the existing explicit candidate tab and already materialized content before feedback.
    if (hasPendingCandidates && (showPending || !hasResults)) return DiscoveryBodyState.PENDING_CANDIDATES
    if (hasResults) return DiscoveryBodyState.CONTENT
    if (storageUnavailable) return DiscoveryBodyState.STORAGE_UNAVAILABLE
    if (!ready) return DiscoveryBodyState.RESTORING
    if (searchPending) return DiscoveryBodyState.SEARCHING
    return when (status?.state) {
        DiscoveryState.NO_SOURCES -> DiscoveryBodyState.NO_SOURCES
        DiscoveryState.FILLING -> DiscoveryBodyState.FILLING
        DiscoveryState.BUFFERED -> DiscoveryBodyState.BUFFERED
        DiscoveryState.BACKOFF -> DiscoveryBodyState.BACKOFF
        DiscoveryState.EXHAUSTED -> DiscoveryBodyState.EXHAUSTED
        DiscoveryState.SUSPENDED -> DiscoveryBodyState.SUSPENDED
        DiscoveryState.UNSUPPORTED_FILTER -> when (status.lastError) {
            DiscoveryError.INVALID_QUERY -> DiscoveryBodyState.INVALID_QUERY
            DiscoveryError.SOURCE_CONFIGURATION -> DiscoveryBodyState.SOURCE_CONFIGURATION
            else -> DiscoveryBodyState.UNSUPPORTED_FILTER
        }
        DiscoveryState.FAILED -> DiscoveryBodyState.FAILED
        DiscoveryState.CAPACITY_REACHED -> DiscoveryBodyState.CAPACITY_REACHED
        DiscoveryState.IDLE, null -> if (nextPagePending) DiscoveryBodyState.WAITING_FOR_PAGE else DiscoveryBodyState.IDLE
    }
}

internal data class DiscoveryBodyText(val title: Int, val detail: Int, val showFilterGuidance: Boolean = false)

/** Android resource IDs are resolved by the current composition, never cached as translated text. */
internal fun discoveryBodyText(state: DiscoveryBodyState): DiscoveryBodyText? = when (state) {
    DiscoveryBodyState.CONTENT, DiscoveryBodyState.PENDING_CANDIDATES -> null
    DiscoveryBodyState.STORAGE_UNAVAILABLE -> DiscoveryBodyText(R.string.discovery_copy_9, R.string.discovery_copy_11)
    DiscoveryBodyState.RESTORING -> DiscoveryBodyText(R.string.discovery_copy_79, R.string.discovery_body_restoring)
    DiscoveryBodyState.SEARCHING -> DiscoveryBodyText(R.string.discovery_body_searching, R.string.discovery_body_wait)
    DiscoveryBodyState.WAITING_FOR_PAGE -> DiscoveryBodyText(R.string.discovery_body_waiting_page, R.string.discovery_body_page_wait)
    DiscoveryBodyState.NO_SOURCES -> DiscoveryBodyText(R.string.discovery_copy_10, R.string.flow_discovery_browser_fallback)
    DiscoveryBodyState.FILLING -> DiscoveryBodyText(R.string.discovery_copy_83, R.string.discovery_body_wait)
    DiscoveryBodyState.BUFFERED -> DiscoveryBodyText(R.string.discovery_copy_84, R.string.discovery_body_buffered)
    DiscoveryBodyState.BACKOFF -> DiscoveryBodyText(R.string.discovery_copy_85, R.string.discovery_body_backoff)
    DiscoveryBodyState.EXHAUSTED -> DiscoveryBodyText(R.string.flow_discovery_no_results, R.string.discovery_body_round_finished, true)
    DiscoveryBodyState.SUSPENDED -> DiscoveryBodyText(R.string.discovery_copy_87, R.string.discovery_body_paused)
    DiscoveryBodyState.INVALID_QUERY -> DiscoveryBodyText(R.string.discovery_copy_80, R.string.discovery_copy_13, true)
    DiscoveryBodyState.SOURCE_CONFIGURATION -> DiscoveryBodyText(R.string.discovery_copy_81, R.string.discovery_body_configuration)
    DiscoveryBodyState.UNSUPPORTED_FILTER -> DiscoveryBodyText(R.string.discovery_copy_88, R.string.discovery_copy_13, true)
    DiscoveryBodyState.FAILED -> DiscoveryBodyText(R.string.discovery_copy_89, R.string.discovery_body_failed)
    DiscoveryBodyState.CAPACITY_REACHED -> DiscoveryBodyText(R.string.discovery_copy_90, R.string.discovery_copy_4)
    DiscoveryBodyState.IDLE -> DiscoveryBodyText(R.string.discovery_copy_91, R.string.discovery_copy_13, true)
}
