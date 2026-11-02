package com.easysavevideo.verification

import com.easysavevideo.discovery.*

/** Adapter for A; transport/client keep their existing TLS, consent, cleanup and identity fences. */
class RemoteVerificationEngine(private val client: (Long, Boolean) -> VerificationServiceClient,
    private val isCurrent: () -> Boolean, private val physicalIdle: () -> Boolean = { false },
    private val monotonicMillis: () -> Long = { System.nanoTime()/1_000_000 }) : VerificationEngine {
    override val id = "optional-scrapling-service"
    override val capabilities = setOf(EngineCapability.STATIC_PAGE,EngineCapability.DYNAMIC_PAGE)
    override val sendsDataToAnotherProcess = true
    override fun isAvailable() = isCurrent()
    override fun isPhysicallyIdle() = physicalIdle()
    override fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult {
        fun negative(outcome: EngineOutcome) = EngineResult(request.requestId,request.generation,id,outcome)
        if (!request.remoteConsent || !isCurrent()) return negative(EngineOutcome.UNAVAILABLE)
        val remaining = request.deadlineMonotonicMillis-monotonicMillis()
        // Conservative reservation: one bounded HTTP request and the full response cap.
        if (remaining <= 0) return negative(EngineOutcome.TIMEOUT)
        if (request.maxRequests < 1 || request.maxBytes < 32768) return negative(EngineOutcome.UNAVAILABLE)
        val response = client(minOf(1500L,remaining),request.dynamicAllowed).verify(request.candidate,operation,request.requestId)
        val outcome = when(response.outcome) {
            VerificationOutcome.PAGE_EVIDENCE -> EngineOutcome.PAGE_EVIDENCE
            VerificationOutcome.REJECTED -> EngineOutcome.DENIED
            VerificationOutcome.TIMEOUT -> EngineOutcome.TIMEOUT
            VerificationOutcome.CANCELLED -> EngineOutcome.CANCELLED
            VerificationOutcome.INSUFFICIENT -> EngineOutcome.UNKNOWN
            VerificationOutcome.BUSY,VerificationOutcome.UNAVAILABLE,VerificationOutcome.UNSUPPORTED -> EngineOutcome.UNAVAILABLE
        }
        val evidence = when(response.evidence) {
            VerificationEvidence.HTML_VIDEO -> EngineEvidence.HTML_VIDEO
            VerificationEvidence.DYNAMIC_VIDEO_XHR, VerificationEvidence.DYNAMIC_VIDEO_DOM -> EngineEvidence.DYNAMIC_VIDEO
            VerificationEvidence.NONE -> EngineEvidence.NONE
        }
        return EngineResult(request.requestId,request.generation,id,outcome,evidence,response.toRaw(),1,32768)
    }
}
