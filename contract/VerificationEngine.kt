package com.easysavevideo.validation.contract

/** Independently compiled contract; not yet wired into the recovered ESV app. */
data class EngineId(val value: String)
enum class Capability { STATIC_HTML, DYNAMIC_DOM, LOCAL_ADAPTATION }
enum class EvidenceLevel { UNKNOWN, CANDIDATE, VERIFIED_MEDIA }
enum class Outcome { SUCCESS, UNAVAILABLE, DENIED, RATE_LIMITED, TIMEOUT, CANCELLED, ERROR }
data class VerificationBudget(
    val deadlineElapsedRealtimeMs: Long,
    val maxRequests: Int = 3,
    val maxResponseBytes: Long = 2_000_000,
    val maxRedirects: Int = 2
)
data class NetworkConstraints(
    val allowedOrigins: Set<String>,
    val remoteConsentForThisProcess: Boolean = false,
    val requireTls: Boolean = true,
    val allowDownloads: Boolean = false
)
data class VerificationRequest(
    val requestId: String,
    val sourceGeneration: Long,
    val pageUrl: String,
    val requiredCapabilities: Set<Capability>,
    val budget: VerificationBudget,
    val network: NetworkConstraints
)
data class VerificationResult(
    val requestId: String,
    val sourceGeneration: Long,
    val engineId: EngineId,
    val outcome: Outcome,
    val evidence: EvidenceLevel,
    val mediaUrls: List<String> = emptyList(),
    val requestsConsumed: Int = 0,
    val bytesConsumed: Long = 0,
    val resourceUsageKnown: Boolean = true,
    val reason: String? = null
)
interface VerificationCancellation { fun isCancelled(): Boolean }
interface VerificationEngine : AutoCloseable {
    val id: EngineId
    val capabilities: Set<Capability>
    val sendsDataToAnotherProcess: Boolean
    fun isAvailable(): Boolean
    suspend fun verify(request: VerificationRequest, cancellation: VerificationCancellation): VerificationResult
}
interface VerificationEngineRegistry {
    fun register(engine: VerificationEngine)
    fun engines(): List<VerificationEngine>
}
// Scheduler owns one request-wide ledger and one active engine. Fallback receives
// the remaining deadline/request/byte budget. Selection uses registry metadata;
// Discovery must not name individual engines or execute downloaded rules.
