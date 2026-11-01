package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.core.model.TaskMethod

/** Application-lifetime, field-specific knowledge. Disk reloads cannot roll back accepted app choices. */
internal class SaveSettingsOwner {
    private var current = SaveSettings()
    private var knownWifi = false
    private var knownConcurrency = false
    private var knownMethods = false
    val wifiKnown: Boolean @Synchronized get() = knownWifi
    val concurrencyKnown: Boolean @Synchronized get() = knownConcurrency
    val methodsKnown: Boolean @Synchronized get() = knownMethods

    @Synchronized fun snapshot(): SaveSettings = current

    /** Call only for a successful load. An early Wi-Fi edit does not hide loaded concurrency. */
    @Synchronized fun initialize(loaded: SaveSettings): SaveSettings {
        current = current.copy(
            wifiOnly = if (knownWifi) current.wifiOnly else loaded.wifiOnly,
            maxConcurrentDirectTasks = if (knownConcurrency) current.maxConcurrentDirectTasks else loaded.maxConcurrentDirectTasks,
            directEnabled = if (knownMethods) current.directEnabled else loaded.directEnabled,
            streamEnabled = if (knownMethods) current.streamEnabled else loaded.streamEnabled,
            recordEnabled = if (knownMethods) current.recordEnabled else loaded.recordEnabled,
        )
        knownWifi = true
        knownConcurrency = true
        knownMethods = true
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

    /** An unknown persisted preference is not permission to start a new save task. */
    @Synchronized fun isMethodEnabled(method: TaskMethod): Boolean = when (method) {
        TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD -> knownMethods && current.isMethodEnabled(method)
        TaskMethod.BROWSER, TaskMethod.EDIT -> true
    }

    /** Field-only edits have no cancellation, network, entitlement or active-task side effects. */
    @Synchronized fun updateMethodEnabled(method: TaskMethod, enabled: Boolean): SaveSettings? {
        if (!knownMethods || method == TaskMethod.BROWSER || method == TaskMethod.EDIT) return null
        current = current.withMethodEnabled(method, enabled)
        return current
    }

    /** Resolve inside the shared persistence boundary, not when an Activity queues its write. */
    @Synchronized fun acceptedMethodSettings(): SaveSettings? = current.takeIf { knownMethods }
}
