package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.DiscoveryPowerSignals

/** Display metadata only. Synthetic values and choices live exclusively in the debug source set. */
data class DiscoveryPowerScenario(val id: String, val label: String)

/** A selection is process-local and never enters preferences, checkpoints, or diagnostics. */
data class DiscoveryPowerSelection(val scenarioId: String?, val signals: DiscoveryPowerSignals) {
    override fun toString() = "DiscoveryPowerSelection([redacted])"
}

object DiscoveryPowerPresentationPolicy {
    fun lines(policy: com.easysavevideo.discovery.DiscoveryPowerPolicyStatus?, pending: Boolean, simulated: Boolean, os: DiscoveryOsPowerSnapshot? = null): List<String> {
        val result = mutableListOf(if (os == null) "当前版本未接入 Android 电量、温度或网络状态监测，未测量实际耗电或省电效果。"
            else "已接入 Android 电量、省电模式、温控和默认网络观察；仅在前台发现页监听，未测量实际耗电或省电效果。")
        if (os != null) result += when (os.state) {
            OsObservationState.STOPPED, OsObservationState.CLOSED -> "系统监听已停止；未重新取得有效信号前暂停补充。"
            OsObservationState.UNAVAILABLE -> "系统监听或到期检查不可用，补充已暂停；可离开发现页后重进。若仍不可用，请重启应用。"
            OsObservationState.OBSERVING -> if (listOf(os.battery, os.saver, os.thermal, os.network).all { it == OsSignalAge.FRESH })
                "已取得近期系统观察。五分钟未更新的单项观察会到期；刷新只更新信号，不会自动补充。"
                else "部分系统观察未知或已到期；这不代表电量为零或设备故障。可刷新系统信号，待有效后再主动查找或翻页。"
        }
        if (pending) result += "策略更新尚未完成，发现请求已保守暂停。"
        if (policy == null) return result
        result += when (policy.state) {
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.NOT_CONNECTED -> "系统信号未接入；仅按所选静态模式及前台、页面可见性和实际保存/录制状态限制补充。"
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.UNRESTRICTED -> "${if (simulated) "模拟信号" else if (os != null) "系统观察" else "注入信号"}允许既有预算，不会扩大静态模式或其他上限。"
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.CONSERVING -> "${if (simulated) "模拟信号" else if (os != null) "系统观察" else "注入信号"}收紧补充预算；这不是已经节省电量的测量结果。"
            com.easysavevideo.discovery.DiscoveryPowerPolicyState.PAUSED -> "${if (simulated) "模拟信号" else if (os != null) "系统观察" else "注入信号"}未知或需暂停，停止来源请求；已有本地结果仍可查看。"
        }
        result += "有效预算：并发最多 ${policy.concurrency}，每轮最多 ${policy.maxRequestsPerRound} 次尝试，低/高水位 ${policy.lowWatermark}/${policy.highWatermark}。"
        result += "信号变化只会收紧或停止当前需求；恢复不会自动开启新一轮。实际保存或录制始终优先。"
        return result
    }
}
