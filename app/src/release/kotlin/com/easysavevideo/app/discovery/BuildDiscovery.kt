package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.ProviderRegistry

/** Explicit local source configuration only; an empty registry remains fully supported. */
object BuildDiscovery {
    const val supportsDemo = false
    @Suppress("UNUSED_PARAMETER")
    fun registry(demoEnabled: Boolean, commonsEnabled: Boolean = true, searxng: SearxngSourceConfiguration? = null): ProviderRegistry =
        ProviderRegistry(SearxngSourceRegistration.bindGeneration(CommonsSourceRegistration.providers(commonsEnabled) + SearxngSourceRegistration.providers(searxng), searxng, commonsEnabled))
}
