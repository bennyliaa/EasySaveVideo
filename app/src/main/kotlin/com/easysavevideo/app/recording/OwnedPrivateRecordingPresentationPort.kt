package com.easysavevideo.app.recording

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.app.ui.PrivateRecordingAudioChoice
import com.easysavevideo.app.ui.PrivateRecordingPresentationUi
import com.easysavevideo.app.ui.PrivateRecordingStage

/** Exact bridge owner readback. Never includes a grant, URL, filename or output destination. */
internal data class PrivateCaptureWindowOwnerReadback(
    val descriptor: PrivateCaptureDescriptor, val grantPending: Boolean, val submitted: Boolean,
)

/**
 * One owned Activity/root attachment. Setup evidence is short lived and is released at submission.
 * A live runtime authorization follows normal presentation/input claims across routes and profiles.
 * Local protection failures never become native Stop commands or invented native-drain evidence.
 */
internal class OwnedPrivateRecordingPresentationPort(
    private val activity: ComponentActivity,
    private val root: PrivateContentRoot,
    private val guard: PrivateWindowGuard,
    private val browser: SystemBrowserHost,
    private val browserRouteCurrent: () -> Boolean,
    private val routeIdentity: () -> Any,
    private val readOwner: (PrivateCaptureTarget, Long, Any) -> PrivateCaptureWindowOwnerReadback?,
    private val invalidateOwner: (Any) -> Unit,
    private val isContinuousAuthorized: (ContinuousRecordingAuthorization) -> Boolean = { false },
    private val interactionPolicy: PrivateCaptureInteractionPolicy =
        PrivateCaptureInteractionPolicy.CONTINUOUS_USER_AUTHORIZED,
) : PrivateRecordingPresentationPort, AutoCloseable {
    private val window = activity.window
    private val decor = window.decorView
    private class Presentation(val target: PrivateCaptureTarget, val id: Long, val route: Any) {
        val identity = Any()
        val state = PrivateCaptureWindowState()
        var owner: Any? = null
        var descriptor: PrivateCaptureDescriptor? = null
        var completed: ((PrivateCaptureSurfaceEvidence?) -> Unit)? = null
        var draw: PrivateCaptureWindowDraw? = null
        var evidence: PrivateCaptureSurfaceEvidence? = null
        var capability: PrivateWindowGuard.CaptureCapability? = null
        var snapshot: PrivateCaptureWindowSnapshot? = null
        var stopRequested = false
        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    }
    private var current: Presentation? = null
    private var continuous: ContinuousRecordingAuthorization? = null

    override fun attachContinuous(authorization: ContinuousRecordingAuthorization): Boolean {
        requireMain()
        if (closed || !safe { isContinuousAuthorized(authorization) }) return false
        // Submission must first retire setup identity; never retain an initial profile or WebView.
        current?.let { end(it.id) }
        if (!guard.attachContinuousCapture(authorization, isContinuousAuthorized)) return false
        continuous = authorization
        return true
    }
    override fun detachContinuous(): Boolean {
        requireMain()
        continuous = null
        return guard.detachContinuousCapture()
    }
    private var closed = false
    private var observerCleanupFailed = false
    private var layoutGeneration = 0L
    /** Normal secure-browsing reveal must not replace this adapter's exact ARMING/retiring frame. */
    val ownsCapturePresentation get() = current?.state?.hasRequest == true
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun safe(check: () -> Boolean) = try { check() } catch (_: Exception) { false }
    private fun targetCurrent(value: Presentation): Boolean = safe { !closed && current === value &&
        activity.window === window && window.decorView === decor && guard.ownsCaptureRoot(activity, root) &&
        !activity.isDestroyed && !activity.isFinishing &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && decor.hasWindowFocus() &&
        safe(browserRouteCurrent) && safe { routeIdentity() === value.route } && value.target.session.isLive() &&
        browser.isPrivateCaptureTargetCurrent(value.target)
    }
    private fun ownerCurrent(value: Presentation): Boolean {
        if (!targetCurrent(value) || value.state.isInvalidated) return false
        val owner = value.owner ?: return false
        val expected = value.descriptor ?: return false
        val actual = try { readOwner(value.target, value.id, owner) } catch (_: Exception) { null } ?: return false
        return privateCaptureOwnerReadbackAllowed(expected, actual.descriptor, actual.grantPending,
            actual.submitted, value.state.opened, foregroundAndTargetCurrent = targetCurrent(value))
    }
    override fun capabilityAvailable(): Boolean {
        requireMain()
        return !closed && !observerCleanupFailed && Build.VERSION.SDK_INT >= 31 &&
            interactionPolicy == PrivateCaptureInteractionPolicy.CONTINUOUS_USER_AUTHORIZED &&
            safe(browserRouteCurrent) && safe { guard.ownsCaptureRoot(activity, root) } && safe(guard::captureCapabilityAvailable)
    }
    override fun begin(target: PrivateCaptureTarget, presentationId: Long): Boolean {
        requireMain()
        if (closed || observerCleanupFailed || current != null || target.viewIdentity !is WebView) return false
        val route = try { routeIdentity() } catch (_: Exception) { return false }
        val value = Presentation(target, presentationId, route)
        current = value
        if (!targetCurrent(value)) { current = null; return false }
        return true // No flag mutation, capability or frame proof is issued here.
    }
    override fun requestEvidence(target: PrivateCaptureTarget, owner: Any, scope: PrivateCaptureScope,
        completed: (PrivateCaptureSurfaceEvidence?) -> Unit): Boolean {
        requireMain()
        val value = current ?: return false
        val expectedScope = if (Build.VERSION.SDK_INT >= 34) PrivateCaptureScope.API34_PLUS_USER_CHOICE
            else PrivateCaptureScope.API31_TO_33_DEFAULT_DISPLAY
        if (value.target !== target || value.state.hasRequest || !capabilityAvailable() || scope != expectedScope) return false
        val readback = try { readOwner(target, value.id, owner) } catch (_: Exception) { null } ?: return false
        if (!privateCaptureOwnerReadbackAllowed(readback.descriptor, readback.descriptor,
                readback.grantPending, readback.submitted, opened = false, foregroundAndTargetCurrent = targetCurrent(value))) return false
        value.owner = owner; value.descriptor = readback.descriptor; value.completed = completed
        if (!value.state.request(target, owner, value.id, readback.descriptor)) return false
        // All independent protection effects run before observing the new composition/frame.
        if (!protectIndependently() || !ownerCurrent(value)) { invalidate(value); return false }
        val view = target.viewIdentity as? WebView ?: run { invalidate(value); return false }
        val observed = try {
            guard.observeCaptureFrame(value.identity, view, target.documentIdentity, value.identity, ++layoutGeneration,
                stillOwned = { ownerCurrent(value) },
                drawn = { snapshot ->
                    if (current === value && !value.state.isInvalidated) {
                        value.draw = value.state.drawn(snapshot)
                        if (value.draw == null) invalidate(value)
                    }
                },
                committed = { capability, snapshot ->
                    if (current === value && !value.state.isInvalidated) {
                        val draw = value.draw
                        val ticket = draw?.let { value.state.committed(it, snapshot) }
                        if (ticket == null || !ownerCurrent(value)) invalidate(value)
                        else {
                            val evidence = PrivateCaptureSurfaceEvidence(target, owner, capability, scope,
                                if (Build.VERSION.SDK_INT >= 33) PrivateCaptureRecentsProtection.API33_RECENTS_SUPPRESSED
                                else PrivateCaptureRecentsProtection.LEGACY_RECENTS_RISK_ACCEPTED)
                            if (!value.state.register(ticket, evidence)) invalidate(value)
                            else {
                                value.snapshot = snapshot; value.capability = capability; value.evidence = evidence
                                val callback = value.completed; value.completed = null
                                try { callback?.invoke(evidence) } catch (_: Exception) { invalidate(value) }
                            }
                        }
                    }
                }, invalidated = { invalidate(value) })
        } catch (_: Exception) { false }
        if (!observed || !installObservers(value, view)) { invalidate(value); return false }
        root.invalidate()
        return true
    }
    /** Only the actual owning ARMING SideEffect may call this; UI state publication alone is insufficient. */
    fun compositionApplied(ui: PrivateRecordingPresentationUi?) {
        requireMain()
        val value = current?.takeIf { it.state.hasRequest && !it.state.isInvalidated } ?: return
        val descriptor = value.descriptor ?: return
        if (ui == null || ui.presentationId != value.id ||
            (ui.audioChoice == PrivateRecordingAudioChoice.SILENT) != (descriptor.audio == PrivateCaptureAudio.SILENT)) {
            invalidate(value); return
        }
        if (value.state.opened) {
            if (ui.stage !in setOf(PrivateRecordingStage.ARMING, PrivateRecordingStage.RECORDING)) invalidate(value)
            else checkObserved(value)
            return
        }
        if (ui.stage != PrivateRecordingStage.ARMING || !ownerCurrent(value) ||
            !value.state.acknowledgeComposition(value.id, descriptor) ||
            !safe { guard.acknowledgeCaptureComposition(value.identity) }) { invalidate(value); return }
        root.invalidate()
    }
    private fun checkObserved(value: Presentation): Boolean {
        if (current !== value || value.state.isInvalidated) return false
        val snapshot = try { guard.readCaptureSnapshot(value.identity) } catch (_: Exception) { null }
        if (!ownerCurrent(value) || snapshot == null || value.snapshot?.sameAs(snapshot) == false ||
            value.draw?.snapshot?.sameAs(snapshot) == false) {
            invalidate(value); return false
        }
        return true
    }
    override fun isCurrent(evidence: PrivateCaptureSurfaceEvidence): Boolean {
        requireMain()
        val value = current ?: return false
        if (value.evidence !== evidence || value.capability !== evidence.surfaceIdentity || !checkObserved(value)) return false
        val snapshot = try { guard.readCaptureSnapshot(value.identity) } catch (_: Exception) { null } ?: return false
        return value.state.isCurrent(evidence, evidence.target, evidence.ownerIdentity, snapshot)
    }
    override fun openException(evidence: PrivateCaptureSurfaceEvidence, stillAllowed: () -> Boolean): Boolean {
        requireMain()
        val value = current ?: return false
        if (!isCurrent(evidence)) return false
        val capability = value.capability ?: return false
        val snapshot = guard.readCaptureSnapshot(value.identity) ?: return false
        if (!value.state.consume(evidence, evidence.target, evidence.ownerIdentity, snapshot)) return false
        val opened = try { guard.openCaptureException(capability) {
            current === value && ownerCurrent(value) && safe(stillAllowed)
        } } catch (_: Exception) { false }
        if (!opened || !value.state.opened(evidence)) { invalidate(value); return false }
        return true
    }
    private fun protectIndependently() = restorePrivateCaptureProtection(
        guard::installProtectionBarrier, root::revokeCaptureInput, browser::mutePrivateCaptureAudio)
    override fun installProtectionBarrier(): Boolean {
        requireMain()
        current?.takeIf { it.state.hasRequest && !it.state.isInvalidated }?.let(::invalidate)
        return protectIndependently()
    }
    override fun restoreProtection(target: PrivateCaptureTarget, protected: () -> Unit) {
        requireMain()
        val value = current?.takeIf { it.target === target } ?: return
        value.state.invalidate(); value.evidence = null; value.capability = null
        guard.forgetCaptureObservation(value.identity)
        retireObservers(value)
        if (!protectIndependently()) return
        // No acknowledgement on detach, close, timeout, completed Future or destroyed Activity.
        // Repeated safety barriers cannot lose the exact retiring root's independent obligation.
        guard.afterOwnedCaptureProtectionSubmitted(value.identity) {
            if (current === value && root.isCovered && guard.lifecycleReadiness().secureRequested) protected()
        }
    }
    private fun invalidate(value: Presentation) {
        if (current !== value) return
        value.state.invalidate(); value.evidence = null; value.capability = null
        guard.forgetCaptureObservation(value.identity)
        retireObservers(value)
        protectIndependently()
        // Only a never-submitted setup may be invalidated by its initial target/window.
        val owner = value.owner
        val submitted = owner?.let { try { readOwner(value.target, value.id, it)?.submitted } catch (_: Exception) { null } }
        if (owner != null && privateCaptureSetupMayCancel(submitted) && !value.stopRequested) {
            value.stopRequested = true
            try { invalidateOwner(owner) } catch (_: Exception) { /* Never acknowledge drain. */ }
        }
        val callback = value.completed; value.completed = null
        try { callback?.invoke(null) } catch (_: Exception) { }
    }
    private fun installObservers(value: Presentation, view: View): Boolean = try {
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) false else {
            value.observer = observer
            value.preDraw = ViewTreeObserver.OnPreDrawListener {
                if (current !== value || value.state.isInvalidated) true else checkObserved(value)
            }.also(observer::addOnPreDrawListener)
            true
        }
    } catch (_: Exception) { observerCleanupFailed = true; false }
    private fun retireObservers(value: Presentation) {
        val observer = value.observer; value.observer = null
        value.preDraw?.let {
            if (observer?.isAlive == true && !safe { observer.removeOnPreDrawListener(it); true }) observerCleanupFailed = true
        }
        value.preDraw = null
    }
    override fun end(presentationId: Long) {
        requireMain()
        val value = current?.takeIf { it.id == presentationId } ?: return
        value.state.invalidate(); guard.forgetCaptureObservation(value.identity); retireObservers(value)
        guard.forgetCaptureRetirement(value.identity)
        protectIndependently()
        current = null
    }
    override fun close() {
        requireMain()
        if (closed) return
        current?.let(::invalidate)
        current?.let { end(it.id) }
        detachContinuous()
        closed = true
        protectIndependently()
    }
}
