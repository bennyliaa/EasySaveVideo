package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test
import com.easysavevideo.discovery.SaveEvaluationOutcome.*
import com.easysavevideo.discovery.SaveEvaluationMethod.*

class DiscoverySaveEvaluationSessionTest {
    private fun candidate(url: String = "https://example.org/private?query=secret", provider: String = "private-source") =
        PendingCandidatePolicy.normalize(CandidateUrl(url, SourceReportedCandidateMetadata(title = "private title")), provider, SearchFilters())!!
    private fun session(evidence: SaveEvaluationEvidence = SaveEvaluationEvidence.SYNTHETIC_FIXTURE,
        mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK) =
        DiscoverySaveEvaluationSession(evidence, mode).also { it.begin() }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
    }
    private fun complete(s: DiscoverySaveEvaluationSession, id: String, m: SaveEvaluationMethod, o: SaveEvaluationOutcome) =
        s.finishMethod(s.beginMethod(id, m), o)
    @Test fun lifecycleRequiresExplicitBeginAndKnownPage() {
        val s = DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE)
        rejected { s.snapshot() }; rejected { s.register(candidate()) }; rejected { s.beginPage("missing") }
        rejected { s.finish() }; s.begin(); rejected { s.begin() }; rejected { s.beginPage("missing") }
    }
    @Test fun registeringNeverStartsPageOrInfersResultsFromCandidate() {
        val s = session(); val c = candidate(); s.register(c)
        val r = s.snapshot().evaluation.overall
        assertEquals(1, r.notStartedPages); assertEquals(0, r.actualTestPages); assertNull(r.save1Share)
        rejected { s.beginMethod(c.candidateId, SAVE1) }
    }
    @Test fun startedPageWithoutMethodResultRemainsInCommonDenominator() {
        val s = session(); val c = candidate(); s.register(c); s.beginPage(c.candidateId)
        rejected { s.beginPage(c.candidateId) }
        val r = s.finish().evaluation.overall
        assertEquals(1, r.actualTestPages); assertTrue(r.partial); assertEquals(0, r.save1.failed)
        assertEquals(1, r.save1.notTested)
    }
    @Test fun bothPrimarySuccessesOverlap() {
        val s = session(); val c = candidate(); s.register(c); s.beginPage(c.candidateId)
        complete(s,c.candidateId,SAVE1,SUCCEEDED); complete(s,c.candidateId,SAVE2,SUCCEEDED)
        val r = s.finish().evaluation.overall
        assertEquals(1.0,r.save1Share!!,0.0); assertEquals(1.0,r.save2Share!!,0.0); assertFalse(r.partial)
    }
    @Test fun fallbackCannotStartBeforeBothFailuresComplete() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        rejected { s.beginMethod(c.candidateId,SAVE3) }
        val a=s.beginMethod(c.candidateId,SAVE1); val b=s.beginMethod(c.candidateId,SAVE2)
        rejected { s.beginMethod(c.candidateId,SAVE3) }; s.finishMethod(a,FAILED)
        rejected { s.beginMethod(c.candidateId,SAVE3) }; s.finishMethod(b,FAILED)
        complete(s,c.candidateId,SAVE3,SUCCEEDED)
        assertEquals(1.0,s.finish().evaluation.overall.fallbackShare!!,0.0)
    }
    @Test fun blockedOrSuccessfulPrimaryNeverAllowsFallback() {
        for (outcome in listOf(BLOCKED,SUCCEEDED)) {
            val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
            complete(s,c.candidateId,SAVE1,FAILED); complete(s,c.candidateId,SAVE2,outcome)
            rejected { s.beginMethod(c.candidateId,SAVE3) }
        }
    }
    @Test fun onlyExactIssuedAttemptCanFinishAndOnlyOnce() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        val a=s.beginMethod(c.candidateId,SAVE1)
        rejected { s.finishMethod(SaveEvaluationAttempt(Any(),c.candidateId,SAVE1),SUCCEEDED) }
        rejected { s.finishMethod(SaveEvaluationAttempt(a.owner,a.pageId,a.method),SUCCEEDED) }
        rejected { s.finishMethod(a,NOT_TESTED) }; s.finishMethod(a,FAILED)
        rejected { s.finishMethod(a,SUCCEEDED) }; rejected { s.beginMethod(c.candidateId,SAVE1) }
        assertEquals(1,s.snapshot().evaluation.overall.save1.failed)
    }
    @Test fun concurrentDuplicateBeginIsRejected() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        s.beginMethod(c.candidateId,SAVE1); rejected { s.beginMethod(c.candidateId,SAVE1) }
    }
    @Test fun ticketsCannotCrossSessionBoundaryEvenForSamePage() {
        val a=session(); val b=session(); val c=candidate()
        listOf(a,b).forEach { it.register(c); it.beginPage(c.candidateId) }
        val x=a.beginMethod(c.candidateId,SAVE1); val y=b.beginMethod(c.candidateId,SAVE1)
        rejected { b.finishMethod(x,SUCCEEDED) }; b.finishMethod(y,FAILED); a.finishMethod(x,SUCCEEDED)
    }
    @Test fun finishPreservesInFlightUnknownAndFencesEveryLaterEvent() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        val a=s.beginMethod(c.candidateId,SAVE1); val r=s.finish()
        assertEquals(1,r.unfinishedMethodAttempts); assertTrue(r.evaluation.overall.partial)
        assertEquals(1,r.evaluation.overall.save1.notTested); assertEquals(0,r.evaluation.overall.save1.failed)
        rejected { s.finishMethod(a,SUCCEEDED) }; rejected { s.register(c) }
        rejected { s.beginPage(c.candidateId) }; rejected { s.beginMethod(c.candidateId,SAVE2) }; rejected { s.begin() }
        assertEquals(r,s.snapshot()); assertEquals(r,s.finish())
    }
    @Test fun duplicateProvidersRetainCompletedResultsWithoutDoubleCounting() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        complete(s,c.candidateId,SAVE1,SUCCEEDED)
        s.register(candidate(provider="another-source")); s.register(c)
        val r=s.snapshot().evaluation
        assertEquals(1,r.overall.actualTestPages); assertEquals(2,r.byProvider.size)
        assertTrue(r.byProvider.values.all { it.save1.succeeded==1 && it.actualTestPages==1 })
    }
    @Test fun invalidCandidateDoesNotAlterRun() {
        val s=session(); val c=candidate()
        rejected { s.register(c.copy(candidateId="bad")) }
        assertEquals(0,s.snapshot().evaluation.overall.uniquePages)
    }
    @Test fun providerOverflowRejectsWithoutLosingPriorSources() {
        val s=session(); val c=candidate().copy(provenance=(1..32).map { "p$it" }.toSet()); s.register(c)
        rejected { s.register(candidate(provider="extra")) }
        assertEquals(32,s.snapshot().evaluation.byProvider.size)
    }
    @Test fun pageCapacityRefusesNewPageButAllowsDuplicate() {
        val s=session()
        (0 until 500).forEach { s.register(candidate("https://example.org/$it")) }
        rejected { s.register(candidate("https://example.org/overflow")) }
        s.register(candidate("https://example.org/0", "other"))
        assertEquals(500,s.snapshot().evaluation.overall.uniquePages)
    }
    @Test fun emptyObservedRunRetainsEvidenceLabelWithoutInventingSuccess() {
        val r=session(SaveEvaluationEvidence.OBSERVED_SESSION).finish()
        assertEquals(SaveEvaluationEvidence.OBSERVED_SESSION,r.evaluation.evidence)
        assertNull(r.evaluation.overall.save1Share)
    }
    @Test fun exportIsDeterministicAsciiAndContainsNoPrivateStrings() {
        val s=session(); s.register(candidate()); val r=s.finish()
        val json=DiscoverySaveEvaluationJson.export(r)
        assertEquals(json,DiscoverySaveEvaluationJson.export(r)); assertTrue(json.all { it.code<128 })
        listOf("example.org","query=secret","private-source","private title",candidate().candidateId).forEach {
            assertFalse(json.contains(it))
        }
        assertTrue(json.contains("\"schemaVersion\":2")); assertTrue(json.contains("\"evidence\":\"SYNTHETIC_FIXTURE\""))
        assertTrue(json.contains("\"save1Share\":null")); assertTrue(json.contains("\"partial\":true"))
        assertFalse(json.contains("NaN")); assertFalse(json.contains("Infinity"))
    }
    @Test fun localMappingMatchesThisReportAndRedactsDiagnostics() {
        val s=session(); s.register(candidate(provider="z-source")); s.register(candidate(provider="a-source"))
        val r=s.finish(); val mapping=DiscoverySaveEvaluationJson.localProviderMapping(r)
        assertEquals("a-source",mapping.sourceIdFor("source-1")); assertEquals("z-source",mapping.sourceIdFor("source-2"))
        assertNull(mapping.sourceIdFor("missing")); assertFalse(mapping.toString().contains("a-source"))
        assertTrue(DiscoverySaveEvaluationJson.export(r).contains("\"provider\":\"source-2\""))
    }
    @Test fun exporterHasHardByteBoundAndDoesNotReturnTruncatedJson() {
        val base=session().finish()
        fun report(n: Int) = base.copy(evaluation=base.evaluation.copy(byProvider=
            (0 until n).associate { "private-$it" to base.evaluation.overall }))
        var low=0; var high=4000
        rejected { DiscoverySaveEvaluationJson.export(report(high)) }
        while (low + 1 < high) {
            val middle=(low+high)/2
            try { DiscoverySaveEvaluationJson.export(report(middle)); low=middle }
            catch (_: IllegalArgumentException) { high=middle }
        }
        val json=DiscoverySaveEvaluationJson.export(report(low))
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES)
        val nextEntryBytes = DiscoverySaveEvaluationJson.export(report(1)).length - DiscoverySaveEvaluationJson.export(report(0)).length + 1
        assertTrue(DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES-json.length < nextEntryBytes + 4)
        assertTrue(json.endsWith("]}")); rejected { DiscoverySaveEvaluationJson.export(report(low+1)) }
        val file=java.io.File("build/evaluation-export-boundary-fixture.json")
        file.parentFile.mkdirs(); file.writeText(json,Charsets.UTF_8)
    }
    @Test fun outputUsesStableDecimalSyntaxUnderOtherDefaultLocale() {
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMAN)
            val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
            complete(s,c.candidateId,SAVE1,SUCCEEDED)
            assertTrue(DiscoverySaveEvaluationJson.export(s.finish()).contains("\"save1Share\":1.0"))
        } finally { java.util.Locale.setDefault(previous) }
    }
    @Test fun emitExplicitlySyntheticJsonFixtureForIndependentParser() {
        val s=session(); val c=candidate(); s.register(c); s.beginPage(c.candidateId)
        complete(s,c.candidateId,SAVE1,FAILED); complete(s,c.candidateId,SAVE2,FAILED)
        complete(s,c.candidateId,SAVE3,SUCCEEDED)
        val json=DiscoverySaveEvaluationJson.export(s.finish())
        val file=java.io.File("build/evaluation-export-fixture.json")
        file.parentFile.mkdirs(); file.writeText(json, Charsets.UTF_8)
        assertTrue(file.length() <= DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES)
    }

    @Test fun sessionRetainsOnlyIdentityAndSourcesThenClearsThemAtFinish() {
        val s=session(); val c=candidate().copy(sourceReported=SourceReportedCandidateMetadata(title="secret".repeat(100000)))
        s.register(c); s.beginPage(c.candidateId); s.beginMethod(c.candidateId,SAVE1)
        fun map(name: String): Map<*, *> {
            val field=s.javaClass.getDeclaredField(name); field.isAccessible=true
            return field.get(s) as Map<*, *>
        }
        val rows=map("pages")
        assertEquals(1,rows.size)
        assertTrue(rows.values.all { it is SaveEvaluationObservation })
        assertFalse(rows.toString().contains("example.org")); assertFalse(rows.toString().contains("secret"))
        val finished=s.finish()
        assertTrue(map("pages").isEmpty()); assertTrue(map("attempts").isEmpty())
        assertEquals(1,finished.unfinishedMethodAttempts)
        assertEquals("private-source",DiscoverySaveEvaluationJson.localProviderMapping(finished).sourceIdFor("source-1"))
    }
    @Test fun sealedProviderMapCannotBeChangedThroughJavaOrMutableCast() {
        val s=session(); s.register(candidate()); val report=s.finish()
        try { (report.evaluation.byProvider as MutableMap<String,SaveEvaluationCounts>).clear(); fail() }
        catch (_: UnsupportedOperationException) { }
        assertEquals(1,s.snapshot().evaluation.byProvider.size)
    }

    @Test fun independentStartSkipsPrimariesAndAcceptsOnlyRecordingAttempts() {
        val s = session(mode = SaveEvaluationMode.INDEPENDENT_RECORD); val c = candidate(); s.register(c)
        assertEquals(0, s.snapshot().evaluation.overall.save1.skipped)
        rejected { s.beginMethod(c.candidateId, SAVE3) }; s.beginPage(c.candidateId)
        rejected { s.beginMethod(c.candidateId, SAVE1) }; rejected { s.beginMethod(c.candidateId, SAVE2) }
        val started = s.snapshot().evaluation.overall
        assertEquals(1, started.save1.skipped); assertEquals(1, started.save2.skipped)
        assertEquals(0, started.save1.failed); assertEquals(0, started.save2.tested)
        assertEquals(1, started.independentRecord.notTested); assertTrue(started.partial)
        val attempt = s.beginMethod(c.candidateId, SAVE3)
        rejected { s.finishMethod(attempt, SKIPPED) }; rejected { s.finishMethod(attempt, NOT_TESTED) }
        s.finishMethod(attempt, SUCCEEDED)
        val sealed = s.finish()
        assertEquals(SaveEvaluationMode.INDEPENDENT_RECORD, s.mode); assertEquals(s.mode, sealed.mode)
        assertEquals(1, sealed.evaluation.overall.independentRecord.succeeded)
        assertFalse(sealed.evaluation.overall.partial); assertEquals(0, sealed.evaluation.overall.save3.succeeded)
        assertNull(sealed.evaluation.overall.fallbackShare)
        assertSame(sealed, s.finish()); assertSame(sealed, s.snapshot())
        rejected { s.finishMethod(attempt, FAILED) }; rejected { s.beginMethod(c.candidateId, SAVE3) }
    }

    @Test fun independentUnfinishedRecordingStaysPartialWithSkipsPreserved() {
        val s = session(mode = SaveEvaluationMode.INDEPENDENT_RECORD); val c = candidate()
        s.register(c); s.beginPage(c.candidateId); s.beginMethod(c.candidateId, SAVE3)
        s.register(candidate(provider = "another-source"))
        val r = s.finish()
        assertEquals(1, r.unfinishedMethodAttempts); assertEquals(1, r.evaluation.overall.actualTestPages)
        assertEquals(1, r.evaluation.overall.independentRecord.notTested)
        assertEquals(0, r.evaluation.overall.independentRecord.failed); assertTrue(r.evaluation.overall.partial)
        assertTrue(r.evaluation.byProvider.values.all { it.save1.skipped == 1 && it.independentRecord.notTested == 1 })
    }

    @Test fun jointRecordingCanFinishFirstAndFallbackSubsetIsDerivedFromLaterPrimaryFailures() {
        val s = session(mode = SaveEvaluationMode.JOINT); val c = candidate(); s.register(c); s.beginPage(c.candidateId)
        complete(s, c.candidateId, SAVE3, SUCCEEDED)
        val early = s.snapshot()
        assertEquals(SaveEvaluationMode.JOINT, early.mode)
        assertEquals(1.0, early.evaluation.overall.jointRecordShare!!, 0.0)
        assertEquals(0.0, early.evaluation.overall.fallbackShare!!, 0.0); assertTrue(early.evaluation.overall.partial)
        complete(s, c.candidateId, SAVE1, FAILED)
        assertEquals(0, s.snapshot().evaluation.overall.fallbackEligiblePages)
        complete(s, c.candidateId, SAVE2, FAILED)
        val r = s.finish()
        assertEquals(1, r.evaluation.overall.save3.succeeded); assertEquals(1, r.evaluation.overall.fallbackSave3.succeeded)
        assertEquals(1.0, r.evaluation.overall.fallbackShare!!, 0.0); assertFalse(r.evaluation.overall.partial)
        assertEquals(0, early.evaluation.overall.fallbackEligiblePages)
        assertEquals(0, early.evaluation.overall.save1.failed) // Prior snapshots remain immutable.
    }

    @Test fun jointRecordingWorksAfterSuccessfulOrBlockedPrimaryWithoutBeingFallback() {
        for (outcome in listOf(SUCCEEDED, BLOCKED)) {
            val s = session(mode = SaveEvaluationMode.JOINT); val c = candidate(); s.register(c); s.beginPage(c.candidateId)
            complete(s, c.candidateId, SAVE1, outcome); complete(s, c.candidateId, SAVE2, FAILED)
            complete(s, c.candidateId, SAVE3, SUCCEEDED)
            val r = s.finish().evaluation.overall
            assertEquals(1, r.save3.succeeded); assertEquals(0, r.fallbackSave3.succeeded)
            assertEquals(1.0, r.jointRecordShare!!, 0.0); assertEquals(0.0, r.fallbackShare!!, 0.0)
        }
    }

    @Test fun crossModeTicketsCannotCompleteRecordingAndSkippedIsNeverAttemptTerminal() {
        val independent = session(mode = SaveEvaluationMode.INDEPENDENT_RECORD)
        val joint = session(mode = SaveEvaluationMode.JOINT); val c = candidate()
        listOf(independent, joint).forEach { it.register(c); it.beginPage(c.candidateId) }
        val a = independent.beginMethod(c.candidateId, SAVE3); val b = joint.beginMethod(c.candidateId, SAVE3)
        rejected { joint.finishMethod(a, SUCCEEDED) }; rejected { independent.finishMethod(b, SUCCEEDED) }
        rejected { joint.finishMethod(b, SKIPPED) }; rejected { independent.finishMethod(a, SKIPPED) }
        independent.finishMethod(a, FAILED); joint.finishMethod(b, SUCCEEDED)
        assertEquals(1, independent.finish().evaluation.overall.independentRecord.failed)
        assertEquals(1, joint.finish().evaluation.overall.save3.succeeded)
    }

    @Test fun everyModeExportsVersionedRedactedSeparatedCountsAndExplicitEvidence() {
        for (mode in SaveEvaluationMode.entries) for (evidence in SaveEvaluationEvidence.entries) {
            val s = session(evidence, mode); val c = candidate(); s.register(c); s.beginPage(c.candidateId)
            if (mode == SaveEvaluationMode.FALLBACK) {
                complete(s, c.candidateId, SAVE1, FAILED); complete(s, c.candidateId, SAVE2, FAILED)
            }
            complete(s, c.candidateId, SAVE3, SUCCEEDED)
            val report = s.finish(); val json = DiscoverySaveEvaluationJson.export(report)
            assertTrue(json.contains("\"schemaVersion\":2")); assertTrue(json.contains("\"mode\":\"${mode.name}\""))
            assertTrue(json.contains("\"evidence\":\"${evidence.name}\""))
            assertTrue(json.contains("\"fallbackSave3\":")); assertTrue(json.contains("\"independentRecord\":"))
            assertTrue(json.contains("\"skipped\":")); assertTrue(json.all { it.code < 128 })
            listOf("example.org", "query=secret", "private-source", "private title", c.candidateId).forEach {
                assertFalse(json.contains(it))
            }
            if (evidence == SaveEvaluationEvidence.SYNTHETIC_FIXTURE) {
                val file = java.io.File("build/evaluation-export-${mode.name.lowercase()}-fixture.json")
                file.parentFile.mkdirs(); file.writeText(json, Charsets.UTF_8)
            }
        }
    }

}
