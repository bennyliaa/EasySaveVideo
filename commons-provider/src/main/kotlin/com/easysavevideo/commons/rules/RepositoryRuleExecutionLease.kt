package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.commons.maintenance.ProviderHealthObservation
import com.easysavevideo.commons.maintenance.ProviderHealthOutcome
import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.commons.maintenance.SignedProviderPackage
import com.easysavevideo.commons.shrinkLimits
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderAuthorityView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resource lease backed exclusively by the existing repository, never an independent trust owner. */
internal class RepositoryRuleExecutionLease(
    private val repository: ProviderRepository,
    value: SignedProviderPackage,
    val authority: ProviderAuthorityView,
    private val operation: DiscoveryOperation,
    private val clock: EpochClock,
    observation: ProviderHealthObservation?,
) : AutoCloseable {
    val plan: CandidateRulePlan = value.candidatePlan ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
    val limits: CommonsLimits = shrinkLimits(repository.target.localLimits, value.policy.limits)
    private val closed = AtomicBoolean(false)
    private val observationRef = AtomicReference(observation)
    private val observer = try {
        repository.observeChanges {
            if (!authority.isCurrent(clock.nowMillis())) operation.cancellation.cancel()
        }
    } catch (_: Exception) {
        observation?.let(repository::abandonObservation)
        throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
    }

    fun check() {
        if (closed.get()) throw DiscoveryFailure(DiscoveryError.CANCELLED)
        val now = clock.nowMillis()
        operation.check(now)
        if (!authority.isCurrent(now)) {
            // Never call cancellation/listeners while holding the authority publication gate.
            operation.cancellation.cancel()
            throw DiscoveryFailure(DiscoveryError.CANCELLED)
        }
    }

    fun complete(outcome: ProviderHealthOutcome) {
        check()
        observationRef.getAndSet(null)?.let { repository.completeObservation(it, outcome, ::check) }
        check()
    }

    fun cursorExpiry(now: Long): Long = minOf(repository.ruleAuthorityExpiry(authority),
        if (now > Long.MAX_VALUE - MAX_CURSOR_TTL_MILLIS) Long.MAX_VALUE else now + MAX_CURSOR_TTL_MILLIS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observer.close()
        observationRef.getAndSet(null)?.let(repository::abandonObservation)
        // Normal resource close deliberately does not revoke the immutable pending-candidate view.
    }
    override fun toString(): String = "RepositoryRuleExecutionLease([redacted])"
    private companion object { const val MAX_CURSOR_TTL_MILLIS = 5L * 60 * 1000 }
}
