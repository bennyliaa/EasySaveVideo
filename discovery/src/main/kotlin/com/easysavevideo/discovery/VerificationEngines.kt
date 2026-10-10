package com.easysavevideo.discovery

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class EngineCapability { STATIC_PAGE, DYNAMIC_PAGE, LOCAL_MATCHING }
enum class EngineOutcome { PAGE_EVIDENCE, UNKNOWN, UNAVAILABLE, DENIED, RATE_LIMITED, TIMEOUT, CANCELLED, ERROR }
enum class EngineEvidence { NONE, HTML_VIDEO, DYNAMIC_VIDEO }
enum class EngineVerdict { VIDEO, NON_VIDEO, UNKNOWN }
/** Target access still requires an admitted source policy; this does not grant one. */
class EngineNetworkConstraints(allowedOrigins: Set<String> = emptySet(), val requireTls: Boolean = true,
    val maxRedirects: Int = 0, val maxHeaderBytes: Int = 16384, val sourceAdmissionRequired: Boolean = false) {
    val allowedOrigins: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(allowedOrigins))
    val downloadsAllowed: Boolean get() = false
    val cookiesAllowed: Boolean get() = false
    init {require(allowedOrigins.size<=16 && maxRedirects in 0..2 && maxHeaderBytes in 1..16384)}
    override fun toString()="EngineNetworkConstraints([redacted])"
}

/** Page evidence never asserts playback, saving rights or an extractable media resource. */
data class EngineRequest(val requestId: String, val generation: String, val candidate: CandidateUrl,
    val deadlineMonotonicMillis: Long, val maxRequests: Int, val maxBytes: Long,
    val dynamicAllowed: Boolean, val remoteConsent: Boolean,
    val network: EngineNetworkConstraints = EngineNetworkConstraints()) {
    override fun toString() = "EngineRequest([redacted])"
}
data class EngineResult(val requestId: String, val generation: String, val engineId: String,
    val outcome: EngineOutcome, val evidence: EngineEvidence = EngineEvidence.NONE,
    val raw: RawVideoResult? = null, val requestsConsumed: Int = 0, val bytesConsumed: Long = 0,
    val consumptionKnown: Boolean = true) {
    // Neither static absence nor an engine failure proves NON_VIDEO; this stage abstains.
    val verdict: EngineVerdict get() = if (outcome==EngineOutcome.PAGE_EVIDENCE) EngineVerdict.VIDEO else EngineVerdict.UNKNOWN
    override fun toString() = "EngineResult(outcome=$outcome,evidence=$evidence,[redacted])"
}
interface VerificationEngine {
    val id: String
    val capabilities: Set<EngineCapability>
    val sendsDataToAnotherProcess: Boolean
    fun isAvailable(): Boolean
    fun isPhysicallyIdle(): Boolean
    /** Must enforce deadline/cancel/byte budgets and finish cleanup before returning. */
    fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult
}
enum class EngineAccessBoundary { UNDECLARED, REMOTE_SERVICE, LOCAL_TARGET, OWNED_LOOPBACK_FIXTURE }

