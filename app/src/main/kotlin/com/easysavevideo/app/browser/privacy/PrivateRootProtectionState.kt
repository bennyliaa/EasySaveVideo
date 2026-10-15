package com.easysavevideo.app.browser.privacy

/** Process-local reference identities only. No browsing data or persisted exception state. */
internal class PrivateRootIdentity(
    val window: Any,
    val decor: Any,
    val root: Any,
    val target: Any,
    val presentation: Any,
) {
    fun sameAs(other: PrivateRootIdentity) = window === other.window && decor === other.decor &&
        root === other.root && target === other.target && presentation === other.presentation
    override fun toString() = "PrivateRootIdentity([redacted])"
}

internal class PrivateProtectionTurn internal constructor(val generation: Long, val identity: PrivateRootIdentity)
internal class PrivateCoverDraw internal constructor(internal val turn: PrivateProtectionTurn)

internal enum class PrivateRootPresentationState { COVERED, PRIVATE_VISIBLE, PRIVATE_CAPTURE_VISIBLE, ORDINARY_VISIBLE, DISPOSED }
internal enum class PrivateRevealResult { REVEALED, PENDING, STALE, BLOCKED }

/** Captured on the owner thread after rechecking the exact claim and real Android parents. */
internal data class PrivateRevealReadback(
    val secure: Boolean,
    val structureExcluded: Boolean,
    val contentCaptureDisabled: Boolean,
    val recentsProtection: Boolean,
    val integrationReady: Boolean,
    val claimCurrent: Boolean,
    val compositionApplied: Boolean,
    val attached: Boolean,
    val focused: Boolean,
    val captureExceptionInactive: Boolean,
    val continuousCaptureAuthorized: Boolean = false,
)

/** Ordinary departure is distinct from startup. Values must come from a fresh owner check. */
internal data class PrivateOrdinaryDepartureReadback(
    val ordinaryCurrent: Boolean,
    val privateViewsDetached: Boolean,
    val privateDialogsAbsent: Boolean,
    val privateCallbacksRetired: Boolean,
    val captureDrained: Boolean,
    /** Independent projection may remain live after every private browser source has detached. */
    val continuousCaptureAuthorized: Boolean = false,
    val independentRecordingOwner: Boolean = false,
) {
    val complete get() = ordinaryCurrent && privateViewsDetached && privateDialogsAbsent &&
        privateCallbacksRetired && (captureDrained || continuousCaptureAuthorized || independentRecordingOwner)
}

/**
 * Production state shared by the Android root and its JVM tests. A cover draw followed by its
 * hardware commit is a local submission acknowledgment, never global compositor/snapshot proof.
 * Every method is owner-thread confined by the Android adapter.
 */
internal class PrivateRootProtectionState {
    var generation = 0L; private set
    var presentationState = PrivateRootPresentationState.COVERED; private set
    var visibleIdentity: PrivateRootIdentity? = null; private set
    private var privateWasReserved = false

    fun reservePrivate() { if (!disposed) privateWasReserved = true }
    var covered = true; private set
    var suppressStructure = true; private set
    var disposed = false; private set
    var current: PrivateProtectionTurn? = null; private set
    var coverFrameCommitted = false; private set
    var coverDrawn = false; private set
    var compositionApplied = false; private set
    private var ordinaryDepartureTurn: PrivateProtectionTurn? = null
    private var pendingDraw: PrivateCoverDraw? = null

    fun begin(identity: PrivateRootIdentity): PrivateProtectionTurn? {
        if (disposed) return null
        invalidate()
        return PrivateProtectionTurn(generation, identity).also { current = it }
    }
    fun beginOrdinaryDeparture(identity: PrivateRootIdentity): PrivateProtectionTurn? {
        if (!privateWasReserved) return null
        return begin(identity)?.also { ordinaryDepartureTurn = it }
    }
    fun isCurrent(turn: PrivateProtectionTurn, identity: PrivateRootIdentity) =
        !disposed && covered && current === turn && turn.identity.sameAs(identity)

    /** Old covered frames cannot prove a hierarchy that was composed only afterward. */
    fun acknowledgeAppliedComposition(turn: PrivateProtectionTurn, identity: PrivateRootIdentity): Boolean {
        if (!isCurrent(turn, identity)) return false
        if (!compositionApplied) {
            compositionApplied = true
            pendingDraw = null; coverDrawn = false; coverFrameCommitted = false
        }
        return true
    }

