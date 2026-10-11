package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class ExplicitCandidateVerificationTest {
    @Test fun confirmedMergedUrlAliasNeverSchedulesExplicitVerification() {
        val filters = SearchFilters(safeSearch = false)
        val pool = ResultPool()
        val results = listOf("https://video.example/retained", "https://video.example/watch?id=1").map {
            ResultNormalizer.normalize(RawVideoResult(it, true, sourceNamespace = "site", sourceVideoId = "same"), "source", filters)!!
        }
        val merged = pool.admit(PoolState(), results, 1000).state.entries.single().result
        val f = Fixture(initialResults = listOf(merged))
        assertTrue(f.coordinator.pendingCandidates().isEmpty())
        assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        assertEquals(1, f.runtime.calls.size)
    }
    private class Fixture(initialResults: List<VideoSearchResult> = emptyList(), initialSummary: VerificationSummary? = null, val store: DiscoveryStore = InMemoryDiscoveryStore()) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val registry = ProviderRegistry(listOf(StubProvider()))
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock)
        val profile = SearchProfile("profile", "query", filters = SearchFilters(safeSearch = false))
        val candidate = PendingCandidatePolicy.normalize(CandidateUrl("https://video.example/watch?id=1"), "source", profile.filters)!!
        val stamp: SessionStamp
        init {
            coordinator.setVisibility(true, true)
            stamp = coordinator.start("session", profile)
            runtime.calls[0].callback(ProviderOutcome.Success(ValidatedProviderPage(initialResults, null, null, true, listOf(candidate), initialSummary?.let { listOf(CandidateVerificationReport(candidate.candidateId, it)) } ?: emptyList())))
        }
    }
    @Test fun cancellationWhileCommitIsBlockedEndsCandidateWithoutPublicationOrRetry() {
        val backing = InMemoryDiscoveryStore()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val tx = this
                val wrapped = object : DiscoveryTransaction by tx {
                    override fun beforeCommit(check: () -> Unit) {
                        tx.beforeCommit {
                            if (armed.get()) {
                                entered.countDown()
                                require(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            check()
                        }
                    }
                }
                wrapped.block()
            }
        }
        val f = Fixture(store = store)
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        armed.set(true)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try { f.runtime.complete(1, 1) } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            f.coordinator.suspendImmediately()
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertNull(failure.get())
        armed.set(false)
        // acceptPage has removed active before the queued lifecycle cancellation is applied.
        assertFalse(f.coordinator.pendingCandidates().single().verificationActive)
        assertEquals(EngineOutcome.CANCELLED, f.coordinator.pendingCandidates().single().verification!!.outcome)
        assertNull(f.coordinator.verificationSummary())
        assertEquals(0, f.coordinator.status().availableCount)
        backing.transaction {
            assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
            assertNull(seen("profile", result(1).fingerprint))
        }
        f.coordinator.setVisibility(false, false)
        f.coordinator.setVisibility(true, true)
        assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        assertEquals(2, f.runtime.calls.size)
    }
    @Test fun queryChangeClearsSummaryAndOldWorkerCannotRestoreIt() {
        val summary = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
        val f = Fixture(initialSummary = summary)
        assertEquals(summary, f.coordinator.verificationSummary())
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        val old = f.runtime.calls[1]
        f.coordinator.invalidateVerificationSummary()
        assertNull(f.coordinator.verificationSummary())
        f.coordinator.start("session", f.profile.copy(query = "new"))
        old.callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true,
            listOf(f.candidate), listOf(CandidateVerificationReport(f.candidate.candidateId, summary)))))
        assertNull(f.coordinator.verificationSummary())
    }
    @Test fun sourceFenceImmediatelyClearsSummaryAndRejectsLateReport() {
        val summary = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
        val f = Fixture(initialSummary = summary)
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        f.coordinator.suspendSourcesImmediately()
        assertNull(f.coordinator.verificationSummary())
        f.runtime.calls[1].callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true,
            listOf(f.candidate), listOf(CandidateVerificationReport(f.candidate.candidateId, summary)))))
        assertNull(f.coordinator.verificationSummary())
    }
    @Test fun backgroundFenceClearsSummaryAndResumeDoesNotRestoreOldCallback() {
        val summary = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
        val f = Fixture(initialSummary = summary)
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        val old = f.runtime.calls[1]
        f.coordinator.suspendImmediately()
        assertNull(f.coordinator.verificationSummary())
        f.coordinator.setVisibility(false, false)
        f.coordinator.setVisibility(true, true)
        old.callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true,
            listOf(f.candidate), listOf(CandidateVerificationReport(f.candidate.candidateId, summary)))))
        assertNull(f.coordinator.verificationSummary())
    }
    @Test fun atomicInvalidationRejectsOldGenerationAndAllowsOnlyNewPublication() {
        val fence = VerificationSummaryFence()
        val summary = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
        val old = fence.generation
        assertTrue(fence.publish(old, summary))
        fence.invalidate()
        assertFalse(fence.publish(old, summary)); assertNull(fence.snapshot())
        assertTrue(fence.publish(fence.generation, summary))
        assertEquals(summary, fence.snapshot())
    }
    @Test fun repeatedClicksUseOneExistingRuntimeAttempt() {
        val f = Fixture()
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        assertEquals(2, f.runtime.calls.size)
        assertEquals(0, f.runtime.calls[1].operation.context.retryBudget)
        assertTrue(f.coordinator.pendingCandidates().single().verificationActive)
        f.runtime.complete(1, 1)
        assertTrue(f.coordinator.pendingCandidates().isEmpty())
        assertEquals(1, f.coordinator.status().availableCount)
        f.store.transaction {
            assertTrue(progress("profile", "source", f.profile.queryKey)!!.exhausted)
        }
    }
    @Test fun suspendedOrUnknownCandidateMakesNoRequestAndRetainsPending() {
        val f = Fixture()
        f.coordinator.setVisibility(false, false)
        assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        assertEquals(1, f.runtime.calls.size)
        assertEquals(1, f.coordinator.pendingCandidates().size)
    }
    @Test fun backgroundCancelsAndLateSuccessCannotPublish() {
        val f = Fixture(); assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        f.coordinator.suspendImmediately(); f.coordinator.setVisibility(false, false)
        assertTrue(f.runtime.calls[1].cancelled)
        f.runtime.complete(1, 1)
        assertEquals(0, f.coordinator.status().availableCount)
        assertEquals(EngineOutcome.CANCELLED, f.coordinator.pendingCandidates().single().verification!!.outcome)
    }
    @Test fun sourceAndSessionChangesRejectLateResults() {
        for (changeSession in listOf(false, true)) {
            val f = Fixture(); assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
            if (changeSession) f.coordinator.start("session", f.profile.copy(query = "new"))
            else f.coordinator.suspendSourcesImmediately()
            f.runtime.complete(1, 1)
            assertEquals(0, f.coordinator.status().availableCount)
            assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        }
    }
    @Test fun noRetryOnFailedExplicitAttemptAndCandidateRemains() {
        val f = Fixture(); assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        f.runtime.fail(1, true)
        f.timer.advance(3000)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.coordinator.pendingCandidates().size)
        assertFalse(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
    }
    @Test fun confirmationPreservesExistingPageAndSortContext() {
        val f = Fixture(listOf(result(2)))
        val original = f.coordinator.page(0)!!
        val order = f.coordinator.currentCheckpoint()!!.profile.sortOrder
        assertTrue(f.coordinator.verifyPending(f.stamp, f.candidate.candidateId))
        f.runtime.complete(1, 1)
        assertEquals(original, f.coordinator.page(0))
        assertEquals(order, f.coordinator.currentCheckpoint()!!.profile.sortOrder)
        assertEquals(2, f.coordinator.currentCheckpoint()!!.pool.entries.size)
    }
    @Test fun zeroValidationBudgetKeepsAllPendingWithoutCallingValidator() {
        var calls = 0
        val pipeline = ValidationPipeline(CandidateValidator { _, _ -> calls++; null }, clock = FakeClock(),
            retainFailedValidationCandidates = true, maxValidationsPerPage = 0)
        val page = pipeline.validate("source", ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch"))),
            SearchFilters(safeSearch = false), operation())
        assertEquals(0, calls); assertEquals(1, page.pendingCandidates.size); assertTrue(page.results.isEmpty())
    }
    @Test fun technicalEvidenceWithoutRawConfirmationNeverCreatesResult() {
        val validator = object : CandidateValidator {
            override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult? = null
            override fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation) =
                CandidateValidation(null, VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE))
        }
        val page = ValidationPipeline(validator, clock = FakeClock(), retainFailedValidationCandidates = true)
            .validate("source", ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch"))),
                SearchFilters(safeSearch = false), operation())
        assertTrue(page.results.isEmpty()); assertEquals(1, page.pendingCandidates.size)
        assertEquals(EngineEvidence.NONE, page.verificationReports.single().summary.evidence)
    }
}
