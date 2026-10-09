package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*

/** Synthetic inputs are opt-in, process-local, and absent from the Release source set. */
object BuildDiscoveryPower {
    val scenarios = listOf(
        DiscoveryPowerScenario("disconnected", "关闭模拟：使用 Android 系统信号"),
        DiscoveryPowerScenario("normal", "模拟：正常电量、温度与非计费网络"),
        DiscoveryPowerScenario("conserving", "模拟：低电量、温热与计费网络"),
        DiscoveryPowerScenario("unknown", "模拟：已启用但信号未知"),
        DiscoveryPowerScenario("paused", "模拟：临界电量、过热与离线"),
    )
    fun selection(id: String): DiscoveryPowerSelection? = when (id) {
        "disconnected" -> DiscoveryPowerSelection(null, DiscoveryPowerSignals())
        "normal" -> DiscoveryPowerSelection(id, DiscoveryPowerSignals(true, BatterySignal.NORMAL, ThermalSignal.NORMAL, NetworkSignal.UNMETERED))
        "conserving" -> DiscoveryPowerSelection(id, DiscoveryPowerSignals(true, BatterySignal.LOW, ThermalSignal.WARM, NetworkSignal.METERED))
        "unknown" -> DiscoveryPowerSelection(id, DiscoveryPowerSignals(enabled = true))
        "paused" -> DiscoveryPowerSelection(id, DiscoveryPowerSignals(true, BatterySignal.CRITICAL, ThermalSignal.SEVERE, NetworkSignal.OFFLINE))
        else -> null
    }
}
