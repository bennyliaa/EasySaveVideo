package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings

/** Application-lifetime, field-specific knowledge. Disk reloads cannot roll back accepted app choices. */
internal class SaveSettingsOwner {
    private var current = SaveSettings()
    private var knownWifi = false
    private var knownConcurrency = false
    val wifiKnown: Boolean @Synchronized get() = knownWifi
    val concurrencyKnown: Boolean @Synchronized get() = knownConcurrency

    @Synchronized fun snapshot(): SaveSettings = current

    /** Call only for a successful load. An early Wi-Fi edit does not hide loaded concurrency. */
    @Synchronized fun initialize(loaded: SaveSettings): SaveSettings {
        current = current.copy(
            wifiOnly = if (knownWifi) current.wifiOnly else loaded.wifiOnly,
            maxConcurrentDirectTasks = if (knownConcurrency) current.maxConcurrentDirectTasks else loaded.maxConcurrentDirectTasks,
        )
        knownWifi = true
        knownConcurrency = true
        return current
    }

    @Synchronized fun updateWifiOnly(enabled: Boolean): SaveSettings {
        current = current.copy(wifiOnly = enabled)
        knownWifi = true
        return current
    }

    /** Does not confirm an unknown Wi-Fi preference or grant network-policy authority. */
    @Synchronized fun updateConcurrency(count: Int): SaveSettings? {
        if (!knownConcurrency || count !in 1..2) return null
        current = current.copy(maxConcurrentDirectTasks = count)
        return current
    }
}
