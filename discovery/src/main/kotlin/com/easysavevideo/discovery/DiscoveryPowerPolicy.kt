package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode

/** Transient, caller-supplied observations. No platform reader or persistence is implied. */
enum class BatterySignal { UNKNOWN, NORMAL, LOW, CRITICAL }
enum class ThermalSignal { UNKNOWN, NORMAL, WARM, SEVERE }
enum class NetworkSignal { UNKNOWN, UNMETERED, METERED, OFFLINE }

data class DiscoveryPowerSignals(
    val enabled: Boolean = false,
    val battery: BatterySignal = BatterySignal.UNKNOWN,
    val thermal: ThermalSignal = ThermalSignal.UNKNOWN,
    val network: NetworkSignal = NetworkSignal.UNKNOWN,
)

enum class DiscoveryPowerPolicyState { NOT_CONNECTED, UNRESTRICTED, CONSERVING, PAUSED }

/** Immutable technical status, not measured energy savings or evidence of OS monitoring. */
data class DiscoveryPowerPolicyStatus(
    val signals: DiscoveryPowerSignals,
    val state: DiscoveryPowerPolicyState,
    val requestedMode: DiscoveryMode,
    val effectiveMode: DiscoveryMode,
    val concurrency: Int,
    val maxRequestsPerRound: Int,
    val lowWatermark: Int,
    val highWatermark: Int,
) {
    val allowsRequests: Boolean get() = state != DiscoveryPowerPolicyState.PAUSED
}

/**
 * Pure, conservative intersection with existing bounds. Disabled means NOT_CONNECTED, not healthy.
 * Enabled unknown observations pause requests. Low battery, warm thermal state or metering cap work
 * at one concurrent request, three attempts per explicit round and one/two-page watermarks.
 * Critical battery, severe heat and offline also pause. These are deterministic policy choices,
 * not battery thresholds, OS classifications, network validation or measured energy claims.
 */
object DiscoveryPowerPolicy {
    fun evaluate(
        signals: DiscoveryPowerSignals,
        requestedMode: DiscoveryMode,
        limits: DiscoveryLimits = DiscoveryLimits(),
    ): DiscoveryPowerPolicyStatus {
        val paused = signals.enabled && ((signals.battery == BatterySignal.UNKNOWN || signals.battery == BatterySignal.CRITICAL) ||
            (signals.thermal == ThermalSignal.UNKNOWN || signals.thermal == ThermalSignal.SEVERE) ||
            (signals.network == NetworkSignal.UNKNOWN || signals.network == NetworkSignal.OFFLINE))
        val conserving = signals.enabled && (signals.battery == BatterySignal.LOW ||
            signals.thermal == ThermalSignal.WARM || signals.network == NetworkSignal.METERED)
        val state = when {
            !signals.enabled -> DiscoveryPowerPolicyState.NOT_CONNECTED
            paused -> DiscoveryPowerPolicyState.PAUSED
            conserving -> DiscoveryPowerPolicyState.CONSERVING
            else -> DiscoveryPowerPolicyState.UNRESTRICTED
        }
        val effectiveMode = if (paused || conserving) DiscoveryMode.POWER_SAVING else requestedMode
        return DiscoveryPowerPolicyStatus(signals, state, requestedMode, effectiveMode,
            concurrency = if (paused) 0 else minOf(limits.concurrency(requestedMode), limits.concurrency(effectiveMode)),
            maxRequestsPerRound = if (paused) 0 else minOf(limits.maxRequestsPerRound, if (conserving) 3 else limits.maxRequestsPerRound),
            lowWatermark = if (paused) 0 else minOf(limits.lowWatermark, if (conserving) limits.pageSize else limits.lowWatermark),
            highWatermark = if (paused) 0 else minOf(limits.highWatermark, if (conserving) limits.pageSize * 2 else limits.highWatermark))
    }
}
