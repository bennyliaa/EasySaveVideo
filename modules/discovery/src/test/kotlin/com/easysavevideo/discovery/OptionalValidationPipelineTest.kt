package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class OptionalValidationPipelineTest {
    private val candidate = CandidateUrl("https://video.example/watch", SourceReportedCandidateMetadata(title = "Untrusted source title", reportedDurationMs = 5000))
    private val filters = SearchFilters(safeSearch = false)
    private fun pipeline(validator: CandidateValidator?, cap: Int = 1) = ValidationPipeline(validator,
        clock = FakeClock(), retainUnvalidatedCandidates = true, retainFailedValidationCandidates = true,
        maxValidationsPerPage = cap)
    @Test fun unavailableKeepsCandidateWithoutConfirmation() {
        val p = pipeline(CandidateValidator { _, _ -> null }).validate("source", ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertTrue(p.results.isEmpty()); assertEquals(1, p.pendingCandidates.size)
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, p.pendingCandidates.single().sourceReported!!.metadataLevel)
    }
    @Test fun exceptionIsIsolatedFromExistingResultsAndPendingPage() {
        val p = pipeline(CandidateValidator { _, _ -> throw IllegalStateException("INTERNAL") }).validate("source",
            ProviderPage(results = listOf(RawVideoResult("https://video.example/already", true)), candidates = listOf(candidate)), filters, operation())
        assertEquals(1, p.results.size); assertEquals(1, p.pendingCandidates.size)
    }
    @Test fun laterCandidatesStayPendingAfterOneAttempt() {
        var attempts = 0
        val candidates = (1..4).map { candidate.copy(pageUrl = "https://video.example/$it") }
        val p = pipeline(CandidateValidator { c, _ -> attempts++; RawVideoResult(c.pageUrl, true) }).validate("source",
            ProviderPage(candidates = candidates), filters, operation())
        assertEquals(1, attempts); assertEquals(1, p.results.size); assertEquals(3, p.pendingCandidates.size)
    }
    @Test fun failedAttemptConsumesBudget() {
        var attempts = 0
        val p = pipeline(CandidateValidator { _, _ -> attempts++; throw IllegalArgumentException() }).validate("source",
            ProviderPage(candidates = listOf(candidate, candidate.copy(pageUrl = "https://video.example/other"))), filters, operation())
        assertEquals(1, attempts); assertEquals(2, p.pendingCandidates.size)
    }
    @Test fun zeroBudgetNeverCallsValidator() {
        val p = pipeline(CandidateValidator { _, _ -> fail(); null }, 0).validate("source", ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertEquals(1, p.pendingCandidates.size)
    }
    @Test fun cancelledOperationNeverPublishesPending() {
        val op = operation()
        try { pipeline(CandidateValidator { _, _ -> op.cancellation.cancel(); null }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters, op); fail() }
        catch (e: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, e.code) }
    }
    @Test fun interruptedValidatorCannotBecomePending() {
        try {
            try { pipeline(CandidateValidator { _, _ -> throw InterruptedException() }).validate("source",
                ProviderPage(candidates = listOf(candidate)), filters, operation()); fail() }
            catch (e: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, e.code); assertTrue(Thread.currentThread().isInterrupted) }
        } finally { Thread.interrupted() }
    }
    @Test fun excludedProviderCannotUseVerificationToBypassFilter() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true) }).validate("searxng-1",
            ProviderPage(candidates = listOf(candidate)), filters.copy(sourceNamespaces = setOf("searxng-2")), operation())
        assertTrue(p.results.isEmpty()); assertTrue(p.pendingCandidates.isEmpty())
    }
    @Test fun staleGenerationCancellationCannotBecomePending() {
        try { pipeline(CandidateValidator { _, _ -> throw DiscoveryFailure(DiscoveryError.CANCELLED) }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters, operation()); fail() }
        catch (e: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, e.code) }
    }
    @Test fun sourceMetadataNeverFillsValidatedFields() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true) }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertNull(p.results.single().title); assertNull(p.results.single().durationMs)
    }
    @Test fun unconfirmedRawRemainsPending() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, false, title = "Claim") }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters, operation())
        assertTrue(p.results.isEmpty()); assertEquals(1, p.pendingCandidates.size)
    }
    @Test fun verifiedCandidateRespectsProviderNamespaceFilter() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true) }).validate("searxng-1",
            ProviderPage(candidates = listOf(candidate)), filters.copy(sourceNamespaces = setOf("searxng-1")), operation())
        assertEquals("searxng-1", p.results.single().sourceNamespace)
    }
    @Test fun unknownDurationRemainsExcludedWhenRequested() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true) }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters.copy(includeUnknownDuration = false), operation())
        assertTrue(p.results.isEmpty()); assertTrue(p.pendingCandidates.isEmpty())
    }
    @Test fun knownFilteredDurationNeverReappearsAsUnknownPending() {
        val p = pipeline(CandidateValidator { c, _ -> RawVideoResult(c.pageUrl, true, durationMs = 10_000) }).validate("source",
            ProviderPage(candidates = listOf(candidate)), filters.copy(minimumDurationMs = 20_000, includeUnknownDuration = true), operation())
        assertTrue(p.results.isEmpty()); assertTrue(p.pendingCandidates.isEmpty())
    }
    @Test fun disabledServiceDoesNotAffectExistingProviderResults() {
        val p = pipeline(null).validate("source", ProviderPage(results = listOf(RawVideoResult("https://video.example/direct", true))), filters, operation())
        assertEquals(1, p.results.size); assertTrue(p.pendingCandidates.isEmpty())
    }
}
