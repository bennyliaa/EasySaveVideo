package com.easysavevideo.app.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.easysavevideo.app.vault.crypto.AndroidVaultAuthentication
import com.easysavevideo.app.vault.crypto.AndroidVaultBiometric
import com.easysavevideo.app.vault.crypto.AndroidVaultKeystore
import com.easysavevideo.app.R
import android.app.Activity
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
        val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            ThreadPoolExecutor.AbortPolicy())
        val timers = Executors.newSingleThreadScheduledExecutor()
        val main = Handler(Looper.getMainLooper())
        val ui = Executor { command -> check(main.post(command)) }
        val clock = VaultMonotonicClock(SystemClock::elapsedRealtime)
        val keystore = AndroidVaultKeystore()
        val authentication = AndroidVaultAuthentication(keystore = keystore,
            biometric = { binding ->
                binding.requireCurrent()
                val activity = binding.platformHost as? Activity ?: throw VaultOperationException(VaultFailure.REJECTED)
                AndroidVaultBiometric(activity, keystore, lane, timers, clock, platformExecutor = ui)
            },
            biometricText = { binding ->
                binding.requireCurrent()
                val activity = binding.platformHost as? Activity ?: throw VaultOperationException(VaultFailure.REJECTED)
                val title = when (binding.purpose) {
                    VaultBiometricPurpose.ENROLL_WRAP -> R.string.vault_biometric_prompt_enable
                    VaultBiometricPurpose.ENROLL_VERIFY -> R.string.vault_biometric_prompt_verify
                    VaultBiometricPurpose.UNLOCK -> R.string.vault_biometric_prompt_unlock
                    VaultBiometricPurpose.RETIRE_ALIAS -> throw VaultOperationException(VaultFailure.REJECTED)
                }
                activity.getText(title) to activity.getText(R.string.vault_biometric_cancel)
            })
        var source: VaultMediaSourcePort? = null
        val store = AndroidVaultStore(context.applicationContext, authentication) { source }
        source = AndroidSaveRuntime.get(context.applicationContext).vaultMediaSource(store, presentation::retireOrdinarySource)
        val files = AndroidVaultPhoneFiles(context.applicationContext, store)
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
