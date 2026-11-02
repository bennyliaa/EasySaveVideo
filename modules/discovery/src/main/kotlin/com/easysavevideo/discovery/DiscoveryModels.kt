package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.RepeatPolicy
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun framed(vararg fields: String): String = fields.joinToString("") { "${it.length}:$it" }
internal fun requireId(value: String) { require(value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) }

data class SearchFilters(
    val sourceNamespaces: Set<String> = emptySet(),
    val minimumDurationMs: Long? = null,
    val maximumDurationMs: Long? = null,
    val includeUnknownDuration: Boolean = true,
    val safeSearch: Boolean = true,
) {
    init {
        require(sourceNamespaces.size <= 100)
        sourceNamespaces.forEach(::requireId)
        require(minimumDurationMs == null || minimumDurationMs >= 0)
        require(maximumDurationMs == null || maximumDurationMs >= 0)
        require(minimumDurationMs == null || maximumDurationMs == null || minimumDurationMs <= maximumDurationMs)
    }
    internal fun key(): String = framed(sourceNamespaces.map { it.lowercase(Locale.ROOT) }.sorted().joinToString(","),
        minimumDurationMs.toString(), maximumDurationMs.toString(), includeUnknownDuration.toString(), safeSearch.toString())
}

/** Local ordering of unallocated results. RELEVANCE preserves provider arrival, not an inferred score. */
enum class ResultSort { RELEVANCE, NEWEST, DURATION_SHORTEST, DURATION_LONGEST }

/** Functional data, never a diagnostic or telemetry payload. History and sort are not source-query parameters. */
data class SearchProfile(
    val profileId: String,
    val query: String,
    val locale: String = "und",
    val filters: SearchFilters = SearchFilters(),
    val historyPolicy: RepeatPolicy = RepeatPolicy.PREFER_NEW,
    val sortOrder: ResultSort = ResultSort.RELEVANCE,
) {
    init { requireId(profileId); require(query.length <= 512); require(locale.length in 1..64) }
    val queryKey: String get() = digest(framed(
        Normalizer.normalize(query, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT),
        locale.lowercase(Locale.ROOT), filters.key()))
    override fun toString(): String = "SearchProfile([redacted])"
}

data class SessionStamp(val sessionId: String, val epoch: Long) {
    init { requireId(sessionId); require(epoch > 0) }
}

data class RawVideoResult(
    val pageUrl: String,
    val videoConfirmed: Boolean,
    val title: String? = null,
    val sourceName: String? = null,
    val sourceNamespace: String? = null,
    val sourceVideoId: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val description: String? = null,
    val publishedAtEpochMillis: Long? = null,
    val rights: ResultRights = ResultRights(),
) { override fun toString(): String = "RawVideoResult([redacted])" }

data class VideoSearchResult(
    val fingerprint: String,
    val canonicalPageUrl: String,
    val title: String?,
    val sourceName: String,
    val sourceNamespace: String?,
    val sourceVideoId: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val description: String?,
    val publishedAtEpochMillis: Long?,
    val provenance: Set<String>,
    val identityAliases: Set<String>,
    val identityVersion: Int = 1,
    val rights: ResultRights = ResultRights(),
) {
    val resultId: String get() = fingerprint
    override fun toString(): String = "VideoSearchResult([redacted])"
}

/** The source's report is never page validation, media identity, or licensing evidence. */
enum class CandidateMetadataLevel { SOURCE_REPORTED }
data class SourceReportedCandidateMetadata(
    val title: String? = null,
    val sourceName: String? = null,
    val description: String? = null,
    val reportedDurationMs: Long? = null,
    val reportedPublishedAtEpochMillis: Long? = null,
    val metadataLevel: CandidateMetadataLevel = CandidateMetadataLevel.SOURCE_REPORTED,
) { override fun toString(): String = "SourceReportedCandidateMetadata([redacted])" }

data class CandidateUrl(val pageUrl: String, val sourceReported: SourceReportedCandidateMetadata? = null) { override fun toString(): String = "CandidateUrl([redacted])" }

enum class ProviderProgressScope { DURABLE, SESSION }

data class ProviderDescriptor(
    val id: String,
    val version: String,
    val cursorSchemaVersion: Int = 1,
    val compatibleCursorVersions: Set<String> = emptySet(),
    val progressScope: ProviderProgressScope = ProviderProgressScope.DURABLE,
) {
    init { requireId(id); requireId(version); require(cursorSchemaVersion > 0); compatibleCursorVersions.forEach(::requireId) }
}

data class ProviderProgress(
    val profileId: String,
    val providerId: String,
    val queryKey: String,
    val providerVersion: String,
    val cursorSchemaVersion: Int,
    val cursor: String?,
    val expiresAtEpochMillis: Long?,
    val exhausted: Boolean,
    val lastSuccessAtEpochMillis: Long?,
) {
    init { require(cursor == null || cursor.length <= 8192); require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0) }
    fun isCompatible(descriptor: ProviderDescriptor, nowMillis: Long): Boolean =
        descriptor.id == providerId && cursorSchemaVersion == descriptor.cursorSchemaVersion &&
            (providerVersion == descriptor.version || providerVersion in descriptor.compatibleCursorVersions) &&
            (expiresAtEpochMillis == null || nowMillis < expiresAtEpochMillis)
    override fun toString(): String = "ProviderProgress([redacted])"
}

enum class DiscoveryError {
    INVALID_RESULT, RESPONSE_TOO_LARGE, TOO_MANY_CANDIDATES, VALIDATOR_UNAVAILABLE,
    PROVIDER_FAILURE, DEADLINE_EXCEEDED, CANCELLED, BUSY, REPEATED_CURSOR, NO_NEW_RESULTS,
    STORAGE_UNAVAILABLE, CAPACITY_REACHED, UNSUPPORTED_FILTER, SOURCE_CONFIGURATION, RATE_LIMITED, INVALID_QUERY,
}
class DiscoveryFailure(val code: DiscoveryError, val retryable: Boolean = false, val retryAtEpochMillis: Long? = null) : RuntimeException(code.name)
data class ProviderHealth(val failureCount: Int = 0, val cooldownUntilEpochMillis: Long = 0, val lastError: DiscoveryError? = null,
    val blockedQueryKey: String? = null, val cooldownQueryKey: String? = null, val cooldownProviderVersion: String? = null)

data class DiscoveryLimits(
    val pageSize: Int = 20,
    val poolCapacity: Int = 500,
    val maxRequestsPerRound: Int = 6,
    val requestDeadlineMillis: Long = 15_000,
    val maxResponseBytes: Long = 2L * 1024 * 1024,
    val maxCandidatesPerResponse: Int = 100,
    val maxRetries: Int = 2,
    val cooldownMillis: Long = 30_000,
) {
    init {
        require(pageSize in 1..100); require(poolCapacity in 5..500)
        require(pageSize * 2 < poolCapacity); require(maxRequestsPerRound in 1..6)
        require(requestDeadlineMillis in 1..15_000); require(maxResponseBytes in 1..2L * 1024 * 1024)
        require(maxCandidatesPerResponse in 1..100); require(maxRetries in 0..2); require(cooldownMillis > 0)
    }
    val lowWatermark: Int get() = pageSize * 2
    val highWatermark: Int get() = minOf(pageSize * 5, poolCapacity)
    fun concurrency(mode: DiscoveryMode): Int = when (mode) {
        DiscoveryMode.POWER_SAVING -> 1
        DiscoveryMode.BALANCED -> 2
        DiscoveryMode.AGGRESSIVE -> 3
    }
}
