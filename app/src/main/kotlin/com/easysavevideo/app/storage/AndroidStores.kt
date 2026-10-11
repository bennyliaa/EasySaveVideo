package com.easysavevideo.app.storage

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.easysavevideo.app.browser.BrowserInputPolicy
import com.easysavevideo.core.model.*
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Primitive settings only. All calls are made on the bounded single IO executor. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val application = context.applicationContext
    private val prefs = context.getSharedPreferences("browser_settings_v1", Context.MODE_PRIVATE)
    override fun load(): AppSettings = AppSettings(browser = BrowserSettings(
        restoreTabs = prefs.getBoolean("restore_tabs", true),
        maxActiveWebViews = prefs.getInt("active_views", 3).coerceIn(1, 4),
        javaScriptEnabled = prefs.getBoolean("javascript", true),
        acceptThirdPartyCookies = prefs.getBoolean("third_party_cookies", false),
    ), discovery = DiscoverySettings(
        columns = prefs.getInt("discovery_columns", 2).coerceIn(1, 6),
        pageSize = prefs.getInt("discovery_page_size", 20).coerceIn(1, 100),
        repeatPolicy = RepeatPolicy.entries.firstOrNull { it.name == prefs.getString("discovery_repeat", null) } ?: RepeatPolicy.PREFER_NEW,
        mode = DiscoveryMode.entries.firstOrNull { it.name == prefs.getString("discovery_mode", null) } ?: DiscoveryMode.BALANCED,
    ), appearance = AppearanceSettings(
        ThemeMode.fromKey(prefs.getString("ui_theme_mode", null)),
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = application.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            AppLanguage.fromTag(if (locales.isEmpty) "" else locales[0].toLanguageTag())
        } else AppLanguage.fromTag(prefs.getString("ui_language", null)),
    ))
    override fun save(settings: AppSettings) {
        check(prefs.edit().putBoolean("restore_tabs", settings.browser.restoreTabs)
            .putInt("active_views", settings.browser.maxActiveWebViews)
            .putBoolean("javascript", settings.browser.javaScriptEnabled)
            .putBoolean("third_party_cookies", settings.browser.acceptThirdPartyCookies)
            .putInt("discovery_columns", settings.discovery.columns).putInt("discovery_page_size", settings.discovery.pageSize)
            .putString("discovery_repeat", settings.discovery.repeatPolicy.name).putString("discovery_mode", settings.discovery.mode.name)
            .putString("ui_theme_mode", settings.appearance.themeMode.key)
            .apply { if (Build.VERSION.SDK_INT < 33) putString("ui_language", settings.appearance.language.tag) }.commit())
        if (Build.VERSION.SDK_INT >= 33) {
            // The platform is the sole locale persistence owner on API33+.
            val manager = application.getSystemService(android.app.LocaleManager::class.java)
            val requested = android.os.LocaleList.forLanguageTags(settings.appearance.language.tag)
            if (manager.applicationLocales != requested) manager.applicationLocales = requested
        }
    }
}

/** Sensitive functional state, separate from logs; no cookies or history blobs. Bounded, atomic. */
class AndroidBrowserSessionStore(context: Context) : BrowserSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "browser_session_v1.bin"))
    override fun load(): BrowserSessionCheckpoint? {
        if (!file.baseFile.exists()) return null
        require(file.baseFile.length() <= BrowserSessionCodec.MAX_BYTES)
        return BrowserSessionCodec.decode(file.readFully())
    }
    override fun save(checkpoint: BrowserSessionCheckpoint) {
        val bytes = BrowserSessionCodec.encode(checkpoint)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}

/** No platform logs or exception strings. 256 KiB total, seven days, ERROR enums only. */
class AndroidErrorSink(context: Context) : ErrorSink, AutoCloseable {
    private val directory = File(context.noBackupFilesDir, "technical_errors")
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32), ThreadPoolExecutor.DiscardPolicy())
    init { executor.execute { runCatching { prune(System.currentTimeMillis()) } } }
    private fun prune(now: Long) {
        val day = now / (24 * 3600 * 1000L)
        directory.listFiles().orEmpty().filter { file ->
            val writtenDay = file.name.substringBefore('.').toLongOrNull()
            writtenDay == null || writtenDay < day - 6
        }.forEach { it.delete() }
        directory.listFiles().orEmpty().sortedByDescending { it.name }.drop(5).forEach { it.delete() }
    }
    override fun report(error: ErrorEnvelope) {
        runCatching { executor.execute {
            runCatching {
                directory.mkdirs()
                val now = System.currentTimeMillis()
                val day = now / (24 * 3600 * 1000L)
                prune(now)
                val current = File(directory, "$day.log")
                // Fixed daily buckets cannot indefinitely extend old records' retention.
                if (current.length() < 51 * 1024) current.appendText(
                    "$now ERROR ${error.module.name} ${error.code.name} api=${Build.VERSION.SDK_INT} http=${error.safeContext.httpStatus ?: 0}\n")
                directory.listFiles().orEmpty().sortedByDescending { it.name }.drop(5).forEach { it.delete() }
            }
        } }
    }
    override fun close() { executor.shutdown() }
}
