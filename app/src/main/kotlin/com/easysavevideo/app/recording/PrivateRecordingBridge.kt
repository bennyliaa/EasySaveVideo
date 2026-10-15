package com.easysavevideo.app.recording

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.app.ui.PrivateRecordingAudioChoice
import com.easysavevideo.app.ui.PrivateRecordingPresentationUi
import com.easysavevideo.app.ui.PrivateRecordingStage
import com.easysavevideo.recording.RecordingAudioChoice

/** Implemented by the real Activity window/presentation owner, never by page content. */
internal interface PrivateRecordingPresentationPort : PrivateCaptureSurfacePort {
    fun capabilityAvailable(): Boolean
    fun begin(target: PrivateCaptureTarget, presentationId: Long): Boolean
    /** Returns evidence only after the ARMING layout, old dialogs/IME, and exact owned frame settle. */
    fun requestEvidence(target: PrivateCaptureTarget, owner: Any, scope: PrivateCaptureScope,
        completed: (PrivateCaptureSurfaceEvidence?) -> Unit): Boolean
    fun isCurrent(evidence: PrivateCaptureSurfaceEvidence): Boolean
    fun installProtectionBarrier(): Boolean
    fun end(presentationId: Long)
    /** Attachment-only pixel policy. No recording/native lifetime is owned by this port. */
    fun attachContinuous(authorization: ContinuousRecordingAuthorization): Boolean = false
    fun detachContinuous(): Boolean = installProtectionBarrier()
}

