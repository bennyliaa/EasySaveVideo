package com.easysavevideo.app.vault

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.easysavevideo.app.vault.playback.AndroidVaultPlaybackSurfaceSlot
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.core.model.VaultRecoveryChoice
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import com.easysavevideo.library.thumbnail.*

/** Host-only reference identities. They never establish authentication. */
internal class VaultHostClaim internal constructor(val host: VaultProtectedHost, val route: Any, val generation: Long) {
    val target = host
    val presentation = Any()
}

private sealed interface VaultHostSubmission : AutoCloseable {
    class Password(val input: VaultCredentialInput, val choice: VaultRecoveryChoice) : VaultHostSubmission {
        override fun close() = input.close()
    }
    class Confirmation(val input: VaultRecoveryInput) : VaultHostSubmission { override fun close() = input.close() }
    class Reset(val input: VaultRecoveryInput, val password: VaultCredentialInput) : VaultHostSubmission {
        override fun close() { input.close(); password.close() }
    }
}

/** One Activity attachment to the existing root/guard and the app-scoped runtime. */
internal class VaultProtectedHost(
    private val activity: ComponentActivity,
    private val root: PrivateContentRoot,
    private val guard: PrivateWindowGuard,
    private val mainOwner: PrivateMainPresentationOwner,
    private val binding: AndroidVaultRuntimeBinding,
    private val routeNow: () -> Any,
    private val inVaultRoute: () -> Boolean,
    private val foreground: () -> Boolean,
    private val captureDrained: () -> Boolean,
    private val selectPhoneFile: (VaultPhoneFileRequest) -> Unit = {},
    private val retireOrdinary: (com.easysavevideo.library.MediaReadRef, () -> Unit) -> Unit = { _, _ -> },
) {
    val runtime get() = binding.runtime
    val presentation get() = binding.presentation
    private val main = Handler(Looper.getMainLooper())
    private val actionEpoch = AtomicLong(0)
    private var attached = false
    private var detaching = false
    private var started = false
    private var generation = 0L
    private var claim: VaultHostClaim? = null
    /** Always mounted by Main outside NavHost route/status branches, under the existing root. */
    val playbackContainer = FrameLayout(activity)
    private var playbackToken: VaultPlaybackHostToken? = null
    private var playbackTicket: VaultPlaybackTicket? = null
    private var playbackSlot: AndroidVaultPlaybackSurfaceSlot? = null
    private var playbackAcquisitionUnconfirmed = false
    private val thumbnailIntent = AtomicBoolean(false)
    private var thumbnailHost: VaultThumbnailHostToken? = null
    private val thumbnailHosts = linkedSetOf<VaultThumbnailHostToken>()
    private var thumbnailCatalogRevision: Long? = null
    private var thumbnailViewport = emptyList<VaultMediaReadRef>()
    private class ThumbnailAdmission(val ref: VaultMediaReadRef) { var ticket: VaultThumbnailTicket? = null }
    private var thumbnailAdmission: ThumbnailAdmission? = null
    private var thumbnailProgressPending = false
    private var thumbnailCleanupUnconfirmed by mutableStateOf(false)
    private val thumbnailHostFailure = AtomicBoolean(false)
    private var thumbnailSessionFenced = false
    private var thumbnailSessionGeneration = generation
    var thumbnailsRequested by mutableStateOf(false); private set
    private var thumbnailSession: TypedBoundedThumbnailSession<VaultMediaReadRef, VaultMediaReadRef, VaultThumbnailFrame> = createThumbnailSession()
    private fun createThumbnailSession(): TypedBoundedThumbnailSession<VaultMediaReadRef, VaultMediaReadRef, VaultThumbnailFrame> = TypedBoundedThumbnailSession(
        decoder = AsyncThumbnailDecoder { ref, cancelled, complete ->
            requireMain()
            val token = thumbnailHost
            if (cancelled() || !thumbnailDemandCurrent() || token == null || ref !in thumbnailViewport) {
                ThumbnailStartResult.REJECTED
            } else {
                val admission = ThumbnailAdmission(ref)
                thumbnailAdmission = admission
                val ticket = runtime.openThumbnail(ref, token) { result, settled ->
                    complete(result) {
                        settled()
                        if (thumbnailAdmission === admission) thumbnailAdmission = null
                        thumbnailProgressed()
                    }
                }
                admission.ticket = ticket
                if (ticket == null) {
                    if (thumbnailAdmission === admission) thumbnailAdmission = null
                    ThumbnailStartResult.REJECTED
                } else ThumbnailStartResult.ACCEPTED
            }
        },
        // Inline admission only. All read preparation and native decoding remain on existing owners' lanes.
        worker = ThumbnailWorkLane { task -> requireMain(); task.run(); true },
        ui = object : ThumbnailUiDispatcher {
            override fun requireCurrentThread() = requireMain()
            override fun post(task: Runnable) { check(main.post(task)) }
            override fun later(task: Runnable, delayMillis: Long) { check(main.postDelayed(task, delayMillis)) }
            override fun remove(task: Runnable) { main.removeCallbacks(task) }
        },
        onStalled = {
            thumbnailCleanupUnconfirmed = true; thumbnailSessionFenced = true
            thumbnailIntent.set(false); thumbnailsRequested = false
            runtime.revokeThumbnails(thumbnailHost)
            thumbnailProgressed()
        },
        adapter = object : ThumbnailItemAdapter<VaultMediaReadRef, VaultMediaReadRef> {
            override val maxPageSize = VaultThumbnailLimits.MAX_FRAMES
            override fun key(item: VaultMediaReadRef) = item
            override fun isEligible(item: VaultMediaReadRef) = item in thumbnailViewport
        },
        onChanged = { thumbnailProgressed() },
    )
    var playbackSlotRetained by mutableStateOf(false); private set
    private var rootTicket: PrivateInputRetirement? = null
    private var rootTicketRetiring = false
    private var coverReceipt: Pair<Long, () -> Unit>? = null
    private var coveredGeneration: Long? = null
    private var promptDeadline = 0L
    private var pendingOpen: VaultCredentialOpening? = null
    private var pendingWorkflow: VaultRecoveryWorkflow? = null
    private var pendingBiometricWorkflow: VaultBiometricWorkflow? = null
    private var pendingSubmission: Pair<VaultCredentialRequest, VaultHostSubmission>? = null
    private val retiredRequests = mutableListOf<VaultCredentialRequest>()
    var revision by mutableLongStateOf(0); private set
    var credentialRequest by mutableStateOf<VaultCredentialRequest?>(null); private set
    private val rootAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = hierarchyChanged()
        override fun onViewDetachedFromWindow(v: View) {
            // Actual detach invalidates draw/read authority before any queued Main observation.
            invalidateActionsFromAnyThread()
            if (!main.post { stopThumbnails(clearViewport = true); hierarchyChanged() }) thumbnailHostFailure.set(true)
        }
    }

    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())

    fun attach(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (attached) return !detaching
        if (!presentation.attach(this)) return false
        attached = true; root.addOnAttachStateChangeListener(rootAttachment)
        observePresence()
        return true
    }
    fun invalidateActionsFromAnyThread() {
        actionEpoch.incrementAndGet()
        thumbnailIntent.set(false) // Draw guards lose authority before the Main cover/handoff.
    }
    fun presentationChanged() {
        requireMain()
        if (presentation.rendered.status != VaultRuntimeStatus.CATALOG ||
            thumbnailCatalogRevision != presentation.rendered.revision) stopThumbnails(clearViewport = true)
        revision++; hierarchyChanged(); mainOwner.vaultProgressed()
    }
    fun observePresence() {
        requireMain()
        if (!attached || detaching) return
        val present = inVaultRoute() && foreground()
        if (!started) {
            if (!present) return
            started = true
            runtime.observePresence(true, true)
            runtime.start()
        }
        if (!present) invalidateActionsFromAnyThread()
        runtime.observePresence(inVaultRoute(), foreground())
        if (!present) { stopThumbnails(clearViewport = true); retireRequest(dropSubmission = true) }
        revision++
    }
    fun protectBeforeDeparture() {
        requireMain()
        if (!attached) return
        invalidateActionsFromAnyThread(); guard.installProtectionBarrier()
        stopThumbnails(clearViewport = true); retireRequest(dropSubmission = true)
        if (started) runtime.observePresence(false, foreground())
    }
    fun deviceLocked() { requireMain(); invalidateActionsFromAnyThread(); stopThumbnails(clearViewport = true); retireRequest(true); if (started) runtime.deviceLocked() }
    fun surfaceCovered() { requireMain(); invalidateActionsFromAnyThread(); stopThumbnails(clearViewport = true); retireRequest(true); if (started) runtime.surfaceCovered() }
    /** System prompt focus is expected only after its exact platform call has entered. */
    fun windowFocusLost() {
        requireMain()
        val current = claim
        if (current != null && root.isCovered && owns(current) &&
            runtime.systemPromptOwnsFocus(current, activity, generation)) return // Existing barrier remains installed.
        surfaceCovered()
    }
    fun canRetryPresentationAfterFocus(): Boolean = !holdsBiometricCover() && runtime.biometricCleanupComplete()
    fun holdsBiometricCover(): Boolean = runtime.biometricPrivateCoverRequired()
    fun background() { requireMain(); invalidateActionsFromAnyThread(); stopThumbnails(clearViewport = true); retireRequest(true); if (started) runtime.observePresence(inVaultRoute(), false) }
    private fun retireRootInput() {
        val ticket = rootTicket ?: return
        rootTicketRetiring = true
        ticket.retire { main.post { if (rootTicket === ticket) hierarchyChanged() } }
    }
    fun claimFor(route: Any): VaultHostClaim? {
        revision
        if (!attached || detaching || !inVaultRoute() || routeNow() !== route || guard.hasContinuousCaptureAuthorization || !captureDrained()) return null
        return claim?.takeIf { it.route === route && it.generation == generation } ?: VaultHostClaim(this, route, generation).also { claim = it }
    }
    fun owns(value: VaultHostClaim): Boolean = attached && !detaching && claim === value && value.generation == generation &&
        routeNow() === value.route && inVaultRoute() && foreground() && !activity.isDestroyed && !activity.isFinishing &&
        !guard.hasContinuousCaptureAuthorization && captureDrained()
    fun prepareInput(value: VaultHostClaim): Boolean {
        requireMain()
        if (!owns(value)) return false
        rootTicket?.let { old ->
            if (!rootTicketRetiring) return true
            if (!old.isRetired) return false
            if (!guard.inputLifetime.finishRetirementTracking(old)) return false
            rootTicket = null; rootTicketRetiring = false
        }
        if (rootTicket == null) rootTicket = guard.inputLifetime.beginRetirementTracking() ?: return false
        return true
    }
    fun captureAction(): () -> Boolean {
        val captured = claim
        val epoch = actionEpoch.get()
        val inputEpoch = mainOwner.inputEpoch
        return { captured != null && actionEpoch.get() == epoch && mainOwner.inputEpoch == inputEpoch && owns(captured) && mainOwner.vaultActionsCurrent(captured) }
    }
    fun publicationAllowed(): Boolean {
        val current = claim ?: return false
        return owns(current) && mainOwner.vaultActionsCurrent(current) && !guard.hasContinuousCaptureAuthorization
    }
    private fun thumbnailDemandCurrent(): Boolean = thumbnailIntent.get() && thumbnailsRequested &&
        thumbnailHost?.isCurrent() == true && publicationAllowed() &&
        presentation.rendered.status == VaultRuntimeStatus.CATALOG &&
        thumbnailCatalogRevision == presentation.rendered.revision

    fun toggleThumbnails() {
        requireMain()
        if (!captureAction()() || !publicationAllowed()) return
        if (thumbnailsRequested) { stopThumbnails(); return }
        val state = presentation.rendered
        if (thumbnailSessionGeneration != generation && thumbnailResourcesRetired()) {
            // Fresh protected reveal only; stop/start and scrolling never reset the attempt/session fence.
            thumbnailSession.close()
            thumbnailSession = createThumbnailSession()
            thumbnailSessionGeneration = generation
            thumbnailSessionFenced = false; thumbnailCleanupUnconfirmed = false
        }
        if (state.status != VaultRuntimeStatus.CATALOG || state.playback.occupied || state.media.busy ||
            state.media.hasMove || state.media.hasCopy || thumbnailSessionFenced || thumbnailHostFailure.get() || !runtime.thumbnailWorkComplete() ||
            state.media.batch?.status?.let { it !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) } == true) return
        val previous = thumbnailHost
        if (previous == null || !previous.isCurrent() || thumbnailCatalogRevision != state.revision) {
            previous?.revoke()
            val epoch = actionEpoch.get()
            thumbnailHost = VaultThumbnailHostToken(generation) { actionEpoch.get() == epoch }
            thumbnailHosts += checkNotNull(thumbnailHost)
        }
        thumbnailCatalogRevision = state.revision
        thumbnailIntent.set(true); thumbnailsRequested = true
        thumbnailSession.updatePage(thumbnailViewport, enabled = true)
        thumbnailViewport.forEach(thumbnailSession::request)
    }
    fun updateThumbnailViewport(visible: List<VaultMediaReadRef>) {
        requireMain()
        val state = presentation.rendered
        val exact = if (publicationAllowed() && state.status == VaultRuntimeStatus.CATALOG)
            visible.asSequence().filter { ref -> state.items.any { it.ref == ref } }.distinct()
                .take(VaultThumbnailLimits.MAX_FRAMES).toList() else emptyList()
        if (thumbnailViewport == exact) return
        thumbnailViewport = exact
        // The shared bounded algorithm invalidates an active job on every page change.
        thumbnailAdmission?.ticket?.let(runtime::closeThumbnail)
        val enabled = thumbnailDemandCurrent()
        thumbnailSession.updatePage(exact, enabled)
        if (enabled) exact.forEach(thumbnailSession::request)
    }
    fun thumbnailState(ref: VaultMediaReadRef): ThumbnailState<VaultThumbnailFrame> {
        requireMain(); revision
        if (!publicationAllowed() || ref !in thumbnailViewport)
            return ThumbnailState.Unavailable(ThumbnailUnavailable.INACTIVE)
        if (thumbnailCleanupUnconfirmed)
            return ThumbnailState.Unavailable(ThumbnailUnavailable.RELEASE_UNCONFIRMED)
        if (!thumbnailDemandCurrent()) return ThumbnailState.Unavailable(ThumbnailUnavailable.INACTIVE)
        return thumbnailSession.state(ref)
    }
    private fun stopThumbnails(clearViewport: Boolean = false) {
        requireMain()
        thumbnailIntent.set(false); thumbnailsRequested = false
        // Revoke exact runtime read/display tokens before removing app-owned frames.
        try { thumbnailHost?.let(runtime::revokeThumbnails) }
        catch (_: Exception) { thumbnailHostFailure.set(true); thumbnailCleanupUnconfirmed = true }
        if (clearViewport) thumbnailViewport = emptyList()
        try { thumbnailSession.updatePage(emptyList(), enabled = false) }
        catch (_: Exception) { thumbnailHostFailure.set(true); thumbnailCleanupUnconfirmed = true }
        thumbnailProgressed()
    }
    fun thumbnailCompositionRemoved() { requireMain(); stopThumbnails(clearViewport = true) }
    private fun retireThumbnailsBeforeOperation(): Boolean {
        stopThumbnails()
        // Unknown native/read/Main cleanup is never silently queued behind a chosen operation.
        return runtime.thumbnailWorkComplete() && !thumbnailSession.hasActiveJob && !thumbnailSession.hasUnconfirmedResources
    }
    private fun thumbnailResourcesRetired(): Boolean = !thumbnailHostFailure.get() && !thumbnailProgressPending && runtime.thumbnailCleanupComplete() &&
        !thumbnailSession.hasActiveJob && !thumbnailSession.hasUnconfirmedResources && thumbnailSession.retainedCount == 0
    private fun thumbnailProgressed() {
        requireMain(); revision++
        // Coalesce the existing Main queue, avoiding reentry from frame.close/runtime.gate.
        if (thumbnailProgressPending) return
        thumbnailProgressPending = true
        if (!main.post {
                try {
                    runtime.thumbnailHostProgressed()
                    if (!thumbnailHostFailure.get() && runtime.thumbnailWorkComplete() &&
                        !thumbnailSession.hasActiveJob && !thumbnailSession.hasUnconfirmedResources)
                        thumbnailCleanupUnconfirmed = false
                } catch (_: Exception) {
                    thumbnailHostFailure.set(true); thumbnailCleanupUnconfirmed = true
                }
                thumbnailProgressPending = false
                hierarchyChanged(); mainOwner.vaultProgressed()
            }) {
            // Failed delivery is no release receipt; keep this Host attached and its owners live.
            thumbnailProgressPending = true
            thumbnailHostFailure.set(true); thumbnailCleanupUnconfirmed = true
        }
    }
    fun openPlayback(ref: VaultMediaReadRef) {
        requireMain()
        if (!captureAction()() || !publicationAllowed() || playbackToken != null || playbackSlot != null || playbackAcquisitionUnconfirmed) return
        if (!retireThumbnailsBeforeOperation()) return
        val epoch = actionEpoch.get()
        // Background code reads only atomic epoch; all View/Compose facts were checked on Main.
        val token = VaultPlaybackHostToken(generation) { actionEpoch.get() == epoch }
        playbackToken = token
        if (!runtime.openPlayback(ref, token)) playbackToken = null
    }
    fun playPlayback(ticket: VaultPlaybackTicket) { if (playbackActionCurrent(ticket)) runtime.playPlayback(ticket) }
    fun pausePlayback(ticket: VaultPlaybackTicket) { if (playbackActionCurrent(ticket)) runtime.pausePlayback(ticket) }
    fun seekPlayback(ticket: VaultPlaybackTicket, targetMillis: Long) {
        if (playbackActionCurrent(ticket)) runtime.seekPlayback(ticket, targetMillis)
    }
    fun closePlayback(ticket: VaultPlaybackTicket) {
        requireMain()
        if (playbackToken === ticket.host) runtime.closePlayback(ticket)
    }
    private fun playbackActionCurrent(ticket: VaultPlaybackTicket): Boolean {
        requireMain()
        return playbackToken === ticket.host && ticket.host.isCurrent() && captureAction()() && publicationAllowed()
    }
    /** Native resources remain in this stable slot until exact detach/destroy is observed. */
    fun offerPreparedPlayback(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback): Boolean {
        requireMain()
        if (!playbackActionCurrent(ticket) || ticket.host.generation != generation || playbackSlot != null) return false
        playbackTicket = ticket
        playbackAcquisitionUnconfirmed = true
        val slot = try { AndroidVaultPlaybackSurfaceSlot(activity, ticket, prepared,
            current = { playbackTicket === ticket && playbackActionCurrent(ticket) },
            changed = { main.post { playbackProgressed() } }) } catch (_: Exception) {
            presentation.playbackRetired(ticket, VaultPlaybackDrain.UNCONFIRMED)
            prepared.revoke()
            return true // The attempted acquisition remains owned and cannot be replaced.
        }
        playbackAcquisitionUnconfirmed = false
        playbackSlot = slot; playbackSlotRetained = true
        playbackContainer.addView(slot.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        playbackProgressed()
        return true
    }
    fun revokePlaybackPresentation(ticket: VaultPlaybackTicket) {
        requireMain()
        if (playbackTicket !== ticket) {
            if (playbackToken === ticket.host) playbackProgressed()
            return
        }
        playbackSlot?.cover()
        // Do not wait for hostResourcesRetired/ordinaryReleaseAllowed: they include this slot.
        playbackSlot?.requestRetirement()
        playbackProgressed()
    }
    /** Called by guard/input/lifecycle revocation; does not alter account auto-lock policy. */
    fun playbackProtectionLost() {
        requireMain()
        invalidateActionsFromAnyThread()
        stopThumbnails(clearViewport = true)
        if (playbackToken == null && playbackSlot == null && !playbackAcquisitionUnconfirmed) return
        playbackSlot?.cover()
        runtime.revokePlayback()
        playbackSlot?.requestRetirement()
        playbackProgressed()
    }
    fun playbackCompositionRemoved() {
        requireMain()
        playbackProtectionLost() // Activity/root removal is an observation, never a release receipt.
    }
    fun playbackReservation(): VaultPlaybackHostToken? { requireMain(); return playbackToken }
    fun playbackStateChanged(view: VaultPlaybackView) {
        requireMain()
        if (view.status == VaultPlaybackStatus.UNAVAILABLE && !runtime.playbackCleanupComplete())
            root.showVaultPlaybackReleaseWarning(true)
        if (runtime.playbackCleanupComplete() && !playbackAcquisitionUnconfirmed)
            root.showVaultPlaybackReleaseWarning(false)
        if (view.status == VaultPlaybackStatus.IDLE && playbackSlot == null && !playbackAcquisitionUnconfirmed &&
            runtime.playbackCleanupComplete()) playbackToken = null
        if (view.status in setOf(VaultPlaybackStatus.IDLE, VaultPlaybackStatus.RETIRING, VaultPlaybackStatus.UNAVAILABLE)) {
            revision++; hierarchyChanged(); mainOwner.vaultProgressed()
        }
    }
    fun playbackProgressed() {
        requireMain()
        val ticket = playbackTicket
        val slot = playbackSlot
        if (ticket != null && slot != null) {
            val drain = slot.observeDrain()
            presentation.playbackRetired(ticket, drain)
            if (drain == VaultPlaybackDrain.RELEASED) {
                // Only the slot's actual detach/destroy proof permits removing its stable container child.
                playbackContainer.removeView(slot.view)
                playbackSlot = null; playbackTicket = null; playbackSlotRetained = false
            }
        }
        runtime.playbackHostProgressed()
        if (runtime.playbackCleanupComplete() && !playbackAcquisitionUnconfirmed) root.showVaultPlaybackReleaseWarning(false)
        if (playbackSlot == null && !playbackAcquisitionUnconfirmed && runtime.playbackCleanupComplete()) playbackToken = null
        revision++; hierarchyChanged(); mainOwner.vaultProgressed()
    }
    fun playbackCompositionCurrent(): Boolean = playbackContainer.isAttachedToWindow &&
        playbackContainer.parent != null && (playbackSlot == null || playbackSlot?.view?.parent === playbackContainer)
    fun revealCatalog() { if (captureAction()()) runtime.revealEmptyCatalog() }
    fun confirmMoveToVault() { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.confirmMoveToVault() }
    fun copyMediaToPhone(ref: VaultMediaReadRef) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.copyMediaToPhone(ref) }
    fun confirmCopyToPhone() { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.confirmCopyToPhone() }
    fun copyMediaBatchToPhone(refs: List<VaultMediaReadRef>) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.copyMediaBatchToPhone(refs) }
    fun confirmBatchCopy(token: Any) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.confirmBatchCopy(token) }
    fun continueBatchCopy(token: Any) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.continueBatchCopy(token) }
    fun keepCurrentBatchOutputAndContinue(token: Any) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.keepCurrentBatchOutputAndContinue(token) }
    fun keepCurrentSingleOutput(token: Any) { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.keepCurrentSingleOutput(token) }
    fun reconcileMedia() { if (captureAction()() && retireThumbnailsBeforeOperation()) runtime.reconcileMedia() }
    fun cancelMedia() { if (captureAction()()) runtime.cancelMedia() }
    fun retireOrdinarySource(ref: com.easysavevideo.library.MediaReadRef, retired: () -> Unit) {
        requireMain()
        if (!attached || detaching) return
        retireOrdinary(ref, retired)
    }
    fun requestPhoneFileSelection(request: VaultPhoneFileRequest) {
        requireMain()
        if (!publicationAllowed()) { runtime.retirePhoneSelection(request); return }
        // No private content is handed to the chooser; route/focus loss revokes actual readers.
        selectPhoneFile(request)
    }
    fun lock() { if (captureAction()()) { stopThumbnails(clearViewport = true); retireRequest(true); runtime.lock() } }
    fun beginCredential(mode: VaultCredentialMode) {
        requireMain()
        if (!captureAction()() || credentialRequest != null || pendingSubmission != null || pendingOpen != null) return
        stopThumbnails(clearViewport = true)
        val workflow = when (mode) {
            VaultCredentialMode.RECOVERY_ENROLL -> runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL) ?: return
            VaultCredentialMode.RECOVERY_RESET -> runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD) ?: return
            VaultCredentialMode.RECOVERY_DISPLAY -> runtime.currentRecoveryDisplayWorkflow() ?: return
            VaultCredentialMode.RECOVERY_CONFIRM -> return // Only actual display retirement can reach confirmation.
            else -> null
        }
        val biometricWorkflow = when (mode) {
            VaultCredentialMode.BIOMETRIC_ENROLL -> runtime.beginBiometricWorkflow(VaultBiometricWorkflowKind.ENROLL) ?: return
            VaultCredentialMode.BIOMETRIC_REVOKE -> runtime.beginBiometricWorkflow(VaultBiometricWorkflowKind.REVOKE) ?: return
            else -> null
        }
        // Finish old native/input cleanup before a new dialog exists. Controlled recovery handoff grants no auth.
        if (workflow != null) {
            if (!runtime.coverForRecoveryHandoff(workflow)) { runtime.cancelRecoveryWorkflow(workflow); return }
        } else if (biometricWorkflow != null) {
            if (!runtime.coverForBiometricHandoff(biometricWorkflow)) { runtime.cancelBiometricWorkflow(biometricWorkflow); return }
        } else runtime.surfaceCovered()
        pendingOpen = VaultCredentialOpening(mode, generation); pendingWorkflow = workflow; pendingBiometricWorkflow = biometricWorkflow
        hierarchyChanged()
    }
    private fun publishCredential(mode: VaultCredentialMode, workflow: VaultRecoveryWorkflow?, biometricWorkflow: VaultBiometricWorkflow?) {
        if (biometricWorkflow != null && !runtime.biometricWorkflowCurrent(biometricWorkflow)) return
        if (mode == VaultCredentialMode.BIOMETRIC_UNLOCK) {
            val factory = biometricHostBinding() ?: return
            runtime.unlockBiometric(factory(VaultBiometricPurpose.UNLOCK, null))
            return
        }
        if (workflow != null && !runtime.recoveryWorkflowCurrent(workflow)) return
        val display = if (mode == VaultCredentialMode.RECOVERY_DISPLAY)
            runtime.takeRecoveryDisplay(checkNotNull(workflow)) ?: return else null
        val request = VaultCredentialRequest(mode, workflow, display, biometricWorkflow)
        credentialRequest = request
        promptDeadline = minOf(SystemClock.elapsedRealtime() + VaultRuntimeLimits.PROMPT_MILLIS,
            workflow?.let(runtime::recoveryPromptDeadline) ?: biometricWorkflow?.deadlineElapsedMillis ?: Long.MAX_VALUE)
        main.postDelayed({
            if (credentialRequest === request) cancelCredential(request)
            if (pendingSubmission?.first === request) {
                pendingSubmission?.second?.close(); pendingSubmission = null
                request.workflow?.let(runtime::cancelRecoveryWorkflow)
                request.biometricWorkflow?.let(runtime::cancelBiometricWorkflow)
                runtime.surfaceCovered()
            }
        }, (promptDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    fun requestCurrent(request: VaultCredentialRequest): Boolean = credentialRequest === request && request.isLive() &&
        attached && !detaching && inVaultRoute() && foreground() && SystemClock.elapsedRealtime() < promptDeadline &&
        !guard.hasContinuousCaptureAuthorization && captureDrained() && !activity.isDestroyed && !activity.isFinishing &&
        (request.workflow == null || runtime.recoveryWorkflowCurrent(request.workflow)) &&
        (request.biometricWorkflow == null || runtime.biometricWorkflowCurrent(request.biometricWorkflow))
    fun protectForModal(request: Any): Boolean = request is VaultCredentialRequest && requestCurrent(request) &&
        !guard.hasContinuousCaptureAuthorization && guard.installProtectionBarrier()
    fun submitCredential(request: VaultCredentialRequest, input: VaultCredentialInput, recoveryChoice: VaultRecoveryChoice) {
        val supported = request.mode in setOf(VaultCredentialMode.SETUP, VaultCredentialMode.UNLOCK, VaultCredentialMode.RECOVERY_ENROLL,
            VaultCredentialMode.BIOMETRIC_ENROLL, VaultCredentialMode.BIOMETRIC_REVOKE)
        if (!supported || request.mode == VaultCredentialMode.SETUP &&
            recoveryChoice !in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN)) { input.close(); return }
        submit(request, VaultHostSubmission.Password(input, recoveryChoice))
    }
    fun submitRecoveryConfirmation(request: VaultCredentialRequest, input: VaultRecoveryInput) {
        if (request.mode != VaultCredentialMode.RECOVERY_CONFIRM) { input.close(); return }
        submit(request, VaultHostSubmission.Confirmation(input))
    }
    fun submitPasswordReset(request: VaultCredentialRequest, input: VaultRecoveryInput, password: VaultCredentialInput) {
        if (request.mode != VaultCredentialMode.RECOVERY_RESET) { input.close(); password.close(); return }
        submit(request, VaultHostSubmission.Reset(input, password))
    }
    private fun submit(request: VaultCredentialRequest, submission: VaultHostSubmission) {
        requireMain()
        if (!requestCurrent(request)) { submission.close(); return }
        credentialRequest = null; request.retire(); retiredRequests += request
        val workflow = request.workflow
        if (workflow != null) {
            if (!runtime.coverForRecoveryHandoff(workflow)) { submission.close(); runtime.cancelRecoveryWorkflow(workflow); return }
        } else if (request.biometricWorkflow != null) {
            if (!runtime.coverForBiometricHandoff(request.biometricWorkflow)) {
                submission.close(); runtime.cancelBiometricWorkflow(request.biometricWorkflow); return
            }
        } else runtime.surfaceCovered()
        pendingSubmission = request to submission
        invalidateActionsFromAnyThread()
        guard.installProtectionBarrier()
        retireRootInput()
        revision++; hierarchyChanged()
    }
    fun continueRecoveryDisplay(request: VaultCredentialRequest) {
        requireMain()
        val workflow = request.workflow ?: return
        if (request.mode != VaultCredentialMode.RECOVERY_DISPLAY || !requestCurrent(request)) return
        // Erase the generated display before allowing a distinct user re-entry request to exist.
        credentialRequest = null; request.retire(); retiredRequests += request
        if (!runtime.advanceRecoveryConfirmation(workflow) || !runtime.coverForRecoveryHandoff(workflow)) {
            runtime.cancelRecoveryWorkflow(workflow); return
        }
        pendingOpen = VaultCredentialOpening(VaultCredentialMode.RECOVERY_CONFIRM, generation)
        pendingWorkflow = workflow
        invalidateActionsFromAnyThread(); guard.installProtectionBarrier(); retireRootInput()
        revision++; hierarchyChanged()
    }
    fun cancelCredential(request: VaultCredentialRequest) {
        requireMain()
        if (credentialRequest !== request) return
        retireRequest(true); request.workflow?.let(runtime::cancelRecoveryWorkflow)
        request.biometricWorkflow?.let(runtime::cancelBiometricWorkflow); runtime.surfaceCovered()
    }
    private fun retireRequest(dropSubmission: Boolean) {
        pendingOpen = null; pendingWorkflow = null; pendingBiometricWorkflow = null
        credentialRequest?.let { it.retire(); retiredRequests += it }; credentialRequest = null
        if (dropSubmission) { pendingSubmission?.second?.close(); pendingSubmission = null }
    }
    /** Runtime's exact generation. Only current root frame/detach + host resources can complete it. */
    fun cover(value: Long, receipt: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (value < generation) return
        generation = value; coveredGeneration = null; coverReceipt = value to receipt
        invalidateActionsFromAnyThread(); guard.installProtectionBarrier()
        stopThumbnails(clearViewport = true); retireRequest(dropSubmission = true)
        playbackSlot?.cover(); runtime.revokePlayback(); playbackSlot?.requestRetirement()
        retireRootInput()
        revision++; hierarchyChanged()
    }
    fun coveredFrame(value: Long) {
        requireMain()
        if (value != generation) return
        coveredGeneration = value
        hierarchyChanged()
    }
    fun pendingCoverGeneration(): Long? = coverReceipt?.first
    fun hostResourcesRetired(): Boolean = runtime.biometricCleanupComplete() && thumbnailResourcesRetired() && !playbackAcquisitionUnconfirmed && playbackSlot == null && runtime.playbackCleanupComplete() && !guard.hasRegisteredPrivateDialogs &&
        (rootTicket == null || rootTicket?.isRetired == true) && retiredRequests.all { it.retiredCleanly() }
    fun hierarchyChanged() {
        requireMain()
        if (!attached) return
        if (!root.isAttachedToWindow && root.windowToken == null) {
            invalidateActionsFromAnyThread()
            if (thumbnailsRequested || thumbnailViewport.isNotEmpty()) stopThumbnails(clearViewport = true)
            coveredGeneration = generation
        }
        // The observed cover/root-detach fact retires visual markers BEFORE aggregate resources.
        // Waiting for the aggregate cover receipt here would create a circular cleanup wait.
        if (coveredGeneration == generation) {
            thumbnailHosts.filter { !it.isCurrent() }.toList().forEach { token ->
                runtime.thumbnailHostCovered(token)
                thumbnailHosts.remove(token)
                if (thumbnailHost === token) thumbnailHost = null
            }
        }
        if (coveredGeneration == generation && hostResourcesRetired()) {
            val exact = coverReceipt
            if (exact?.first == generation) { coverReceipt = null; exact.second() }
            rootTicket?.takeIf { it.isRetired }?.let {
                if (guard.inputLifetime.finishRetirementTracking(it)) { rootTicket = null; rootTicketRetiring = false }
            }
            retiredRequests.clear()
            val opening = pendingOpen
            val mode = opening?.takeReady(generation, hostResourcesRetired(), runtime.hostCleanupComplete(),
                !detaching && inVaultRoute() && foreground() && !guard.hasContinuousCaptureAuthorization && captureDrained())
            if (mode != null && pendingOpen === opening) {
                val workflow = pendingWorkflow
                val biometric = pendingBiometricWorkflow
                pendingOpen = null; pendingWorkflow = null; pendingBiometricWorkflow = null; publishCredential(mode, workflow, biometric)
            }
            val pending = pendingSubmission
            if (pending != null && runtime.hostCleanupComplete() && inVaultRoute() && foreground() && !detaching &&
                !guard.hasContinuousCaptureAuthorization && captureDrained()) {
                pendingSubmission = null
                val request = pending.first
                val workflow = request.workflow
                if (SystemClock.elapsedRealtime() >= promptDeadline ||
                    workflow != null && !runtime.recoveryWorkflowCurrent(workflow) ||
                    request.biometricWorkflow != null && !runtime.biometricWorkflowCurrent(request.biometricWorkflow)) {
                    pending.second.close()
                    workflow?.let(runtime::cancelRecoveryWorkflow)
                    request.biometricWorkflow?.let(runtime::cancelBiometricWorkflow)
                } else when (val submission = pending.second) {
                    is VaultHostSubmission.Password -> when (request.mode) {
                        VaultCredentialMode.SETUP -> runtime.setupPassword(submission.input, submission.choice)
                        VaultCredentialMode.UNLOCK -> runtime.unlockPassword(submission.input)
                        VaultCredentialMode.RECOVERY_ENROLL -> runtime.unlockForRecovery(submission.input, checkNotNull(workflow))
                        VaultCredentialMode.BIOMETRIC_ENROLL, VaultCredentialMode.BIOMETRIC_REVOKE -> {
                            val biometric = checkNotNull(request.biometricWorkflow)
                            val factory = biometricHostBinding()
                            if (factory == null) { submission.close(); runtime.cancelBiometricWorkflow(biometric) }
                            else runtime.unlockForBiometric(submission.input, biometric, factory)
                        }
                        else -> submission.close()
                    }
                    is VaultHostSubmission.Confirmation -> runtime.confirmRecovery(checkNotNull(workflow), submission.input)
                    is VaultHostSubmission.Reset -> runtime.resetPassword(checkNotNull(workflow), submission.input, submission.password)
                }
            }
            if (detaching && actualHostRetired()) {
                root.removeOnAttachStateChangeListener(rootAttachment)
                presentation.detachAfterActualRetirement(this); attached = false
            }
        }
    }
    private fun biometricHostBinding(): VaultBiometricBindingFactory? {
        requireMain()
        val exact = claimFor(routeNow()) ?: return null
        if (!owns(exact) || !hostResourcesRetired() || !runtime.hostCleanupComplete()) return null
        val epoch = actionEpoch.get()
        val inputEpoch = mainOwner.inputEpoch
        val presentationIdentity = exact.presentation
        val attachment = activity
        val hostGeneration = generation
        val valid = AtomicBoolean(true)
        return { purpose, workflow -> VaultBiometricBinding(purpose, exact, attachment, hostGeneration, workflow) {
            // Background/crypto callbacks read only revocation atomics. The platform dispatcher is Main,
            // where the exact Activity/claim/input/window facts are rechecked immediately before submit.
            if (!valid.get() || actionEpoch.get() != epoch) false
            else if (Looper.myLooper() != Looper.getMainLooper()) true
            else {
                val current = mainOwner.inputEpoch == inputEpoch && exact.presentation === presentationIdentity &&
                    claim === exact && generation == hostGeneration && owns(exact) && root.isAttachedToWindow && root.windowToken != null
                if (!current) valid.set(false)
                current
            }
        } }
    }
    fun ordinaryReleaseAllowed(): Boolean = hostResourcesRetired() && coverReceipt == null && (!started || runtime.hostCleanupComplete())
    fun actualHostRetired(): Boolean = detaching && !root.isAttachedToWindow && root.windowToken == null && hostResourcesRetired()
    fun detach() {
        requireMain()
        if (!attached || detaching) return
        detaching = true; invalidateActionsFromAnyThread(); guard.installProtectionBarrier()
        stopThumbnails(clearViewport = true); retireRequest(true)
        if (started) runtime.observePresence(false, false)
        retireRootInput()
        hierarchyChanged()
        // The app singleton is deliberately not closed/replaced by Activity destruction.
    }
}
