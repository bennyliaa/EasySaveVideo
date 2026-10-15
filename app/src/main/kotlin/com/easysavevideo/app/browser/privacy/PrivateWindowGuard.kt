package com.easysavevideo.app.browser.privacy

import com.easysavevideo.app.recording.ContinuousRecordingAuthorization
import android.app.ActivityManager
import android.graphics.Rect
import android.view.WindowInsets
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.contentcapture.ContentCaptureManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner

internal enum class PrivateContentCaptureCapability { DISABLED_BY_ACTIVITY, FACILITY_ABSENT, UNAVAILABLE }
internal enum class PrivateRecentsCapability { API33_SUPPRESSION_REQUESTED, LEGACY_EVIDENCE_UNAVAILABLE, UNAVAILABLE }
internal enum class PrivateWindowPendingGate {
    HOST_INPUT_PREDICATE, RETAINED_INPUT_CONNECTION, DIALOG_POPUP_REGISTRY,
    NATIVE_PRESENTATIONS, ANDROID_DEVICE_VALIDATION, CAPTURE_POLICY_ADAPTER,
}
internal data class PrivateWindowReadiness(
    val ownedRootAttached: Boolean,
    val secureRequested: Boolean,
    val viewTreeOwnersInstalled: Boolean,
    val structureExcluded: Boolean,
    val contentCapture: PrivateContentCaptureCapability,
    val recents: PrivateRecentsCapability,
    val coverFrameSubmitted: Boolean,
    val resumedAndFocused: Boolean,
    val pending: Set<PrivateWindowPendingGate>,
    val continuousCaptureAuthorized: Boolean = false,
) {
    /** Installation readiness stays true while the owned root is deliberately covered. */
    val ready: Boolean get() = pending.all { it == PrivateWindowPendingGate.CAPTURE_POLICY_ADAPTER } &&
        ownedRootAttached && viewTreeOwnersInstalled && (secureRequested || continuousCaptureAuthorized) && structureExcluded &&
        contentCapture != PrivateContentCaptureCapability.UNAVAILABLE &&
        recents != PrivateRecentsCapability.UNAVAILABLE
}

/** Main-owner claim. All callbacks are short, read-only and evaluated on the main thread. */
internal class PrivateWindowPresentation(
    val target: Any,
    val presentation: Any,
    val contentView: View?,
    val expectedParent: ViewGroup?,
    val isCurrent: () -> Boolean,
    val compositionIsCurrent: () -> Boolean,
    val captureExceptionInactive: () -> Boolean,
) {
    override fun toString() = "PrivateWindowPresentation([redacted])"
}

/** Rechecks departure ownership; memory-only private sessions may survive ordinary display. */
internal class PrivateOrdinaryPresentationProof(
    val target: Any,
    val presentation: Any,
    val readback: () -> PrivateOrdinaryDepartureReadback,
)

/** Exact finite app-dialog registration. It is not a provider-popup/window scanner. */
internal class PrivateDialogRegistration internal constructor(
    internal val owner: PrivateWindowGuard,
    val guard: PrivateWindowGuard,
    internal val request: Any,
    internal val requestIsCurrent: () -> Boolean,
    internal val onOwnerClosed: () -> Unit,
    internal val teardownIsComplete: () -> Boolean,
)

/**
 * Owns one exact app Window/Decor/root. A parent guard may register a finite set of app dialogs.
 * Normal private reveal never clears secure or creates capture evidence. Provider/native surfaces
 * and Android acceptance remain independent pending gates; capture additionally needs exact one-use evidence.
 */
