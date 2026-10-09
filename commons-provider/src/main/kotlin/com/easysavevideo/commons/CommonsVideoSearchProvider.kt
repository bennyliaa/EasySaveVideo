package com.easysavevideo.commons

import com.easysavevideo.discovery.*
import java.text.Normalizer
import java.util.Locale

/** Rights-preserving metadata bridge. No media URL or save/playback authority crosses this SPI. */
class CommonsVideoSearchProvider(private val metadata: CommonsMetadataProvider) : VideoSearchProvider {
    override val descriptor = ProviderDescriptor(CommonsIdentity.PROVIDER_ID, CommonsIdentity.VERSION)
    override fun refusal(profile: SearchProfile): DiscoveryError? = when {
        profile.filters.safeSearch -> DiscoveryError.UNSUPPORTED_FILTER
        profile.query.isBlank() || profile.query.any { it.isISOControl() } || !isWellFormedUnicode(profile.query) || canonicalQuery(profile.query).length > 512 -> DiscoveryError.INVALID_QUERY
        else -> null
    }
    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
        refusal(request.profile)?.let { throw DiscoveryFailure(it) }
        if (request.profile.filters.sourceNamespaces.isNotEmpty() &&
            request.profile.filters.sourceNamespaces.none { it.equals(CommonsIdentity.PROVIDER_ID, ignoreCase = true) })
            return ProviderPage(exhausted = true)
        val language = request.profile.locale.takeIf {
            it.length <= 32 && it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
        }?.lowercase(Locale.ROOT) ?: "en"
        val page = try {
            val query = canonicalQuery(request.profile.query)
            metadata.search(CommonsSearchRequest(query, language, request.progress?.cursor,
                request.profile.filters.safeSearch, request.profile.queryKey), operation)
        } catch (failure: CommonsFailure) { throw failure.discoveryFailure() }
        return ProviderPage(page.results.map(::toDiscoveryResult), nextCursor = page.nextCursor,
            cursorExpiresAtEpochMillis = page.cursorExpiresAtEpochMillis, exhausted = page.exhausted,
            responseBytes = page.responseBytes)
    }
}

internal fun toDiscoveryResult(value: CommonsVideoMetadata): RawVideoResult {
    val rights = value.rights
    return RawVideoResult(pageUrl = value.pageUrl, videoConfirmed = true,
        title = value.fileTitle.take(512).dropLastWhile { it.isHighSurrogate() },
        sourceName = CommonsIdentity.SOURCE, sourceNamespace = CommonsIdentity.PROVIDER_ID,
        sourceVideoId = value.pageId.toString(), thumbnailUrl = value.thumbnailUrl, durationMs = value.durationMs,
        rights = ResultRights(listOf(RightsSnapshot(
            providerId = CommonsIdentity.PROVIDER_ID, sourceName = CommonsIdentity.SOURCE,
            sourceNamespace = CommonsIdentity.PROVIDER_ID, sourceItemId = value.pageId.toString(),
            sourcePageUrl = rights.sourcePageUrl, sourcePageRevisionId = rights.sourcePageRevisionId,
            retrievedAtEpochMillis = rights.retrievedAtEpochMillis, author = rights.author, credit = rights.credit,
            customAttribution = rights.customAttribution, licenseName = rights.licenseName, licenseUrl = rights.licenseUrl,
            usageTerms = rights.usageTerms, copyrighted = rights.copyrighted, attributionRequired = rights.attributionRequired,
            nonFree = rights.nonFree, restrictions = rights.restrictions.toList(), deletionReason = rights.deletionReason,
            review = RightsReview.valueOf(rights.review.name), provenance = rights.provenance.map {
                RightsFieldProvenance(it.field, it.apiReportedSource, RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT,
                    it.htmlConvertedToText, it.truncated)
            }, metadataTermsUrl = rights.metadataTermsUrl))))
}

private fun CommonsFailure.discoveryFailure(): DiscoveryFailure {
    val error = when (code) {
        CommonsError.UNSUPPORTED_FILTER -> DiscoveryError.UNSUPPORTED_FILTER
        CommonsError.DISABLED, CommonsError.CONTACT_REQUIRED, CommonsError.VERSION_REQUIRED,
        CommonsError.TRANSPORT_UNAVAILABLE -> DiscoveryError.SOURCE_CONFIGURATION
        CommonsError.INVALID_REQUEST -> DiscoveryError.INVALID_QUERY
        CommonsError.CANCELLED -> DiscoveryError.CANCELLED
        CommonsError.DEADLINE_EXCEEDED -> DiscoveryError.DEADLINE_EXCEEDED
        CommonsError.RESPONSE_TOO_LARGE -> DiscoveryError.RESPONSE_TOO_LARGE
        CommonsError.RATE_LIMITED -> DiscoveryError.RATE_LIMITED
        CommonsError.BUSY -> DiscoveryError.BUSY
        else -> DiscoveryError.PROVIDER_FAILURE
    }
    return DiscoveryFailure(error, code in setOf(CommonsError.RATE_LIMITED, CommonsError.BUSY, CommonsError.SERVICE_ERROR), retryAtEpochMillis)
}

private fun canonicalQuery(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
