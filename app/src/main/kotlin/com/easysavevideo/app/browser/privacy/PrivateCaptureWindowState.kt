package com.easysavevideo.app.browser.privacy

/** Identity-only, process-local window readback. Never contains pixels, URLs or projection data. */
internal class PrivateCaptureWindowSnapshot(
    val window: Any, val decor: Any, val root: Any, val view: Any, val document: Any,
    val presentation: Any, val windowToken: Any, val parents: List<Any>,
    val layout: PrivateCaptureWindowLayout,
) {
    fun sameAs(other: PrivateCaptureWindowSnapshot) = window === other.window && decor === other.decor &&
        root === other.root && windowToken === other.windowToken
    override fun toString() = "PrivateCaptureWindowSnapshot([redacted])"
}
internal data class PrivateCaptureWindowRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class PrivateCaptureWindowLayout(
    val generation: Long, val root: PrivateCaptureWindowRect, val view: PrivateCaptureWindowRect,
    val metrics: PrivateCaptureWindowRect, val maximumMetrics: PrivateCaptureWindowRect,
    val systemInsets: PrivateCaptureWindowRect, val imeInsets: PrivateCaptureWindowRect,
    val cutoutInsets: PrivateCaptureWindowRect, val rotation: Int, val displayId: Int,
    val densityDpi: Int, val density: Float, val fontScale: Float, val orientation: Int,
)
internal class PrivateCaptureWindowDraw internal constructor(
    internal val request: Any, internal val snapshot: PrivateCaptureWindowSnapshot,
)
internal class PrivateCaptureWindowTicket internal constructor(internal val draw: PrivateCaptureWindowDraw)

/**
 * Production admission state. Android supplies actual applied-composition/draw/commit callbacks;
 * these host-testable transitions are not platform or compositor acceptance.
 * All operations are owner-thread confined. No lock spans a Window, provider or media call.
 */
internal class PrivateCaptureWindowState {
    private var request: Any? = null
    private var owner: Any? = null
    private var target: Any? = null
    private var presentationId: Long? = null
    private var descriptor: PrivateCaptureDescriptor? = null
    private var compositionApplied = false
    private var draw: PrivateCaptureWindowDraw? = null
    private var ticket: PrivateCaptureWindowTicket? = null
    private var evidence: Any? = null
    private var invalidated = false
    private var consumed = false
    var opened = false; private set
    val hasRequest get() = request != null
    val hasFrameObservation get() = draw != null
    val hasEvidence get() = evidence != null
    val isInvalidated get() = invalidated

    fun request(target: Any, owner: Any, presentationId: Long, descriptor: PrivateCaptureDescriptor): Boolean {
        if (request != null || invalidated || descriptor.mode != PrivateCaptureMode.BASIC) return false
        this.target = target; this.owner = owner; this.presentationId = presentationId; this.descriptor = descriptor
        request = Any()
        return true
    }
    fun acknowledgeComposition(presentationId: Long, descriptor: PrivateCaptureDescriptor): Boolean {
        if (request == null || invalidated || this.presentationId != presentationId || this.descriptor != descriptor) return false
        if (!compositionApplied) { compositionApplied = true; draw = null; ticket = null; evidence = null }
        return true
    }
    fun drawn(snapshot: PrivateCaptureWindowSnapshot): PrivateCaptureWindowDraw? {
        val request = request ?: return null
        if (invalidated || !compositionApplied || evidence != null || opened) return null
        val previous = draw
        if (previous != null && !previous.snapshot.sameAs(snapshot)) { invalidate(); return null }
        return PrivateCaptureWindowDraw(request, snapshot).also { draw = it; ticket = null }
    }
    fun committed(draw: PrivateCaptureWindowDraw, snapshot: PrivateCaptureWindowSnapshot): PrivateCaptureWindowTicket? {
        if (invalidated || this.draw !== draw || draw.request !== request || !compositionApplied || evidence != null) return null
        if (!draw.snapshot.sameAs(snapshot)) { invalidate(); return null }
        return PrivateCaptureWindowTicket(draw).also { ticket = it }
    }
    fun register(ticket: PrivateCaptureWindowTicket, evidence: Any): Boolean {
        if (invalidated || this.ticket !== ticket || this.evidence != null || ticket.draw !== draw) return false
        this.evidence = evidence
        return true
    }
    fun isCurrent(evidence: Any, target: Any, owner: Any, snapshot: PrivateCaptureWindowSnapshot): Boolean =
        !invalidated && this.evidence === evidence && this.target === target && this.owner === owner &&
            ticket?.draw?.snapshot?.sameAs(snapshot) == true
    fun consume(evidence: Any, target: Any, owner: Any, snapshot: PrivateCaptureWindowSnapshot): Boolean {
        if (consumed || !isCurrent(evidence, target, owner, snapshot)) return false
        consumed = true
        return true
    }
    fun opened(evidence: Any): Boolean {
        if (!consumed || invalidated || this.evidence !== evidence || opened) return false
        opened = true
        return true
    }
    /** Retiring this owned attachment kills its one-use window evidence, never native recording ownership. */
    fun invalidate() { invalidated = true; opened = false; draw = null; ticket = null; evidence = null }
}

/** Every restoration action runs even when an earlier platform/provider action fails. */
internal fun restorePrivateCaptureProtection(vararg actions: () -> Boolean): Boolean {
    var protected = true
    actions.forEach { action -> if (!try { action() } catch (_: Exception) { false }) protected = false }
    return protected
}

/** The final policy check is immediately adjacent to the sole flag mutation. */
internal fun performPrivateCaptureOpening(
    stillAllowed: () -> Boolean,
    clearSecure: () -> Unit,
    reveal: () -> Boolean,
    verify: () -> Boolean,
    restore: () -> Unit,
): Boolean {
    val success = try {
        if (!stillAllowed()) false else {
            clearSecure()
            reveal() && verify()
        }
    } catch (_: Exception) { false }
    if (!success) try { restore() } catch (_: Exception) { /* Never turn failed restoration into admission. */ }
    return success
}

/** Continuous capture preserves normal input, navigation and app-owned dialog behavior. */
internal enum class PrivateCaptureInteractionPolicy { CONTINUOUS_USER_AUTHORIZED }

/** Pending grant is a pre-open condition. Consuming it cannot invalidate an already opened owner. */
internal fun privateCaptureOwnerReadbackAllowed(expected: PrivateCaptureDescriptor,
    actual: PrivateCaptureDescriptor, grantPending: Boolean, submitted: Boolean, opened: Boolean,
    foregroundAndTargetCurrent: Boolean): Boolean =
    foregroundAndTargetCurrent && expected == actual && expected.mode == PrivateCaptureMode.BASIC &&
        (opened || grantPending && !submitted)

/** Idempotent attachment calls must retain known local failure, never report a no-op as recovery. */
internal fun privateCaptureAttachmentHealthy(owned: Boolean, disposed: Boolean,
    protectionFailed: Boolean, localBarrierHealthy: Boolean): Boolean =
    owned && !disposed && !protectionFailed && localBarrierHealthy

/** Missing/failed readback is unknown, not proof that a setup never reached runtime submission. */
internal fun privateCaptureSetupMayCancel(submitted: Boolean?): Boolean = submitted == false

/** A failed restoration is sticky for this exact guard lifetime, including caught platform throws. */
internal fun privateCaptureProtectionFailure(previousFailure: Boolean, restorationSucceeded: Boolean): Boolean =
    previousFailure || !restorationSucceeded
