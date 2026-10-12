package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.DiscoveryPowerSignals

/** Display metadata only. Synthetic values and choices live exclusively in the debug source set. */
data class DiscoveryPowerScenario(val id: String, val label: String)

/** A selection is process-local and never enters preferences, checkpoints, or diagnostics. */
data class DiscoveryPowerSelection(val scenarioId: String?, val signals: DiscoveryPowerSignals) {
    override fun toString() = "DiscoveryPowerSelection([redacted])"
}

object DiscoveryPowerPresentationPolicy {
    fun lines(policy: com.easysavevideo.discovery.DiscoveryPowerPolicyStatus?, pending: Boolean,
              simulated: Boolean, os: DiscoveryOsPowerSnapshot? = null, text: DiscoveryPowerText): List<String> {
        val result = mutableListOf(text.get(if (os == null) DiscoveryPowerCopy.NOT_MONITORED else DiscoveryPowerCopy.MONITORED))
        if (os != null) result += text.get(when (os.state) {
            OsObservationState.STOPPED, OsObservationState.CLOSED -> DiscoveryPowerCopy.STOPPED
            OsObservationState.UNAVAILABLE -> DiscoveryPowerCopy.UNAVAILABLE
            OsObservationState.OBSERVING -> if (listOf(os.battery, os.saver, os.thermal, os.network).all { it == OsSignalAge.FRESH })
                DiscoveryPowerCopy.FRESH else DiscoveryPowerCopy.UNKNOWN
        })
        if (pending) result += text.get(DiscoveryPowerCopy.PENDING)
        if (policy == null) return result
        val origin = text.get(when {
            simulated -> DiscoveryPowerCopy.SIMULATED
            os != null -> DiscoveryPowerCopy.SYSTEM
            else -> DiscoveryPowerCopy.INJECTED
        })
        result += when (policy.state) {
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.NOT_CONNECTED -> text.get(DiscoveryPowerCopy.NOT_CONNECTED)
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.UNRESTRICTED -> text.get(DiscoveryPowerCopy.UNRESTRICTED, origin)
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.CONSERVING -> text.get(DiscoveryPowerCopy.CONSERVING, origin)
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.PAUSED -> text.get(DiscoveryPowerCopy.PAUSED, origin)
        }
        result += text.get(DiscoveryPowerCopy.BUDGET, policy.concurrency, policy.maxRequestsPerRound, policy.lowWatermark, policy.highWatermark)
        result += text.get(DiscoveryPowerCopy.RECOVERY)
        return result
    }
}
