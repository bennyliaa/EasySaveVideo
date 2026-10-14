package com.easysavevideo.app.discovery

import com.easysavevideo.app.R

/** These are existing UI destinations, never retry/source-enable/network commands. */
internal enum class DiscoveryBodyRecovery { MANAGE_SOURCES, EDIT_FILTERS }
internal fun discoveryBodyRecovery(state: DiscoveryBodyState): DiscoveryBodyRecovery? = when (state) {
    DiscoveryBodyState.NO_SOURCES, DiscoveryBodyState.SOURCE_CONFIGURATION -> DiscoveryBodyRecovery.MANAGE_SOURCES
    DiscoveryBodyState.INVALID_QUERY, DiscoveryBodyState.UNSUPPORTED_FILTER, DiscoveryBodyState.EXHAUSTED -> DiscoveryBodyRecovery.EDIT_FILTERS
    else -> null
}
internal fun discoveryBodyRecoveryLabel(action: DiscoveryBodyRecovery): Int = when (action) {
    DiscoveryBodyRecovery.MANAGE_SOURCES -> R.string.discovery_recovery_sources
    DiscoveryBodyRecovery.EDIT_FILTERS -> R.string.discovery_recovery_filters
}

/** Only the currently rendered empty-state action may open its corresponding existing UI. */
internal fun dispatchDiscoveryBodyRecovery(action: DiscoveryBodyRecovery,
    expected: DiscoveryUiState, current: DiscoveryUiState,
    expectedLifecycle: Long, currentLifecycle: Long, interactive: Boolean,
    openSources: () -> Unit, openFilters: () -> Unit): Boolean {
    if (!interactive || expected !== current || expectedLifecycle != currentLifecycle ||
        !current.ready || current.storageUnavailable) return false
    val body = discoveryBodyState(current.results.isNotEmpty(), current.pendingCandidates.isNotEmpty(),
        false, current.ready, current.storageUnavailable, false, current.nextPagePending, current.status)
    if (discoveryBodyRecovery(body) != action) return false
    when (action) {
        DiscoveryBodyRecovery.MANAGE_SOURCES -> openSources()
        DiscoveryBodyRecovery.EDIT_FILTERS -> openFilters()
    }
    return true
}
