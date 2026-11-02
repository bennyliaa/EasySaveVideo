package com.easysavevideo.discovery

/** Local presentation facts. Opaque cursor/query values never leave the coordinator in this snapshot. */
enum class ProviderDiscoveryState {
    NOT_STARTED, REQUESTING, RETRY_WAIT, COOLDOWN, BLOCKED, READY, EXHAUSTED,
    CURSOR_EXPIRED, CURSOR_INCOMPATIBLE, SUSPENDED, STORAGE_UNAVAILABLE,
}
data class ProviderDiscoveryProgress(
    val providerId: String,
    val state: ProviderDiscoveryState,
    val hasContinuation: Boolean,
    val lastSuccessAtEpochMillis: Long?,
    val retryAtEpochMillis: Long?,
    val failureCount: Int,
    val lastError: DiscoveryError?,
) {
    override fun toString(): String = "ProviderDiscoveryProgress([redacted])"
}

internal fun providerDiscoveryProgress(
    descriptor: ProviderDescriptor,
    progress: ProviderProgress?,
    health: ProviderHealth?,
    queryKey: String,
    nowMillis: Long,
    gateOpen: Boolean,
    requesting: Boolean,
    retrying: Boolean,
    storageUnavailable: Boolean,
    hasQuery: Boolean,
): ProviderDiscoveryProgress {
    val relevant = health?.takeIf { it.blockedQueryKey == null || it.blockedQueryKey == queryKey }
    val compatible = progress?.isCompatible(descriptor, nowMillis) == true
    val state = when {
        storageUnavailable -> ProviderDiscoveryState.STORAGE_UNAVAILABLE
        !hasQuery -> ProviderDiscoveryState.NOT_STARTED
        !gateOpen -> ProviderDiscoveryState.SUSPENDED
        requesting -> ProviderDiscoveryState.REQUESTING
        retrying -> ProviderDiscoveryState.RETRY_WAIT
        (relevant?.cooldownUntilEpochMillis ?: 0) > nowMillis -> ProviderDiscoveryState.COOLDOWN
        relevant?.blockedQueryKey == queryKey -> ProviderDiscoveryState.BLOCKED
        progress?.expiresAtEpochMillis?.let { nowMillis >= it } == true -> ProviderDiscoveryState.CURSOR_EXPIRED
        progress != null && !compatible -> ProviderDiscoveryState.CURSOR_INCOMPATIBLE
        progress?.exhausted == true -> ProviderDiscoveryState.EXHAUSTED
        progress != null -> ProviderDiscoveryState.READY
        else -> ProviderDiscoveryState.NOT_STARTED
    }
    return ProviderDiscoveryProgress(descriptor.id, state,
        compatible && progress?.exhausted == false && progress.cursor != null,
        progress?.lastSuccessAtEpochMillis,
        relevant?.cooldownUntilEpochMillis?.takeIf { it > nowMillis },
        relevant?.failureCount ?: 0, relevant?.lastError)
}
