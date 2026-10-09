package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DiscoveryContractsTest {
    @Test fun queryIdentityNormalizesTermsButRetainsSourceParameters() {
        val a = SearchProfile("a", "  Ａ  B ", "EN")
        assertEquals(a.queryKey, SearchProfile("b", "a b", "en").queryKey)
        assertEquals(a.queryKey, a.copy(historyPolicy = RepeatPolicy.HIDE_SHOWN).queryKey)
        assertNotEquals(a.queryKey, a.copy(filters = SearchFilters(safeSearch = false)).queryKey)
        assertNotEquals(a.queryKey, a.copy(locale = "zh").queryKey)
    }
    @Test fun conservativeUrlNormalizationPreservesIdentityParameters() {
        assertEquals("https://example.com/watch?token=x&id=1#part", ResultNormalizer.canonicalUrl("HTTPS://EXAMPLE.COM:443/watch?token=x&id=1#part"))
        assertEquals("http://example.com/", ResultNormalizer.canonicalUrl("http://EXAMPLE.COM:80"))
        assertNotEquals(ResultNormalizer.canonicalUrl("https://example.com/v?id=1"), ResultNormalizer.canonicalUrl("https://example.com/v?id=2"))
        listOf("javascript:alert(1)", "file:///tmp/x", "https://user:pass@example.com", "https://example.com/a b", "https://example.com/%GG").forEach { assertNull(ResultNormalizer.canonicalUrl(it)) }
    }
    @Test fun confirmedVideoAndHonestNullableFieldsAreRequired() {
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/", false), "a"))
        val video = ResultNormalizer.normalize(RawVideoResult("https://example.com/", true, title = " ", durationMs = 0), "a")!!
        assertNull(video.title); assertNull(video.durationMs); assertNull(video.thumbnailUrl)
        assertEquals("example.com", video.sourceName)
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/", true, title = "x".repeat(513)), "a"))
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/", true, durationMs = -1), "a"))
    }
    @Test fun durationUnknownIsSeparateFromKnownOutOfRange() {
        val filter = SearchFilters(minimumDurationMs = 1000, maximumDurationMs = 2000)
        val raw = RawVideoResult("https://example.com/", true)
        assertNotNull(ResultNormalizer.normalize(raw, "a", filter))
        assertNull(ResultNormalizer.normalize(raw, "a", filter.copy(includeUnknownDuration = false)))
        assertNull(ResultNormalizer.normalize(raw.copy(durationMs = 999), "a", filter))
        assertNotNull(ResultNormalizer.normalize(raw.copy(durationMs = 1500), "a", filter))
    }
    @Test fun identityMergesProvidersButNeverTitleOnly() {
        val pool = ResultPool()
        val first = result(1, "a")
        val second = result(1, "b").copy(canonicalPageUrl = "https://video.example/alternate")
        val state = pool.admit(PoolState(), listOf(first, second, result(2).copy(title = first.title)), 1).state
        assertEquals(2, state.entries.size)
        assertEquals(setOf("a", "b"), state.entries.first().result.provenance)
    }
    @Test fun fallbackUrlIdentityMergesWithLaterSourceIdentity() {
        val raw = RawVideoResult("https://video.example/watch?id=1", true)
        val state = ResultPool().admit(PoolState(), listOf(ResultNormalizer.normalize(raw, "a")!!, result(1, "b")), 1).state
        assertEquals(1, state.entries.size); assertEquals(setOf("a", "b"), state.entries.single().result.provenance)
        assertNotNull(state.entries.single().result.sourceVideoId)
    }
    @Test fun fourHistoryPoliciesUseIndependentFacts() {
        val facts = listOf(
            SeenRecord("p", "A", 1), SeenRecord("p", "B", 1, shownAt = 2),
            SeenRecord("p", "C", 1, shownAt = 2, openedAt = 3),
            SeenRecord("p", "D", 1, shownAt = 2, openedAt = 3, playedAt = 4),
            SeenRecord("p", "E", 1, savedAt = 3), SeenRecord("p", "F", 1))
        fun allowed(policy: RepeatPolicy) = facts.filter { SeenHistoryPolicy.allows(policy, it) }.map { it.fingerprint }
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), allowed(RepeatPolicy.SHOW_ALL))
        assertEquals(listOf("A", "B", "E", "F"), allowed(RepeatPolicy.HIDE_WATCHED))
        assertEquals(listOf("A", "E", "F"), allowed(RepeatPolicy.HIDE_SHOWN))
        assertEquals(listOf("A", "E", "F", "B", "C", "D"), facts.sortedBy { SeenHistoryPolicy.priority(RepeatPolicy.PREFER_NEW, it) }.map { it.fingerprint })
        assertTrue(SeenHistoryPolicy.allows(RepeatPolicy.HIDE_SHOWN, SeenRecord("p", "x", 1, openedAt = 2)))
        assertFalse(SeenHistoryPolicy.allows(RepeatPolicy.HIDE_WATCHED, SeenRecord("p", "x", 1, playedAt = 2)))
    }
    @Test fun seenEventsAreIdempotentMonotonicAndDoNotInventOtherFacts() {
        val seen = SeenRecord("p", "x", 100).record(SeenEvent.SAVED, 200).record(SeenEvent.SAVED, 150)
        assertEquals(200L, seen.savedAt); assertNull(seen.openedAt); assertNull(seen.playedAt); assertNull(seen.shownAt)
        assertEquals(seen, seen.record(SeenEvent.SAVED, 200))
    }
    @Test fun shownRequiresContinuousHalfVisibilityFor500ms() {
        val tracker = ShownVisibilityTracker()
        assertFalse(tracker.update("x", .5, 0)); assertFalse(tracker.update("x", .5, 499))
        assertFalse(tracker.update("x", .49, 500)); assertFalse(tracker.update("x", 1.0, 600))
        assertTrue(tracker.update("x", .5, 1100)); assertFalse(tracker.update("x", 1.0, 1600))
        tracker.clear(); assertFalse(tracker.update("x", 1.0, 1700))
    }
    @Test fun materializedPagesDoNotReorderOrReallocateWhenHistoryChanges() {
        val pool = ResultPool(); val stamp = SessionStamp("s", 1)
        val seen = mutableMapOf<String, SeenRecord>()
        var state = pool.admit(PoolState(), (1..30).map { result(it) }, 1).state
        val (after, first) = pool.materialize(state, stamp, 0, 20, RepeatPolicy.HIDE_SHOWN, seen::get)
        state = after; assertEquals(20, first!!.orderedResultIds.size)
        first.orderedResultIds.forEach { seen[it] = SeenRecord("p", it, 1, shownAt = 2) }
        state = pool.admit(state, (31..40).map { result(it) }, 2).state
        assertEquals(first, pool.materialize(state, stamp, 0, 20, RepeatPolicy.HIDE_SHOWN, seen::get).second)
        val second = pool.materialize(state, stamp, 1, 20, RepeatPolicy.HIDE_SHOWN, seen::get).second!!
        assertTrue(first.orderedResultIds.intersect(second.orderedResultIds.toSet()).isEmpty())
        assertEquals(20, second.orderedResultIds.size)
    }
    @Test fun inventoryExcludesAllAllocatedPagesAndStrictHistory() {
        val pool = ResultPool(); var state = pool.admit(PoolState(), (1..100).map { result(it) }, 1).state
        repeat(4) { state = pool.materialize(state, SessionStamp("s", 1), it, 20, RepeatPolicy.SHOW_ALL) { null }.first }
        assertEquals(20, pool.available(state, RepeatPolicy.SHOW_ALL) { null }.size)
        assertEquals(0, pool.available(state, RepeatPolicy.HIDE_WATCHED) { SeenRecord("p", it, 1, openedAt = 2) }.size)
    }
    @Test fun partialPageIsStableAndNoEmptyPageIsInvented() {
        val pool = ResultPool(); var state = pool.admit(PoolState(), listOf(result(1)), 1).state
        val pair = pool.materialize(state, SessionStamp("s", 1), 0, 20, RepeatPolicy.SHOW_ALL) { null }; state = pair.first
        state = pool.admit(state, listOf(result(2)), 2).state
        assertEquals(1, pool.materialize(state, SessionStamp("s", 1), 0, 20, RepeatPolicy.SHOW_ALL) { null }.second!!.orderedResultIds.size)
        assertNull(pool.materialize(PoolState(), SessionStamp("s", 1), 0, 20, RepeatPolicy.SHOW_ALL) { null }.second)
    }
    @Test fun capacityNeverDeletesMaterializedPage() {
        val pool = ResultPool(5); var state = pool.admit(PoolState(), (1..5).map { result(it) }, 1).state
        state = pool.materialize(state, SessionStamp("s", 1), 0, 2, RepeatPolicy.SHOW_ALL) { null }.first
        val original = state.pages.single()
        state = pool.admit(state, (6..10).map { result(it) }, 2).state
        assertEquals(5, state.entries.size); assertTrue(original.orderedResultIds.all { id -> state.entries.any { it.result.resultId == id } })
        state = pool.materialize(state, SessionStamp("s", 1), 1, 5, RepeatPolicy.SHOW_ALL) { null }.first
        assertEquals(DiscoveryError.CAPACITY_REACHED, assertThrows(DiscoveryFailure::class.java) { pool.admit(state, listOf(result(11)), 3) }.code)
    }
    @Test fun viewRevisionPreservesCurrentAnchor() {
        val pool = ResultPool(); var state = pool.admit(PoolState(), (1..40).map { result(it) }, 1).state
        repeat(2) { state = pool.materialize(state, SessionStamp("s", 1), it, 20, RepeatPolicy.SHOW_ALL) { null }.first }
        val anchor = state.pages[1].orderedResultIds
        val revised = pool.revise(state, SessionStamp("s", 1), 1, 10)
        assertEquals(1L, revised.viewRevision); assertEquals(anchor, revised.pages.single().orderedResultIds)
    }
    @Test fun storeRollsBackPoolProgressAndSeenTogether() {
        val store = InMemoryDiscoveryStore(); val profile = SearchProfile("p", "q")
        assertThrows(IllegalStateException::class.java) { store.transaction {
            putCheckpoint(DiscoveryCheckpoint(SessionStamp("s", 1), profile))
            putProgress(ProviderProgress("p", "a", profile.queryKey, "1", 1, "next", null, false, 1))
            putSeen(SeenRecord("p", "x", 1)); error("injected failure")
        } }
        store.transaction { assertNull(checkpoint("s")); assertNull(progress("p", "a", profile.queryKey)); assertNull(seen("p", "x")) }
    }
    @Test fun resetProgressAndClearHistoryAreIndependentAndScoped() {
        val store = InMemoryDiscoveryStore(); store.transaction {
            putSeen(SeenRecord("p", "x", 1)); putSeen(SeenRecord("other", "x", 1))
            listOf("a", "b").forEach { putProgress(ProviderProgress("p", it, "q", "1", 1, "next", null, false, 1)) }
            resetProgress("p", "a"); assertNull(progress("p", "a", "q")); assertNotNull(progress("p", "b", "q")); assertNotNull(seen("p", "x"))
            clearSeen("p"); assertNull(seen("p", "x")); assertNotNull(seen("other", "x")); assertNotNull(progress("p", "b", "q"))
        }
    }
    @Test fun cursorExpiryAndUnknownCompatibilityAreFailClosed() {
        val progress = ProviderProgress("p", "a", "q", "1", 1, "next", 100, false, 1)
        assertTrue(progress.isCompatible(ProviderDescriptor("a", "1"), 99))
        assertFalse(progress.isCompatible(ProviderDescriptor("a", "1"), 100))
        assertFalse(progress.isCompatible(ProviderDescriptor("a", "2"), 1))
        assertTrue(progress.isCompatible(ProviderDescriptor("a", "2", compatibleCursorVersions = setOf("1")), 1))
        assertFalse(progress.isCompatible(ProviderDescriptor("a", "2", 2, setOf("1")), 1))
    }
    @Test fun schedulerEnforcesWatermarksConcurrencyAndSixRequests() {
        val s = DiscoveryScheduler(); s.visibility(true, true)
        assertFalse(s.beginRound(40)); assertTrue(s.beginRound(39))
        assertTrue(s.reserve("a", 39)); assertFalse(s.reserve("a", 39)); assertTrue(s.reserve("b", 39)); assertFalse(s.reserve("c", 39))
        s.release("a"); assertTrue(s.reserve("a", 60)) // Filling continues past low watermark.
        s.release("a"); assertFalse(s.reserve("a", 100))
        repeat(3) { s.release("a"); assertTrue(s.reserve("a", 90)) }
        s.release("a"); assertFalse(s.reserve("a", 90)); assertEquals(6, s.requestsThisRound)
        s.priority(true, false); assertFalse(s.canLaunch("a", 0))
        assertEquals(1, DiscoveryLimits().concurrency(DiscoveryMode.POWER_SAVING)); assertEquals(3, DiscoveryLimits().concurrency(DiscoveryMode.AGGRESSIVE))
    }
    @Test fun limitsCannotExpandRemoteBudgets() {
        assertEquals(40, DiscoveryLimits().lowWatermark); assertEquals(100, DiscoveryLimits().highWatermark)
        assertEquals(500, DiscoveryLimits(pageSize = 100).highWatermark)
        assertThrows(IllegalArgumentException::class.java) { DiscoveryLimits(maxRetries = 3) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryLimits(maxRequestsPerRound = 7) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryLimits(requestDeadlineMillis = 15001) }
    }
    @Test fun boundedBodyRejectsOverflowThroughReadsAndSkip() {
        BoundedProviderInputStream(ByteArrayInputStream(ByteArray(11)), 10).use { input ->
            assertEquals(10, input.read(ByteArray(10)))
            assertEquals(DiscoveryError.RESPONSE_TOO_LARGE, assertThrows(DiscoveryFailure::class.java) { input.read() }.code)
        }
        BoundedProviderInputStream(ByteArrayInputStream(ByteArray(11)), 10).use { input ->
            assertThrows(DiscoveryFailure::class.java) { input.skip(11) }; assertFalse(input.markSupported())
        }
    }
    @Test fun candidatePoolIsFiniteAndDeduplicatesCanonicalUrls() {
        val pool = CandidateUrlPool(1)
        assertTrue(pool.add(CandidateUrl("https://EXAMPLE.com"))); assertFalse(pool.add(CandidateUrl("https://example.com/")))
        assertFalse(pool.add(CandidateUrl("javascript:x")))
        assertThrows(DiscoveryFailure::class.java) { pool.add(CandidateUrl("https://other.example/")) }
        assertNotNull(pool.take()); assertEquals(0, pool.size)
    }
    @Test fun candidateValidationNeverPretendsMissingAdapterIsExtraction() {
        val clock = FakeClock(); val page = ProviderPage(candidates = listOf(CandidateUrl("https://example.com/")))
        assertEquals(DiscoveryError.VALIDATOR_UNAVAILABLE, assertThrows(DiscoveryFailure::class.java) {
            ValidationPipeline(clock = clock).validate("a", page, SearchFilters(), operation())
        }.code)
        val pipeline = ValidationPipeline(CandidateValidator { _, _ -> null }, clock = clock)
        assertTrue(pipeline.validate("a", page, SearchFilters(), operation()).results.isEmpty())
    }
    @Test fun pipelineEnforcesDeadlineSizeAnd100CandidateCeiling() {
        val clock = FakeClock(); val pipeline = ValidationPipeline(clock = clock)
        assertThrows(DiscoveryFailure::class.java) { pipeline.validate("a", ProviderPage(responseBytes = 2L * 1024 * 1024 + 1), SearchFilters(), operation()) }
        assertThrows(DiscoveryFailure::class.java) { pipeline.validate("a", ProviderPage(results = List(101) { RawVideoResult("https://example.com/", true) }), SearchFilters(), operation()) }
        val slow = ValidationPipeline(CandidateValidator { _, _ -> clock.now = 16000; RawVideoResult("https://example.com/", true) }, clock = clock)
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, assertThrows(DiscoveryFailure::class.java) { slow.validate("a", ProviderPage(candidates = listOf(CandidateUrl("https://example.com/"))), SearchFilters(), operation()) }.code)
    }
    @Test fun functionalObjectsRedactSearchAndResultContentsFromToString() {
        assertFalse(SearchProfile("p", "private search").toString().contains("private"))
        assertFalse(result(1).toString().contains("https://"))
        assertFalse(RawVideoResult("https://secret.example/", true).toString().contains("secret"))
    }

    @Test fun bridgeAliasesMergeAllUnmaterializedEntriesAndPreservePinnedPages() {
        val pool = ResultPool()
        val a = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true), "a")!!
        val b = ResultNormalizer.normalize(RawVideoResult("https://video.example/b", true, sourceNamespace = "video", sourceVideoId = "id"), "b")!!
        val bridge = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true, sourceNamespace = "video", sourceVideoId = "id"), "c")!!
        val initial = pool.admit(PoolState(), listOf(a, b), 1).state
        val merged = pool.admit(initial, listOf(bridge), 2).state
        assertEquals(1, merged.entries.size); assertEquals(setOf("a", "b", "c"), merged.entries.single().result.provenance)
        val pinned = pool.materialize(initial, SessionStamp("s", 1), 0, 20, RepeatPolicy.SHOW_ALL) { null }.first
        val kept = pool.admit(pinned, listOf(bridge), 2).state
        assertEquals(pinned.pages, kept.pages); assertEquals(2, kept.entries.size)
        assertEquals(0, pool.available(kept, RepeatPolicy.SHOW_ALL) { null }.size)
    }
    @Test fun longFallbackSourceAndUnboundedIdentityAliasesAreRejectedAtSourceBoundary() {
        val host = "a".repeat(60) + "." + "b".repeat(60) + ".example.com"
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://$host/", true), "a"))
        val pool = ResultPool(); var state = PoolState()
        for (id in 1..127) {
            val raw = RawVideoResult("https://video.example/$id", true, sourceNamespace = "video", sourceVideoId = "same")
            state = pool.admit(state, listOf(ResultNormalizer.normalize(raw, "a")!!), 1).state
        }
        val overflow = ResultNormalizer.normalize(RawVideoResult("https://video.example/128", true, sourceNamespace = "video", sourceVideoId = "same"), "a")!!
        assertEquals(DiscoveryError.INVALID_RESULT, assertThrows(DiscoveryFailure::class.java) { pool.admit(state, listOf(overflow), 1) }.code)
    }
    @Test fun aliasHistoryIsAppliedWhenPreferredIdentityBecomesAvailable() {
        val old = ResultNormalizer.normalize(RawVideoResult("https://video.example/watch?id=1", true), "a")!!
        val known = result(1)
        val state = ResultPool().admit(PoolState(), listOf(known), 1).state
        val seen = mapOf(old.fingerprint to SeenRecord("p", old.fingerprint, 1, shownAt = 2))
        assertTrue(ResultPool().available(state, RepeatPolicy.HIDE_SHOWN, seen::get).isEmpty())
    }

    @Test fun bridgedPinnedIdentitiesCannotDuplicateAfterViewRevision() {
        val pool = ResultPool(); val stamp = SessionStamp("s", 1)
        val a = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true), "a")!!
        val b = ResultNormalizer.normalize(RawVideoResult("https://video.example/b", true, sourceNamespace = "video", sourceVideoId = "id"), "b")!!
        val bridge = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true, sourceNamespace = "video", sourceVideoId = "id"), "c")!!
        var state = pool.admit(PoolState(), listOf(a, b, result(99)), 1).state
        repeat(3) { state = pool.materialize(state, stamp, it, 1, RepeatPolicy.SHOW_ALL) { null }.first }
        state = pool.admit(state, listOf(bridge), 2).state
        val secondAnchor = pool.revise(state, stamp, 1, 20)
        assertEquals(listOf(result(99).fingerprint), pool.available(secondAnchor, RepeatPolicy.SHOW_ALL) { null }.map { it.result.fingerprint })
        val thirdAnchor = pool.revise(state, stamp, 2, 20)
        assertEquals(1, pool.available(thirdAnchor, RepeatPolicy.SHOW_ALL) { null }.size)
    }
}
