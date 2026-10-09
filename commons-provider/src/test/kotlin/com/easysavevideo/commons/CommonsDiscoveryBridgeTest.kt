package com.easysavevideo.commons

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class CommonsDiscoveryBridgeTest {
    private fun searchRequest(safe: Boolean = false, cursor: String? = null) = SearchRequest(SessionStamp("session", 1),
        SearchProfile("profile", "Big Buck Bunny", "en", SearchFilters(safeSearch = safe)),
        cursor?.let { ProviderProgress("profile", CommonsIdentity.PROVIDER_ID, "unused", CommonsIdentity.VERSION, 1, it, null, false, null) })
    @Test fun bridgePreservesEveryRightsFieldFromRecordedMetadata() {
        val transport = TestTransport(Reply(fixture("big-buck-bunny-live.json")))
        val result = CommonsVideoSearchProvider(provider(transport)).search(searchRequest(), operation()).results.single()
        val rights = result.rights.snapshots.single()
        val original = CommonsResponseParser(5).parse(fixture("big-buck-bunny-live.json"), "key", 1000, 301000) {}.results.single()
        assertEquals(original.rights.author, rights.author)
        assertEquals(original.rights.credit, rights.credit)
        assertEquals(original.rights.customAttribution, rights.customAttribution)
        assertEquals(original.rights.licenseName, rights.licenseName)
        assertEquals(original.rights.licenseUrl, rights.licenseUrl)
        assertEquals(original.rights.usageTerms, rights.usageTerms)
        assertEquals(original.rights.copyrighted, rights.copyrighted)
        assertEquals(original.rights.attributionRequired, rights.attributionRequired)
        assertEquals(original.rights.nonFree, rights.nonFree)
        assertEquals(original.rights.restrictions, rights.restrictions)
        assertEquals(original.rights.deletionReason, rights.deletionReason)
        assertEquals(original.rights.sourcePageRevisionId, rights.sourcePageRevisionId)
        assertEquals(original.rights.retrievedAtEpochMillis, rights.retrievedAtEpochMillis)
        assertEquals(original.rights.metadataTermsUrl, rights.metadataTermsUrl)
        assertEquals(original.rights.review.name, rights.review.name)
        assertEquals(original.rights.provenance.map { it.field }, rights.provenance.map { it.field })
        assertTrue(rights.provenance.all { it.kind == RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT })
        assertEquals(CommonsIdentity.PROVIDER_ID, result.sourceNamespace)
        assertEquals(original.pageId.toString(), result.sourceVideoId)
        assertEquals(CommonsIdentity.SOURCE, result.sourceName)
        assertFalse(rights.downloadAuthorized)
        val normalized = ResultNormalizer.normalize(result, CommonsIdentity.PROVIDER_ID)!!
        assertEquals(rights, RightsCodec.decode(RightsCodec.encode(normalized.rights)).snapshots.single())
    }
    @Test fun requiredSafeSearchRefusesBeforeAnyTransportCall() {
        val transport = TestTransport()
        val source = CommonsVideoSearchProvider(provider(transport))
        assertEquals(DiscoveryError.UNSUPPORTED_FILTER, source.refusal(searchRequest(true).profile))
        try { source.search(searchRequest(true), operation()); fail() } catch (failure: DiscoveryFailure) {
            assertEquals(DiscoveryError.UNSUPPORTED_FILTER, failure.code); assertFalse(failure.retryable)
        }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun unrelatedNamespaceDoesNotFetchOrManufactureResults() {
        val transport = TestTransport()
        val request = searchRequest().let { it.copy(profile = it.profile.copy(filters = it.profile.filters.copy(sourceNamespaces = setOf("other")))) }
        val page = CommonsVideoSearchProvider(provider(transport)).search(request, operation())
        assertTrue(page.results.isEmpty()); assertTrue(page.exhausted); assertTrue(transport.requests.isEmpty())
    }
    @Test fun rateLimitRetryAtSurvivesBridgeExactly() {
        val transport = TestTransport(Reply(status = 429, headers = mapOf("Retry-After" to listOf("90"))))
        try { CommonsVideoSearchProvider(provider(transport)).search(searchRequest(), operation()); fail() } catch (failure: DiscoveryFailure) {
            assertEquals(DiscoveryError.RATE_LIMITED, failure.code); assertTrue(failure.retryable)
            assertEquals(91_000L, failure.retryAtEpochMillis)
        }
    }
    @Test fun rawContinuationIsReplayedAndUnknownDurationRemainsNull() {
        val transport = TestTransport(Reply(), Reply(fixture("search-live-2.json")))
        val source = CommonsVideoSearchProvider(provider(transport))
        val first = source.search(searchRequest(), operation())
        assertNotNull(first.nextCursor)
        val second = source.search(searchRequest(cursor = first.nextCursor), operation())
        assertTrue(transport.requests[1].url.rawQuery.contains("gsroffset="))
        assertTrue(second.results.all { it.rights.snapshots.size == 1 })
        val original = CommonsResponseParser(5).parse(fixture("big-buck-bunny-live.json"), "key", 1000, 301000) {}.results.single()
        assertNull(toDiscoveryResult(original.copy(durationMs = null)).durationMs)
    }
    @Test fun boundedDisplayTitleDoesNotSplitSurrogatesOrChangeSourceIdentity() {
        val original = CommonsResponseParser(5).parse(fixture("big-buck-bunny-live.json"), "key", 1000, 301000) {}.results.single()
        val mapped = toDiscoveryResult(original.copy(fileTitle = "a".repeat(511) + "\uD83D\uDE03" + "suffix"))
        assertEquals(511, mapped.title!!.length)
        assertEquals(original.pageId.toString(), mapped.sourceVideoId)
        assertEquals(original.pageUrl, mapped.pageUrl)
        assertEquals(original.rights.sourcePageUrl, mapped.rights.snapshots.single().sourcePageUrl)
    }
    @Test fun malformedQueryDoesNotFetch() {
        val transport = TestTransport(); val source = CommonsVideoSearchProvider(provider(transport))
        listOf("line\nbreak", "bad\uD800").forEach { query ->
            val request = searchRequest().let { it.copy(profile = it.profile.copy(query = query)) }
            try { source.search(request, operation()); fail() } catch (failure: DiscoveryFailure) {
                assertEquals(DiscoveryError.INVALID_QUERY, failure.code)
            }
        }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun normalizedEquivalentQueriesReuseCompatibleCursorAndAllFiltersBindCache() {
        val transport = TestTransport(Reply(), Reply(fixture("search-live-2.json")), Reply())
        val source = CommonsVideoSearchProvider(provider(transport))
        val first = source.search(searchRequest(), operation())
        val equivalent = searchRequest(cursor = first.nextCursor).let {
            it.copy(profile = it.profile.copy(query = "  big   buck bunny  ", locale = "EN"))
        }
        assertEquals(searchRequest().profile.queryKey, equivalent.profile.queryKey)
        source.search(equivalent, operation())
        assertEquals(2, transport.requests.size)
        val otherFilters = searchRequest().let { it.copy(profile = it.profile.copy(filters = it.profile.filters.copy(maximumDurationMs = 60000))) }
        source.search(otherFilters, operation())
        assertEquals(3, transport.requests.size)
    }

}
