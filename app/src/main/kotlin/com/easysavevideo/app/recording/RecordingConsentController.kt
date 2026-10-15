package com.easysavevideo.app.recording

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference

/** Correlation only. No Intent, permission result, Activity, or callback is retained here. */
internal data class PendingRecordingConsent(
    val requestId: String,
    val stage: RecordingConsentStage,
    val cancelled: Boolean = false,
    val defaultDisplayOnly: Boolean = false,
)
internal enum class RecordingConsentStage { AUDIO, PROJECTION }
internal fun canAcceptRecordingConsent(pending: PendingRecordingConsent?, stage: RecordingConsentStage): Boolean =
    pending != null && pending.stage == stage && !pending.cancelled

/** Process death deliberately forgets this state. A restored system result then has no owner. */
private object RecordingConsentMemory {
    var pending by mutableStateOf<PendingRecordingConsent?>(null)
    var audioPermissionDenied by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
}

internal const val RECORDING_PROJECTION_REQUEST_CODE = 41_320
internal const val RECORDING_AUDIO_REQUEST_CODE = 41_321
internal fun isRecordingResultCode(code: Int): Boolean =
    code == RECORDING_PROJECTION_REQUEST_CODE || code == RECORDING_AUDIO_REQUEST_CODE

/**
 * This registry is deliberately independent of ComponentActivity's SavedStateRegistry. Neither
 * onSaveInstanceState nor onRestoreInstanceState is called. Fixed platform request codes are below
 * AndroidX's randomized >=65536 range. Only the safe request-code correlation survives recreation,
 * in this process; a cold process consumes and discards an old fixed-code result.
 */
private object EphemeralRecordingResults : ActivityResultRegistry() {
    private var host = WeakReference<ComponentActivity>(null)
    private var projectionCode: Int? = null
    private var audioCode: Int? = null
    fun attach(activity: ComponentActivity) { host = WeakReference(activity) }
    fun attachedTo(activity: ComponentActivity): Boolean = host.get() === activity
    fun detach(activity: ComponentActivity) { if (attachedTo(activity)) host.clear() }

    @Suppress("DEPRECATION")
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        val activity = host.get() ?: throw IllegalStateException()
        val synchronous = contract.getSynchronousResult(activity, input)
        if (synchronous != null) {
            Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, synchronous.value) }
            return
        }
        when (contract) {
            is ActivityResultContracts.RequestPermission -> {
                check(input == Manifest.permission.RECORD_AUDIO)
                audioCode = requestCode
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORDING_AUDIO_REQUEST_CODE)
            }
            is ActivityResultContracts.StartActivityForResult -> {
                projectionCode = requestCode
                activity.startActivityForResult(input as? Intent ?: throw IllegalArgumentException(),
                    RECORDING_PROJECTION_REQUEST_CODE, options?.toBundle())
            }
            else -> throw IllegalArgumentException()
        }
    }

    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RECORDING_PROJECTION_REQUEST_CODE) return false
        val code = projectionCode
        projectionCode = null
        if (code != null) {
            // A detached Activity cannot retain a grant while waiting for the next Activity. Queue
            // only an empty denial; a reattached callback can then abandon the reserved request.
            if (host.get() == null) dispatchResult(code, Activity.RESULT_CANCELED, null)
            else dispatchResult(code, resultCode, data)
        }
        return true // Includes an old process's result; never send it to the saved registry.
    }
    fun permissionResult(requestCode: Int, permissions: Array<out String>, results: IntArray): Boolean {
        if (requestCode != RECORDING_AUDIO_REQUEST_CODE) return false
        val code = audioCode
        audioCode = null
        if (code != null) {
            val permitted = permissions.size == 1 && permissions[0] == Manifest.permission.RECORD_AUDIO &&
                results.size == 1 && results[0] == PackageManager.PERMISSION_GRANTED
            dispatchResult(code, permitted)
        }
        return true
    }
}

/**
 * Register in Activity.onCreate and forward the two fixed-code results through the companion below
 * BEFORE calling super. This controller, registry and pending ID never join Activity saved state.
 * Call close from onDestroy; cancel separately when the user abandons the request.
 */
