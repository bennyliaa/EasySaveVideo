package com.easysavevideo.discovery

import java.util.IdentityHashMap

/** Finite, nonrefunding limits shared by every source in one explicitly started user search. */
data class SessionCandidateLimits(
    val maxUniquePages: Int = 24,
    val maxRequests: Int = 72,
    val maxBytes: Long = 12L * 1024 * 1024,
    val lifetimeMillis: Long = 60_000,
    val requestsPerPage: Int = 3,
    val bytesPerPage: Long = 512L * 1024,
) {
    init {
        require(maxUniquePages in 1..500 && maxRequests in 1..1500)
        require(maxBytes in 1..64L * 1024 * 1024 && lifetimeMillis in 1..60_000)
        require(requestsPerPage in 1..4 && bytesPerPage in 1..2_097_152)
    }
}

enum class SessionAdmissionStatus { INACTIVE, ACTIVE, EXPIRED, PAGE_LIMIT, REQUEST_LIMIT, BYTE_LIMIT }

internal data class SessionValidationBudget(val requests: Int, val bytes: Long, val remainingMillis: Long)

/** Opaque, in-memory authority. No public constructor, copy, serial form or URL-derived credential. */
class SessionCandidateGrant private constructor(
    private val owner: SessionCandidateAdmission,
    internal val identity: Any,
) {
    internal fun consume(candidate: CandidateUrl, operation: DiscoveryOperation, requests: Int, bytes: Long): SessionValidationBudget? =
        owner.consume(this, candidate, operation, requests, bytes)
    internal fun finish(candidate: CandidateUrl, operation: DiscoveryOperation) = owner.finish(this, candidate, operation)
    internal fun isCurrent(candidate: CandidateUrl, operation: DiscoveryOperation): Boolean =
        owner.grantCurrent(this, candidate, operation)
    internal companion object {
        fun issue(owner: SessionCandidateAdmission, identity: Any) = SessionCandidateGrant(owner, identity)
    }
    override fun toString() = "SessionCandidateGrant([redacted])"
}

/**
 * Trusted runtime-only admission for candidates returned by the original registered source.
 * start/restore/background cannot implicitly enable it: only the coordinator's explicit user-search
 * flag begins a lease. All state is ephemeral and revocation never serializes or restores authority.
 */
