package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class PendingCandidateTest {
    private val filters = SearchFilters(safeSearch = false)
    private val candidate = CandidateUrl("https://video.example/watch", SourceReportedCandidateMetadata(title = "Source title", sourceName = "Source"))
    @Test fun existingUnconfiguredPipelineStillRefusesCandidates() {
        try { ValidationPipeline(clock = FakeClock()).validate("source", ProviderPage(candidates = listOf(candidate)), filters, operation()); fail() }
        catch (expected: DiscoveryFailure) { assertEquals(DiscoveryError.VALIDATOR_UNAVAILABLE, expected.code) }
    }
    @Test fun optInChannelNeverCreatesVerifiedResult() {
        val page = ValidationPipeline(clock = FakeClock(), retainUnvalidatedCandidates = true)
            .validate("source", ProviderPage(candidates = listOf(candidate), exhausted = true), filters, operation())
        assertTrue(page.results.isEmpty()); assertEquals(1, page.pendingCandidates.size)
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, page.pendingCandidates.single().sourceReported!!.metadataLevel)
    }
    @Test fun falseRawConfirmationRemainsRejected() {
        val page = ValidationPipeline(clock = FakeClock(), retainUnvalidatedCandidates = true)
            .validate("source", ProviderPage(results = listOf(RawVideoResult(candidate.pageUrl, false))), filters, operation())
        assertTrue(page.results.isEmpty()); assertTrue(page.pendingCandidates.isEmpty())
    }
    @Test fun invalidCandidateMetadataIsNotPromotedOrShown() {
        val invalid = candidate.copy(sourceReported = SourceReportedCandidateMetadata(title = "x".repeat(513)))
        assertNull(PendingCandidatePolicy.normalize(invalid, "source", filters))
        assertNull(PendingCandidatePolicy.normalize(candidate.copy(pageUrl = "javascript:alert(1)"), "source", filters))
    }
    @Test fun pendingDurationIsUnknownEvenWhenReported() {
        val reported = candidate.copy(sourceReported = SourceReportedCandidateMetadata(reportedDurationMs = 1000))
        assertNull(PendingCandidatePolicy.normalize(reported, "source", filters.copy(includeUnknownDuration = false)))
        assertNotNull(PendingCandidatePolicy.normalize(reported, "source", filters.copy(minimumDurationMs = 9000)))
    }
    @Test fun candidateFilteringUsesStableProviderId() {
        assertNotNull(PendingCandidatePolicy.normalize(candidate, "searxng-1", filters.copy(sourceNamespaces = setOf("searxng-1"))))
        assertNull(PendingCandidatePolicy.normalize(candidate, "searxng-1", filters.copy(sourceNamespaces = setOf("searxng-2"))))
    }
    @Test fun candidateMetadataIsDetachedAndDisplayControlCharactersRemoved() {
        val row = PendingCandidatePolicy.normalize(candidate.copy(sourceReported = SourceReportedCandidateMetadata(title = "a\u202eb\nc")), "source", filters)!!
        assertEquals("abc", row.sourceReported!!.title)
        assertFalse(row.toString().contains(candidate.pageUrl))
        assertFalse(row.sourceReported.toString().contains("abc"))
    }
    @Test fun poolDeduplicatesAndNeverEvictsIntoConfirmedResults() {
        val pool = PendingCandidatePool(1)
        val first = PendingCandidatePolicy.normalize(candidate, "source", filters)!!
        val duplicate = PendingCandidatePolicy.normalize(candidate, "second", filters)!!
        assertEquals(1, pool.add(listOf(first), emptySet()))
        assertEquals(0, pool.add(listOf(duplicate), emptySet()))
        assertEquals(setOf("source", "second"), pool.snapshot().single().provenance)
        val extra = PendingCandidatePolicy.normalize(candidate.copy(pageUrl = "https://video.example/next"), "source", filters)!!
        assertEquals(0, pool.add(listOf(extra), emptySet())); assertEquals(1, pool.size)
        pool.add(emptyList(), setOf(first.canonicalPageUrl)); assertEquals(0, pool.size)
    }
    @Test fun candidatesPreserveMetadataThroughLocalUrlDeduplication() {
        val pool = CandidateUrlPool(); assertTrue(pool.add(candidate)); assertFalse(pool.add(candidate))
        assertEquals(candidate, pool.take())
    }
    @Test fun realValidatorHasExclusiveConfirmationAuthority() {
        val pipeline = ValidationPipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true) }, clock = FakeClock(), retainUnvalidatedCandidates = true)
        val page = pipeline.validate("source", ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertEquals(1, page.results.size); assertTrue(page.pendingCandidates.isEmpty())
    }
    @Test fun validatorRejectionNeverFallsBackToSourceClaim() {
        val pipeline = ValidationPipeline(CandidateValidator { _, _ -> null }, clock = FakeClock(), retainUnvalidatedCandidates = true)
        val page = pipeline.validate("source", ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertTrue(page.results.isEmpty()); assertTrue(page.pendingCandidates.isEmpty())
    }
    @Test fun cancelledCandidatePageCannotPublish() {
        val op = operation(); op.cancellation.cancel()
        try { ValidationPipeline(clock = FakeClock(), retainUnvalidatedCandidates = true)
            .validate("source", ProviderPage(candidates = listOf(candidate)), filters, op); fail() }
        catch (expected: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, expected.code) }
    }
}
