package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.commons.rules.RuleHttpEndpoint
import com.easysavevideo.discovery.CandidateUrl
import java.util.Collections

/** Explicit host admission kinds; none grants keys, directory access, or activation authority. */
enum class ProviderPackageKind { COMMONS_PARAMETERS_V1, DECLARATIVE_JSON_CANDIDATES_V1, BOUNDED_HTTP_CANDIDATES_V1 }

/** Host-owned request inputs and expected encoding. Neither contains networking authority. */
class ProviderRuleHttpSelfTestInput(
    val query: String,
    val locale: String,
    val cursor: String? = null,
    val expectedEncodedPathAndQuery: String,
) {
    init {
        require(query.length in 1..512 && boundedInput(query))
        require(locale.length in 1..64 && locale.all { it.code in 33..126 })
        require(cursor == null || boundedInput(cursor))
        require(expectedEncodedPathAndQuery.length in 1..16_384 && expectedEncodedPathAndQuery.startsWith('/') &&
            expectedEncodedPathAndQuery.none { it.code !in 33..126 || it == '#' || it == '\\' })
    }
    override fun toString(): String = "ProviderRuleHttpSelfTestInput([redacted])"
    private companion object {
        fun boundedInput(value: String): Boolean = value.isNotBlank() && isWellFormedUnicode(value) &&
            value.none { it.isISOControl() } && value.toByteArray(Charsets.UTF_8).size <= 2048
    }
}

/** Fixed host-owned expected outputs. Signed packages cannot supply their own pass conditions. */
class ProviderRuleSelfTestVector(
    responseBytes: ByteArray,
    expectedCandidates: List<CandidateUrl>,
    val expectedNextCursor: String? = null,
    val expectedExhausted: Boolean = true,
    val httpRequest: ProviderRuleHttpSelfTestInput? = null,
) {
    private val response = responseBytes.also { require(it.size in 1..MAX_RESPONSE_BYTES) }.copyOf()
    val responseBytes: ByteArray get() = response.copyOf()
    val expectedCandidates: List<CandidateUrl> = Collections.unmodifiableList(
        expectedCandidates.also { require(it.size <= MAX_CANDIDATES) }.toList())

    init {
        require(responseBytes.size in 1..MAX_RESPONSE_BYTES)
        require(expectedCandidates.size <= MAX_CANDIDATES)
        require(expectedCandidates.all { candidate ->
            candidate.pageUrl.length in 1..4096 && isWellFormedUnicode(candidate.pageUrl) &&
                candidate.pageUrl.none { it.isISOControl() } &&
                candidate.sourceReported.let { metadata ->
                    metadata == null || (boundedText(metadata.title, 512) &&
                        boundedText(metadata.sourceName, 128) && boundedText(metadata.description, 2048) &&
                        (metadata.reportedDurationMs?.let { it > 0 } != false) &&
                        (metadata.reportedPublishedAtEpochMillis?.let { it >= 0 } != false))
                }
        })
        require(expectedNextCursor == null || (expectedNextCursor.isNotBlank() &&
            isWellFormedUnicode(expectedNextCursor) && expectedNextCursor.none { it.isISOControl() } &&
            expectedNextCursor.toByteArray(Charsets.UTF_8).size <= 2048))
        require(expectedExhausted == (expectedNextCursor == null))
    }

    override fun toString(): String = "ProviderRuleSelfTestVector([redacted])"

    companion object {
        const val MAX_RESPONSE_BYTES = 16_384
        const val MAX_CANDIDATES = 5
        private fun boundedText(value: String?, max: Int): Boolean = value == null ||
            (value.length <= max && isWellFormedUnicode(value) && value.none { it.isISOControl() })
    }
}

