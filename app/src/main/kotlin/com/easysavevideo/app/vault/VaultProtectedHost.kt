package com.easysavevideo.app.vault

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.core.model.VaultRecoveryChoice
import java.util.concurrent.atomic.AtomicLong

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
    private var rootTicket: PrivateInputRetirement? = null
    private var rootTicketRetiring = false
    private var coverReceipt: Pair<Long, () -> Unit>? = null
    private var coveredGeneration: Long? = null
    private var promptDeadline = 0L
    private var pendingOpen: VaultCredentialOpening? = null
    private var pendingWorkflow: VaultRecoveryWorkflow? = null
    private var pendingSubmission: Pair<VaultCredentialRequest, VaultHostSubmission>? = null
    private val retiredRequests = mutableListOf<VaultCredentialRequest>()
    var revision by mutableLongStateOf(0); private set
    var credentialRequest by mutableStateOf<VaultCredentialRequest?>(null); private set
    private val rootAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = hierarchyChanged()
        override fun onViewDetachedFromWindow(v: View) { main.post { hierarchyChanged() } }
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
    fun invalidateActionsFromAnyThread() { actionEpoch.incrementAndGet() }
    fun presentationChanged() { requireMain(); revision++; hierarchyChanged(); mainOwner.vaultProgressed() }
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
        if (!present) retireRequest(dropSubmission = true)
        revision++
    }
    fun protectBeforeDeparture() {
        requireMain()
        if (!attached) return
        invalidateActionsFromAnyThread(); retireRequest(dropSubmission = true)
        if (started) runtime.observePresence(false, foreground())
        guard.installProtectionBarrier()
    }
    fun deviceLocked() { requireMain(); invalidateActionsFromAnyThread(); retireRequest(true); if (started) runtime.deviceLocked() }
    fun surfaceCovered() { requireMain(); invalidateActionsFromAnyThread(); retireRequest(true); if (started) runtime.surfaceCovered() }
    fun background() { requireMain(); invalidateActionsFromAnyThread(); retireRequest(true); if (started) runtime.observePresence(inVaultRoute(), false) }
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
    fun revealCatalog() { if (captureAction()()) runtime.revealEmptyCatalog() }
    fun confirmMoveToVault() { if (captureAction()()) runtime.confirmMoveToVault() }
    fun copyMediaToPhone(ref: VaultMediaReadRef) { if (captureAction()()) runtime.copyMediaToPhone(ref) }
    fun confirmCopyToPhone() { if (captureAction()()) runtime.confirmCopyToPhone() }
    fun copyMediaBatchToPhone(refs: List<VaultMediaReadRef>) { if (captureAction()()) runtime.copyMediaBatchToPhone(refs) }
    fun confirmBatchCopy(token: Any) { if (captureAction()()) runtime.confirmBatchCopy(token) }
    fun continueBatchCopy(token: Any) { if (captureAction()()) runtime.continueBatchCopy(token) }
    fun keepCurrentBatchOutputAndContinue(token: Any) { if (captureAction()()) runtime.keepCurrentBatchOutputAndContinue(token) }
    fun keepCurrentSingleOutput(token: Any) { if (captureAction()()) runtime.keepCurrentSingleOutput(token) }
    fun reconcileMedia() { if (captureAction()()) runtime.reconcileMedia() }
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
    fun lock() { if (captureAction()()) { retireRequest(true); runtime.lock() } }
    fun beginCredential(mode: VaultCredentialMode) {
        requireMain()
        if (!captureAction()() || credentialRequest != null || pendingSubmission != null || pendingOpen != null) return
        val workflow = when (mode) {
            VaultCredentialMode.RECOVERY_ENROLL -> runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.ENROLL) ?: return
            VaultCredentialMode.RECOVERY_RESET -> runtime.beginRecoveryWorkflow(VaultRecoveryWorkflowKind.RESET_PASSWORD) ?: return
            VaultCredentialMode.RECOVERY_DISPLAY -> runtime.currentRecoveryDisplayWorkflow() ?: return
            VaultCredentialMode.RECOVERY_CONFIRM -> return // Only actual display retirement can reach confirmation.
            else -> null
        }
        // Finish old native/input cleanup before a new dialog exists. Controlled recovery handoff grants no auth.
        if (workflow != null) {
            if (!runtime.coverForRecoveryHandoff(workflow)) { runtime.cancelRecoveryWorkflow(workflow); return }
        } else runtime.surfaceCovered()
        pendingOpen = VaultCredentialOpening(mode, generation); pendingWorkflow = workflow
        hierarchyChanged()
    }
    private fun publishCredential(mode: VaultCredentialMode, workflow: VaultRecoveryWorkflow?) {
        if (workflow != null && !runtime.recoveryWorkflowCurrent(workflow)) return
        val display = if (mode == VaultCredentialMode.RECOVERY_DISPLAY)
            runtime.takeRecoveryDisplay(checkNotNull(workflow)) ?: return else null
        val request = VaultCredentialRequest(mode, workflow, display)
        credentialRequest = request
        promptDeadline = minOf(SystemClock.elapsedRealtime() + VaultRuntimeLimits.PROMPT_MILLIS,
            workflow?.let(runtime::recoveryPromptDeadline) ?: Long.MAX_VALUE)
        main.postDelayed({
            if (credentialRequest === request) cancelCredential(request)
            if (pendingSubmission?.first === request) {
                pendingSubmission?.second?.close(); pendingSubmission = null
                request.workflow?.let(runtime::cancelRecoveryWorkflow)
                runtime.surfaceCovered()
            }
        }, (promptDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    fun requestCurrent(request: VaultCredentialRequest): Boolean = credentialRequest === request && request.isLive() &&
        attached && !detaching && inVaultRoute() && foreground() && SystemClock.elapsedRealtime() < promptDeadline &&
        !guard.hasContinuousCaptureAuthorization && captureDrained() && !activity.isDestroyed && !activity.isFinishing &&
        (request.workflow == null || runtime.recoveryWorkflowCurrent(request.workflow))
    fun protectForModal(request: Any): Boolean = request is VaultCredentialRequest && requestCurrent(request) &&
        !guard.hasContinuousCaptureAuthorization && guard.installProtectionBarrier()
    fun submitCredential(request: VaultCredentialRequest, input: VaultCredentialInput, recoveryChoice: VaultRecoveryChoice) {
        val supported = request.mode in setOf(VaultCredentialMode.SETUP, VaultCredentialMode.UNLOCK, VaultCredentialMode.RECOVERY_ENROLL)
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
        retireRequest(true); request.workflow?.let(runtime::cancelRecoveryWorkflow); runtime.surfaceCovered()
    }
    private fun retireRequest(dropSubmission: Boolean) {
        pendingOpen = null; pendingWorkflow = null
        credentialRequest?.let { it.retire(); retiredRequests += it }; credentialRequest = null
        if (dropSubmission) { pendingSubmission?.second?.close(); pendingSubmission = null }
    }
    /** Runtime's exact generation. Only current root frame/detach + host resources can complete it. */
    fun cover(value: Long, receipt: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (value < generation) return
        generation = value; coveredGeneration = null; coverReceipt = value to receipt
        invalidateActionsFromAnyThread(); retireRequest(dropSubmission = true)
        guard.installProtectionBarrier()
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
    fun hostResourcesRetired(): Boolean = !guard.hasRegisteredPrivateDialogs &&
        (rootTicket == null || rootTicket?.isRetired == true) && retiredRequests.all { it.retiredCleanly() }
    fun hierarchyChanged() {
        requireMain()
        if (!attached) return
        if (!root.isAttachedToWindow && root.windowToken == null) coveredGeneration = generation
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
                pendingOpen = null; pendingWorkflow = null; publishCredential(mode, workflow)
            }
            val pending = pendingSubmission
            if (pending != null && runtime.hostCleanupComplete() && inVaultRoute() && foreground() && !detaching &&
                !guard.hasContinuousCaptureAuthorization && captureDrained()) {
                pendingSubmission = null
                val request = pending.first
                val workflow = request.workflow
                if (SystemClock.elapsedRealtime() >= promptDeadline ||
                    workflow != null && !runtime.recoveryWorkflowCurrent(workflow)) {
                    pending.second.close()
                    workflow?.let(runtime::cancelRecoveryWorkflow)
                } else when (val submission = pending.second) {
                    is VaultHostSubmission.Password -> when (request.mode) {
                        VaultCredentialMode.SETUP -> runtime.setupPassword(submission.input, submission.choice)
                        VaultCredentialMode.UNLOCK -> runtime.unlockPassword(submission.input)
                        VaultCredentialMode.RECOVERY_ENROLL -> runtime.unlockForRecovery(submission.input, checkNotNull(workflow))
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
    fun ordinaryReleaseAllowed(): Boolean = hostResourcesRetired() && coverReceipt == null && (!started || runtime.hostCleanupComplete())
    fun actualHostRetired(): Boolean = detaching && !root.isAttachedToWindow && root.windowToken == null && hostResourcesRetired()
    fun detach() {
        requireMain()
        if (!attached || detaching) return
        detaching = true; invalidateActionsFromAnyThread(); retireRequest(true)
        if (started) runtime.observePresence(false, false)
        guard.installProtectionBarrier()
        retireRootInput()
        hierarchyChanged()
        // The app singleton is deliberately not closed/replaced by Activity destruction.
    }
}