    /** Called only after the native opaque draw, including software draws for ordinary startup. */
    fun drawn(turn: PrivateProtectionTurn, identity: PrivateRootIdentity, attached: Boolean,
        hardware: Boolean, hasArea: Boolean): PrivateCoverDraw? {
        if (!isCurrent(turn, identity) || !attached || !hasArea) return null
        coverDrawn = true
        if (!hardware || coverFrameCommitted || pendingDraw != null) return null
        return PrivateCoverDraw(turn).also { pendingDraw = it }
    }
    fun registrationUnavailable(draw: PrivateCoverDraw) {
        if (pendingDraw === draw) pendingDraw = null
    }
    fun commit(draw: PrivateCoverDraw, identity: PrivateRootIdentity, attached: Boolean,
        hardware: Boolean): Boolean {
        if (pendingDraw !== draw || !coverDrawn || !attached || !hardware ||
            !isCurrent(draw.turn, identity)) return false
        pendingDraw = null
        coverFrameCommitted = true
        return true
    }
    /** Safe ordinary content can be released without claiming hardware/private protection proof. */
    fun releaseOrdinary(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        ordinaryIsCurrent: Boolean, privateContentAttached: Boolean): Boolean {
        if (privateWasReserved || !isCurrent(turn, identity) || !coverDrawn ||
            !ordinaryIsCurrent || privateContentAttached) return false
        return releaseOrdinaryState()
    }

    fun revealProtectedPrivate(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        readback: PrivateRevealReadback): PrivateRevealResult {
        if (!privateWasReserved || ordinaryDepartureTurn != null || !isCurrent(turn, identity) || !readback.claimCurrent) return PrivateRevealResult.STALE
        if ((!readback.secure && !readback.continuousCaptureAuthorized) || !readback.structureExcluded || !suppressStructure ||
            !readback.contentCaptureDisabled || !readback.recentsProtection ||
            !readback.integrationReady || (!readback.captureExceptionInactive && !readback.continuousCaptureAuthorized)) return PrivateRevealResult.BLOCKED
        if (!compositionApplied || !readback.compositionApplied || !readback.attached || !readback.focused ||
            !coverFrameCommitted) return PrivateRevealResult.PENDING
        retireTurn()
        visibleIdentity = identity
        presentationState = if (readback.continuousCaptureAuthorized) PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE
            else PrivateRootPresentationState.PRIVATE_VISIBLE
        covered = false // Structure suppression deliberately survives this transition.
        return PrivateRevealResult.REVEALED
    }

    /** Exact protected composition/frame first; the guard separately owns the secure-flag operation. */
    fun revealCapture(turn: PrivateProtectionTurn, identity: PrivateRootIdentity): Boolean {
        if (!privateWasReserved || ordinaryDepartureTurn != null || !isCurrent(turn, identity) ||
            !compositionApplied || !coverFrameCommitted || !suppressStructure) return false
        retireTurn()
        visibleIdentity = identity
        presentationState = PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE
        covered = false
        return true
    }

    fun releasePresentedOrdinary(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        readback: PrivateOrdinaryDepartureReadback): Boolean {
        if (!privateWasReserved || ordinaryDepartureTurn !== turn || !isCurrent(turn, identity) || !compositionApplied || !coverDrawn || !readback.complete) return false
        return releaseOrdinaryState()
    }
    private fun retireTurn() {
        generation++
        current = null; ordinaryDepartureTurn = null; pendingDraw = null; compositionApplied = false; coverDrawn = false; coverFrameCommitted = false
    }
    private fun releaseOrdinaryState(): Boolean {
        retireTurn(); visibleIdentity = null
        presentationState = PrivateRootPresentationState.ORDINARY_VISIBLE
        suppressStructure = false; covered = false
        return true
    }
    /** Detach, replacement, a newer cover and disposal all retire prior callbacks synchronously. */
    fun invalidate() {
        generation++
        covered = true; suppressStructure = true; visibleIdentity = null
        presentationState = if (disposed) PrivateRootPresentationState.DISPOSED else PrivateRootPresentationState.COVERED
        current = null; ordinaryDepartureTurn = null; pendingDraw = null; compositionApplied = false; coverDrawn = false; coverFrameCommitted = false
    }
    fun close() { invalidate(); disposed = true; presentationState = PrivateRootPresentationState.DISPOSED }
}

/** App-owned opt-in, independent of the system/service/user effective availability getter. */
internal class PrivateContentCaptureSetting(private val ordinaryOptIn: Boolean) {
    fun protect(setEnabled: (Boolean) -> Unit) = setEnabled(false)
    fun restoreOrdinary(setEnabled: (Boolean) -> Unit) = setEnabled(ordinaryOptIn)
}
