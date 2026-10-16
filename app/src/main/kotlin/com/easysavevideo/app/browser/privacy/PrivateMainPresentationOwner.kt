package com.easysavevideo.app.browser.privacy

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.ui.PrivateAppDialogEnvironment
import com.easysavevideo.app.vault.VaultProtectedHost
import com.easysavevideo.app.vault.VaultHostClaim
import com.easysavevideo.app.vault.VaultCredentialRequest
import com.easysavevideo.core.model.BrowserLoadState

/** Activity-thread presentation ownership. A claim or composition acknowledgement never grants capture. */
internal class PrivateMainPresentationOwner(
    private val activity: ComponentActivity,
    private val root: PrivateContentRoot,
    private val guard: PrivateWindowGuard,
    private val browser: SystemBrowserHost,
    private val routeIdentity: () -> Any,
    private val isBrowserRoute: () -> Boolean,
    private val foreground: () -> Boolean,
    private val captureDrained: () -> Boolean,
    private val captureExceptionInactive: () -> Boolean,
    private val protectForPresentationTransition: () -> Boolean,
    private val audioDisposition: (WebView) -> PrivateBrowsingAudioDisposition,
    private val continuousCaptureAuthorized: () -> Boolean = { false },
    private val independentRecordingOwner: () -> Boolean = { false },
    private val vault: () -> VaultProtectedHost? = { null },
) : PrivateBrowserPresentationBinding {
    internal class Applied internal constructor(val route: Any, val claim: PrivateBrowsingPresentationClaim?, val vaultClaim: VaultHostClaim? = null) {
        val sensitive get() = claim != null || vaultClaim != null
        val identity = Any()
        var prepared = false
        var applied = false
        var windowClaim: PrivateWindowPresentation? = null
        var ordinaryTurn: PrivateProtectionTurn? = null
        var acknowledgedTurn: PrivateProtectionTurn? = null
        var dialogEnvironment: PrivateAppDialogEnvironment? = null
        var applyRevision by mutableLongStateOf(0L)
        lateinit var childScheduler: PrivateChildCompositionScheduler
    }
    override val inputLifetime get() = guard.inputLifetime
    var inputEpoch by mutableLongStateOf(0L); private set
    private var current: Applied? = null
    private var reservedPrivateTransition = false
    private var closed = false
    private val observedViews = mutableSetOf<View>()
    private val attachmentObserver = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = hierarchyChanged()
        override fun onViewDetachedFromWindow(view: View) {
            hierarchyChanged()
            val claim = current?.claim
            if (view !== claim?.webView && view !== claim?.fullscreenView) {
                view.removeOnAttachStateChangeListener(this); observedViews.remove(view)
            }
        }
    }
    override fun ready() = !closed && guard.browsingIntegrationInstalled
    override fun isCovered() = !closed && root.isCovered
    override fun isVisible(claim: PrivateBrowsingPresentationClaim): Boolean = current?.let {
        it.claim === claim && appliedCurrent(it) && guard.hasVisiblePrivatePresentation(claim.targetIdentity, claim.presentationIdentity)
    } == true
    override fun captureAudioDisposition(view: WebView) = audioDisposition(view)

    /** The guard invokes this on every revocation; reveal below separately publishes a fresh epoch. */
    fun inputRevoked() { if (!closed) { inputEpoch++; invalidateChildComposition() } }
    private fun invalidateChildComposition() {
        current?.let { value ->
            value.applied = false; value.acknowledgedTurn = null
            value.childScheduler.hierarchyChanged()
        }
    }
    /** Actual attachment/teardown/focus events request new composition; none supplies apply proof. */
    fun hierarchyChanged() {
        if (closed || current == null) return
        val discardOldFrame = !root.isCovered || current?.acknowledgedTurn != null
        invalidateChildComposition()
        // A real hierarchy change after acknowledgement must retire that frame's turn. The
        // guard's revoke callback only invalidates, so this cannot recurse into another cover.
        if (discardOldFrame) guard.installProtectionBarrier()
    }
    private fun forgetDetachedViews() {
        observedViews.toList().filter { !it.isAttachedToWindow }.forEach {
            it.removeOnAttachStateChangeListener(attachmentObserver); observedViews.remove(it)
        }
    }
    private fun newApplied(route: Any, claim: PrivateBrowsingPresentationClaim?, vaultClaim: VaultHostClaim? = null): Applied {
        current?.childScheduler?.close()
        return Applied(route, claim, vaultClaim).also { value ->
            value.childScheduler = PrivateChildCompositionScheduler(
                isCurrent = { !closed && current === value && routeIdentity() === value.route },
                acknowledge = { acknowledgeChildComposition(value) },
                requestComposition = { value.applyRevision++ },
            )
            current = value
            listOfNotNull(claim?.webView, claim?.fullscreenView).forEach { view ->
                if (observedViews.add(view)) view.addOnAttachStateChangeListener(attachmentObserver)
            }
        }
    }
    /** Reading the observable revision subscribes Main to genuine native attachment/teardown work. */
    fun captureChildApply(value: Applied?): PrivateChildCompositionScheduler.Ticket? {
        if (value == null || current !== value || closed) return null
        value.applyRevision
        return value.childScheduler.ticket
    }
    fun childLaidOut(value: Applied?, ticket: PrivateChildCompositionScheduler.Ticket?) {
        if (value != null && ticket != null && current === value) value.childScheduler.childLaidOut(ticket)
    }
    fun compositionApplied(value: Applied?, ticket: PrivateChildCompositionScheduler.Ticket?) {
        if (value != null && ticket != null && current === value) value.childScheduler.childApplied(ticket)
    }

    /**
     * Non-authorizing reservation before entry/navigation. If asynchronous entry fails before a
     * Host claim exists, this still requires genuine ordinary departure proof, not startup fallback.
     */
    fun reserveProtectedTransition(): Boolean {
        if (closed) return false
        reservedPrivateTransition = true
        current?.let {
            it.prepared = false; it.applied = false; it.windowClaim = null
            it.ordinaryTurn = null; it.acknowledgedTurn = null
        }
        val transition = Any()
        return guard.reservePrivatePresentation(transition, transition)
    }

    /** Called before sensitive composition. Cache identities; never churn a live claim per recomposition. */
    fun prepareComposition(): Applied? {
        if (closed) return null
        val route = routeIdentity()
        val old = current
        val vaultClaim = vault()?.claimFor(route)
        if (vaultClaim != null && !browser.isPrivateMode) {
            val value = if (old?.vaultClaim === vaultClaim && old.route === route) old else newApplied(route, null, vaultClaim)
            if (!value.prepared) {
                reservedPrivateTransition = true
                if (!guard.reservePrivatePresentation(vaultClaim.target, vaultClaim.presentation)) return null
                value.prepared = true
            }
            return value
        }
        if (browser.isPrivateMode) {
            val value = if (old?.claim != null && old.route === route &&
                browser.isPrivateBrowsingPresentationCurrent(old.claim)) old else {
                val claim = if (isBrowserRoute()) browser.capturePrivateBrowsingPresentation()
                    else browser.capturePrivateApplicationPresentation()
                if (claim == null) {
                    if (!root.isCovered) guard.installProtectionBarrier()
                    return null
                }
                newApplied(route, claim)
            }
            if (!value.prepared) {
                val claim = checkNotNull(value.claim)
                reservedPrivateTransition = true
                if (!guard.reservePrivatePresentation(claim.targetIdentity, claim.presentationIdentity) ||
                    !browser.bindPrivatePresentationInput(claim)) return null
                value.prepared = true
            }
            return value
        }
        if (old != null && !old.sensitive && old.route === route) return old
        return newApplied(route, null)
    }
    private fun coveredCompositionCurrent(value: Applied): Boolean = !closed && current === value && value.applied &&
        routeIdentity() === value.route
    private fun appliedCurrent(value: Applied): Boolean = coveredCompositionCurrent(value) &&
        !activity.isDestroyed && !activity.isFinishing && foreground()
    /**
     * Ordinary-only screens may allocate raw dialogs. After any private reservation, first apply
     * an empty ordinary shell and obtain genuine departure release before composing those screens.
     * Startup remains unchanged; later ordinary-to-ordinary routes need no new private proof.
     */
    fun canComposeOrdinaryContent(value: Applied?): Boolean = !closed && value != null &&
        current === value && !value.sensitive && routeIdentity() === value.route && !browser.isPrivateMode &&
        (!reservedPrivateTransition || (!root.isCovered &&
            root.presentationState == PrivateRootPresentationState.ORDINARY_VISIBLE))
    fun isCurrentSource(value: Applied?): Boolean = value != null && current === value && routeIdentity() === value.route &&
        when { value.vaultClaim != null -> value.vaultClaim.host.owns(value.vaultClaim)
            value.claim != null -> browser.isPrivateMode && browser.isPrivateBrowsingPresentationCurrent(value.claim)
            else -> !browser.isPrivateMode && vault()?.claimFor(value.route) == null }
    fun vaultActionsCurrent(claim: VaultHostClaim): Boolean = current?.let { value ->
        value.vaultClaim === claim && appliedCurrent(value) && claim.host.owns(claim) &&
            !guard.hasContinuousCaptureAuthorization && guard.hasVisiblePrivatePresentation(claim.target, claim.presentation)
    } == true
    private fun ownsContent(view: View?): Boolean {
        if (view == null) return true
        if (!view.isAttachedToWindow) return false
        var parent = view.parent
        while (parent is View) {
            if (parent === root) return true
            parent = parent.parent
        }
        return false
    }
    /** Matches the real BrowserScreen branch; null alone is never sufficient shell evidence. */
    private fun compositionShapeCurrent(claim: PrivateBrowsingPresentationClaim): Boolean {
        if (claim.kind == PrivateBrowsingPresentationKind.APPLICATION)
            return !isBrowserRoute() && claim.contentView == null
        if (!isBrowserRoute()) return false
        return when (claim.renderedContent) {
            PrivateBrowsingRenderedContent.VIEW -> claim.contentView != null && ownsContent(claim.contentView) &&
                (claim.fullscreenView == null || claim.webView?.let { !it.isAttachedToWindow || ownsContent(it) } != false)
            PrivateBrowsingRenderedContent.EMPTY -> claim.isEmptyTab && claim.contentView == null &&
                browser.state.selectedTab?.let { it.address == null && it.loadState == BrowserLoadState.IDLE } == true
            PrivateBrowsingRenderedContent.ERROR_SHELL -> claim.contentView == null &&
                browser.state.selectedTab?.loadState in setOf(BrowserLoadState.FAILED, BrowserLoadState.CANCELLED)
        }
    }
    /** Reached only through an exact ticket from the actual route/browser child SideEffect. */
    private fun acknowledgeChildComposition(value: Applied): Boolean {
        if (closed || current !== value || routeIdentity() !== value.route) return false
        value.applied = true
        val pendingHost = vault()
        val pendingCover = pendingHost?.pendingCoverGeneration()
        if (pendingCover != null) {
            val turn = guard.currentProtectionTurn ?: return false
            if (guard.acknowledgeHostCover(turn) { coveredCompositionCurrent(value) }) {
                value.acknowledgedTurn = turn
                guard.afterOwnedCoverFrameSubmitted(turn) {
                    if (current === value && value.acknowledgedTurn === turn && routeIdentity() === value.route) {
                        pendingHost.coveredFrame(pendingCover)
                        value.childScheduler.hierarchyChanged()
                    }
                }
                return true
            }
            return false
        }
        val vaultClaim = value.vaultClaim
        if (vaultClaim != null) {
            if (!value.prepared || !vaultClaim.host.owns(vaultClaim) || !vaultClaim.host.prepareInput(vaultClaim)) return false
            val turn = guard.currentProtectionTurn ?: return false
            val windowClaim = value.windowClaim ?: PrivateWindowPresentation(
                vaultClaim.target, vaultClaim.presentation, null, null,
                isCurrent = { current === value && vaultClaim.host.owns(vaultClaim) },
                compositionIsCurrent = { appliedCurrent(value) && vaultClaim.host.owns(vaultClaim) },
                captureExceptionInactive = { !guard.hasContinuousCaptureAuthorization && captureDrained() },
                capturePolicy = PrivateCapturePolicy.FORBIDDEN,
            ).also { value.windowClaim = it }
            if (!guard.acknowledgePrivateComposition(turn, windowClaim)) return false
            value.acknowledgedTurn = turn
            guard.afterOwnedCoverFrameSubmitted(turn) { reveal(value, turn, windowClaim) }
            return true
        }
        val claim = value.claim
        if (claim == null) {
            if (!reservedPrivateTransition) {
                guard.acknowledgeInitialOrdinaryComposition {
                    appliedCurrent(value) && browser.readPrivateOrdinaryDeparture().ordinaryCurrent
                }
                return true
            } else if (root.isCovered) {
                val turn = value.ordinaryTurn?.takeIf { guard.currentProtectionTurn === it }
                    ?: guard.beginOrdinaryPresentation(value.identity, value.identity)?.also { value.ordinaryTurn = it }
                    ?: return false
                val proof = ordinaryProof(value)
                if (guard.acknowledgeOrdinaryComposition(turn, proof)) {
                    value.acknowledgedTurn = turn
                    guard.afterOwnedCoverDrawn(turn) {
                        if (guard.releaseOrdinaryPresentation(turn, proof)) { forgetDetachedViews(); inputEpoch++ }
                    }
                    return true
                }
            }
            return false
        }
        if (!value.prepared) return false
        val actualParent = claim.contentView?.parent as? ViewGroup
        if (value.windowClaim != null && value.windowClaim?.expectedParent !== actualParent) {
            if (!guard.reservePrivatePresentation(claim.targetIdentity, claim.presentationIdentity) ||
                !browser.bindPrivatePresentationInput(claim)) return false
            value.windowClaim = null; value.acknowledgedTurn = null
        }
        val turn = guard.currentProtectionTurn ?: return false
        val windowClaim = value.windowClaim ?: PrivateWindowPresentation(
            claim.targetIdentity, claim.presentationIdentity, claim.contentView, actualParent,
            isCurrent = { current === value && routeIdentity() === value.route && browser.isPrivateBrowsingPresentationCurrent(claim) },
            compositionIsCurrent = {
                appliedCurrent(value) && browser.isPrivateMode && compositionShapeCurrent(claim)
            }, captureExceptionInactive = captureExceptionInactive,
        ).also { value.windowClaim = it }
        if (guard.acknowledgePrivateComposition(turn, windowClaim)) {
            value.acknowledgedTurn = turn
            guard.afterOwnedCoverFrameSubmitted(turn) { reveal(value, turn, windowClaim) }
            return true
        }
        return false
    }
    private fun reveal(value: Applied, turn: PrivateProtectionTurn, claim: PrivateWindowPresentation) {
        if (current !== value || value.acknowledgedTurn !== turn) return
        if (guard.revealProtectedPrivate(turn, claim) == PrivateRevealResult.REVEALED) {
            inputEpoch++
            if (value.claim != null) browser.refreshPrivateCaptureAudio()
        }
    }
    private fun ordinaryProof(value: Applied) = PrivateOrdinaryPresentationProof(value.identity, value.identity) {
        val host = browser.readPrivateOrdinaryDeparture()
        PrivateOrdinaryDepartureReadback(
            ordinaryCurrent = appliedCurrent(value) && !browser.isPrivateMode && host.ordinaryCurrent,
            privateViewsDetached = host.privateViewsDetached,
            privateDialogsAbsent = !guard.hasRegisteredPrivateDialogs,
            privateCallbacksRetired = host.privateCallbacksRetired && (vault()?.ordinaryReleaseAllowed() != false),
            captureDrained = captureDrained(),
            continuousCaptureAuthorized = continuousCaptureAuthorized(),
            independentRecordingOwner = independentRecordingOwner(),
        )
    }
    /** Retry only this turn's genuine applied composition. No focus/timeout manufactured acknowledgement. */
    fun vaultProgressed() { if (!closed) current?.childScheduler?.hierarchyChanged() }
    fun retryPresentation() {
        val value = current?.takeIf { it.applied } ?: return
        val turn = guard.currentProtectionTurn ?: return
        if (value.acknowledgedTurn !== turn) {
            value.childScheduler.hierarchyChanged()
            return
        }
        val claim = value.windowClaim
        if (value.sensitive && claim != null) {
            guard.afterOwnedCoverFrameSubmitted(turn) { reveal(value, turn, claim) }
        } else if (!value.sensitive && value.ordinaryTurn === turn) {
            val proof = ordinaryProof(value)
            guard.afterOwnedCoverDrawn(turn) { if (guard.releaseOrdinaryPresentation(turn, proof)) { forgetDetachedViews(); inputEpoch++ } }
        }
    }
    fun dialogEnvironment(value: Applied?): PrivateAppDialogEnvironment? {
        if (value == null || !value.sensitive || current !== value) return null
        value.vaultClaim?.let { claim ->
            value.dialogEnvironment?.let { return it }
            return PrivateAppDialogEnvironment(activity, guard,
                protectForModal = { claim.host.protectForModal(it) },
                isRequestCurrent = { it is VaultCredentialRequest && claim.host.requestCurrent(it) && !guard.hasContinuousCaptureAuthorization },
                captureDrainedForModal = captureDrained,
                capturePolicy = PrivateCapturePolicy.FORBIDDEN, trackInputRetirement = true,
                onInputRetired = { if (it is VaultCredentialRequest) claim.host.cancelCredential(it) },
            ).also { value.dialogEnvironment = it }
        }
        if (value.claim == null) return null
        // Stable for this exact composition identity. A new environment every recomposition would
        // dispose/recreate the dialog and could never settle its own cover frame or input lifetime.
        value.dialogEnvironment?.let { return it }
        fun live() = !closed && current === value && routeIdentity() === value.route && browser.isPrivateMode &&
            !activity.isDestroyed && !activity.isFinishing && foreground() &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            browser.isPrivateBrowsingPresentationCurrent(value.claim)
        return PrivateAppDialogEnvironment(activity, guard,
            protectForModal = { _ -> live() && protectForPresentationTransition() },
            isRequestCurrent = { _ -> live() }, captureDrainedForModal = captureDrained,
            independentRecordingOwner = independentRecordingOwner)
            .also { value.dialogEnvironment = it }
    }
    fun close() { if (!closed) {
        closed = true; current?.childScheduler?.close(); current = null
        observedViews.toList().forEach { it.removeOnAttachStateChangeListener(attachmentObserver) }
        observedViews.clear()
        guard.installProtectionBarrier()
    } }
}
