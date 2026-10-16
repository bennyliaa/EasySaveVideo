package com.easysavevideo.app.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.easysavevideo.app.vault.crypto.AndroidVaultAuthentication
import com.easysavevideo.app.vault.storage.AndroidVaultStore
import com.easysavevideo.app.vault.storage.AndroidVaultPhoneFiles
import com.easysavevideo.app.save.AndroidSaveRuntime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single production construction gate. No property, debug intent, or saved setting overrides it. */
internal object AndroidVaultRuntimeEntry {
    const val ENABLED: Boolean = false
    private var owner: AndroidVaultRuntimeBinding? = null

    /** Read-only binding lookup; settings composition and persistence cannot create a Vault. */
    @Synchronized fun currentBinding(): AndroidVaultRuntimeBinding? = if (ENABLED) owner else null

    @Synchronized fun getOrCreate(context: Context): AndroidVaultRuntimeBinding? {
        if (!ENABLED) return null
        owner?.let { return it }
        val presentation = AndroidVaultPresentationOwner()
        val authentication = AndroidVaultAuthentication()
        var source: VaultMediaSourcePort? = null
        val store = AndroidVaultStore(context.applicationContext, authentication) { source }
        source = AndroidSaveRuntime.get(context.applicationContext).vaultMediaSource(store, presentation::retireOrdinarySource)
        val files = AndroidVaultPhoneFiles(context.applicationContext, store)
        val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            ThreadPoolExecutor.AbortPolicy())
        val timers = Executors.newSingleThreadScheduledExecutor()
        val main = Handler(Looper.getMainLooper())
        val ui = Executor { command -> check(main.post(command)) }
        val clock = VaultMonotonicClock(SystemClock::elapsedRealtime)
        val scheduler = VaultWakeScheduler { delay, task ->
            val future = timers.schedule(Runnable { task() }, delay, TimeUnit.MILLISECONDS)
            VaultWakeCancellation { future.cancel(false) }
        }
        // The singleton is deliberately retained after release failure; replacement cannot bypass a fence.
        return VaultRuntime(store, authentication, clock, lane, ui, scheduler, presentation,
            SystemClock.elapsedRealtimeNanos().coerceAtLeast(1), source, files,
            com.easysavevideo.app.vault.playback.AndroidVaultPlaybackFactory(context.applicationContext), presentation,
            com.easysavevideo.app.vault.thumbnail.AndroidVaultThumbnailFactory()).let { runtime ->
                AndroidVaultRuntimeBinding(runtime, presentation, files).also { owner = it }
            }
    }
}

internal class AndroidVaultRuntimeBinding(val runtime: VaultRuntime, val presentation: AndroidVaultPresentationOwner,
    val phoneFiles: AndroidVaultPhoneFiles? = null) {
    // Exact app-owner request survives Activity replacement, but is never reconstructed from a URI.
    var pendingPhoneFileRequest: VaultPhoneFileRequest? = null
}
