package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsError
import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.CommonsMaintenance
import com.easysavevideo.commons.CommonsMaintenanceLease
import com.easysavevideo.discovery.DiscoveryCancellation

/** The repository can only narrow the already-configured compiled metadata provider. */
class RepositoryCommonsMaintenance(private val repository: ProviderRepository) : CommonsMaintenance {
    override fun acquire(cancellation: DiscoveryCancellation): CommonsMaintenanceLease {
        val admitted = repository.snapshot()
        if (!admitted.policy.enabled) throw CommonsFailure(CommonsError.DISABLED)
        fun current(): Boolean {
            val now = repository.snapshot()
            return now.generation == admitted.generation && now.policy == admitted.policy && now.policy.enabled
        }
        // Tokens are bounded, single-use, and bound to the exact package/policy generation.
        // A concurrent busy/cache/cancelled request abandons its token without health evidence.
        val observation = repository.beginObservation()
        val observer = try { repository.observeChanges { if (!current()) cancellation.cancel() } }
        catch (failure: Exception) {
            if (observation != null) repository.abandonObservation(observation)
            throw CommonsFailure(CommonsError.DISABLED)
        }
        return object : CommonsMaintenanceLease {
            override val limits = admitted.policy.limits
            override val cacheNamespace = "${admitted.generation}:${admitted.policy.packageVersion}"
            override fun check() {
                if (!current()) { cancellation.cancel(); throw CommonsFailure(CommonsError.DISABLED) }
                if (cancellation.isCancelled) throw CommonsFailure(CommonsError.CANCELLED)
            }
            override fun succeeded(checkBeforePublish: () -> Unit) {
                checkBeforePublish(); check()
                if (observation != null) {
                    repository.completeObservation(observation, ProviderHealthOutcome.SUCCESS, checkBeforePublish)
                    check()
                }
            }
            override fun failed(code: CommonsError, structureFailure: Boolean, checkBeforePublish: () -> Unit) {
                checkBeforePublish(); check()
                if (observation != null) repository.completeObservation(observation,
                    if (structureFailure && code == CommonsError.INVALID_RESPONSE) ProviderHealthOutcome.STRUCTURE_FAILURE
                    else ProviderHealthOutcome.INCONCLUSIVE, checkBeforePublish)
            }
            override fun close() {
                observer.close()
                if (observation != null) repository.abandonObservation(observation)
            }
            override fun toString() = "CommonsMaintenanceLease([redacted])"
        }
    }
}
