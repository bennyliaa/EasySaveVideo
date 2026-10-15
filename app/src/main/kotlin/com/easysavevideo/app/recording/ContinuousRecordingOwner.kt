package com.easysavevideo.app.recording

import com.easysavevideo.app.browser.privacy.PrivateCaptureAudio
import com.easysavevideo.app.browser.privacy.PrivateCaptureDescriptor
import com.easysavevideo.app.browser.privacy.PrivateCaptureDisposition
import com.easysavevideo.app.browser.privacy.PrivateCaptureMode
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingSnapshot
import com.easysavevideo.recording.RecordingState
import com.easysavevideo.recording.RecordingAudioChoice

/** Runtime-issued identity, never reconstructible from saved state, copied fields or a browser target. */
internal class ContinuousRecordingAuthorization internal constructor(
    val sessionId: String,
    val generation: Long,
    val ownerIdentity: Any,
    val descriptor: PrivateCaptureDescriptor,
) {
    override fun toString() = "ContinuousRecordingAuthorization([redacted])"
}
internal enum class ContinuousRecordingPhase {
    ARMING, RECORDING, STOPPING, VERIFYING, COMMITTING, BLOCKED, SUCCEEDED, FAILED, CANCELLED;
    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}
internal data class ContinuousRecordingSnapshot(
    val authorization: ContinuousRecordingAuthorization,
    val phase: ContinuousRecordingPhase,
    val nativeDrained: Boolean,
    val canDiscard: Boolean,
)

/** Callback-free owner state shared by ordinary and private BASIC. No UI/profile/source lease. */
internal class ContinuousRecordingOwner(val descriptor: CaptureDescriptor, ownerIdentity: Any) {
    val authorization = ContinuousRecordingAuthorization(descriptor.sessionId, descriptor.generation,
        ownerIdentity, PrivateCaptureDescriptor(
            if (descriptor.audioChoice == RecordingAudioChoice.NONE) PrivateCaptureAudio.SILENT else PrivateCaptureAudio.DEVICE_AUDIO,
            PrivateCaptureMode.BASIC))
    @Volatile var submitted = false
    @Volatile var nativeStarted = false
    @Volatile var nativeDrained = false
    @Volatile var releaseUnconfirmed = false
    @Volatile var terminal: ContinuousRecordingPhase? = null
    @Volatile private var disposition: PrivateCaptureDisposition? = null
    val stopRequested: Boolean get() = disposition != null
    val discardRequested: Boolean get() = disposition == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT
    @Synchronized fun requestStop(value: PrivateCaptureDisposition): Boolean {
        if (terminal != null) return false
        if (disposition == null || value == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT) disposition = value
        return true
    }
    /** Native system/budget/failure stops are real Stop, even before any release acknowledgement. */
    fun observeNativeSnapshot(snapshot: RecordingSnapshot) {
        if (snapshot.request?.descriptor != descriptor) return
        if (snapshot.state in setOf(RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING))
            nativeStarted = true
        if (snapshot.state in setOf(RecordingState.STOPPING, RecordingState.FINALIZING,
                RecordingState.CANCELLING, RecordingState.CAPTURE_FINISHED, RecordingState.CANCELLED,
                RecordingState.FAILED, RecordingState.RECOVERY_REQUIRED))
            requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        if (snapshot.state == RecordingState.RECOVERY_REQUIRED) releaseUnconfirmed = true
        // Snapshot/UI phase does not prove native release. The real owner/lease supplies that.
    }
    fun snapshot(outputPhase: ContinuousRecordingPhase? = null): ContinuousRecordingSnapshot {
        if (outputPhase?.terminal == true) terminal = outputPhase
        val phase = when {
            releaseUnconfirmed -> ContinuousRecordingPhase.BLOCKED
            terminal != null -> requireNotNull(terminal)
            outputPhase != null -> outputPhase
            stopRequested || nativeDrained -> ContinuousRecordingPhase.STOPPING
            nativeStarted -> ContinuousRecordingPhase.RECORDING
            else -> ContinuousRecordingPhase.ARMING
        }
        return ContinuousRecordingSnapshot(authorization, phase, nativeDrained, terminal == null)
    }
    /** No application-added screen-off stop after this exact BASIC session was submitted. */
    fun ignoresScreenOff(sessionId: String): Boolean =
        submitted && descriptor.regionCaptureRecipe == null && descriptor.sessionId == sessionId
    /** Secure browsing compatibility only; conveys no pixel, audio or release authority. */
    fun isIndependentSubmitted(snapshot: ContinuousRecordingSnapshot?): Boolean =
        submitted && snapshot?.authorization === authorization
    fun authorizes(value: ContinuousRecordingAuthorization): Boolean = authorization === value &&
        value.sessionId == descriptor.sessionId && value.generation == descriptor.generation && submitted &&
        !stopRequested && !nativeDrained && !releaseUnconfirmed && terminal == null
}
