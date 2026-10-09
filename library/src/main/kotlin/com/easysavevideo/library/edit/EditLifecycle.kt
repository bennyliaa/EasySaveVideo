package com.easysavevideo.library.edit

/** An acknowledgement of actual close, never a request to cancel or a Java/Kotlin timeout result. */
enum class EditRelease { RELEASED, UNCONFIRMED }
enum class EditCancellation { ACCEPTED, TOO_LATE, ALREADY_TERMINAL }
enum class EditPhase {
    PREPARED, EXECUTING, EXECUTED, VERIFYING, READY_TO_PUBLISH, PUBLISHING,
    CANCELLING, RELEASE_PENDING, CLEANUP_PENDING, RECOVERY_REQUIRED, SUCCEEDED, FAILED, CANCELLED;
    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}

/**
 * Application-wide one-native-job budget. A timeout never frees capacity. A late confirmed release
 * may free the original owner, but never clears the restart fence established by uncertainty.
 */
class EditNativeResourceGate {
    private var owner: Any? = null
    @Volatile var requiresRestart: Boolean = false
        private set
    val isOccupied: Boolean @Synchronized get() = owner != null
    @Synchronized fun claim(candidate: Any): Boolean {
        if (owner != null || requiresRestart) return false
        owner = candidate
        return true
    }
    @Synchronized fun released(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
    @Synchronized fun unconfirmed(candidate: Any): Boolean {
        if (owner !== candidate) return false
        requiresRestart = true
        return true
    }
}

data class EditLifecycleSnapshot(
    val phase: EditPhase,
    val cancelRequested: Boolean,
    val error: EditError?,
    val output: EditOutputRef?,
    val executionReleased: Boolean,
    val verifierReleased: Boolean,
    val ownerRetained: Boolean,
    val requiresRestart: Boolean,
) {
    val cleanupAllowed: Boolean get() = phase == EditPhase.CLEANUP_PENDING && !ownerRetained
    val publicationAllowed: Boolean get() = phase == EditPhase.READY_TO_PUBLISH &&
        executionReleased && verifierReleased && !cancelRequested && error == null && !ownerRetained && !requiresRestart
    override fun toString() = "EditLifecycleSnapshot(phase=$phase, error=$error, ownerRetained=$ownerRetained)"
}

/**
 * Pure per-operation fence. The application owns durable intent/reconciliation around these
 * transitions and must still revalidate input revisions/leases at the storage publication gate.
 * All methods are bounded and synchronized. Native calls run OUTSIDE this monitor and report their
 * actual release afterwards. An opaque owner token must be unique to each execution/verification.
 * Cancelling is always free and never queries entitlements. beginPublication is only this pure
 * lifecycle's local transition. The adapter's shared final storage-admission token decides the
 * UI cancellation winner later, after long IO; definite rejection is reported back explicitly.
 */
class EditLifecycle(private val plan: EditPlan, private val gate: EditNativeResourceGate) {
    private var phase = EditPhase.PREPARED
    private var cancelled = false
    private var error: EditError? = null
    private var output: EditOutputRef? = null
    private var owner: Any? = null
    private var stage: Stage? = null
    private var executionOwner: Any? = null
    private var executionReleased = false
    private var verifierReleased = false
    private var proof: EditVerificationEvidence? = null
    private enum class Stage { EXECUTION, VERIFICATION }

    @Synchronized fun snapshot() = EditLifecycleSnapshot(phase, cancelled, error, output,
        executionReleased, verifierReleased, owner != null, gate.requiresRestart)

    @Synchronized fun beginExecution(candidate: Any, destination: EditOutputRef): Boolean {
        requirePhase(EditPhase.PREPARED)
        if (destination.operationId != plan.request.operationId) throw EditFailure(EditError.INVALID_REQUEST)
        output = destination
        if (!gate.claim(candidate)) {
            error = EditError.CAPACITY
            phase = EditPhase.CLEANUP_PENDING
            return false
        }
        owner = candidate; executionOwner = candidate; stage = Stage.EXECUTION; phase = EditPhase.EXECUTING
        return true
    }

    /** Stale or duplicate callbacks are ignored; only the matching owner may release its lease. */
    @Synchronized fun executionFinished(candidate: Any, succeeded: Boolean, release: EditRelease): Boolean {
        if (owner !== candidate || stage != Stage.EXECUTION) return false
        if (!succeeded && error == null && !cancelled) error = EditError.EXECUTION_FAILED
        if (!acceptRelease(candidate, release)) return true
        executionReleased = true
        phase = if (cancelled || error != null) EditPhase.CLEANUP_PENDING else EditPhase.EXECUTED
        return true
    }

    @Synchronized fun beginVerification(candidate: Any): Boolean {
        requirePhase(EditPhase.EXECUTED)
        if (candidate === executionOwner || cancelled || error != null || !executionReleased || owner != null) throw EditFailure(EditError.INVALID_STATE)
        if (!gate.claim(candidate)) {
            error = EditError.CAPACITY; phase = EditPhase.CLEANUP_PENDING
            return false
        }
        owner = candidate; stage = Stage.VERIFICATION; phase = EditPhase.VERIFYING
        return true
    }

