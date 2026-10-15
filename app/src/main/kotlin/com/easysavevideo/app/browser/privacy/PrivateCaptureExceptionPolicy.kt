package com.easysavevideo.app.browser.privacy

/** Process-local identity only; the View/document tokens must not contain serialized browsing data. */
internal class PrivateCaptureTarget(
    val session: PrivateBrowsingSession,
    val tabIdentity: Any,
    val documentIdentity: Any,
    val viewIdentity: Any,
) {
    override fun toString() = "PrivateCaptureTarget([redacted])"
}
internal enum class PrivateCaptureAudio { SILENT, DEVICE_AUDIO }
internal enum class PrivateCaptureMode { BASIC, REGION }
internal data class PrivateCaptureDescriptor(val audio: PrivateCaptureAudio, val mode: PrivateCaptureMode)

/**
 * Linearizes queue registration against revoke. registerQueued must only synchronously register
 * this exact owner with its stop/drain controller; it must not capture, block, or reenter policy.
 * After consumption, revoke stops that registered queued/native owner and awaits actual drain.
 */
internal class PrivateCaptureStartPermit internal constructor(
    val descriptor: PrivateCaptureDescriptor,
    private val ownerIdentity: Any,
) {
    private var revoked = false
    private var consumed = false
    internal enum class Registration { REJECTED, REGISTERED, FAILED }
    internal fun consumeDeferred(callerIdentity: Any, registerQueued: () -> Unit): Registration = synchronized(this) {
        if (callerIdentity !== ownerIdentity || revoked || consumed) Registration.REJECTED
        else {
            consumed = true
            try { registerQueued(); Registration.REGISTERED }
            catch (_: Exception) { revoked = true; Registration.FAILED }
        }
    }
    fun consumeAndRegister(callerIdentity: Any, registerQueued: () -> Unit): Boolean {
        val result = consumeDeferred(callerIdentity, registerQueued)
        return result == Registration.REGISTERED
    }
    @Synchronized internal fun revoke() { revoked = true }
    @Synchronized internal fun startedAllowed() = consumed && !revoked
}
internal enum class PrivateCaptureScope { API31_TO_33_DEFAULT_DISPLAY, API34_PLUS_USER_CHOICE }
internal enum class PrivateCaptureRecentsProtection { API33_RECENTS_SUPPRESSED, LEGACY_RECENTS_RISK_ACCEPTED }
internal enum class PrivateCaptureDisposition { KEEP_VALID_OUTPUT, DISCARD_UNPUBLISHED_OUTPUT }
internal enum class PrivateCapturePhase { IDLE, CONSENT_PENDING, GRANTED_PROTECTED, ARMING, ACTIVE, STOPPING, BLOCKED }

/** A fixture is not platform validation. Only the reviewed Activity adapter may issue real evidence. */
internal class PrivateCaptureSurfaceEvidence(
    val target: PrivateCaptureTarget,
    val ownerIdentity: Any,
    val surfaceIdentity: Any,
    val scope: PrivateCaptureScope,
    val recentsProtection: PrivateCaptureRecentsProtection,
) {
    override fun toString() = "PrivateCaptureSurfaceEvidence([redacted])"
}
internal class PrivateCaptureAttempt internal constructor(
    val target: PrivateCaptureTarget,
    internal val ownerIdentity: Any,
    val scope: PrivateCaptureScope,
    val descriptor: PrivateCaptureDescriptor,
) {
    internal var permit: PrivateCaptureStartPermit? = null
    internal var stopDisposition: PrivateCaptureDisposition? = null
    internal var discardAccepted = false
    override fun toString() = "PrivateCaptureAttempt([redacted])"
}

/**
 * Called on the owner thread. Protection restoration must begin synchronously before return.
 * protected callback means the current/retiring private surface is safely covered/protected;
 * it is not merely an enqueued flag change. No URL, Intent or projection token crosses this port.
 */
internal interface PrivateCaptureSurfacePort {
    /** Synchronous only; check stillAllowed immediately before clearing any protection flag. */
    fun openException(evidence: PrivateCaptureSurfaceEvidence, stillAllowed: () -> Boolean): Boolean
    fun restoreProtection(target: PrivateCaptureTarget, protected: () -> Unit)
}
internal interface PrivateCaptureStopPort {
    /** done means actual projection/video/audio/native-owner release, never a stop request or UI state. */
    fun stop(ownerIdentity: Any, disposition: PrivateCaptureDisposition, done: () -> Unit)
    /** A later explicit discard affects only unpublished output; it is not a second native stop. */
    fun discardUnpublished(ownerIdentity: Any)
}

/**
 * Setup-only one-shot exception. User action, accepted OS grant, exact recording owner and owned
 * window evidence are separate inputs. Accepted submission retires this entire policy; subsequent
 * page/profile/lifecycle changes cannot become Stop through a retained setup callback.
 */
