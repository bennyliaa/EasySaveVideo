package com.easysavevideo.discovery

import com.easysavevideo.core.model.EpochClock
import java.io.FilterInputStream
import java.io.InputStream

/** Only finite candidates from an admitted search source enter this pool; there is no recursive crawler. */
class CandidateUrlPool(private val capacity: Int = 500) {
    private val urls = linkedMapOf<String, CandidateUrl>()
    init { require(capacity in 1..500) }
    val size: Int get() = urls.size
    fun add(candidate: CandidateUrl): Boolean {
        val canonical = ResultNormalizer.canonicalUrl(candidate.pageUrl) ?: return false
        if (canonical in urls) return false
        if (urls.size >= capacity) throw DiscoveryFailure(DiscoveryError.CAPACITY_REACHED)
        urls[canonical] = candidate.copy(pageUrl = canonical); return true
    }
    fun take(): CandidateUrl? = urls.entries.firstOrNull()?.let { urls.remove(it.key) }
    fun clear() = urls.clear()
}

/** A real adapter must supply evidence. An absent validator must never manufacture video metadata. */
fun interface CandidateValidator {
    fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult?
    fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation): CandidateValidation =
        CandidateValidation(validate(candidate, operation))
}
/** Bounded technical evidence only: no resource, playback or saving authority. */
data class VerificationSummary(val engineId: String, val outcome: EngineOutcome, val evidence: EngineEvidence) {
    init { require(engineId.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
}
data class CandidateValidation(val raw: RawVideoResult?, val summary: VerificationSummary? = null)
data class CandidateVerificationReport(val candidateId: String, val summary: VerificationSummary)
data class ValidatedProviderPage(
    val results: List<VideoSearchResult>, val nextCursor: String?, val cursorExpiresAtEpochMillis: Long?, val exhausted: Boolean,
    val pendingCandidates: List<PendingVideoCandidate> = emptyList(),
    val verificationReports: List<CandidateVerificationReport> = emptyList(),
) { override fun toString(): String = "ValidatedProviderPage(count=${results.size})" }

class ValidationPipeline(
    private val validator: CandidateValidator? = null,
    private val limits: DiscoveryLimits = DiscoveryLimits(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    /** Opt-in UI channel; existing callers still fail closed without a validator. */
    private val retainUnvalidatedCandidates: Boolean = false,
    /** Optional remote validation is best-effort; its unavailable/rejected candidates remain explicit. */
    private val retainFailedValidationCandidates: Boolean = false,
    private val maxValidationsPerPage: Int = 100,
    private val sessionAdmission: SessionCandidateAdmission? = null,
) {
    init { require(maxValidationsPerPage in 0..100) }
    private data class TrustedSource(val provider: VideoSearchProvider, val request: SearchRequest, val authority: ProviderAuthorityView?)
    fun validate(providerId: String, page: ProviderPage, filters: SearchFilters, operation: DiscoveryOperation): ValidatedProviderPage =
        validatePage(providerId, page, filters, operation, null)

    internal fun reserveTrustedSource(provider: VideoSearchProvider, request: SearchRequest, authority: ProviderAuthorityView?,
        operation: DiscoveryOperation) {
        sessionAdmission?.reserveSource(provider, request, authority, operation)?.let { throw DiscoveryFailure(it) }
    }

    internal fun endTrustedSource(operation: DiscoveryOperation) { sessionAdmission?.endSourceOperation(operation) }

    /** Only the runtime can bind a returned page to its original registered source and operation. */
    internal fun validateTrusted(provider: VideoSearchProvider, request: SearchRequest, authority: ProviderAuthorityView?,
        page: ProviderPage, operation: DiscoveryOperation): ValidatedProviderPage =
        validatePage(request.expectedDescriptor!!.id, page, request.profile.filters, operation, TrustedSource(provider, request, authority))

    private fun validatePage(providerId: String, page: ProviderPage, filters: SearchFilters, operation: DiscoveryOperation,
        trusted: TrustedSource?): ValidatedProviderPage {
        operation.check(clock.nowMillis())
        if (page.responseBytes < 0 || page.responseBytes > limits.maxResponseBytes) throw DiscoveryFailure(DiscoveryError.RESPONSE_TOO_LARGE)
        if (page.results.size.toLong() + page.candidates.size > limits.maxCandidatesPerResponse) throw DiscoveryFailure(DiscoveryError.TOO_MANY_CANDIDATES)
        if (page.nextCursor?.length?.let { it > 8192 } == true || page.cursorExpiresAtEpochMillis?.let { it < 0 } == true) throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        if (page.candidates.isNotEmpty() && validator == null && !retainUnvalidatedCandidates) throw DiscoveryFailure(DiscoveryError.VALIDATOR_UNAVAILABLE)
        val pool = CandidateUrlPool(limits.poolCapacity)
        page.candidates.forEach { operation.check(clock.nowMillis()); pool.add(it) }
        val results = mutableListOf<VideoSearchResult>()
        val pending = mutableListOf<PendingVideoCandidate>()
        for (raw in page.results) {
            operation.check(clock.nowMillis())
            ResultNormalizer.normalize(raw, providerId, filters)?.let(results::add)
        }
        val reports = mutableListOf<CandidateVerificationReport>()
        var attempts = 0
        try {
            while (true) {
                operation.check(clock.nowMillis())
                val candidate = pool.take() ?: break
                val managed = trusted != null && trusted.provider.searchBudget != null && sessionAdmission?.tracks(trusted.request.stamp) == true
                val accepted = if (managed) {
                    // Reject malformed source metadata before minting any authority.
                    if (PendingCandidatePolicy.normalize(candidate, providerId, filters.copy(includeUnknownDuration = true)) == null) continue
                    sessionAdmission!!.accept(trusted!!.provider, trusted.request, trusted.authority, candidate, operation,
                        allowValidation = validator != null && attempts < maxValidationsPerPage)
                } else null
                val duplicate = managed && accepted?.first == false
                if (validator == null || managed && accepted == null || duplicate && accepted?.validation == null ||
                    !duplicate && attempts >= maxValidationsPerPage) {
                    PendingCandidatePolicy.normalize(candidate, providerId, filters)?.let(pending::add)
                    continue
                }
                if (!duplicate) attempts++
                val validation = try { accepted?.validation ?: validator.validateEvidence(candidate, operation) }
                catch (failure: Exception) {
                    if (failure is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw DiscoveryFailure(DiscoveryError.CANCELLED)
                    }
                    operation.check(clock.nowMillis())
                    if (!retainFailedValidationCandidates || failure is DiscoveryFailure &&
                        failure.code in setOf(DiscoveryError.CANCELLED, DiscoveryError.DEADLINE_EXCEEDED)) throw failure
                    CandidateValidation(null)
                }
                operation.check(clock.nowMillis())
                if (managed && !duplicate) sessionAdmission?.complete(candidate, operation, validation)
                val raw = validation.raw
                validation.summary?.let { summary ->
                    val id = digest(framed("candidate-url-v1", candidate.pageUrl))
                    reports += CandidateVerificationReport(id, summary)
                }
                val result = raw?.let { ResultNormalizer.normalize(
                    if (retainFailedValidationCandidates && it.sourceNamespace == null) it.copy(sourceNamespace = providerId) else it,
                    providerId, filters) }
                if (result != null) results.add(result)
                else if (retainFailedValidationCandidates && (raw == null || !raw.videoConfirmed))
                    PendingCandidatePolicy.normalize(candidate, providerId, filters)?.let(pending::add)
            }
        } finally { pool.clear() }
        operation.check(clock.nowMillis())
        val provenance = trusted?.let { sessionAdmission?.provenance(it.request.stamp) }.orEmpty()
        return ValidatedProviderPage(results.map { it.copy(provenance = it.provenance + provenance[it.canonicalPageUrl].orEmpty()) },
            page.nextCursor, page.cursorExpiresAtEpochMillis, page.exhausted,
            pending.map { it.copy(provenance = it.provenance + provenance[it.canonicalPageUrl].orEmpty()) }, reports.toList())
    }
}

/** Adapters use this before buffering or parsing; responseBytes alone is not a trusted network limit. */
class BoundedProviderInputStream(input: InputStream, private val maximumBytes: Long = 2L * 1024 * 1024) : FilterInputStream(input) {
    private var count = 0L
    init { require(maximumBytes in 1..2L * 1024 * 1024) }
    private fun account(read: Int): Int {
        if (read > 0) { count += read; if (count > maximumBytes) throw DiscoveryFailure(DiscoveryError.RESPONSE_TOO_LARGE) }
        return read
    }
    override fun read(): Int { val value = `in`.read(); if (value >= 0) account(1); return value }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Read at most the remaining budget + one byte, enough to detect overflow without huge buffering.
        val allowed = minOf(length.toLong(), maximumBytes - count + 1).coerceAtLeast(0).toInt()
        if (length > 0 && allowed == 0) throw DiscoveryFailure(DiscoveryError.RESPONSE_TOO_LARGE)
        return account(`in`.read(buffer, offset, allowed))
    }
    override fun skip(amount: Long): Long {
        val buffer = ByteArray(8192); var remaining = amount.coerceAtLeast(0); var total = 0L
        while (remaining > 0) { val n = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); if (n < 0) break; remaining -= n; total += n }
        return total
    }
    override fun markSupported(): Boolean = false
    override fun mark(readlimit: Int) { /* Rewinding would bypass the cumulative byte budget. */ }
    override fun reset(): Nothing = throw java.io.IOException("RESET_UNSUPPORTED")
}
