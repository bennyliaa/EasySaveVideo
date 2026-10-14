package com.easysavevideo.save

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes entry to the existing native verifier without submitting another native Future.
 * Waiting consumes the original operation deadline and remains cancellable/interruptible.
 * The application permit is never evidence of native release: the delegate's draining gate remains
 * authoritative until actual native release. Only an unreadable recovery gate is process-latched.
 */
class SerialArtifactVerifier(
    private val delegate: ArtifactVerifier,
    private val clock: EpochClock,
) : ArtifactVerifier {
    private val lane = Semaphore(1, true)
    private val recoveryProbeFailed = AtomicBoolean(false)

    override fun requiresProcessRecovery(): Boolean {
        val unreleased = try { delegate.requiresProcessRecovery() }
        catch (_: Exception) { recoveryProbeFailed.set(true); true }
        return unreleased || recoveryProbeFailed.get()
    }

    override fun verify(
        artifact: StagingArtifact,
        transfer: TransferResult,
        context: OperationContext,
    ): VerificationEvidence {
        while (true) {
            checkActive(context)
            val remaining = (context.deadlineEpochMillis - clock.nowMillis()).coerceAtLeast(1)
            val acquired = try {
                lane.tryAcquire(minOf(WAIT_SLICE_MILLIS, remaining), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SaveFailure(SaveError.CANCELLED)
            }
            if (acquired) break
        }
        var releaseUnconfirmed = false
        val evidence = try {
            // Nothing while waiting renews the original context, token, deadline or native gate.
            checkActive(context)
            val result = delegate.verify(artifact, transfer, context)
            checkActive(context)
            result
        } finally {
            try {
                // Abandonment can be reported only by the delegate's own finally block.
                releaseUnconfirmed = requiresProcessRecovery()
            } finally {
                lane.release()
            }
        }
        // Recovery may have changed or become unreadable while relinquishing the permit.
        checkActive(context)
        // Actual later release permits a new call, but cannot rehabilitate this call's late evidence.
        if (releaseUnconfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        return evidence
    }

    private fun checkActive(context: OperationContext) {
        // Observe native draining even when the original operation has already stopped.
        val unreleased = requiresProcessRecovery()
        val error = when (context.stopReason(clock.nowMillis())) {
            OperationStopReason.CANCELLED -> SaveError.CANCELLED
            OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
            OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
            null -> null
        }
        if (error != null) throw SaveFailure(error)
        if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        if (unreleased) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }

    private companion object { const val WAIT_SLICE_MILLIS = 50L }
}
