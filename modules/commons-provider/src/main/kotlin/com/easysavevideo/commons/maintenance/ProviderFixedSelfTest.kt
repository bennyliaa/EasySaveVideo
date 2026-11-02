package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsError
import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsResponseParser
import com.easysavevideo.commons.CommonsRightsReview
import com.easysavevideo.commons.rules.BoundedHttpRule
import com.easysavevideo.commons.rules.DeclarativeJsonRule
import com.easysavevideo.commons.rules.RuleHttpBinding
import com.easysavevideo.commons.rules.RuleResponseFormat
import com.easysavevideo.commons.rules.projectCandidateRule
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderDescriptor

/**
 * Host-owned synthetic vectors only: no requests, user data or executable input.
 * Legacy vectors exercise the built-in parser; candidate vectors exercise the exact authenticated
 * finite plan and, for HTTP, the encoded request template without executing a response source. Neither proves compatibility with a live site. Each path has fixed input/check budgets.
 * Cancellation is propagated, never converted to an incompatibility/quarantine verdict.
 */
internal object ProviderFixedSelfTest {
    private const val NOW = 1_000L
    private const val KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val MAX_CHECKS = 2_048
    private val page = CommonsIdentity.API.substringBefore("/w/") + "/wiki/File:ESV_self_test.webm"
    private val sample = """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":6,"title":"File:ESV_self_test.webm","videoinfo":[{"mime":"video/webm","mediatype":"VIDEO","descriptionurl":"$page","duration":1.25,"width":320,"height":180}]}]}}""".toByteArray()
    private val empty = """{"batchcomplete":true,"query":{"pages":[]}}""".toByteArray()
    private val continued = """{"continue":{"continue":"gsroffset||","gsroffset":1},"query":{"pages":[]}}""".toByteArray()
    private val invalid = listOf(
        """{"query":{"pages":"not-an-array"}}""",
        """{"query":{"pages":[]},"continue":{"action":"edit"}}""",
        """{"batchcomplete":true,"batchcomplete":false}""",
        """{"query":{"pages":[{"pageid":1,"ns":6},{"pageid":1,"ns":6}]}}""",
    ).map { it.toByteArray() }

    /** Runs the exact authenticated plan against host-owned vectors, never package-defined assertions. */
    fun run(value: SignedProviderPackage, checkCancelled: () -> Unit): Boolean {
        if (value.kind == ProviderPackageKind.COMMONS_PARAMETERS_V1) return run(value.policy, checkCancelled)
        checkCancelled()
        val plan = value.candidatePlan ?: return false
        val target = value.policy.target
        var checks = 0
        // HTTP HTML uses bounded tree scans in addition to the original JSON checks. This is a
        // finite self-test budget, not a promise of hard preemption during Jsoup's initial parse.
        val maxChecks = if (plan is BoundedHttpRule) 2_000_000 else MAX_CHECKS * 8
        fun check() {
            checkCancelled()
            if (++checks > maxChecks) throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        }
        val cancellation = DiscoveryCancellation()
        val operation = DiscoveryOperation(OperationContext("rule-fixed-self-test", NOW + 1_000, cancellation,
            resourceBudget = ResourceBudget(maxBytes = value.policy.limits.maxResponseBytes)), cancellation)
        val descriptor = ProviderDescriptor(target.providerId, target.providerVersion)
        val limits = DiscoveryLimits(maxResponseBytes = value.policy.limits.maxResponseBytes,
            maxCandidatesPerResponse = value.policy.limits.pageSize)
        val clock = EpochClock { check(); NOW }
        fun binding(vector: ProviderRuleSelfTestVector): RuleHttpBinding? = when (plan) {
            is DeclarativeJsonRule -> null
            is BoundedHttpRule -> {
                val input = vector.httpRequest ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
                val endpoint = target.httpEndpoint ?: throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
                plan.bind(endpoint, input.query, input.locale, input.cursor, ::check).also {
                    if (it.encodedPathAndQuery != input.expectedEncodedPathAndQuery)
                        throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
                }
            }
        }
        try {
            for (vector in target.selfTestVectors) {
                check()
                val bytes = vector.responseBytes
                if (bytes.size > value.policy.limits.maxResponseBytes) return false
                val page = projectCandidateRule(plan, bytes, binding(vector), descriptor, target.sourceName,
                    limits, clock, operation) {}
                if (page.results.isNotEmpty() || page.candidates != vector.expectedCandidates ||
                    page.nextCursor != vector.expectedNextCursor || page.exhausted != vector.expectedExhausted) return false
            }
            val invalid = mutableListOf(byteArrayOf(0xc3.toByte(), 0x28))
            if (plan is DeclarativeJsonRule || target.httpEndpoint?.format == RuleResponseFormat.JSON)
                invalid += "{\"duplicate\":1,\"duplicate\":2}".toByteArray()
            val negativeBinding = binding(target.selfTestVectors.first())
            for (bytes in invalid) {
                check()
                try {
                    projectCandidateRule(plan, bytes, negativeBinding, descriptor, target.sourceName,
                        limits, clock, operation) {}
                    return false
                } catch (failure: DiscoveryFailure) {
                    if (failure.code != DiscoveryError.INVALID_RESULT) throw failure
                }
            }
        } catch (failure: DiscoveryFailure) {
            if (failure.code in setOf(DiscoveryError.CANCELLED, DiscoveryError.DEADLINE_EXCEEDED)) throw failure
            return false
        }
        checkCancelled()
        return true
    }

    fun run(policy: ProviderPolicy, checkCancelled: () -> Unit): Boolean {
        checkCancelled()
        if (!policy.enabled) return true
        val positives = listOf(sample, empty, continued)
        if (positives.any { it.size > policy.limits.maxResponseBytes }) return false
        var checks = 0
        fun check() {
            checkCancelled()
            check(++checks <= MAX_CHECKS) { "SELF_TEST_RESOURCE_LIMIT" }
        }
        val parser = CommonsResponseParser(policy.limits.pageSize)
        val parsed = parser.parse(sample, KEY, NOW, NOW + 1_000, ::check)
        val video = parsed.results.singleOrNull() ?: return false
        if (!parsed.exhausted || parsed.nextCursor != null || video.pageId != 1L || video.durationMs != 1_250L ||
            video.width != 320 || video.height != 180 || video.rights.downloadAuthorized ||
            video.rights.review != CommonsRightsReview.INCOMPLETE) return false
        val blank = parser.parse(empty, KEY, NOW, NOW + 1_000, ::check)
        if (!blank.exhausted || blank.results.isNotEmpty()) return false
        val next = parser.parse(continued, KEY, NOW, NOW + 1_000, ::check)
        if (next.exhausted || next.nextCursor == null || next.results.isNotEmpty()) return false
        for (bytes in invalid) {
            check()
            try {
                parser.parse(bytes, KEY, NOW, NOW + 1_000, ::check)
                return false
            } catch (failure: CommonsFailure) {
                if (failure.code != CommonsError.INVALID_RESPONSE) return false
            }
        }
        checkCancelled()
        return true
    }
}
