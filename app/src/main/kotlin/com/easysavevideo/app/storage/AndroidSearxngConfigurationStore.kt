package com.easysavevideo.app.storage

import android.content.Context
import java.io.File

/** No URLs in SharedPreferences, backup payloads, notices, analytics, or diagnostics. */
class AndroidSearxngConfigurationStore(context: Context) : SearxngConfigurationStore {
    private val appContext = context.applicationContext
    // Context's noBackupFilesDir may itself touch disk; defer even that lookup until background I/O.
    private val delegate by lazy { FileSearxngConfigurationStore(File(appContext.noBackupFilesDir, "searxng-sources-v1.bin")) }
    override fun load(): SearxngConfigurationRead = try { delegate.load() } catch (_: Exception) { SearxngConfigurationRead.Unavailable }
    override fun replace(expectedGeneration: String, replacement: com.easysavevideo.app.discovery.SearxngSourceConfiguration): SearxngConfigurationWrite =
        try { delegate.replace(expectedGeneration, replacement) } catch (_: Exception) { SearxngConfigurationWrite.Unavailable }
}