class RecordingConsentController(
    private val activity: ComponentActivity,
    private val onGrant: (String, Int, Intent) -> Unit,
    private val onDenied: (String) -> Unit,
    private val isRequestAllowed: (String) -> Boolean,
    private val onAudioDenied: (String) -> Unit = onDenied,
    private val requiresDefaultDisplay: (String) -> Boolean = { false },
) : AutoCloseable {
    private var closed = false
    init { EphemeralRecordingResults.attach(activity) }
    private val projectionLauncher: ActivityResultLauncher<Intent> = EphemeralRecordingResults.register(
        "esv.recording.projection", ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = RecordingConsentMemory.pending
        if (pending?.stage != RecordingConsentStage.PROJECTION) return@register
        // Clear ownership before invoking application code. No result is retained or reusable.
        RecordingConsentMemory.pending = null
        if (!canAcceptRecordingConsent(pending, RecordingConsentStage.PROJECTION)) return@register
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || !isRequestAllowed(pending.requestId) ||
            pending.defaultDisplayOnly != requiresDefaultDisplay(pending.requestId)) {
            RecordingConsentMemory.notice = "未获得本次系统录制授权，未开始录制。"
            onDenied(pending.requestId)
        } else {
            try { onGrant(pending.requestId, result.resultCode, data) }
            catch (_: RuntimeException) {
                RecordingConsentMemory.notice = "本次授权未能交接，未重新使用授权。请重新开始。"
                onDenied(pending.requestId)
            }
        }
    }
    private val audioLauncher: ActivityResultLauncher<String> = EphemeralRecordingResults.register(
        "esv.recording.audio", ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = RecordingConsentMemory.pending
        if (pending?.stage != RecordingConsentStage.AUDIO) return@register
        if (!canAcceptRecordingConsent(pending, RecordingConsentStage.AUDIO)) { RecordingConsentMemory.pending = null; return@register }
        if (!granted) {
            RecordingConsentMemory.pending = null
            RecordingConsentMemory.audioPermissionDenied = true
            RecordingConsentMemory.notice = "内部声音权限未允许。请选择无声音重新开始，或取消。"
            onAudioDenied(pending.requestId)
        } else if (!isRequestAllowed(pending.requestId)) {
            RecordingConsentMemory.pending = null
            onDenied(pending.requestId)
        } else launchProjection(pending.requestId, pending.defaultDisplayOnly)
    }

    /** Correlation only; a pending system request does not identify why an Activity paused. */
    internal fun pendingForExactRequest(requestId: String): PendingRecordingConsent? {
        if (closed || !EphemeralRecordingResults.attachedTo(activity)) return null
        return RecordingConsentMemory.pending?.takeIf { it.requestId == requestId && !it.cancelled }
    }
    /** Caller must first match its exact Runtime binding; stale owners cannot cancel a new request. */
    internal fun cancelExactRequest(requestId: String): Boolean {
        if (pendingForExactRequest(requestId) == null) return false
        cancel()
        return true
    }

    val requestInFlight: Boolean get() = RecordingConsentMemory.pending != null
    val audioPermissionDenied: Boolean get() = RecordingConsentMemory.audioPermissionDenied
    val notice: String? get() = RecordingConsentMemory.notice
    val waitingForAudio: Boolean get() = RecordingConsentMemory.pending?.let {
        it.stage == RecordingConsentStage.AUDIO && !it.cancelled
    } == true

    /** Requires a fresh descriptor reserved by the runtime after explicit disclosure acceptance. */
    fun begin(requestId: String, internalAudio: Boolean): Boolean {
        RecordingConsentMemory.pending?.let { current ->
            // An idempotent duplicate must not abandon the request it is duplicating.
            if (current.requestId != requestId) onDenied(requestId)
            return current.requestId == requestId && !current.cancelled
        }
        if (closed || !EphemeralRecordingResults.attachedTo(activity) || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !isRequestAllowed(requestId)) {
            onDenied(requestId)
            return false
        }
        RecordingConsentMemory.audioPermissionDenied = false
        RecordingConsentMemory.notice = null
        if (internalAudio && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingConsentMemory.pending = PendingRecordingConsent(requestId, RecordingConsentStage.AUDIO, defaultDisplayOnly = requiresDefaultDisplay(requestId))
            try { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            catch (_: RuntimeException) { abandonFailedLaunch(requestId) }
        } else launchProjection(requestId, requiresDefaultDisplay(requestId))
        return RecordingConsentMemory.pending?.requestId == requestId
    }

    private fun launchProjection(requestId: String, defaultDisplayOnly: Boolean) {
        if (closed || !isRequestAllowed(requestId) || defaultDisplayOnly != requiresDefaultDisplay(requestId)) {
            RecordingConsentMemory.pending = null
            onDenied(requestId)
            return
        }
        RecordingConsentMemory.pending = PendingRecordingConsent(requestId, RecordingConsentStage.PROJECTION, defaultDisplayOnly = defaultDisplayOnly)
        try {
            val manager = activity.getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException()
            // Only Region/authorized-preview uses default-display geometry. Ordinary and private
            // BASIC retain Android's user-choice consent, including app sharing on API34+.
            // Geometry callbacks do not attest the choice. No private result extras are inspected.
            val intent = if (defaultDisplayOnly && Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        } catch (_: RuntimeException) { abandonFailedLaunch(requestId) }
    }

    private fun abandonFailedLaunch(requestId: String) {
        RecordingConsentMemory.pending = null
        RecordingConsentMemory.notice = "系统授权页面暂时不可用，未开始录制。"
        onDenied(requestId)
    }

    fun cancel() {
        if (closed || !EphemeralRecordingResults.attachedTo(activity)) return
        RecordingConsentMemory.audioPermissionDenied = false
        RecordingConsentMemory.notice = null
        val pending = RecordingConsentMemory.pending ?: return
        if (!pending.cancelled) {
            // Keep a tombstone until the outstanding result drains, so an old grant cannot be
            // misattributed to a new request. The system dialog itself remains user-controlled.
            RecordingConsentMemory.pending = pending.copy(cancelled = true)
            onDenied(pending.requestId)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (EphemeralRecordingResults.attachedTo(activity)) {
            audioLauncher.unregister()
            projectionLauncher.unregister()
            EphemeralRecordingResults.detach(activity)
        }
    }

    companion object {
        /** Always consumes our fixed code, even after process death and before controller creation. */
        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
            EphemeralRecordingResults.activityResult(requestCode, resultCode, data)
        fun dispatchPermissionResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean =
            EphemeralRecordingResults.permissionResult(requestCode, permissions, grantResults)
    }
}