class SessionCandidateAdmission(
    private val registry: ProviderRegistry,
    private val limits: SessionCandidateLimits = SessionCandidateLimits(),
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Source(val provider: VideoSearchProvider, val descriptor: ProviderDescriptor)
    private class Session(val stamp: SessionStamp, val queryKey: String, val until: Long, val sources: Map<String, Source>) {
        val pages = linkedMapOf<String, Entry>()
        val operations = IdentityHashMap<DiscoveryOperation, SourceOperation>()
        var reservedRequests = 0
        var reservedBytes = 0L
        var refusal: SessionAdmissionStatus? = null
    }
    private class SourceOperation(val source: Source, val authority: ProviderAuthorityView?) { var completed = false }
    private class Entry(val session: Session, var source: Source, var authority: ProviderAuthorityView?,
        var candidate: CandidateUrl, var operation: DiscoveryOperation) {
        val provenance = linkedSetOf(source.descriptor.id)
        var admitted = false
        var consumed = false
        var finished = false
        var validation: CandidateValidation? = null
        var grant: SessionCandidateGrant? = null
    }
    internal data class Accepted(val candidate: CandidateUrl, val first: Boolean, val validation: CandidateValidation?)
    private val lock = Any()
    private var session: Session? = null
    private val candidates = IdentityHashMap<CandidateUrl, Entry>()

    internal fun beginUserSearch(stamp: SessionStamp, queryKey: String) {
        val sources = registry.all().associate { it.descriptor.id to Source(it, it.descriptor.detachedDescriptor()) }
        val now = monotonicMillis()
        val until = if (now > Long.MAX_VALUE - limits.lifetimeMillis) Long.MAX_VALUE else now + limits.lifetimeMillis
        synchronized(lock) { candidates.clear(); session = Session(stamp, queryKey, until, sources) }
    }

    fun status(): SessionAdmissionStatus {
        val now = monotonicMillis()
        return synchronized(lock) {
            val active = session ?: return@synchronized SessionAdmissionStatus.INACTIVE
            when {
                now >= active.until -> SessionAdmissionStatus.EXPIRED
                active.reservedRequests >= limits.maxRequests -> SessionAdmissionStatus.REQUEST_LIMIT
                active.reservedBytes >= limits.maxBytes -> SessionAdmissionStatus.BYTE_LIMIT
                active.pages.size >= limits.maxUniquePages -> SessionAdmissionStatus.PAGE_LIMIT
                active.refusal != null -> active.refusal!!
                else -> SessionAdmissionStatus.ACTIVE
            }
        }
    }

    internal fun tracks(stamp: SessionStamp): Boolean = synchronized(lock) { session?.stamp == stamp }
    internal fun permitsSource(stamp: SessionStamp): Boolean {
        val now = monotonicMillis()
        return synchronized(lock) { session?.let { it.stamp == stamp && now < it.until } == true }
    }

    /** Keep the source fetch and B inside the same window without extending the original deadline. */
    internal fun boundDeadline(stamp: SessionStamp, proposedEpochDeadline: Long): Long {
        val monotonic = monotonicMillis()
        val remaining = synchronized(lock) {
            session?.takeIf { it.stamp == stamp }?.let { (it.until - monotonic).coerceAtLeast(0) }
        } ?: return minOf(proposedEpochDeadline, epochMillis())
        val now = epochMillis()
        val end = if (now > Long.MAX_VALUE - remaining) Long.MAX_VALUE else now + remaining
        return minOf(proposedEpochDeadline, end)
    }


    /** Immediate, permanent retirement of the current lease. Resuming a UI never renews it. */
    fun revoke() { synchronized(lock) { session = null; candidates.clear() } }

    private fun sourceCurrent(source: Source, authority: ProviderAuthorityView?, now: Long): Boolean =
        registry.find(source.descriptor.id) === source.provider && source.provider.descriptor == source.descriptor &&
            (!source.provider.requiresAuthority || authority != null) &&
            (authority == null || authority.descriptor == source.descriptor && authority.isCurrent(now))

    /** Reserve the provider's enforced aggregate search envelope before any provider network I/O. */
    internal fun reserveSource(provider: VideoSearchProvider, request: SearchRequest, authority: ProviderAuthorityView?,
        operation: DiscoveryOperation): DiscoveryError? {
        // Legacy providers may continue producing their existing results, but cannot mint grants.
        val envelope = provider.searchBudget ?: return null
        val active = synchronized(lock) { session } ?: return DiscoveryError.CANCELLED
        val source = active.sources[provider.descriptor.id] ?: return DiscoveryError.CANCELLED
        val now = epochMillis()
        if (source.provider !== provider || active.stamp != request.stamp || active.queryKey != request.profile.queryKey ||
            request.expectedDescriptor != source.descriptor || !sourceCurrent(source, authority, now) ||
            operation.cancellation.isCancelled || now >= operation.context.deadlineEpochMillis) return DiscoveryError.CANCELLED
        val monotonic = monotonicMillis()
        val bytes = minOf(envelope.maxBytes, operation.context.resourceBudget.maxBytes ?: envelope.maxBytes)
        return synchronized(lock) {
            if (session !== active || operation.cancellation.isCancelled) DiscoveryError.CANCELLED
            else if (monotonic >= active.until) DiscoveryError.DEADLINE_EXCEEDED
            else if (active.operations.containsKey(operation)) DiscoveryError.CANCELLED
            else if (envelope.maxRequests > limits.maxRequests - active.reservedRequests) {
                active.refusal = SessionAdmissionStatus.REQUEST_LIMIT; DiscoveryError.CAPACITY_REACHED
            } else if (bytes > limits.maxBytes - active.reservedBytes) {
                active.refusal = SessionAdmissionStatus.BYTE_LIMIT; DiscoveryError.CAPACITY_REACHED
            }
            else {
                active.reservedRequests += envelope.maxRequests; active.reservedBytes += bytes
                active.operations[operation] = SourceOperation(source, authority)
                null
            }
        }
    }

    internal fun accept(provider: VideoSearchProvider, request: SearchRequest, authority: ProviderAuthorityView?,
        candidate: CandidateUrl, operation: DiscoveryOperation, allowValidation: Boolean = true): Accepted? {
        val now = epochMillis()
        if (operation.cancellation.isCancelled || now >= operation.context.deadlineEpochMillis ||
            VerificationOriginPolicy.origin(candidate.pageUrl) == null) return null
        val active = synchronized(lock) { session } ?: return null
        val source = active.sources[provider.descriptor.id] ?: return null
        if (source.provider !== provider || request.stamp != active.stamp || request.profile.queryKey != active.queryKey ||
            request.expectedDescriptor != source.descriptor || !sourceCurrent(source, authority, now)) return null
        val monotonic = monotonicMillis()
        return synchronized(lock) {
            if (session !== active || monotonic >= active.until || operation.cancellation.isCancelled) return@synchronized null
            val reserved = active.operations[operation]
            if (reserved == null || reserved.completed || reserved.source !== source || reserved.authority !== authority) return@synchronized null
            val existing = active.pages[candidate.pageUrl]
            if (existing == null && active.pages.size >= limits.maxUniquePages) return@synchronized null
            val entry = existing ?: Entry(active, source, authority, candidate, operation).also { active.pages[candidate.pageUrl] = it }
            entry.provenance += source.descriptor.id
            if (!entry.admitted && allowValidation) {
                // Observing an unrun candidate does not consume its one validation admission.
                // Only another real trusted source response may take this slot; no copied token can.
                entry.source = source; entry.authority = authority; entry.candidate = candidate; entry.operation = operation
                entry.admitted = true; entry.consumed = false; entry.finished = false
                entry.grant = SessionCandidateGrant.issue(this, entry)
                candidates[candidate] = entry
                Accepted(candidate, true, null)
            } else Accepted(candidate, false, entry.validation)
        }
    }

    /** Only the exact canonical object accepted by the trusted pipeline can acquire constraints. */
    fun network(candidate: CandidateUrl, operation: DiscoveryOperation): EngineNetworkConstraints? {
        val entry = synchronized(lock) { candidates[candidate]?.takeIf { !it.finished } } ?: return null
        if (entry.operation !== operation || !entryCurrent(entry, candidate, operation, requireConsumed = false)) return null
        val origin = VerificationOriginPolicy.origin(candidate.pageUrl) ?: return null
        return EngineNetworkConstraints(setOf(origin), requireTls = true, maxRedirects = 0,
            sourceAdmissionRequired = true, requiresRobotsCheck = true, candidateGrant = entry.grant ?: return null)
    }

    private fun entryCurrent(entry: Entry, candidate: CandidateUrl, operation: DiscoveryOperation, requireConsumed: Boolean): Boolean {
        val now = epochMillis()
        if (entry.candidate !== candidate || entry.operation !== operation || operation.cancellation.isCancelled ||
            now >= operation.context.deadlineEpochMillis || !sourceCurrent(entry.source, entry.authority, now)) return false
        val monotonic = monotonicMillis()
        return synchronized(lock) { session === entry.session && candidates[candidate] === entry &&
            monotonic < entry.session.until && entry.session.operations[operation]?.completed == false &&
            (!requireConsumed || entry.consumed && !entry.finished) }
    }

    internal fun grantCurrent(grant: SessionCandidateGrant, candidate: CandidateUrl, operation: DiscoveryOperation): Boolean {
        val entry = grant.identity as? Entry ?: return false
        return entry.grant === grant && entryCurrent(entry, candidate, operation, requireConsumed = true)
    }

    internal fun consume(grant: SessionCandidateGrant, candidate: CandidateUrl, operation: DiscoveryOperation,
        requests: Int, bytes: Long): SessionValidationBudget? {
        val entry = grant.identity as? Entry ?: return null
        if (entry.grant !== grant || !entryCurrent(entry, candidate, operation, requireConsumed = false)) return null
        val monotonic = monotonicMillis()
        return synchronized(lock) {
            val active = entry.session
            if (session !== active || entry.consumed || entry.finished || operation.cancellation.isCancelled || monotonic >= active.until) return@synchronized null
            // Reserve before transport. Unknown, failed and cancelled work never refunds quota.
            val requestBudget = minOf(requests, limits.requestsPerPage, limits.maxRequests - active.reservedRequests)
            val byteBudget = minOf(bytes, limits.bytesPerPage, limits.maxBytes - active.reservedBytes)
            entry.consumed = true
            if (requestBudget <= 0 || byteBudget <= 0) return@synchronized null
            active.reservedRequests += requestBudget; active.reservedBytes += byteBudget
            SessionValidationBudget(requestBudget, byteBudget, active.until - monotonic)
        }
    }

    internal fun finish(grant: SessionCandidateGrant, candidate: CandidateUrl, operation: DiscoveryOperation) {
        val entry = grant.identity as? Entry ?: return
        synchronized(lock) {
            if (entry.grant === grant && entry.candidate === candidate && entry.operation === operation && entry.consumed) entry.finished = true
        }
    }

    internal fun complete(candidate: CandidateUrl, operation: DiscoveryOperation, validation: CandidateValidation) {
        val entry = synchronized(lock) { candidates[candidate] } ?: return
        if (!entryCurrent(entry, candidate, operation, requireConsumed = false)) return
        synchronized(lock) {
            if (session === entry.session && candidates[candidate] === entry) {
                if (!entry.consumed && validation.summary?.engineId == "scheduler" && validation.summary.outcome == EngineOutcome.UNAVAILABLE) {
                    // A busy physical scheduler performed no I/O and consumed no quota. Retire this
                    // exact token; only a later real source response may claim the still-unrun page.
                    entry.admitted = false; entry.grant = null; candidates.remove(candidate)
                } else entry.validation = validation
                entry.finished = true
            }
        }
    }

    internal fun endSourceOperation(operation: DiscoveryOperation) {
        synchronized(lock) {
            val active = session ?: return
            active.operations[operation]?.completed = true
            active.pages.values.filter { it.operation === operation }.forEach { it.finished = true }
        }
    }

    /** Bounded snapshots are provenance facts only; they never grant future network authority. */
    internal fun provenance(stamp: SessionStamp): Map<String, Set<String>> = synchronized(lock) {
        session?.takeIf { it.stamp == stamp }?.pages?.mapValues { it.value.provenance.toSet() }.orEmpty()
    }
    override fun toString() = "SessionCandidateAdmission([redacted])"
}
