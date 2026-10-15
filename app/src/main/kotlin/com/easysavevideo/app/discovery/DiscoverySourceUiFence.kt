package com.easysavevideo.app.discovery

/** Errors use the current fenced generation too; a stale error cannot erase a newer source intent. */
data class DiscoverySourceInitializationClaim(val intent: Long, val configurationGeneration: String?) {
    override fun toString() = "DiscoverySourceInitializationClaim([redacted])"
}

object DiscoverySourceUiFence {
    fun canInitialize(claim: DiscoverySourceInitializationClaim, currentIntent: Long,
        currentConfigurationGeneration: String?, configurationBusy: Boolean): Boolean =
        claim.intent == currentIntent && !configurationBusy &&
            // Unavailable configuration cannot enable a SearXNG endpoint; preserve the zero-source fallback.
            (claim.configurationGeneration == null || claim.configurationGeneration == currentConfigurationGeneration)
    fun accepts(publishedIntent: Long, currentIntent: Long): Boolean = publishedIntent == currentIntent
    fun failure(intent: Long, demo: Boolean) = DiscoveryUiState(ready = true, demo = demo, storageUnavailable = true,
        configurationIntent = intent, needsExplicitSearch = true,
        notice = DiscoveryNotice.STORAGE_RECOVERY_UNAVAILABLE)
}
