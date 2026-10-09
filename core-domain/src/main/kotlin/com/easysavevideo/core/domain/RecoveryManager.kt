package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/**
 * Small cooperative recovery pass. Adapters run blocking/long scans outside this pass and respect
 * the absolute deadline. This class does not pretend to forcibly time out uninterruptible IO.
 */
class RecoveryManager(private val clock: EpochClock, private val maxAttemptsPerKind: Int = 1) {
    init { require(maxAttemptsPerKind in 1..3) }
    private val attempts = mutableMapOf<RecoveryObjectKind, Int>()
    private val completed = mutableMapOf<RecoveryObjectKind, RecoveryOutcome>()

    fun check(probes: List<RecoveryProbe>, operation: OperationContext): RecoveryReport {
        val outcomes = mutableListOf<RecoveryOutcome>()
        probes.distinctBy { it.kind }.forEach { probe ->
            val spent = attempts[probe.kind] ?: 0
            when {
                completed[probe.kind] != null -> outcomes += completed.getValue(probe.kind)
                operation.stopReason(clock.nowMillis()) != null ->
                    outcomes += RecoveryOutcome(probe.kind, RecoveryDisposition.DEFERRED)
                spent >= maxAttemptsPerKind ->
                    outcomes += RecoveryOutcome(probe.kind, RecoveryDisposition.ISOLATED, recoveryError())
                else -> {
                    attempts[probe.kind] = spent + 1
                    val outcome = try { probe.check(operation) } catch (_: Exception) {
                        RecoveryOutcome(probe.kind, RecoveryDisposition.ISOLATED, recoveryError())
                    }
                    val checked = if (outcome.kind == probe.kind) outcome
                        else RecoveryOutcome(probe.kind, RecoveryDisposition.ISOLATED, recoveryError())
                    outcomes += checked
                    if (checked.disposition == RecoveryDisposition.READY || checked.disposition == RecoveryDisposition.WAITING_USER) {
                        completed[probe.kind] = checked
                    }
                }
            }
        }
        return RecoveryReport(outcomes, clock.nowMillis() >= operation.deadlineEpochMillis)
    }

    private fun recoveryError() = ErrorEnvelope(ErrorCode.RECOVERY_FAILED, ErrorModule.RECOVERY, false, UserAction.RETRY)
}

/** Diagnostics are best effort and cannot take down the user's requested operation. */
fun ErrorSink.reportSafely(error: ErrorEnvelope) { try { report(error) } catch (_: Exception) { /* No raw fallback log. */ } }
