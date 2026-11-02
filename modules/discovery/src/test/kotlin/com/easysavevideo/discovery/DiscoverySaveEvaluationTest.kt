package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test
import com.easysavevideo.discovery.SaveEvaluationOutcome.*

class DiscoverySaveEvaluationTest {
    private fun row(id: String = "one", provider: String = "a", started: Boolean = true,
                    one: SaveEvaluationOutcome = NOT_TESTED, two: SaveEvaluationOutcome = NOT_TESTED,
                    three: SaveEvaluationOutcome = NOT_TESTED) = CandidateSaveEvaluation(
        PendingCandidatePolicy.normalize(CandidateUrl("https://example.org/$id"), provider, SearchFilters())!!,
        SaveEvaluationEvidence.SYNTHETIC_FIXTURE, started, one, two, three)
    private fun report(vararg rows: CandidateSaveEvaluation) = DiscoverySaveEvaluation.summarize(rows.toList())
    private fun rejected(vararg rows: CandidateSaveEvaluation) {
        try { report(*rows); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
    }
    @Test fun emptyRatesAreUnknown() {
        val r = report()
        assertNull(r.evidence); assertNull(r.overall.save1Share); assertNull(r.overall.save2Share)
        assertNull(r.overall.fallbackShare); assertNull(r.overall.save1.successRate)
        assertTrue(r.byProvider.isEmpty())
    }
    @Test fun commonDenominatorIncludesStartedPagesWithoutResults() {
        val r = report(row(one = SUCCEEDED, two = SUCCEEDED), row("two"), row("three", started = false)).overall
        assertEquals(3, r.uniquePages); assertEquals(2, r.actualTestPages); assertEquals(1, r.notStartedPages)
        assertEquals(0.5, r.save1Share!!, 0.0); assertEquals(0.5, r.save2Share!!, 0.0)
        assertEquals(1.0, r.save1.successRate!!, 0.0); assertEquals(1, r.save1.notTested)
        assertEquals(0, r.save1.failed); assertTrue(r.partial)
    }
    @Test fun primarySuccessesOverlapAndNeedNotSumToOne() {
        val r = report(row(one = SUCCEEDED, two = SUCCEEDED)).overall
        assertEquals(1.0, r.save1Share!!, 0.0); assertEquals(1.0, r.save2Share!!, 0.0)
        assertEquals(0.0, r.fallbackShare!!, 0.0); assertFalse(r.partial)
    }
    @Test fun fallbackMainShareUsesAllStartedPages() {
        val r = report(row(one = SUCCEEDED, two = SUCCEEDED), row("two", one = FAILED, two = FAILED, three = SUCCEEDED)).overall
        assertEquals(0.5, r.fallbackShare!!, 0.0); assertEquals(1, r.fallbackEligiblePages)
        assertEquals(1.0, r.save3.successRate!!, 0.0); assertEquals(1.0, r.fallbackRescueRate!!, 0.0)
        assertFalse(r.partial)
    }
    @Test fun incompleteFallbackStaysPartialWithoutInventingFailure() {
        val r = report(row(one = FAILED, two = FAILED), row("two", one = FAILED, two = FAILED, three = SUCCEEDED)).overall
        assertEquals(0.5, r.fallbackShare!!, 0.0); assertNull(r.fallbackRescueRate)
        assertEquals(0.5, r.fallbackTestCoverage!!, 0.0); assertEquals(1, r.save3.notTested)
        assertEquals(0, r.save3.failed); assertTrue(r.partial)
    }
    @Test fun blockedIsNeitherFailedNorCompleted() {
        val r = report(row(one = BLOCKED, two = FAILED)).overall
        assertEquals(1, r.save1.blocked); assertEquals(0, r.save1.tested); assertEquals(0, r.save1.failed)
        assertEquals(0, r.fallbackEligiblePages); assertNull(r.save1.successRate); assertTrue(r.partial)
    }
    @Test fun fallbackRefusesUnknownBlockedOrSuccessfulPrimaries() {
        for (outcome in listOf(NOT_TESTED, BLOCKED, SUCCEEDED, SKIPPED)) {
            rejected(row(one = outcome, two = FAILED, three = SUCCEEDED))
            rejected(row(one = FAILED, two = outcome, three = FAILED))
            rejected(row(one = FAILED, two = outcome, three = BLOCKED))
        }
    }
    @Test fun outcomeRequiresExplicitStart() { rejected(row(started = false, one = SUCCEEDED)) }
    @Test fun candidateOnlyIsNotATest() {
        val r = report(row(started = false)).overall
        assertEquals(0, r.actualTestPages); assertEquals(1, r.notStartedPages)
        assertNull(r.save1Share); assertNull(r.fallbackShare); assertTrue(r.partial)
    }
    @Test fun duplicatesCountOnceOverallAndOnceForEveryProvider() {
        val r = report(row(one = SUCCEEDED), row(provider = "b", two = SUCCEEDED), row(one = SUCCEEDED),
            row("two", provider = "b", one = FAILED, two = FAILED, three = FAILED))
        assertEquals(2, r.overall.actualTestPages); assertEquals(setOf("a", "b"), r.byProvider.keys)
        assertEquals(1, r.byProvider.getValue("a").actualTestPages)
        assertEquals(2, r.byProvider.getValue("b").actualTestPages)
        assertEquals(1.0, r.byProvider.getValue("a").save2Share!!, 0.0)
        assertEquals(0.5, r.byProvider.getValue("b").save1Share!!, 0.0)
    }
    @Test fun methodObservationsMergeBeforeFallbackEligibilityValidation() {
        val r = report(row(one = FAILED), row(provider = "b", two = FAILED, three = SUCCEEDED))
        assertEquals(1, r.overall.save3.succeeded)
    }
    @Test fun conflictCannotSelectSuccessAndHideFailure() { rejected(row(one = SUCCEEDED), row(one = FAILED)) }
    @Test fun blockedCannotBeSilentlyUpgradedAcrossAttempts() { rejected(row(one = BLOCKED), row(one = SUCCEEDED)) }
    @Test fun evidenceKindsNeverMix() {
        rejected(row(), row("two").copy(evidence = SaveEvaluationEvidence.OBSERVED_SESSION))
    }
    @Test fun observedLabelDoesNotCreateOutcomes() {
        val r = report(row(started = false).copy(evidence = SaveEvaluationEvidence.OBSERVED_SESSION))
        assertEquals(SaveEvaluationEvidence.OBSERVED_SESSION, r.evidence); assertNull(r.overall.save1Share)
    }
    @Test fun forgedIdentityIsRejected() {
        val r = row(); rejected(r.copy(candidate = r.candidate.copy(candidateId = "forged")))
        rejected(r.copy(candidate = r.candidate.copy(canonicalPageUrl = "https://example.org/other")))
        rejected(r.copy(candidate = r.candidate.copy(provenance = emptySet())))
    }
    @Test fun sourceBoundsAreNotSilentlyTruncated() {
        val r = row(); rejected(r.copy(candidate = r.candidate.copy(provenance = (1..33).map { "p$it" }.toSet())))
        rejected(r.copy(candidate = r.candidate.copy(provenance = (1..32).map { "p$it" }.toSet())), row(provider = "extra"))
    }
    @Test fun inputBoundIsEnforced() {
        try { DiscoverySaveEvaluation.summarize(List(501) { row() }); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun diagnosticsDoNotExposePageMetadata() {
        val r = row(); assertFalse(r.toString().contains("example.org"))
        assertFalse(report(r).toString().contains("example.org"))
    }
    @Test fun orderingDoesNotChangeCountsOrProvenance() {
        val rows = listOf(row(one = SUCCEEDED), row(provider = "b", two = FAILED), row("two"))
        assertEquals(DiscoverySaveEvaluation.summarize(rows), DiscoverySaveEvaluation.summarize(rows.reversed()))
    }
    @Test fun actualPendingPoolAggregationPreservesFailureAndDoesNotInventSaveTests() {
        val pool = PendingCandidatePool(5)
        val first = row().candidate
        pool.add(listOf(first), emptySet())
        val failure = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
        pool.mark(first.candidateId, false, failure)
        pool.add(listOf(row(provider = "b").candidate, row("two").candidate), emptySet())
        val candidates = pool.snapshot()
        assertEquals(2, candidates.size)
        assertEquals(setOf("a", "b"), candidates.first().provenance)
        assertEquals(failure, candidates.first().verification)
        assertNull(candidates.last().verification)
        val evaluation = DiscoverySaveEvaluation.summarize(candidates.map {
            CandidateSaveEvaluation(it, SaveEvaluationEvidence.SYNTHETIC_FIXTURE)
        })
        assertEquals(2, evaluation.overall.notStartedPages)
        assertEquals(0, evaluation.overall.actualTestPages)
        assertNull(evaluation.overall.save1Share)
    }

    @Test fun requestedCommonDenominatorExampleAllows53And65And20Percent() {
        val rows = (0 until 100).map { i ->
            row("page-$i", one = if (i < 53) SUCCEEDED else FAILED,
                two = if (i < 65) SUCCEEDED else FAILED,
                three = if (i < 65) NOT_TESTED else if (i < 85) SUCCEEDED else FAILED)
        }
        val r = DiscoverySaveEvaluation.summarize(rows)
        assertEquals(100, r.overall.actualTestPages)
        assertEquals(0.53, r.overall.save1Share!!, 0.0)
        assertEquals(0.65, r.overall.save2Share!!, 0.0)
        assertEquals(0.20, r.overall.fallbackShare!!, 0.0)
        assertEquals(r.overall, r.byProvider.getValue("a"))
        assertFalse(r.overall.partial)
    }

    @Test fun normalizedHostAndDefaultPortDeduplicateButPageIdentityIsConservative() {
        fun observation(url: String) = CandidateSaveEvaluation(
            PendingCandidatePolicy.normalize(CandidateUrl(url), "a", SearchFilters())!!,
            SaveEvaluationEvidence.SYNTHETIC_FIXTURE, testStarted = true)
        val rows = listOf("https://example.org/watch?q=1#first", "HTTPS://EXAMPLE.ORG:443/watch?q=1#first",
            "https://example.org/watch?q=2#first", "https://example.org/watch?q=1#second",
            "https://example.org/Watch?q=1#first").map(::observation)
        val r = DiscoverySaveEvaluation.summarize(rows)
        assertEquals(4, r.overall.uniquePages)
        assertEquals(4, r.overall.actualTestPages)
    }

    @Test fun legacyCallsRemainFallbackByDefault() {
        val r = report(row(one = FAILED, two = FAILED, three = SUCCEEDED))
        assertEquals(SaveEvaluationMode.FALLBACK, r.mode)
        assertEquals(SaveEvaluationMode.FALLBACK, r.overall.mode)
        assertEquals(r.overall.save3, r.overall.fallbackSave3)
        assertNull(r.overall.jointRecordShare); assertNull(r.overall.independentRecordShare)
        assertEquals(0, r.overall.independentRecord.tested)
    }

    @Test fun independentRecordingNeverCountsSkippedPrimariesAsFailuresOrFallback() {
        val rows = listOf(row(one = SKIPPED, two = SKIPPED, three = SUCCEEDED),
            row("failed", one = SKIPPED, two = SKIPPED, three = FAILED),
            row("blocked", one = SKIPPED, two = SKIPPED, three = BLOCKED),
            row("unfinished", one = SKIPPED, two = SKIPPED), row("unstarted", started = false))
        val report = DiscoverySaveEvaluation.summarize(rows, SaveEvaluationMode.INDEPENDENT_RECORD)
        val r = report.overall
        assertEquals(SaveEvaluationMode.INDEPENDENT_RECORD, report.mode)
        assertEquals(5, r.uniquePages); assertEquals(4, r.actualTestPages); assertEquals(1, r.notStartedPages)
        assertEquals(SaveMethodEvaluationCounts(0, 0, 0, 0, skipped = 4), r.save1)
        assertEquals(r.save1, r.save2)
        assertEquals(SaveMethodEvaluationCounts(1, 1, 1, 1), r.independentRecord)
        assertEquals(0.25, r.independentRecordShare!!, 0.0)
        assertEquals(0.5, r.independentRecordTestCoverage!!, 0.0)
        assertEquals(0.5, r.independentRecord.successRate!!, 0.0)
        assertEquals(0, r.fallbackEligiblePages); assertEquals(0, r.fallbackSave3.tested)
        assertEquals(0, r.save3.succeeded); assertEquals(0, r.save3.failed)
        assertNull(r.save1Share); assertNull(r.save2Share); assertNull(r.fallbackShare)
        assertNull(r.fallbackTestCoverage); assertNull(r.fallbackRescueRate); assertNull(r.jointRecordShare)
        assertTrue(r.partial); assertEquals(r, report.byProvider.getValue("a"))
    }

    @Test fun independentCompletedRunIsNotPartialMerelyBecausePrimariesWereSkipped() {
        val r = DiscoverySaveEvaluation.summarize(listOf(
            row(one = SKIPPED, two = SKIPPED, three = SUCCEEDED)), SaveEvaluationMode.INDEPENDENT_RECORD).overall
        assertFalse(r.partial); assertEquals(1.0, r.independentRecordShare!!, 0.0)
        assertEquals(1.0, r.independentRecordTestCoverage!!, 0.0)
    }

    @Test fun independentModeRequiresExplicitSkipsAndRejectsRelabelingPrimaryResults() {
        for (one in SaveEvaluationOutcome.entries) for (two in SaveEvaluationOutcome.entries) {
            if (one == SKIPPED && two == SKIPPED) continue
            try {
                DiscoverySaveEvaluation.summarize(listOf(row(one = one, two = two, three = SUCCEEDED)),
                    SaveEvaluationMode.INDEPENDENT_RECORD)
                fail("Expected independent primary refusal")
            } catch (_: IllegalArgumentException) { }
        }
        rejected(row(started = false, one = SKIPPED, two = SKIPPED))
    }

    @Test fun independentDuplicatesPreserveAllSourcesAndNeverSelectFavorableConflicts() {
        val r = row(one = SKIPPED, two = SKIPPED, three = SUCCEEDED)
        val duplicate = row(provider = "b", one = SKIPPED, two = SKIPPED)
        val report = DiscoverySaveEvaluation.summarize(listOf(r, duplicate, r), SaveEvaluationMode.INDEPENDENT_RECORD)
        assertEquals(1, report.overall.actualTestPages); assertEquals(1, report.overall.independentRecord.succeeded)
        assertEquals(setOf("a", "b"), report.byProvider.keys)
        assertTrue(report.byProvider.values.all { it.independentRecord.succeeded == 1 })
        try {
            DiscoverySaveEvaluation.summarize(listOf(r, r.copy(save3 = FAILED)), SaveEvaluationMode.INDEPENDENT_RECORD)
            fail("Expected conflict")
        } catch (_: IllegalArgumentException) { }
        try {
            DiscoverySaveEvaluation.summarize(listOf(r, duplicate.copy(evidence = SaveEvaluationEvidence.OBSERVED_SESSION)),
                SaveEvaluationMode.INDEPENDENT_RECORD)
            fail("Expected mixed evidence refusal")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun jointRecordingMaySucceedBeforeOrWithoutPrimaryResults() {
        for (primary in SaveEvaluationOutcome.entries) {
            val r = DiscoverySaveEvaluation.summarize(listOf(row(one = primary, two = SUCCEEDED, three = SUCCEEDED)),
                SaveEvaluationMode.JOINT).overall
            assertEquals(1, r.save3.succeeded); assertEquals(1.0, r.jointRecordShare!!, 0.0)
            assertEquals(0.0, r.fallbackShare!!, 0.0); assertEquals(0, r.fallbackSave3.succeeded)
            assertEquals(0, r.fallbackEligiblePages); assertNull(r.independentRecordShare)
            assertEquals(primary != SUCCEEDED && primary != FAILED, r.partial)
        }
    }

    @Test fun jointOutcomeMatrixDerivesOnlyThreeSuccessStrictlyFromBothPrimaryFailures() {
        var index = 0
        val rows = SaveEvaluationOutcome.entries.flatMap { one -> SaveEvaluationOutcome.entries.flatMap { two ->
            SaveEvaluationOutcome.entries.map { three -> row("matrix-${index++}", one = one, two = two, three = three) }
        } }
        val r = DiscoverySaveEvaluation.summarize(rows, SaveEvaluationMode.JOINT).overall
        assertEquals(125, r.actualTestPages); assertEquals(25, r.save3.succeeded)
        assertEquals(0.2, r.jointRecordShare!!, 0.0); assertEquals(0.4, r.jointRecordTestCoverage!!, 0.0)
        assertEquals(5, r.fallbackEligiblePages); assertEquals(1, r.fallbackSave3.succeeded)
        assertEquals(1.0 / 125, r.fallbackShare!!, 0.0); assertEquals(0.4, r.fallbackTestCoverage!!, 0.0)
        assertNull(r.fallbackRescueRate); assertEquals(0, r.independentRecord.tested); assertTrue(r.partial)
    }

    @Test fun jointAllMethodsMustFinishAndSharedDenominatorIncludesUnfinishedPages() {
        val r = DiscoverySaveEvaluation.summarize(listOf(row(one = FAILED, two = FAILED, three = SUCCEEDED),
            row("overlap", one = SUCCEEDED, two = SUCCEEDED, three = SUCCEEDED), row("unfinished")), SaveEvaluationMode.JOINT).overall
        assertEquals(1.0 / 3, r.save1Share!!, 0.0); assertEquals(1.0 / 3, r.save2Share!!, 0.0)
        assertEquals(2.0 / 3, r.jointRecordShare!!, 0.0); assertEquals(1.0 / 3, r.fallbackShare!!, 0.0)
        assertEquals(1.0, r.fallbackRescueRate!!, 0.0); assertTrue(r.partial)
        val primariesOnly = DiscoverySaveEvaluation.summarize(listOf(row(one = SUCCEEDED, two = SUCCEEDED)), SaveEvaluationMode.JOINT)
        assertTrue(primariesOnly.overall.partial); assertEquals(1, primariesOnly.overall.save3.notTested)
    }

    @Test fun emptyAndUnstartedRunsHaveUnknownModeSpecificRates() {
        for (mode in SaveEvaluationMode.entries) {
            val empty = DiscoverySaveEvaluation.summarize(emptyList(), mode)
            assertEquals(mode, empty.mode); assertNull(empty.overall.fallbackShare)
            assertNull(empty.overall.jointRecordShare); assertNull(empty.overall.independentRecordShare)
            assertNull(empty.overall.jointRecordTestCoverage); assertNull(empty.overall.independentRecordTestCoverage)
            val unstarted = DiscoverySaveEvaluation.summarize(listOf(row(started = false)), mode).overall
            assertEquals(0, unstarted.actualTestPages); assertTrue(unstarted.partial)
            assertEquals(0, unstarted.save1.skipped); assertEquals(0, unstarted.independentRecord.notTested)
        }
    }

}
