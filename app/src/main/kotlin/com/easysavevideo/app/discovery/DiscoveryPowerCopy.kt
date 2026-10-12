package com.easysavevideo.app.discovery

import com.easysavevideo.app.R

/** Finite presentation keys. No policy evaluation, sensor reads or cached locale. */
enum class DiscoveryPowerCopy(val resource: Int) {
    NOT_MONITORED(R.string.discovery_power_not_monitored),
    MONITORED(R.string.discovery_power_monitored),
    STOPPED(R.string.discovery_power_stopped),
    UNAVAILABLE(R.string.discovery_power_unavailable),
    FRESH(R.string.discovery_power_fresh),
    UNKNOWN(R.string.discovery_power_unknown),
    PENDING(R.string.discovery_power_pending),
    NOT_CONNECTED(R.string.discovery_power_not_connected),
    UNRESTRICTED(R.string.discovery_power_unrestricted),
    CONSERVING(R.string.discovery_power_conserving),
    PAUSED(R.string.discovery_power_paused),
    BUDGET(R.string.discovery_power_budget),
    RECOVERY(R.string.discovery_power_recovery),
    SIMULATED(R.string.discovery_power_simulated),
    SYSTEM(R.string.discovery_power_system),
    INJECTED(R.string.discovery_power_injected);
}

fun interface DiscoveryPowerText {
    fun render(key: DiscoveryPowerCopy, arguments: Array<out Any>): String
    fun get(key: DiscoveryPowerCopy, vararg arguments: Any): String = render(key, arguments)
}