/** Activity setup/attachment only. Accepted BASIC capture belongs to the application runtime. */
internal class PrivateRecordingBridge(
    private val activity: ComponentActivity,
    private val browser: SystemBrowserHost,
    private val runtime: AndroidSaveRuntime,
    private val consent: RecordingConsentController,
    private val surface: PrivateRecordingPresentationPort,
    private val browserRouteCurrent: () -> Boolean,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Owner(val id: Long, val target: PrivateCaptureTarget, val identity: Any = Any()) {
        var binding: PrivateRecordingNativeBinding? = null
        var attempt: PrivateCaptureAttempt? = null
        var continuation: PrivateRecordingContinuation? = null
        var service: Any? = null
        var destinationPending = false
        var grantOffered = false
        var submitted = false
        var audio = PrivateRecordingAudioChoice.SILENT
        var stage = PrivateRecordingStage.CONFIGURE
    }
    private var generation = 0L
    private var owner: Owner? = null
    private var closed = false
    var presentation by mutableStateOf<PrivateRecordingPresentationUi?>(null); private set
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun deliver(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    private fun current(value: Owner) = !closed && owner === value && value.target.session.isLive()
    private fun foregroundTarget(value: Owner) = current(value) && !activity.isDestroyed && !activity.isFinishing &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        activity.hasWindowFocus() && browserRouteCurrent() && browser.isPrivateCaptureTargetCurrent(value.target)
    private var policy: PrivateCaptureExceptionPolicy? = null
    private val observation = runtime.addContinuousRecordingListener { snapshot ->
        if (!closed && owner == null) publishContinuous(snapshot)
    }
    private fun createPolicy() = PrivateCaptureExceptionPolicy(surface, object : PrivateCaptureStopPort {
        override fun stop(ownerIdentity: Any, disposition: PrivateCaptureDisposition, done: () -> Unit) {
            val value = owner?.takeIf { it.identity === ownerIdentity } ?: return
            val binding = value.binding ?: return
            runtime.stopPrivateRecording(binding.descriptor.sessionId, binding, disposition)
            binding.whenDrained { deliver(done) }
        }
        override fun discardUnpublished(ownerIdentity: Any) {
            val value = owner?.takeIf { it.identity === ownerIdentity } ?: return
            value.binding?.let { runtime.stopPrivateRecording(it.descriptor.sessionId, it,
                PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT) }
        }
    }, targetCurrent = browser::isPrivateCaptureTargetCurrent,
        ownerCurrent = { identity -> owner?.let { it.identity === identity && it.binding?.let(runtime::ownsPrivateRecording) == true } == true },
        surfaceCurrent = surface::isCurrent,
        scheduler = object : PrivatePolicyScheduler {
            override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
                val task = Runnable(action); check(main.postDelayed(task, delayMillis))
                return { main.removeCallbacks(task) }
            }
        }, onOwnerThread = ::deliver, changed = { phase ->
            owner?.let { value ->
                when (phase) {
                    PrivateCapturePhase.ARMING -> value.stage = PrivateRecordingStage.ARMING
                    PrivateCapturePhase.ACTIVE -> value.stage = PrivateRecordingStage.RECORDING
                    PrivateCapturePhase.STOPPING -> value.stage = PrivateRecordingStage.STOPPING
                    PrivateCapturePhase.BLOCKED -> value.stage = PrivateRecordingStage.BLOCKED
                    PrivateCapturePhase.IDLE -> { finishPresentation(value); return@let }
                    else -> Unit
                }
                publish(value)
                if (phase == PrivateCapturePhase.ACTIVE) browser.refreshPrivateCaptureAudio()
                else if (phase in setOf(PrivateCapturePhase.STOPPING, PrivateCapturePhase.BLOCKED)) browser.mutePrivateCaptureAudio()
            }
        })

    fun configure(): Boolean {
        requireMain()
        if (closed || owner != null || runtime.continuousRecordingSnapshot?.phase?.terminal == false || !browserRouteCurrent()) return false
        val target = browser.capturePrivateCaptureTarget() ?: return false
        val value = Owner(++generation, target)
        owner = value; policy = createPolicy()
        if (!surface.begin(target, value.id) || !surface.capabilityAvailable())
            value.stage = PrivateRecordingStage.BLOCKED
        publish(value)
        return true
    }
    private fun publish(value: Owner) {
        if (owner !== value) return
        val continueGeneration = value.continuation?.generation
        presentation = PrivateRecordingPresentationUi(value.id, value.stage, value.audio,
            canStart = !value.destinationPending && value.stage == PrivateRecordingStage.CONFIGURE && foregroundTarget(value) && surface.capabilityAvailable(),
            canContinue = value.stage == PrivateRecordingStage.CONTINUE && foregroundTarget(value) && value.grantOffered && value.service != null,
            onStart = { audio -> if (owner === value) start(value, audio) },
            onContinue = { if (owner === value && continueGeneration != null) continueAfterConsent(value, continueGeneration) },
            onStopSave = { if (owner === value) stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) },
            onDiscard = { if (owner === value) stop(value, PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT) },
            onClose = { if (owner === value) stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) })
    }
    private fun start(value: Owner, audio: PrivateRecordingAudioChoice) {
        requireMain()
        if (value.destinationPending || !foregroundTarget(value) || value.stage != PrivateRecordingStage.CONFIGURE || !surface.capabilityAvailable()) return
        val destinations = runtime.destinations ?: return
        val bounds = activity.windowManager.maximumWindowMetrics.bounds
        val density = activity.resources.configuration.densityDpi
        value.audio = audio
        value.destinationPending = true
        publish(value)
        destinations.select(isCurrent = {
            foregroundTarget(value) && value.stage == PrivateRecordingStage.CONFIGURE && value.binding == null &&
                activity.windowManager.maximumWindowMetrics.bounds == bounds && activity.resources.configuration.densityDpi == density
        }, onTerminal = {
            value.destinationPending = false
            if (owner === value) publish(value)
        }) { admission ->
            if (!foregroundTarget(value) || value.binding != null) return@select
            val choice = if (audio == PrivateRecordingAudioChoice.SILENT) RecordingAudioChoice.NONE else RecordingAudioChoice.INTERNAL
            val id = runtime.preparePrivateRecording(choice, bounds.width(), bounds.height(), density, admission,
                createBinding = { descriptor ->
                    runtime.createPrivateRecordingBinding(descriptor, value.identity).also { value.binding = it }
                }, serviceReady = { binding, service ->
                    if (owner === value && value.binding === binding) {
                        value.service = service
                        if (value.continuation?.serviceParked(value.identity, service, now()) != true) stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
                        else { value.stage = PrivateRecordingStage.CONTINUE; publish(value) }
                    }
                })
            if (id == null) { value.stage = PrivateRecordingStage.BLOCKED; publish(value); return@select }
            val scope = if (Build.VERSION.SDK_INT >= 34) PrivateCaptureScope.API34_PLUS_USER_CHOICE
                else PrivateCaptureScope.API31_TO_33_DEFAULT_DISPLAY
            value.continuation = PrivateRecordingContinuation(value.identity, activity, now() + PrivateCaptureExceptionPolicy.CONSENT_WAIT_MILLIS)
            value.attempt = policy?.requestFromExplicitAction(value.target, value.identity, scope, true,
                PrivateCaptureDescriptor(if (audio == PrivateRecordingAudioChoice.SILENT) PrivateCaptureAudio.SILENT else PrivateCaptureAudio.DEVICE_AUDIO,
                    PrivateCaptureMode.BASIC))
            if (value.attempt == null) { stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return@select }
            value.stage = PrivateRecordingStage.WAITING_SYSTEM; publish(value)
            if (!consent.begin(id, choice == RecordingAudioChoice.INTERNAL)) stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        }
    }
    fun grantOffered(id: String) {
        requireMain()
        owner?.takeIf { it.binding?.descriptor?.sessionId == id }?.let {
            it.grantOffered = runtime.hasPendingRecording(id)
            if (!it.grantOffered) stop(it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) else publish(it)
        }
    }
    private fun continueAfterConsent(value: Owner, generation: Long) {
        requireMain()
        val binding = value.binding ?: return
        val continuation = value.continuation ?: return
        val service = value.service ?: return
        val attempt = value.attempt ?: return
        val policy = policy ?: return
        if (!value.grantOffered || !runtime.hasPendingRecording(binding.descriptor.sessionId) ||
            !continuation.confirmContinue(value.identity, activity, generation, foregroundTarget(value), now())) return
        // Switch the non-sensitive controls while still protected. Evidence must be minted only
        // after this ARMING layout really settles; the policy's later ARMING event is then a no-op UI change.
        value.stage = PrivateRecordingStage.ARMING; publish(value)
        if (!policy.projectionAccepted(attempt)) return
        val requested = surface.requestEvidence(value.target, value.identity, attempt.scope) { evidence -> deliver {
            if (owner !== value || value.attempt !== attempt || value.binding !== binding || value.submitted ||
                policy.phase != PrivateCapturePhase.GRANTED_PROTECTED) return@deliver
            if (evidence == null || !foregroundTarget(value) || !runtime.hasPendingRecording(binding.descriptor.sessionId)) {
                stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return@deliver
            }
            val permit = policy.openForSurface(attempt, evidence) ?: return@deliver
            if (!binding.arm(permit) || !continuation.claimSubmission(value.identity, activity, service, foregroundTarget(value), now()) ||
                !runtime.startPrivateRecording(binding.descriptor.sessionId, binding, service)) {
                stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return@deliver
            }
            value.submitted = true
            // Accepted submission is the boundary, not a later native-start observation. The
            // callback-free permit remains native-owned; every target/Activity setup chain ends.
            check(policy.retireAfterSubmission(attempt))
            value.attempt = null; value.continuation = null; value.service = null; value.binding = null
            this.policy = null; owner = null
            surface.end(value.id)
            publishContinuous(runtime.continuousRecordingSnapshot)
        } }
        if (!requested) stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
    }
    /** Always shield. Suspension never grants permission, and never applies to navigation/tab exit. */
    fun protectForLifecycle(): Boolean {
        requireMain()
        val protected = surface.installProtectionBarrier()
        val muted = browser.mutePrivateCaptureAudio()
        val value = owner ?: return protected && muted
        val binding = value.binding
        val preStart = binding != null && !value.submitted && !binding.isArmed &&
            policy?.phase == PrivateCapturePhase.CONSENT_PENDING &&
            (consent.pendingForExactRequest(binding.descriptor.sessionId) != null || value.grantOffered)
        if (preStart && value.continuation?.suspendProtected(value.identity, activity) == true) {
            if (value.service != null) value.stage = PrivateRecordingStage.CONTINUE
            publish(value)
        } else stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        return protected && muted
    }
    fun protectForPresentationTransition(): Boolean {
        requireMain(); val protected = surface.installProtectionBarrier()
        val muted = browser.mutePrivateCaptureAudio()
        owner?.let { stop(it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) }
        return protected && muted
    }
    /** Capture-source release only; this does not prove output/task completion or window protection. */
    internal val captureSourceDrained: Boolean get() { requireMain(); return runtime.recordingSourceObligationsDrained() }
    internal val hasSetupOwner: Boolean get() { requireMain(); return owner != null }
    internal val hasPresentationOwner: Boolean get() { requireMain(); return owner != null || runtime.continuousRecordingSnapshot != null }
    /** Window readbacks still independently require secure/cover; STOPPING is never an audio permit. */
    internal val captureExceptionInactive: Boolean get() {
        requireMain(); return runtime.continuousRecordingAuthorization == null &&
            policy?.phase !in setOf(PrivateCapturePhase.ARMING, PrivateCapturePhase.ACTIVE)
    }
    internal fun requestAbandoned(id: String) {
        requireMain()
        owner?.takeIf { it.binding?.descriptor?.sessionId == id }?.let {
            stop(it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        }
    }
    /** Exact memory-only owner projection; no new grant, execution or release authority. */
    internal fun readCaptureWindowOwner(
        target: PrivateCaptureTarget,
        presentationId: Long,
        ownerIdentity: Any,
    ): PrivateCaptureWindowOwnerReadback? {
        requireMain()
        if (closed) return null
        val value = owner ?: return null
        if (value.id != presentationId || value.identity !== ownerIdentity || value.target !== target) return null
        val binding = value.binding ?: return null
        if (!runtime.ownsPrivateRecording(binding)) return null
        val attempt = value.attempt ?: return null
        if (attempt.target !== target || attempt.ownerIdentity !== ownerIdentity) return null
        return PrivateCaptureWindowOwnerReadback(attempt.descriptor,
            value.grantOffered && runtime.hasPendingRecording(binding.descriptor.sessionId),
            value.submitted || runtime.isPrivateRecordingSubmitted(binding))
    }
    internal fun invalidateCaptureWindowOwner(ownerIdentity: Any) {
        requireMain()
        owner?.takeIf { it.identity === ownerIdentity && !it.submitted }?.let { value ->
            // Missing/throwing owner readback is not proof of an unsubmitted native owner.
            val binding = value.binding
            if (binding != null && runtime.canAbandonPrivateRecordingSetup(binding))
                stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        }
    }
    fun refreshPresentation() {
        requireMain(); owner?.let(::publish) ?: publishContinuous(runtime.continuousRecordingSnapshot)
    }
    fun allowsVisibleAudio(view: android.webkit.WebView): Boolean =
        runtime.continuousRecordingAuthorization != null && browser.selectedView() === view

    private fun publishContinuous(snapshot: ContinuousRecordingSnapshot?) {
        if (closed || owner != null) return
        if (snapshot == null) { presentation = null; return }
        val authorization = snapshot.authorization
        val stage = when (snapshot.phase) {
            ContinuousRecordingPhase.ARMING -> PrivateRecordingStage.ARMING
            ContinuousRecordingPhase.RECORDING -> PrivateRecordingStage.RECORDING
            ContinuousRecordingPhase.STOPPING -> PrivateRecordingStage.STOPPING
            ContinuousRecordingPhase.VERIFYING -> PrivateRecordingStage.VERIFYING
            ContinuousRecordingPhase.COMMITTING -> PrivateRecordingStage.COMMITTING
            ContinuousRecordingPhase.BLOCKED -> PrivateRecordingStage.BLOCKED
            ContinuousRecordingPhase.SUCCEEDED -> PrivateRecordingStage.SUCCEEDED
            ContinuousRecordingPhase.FAILED -> PrivateRecordingStage.FAILED
            ContinuousRecordingPhase.CANCELLED -> PrivateRecordingStage.CANCELLED
        }
        fun exact() = !closed && runtime.continuousRecordingSnapshot?.authorization === authorization
        presentation = PrivateRecordingPresentationUi(-authorization.generation, stage,
            if (authorization.descriptor.audio == PrivateCaptureAudio.SILENT) PrivateRecordingAudioChoice.SILENT
                else PrivateRecordingAudioChoice.DEVICE_AUDIO,
            canStart = false, canContinue = false, onStart = {}, onContinue = {},
            onStopSave = { if (exact()) runtime.stopRecording(authorization.sessionId) },
            onDiscard = { if (exact()) runtime.cancel(authorization.sessionId) },
            onClose = { if (exact() && snapshot.phase.terminal) runtime.dismissContinuousRecording(authorization) },
            canDiscard = snapshot.canDiscard)
    }
    private fun stop(value: Owner, disposition: PrivateCaptureDisposition) {
        requireMain()
        if (owner !== value) return
        value.continuation?.stop()
        val attempt = value.attempt
        if (attempt != null) policy?.revoke(attempt, disposition)
        else {
            surface.installProtectionBarrier()
            value.binding?.let { runtime.stopPrivateRecording(it.descriptor.sessionId, it, disposition) }
            if (value.binding == null || value.binding?.isCaptureDrained == true) finishPresentation(value)
        }
    }
    private fun finishPresentation(value: Owner) {
        if (owner !== value) return
        owner = null; presentation = null; policy = null
        value.attempt = null; value.continuation = null; value.service = null; value.binding = null
        surface.end(value.id)
        publishContinuous(runtime.continuousRecordingSnapshot)
    }
    override fun close() {
        requireMain()
        if (closed) return
        owner?.let { value ->
            value.binding?.let { consent.cancelExactRequest(it.descriptor.sessionId) }
            stop(value, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        }
        observation.close()
        closed = true
        presentation = null; policy = null
        surface.detachContinuous()
    }
}