/** Only the trusted composition root assigns boundaries; engine declarations are not authority. */
class VerificationEngineRegistry {
    private class Registered(val boundary: EngineAccessBoundary, val engine: VerificationEngine) : VerificationEngine by engine
    companion object {
        internal fun boundary(engine: VerificationEngine): EngineAccessBoundary =
            (engine as? Registered)?.boundary ?: EngineAccessBoundary.UNDECLARED
    }
    private val engines = linkedMapOf<String, VerificationEngine>()
    @Synchronized fun register(engine: VerificationEngine, boundary: EngineAccessBoundary = EngineAccessBoundary.UNDECLARED) {
        require(engine.id.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(engine.id !in engines && engines.size < 16)
        require(boundary != EngineAccessBoundary.REMOTE_SERVICE || engine.sendsDataToAnotherProcess)
        require(boundary != EngineAccessBoundary.LOCAL_TARGET || !engine.sendsDataToAnotherProcess)
        require(boundary != EngineAccessBoundary.OWNED_LOOPBACK_FIXTURE || !engine.sendsDataToAnotherProcess)
        engines[engine.id] = Registered(boundary, engine)
    }
    @Synchronized fun all(): List<VerificationEngine> = engines.values.toList()
}
data class EngineSelection(val preferredIds: List<String> = emptyList(), val fallbackAllowed: Boolean = true,
    val requiredCapabilities: Set<EngineCapability> = setOf(EngineCapability.STATIC_PAGE))

/** One physical scheduling owner; registered engine IDs are extensible, never a two-engine enum. */
class VerificationEngineScheduler(private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val active = AtomicBoolean(false)
    @Volatile private var draining: VerificationEngine? = null
    private fun idle(engine: VerificationEngine): Boolean = try { engine.isPhysicallyIdle() } catch (_: Exception) { false }
    fun verify(request: EngineRequest, operation: DiscoveryOperation, engines: List<VerificationEngine>,
        selection: EngineSelection = EngineSelection(), isCurrent: () -> Boolean = { true }): EngineResult {
        require(request.maxRequests in 0..4 && request.maxBytes in 0..2_097_152)
        fun terminal(outcome: EngineOutcome) = EngineResult(request.requestId, request.generation,
            "scheduler", outcome, consumptionKnown = false)
        fun fence(): EngineOutcome? = when {
            operation.cancellation.isCancelled || Thread.currentThread().isInterrupted || !isCurrent() -> EngineOutcome.CANCELLED
            monotonicMillis() >= request.deadlineMonotonicMillis -> EngineOutcome.TIMEOUT
            else -> null
        }
        fence()?.let { return terminal(it) }
        if (!active.compareAndSet(false, true)) return terminal(EngineOutcome.UNAVAILABLE)
        var executing: VerificationEngine? = null
        try {
            draining?.let { prior ->
                if (!idle(prior)) return terminal(EngineOutcome.UNAVAILABLE)
                draining = null
            }
            var requestsLeft = request.maxRequests
            var bytesLeft = request.maxBytes
            var last: EngineResult? = null
            val candidates = engines.filter {
                it.capabilities.containsAll(selection.requiredCapabilities) &&
                    (!it.sendsDataToAnotherProcess || request.remoteConsent)
            }.sortedBy { selection.preferredIds.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            for (engine in candidates) {
                fence()?.let { return terminal(it) }
                if (requestsLeft == 0 || bytesLeft == 0L) break
                when (VerificationEngineRegistry.boundary(engine)) {
                    EngineAccessBoundary.UNDECLARED -> return terminal(EngineOutcome.DENIED)
                    EngineAccessBoundary.LOCAL_TARGET -> {
                        val origin = VerificationOriginPolicy.origin(request.candidate.pageUrl)
                        if (origin == null || origin !in request.network.allowedOrigins || !request.network.requireTls ||
                            request.network.maxRedirects != 0) return terminal(EngineOutcome.DENIED)
                    }
                    EngineAccessBoundary.OWNED_LOOPBACK_FIXTURE -> {
                        val uri = try { java.net.URI(request.candidate.pageUrl) } catch (_: Exception) { return terminal(EngineOutcome.DENIED) }
                        val origin = "http://127.0.0.1:${uri.port}"
                        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1..65535 ||
                            uri.rawUserInfo != null || uri.rawFragment != null || origin !in request.network.allowedOrigins ||
                            request.network.maxRedirects != 0) return terminal(EngineOutcome.DENIED)
                    }
                    EngineAccessBoundary.REMOTE_SERVICE -> if (!request.remoteConsent) return terminal(EngineOutcome.DENIED)
                }
                if (!engine.isAvailable()) continue
                executing = engine
                val result = try { engine.verify(request.copy(maxRequests=requestsLeft,maxBytes=bytesLeft), operation) }
                catch (failure: DiscoveryFailure) { throw failure }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return terminal(EngineOutcome.CANCELLED) }
                catch (_: Exception) {
                    if (!idle(engine)) draining = engine
                    return terminal(EngineOutcome.ERROR)
                }
                if (!idle(engine)) draining = engine
                fence()?.let { return terminal(it) }
                if (!result.consumptionKnown || result.requestsConsumed !in 0..requestsLeft ||
                    result.bytesConsumed !in 0..bytesLeft || result.requestId != request.requestId ||
                    result.generation != request.generation || result.engineId != engine.id) return terminal(EngineOutcome.ERROR)
                if (result.outcome == EngineOutcome.PAGE_EVIDENCE) {
                    if (result.evidence == EngineEvidence.NONE || result.raw?.videoConfirmed != true ||
                        result.raw.pageUrl != request.candidate.pageUrl) return terminal(EngineOutcome.ERROR)
                } else if (result.evidence != EngineEvidence.NONE || result.raw != null) return terminal(EngineOutcome.ERROR)
                requestsLeft -= result.requestsConsumed; bytesLeft -= result.bytesConsumed
                last = result.copy(requestsConsumed=request.maxRequests-requestsLeft,bytesConsumed=request.maxBytes-bytesLeft)
                if (draining != null) return last
                if (!selection.fallbackAllowed || result.outcome in setOf(EngineOutcome.PAGE_EVIDENCE,
                    EngineOutcome.DENIED, EngineOutcome.RATE_LIMITED, EngineOutcome.CANCELLED)) return last
            }
            return last ?: terminal(EngineOutcome.UNAVAILABLE)
        } finally {
            executing?.let { if (!idle(it)) draining = it }
            active.set(false)
        }
    }

    /** Discovery depends only on this contract; the application composition root registers engines. */
    fun validator(engines: List<VerificationEngine>, generation: String, remoteConsent: Boolean,
        dynamicAllowed: Boolean, selection: EngineSelection = EngineSelection(),
        isCurrent: () -> Boolean = { true }, epochMillis: () -> Long = System::currentTimeMillis,
        admittedNetwork: (CandidateUrl) -> EngineNetworkConstraints = { EngineNetworkConstraints() }): CandidateValidator =
        object : CandidateValidator {
        override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult? =
            validateEvidence(candidate, operation).raw
        override fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation): CandidateValidation {
            operation.check(epochMillis())
            val availableMillis = (operation.context.deadlineEpochMillis - epochMillis() - 1000).coerceAtLeast(0)
            return if (availableMillis == 0L) CandidateValidation(null, VerificationSummary("scheduler", EngineOutcome.TIMEOUT, EngineEvidence.NONE)) else {
                val request = EngineRequest(UUID.randomUUID().toString().replace("-",""), generation, candidate,
                    monotonicMillis() + minOf(3000L,availableMillis), 3, 2_097_152, dynamicAllowed, remoteConsent,
                    admittedNetwork(candidate))
                val result = verify(request,operation,engines,selection,isCurrent)
                operation.check(epochMillis())
                if (!isCurrent()) throw DiscoveryFailure(DiscoveryError.CANCELLED)
                CandidateValidation(result.raw.takeIf { result.outcome == EngineOutcome.PAGE_EVIDENCE },
                    VerificationSummary(result.engineId, result.outcome, result.evidence))
            }
        }
        }
}
