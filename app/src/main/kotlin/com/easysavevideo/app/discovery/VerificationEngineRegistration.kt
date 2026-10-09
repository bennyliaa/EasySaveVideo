package com.easysavevideo.app.discovery

import com.easysavevideo.app.discovery.network.SystemVerificationTransport
import com.easysavevideo.discovery.CandidateValidator
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.discovery.VerificationEngineRegistry
import com.easysavevideo.discovery.VerificationEngineScheduler
import com.easysavevideo.discovery.VerificationEngine
import com.easysavevideo.verification.RemoteVerificationEngine
import com.easysavevideo.verification.VerificationServiceClient

/** One process-wide physical network slot, surviving endpoint edits and Activity/controller recreation. */
object VerificationEngineRegistration {
    private val transport by lazy { SystemVerificationTransport() }
    private val scheduler = VerificationEngineScheduler()
    fun validator(configuration: VerificationConfiguration, timer: DiscoveryTimer,
        localEngines: List<VerificationEngine> = emptyList(),
        isCurrent: (String) -> Boolean): CandidateValidator {
        val registry = VerificationEngineRegistry()
        configuration.endpoint?.let { endpoint -> registry.register(RemoteVerificationEngine({ budget, dynamicAllowed ->
            VerificationServiceClient(endpoint, configuration.generation, transport,
                dynamicAllowed = configuration.dynamicAllowed && dynamicAllowed, isCurrent = { isCurrent(configuration.generation) },
                timer = timer, deadlineMillis = budget)
        }, { isCurrent(configuration.generation) }, { transport.isPhysicallyIdle() })) }
        localEngines.forEach(registry::register)
        return scheduler.validator(registry.all(), configuration.generation, remoteConsent = configuration.endpoint != null,
            dynamicAllowed = configuration.dynamicAllowed, isCurrent = { isCurrent(configuration.generation) })
    }
}
