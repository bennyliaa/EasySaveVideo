package com.easysavevideo.commons.rules

import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.commons.maintenance.ProviderHealthOutcome
import com.easysavevideo.commons.maintenance.ProviderPackageKind
import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Host-selected response source. Only the leased signed plan can supply an HTTP binding. */
fun interface RuleResponseSource { fun create(request: RuleResponseRequest): RuleResponseCall }
interface RuleResponseCall {
    fun execute(): InputStream
    /** Prompt, thread-safe, idempotent resource cancellation; a stuck call retains its runtime slot. */
    fun cancel()
}
class RuleResponseRequest internal constructor(
    val query: String,
    val locale: String,
    val cursor: String?,
    val deadlineEpochMillis: Long,
    val maximumBytes: Long,
    internal val httpBinding: RuleHttpBinding? = null,
) { override fun toString(): String = "RuleResponseRequest([redacted])" }

/** Compatibility facade preserving the original concrete, non-null acquisition API. */
class SignedJsonVideoSearchProvider private constructor(
    private val delegate: SignedRuleVideoSearchProvider,
) : VideoSearchProvider by delegate {
    constructor(repository: ProviderRepository, source: RuleResponseSource,
        clock: EpochClock = EpochClock(System::currentTimeMillis)) :
        this(SignedRuleVideoSearchProvider(repository, source, clock)) {
        require(repository.target.kind == ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1)
    }
    override fun acquire(request: SearchRequest, operation: DiscoveryOperation): ProviderRequestLease =
        delegate.acquire(request, operation)
    override fun toString(): String = "SignedJsonVideoSearchProvider([redacted])"
}

/** Shared SPI adapter; only the original repository's exact signed-plan lease can search. */
class SignedRuleVideoSearchProvider(
    private val repository: ProviderRepository,
    private val source: RuleResponseSource,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
) : VideoSearchProvider {
    init { require(repository.target.kind in setOf(ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1,
        ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1)) }
    override val descriptor: ProviderDescriptor get() = repository.ruleDescriptor()
    override val requiresAuthority: Boolean get() = true
    override fun progressAuthority(expected: ProviderDescriptor, nowMillis: Long): ProviderAuthorityView? =
        repository.currentRuleAuthority(expected, nowMillis)
    override fun refusal(profile: SearchProfile): DiscoveryError? = when {
        profile.filters.safeSearch -> DiscoveryError.UNSUPPORTED_FILTER
        profile.query.isBlank() || profile.query.any { it.isISOControl() } || !isWellFormedUnicode(profile.query) -> DiscoveryError.INVALID_QUERY
        descriptor.version == "unavailable" -> DiscoveryError.SOURCE_CONFIGURATION
        else -> null
    }

    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
        throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)

    override fun acquire(request: SearchRequest, operation: DiscoveryOperation): ProviderRequestLease {
        refusal(request.profile)?.let { throw DiscoveryFailure(it) }
        val expected = request.expectedDescriptor ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
        val lease = repository.acquireRuleExecution(expected, operation)
        val used = AtomicBoolean(false)
        return object : ProviderRequestLease {
            override val authority: ProviderAuthorityView = lease.authority
            override fun search(requestValue: SearchRequest, operationValue: DiscoveryOperation): ProviderPage {
                if (requestValue !== request || operationValue !== operation || !used.compareAndSet(false, true))
                    throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
                return execute(request, operation, lease)
            }
            override fun close() = lease.close()
        }
    }

    private fun execute(request: SearchRequest, operation: DiscoveryOperation, lease: RepositoryRuleExecutionLease): ProviderPage {
        lease.check()
        val expected = lease.authority.descriptor
        val now = clock.nowMillis()
        request.progress?.let { progress ->
            if (progress.profileId != request.profile.profileId || progress.queryKey != request.profile.queryKey ||
                !progress.isCompatible(expected, now) || progress.providerVersion != expected.version ||
                progress.providerId != expected.id) throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        }
        if (request.progress?.exhausted == true || request.profile.filters.sourceNamespaces.let {
                it.isNotEmpty() && it.none { id -> id.equals(expected.id, ignoreCase = true) }
            }) return ProviderPage(exhausted = true)
        val limits = lease.limits
        val deadline = minOf(operation.context.deadlineEpochMillis,
            if (now > Long.MAX_VALUE - limits.deadlineMillis) Long.MAX_VALUE else now + limits.deadlineMillis)
        val maximumBytes = minOf(limits.maxResponseBytes, operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE,
            if (lease.plan is BoundedHttpRule) 65_536L else Long.MAX_VALUE)
        val narrowed = operation.copy(context = operation.context.copy(deadlineEpochMillis = deadline,
            resourceBudget = operation.context.resourceBudget.copy(maxBytes = maximumBytes)))
        fun check() { lease.check(); narrowed.check(clock.nowMillis()) }
        var structureRecognized = false
        try {
            check()
            val binding = when (val plan = lease.plan) {
                is DeclarativeJsonRule -> null
                is BoundedHttpRule -> plan.bind(repository.target.httpEndpoint
                    ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION), request.profile.query,
                    request.profile.locale, request.progress?.cursor, ::check)
            }
            val call = source.create(RuleResponseRequest(request.profile.query, request.profile.locale,
                request.progress?.cursor, deadline, maximumBytes, binding))
            var cancellation: CancellationHandle? = null
            val bytes = try {
                cancellation = operation.cancellation.onCancel { call.cancel() }
                check()
                call.execute().use { body ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val allowed = minOf(buffer.size.toLong(), maximumBytes - output.size() + 1).toInt()
                        val count = body.read(buffer, 0, allowed)
                        check()
                        if (count < 0) break
                        if (count == 0 || count > allowed) throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
                        if (output.size().toLong() + count > maximumBytes) throw DiscoveryFailure(DiscoveryError.RESPONSE_TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { cancellation?.cancel(); call.cancel() }
            check()
            val page = projectCandidateRule(lease.plan, bytes, binding, expected, repository.target.sourceName,
                DiscoveryLimits(maxResponseBytes = maximumBytes, maxCandidatesPerResponse = limits.pageSize),
                EpochClock { check(); clock.nowMillis() }, narrowed) { structureRecognized = true }
            check()
            lease.complete(ProviderHealthOutcome.SUCCESS)
            check()
            return page.copy(cursorExpiresAtEpochMillis = page.nextCursor?.let { lease.cursorExpiry(clock.nowMillis()) })
        } catch (failure: DiscoveryFailure) {
            if (failure.code !in setOf(DiscoveryError.CANCELLED, DiscoveryError.DEADLINE_EXCEEDED)) {
                lease.complete(if (structureRecognized && failure.code == DiscoveryError.INVALID_RESULT)
                    ProviderHealthOutcome.STRUCTURE_FAILURE else ProviderHealthOutcome.INCONCLUSIVE)
            }
            throw failure
        } catch (_: Exception) {
            check()
            lease.complete(ProviderHealthOutcome.INCONCLUSIVE)
            throw DiscoveryFailure(DiscoveryError.PROVIDER_FAILURE)
        }
    }
    override fun toString(): String = "SignedRuleVideoSearchProvider([redacted])"
}