internal class PrivateCaptureExceptionPolicy(
    surface: PrivateCaptureSurfacePort,
    stopPort: PrivateCaptureStopPort,
    targetCurrent: (PrivateCaptureTarget) -> Boolean,
    ownerCurrent: (Any) -> Boolean,
    surfaceCurrent: (PrivateCaptureSurfaceEvidence) -> Boolean,
    scheduler: PrivatePolicyScheduler,
    onOwnerThread: (() -> Unit) -> Unit,
    changed: (PrivateCapturePhase) -> Unit = {},
) {
    private var surface: PrivateCaptureSurfacePort? = surface
    private var stopPort: PrivateCaptureStopPort? = stopPort
    private var targetCurrent: ((PrivateCaptureTarget) -> Boolean)? = targetCurrent
    private var ownerCurrent: ((Any) -> Boolean)? = ownerCurrent
    private var surfaceCurrent: ((PrivateCaptureSurfaceEvidence) -> Boolean)? = surfaceCurrent
    private var scheduler: PrivatePolicyScheduler? = scheduler
    private var onOwnerThread: ((() -> Unit) -> Unit)? = onOwnerThread
    private var changed: ((PrivateCapturePhase) -> Unit)? = changed
    private var retired = false
    companion object { const val CONSENT_WAIT_MILLIS = 120_000L; const val ARM_WAIT_MILLIS = 10_000L; const val DRAIN_WAIT_MILLIS = 15_000L }
    var phase = PrivateCapturePhase.IDLE; private set
    private var current: PrivateCaptureAttempt? = null
    private var evidence: PrivateCaptureSurfaceEvidence? = null
    private var cancelWatch: (() -> Unit)? = null
    private var watchGeneration = 0L
    private fun publish(value: PrivateCapturePhase): Boolean {
        phase = value
        return try { changed?.invoke(value); true } catch (_: Exception) { false }
    }
    private fun cancelTimer() {
        watchGeneration++
        val cancel = cancelWatch; cancelWatch = null
        try { cancel?.invoke() } catch (_: Exception) { /* Cancellation failure cannot interrupt protection/stop. */ }
    }
    private fun deliver(action: () -> Unit) {
        try { onOwnerThread?.invoke(action) } catch (_: Exception) { /* No release is acknowledged; owner watchdog remains. */ }
    }
    private fun valid(attempt: PrivateCaptureAttempt): Boolean {
        if (current !== attempt || !attempt.target.session.isLive()) return false
        val accepted = try { targetCurrent?.invoke(attempt.target) == true && ownerCurrent?.invoke(attempt.ownerIdentity) == true } catch (_: Exception) { false }
        return accepted && current === attempt && attempt.target.session.isLive()
    }
    private fun visible(attempt: PrivateCaptureAttempt): Boolean = evidence?.let {
        it.target === attempt.target && it.ownerIdentity === attempt.ownerIdentity && it.scope == attempt.scope &&
            try { surfaceCurrent?.invoke(it) == true } catch (_: Exception) { false }
    } == true
    private fun watch(attempt: PrivateCaptureAttempt, millis: Long, onlyPhase: PrivateCapturePhase) {
        if (current !== attempt || phase != onlyPhase) return
        cancelTimer()
        val generation = watchGeneration
        try {
            val cancel = requireNotNull(scheduler).after(millis) {
                // No attempt is captured: even a cancelled timer retained by a scheduler cannot
                // keep the initiating target/profile after the submitted policy is retired.
                val active = current
                if (watchGeneration == generation && active != null && phase == onlyPhase)
                    revoke(active, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            }
            if (current === attempt && phase == onlyPhase) cancelWatch = cancel
            else try { cancel() } catch (_: Exception) { }
        } catch (_: Exception) { revoke(attempt, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) }
    }
    /** Caller abandons an already-reserved owner if null is returned. Never called by page/script events. */
    fun requestFromExplicitAction(target: PrivateCaptureTarget, ownerIdentity: Any, scope: PrivateCaptureScope,
        disclosureAccepted: Boolean, descriptor: PrivateCaptureDescriptor): PrivateCaptureAttempt? {
        if (retired || phase != PrivateCapturePhase.IDLE || current != null || !disclosureAccepted || !target.session.isLive()) return null
        val allowed = try { targetCurrent?.invoke(target) == true && ownerCurrent?.invoke(ownerIdentity) == true } catch (_: Exception) { false }
        if (!allowed) return null
        val attempt = PrivateCaptureAttempt(target, ownerIdentity, scope, descriptor)
        current = attempt; evidence = null
        if (!publish(PrivateCapturePhase.CONSENT_PENDING)) { revoke(attempt); return null }
        watch(attempt, CONSENT_WAIT_MILLIS, PrivateCapturePhase.CONSENT_PENDING)
        return attempt.takeIf { current === it && phase == PrivateCapturePhase.CONSENT_PENDING }
    }
    /** Exact RecordingConsentController/owner acceptance, not just RESULT_OK from an arbitrary result. */
    fun projectionAccepted(attempt: PrivateCaptureAttempt): Boolean {
        if (phase != PrivateCapturePhase.CONSENT_PENDING || current !== attempt) return false
        if (!valid(attempt)) { revoke(attempt); return false }
        cancelTimer()
        if (!publish(PrivateCapturePhase.GRANTED_PROTECTED)) { revoke(attempt); return false }
        watch(attempt, ARM_WAIT_MILLIS, PrivateCapturePhase.GRANTED_PROTECTED)
        return current === attempt && phase == PrivateCapturePhase.GRANTED_PROTECTED
    }
    /** Returns a revocable one-shot queue-registration permit, never permission for an unregistered start. */
    fun openForSurface(attempt: PrivateCaptureAttempt, proof: PrivateCaptureSurfaceEvidence): PrivateCaptureStartPermit? {
        if (phase != PrivateCapturePhase.GRANTED_PROTECTED || current !== attempt) return null
        if (!valid(attempt)) { revoke(attempt); return null }
        if (proof.target !== attempt.target || proof.ownerIdentity !== attempt.ownerIdentity || proof.scope != attempt.scope) return null
        val surfaceReady = try { surfaceCurrent?.invoke(proof) == true } catch (_: Exception) { false }
        if (!surfaceReady) return null
        evidence = proof; cancelTimer()
        if (!publish(PrivateCapturePhase.ARMING)) { revoke(attempt); return null }
        if (phase != PrivateCapturePhase.ARMING || !valid(attempt) || !visible(attempt)) {
            revoke(attempt, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return null
        }
        val opened = try { requireNotNull(surface).openException(proof) {
            phase == PrivateCapturePhase.ARMING && valid(attempt) && visible(attempt)
        } } catch (_: Exception) { false }
        if (!opened || phase != PrivateCapturePhase.ARMING || !valid(attempt) || !visible(attempt)) {
            revoke(attempt, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return null
        }
        watch(attempt, ARM_WAIT_MILLIS, PrivateCapturePhase.ARMING)
        if (phase != PrivateCapturePhase.ARMING || !valid(attempt) || !visible(attempt) || phase != PrivateCapturePhase.ARMING) { revoke(attempt); return null }
        return PrivateCaptureStartPermit(attempt.descriptor, attempt.ownerIdentity).also { attempt.permit = it }
    }
    /** Accepted submission transfers native ownership even while still ARMING. No Stop is issued.
     * The runtime takes over the acquisition deadline. This policy can retain no Activity closure.
     */
    fun retireAfterSubmission(attempt: PrivateCaptureAttempt): Boolean {
        if (current !== attempt || phase != PrivateCapturePhase.ARMING || attempt.permit == null) return false
        cancelTimer()
        attempt.permit = null
        current = null; evidence = null; retired = true
        surface = null; stopPort = null; targetCurrent = null; ownerCurrent = null
        surfaceCurrent = null; scheduler = null; onOwnerThread = null; changed = null
        phase = PrivateCapturePhase.IDLE
        return true
    }

    /** Revocation never deletes already committed output; discard affects the exact unpublished owner only. */
    fun revoke(attempt: PrivateCaptureAttempt, disposition: PrivateCaptureDisposition = PrivateCaptureDisposition.KEEP_VALID_OUTPUT): Boolean {
        if (current !== attempt) return false
        if (phase in setOf(PrivateCapturePhase.STOPPING, PrivateCapturePhase.BLOCKED)) {
            if (disposition == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT && !attempt.discardAccepted) {
                attempt.stopDisposition = disposition
                try { requireNotNull(stopPort).discardUnpublished(attempt.ownerIdentity); attempt.discardAccepted = true }
                catch (_: Exception) { if (current === attempt) publish(PrivateCapturePhase.BLOCKED); return false }
            }
            return true
        }
        attempt.permit?.revoke()
        attempt.stopDisposition = disposition
        cancelTimer()
        publish(PrivateCapturePhase.STOPPING) // Observer failure must not interrupt the two safety effects below.
        var starting = true; var protected = false; var released = false; var failed = false
        fun settle() {
            if (starting || failed || current !== attempt || !protected || !released) return
            cancelTimer(); evidence = null; current = null
            publish(PrivateCapturePhase.IDLE)
        }
        // Restore/cover starts before stop. Both requests are still attempted if one throws.
        try { requireNotNull(surface).restoreProtection(attempt.target) { deliver { protected = true; settle() } } }
        catch (_: Exception) { failed = true }
        try {
            val requested = attempt.stopDisposition ?: disposition
            requireNotNull(stopPort).stop(attempt.ownerIdentity, requested) { deliver { released = true; settle() } }
            if (requested == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT) attempt.discardAccepted = true
        }
        catch (_: Exception) { failed = true }
        starting = false
        if (failed) publish(PrivateCapturePhase.BLOCKED)
        else {
            settle()
            if (current === attempt) try {
                cancelWatch = requireNotNull(scheduler).after(DRAIN_WAIT_MILLIS) {
                    if (current === attempt) publish(PrivateCapturePhase.BLOCKED)
                }
            } catch (_: Exception) { publish(PrivateCapturePhase.BLOCKED) }
        }
        return !failed
    }
}
