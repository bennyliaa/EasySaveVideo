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
    val appearance: AppearanceSettings = AppearanceSettings(),
    val editor: EditorSettings = EditorSettings(),
    val save: SaveSettings = SaveSettings(),
    val vaultAutoLockPolicy: VaultAutoLockPolicy = VaultAutoLockPolicy.IMMEDIATE,
) { init { require(schemaVersion == 1) } }

/** Stable values are additive; existing v1 browser/discovery preferences remain readable. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    fun useDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM }
}
enum class AppLanguage(val tag: String) {
    SYSTEM(""), CHINESE("zh-CN"), ENGLISH("en");
    companion object { fun fromTag(tag: String?) = entries.firstOrNull { it.tag == tag } ?: SYSTEM }
}
data class AppearanceSettings(val themeMode: ThemeMode = ThemeMode.SYSTEM, val language: AppLanguage = AppLanguage.SYSTEM)
interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

/** Editor-only defaults. Persist stable keys, never enum ordinals or library/platform types. */
enum class EditorCompressionMode(val key: String) {
    ORIGINAL_QUALITY("original_quality"), SAVE_SPACE("save_space");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ORIGINAL_QUALITY
    }
}
data class EditorSettings(val defaultCompression: EditorCompressionMode = EditorCompressionMode.ORIGINAL_QUALITY)

/** Additive save preferences. Method switches admit new tasks only, never control accepted work. */
data class SaveSettings(
    val wifiOnly: Boolean = false,
    val maxConcurrentDirectTasks: Int = 1,
    val directEnabled: Boolean = true,
    val streamEnabled: Boolean = true,
    val recordEnabled: Boolean = true,
) {
    init { require(maxConcurrentDirectTasks in 1..2) }

    /** Browsing and editing remain available even when every save method is disabled. */
    fun isMethodEnabled(method: TaskMethod): Boolean = when (method) {
        TaskMethod.DIRECT -> directEnabled
        TaskMethod.STREAM -> streamEnabled
        TaskMethod.RECORD -> recordEnabled
        TaskMethod.BROWSER, TaskMethod.EDIT -> true
    }

    fun withMethodEnabled(method: TaskMethod, enabled: Boolean): SaveSettings = when (method) {
        TaskMethod.DIRECT -> copy(directEnabled = enabled)
        TaskMethod.STREAM -> copy(streamEnabled = enabled)
        TaskMethod.RECORD -> copy(recordEnabled = enabled)
        TaskMethod.BROWSER, TaskMethod.EDIT -> throw IllegalArgumentException("not a save method")
    }
}
