package com.easysavevideo.app.discovery

/** Release uses the lifecycle-owned OS bridge; synthetic configuration is never accepted. */
object BuildDiscoveryPower {
    val scenarios: List<DiscoveryPowerScenario> = emptyList()
    @Suppress("UNUSED_PARAMETER")
    fun selection(id: String): DiscoveryPowerSelection? = null
}
