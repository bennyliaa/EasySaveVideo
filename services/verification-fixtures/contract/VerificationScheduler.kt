package com.easysavevideo.validation.contract

import java.util.concurrent.atomic.AtomicBoolean

/** Host-verifiable scheduling core. Android lifecycle/IO enforcement remains to be integrated. */
class LocalEngineRegistry : VerificationEngineRegistry {
    private val registered = linkedMapOf<EngineId, VerificationEngine>()
    @Synchronized override fun register(engine: VerificationEngine) {
        require(engine.id.value.isNotBlank())
        require(engine.id !in registered) { "Duplicate engine identity" }
        registered[engine.id] = engine
    }
    @Synchronized override fun engines(): List<VerificationEngine> = registered.values.toList()
}

data class EngineSelection(val preferred: List<EngineId> = emptyList(), val allowFallback: Boolean = true)

class VerificationScheduler(
    private val registry: VerificationEngineRegistry,
    private val elapsedRealtimeMs: () -> Long
) {
    private val active = AtomicBoolean(false)

    suspend fun verify(
        request: VerificationRequest,
        cancellation: VerificationCancellation,
        selection: EngineSelection = EngineSelection()
    ): VerificationResult {
        require(request.requestId.isNotBlank())
        require(request.budget.maxRequests >= 0 && request.budget.maxResponseBytes >= 0)
        require(request.budget.maxRedirects >= 0)
        if (!active.compareAndSet(false, true)) {
            return terminal(request, Outcome.UNAVAILABLE, "Scheduler busy")
        }
        try {
            if (cancellation.isCancelled()) return terminal(request, Outcome.CANCELLED, "Cancelled")
            if (elapsedRealtimeMs() >= request.budget.deadlineElapsedRealtimeMs) {
                return terminal(request, Outcome.TIMEOUT, "Shared deadline expired")
            }
            var requestsLeft = request.budget.maxRequests
            var bytesLeft = request.budget.maxResponseBytes
            val candidates = registry.engines().filter {
                it.capabilities.containsAll(request.requiredCapabilities) &&
                    (!it.sendsDataToAnotherProcess || request.network.remoteConsentForThisProcess)
            }.sortedBy { engine ->
                selection.preferred.indexOf(engine.id).let { if (it < 0) Int.MAX_VALUE else it }
            }
            var last: VerificationResult? = null
            for (engine in candidates) {
                if (cancellation.isCancelled()) return terminal(request, Outcome.CANCELLED, "Cancelled")
                if (elapsedRealtimeMs() >= request.budget.deadlineElapsedRealtimeMs) {
                    return terminal(request, Outcome.TIMEOUT, "Shared deadline expired")
                }
                if (requestsLeft == 0 || bytesLeft == 0L) break
                val available = try { engine.isAvailable() } catch (_: Exception) { false }
                if (!available) continue
                val next = request.copy(budget = request.budget.copy(
                    maxRequests = requestsLeft, maxResponseBytes = bytesLeft
                ))
                val result = try { engine.verify(next, cancellation) } catch (_: Exception) {
                    // Consumption is unknown after an exception: exhaust the shared ledger.
                    if (cancellation.isCancelled()) return terminal(request, Outcome.CANCELLED, "Cancelled")
                    return terminal(request, Outcome.ERROR, "Engine threw; consumption unknown")
                }
                if (!result.resourceUsageKnown) {
                    return terminal(request, Outcome.ERROR, "Unknown consumption; cannot safely fallback")
                }
                if (result.requestsConsumed < 0 || result.bytesConsumed < 0 ||
                    result.requestsConsumed > requestsLeft || result.bytesConsumed > bytesLeft) {
                    return terminal(request, Outcome.ERROR, "Invalid or exceeded consumption")
                }
                requestsLeft -= result.requestsConsumed
                bytesLeft -= result.bytesConsumed
                if (cancellation.isCancelled()) return terminal(request, Outcome.CANCELLED, "Cancelled")
                if (elapsedRealtimeMs() >= request.budget.deadlineElapsedRealtimeMs) {
                    return terminal(request, Outcome.TIMEOUT, "Shared deadline expired")
                }
                if (result.requestId != request.requestId || result.sourceGeneration != request.sourceGeneration ||
                    result.engineId != engine.id) {
                    return terminal(request, Outcome.ERROR, "Stale or mismatched result")
                }
                if (result.outcome != Outcome.SUCCESS &&
                    (result.evidence != EvidenceLevel.UNKNOWN || result.mediaUrls.isNotEmpty())) {
                    return terminal(request, Outcome.ERROR, "Failure contains media confirmation")
                }
                if (result.evidence == EvidenceLevel.VERIFIED_MEDIA && result.mediaUrls.isEmpty()) {
                    return terminal(request, Outcome.ERROR, "Verified evidence lacks media identity")
                }
                last = result.copy(requestsConsumed = request.budget.maxRequests - requestsLeft,
                                   bytesConsumed = request.budget.maxResponseBytes - bytesLeft)
                if (result.outcome in setOf(Outcome.SUCCESS, Outcome.DENIED, Outcome.RATE_LIMITED, Outcome.CANCELLED) ||
                    !selection.allowFallback) return last
            }
            return last ?: terminal(request, Outcome.UNAVAILABLE, "No eligible available engine")
        } finally {
            active.set(false)
        }
    }

    private fun terminal(request: VerificationRequest, outcome: Outcome, reason: String) = VerificationResult(
        request.requestId, request.sourceGeneration, EngineId("scheduler"), outcome,
        EvidenceLevel.UNKNOWN, resourceUsageKnown = false, reason = reason
    )
}
