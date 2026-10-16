package com.easysavevideo.app.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.easysavevideo.app.vault.crypto.AndroidVaultAuthentication
import com.easysavevideo.app.vault.storage.AndroidVaultStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single production construction gate. No property, debug intent, or saved setting overrides it. */
internal object AndroidVaultRuntimeEntry {
    const val ENABLED: Boolean = false
    private var owner: AndroidVaultRuntimeBinding? = null

    @Synchronized fun getOrCreate(context: Context): AndroidVaultRuntimeBinding? {
        if (!ENABLED) return null
        owner?.let { return it }
        val presentation = AndroidVaultPresentationOwner()
        val authentication = AndroidVaultAuthentication()
        val store = AndroidVaultStore(context.applicationContext, authentication)
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
            SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)).let { runtime ->
                AndroidVaultRuntimeBinding(runtime, presentation).also { owner = it }
            }
    }
}

internal class AndroidVaultRuntimeBinding(val runtime: VaultRuntime, val presentation: AndroidVaultPresentationOwner)