    @Synchronized fun verificationFinished(
        candidate: Any,
        evidence: EditVerificationEvidence?,
        release: EditRelease,
    ): Boolean {
        if (owner !== candidate || stage != Stage.VERIFICATION) return false
        val valid = evidence?.validFor(checkNotNull(output), plan.expectation) == true
        if (!valid && error == null && !cancelled) error = EditError.VERIFICATION_FAILED
        if (!acceptRelease(candidate, release)) return true
        verifierReleased = true
        if (valid && !cancelled && error == null && !gate.requiresRestart) {
            proof = evidence; phase = EditPhase.READY_TO_PUBLISH
        } else phase = EditPhase.CLEANUP_PENDING
        return true
    }

    @Synchronized fun requestCancel(): EditCancellation {
        if (phase == EditPhase.PUBLISHING || phase == EditPhase.SUCCEEDED || phase == EditPhase.RECOVERY_REQUIRED) {
            return EditCancellation.TOO_LATE
        }
        if (phase.terminal) return EditCancellation.ALREADY_TERMINAL
        cancelled = true
        proof = null
        if (error == null) error = EditError.CANCELLED
        phase = when {
            owner != null && phase == EditPhase.RELEASE_PENDING -> EditPhase.RELEASE_PENDING
            owner != null -> EditPhase.CANCELLING
            output != null -> EditPhase.CLEANUP_PENDING
            else -> EditPhase.CANCELLED
        }
        return EditCancellation.ACCEPTED
    }

    /** A stalled native operation is fenced immediately and must later report actual release. */
    @Synchronized fun fail(code: EditError) {
        if (phase.terminal || phase == EditPhase.PUBLISHING || phase == EditPhase.RECOVERY_REQUIRED) return
        if (error == null || error == EditError.CANCELLED) error = code
        proof = null
        phase = if (owner != null) {
            if (code == EditError.TIMEOUT || code == EditError.RELEASE_UNCONFIRMED) {
                gate.unconfirmed(checkNotNull(owner)); EditPhase.RELEASE_PENDING
            } else EditPhase.CANCELLING
        } else if (output != null) EditPhase.CLEANUP_PENDING else EditPhase.FAILED
    }

    /**
     * For a delayed close after timeout/cancellation. Does not accept success or revive a discarded
     * proof. Cleanup remains blocked until this exact owner's actual native/input close completes.
     */
    @Synchronized fun confirmLateRelease(candidate: Any): Boolean {
        if (owner !== candidate || phase !in setOf(EditPhase.RELEASE_PENDING, EditPhase.CANCELLING)) return false
        val releasedStage = stage
        if (!acceptRelease(candidate, EditRelease.RELEASED)) return false
        if (releasedStage == Stage.EXECUTION) executionReleased = true else verifierReleased = true
        if (error == null) error = EditError.RELEASE_UNCONFIRMED
        phase = EditPhase.CLEANUP_PENDING
        return true
    }

    /** Caller must durably record publication intent before invoking the storage publication gate. */
    @Synchronized fun beginPublication(): EditVerificationEvidence {
        if (!snapshot().publicationAllowed) throw EditFailure(EditError.INVALID_STATE)
        val verified = proof ?: throw EditFailure(EditError.VERIFICATION_FAILED)
        if (!verified.validFor(checkNotNull(output), plan.expectation)) throw EditFailure(EditError.VERIFICATION_FAILED)
        phase = EditPhase.PUBLISHING
        return verified
    }

    /** Storage proves rejection before final visibility admission, after all native readers closed. */
    @Synchronized fun publicationAdmissionRejected(wasCancelled: Boolean, code: EditError) {
        requirePhase(EditPhase.PUBLISHING)
        check(owner == null && executionReleased && verifierReleased)
        cancelled = wasCancelled; error = code; proof = null; phase = EditPhase.CLEANUP_PENDING
    }

    /** Call only after verified atomic catalog publication, never after encoder/export completion. */
    @Synchronized fun publicationSucceeded(): Boolean {
        if (phase != EditPhase.PUBLISHING) return false
        phase = EditPhase.SUCCEEDED
        return true
    }

    @Synchronized fun publicationUncertain() {
        requirePhase(EditPhase.PUBLISHING)
        error = EditError.PUBLICATION_UNCERTAIN
        phase = EditPhase.RECOVERY_REQUIRED
    }

    /** Only the operation-owned staging output may be removed; input originals have no delete API. */
    @Synchronized fun cleanupConfirmed(): Boolean {
        if (phase != EditPhase.CLEANUP_PENDING || owner != null) return false
        phase = if (cancelled) EditPhase.CANCELLED else EditPhase.FAILED
        return true
    }

    private fun acceptRelease(candidate: Any, release: EditRelease): Boolean {
        if (release == EditRelease.UNCONFIRMED) {
            gate.unconfirmed(candidate)
            if (error == null || error == EditError.CANCELLED) error = EditError.RELEASE_UNCONFIRMED
            phase = EditPhase.RELEASE_PENDING
            proof = null
            return false
        }
        if (!gate.released(candidate)) {
            if (error == null) error = EditError.RELEASE_UNCONFIRMED
            phase = EditPhase.RELEASE_PENDING
            return false
        }
        owner = null; stage = null
        return true
    }

    private fun requirePhase(expected: EditPhase) {
        if (phase != expected) throw EditFailure(EditError.INVALID_STATE)
    }
}
