package com.easysavevideo.app.discovery

import android.content.Context
import com.easysavevideo.app.storage.AndroidSearxngConfigurationStore

/** Exactly one store/controller/serialized worker for this app process, independent of Activities. */
object AndroidSearxngConfigurationOwner {
    @Volatile private var hub: SearxngConfigurationHub? = null

    fun get(context: Context): SearxngConfigurationHub = hub ?: synchronized(this) {
        hub ?: SearxngConfigurationHub(AndroidSearxngConfigurationStore(context.applicationContext)).also { hub = it }
    }
}