/** Immutable host configuration. Only the existing owner decides which fixed targets may open. */
class ProviderTarget private constructor(
    val kind: ProviderPackageKind,
    val providerId: String,
    val providerVersion: String,
    val sourceName: String,
    val localLimits: CommonsLimits,
    val appVersionCode: Int,
    val specVersion: Int,
    val runtimeVersion: Int,
    selfTestVectors: List<ProviderRuleSelfTestVector>,
    val httpEndpoint: RuleHttpEndpoint? = null,
) {
    val selfTestVectors: List<ProviderRuleSelfTestVector> = Collections.unmodifiableList(
        selfTestVectors.also { require(it.size <= MAX_SELF_TEST_VECTORS) }.toList())
    val maxPackageBytes: Int get() = when (kind) {
        ProviderPackageKind.COMMONS_PARAMETERS_V1 -> ProviderPackageProtocol.MAX_ENVELOPE_BYTES
        ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1, ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 ->
            ProviderPackageProtocol.MAX_V2_ENVELOPE_BYTES
    }
    val maxPackagePayloadBytes: Int get() = when (kind) {
        ProviderPackageKind.COMMONS_PARAMETERS_V1 -> ProviderPackageProtocol.MAX_PAYLOAD_BYTES
        ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1, ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 ->
            ProviderPackageProtocol.MAX_V2_PAYLOAD_BYTES
    }
    val maxPolicyBytes: Int get() = RepositoryPolicyProtocol.MAX_ENVELOPE_BYTES
    val maxPolicyPayloadBytes: Int get() = RepositoryPolicyProtocol.MAX_PAYLOAD_BYTES

    init {
        require(token(providerId, ProviderPackageProtocol.MAX_TARGET_ID_BYTES))
        require(token(providerVersion, ProviderPackageProtocol.MAX_TARGET_VERSION_BYTES))
        require(sourceName.length in 1..128 && sourceName.isNotBlank() &&
            isWellFormedUnicode(sourceName) && sourceName.none { it.isISOControl() })
        require(appVersionCode > 0 && specVersion > 0 && runtimeVersion > 0)
        val defaults = CommonsLimits()
        require(localLimits.pageSize <= defaults.pageSize && localLimits.maxResponseBytes <= defaults.maxResponseBytes &&
            localLimits.deadlineMillis <= defaults.deadlineMillis && localLimits.cacheTtlMillis <= defaults.cacheTtlMillis &&
            localLimits.cacheEntries <= defaults.cacheEntries)
        when (kind) {
            ProviderPackageKind.COMMONS_PARAMETERS_V1 -> require(selfTestVectors.isEmpty() && httpEndpoint == null)
            ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 -> {
                require(httpEndpoint == null && selfTestVectors.all { it.httpRequest == null })
                require(localLimits.maxResponseBytes <= MAX_JSON_RESPONSE_BYTES)
            }
            ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> {
                require(httpEndpoint != null && selfTestVectors.all { it.httpRequest != null })
                require(localLimits.maxResponseBytes <= MAX_HTTP_RESPONSE_BYTES)
                require(selfTestVectors.any { it.expectedCandidates.isEmpty() })
                require(selfTestVectors.any { it.httpRequest?.cursor != null })
                require(selfTestVectors.all { it.httpRequest!!.locale in httpEndpoint.allowedLocales })
            }
        }
        if (kind in setOf(ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1,
                ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1)) {
            require(providerId != CommonsIdentity.PROVIDER_ID)
            require(selfTestVectors.size in 1..MAX_SELF_TEST_VECTORS)
            require(selfTestVectors.any { it.expectedCandidates.isNotEmpty() })
            require(selfTestVectors.all { vector ->
                vector.responseBytes.size <= localLimits.maxResponseBytes &&
                    vector.expectedCandidates.size <= localLimits.pageSize &&
                    vector.expectedCandidates.all { it.sourceReported?.sourceName == sourceName }
            })
        }
    }

    override fun toString(): String = "ProviderTarget([redacted])"

    companion object {
        const val MAX_JSON_RESPONSE_BYTES = 256L * 1024
        const val MAX_HTTP_RESPONSE_BYTES = 64L * 1024
        const val MAX_SELF_TEST_VECTORS = 8
        val LEGACY = ProviderTarget(ProviderPackageKind.COMMONS_PARAMETERS_V1,
            CommonsIdentity.PROVIDER_ID, CommonsIdentity.VERSION, CommonsIdentity.SOURCE,
            CommonsLimits(), 1, 1, 1, emptyList())

        fun declarativeJson(
            providerId: String,
            providerVersion: String,
            sourceName: String,
            selfTestVectors: List<ProviderRuleSelfTestVector>,
            localLimits: CommonsLimits = CommonsLimits(pageSize = 5, maxResponseBytes = MAX_JSON_RESPONSE_BYTES),
            appVersionCode: Int = 1,
            specVersion: Int = 1,
            runtimeVersion: Int = 1,
        ): ProviderTarget = ProviderTarget(ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1,
            providerId, providerVersion, sourceName, localLimits, appVersionCode, specVersion, runtimeVersion, selfTestVectors)

        fun httpCandidates(
            providerId: String,
            providerVersion: String,
            sourceName: String,
            endpoint: RuleHttpEndpoint,
            selfTestVectors: List<ProviderRuleSelfTestVector>,
            localLimits: CommonsLimits = CommonsLimits(pageSize = 5, maxResponseBytes = MAX_HTTP_RESPONSE_BYTES),
            appVersionCode: Int = 1,
            specVersion: Int = 1,
            runtimeVersion: Int = 1,
        ): ProviderTarget = ProviderTarget(ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1,
            providerId, providerVersion, sourceName, localLimits, appVersionCode, specVersion, runtimeVersion,
            selfTestVectors, endpoint)

        private fun token(value: String, max: Int): Boolean = value.length in 1..max && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
        }
    }
}