internal class PrivateWindowGuard(
    private val activity: ComponentActivity,
    private val root: PrivateContentRoot,
    ordinaryContentCaptureOptIn: Boolean,
    private val ownedDialogWindow: Window? = null,
    revokeInput: (Long) -> Unit,
) : AutoCloseable {
    private val window = ownedDialogWindow ?: activity.window
    private val decor = window.decorView
    private val content = checkNotNull(window.findViewById<ViewGroup>(android.R.id.content))
    private data class ViewImportance(val view: View, val autofill: Int, val capture: Int)
    private val ordinaryImportance = listOf(decor, content, root).map {
        ViewImportance(it, it.importantForAutofill, it.importantForContentCapture)
    }
    private val ordinarySecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    // This lookup is capability discovery; never infer app opt-in from its effective getter.
    private val captureLookup = runCatching { activity.getSystemService(ContentCaptureManager::class.java) }
    private val captureManager = captureLookup.getOrNull()
    private val captureSetting = PrivateContentCaptureSetting(ordinaryContentCaptureOptIn)
    private var captureCapability = PrivateContentCaptureCapability.UNAVAILABLE
    private var recentsCapability = PrivateRecentsCapability.UNAVAILABLE
    private val startupTarget = Any()
    private val startupPresentation = Any()
    private var targetIdentity: Any = startupTarget
    private var presentationIdentity: Any = startupPresentation
    private var privateContentReserved = false
    private var ordinaryReleased = false
    private var ordinaryCurrent: (() -> Boolean)? = null
    private var disposed = false
    private var closing = false
    private var rootClosed = false
    private var observerRemoved = false
    private var protectionFailed = false
    private var visibleClaim: PrivateWindowPresentation? = null
    // Identity-only authorization. The predicate is UI-attachment-owned and cleared on retirement.
    private var continuousAuthorization: ContinuousRecordingAuthorization? = null
    private var continuousAuthorizationCurrent: ((ContinuousRecordingAuthorization) -> Boolean)? = null
    val hasContinuousCaptureAuthorization: Boolean get() = continuousAuthorization?.let { value ->
        !disposed && try { continuousAuthorizationCurrent?.invoke(value) == true } catch (_: Exception) { false }
    } == true

    private fun continuousAttachmentHealthy(): Boolean = safeCheck {
        privateCaptureAttachmentHealthy(owned = ownedIdentity() != null, disposed = disposed,
            protectionFailed = protectionFailed, localBarrierHealthy = root.localBarrierHealthy)
    }

    fun attachContinuousCapture(value: ContinuousRecordingAuthorization,
        isCurrent: (ContinuousRecordingAuthorization) -> Boolean): Boolean {
        requireMain()
        if (disposed || !safeCheck { isCurrent(value) }) return false
        val sameAuthorization = continuousAuthorization === value && hasContinuousCaptureAuthorization
        if (!sameAuthorization) {
            if (!continuousAttachmentHealthy()) return false
            continuousAuthorization = value; continuousAuthorizationCurrent = isCurrent
        }
        // Every own/child action is independently caught. A thrown child cannot starve siblings,
        // and replay of the same identity must not launder an earlier local restoration failure.
        val actions: List<() -> Boolean> = listOf({
            if (!sameAuthorization && privateContentReserved) installProtectionBarrier()
            else continuousAttachmentHealthy()
        }) + dialogs.toList().map { registration ->
            { registration.guard.attachContinuousCapture(value, isCurrent) }
        }
        val restored = restorePrivateCaptureProtection(*actions.toTypedArray())
        protectionFailed = privateCaptureProtectionFailure(protectionFailed, restored)
        if (!sameAuthorization) attempt { onOwnedHierarchyChanged?.invoke() }
        return restored && continuousAttachmentHealthy()
    }
    /** Restores local pixels independently. It never substitutes for actual native drain. */
    fun detachContinuousCapture(): Boolean {
        requireMain()
        val hadAuthorization = continuousAuthorization != null
        continuousAuthorization = null; continuousAuthorizationCurrent = null
        val actions: List<() -> Boolean> = listOf({
            if (hadAuthorization && privateContentReserved) installProtectionBarrier()
            else continuousAttachmentHealthy()
        }) + dialogs.toList().map { registration ->
            { registration.guard.detachContinuousCapture() }
        }
        val restored = restorePrivateCaptureProtection(*actions.toTypedArray())
        protectionFailed = privateCaptureProtectionFailure(protectionFailed, restored)
        if (hadAuthorization) attempt { onOwnedHierarchyChanged?.invoke() }
        return restored && continuousAttachmentHealthy()
    }
    private var pendingGates = PrivateWindowPendingGate.entries.toSet()
    private val dialogs = mutableSetOf<PrivateDialogRegistration>()
    /** A constructor is not authority: only the exact instance retained by this guard is accepted. */
    internal class CaptureCapability internal constructor(
        internal val owner: Any, internal val turn: PrivateProtectionTurn,
        internal val snapshot: PrivateCaptureWindowSnapshot,
    ) { override fun toString() = "CaptureCapability([redacted])" }
    private class CaptureObservation(
        val owner: Any, val turn: PrivateProtectionTurn, val view: View, val document: Any,
        val presentation: Any, val layoutGeneration: Long, val stillOwned: () -> Boolean,
        val drawn: (PrivateCaptureWindowSnapshot) -> Unit,
        val committed: (CaptureCapability, PrivateCaptureWindowSnapshot) -> Unit,
        val invalidated: () -> Unit,
    ) {
        var applied = false
        var snapshot: PrivateCaptureWindowSnapshot? = null
        var capability: CaptureCapability? = null
        var consumed = false
        var opened = false
    }
    private var captureObservation: CaptureObservation? = null
    private class CaptureRetirement(val owner: Any, val identity: PrivateRootIdentity, val protected: () -> Unit)
    private var captureRetirement: CaptureRetirement? = null
    /** Installed capability, independent of ordinary restoration or a healthy covered transition. */
    val browsingIntegrationInstalled get() = !disposed && !protectionFailed && root.localBarrierHealthy &&
        pendingGates.all { it == PrivateWindowPendingGate.CAPTURE_POLICY_ADAPTER }
    val inputLifetime get() = root.inputLifetime
    val inputGeneration get() = root.inputGeneration
    val hasRegisteredPrivateDialogs get() = dialogs.isNotEmpty()
    /** Real successful teardown may unblock a child composition; this is never an acknowledgement. */
    var onOwnedHierarchyChanged: (() -> Unit)? = null
    private var drawWaiter: Pair<PrivateProtectionTurn, () -> Unit>? = null
    private var frameWaiter: Pair<PrivateProtectionTurn, () -> Unit>? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close()
        else if (privateContentReserved && (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP))
            installProtectionBarrier()
    }

    init {
        requireMain()
        root.bindOwner(::ownedIdentity, revokeInput, {
            releaseInitialOrdinaryIfSafe()
            val waiting = drawWaiter
            if (waiting != null && root.currentProtectionTurn === waiting.first &&
                ownedIdentity()?.sameAs(waiting.first.identity) == true && root.hasDrawnCover) {
                drawWaiter = null
                waiting.second()
            }
        }, coverSubmitting = { turn -> captureDrawSubmitting(turn) }) { turn ->
            captureFrameCommitted(turn)
            val retiring = captureRetirement
            if (retiring != null && retiring.identity.sameAs(turn.identity) &&
                ownedIdentity()?.sameAs(turn.identity) == true && root.currentProtectionTurn === turn &&
                root.hasCommittedCoverFrame && root.isCovered && secureRequested()) {
                captureRetirement = null
                retiring.protected()
            }
            val waiting = frameWaiter
            if (waiting?.first === turn && ownedIdentity()?.sameAs(turn.identity) == true && secureRequested()) {
                frameWaiter = null
                waiting.second()
            }
        }
        installProtectionBarrier()
        activity.lifecycle.addObserver(lifecycleObserver)
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun ownedIdentity(): PrivateRootIdentity? {
        if (disposed || activity.isDestroyed || (ownedDialogWindow == null && activity.window !== window) || window.decorView !== decor ||
            window.findViewById<View>(android.R.id.content) !== content || root.parent !== content ||
            content.childCount != 1 || content.getChildAt(0) !== root) return null
        return PrivateRootIdentity(window, decor, root, targetIdentity, presentationIdentity)
    }
    private fun secureRequested() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun attempt(action: () -> Unit): Boolean = try { action(); true } catch (_: Exception) { false }
    private fun structureExcluded() = root.structureSuppressed && ordinaryImportance.all {
        it.view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
            it.view.importantForContentCapture == View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    /**
     * True means a local secure request and sticky native drawing/input barrier were installed on
     * this main-thread turn. Hardware frame submission may still be pending. This does NOT stop
     * capture; the future integration must separately revoke the policy/native media owner.
     */
    fun installProtectionBarrier(): Boolean = installBarrier(ordinaryDeparture = false)
    private fun installBarrier(ordinaryDeparture: Boolean): Boolean {
        requireMain()
        if (disposed) return false
        val captureInvalidated = captureObservation?.invalidated
        captureObservation = null // Fence the exact capture capability before any platform operation.
        visibleClaim = null
        frameWaiter = null; drawWaiter = null
        val identity = try { ownedIdentity() } catch (_: Exception) { protectionFailed = true; null }
        // Even a replaced/detached owner gets its old local root covered. It cannot acknowledge
        // the replacement hierarchy, and the return value below remains false for lost ownership.
        val protectedIdentity = identity ?: PrivateRootIdentity(window, decor, root, targetIdentity, presentationIdentity)
        val cover = try {
            if (ordinaryDeparture) root.beginOrdinaryProtection(protectedIdentity) else root.beginProtection(protectedIdentity)
        } catch (_: Exception) { protectionFailed = true; null }
        val secure = attempt { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        // Attempt every independent control even when another setter fails.
        ordinaryImportance.forEach {
            if (!attempt { it.view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }) protectionFailed = true
            if (!attempt { it.view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS }) protectionFailed = true
        }
        captureCapability = try { when {
            captureLookup.isFailure || activity.applicationInfo.targetSdkVersion > 36 -> PrivateContentCaptureCapability.UNAVAILABLE
            captureManager == null -> PrivateContentCaptureCapability.FACILITY_ABSENT
            attempt { captureSetting.protect { captureManager.setContentCaptureEnabled(it) } } -> PrivateContentCaptureCapability.DISABLED_BY_ACTIVITY
            else -> PrivateContentCaptureCapability.UNAVAILABLE
        } } catch (_: Exception) { protectionFailed = true; PrivateContentCaptureCapability.UNAVAILABLE }
        recentsCapability = if (Build.VERSION.SDK_INT >= 33) {
            if (attempt { activity.setRecentsScreenshotEnabled(false) }) PrivateRecentsCapability.API33_SUPPRESSION_REQUESTED
            else PrivateRecentsCapability.UNAVAILABLE
        } else PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE
        // No getter is invented for recents suppression; this records this Activity's invocation.
        @Suppress("DEPRECATION")
        if (!attempt { activity.setTaskDescription(ActivityManager.TaskDescription("EasySaveVideo")) }) protectionFailed = true
        if (!attempt { window.setTitle("EasySaveVideo") }) protectionFailed = true
        if (!attempt { activity.getSystemService(AutofillManager::class.java)?.cancel() }) protectionFailed = true
        // Focus clearing/hide are presentation cleanup only, not retained InputConnection proof.
        attempt {
            root.findFocus()?.clearFocus()
            activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(decor.windowToken, 0)
        }
        val installed = identity != null && cover != null && secure && safeCheck(::secureRequested) && root.isCovered && root.localBarrierHealthy
        if (!installed) protectionFailed = true
        // Callback runs only after this guard's independent safety attempts, never after a frame wait.
        if (captureInvalidated != null) attempt(captureInvalidated)
        return installed
    }

    /** Reserve protection BEFORE future private content attaches. No API here authorizes attachment. */
    fun reservePrivatePresentation(target: Any, presentation: Any): Boolean {
        requireMain()
        privateContentReserved = true; ordinaryCurrent = null
        root.reservePrivate()
        targetIdentity = target; presentationIdentity = presentation
        return installProtectionBarrier()
    }

    /** Local draw + hardware submission only. Unattached/software/no frame stays pending forever. */
    fun afterOwnedCoverFrameSubmitted(turn: PrivateProtectionTurn, submitted: () -> Unit): Boolean {
        requireMain()
        if (disposed || root.currentProtectionTurn !== turn || ownedIdentity()?.sameAs(turn.identity) != true) return false
        if (root.hasCommittedCoverFrame && secureRequested()) submitted() else frameWaiter = turn to submitted
        return true
    }
    /** Ordinary restoration may use a genuine software draw; it never mints private frame proof. */
    fun afterOwnedCoverDrawn(turn: PrivateProtectionTurn, drawn: () -> Unit): Boolean {
        requireMain()
        if (disposed || root.currentProtectionTurn !== turn || ownedIdentity()?.sameAs(turn.identity) != true) return false
        if (root.hasDrawnCover) drawn() else drawWaiter = turn to drawn
        return true
    }
    val currentProtectionTurn get() = root.currentProtectionTurn

    /** No surface/capture evidence is minted until all independent integration gates are reviewed. */
    fun openException(): Boolean {
        requireMain()
        installProtectionBarrier()
        return false
    }
    fun lifecycleReadiness(): PrivateWindowReadiness {
        requireMain()
        val owned = ownedIdentity() != null && root.isAttachedToWindow && decor.isAttachedToWindow &&
            root.windowToken != null && root.windowToken === decor.windowToken
        return PrivateWindowReadiness(
            ownedRootAttached = owned && !protectionFailed && root.localBarrierHealthy,
            secureRequested = secureRequested(),
            viewTreeOwnersInstalled = root.childCount == 1 && root.getChildAt(0).let {
                it.findViewTreeLifecycleOwner() === activity && it.findViewTreeViewModelStoreOwner() === activity &&
                    it.findViewTreeSavedStateRegistryOwner() === activity
            },
            structureExcluded = structureExcluded(),
            contentCapture = captureCapability, recents = recentsCapability,
            coverFrameSubmitted = root.hasCommittedCoverFrame,
            resumedAndFocused = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && decor.hasWindowFocus(),
            pending = pendingGates,
            continuousCaptureAuthorized = hasContinuousCaptureAuthorization,
        )
    }

    fun ownsCaptureRoot(ownerActivity: ComponentActivity, ownerRoot: PrivateContentRoot): Boolean {
        requireMain()
        return activity === ownerActivity && root === ownerRoot && ownedDialogWindow == null && ownedIdentity() != null
    }
    /** API31/32 uses the explicitly accepted residual Recents risk; normal private guards remain. */
    fun captureCapabilityAvailable(): Boolean {
        requireMain()
        return captureSafetyReady(expectSecure = true, requireRevokedInput = false)
    }
    private fun captureSafetyReady(expectSecure: Boolean, requireRevokedInput: Boolean = true): Boolean {
        val ready = lifecycleReadiness()
        return Build.VERSION.SDK_INT >= 31 && ownedDialogWindow == null && privateContentReserved &&
            !activity.isFinishing && pendingGates.isEmpty() &&
            ready.ownedRootAttached && ready.viewTreeOwnersInstalled && ready.structureExcluded &&
            ready.resumedAndFocused && ready.contentCapture != PrivateContentCaptureCapability.UNAVAILABLE &&
            ready.recents != PrivateRecentsCapability.UNAVAILABLE &&
            (!expectSecure || ready.secureRequested) && (!requireRevokedInput || !root.inputLifetime.isAdmitted)
    }
    fun observeCaptureFrame(owner: Any, view: View, document: Any, presentation: Any, layoutGeneration: Long,
        stillOwned: () -> Boolean, drawn: (PrivateCaptureWindowSnapshot) -> Unit,
        committed: (CaptureCapability, PrivateCaptureWindowSnapshot) -> Unit, invalidated: () -> Unit): Boolean {
        requireMain()
        val turn = root.currentProtectionTurn ?: return false
        if (captureObservation != null || !root.isCovered || !captureSafetyReady(true) ||
            ownedIdentity()?.sameAs(turn.identity) != true || !safeCheck(stillOwned)) return false
        return CaptureObservation(owner, turn, view, document, presentation, layoutGeneration, stillOwned,
            drawn, committed, invalidated).let {
            if (captureSnapshot(it) == null) false else { captureObservation = it; true }
        }
    }
    /** Invoked exclusively by the actual ARMING composition SideEffect, never by focus or timers. */
    fun acknowledgeCaptureComposition(owner: Any): Boolean {
        requireMain()
        val value = captureObservation?.takeIf { it.owner === owner } ?: return false
        val identity = ownedIdentity() ?: return false
        if (!safeCheck(value.stillOwned) || value.consumed || !captureSafetyReady(true)) return false
        if (!root.acknowledgeAppliedComposition(value.turn, identity)) return false
        if (!value.applied) { value.applied = true; value.snapshot = null; value.capability = null }
        return true
    }
    private fun captureDrawSubmitting(turn: PrivateProtectionTurn) {
        val value = captureObservation ?: return
        if (value.turn !== turn || !value.applied || value.capability != null) return
        val snapshot = captureSnapshot(value)
        if (!captureSafetyReady(true) || !safeCheck(value.stillOwned) || snapshot == null ||
            value.snapshot?.sameAs(snapshot) == false) { installProtectionBarrier(); return }
        value.snapshot = snapshot
        value.drawn(snapshot)
    }
    private fun captureFrameCommitted(turn: PrivateProtectionTurn) {
        val value = captureObservation ?: return
        if (value.turn !== turn || !value.applied || value.capability != null) return
        val snapshot = captureSnapshot(value)
        if (!captureSafetyReady(true) || !safeCheck(value.stillOwned) || snapshot == null ||
            value.snapshot?.sameAs(snapshot) != true || !root.hasCommittedCoverFrame) {
            installProtectionBarrier(); return
        }
        val capability = CaptureCapability(value.owner, turn, snapshot)
        value.capability = capability
        value.committed(capability, snapshot)
    }
    /** Current geometry and ancestry are always read from the exact retained Android hierarchy. */
    fun readCaptureSnapshot(owner: Any): PrivateCaptureWindowSnapshot? {
        requireMain()
        val value = captureObservation?.takeIf { it.owner === owner } ?: return null
        if (!captureSafetyReady(expectSecure = !value.opened, requireRevokedInput = !value.opened) || !safeCheck(value.stillOwned)) return null
        if (!value.opened && root.currentProtectionTurn !== value.turn) return null
        if (value.opened && (root.presentationState != PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE ||
                root.visibleIdentity?.sameAs(value.turn.identity) != true || secureRequested())) return null
        return captureSnapshot(value)
    }
    fun forgetCaptureObservation(owner: Any) {
        requireMain()
        if (captureObservation?.owner === owner) captureObservation = null
    }
    /** A newer barrier may replace its turn, but never the exact retiring window/root/presentation. */
    fun afterOwnedCaptureProtectionSubmitted(owner: Any, protected: () -> Unit): Boolean {
        requireMain()
        val identity = ownedIdentity() ?: return false
        val turn = root.currentProtectionTurn ?: return false
        if (!root.isCovered || !secureRequested() || !identity.sameAs(turn.identity)) return false
        val existing = captureRetirement
        if (existing != null && (existing.owner !== owner || !existing.identity.sameAs(identity))) return false
        if (root.hasCommittedCoverFrame) protected()
        else captureRetirement = CaptureRetirement(owner, identity, protected)
        return true
    }
    fun forgetCaptureRetirement(owner: Any) {
        requireMain()
        if (captureRetirement?.owner === owner) captureRetirement = null
    }
    /** No arbitrary View or caller-supplied boolean can clear secure: exact retained capability only. */
    fun openCaptureException(capability: CaptureCapability, stillAllowed: () -> Boolean): Boolean {
        requireMain()
        val value = captureObservation ?: return false
        if (value.capability !== capability || value.owner !== capability.owner || value.turn !== capability.turn ||
            value.consumed || !value.applied || !root.hasCommittedCoverFrame) return false
        value.consumed = true // A failed attempt never makes this capability reusable.
        return performPrivateCaptureOpening(
            stillAllowed = {
                captureObservation === value && captureSafetyReady(true) && safeCheck(value.stillOwned) &&
                    captureSnapshot(value)?.let(capability.snapshot::sameAs) == true && safeCheck(stillAllowed)
            },
            clearSecure = { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
            reveal = {
                val identity = ownedIdentity()
                val revealed = identity != null && root.revealCapture(value.turn, identity, value.view)
                if (revealed) value.opened = true
                revealed
            },
            verify = { captureObservation === value && !secureRequested() && captureSafetyReady(false, requireRevokedInput = false) &&
                safeCheck(value.stillOwned) && captureSnapshot(value)?.let(capability.snapshot::sameAs) == true },
            restore = { installProtectionBarrier() },
        )
    }
    private fun captureSnapshot(value: CaptureObservation): PrivateCaptureWindowSnapshot? {
        val identity = ownedIdentity() ?: return null
        if (!identity.sameAs(value.turn.identity)) return null
        val view = value.view
        if (!root.isAttachedToWindow || !view.isAttachedToWindow || !decor.isAttachedToWindow ||
            !root.isHardwareAccelerated || !view.isHardwareAccelerated || !root.isShown || !view.isShown ||
            root.windowToken == null || root.windowToken !== decor.windowToken || view.windowToken !== root.windowToken)
            return null
        val parents = mutableListOf<Any>()
        var current: View? = view
        var foundRoot = false
        while (current != null) {
            if (!current.isShown || current.visibility != View.VISIBLE || current.alpha != 1f || !current.matrix.isIdentity) return null
            parents += current
            if (current === root) foundRoot = true
            if (current === decor) break
            current = current.parent as? View
        }
        if (!foundRoot || parents.lastOrNull() !== decor) return null
        fun exactRect(item: View): PrivateCaptureWindowRect? {
            if (item.width <= 0 || item.height <= 0) return null
            val location = IntArray(2); item.getLocationOnScreen(location)
            val expected = Rect(location[0], location[1], location[0] + item.width, location[1] + item.height)
            val visible = Rect()
            if (!item.getGlobalVisibleRect(visible) || visible != expected) return null
            return expected.captureRect()
        }
        val rootBounds = exactRect(root) ?: return null
        val viewBounds = exactRect(view) ?: return null
        if (viewBounds.left < rootBounds.left || viewBounds.top < rootBounds.top ||
            viewBounds.right > rootBounds.right || viewBounds.bottom > rootBounds.bottom) return null
        val insets = root.rootWindowInsets ?: return null
        val display = root.display ?: return null
        val resources = activity.resources
        val configuration = resources.configuration
        fun inset(type: Int) = insets.getInsets(type).let { PrivateCaptureWindowRect(it.left, it.top, it.right, it.bottom) }
        return PrivateCaptureWindowSnapshot(window, decor, root, view, value.document, value.presentation,
            checkNotNull(root.windowToken), parents,
            PrivateCaptureWindowLayout(value.layoutGeneration, rootBounds, viewBounds,
                activity.windowManager.currentWindowMetrics.bounds.captureRect(),
                activity.windowManager.maximumWindowMetrics.bounds.captureRect(),
                inset(WindowInsets.Type.systemBars()), inset(WindowInsets.Type.ime()), inset(WindowInsets.Type.displayCutout()),
                display.rotation, display.displayId, configuration.densityDpi, resources.displayMetrics.density,
                configuration.fontScale, configuration.orientation))
    }
    private fun Rect.captureRect() = PrivateCaptureWindowRect(left, top, right, bottom)

    /**
     * Startup only: app composition explicitly confirms that no private browser/integration was
     * restored. Release follows the real covered root draw. It is NOT private teardown evidence,
     * and cannot run after reservePrivatePresentation, even if the caller mistakenly says safe.
     */
    fun acknowledgeInitialOrdinaryComposition(isStillOrdinary: () -> Boolean) {
        requireMain()
        if (disposed || ordinaryReleased || privateContentReserved) return
        ordinaryCurrent = isStillOrdinary
        root.invalidate()
    }
    private fun releaseInitialOrdinaryIfSafe() {
        requireMain()
        if (disposed || ordinaryReleased || privateContentReserved || protectionFailed) return
        val identity = ownedIdentity() ?: return
        val turn = root.currentProtectionTurn ?: return
        val ordinary = try { ordinaryCurrent?.invoke() == true } catch (_: Exception) { false }
        if (!ordinary || targetIdentity !== startupTarget || presentationIdentity !== startupPresentation) return
        // Still no private subtree; restore ordinary platform values without enabling private UI.
        if (!root.releaseInitialOrdinary(turn, identity, ordinary, privateContentReserved)) return
        ordinaryReleased = true; ordinaryCurrent = null; frameWaiter = null
        restoreOrdinaryPlatformPolicy()
    }

    /** Installed integration/platform acceptance only; never clear a gate merely after a JVM pass. */
    fun setPendingGates(pending: Set<PrivateWindowPendingGate>) {
        requireMain()
        if (disposed) return
        pendingGates = pending.toSet()
        dialogs.toList().forEach { it.guard.setPendingGates(pendingGates) }
        if (visibleClaim != null && !lifecycleReadiness().ready) installProtectionBarrier()
    }

    /** Call only from the exact content's applied SideEffect, with the native cover already installed. */
    fun acknowledgePrivateComposition(turn: PrivateProtectionTurn, claim: PrivateWindowPresentation): Boolean {
        requireMain()
        if (!privateContentReserved || targetIdentity !== claim.target || presentationIdentity !== claim.presentation ||
            !safeCheck(claim.isCurrent) || !safeCheck(claim.compositionIsCurrent)) return false
        val identity = ownedIdentity() ?: return false
        return root.acknowledgeAppliedComposition(turn, identity)
    }

    fun acknowledgeOrdinaryComposition(turn: PrivateProtectionTurn, proof: PrivateOrdinaryPresentationProof): Boolean {
        requireMain()
        if (disposed || ownedDialogWindow != null || hasRegisteredPrivateDialogs || targetIdentity !== proof.target ||
            presentationIdentity !== proof.presentation) return false
        val readback = try { proof.readback() } catch (_: Exception) { return false }
        if (!readback.complete) return false
        val identity = ownedIdentity() ?: return false
        return root.acknowledgeAppliedComposition(turn, identity)
    }

    fun revealProtectedPrivate(turn: PrivateProtectionTurn, claim: PrivateWindowPresentation): PrivateRevealResult {
        requireMain()
        val identity = ownedIdentity() ?: return PrivateRevealResult.STALE
        if (!privateContentReserved || disposed || targetIdentity !== claim.target ||
            presentationIdentity !== claim.presentation || !safeCheck(claim.isCurrent)) return PrivateRevealResult.STALE
        if (hasRegisteredPrivateDialogs) return PrivateRevealResult.PENDING
        val readiness = lifecycleReadiness()
        val readback = PrivateRevealReadback(
            secure = readiness.secureRequested,
            structureExcluded = readiness.structureExcluded,
            contentCaptureDisabled = readiness.contentCapture != PrivateContentCaptureCapability.UNAVAILABLE,
            recentsProtection = readiness.recents != PrivateRecentsCapability.UNAVAILABLE,
            integrationReady = browsingIntegrationInstalled && (!hasContinuousCaptureAuthorization || pendingGates.isEmpty()),
            claimCurrent = safeCheck(claim.isCurrent),
            compositionApplied = safeCheck(claim.compositionIsCurrent),
            attached = readiness.ownedRootAttached && readiness.viewTreeOwnersInstalled && expectedAttachment(claim),
            focused = readiness.resumedAndFocused,
            captureExceptionInactive = safeCheck(claim.captureExceptionInactive),
            continuousCaptureAuthorized = hasContinuousCaptureAuthorization,
        )
        val authorization = continuousAuthorization
        val result = if (readback.continuousCaptureAuthorized) {
            // The same claim/frame/input checks apply; pixel permission is the separate live session.
            if (!readback.structureExcluded || !readback.contentCaptureDisabled || !readback.recentsProtection ||
                !readback.integrationReady || !readback.claimCurrent) return PrivateRevealResult.BLOCKED
            if (!readback.compositionApplied || !readback.attached || !readback.focused ||
                !root.hasCommittedCoverFrame) return PrivateRevealResult.PENDING
            var reveal = PrivateRevealResult.BLOCKED
            val opened = performPrivateCaptureOpening(
                stillAllowed = { continuousAuthorization === authorization && hasContinuousCaptureAuthorization &&
                    ownedIdentity()?.sameAs(identity) == true && root.currentProtectionTurn === turn &&
                    root.isCovered && root.localBarrierHealthy && safeCheck(claim.isCurrent) &&
                    safeCheck(claim.compositionIsCurrent) && expectedAttachment(claim) },
                clearSecure = { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                reveal = {
                    reveal = root.revealProtectedPrivate(turn, identity, readback.copy(secure = false))
                    reveal == PrivateRevealResult.REVEALED
                },
                verify = { continuousAuthorization === authorization && hasContinuousCaptureAuthorization &&
                    !secureRequested() && ownedIdentity()?.sameAs(identity) == true },
                restore = { installProtectionBarrier() },
            )
            if (opened) reveal else PrivateRevealResult.BLOCKED
        } else root.revealProtectedPrivate(turn, identity, readback)
        if (result == PrivateRevealResult.REVEALED) { visibleClaim = claim; frameWaiter = null }
        return result
    }

    /** Main-thread only, for visible-audio/action admission; no capture-owner inference is made. */
    fun hasVisiblePrivatePresentation(target: Any, presentation: Any): Boolean {
        requireMain()
        val claim = visibleClaim ?: return false
        return claim.target === target && claim.presentation === presentation &&
            root.presentationState in setOf(PrivateRootPresentationState.PRIVATE_VISIBLE,
                PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE) &&
            ownedIdentity()?.let { root.visibleIdentity?.sameAs(it) } == true &&
            safeCheck(claim.isCurrent) && safeCheck(claim.compositionIsCurrent) &&
            (safeCheck(claim.captureExceptionInactive) || hasContinuousCaptureAuthorization) && expectedAttachment(claim) &&
            lifecycleReadiness().let { it.ready && it.resumedAndFocused } && !hasRegisteredPrivateDialogs
    }

    private fun expectedAttachment(claim: PrivateWindowPresentation): Boolean {
        val view = claim.contentView ?: return claim.expectedParent == null
        val parent = claim.expectedParent ?: return false
        if (view.parent !== parent || !view.isAttachedToWindow || view.windowToken !== root.windowToken ||
            view.visibility != View.VISIBLE) return false
        var current: View? = parent
        while (current != null) {
            if (current === root) return true
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return false
    }
    private fun safeCheck(check: () -> Boolean) = try { check() } catch (_: Exception) { false }

    /** Fresh ordinary identity; existing private callbacks and input are synchronously revoked. */
    fun beginOrdinaryPresentation(target: Any, presentation: Any): PrivateProtectionTurn? {
        requireMain()
        if (disposed || !privateContentReserved || ownedDialogWindow != null) return null
        targetIdentity = target; presentationIdentity = presentation; ordinaryCurrent = null
        return if (installBarrier(ordinaryDeparture = true)) root.currentProtectionTurn else null
    }

    fun releaseOrdinaryPresentation(turn: PrivateProtectionTurn, proof: PrivateOrdinaryPresentationProof): Boolean {
        requireMain()
        if (disposed || ownedDialogWindow != null || !privateContentReserved || protectionFailed ||
            hasRegisteredPrivateDialogs || targetIdentity !== proof.target || presentationIdentity !== proof.presentation) return false
        val identity = ownedIdentity() ?: return false
        val readback = try { proof.readback() } catch (_: Exception) { return false }
        if (!readback.complete || !root.localBarrierHealthy || !secureRequested()) return false
        if (!root.releasePresentedOrdinary(turn, identity, readback)) return false
        frameWaiter = null; drawWaiter = null; visibleClaim = null; ordinaryReleased = true
        val restored = restoreOrdinaryPlatformPolicy()
        if (restored) privateContentReserved = false
        return restored
    }

    private fun restoreOrdinaryPlatformPolicy(): Boolean {
        var restored = true
        ordinaryImportance.forEach {
            if (!attempt { it.view.importantForAutofill = it.autofill }) restored = false
            if (!attempt { it.view.importantForContentCapture = it.capture }) restored = false
        }
        if (!attempt { captureSetting.restoreOrdinary { captureManager?.setContentCaptureEnabled(it) } }) restored = false
        if (Build.VERSION.SDK_INT >= 33 && recentsCapability == PrivateRecentsCapability.API33_SUPPRESSION_REQUESTED)
            if (!attempt { activity.setRecentsScreenshotEnabled(true) }) restored = false
        attempt { activity.getSystemService(AutofillManager::class.java)?.cancel() }
        if (!ordinarySecure && !attempt { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }) restored = false
        if (!restored) {
            installProtectionBarrier() // Failed restoration stays covered, never an optimistic result.
            return false
        }
        captureCapability = PrivateContentCaptureCapability.UNAVAILABLE
        recentsCapability = PrivateRecentsCapability.UNAVAILABLE
        return true
    }

    /** Called with an empty owned dialog root, before attaching its sensitive Compose content. */
    fun registerPrivateDialog(dialogWindow: Window, dialogRoot: PrivateContentRoot, request: Any,
        requestIsCurrent: () -> Boolean, onOwnerClosed: () -> Unit,
        teardownIsComplete: () -> Boolean, revokeInput: (Long) -> Unit): PrivateDialogRegistration? {
        requireMain()
        if (disposed || ownedDialogWindow != null || dialogWindow === window || dialogRoot === root ||
            dialogs.any { it.guard.window === dialogWindow || it.guard.root === dialogRoot } ||
            !safeCheck(requestIsCurrent) || !root.isCovered || !secureRequested() || !dialogRoot.isCovered || dialogRoot.childCount != 0) return null
        val child = try { PrivateWindowGuard(activity, dialogRoot, ordinaryContentCaptureOptIn = false,
            ownedDialogWindow = dialogWindow, revokeInput = revokeInput) } catch (_: Exception) { return null }
        child.setPendingGates(pendingGates)
        val authorization = continuousAuthorization
        val isCurrent = continuousAuthorizationCurrent
        if (authorization != null && isCurrent != null && hasContinuousCaptureAuthorization &&
            !child.attachContinuousCapture(authorization, isCurrent)) { child.close(); return null }
        if (!child.reservePrivatePresentation(request, request)) { child.close(); return null }
        return PrivateDialogRegistration(this, child, request, requestIsCurrent, onOwnerClosed, teardownIsComplete).also { dialogs.add(it) }
    }

    fun isRegisteredPrivateDialog(registration: PrivateDialogRegistration): Boolean {
        requireMain()
        return !disposed && registration.owner === this && registration in dialogs &&
            safeCheck(registration.requestIsCurrent)
    }
    /** The original native hierarchy must be detached and empty; closed admission is insufficient. */
    private fun detachedEmptyDialogRoot(): Boolean = ownedDialogWindow != null &&
        window.decorView === decor && window.findViewById<View>(android.R.id.content) === content &&
        root.parent === content && content.childCount == 1 && content.getChildAt(0) === root &&
        !decor.isAttachedToWindow && decor.windowToken == null &&
        !root.isAttachedToWindow && root.windowToken == null && root.childCount == 0 && dialogs.isEmpty()

    fun unregisterPrivateDialog(registration: PrivateDialogRegistration): Boolean {
        requireMain()
        if (registration.owner !== this) return false
        if (registration !in dialogs) return true // Exact duplicate, including late native callbacks.
        if (!safeCheck(registration.teardownIsComplete) ||
            !safeCheck(registration.guard::detachedEmptyDialogRoot)) return false
        // Never remove first: failed child close must retain/quarantine this exact registration.
        if (!attempt { registration.guard.close() } || !registration.guard.closeComplete) return false
        if (!safeCheck(registration.teardownIsComplete) ||
            !safeCheck(registration.guard::detachedEmptyDialogRoot)) return false
        val removed = dialogs.remove(registration)
        if (removed) attempt { onOwnedHierarchyChanged?.invoke() }
        return removed
    }
    private val closeComplete get() = disposed && rootClosed && observerRemoved && dialogs.isEmpty()

    override fun close() {
        requireMain()
        if (closing || closeComplete) return
        closing = true
        try {
            // Admission ends once; resource cleanup remains retryable after a platform failure.
            root.inputLifetime.dispose()
            if (!disposed) attempt { installProtectionBarrier() }
            continuousAuthorization = null; continuousAuthorizationCurrent = null
            disposed = true; frameWaiter = null; drawWaiter = null; ordinaryCurrent = null; visibleClaim = null
            onOwnedHierarchyChanged = null
            dialogs.toList().forEach {
                attempt(it.onOwnerClosed)
                attempt { it.guard.close() }
                attempt { unregisterPrivateDialog(it) }
            }
            // Every child and independent cleanup is attempted even when an earlier one throws.
            // Attached/uncertain children stay registered after parent closure for late callbacks.
            if (!observerRemoved) observerRemoved = attempt { activity.lifecycle.removeObserver(lifecycleObserver) }
            if (!rootClosed) rootClosed = attempt { root.close() }
        } finally { closing = false }
    }
}
