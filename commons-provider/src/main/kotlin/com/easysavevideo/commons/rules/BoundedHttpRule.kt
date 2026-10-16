package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.ProviderPage

/** Data-only plans are not publisher trust, repository authority, or permission to make a request. */
sealed interface CandidateRulePlan { val ruleVersion: Long }

/** One closed GET template and one finite response projection for an exact host-owned endpoint. */
class BoundedHttpRule private constructor(
    override val ruleVersion: Long,
    private val endpointId: String,
    private val path: String,
    private val query: List<RuleHttpQueryValue>,
    internal val response: Response,
) : CandidateRulePlan {
    internal sealed interface Response {
        class Json(val rule: DeclarativeJsonRule) : Response
        class Html(val projection: DeclarativeHtmlProjection) : Response
    }

    internal fun bind(endpoint: RuleHttpEndpoint, query: String, locale: String, cursor: String?,
        check: () -> Unit): RuleHttpBinding =
        endpoint.bind(endpointId, path, this.query, query, locale, cursor, check)

    internal fun checkBinding(binding: RuleHttpBinding) {
        if (binding.endpoint.endpointId != endpointId || binding.format != when (response) {
                is Response.Json -> RuleResponseFormat.JSON
                is Response.Html -> RuleResponseFormat.HTML
            }) invalidRule()
    }

    override fun toString(): String = "BoundedHttpRule([redacted])"

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_RULE_BYTES = 16_384
        const val MAX_RESPONSE_BYTES = 65_536L

        fun compile(bytes: ByteArray, endpoint: RuleHttpEndpoint, check: () -> Unit = {}): BoundedHttpRule {
            fun checked() {
                if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
                try { check() }
                catch (failure: DiscoveryFailure) { throw failure }
                catch (_: Exception) { ruleFailure(DiscoveryError.CANCELLED) }
                if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
            }
            checked()
            if (bytes.size > MAX_RULE_BYTES) invalidRule()
            val root = try { StrictJson.read(bytes.copyOf(), ::checked) as? Json.Obj ?: invalidRule() }
                catch (_: CommonsFailure) { invalidRule() }
            val values = exactFields(root, setOf("schemaVersion", "ruleVersion", "request", "response"))
            if ((values["schemaVersion"] as? Json.Num)?.raw != SCHEMA_VERSION.toString()) invalidRule()
            val rawVersion = (values["ruleVersion"] as? Json.Num)?.raw ?: invalidRule()
            if (rawVersion.isEmpty() || rawVersion.any { it !in '0'..'9' }) invalidRule()
            val version = rawVersion.toLongOrNull()?.takeIf { it > 0 } ?: invalidRule()
            val request = exactFields(values["request"], setOf("endpointId", "path", "query"))
            val id = string(request["endpointId"])
            if (id.length != 64 || id.any { it !in '0'..'9' && it !in 'a'..'f' }) invalidRule()
            val path = string(request["path"])
            if (path.length !in 1..256 || !path.startsWith('/') || path.any { it.code !in 33..126 ||
                    it in "?#\\%" } || path.split('/').any { it == "." || it == ".." }) invalidRule()
            val rawQuery = (request["query"] as? Json.Arr)?.values ?: invalidRule()
            if (rawQuery.size !in 1..8) invalidRule()
            val names = hashSetOf<String>()
            val query = rawQuery.map { value ->
                checked()
                val fields = (value as? Json.Obj)?.fields ?: invalidRule()
                if (fields.keys != setOf("name", "input") && fields.keys != setOf("name", "literal")) invalidRule()
                val name = string(fields["name"])
                if (!asciiToken(name, 32) || !names.add(name)) invalidRule()
                if ("input" in fields) RuleHttpQueryValue(name, when (string(fields["input"])) {
                    "query" -> RuleInputSlot.QUERY
                    "locale" -> RuleInputSlot.LOCALE
                    "cursor" -> RuleInputSlot.CURSOR
                    else -> invalidRule()
                }, null)
                else {
                    val literal = string(fields["literal"])
                    if (literal.isEmpty() || !isWellFormedUnicode(literal) || literal.any { it.isISOControl() } ||
                        literal.toByteArray(Charsets.UTF_8).size > 128) invalidRule()
                    RuleHttpQueryValue(name, null, literal)
                }
            }.toList()
            endpoint.validateTemplate(id, path, query, ::checked)
            val projection = (values["response"] as? Json.Obj)?.fields ?: invalidRule()
            val response = when (string(projection["format"])) {
                "json" -> {
                    if (endpoint.format != RuleResponseFormat.JSON) invalidRule()
                    Response.Json(DeclarativeJsonRule.compileMapping(version, projection.filterKeys { it != "format" }))
                }
                "html" -> {
                    if (endpoint.format != RuleResponseFormat.HTML) invalidRule()
                    Response.Html(DeclarativeHtmlProjection.compile(projection.filterKeys { it != "format" }, ::checked))
                }
                else -> invalidRule()
            }
            checked()
            return BoundedHttpRule(version, id, path, query, response)
        }

        private fun exactFields(value: Json?, expected: Set<String>): Map<String, Json> =
            (value as? Json.Obj)?.fields?.takeIf { it.keys == expected } ?: invalidRule()

        private fun string(value: Json?): String = (value as? Json.Str)?.value ?: invalidRule()
    }
}

/** Shared pure projection. Both installed kinds retain one lease/read/health lifecycle in the SPI. */
internal fun projectCandidateRule(
    plan: CandidateRulePlan,
    bytes: ByteArray,
    binding: RuleHttpBinding?,
    descriptor: ProviderDescriptor,
    sourceName: String,
    limits: DiscoveryLimits,
    clock: EpochClock,
    operation: DiscoveryOperation,
    structureRecognized: () -> Unit,
): ProviderPage = when (plan) {
    is DeclarativeJsonRule -> {
        if (binding != null) invalidRule()
        DeclarativeJsonRuleExecutor(plan, descriptor, sourceName, limits, clock)
            .executeObserved(bytes, operation, structureRecognized)
    }
    is BoundedHttpRule -> {
        val actualBinding = binding ?: invalidRule()
        plan.checkBinding(actualBinding)
        if (bytes.size > BoundedHttpRule.MAX_RESPONSE_BYTES) ruleFailure(DiscoveryError.RESPONSE_TOO_LARGE)
        when (val response = plan.response) {
            is BoundedHttpRule.Response.Json -> DeclarativeJsonRuleExecutor(response.rule, descriptor,
                sourceName, limits, clock).executeObserved(bytes, operation, structureRecognized)
            is BoundedHttpRule.Response.Html -> DeclarativeHtmlProjectionExecutor(response.projection,
                descriptor, sourceName, limits, clock, actualBinding.url)
                .executeObserved(bytes, operation, structureRecognized)
        }
    }
}

internal fun asciiToken(value: String, maximum: Int): Boolean = value.length in 1..maximum && value.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
}
