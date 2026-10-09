package com.easysavevideo.core.model

/** M1 stores preferences only; Discovery execution and its settings UI remain M2. */
enum class RepeatPolicy { PREFER_NEW, HIDE_WATCHED, HIDE_SHOWN, SHOW_ALL }
enum class DiscoveryMode { POWER_SAVING, BALANCED, AGGRESSIVE }
data class DiscoverySettings(
    val columns: Int = 2,
    val pageSize: Int = 20,
    val repeatPolicy: RepeatPolicy = RepeatPolicy.PREFER_NEW,
    val mode: DiscoveryMode = DiscoveryMode.BALANCED,
) {
    init { require(columns in 1..6); require(pageSize in 1..100) }
}
data class BrowserSettings(
    val restoreTabs: Boolean = true,
    val maxActiveWebViews: Int = 3,
    val javaScriptEnabled: Boolean = true,
    val acceptThirdPartyCookies: Boolean = false,
) {
    init { require(maxActiveWebViews in 1..4) }
}
data class AppSettings(
    val schemaVersion: Int = 1,
    val browser: BrowserSettings = BrowserSettings(),
    val discovery: DiscoverySettings = DiscoverySettings(),
) { init { require(schemaVersion == 1) } }
interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}
