package com.easysavevideo.app.discovery

import com.easysavevideo.app.discovery.network.SystemVerificationTransport
import com.easysavevideo.discovery.CandidateValidator
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.verification.VerificationServiceClient

/** One process-wide physical network slot, surviving endpoint edits and Activity/controller recreation. */
object VerificationServiceRegistration {
    private val transport by lazy { SystemVerificationTransport() }
    fun validator(configuration: VerificationConfiguration, timer: DiscoveryTimer,
        isCurrent: (String) -> Boolean): CandidateValidator? = configuration.endpoint?.let {
        VerificationServiceClient(it, configuration.generation, transport,
            dynamicAllowed = configuration.dynamicAllowed, isCurrent = { isCurrent(configuration.generation) }, timer = timer)
    }
}
