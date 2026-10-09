package com.easysavevideo.searxng

import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.storage.*
import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Independent host-only regression probes. No Android, external I/O, or live endpoint calls. */
class IndependentSearxngIntegrationTest {
    private val profileA = SearchProfile("profile-a", "alpha", filters = SearchFilters(safeSearch = false))
    private val profileB = SearchProfile("profile-b", "beta", filters = SearchFilters(safeSearch = false))
    private val clock = EpochClock { 1000L }
    private val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
    private class Runtime : ProviderRuntime {
        data class Call(val request: SearchRequest, val operation: DiscoveryOperation, val callback: (ProviderOutcome) -> Unit)
        val calls = mutableListOf<Call>()
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle {
            calls += Call(request, operation, callback)
            return CancellationHandle { operation.cancellation.cancel() }
        }
    }
    private fun config(revision: Long = 1) = SearxngSourceConfiguration(revision,
        SearxngConfigurationPolicy.SLOT_IDS.map { SearxngSourceSlot(it, if (it == "searxng-1") "https://search.example.org/search" else null, it == "searxng-1") })
    private fun registry(revision: Long) = config(revision).let { configuration ->
        ProviderRegistry(SearxngSourceRegistration.bindGeneration(SearxngSourceRegistration.providers(configuration), configuration, false))
    }
    private fun coordinator(runtime: Runtime, store: DiscoveryStore = InMemoryDiscoveryStore(), revision: Long = 1) =
        VideoDiscoveryCoordinator(registry(revision), runtime, store, timer, clock,
            sourceConfigurationKey = config(revision).generation)
    private fun pending(id: String = "one") = PendingCandidatePolicy.normalize(
        CandidateUrl("https://video.example.org/$id", SourceReportedCandidateMetadata(title = "reported")),
        "searxng-1", profileA.filters)!!
    private fun result(id: String = "one") = ResultNormalizer.normalize(RawVideoResult("https://video.example.org/$id", true), "searxng-1")!!
    private fun success(pending: List<PendingVideoCandidate>, cursor: String? = null, exhausted: Boolean = true) =
        ProviderOutcome.Success(ValidatedProviderPage(emptyList(), cursor, null, exhausted, pending))

    @Test fun sourceLatchCannotBeReopenedByLifecyclePriorityOrDemand() {
        val runtime = Runtime(); val c = coordinator(runtime)
        c.setVisibility(true, true); c.start("session", profileA)
        assertEquals(1, runtime.calls.size)
        c.suspendSourcesImmediately()
        c.setVisibility(false, false); c.setVisibility(true, true)
        c.setPriority(true, false); c.setPriority(false, false)
        c.setMode(DiscoveryMode.AGGRESSIVE); c.requestMore(); c.page(0)
        assertEquals(1, runtime.calls.size)
        runtime.calls.single().callback(success(listOf(pending())))
        assertTrue(c.pendingCandidates().isEmpty())
    }
    @Test fun generationChangeInvalidatesInactiveProfileCursorAsWellAsCurrentProfile() {
        val runtime = Runtime(); val store = InMemoryDiscoveryStore(); val first = coordinator(runtime, store, 1)
        first.setVisibility(true, true); first.start("session", profileA)
        runtime.calls[0].callback(success(listOf(pending()), cursor = "old-profile-a", exhausted = false))
        first.start("session", profileB); first.close()
        val changed = coordinator(runtime, store, 2); assertTrue(changed.restore("session"))
        val before = runtime.calls.size; changed.setVisibility(true, true); changed.requestMore()
        assertEquals(before, runtime.calls.size)
        changed.start("session", profileA)
        assertEquals(before + 1, runtime.calls.size)
        assertNull("No cursor from before the configuration change may be reused", runtime.calls.last().request.progress)
    }
    @Test fun sameUrlDifferentRevisionChangesProviderCompatibility() {
        val first = registry(1).all().single().descriptor; val second = registry(2).all().single().descriptor
        assertEquals(first.id, second.id); assertNotEquals(first.version, second.version)
    }
    @Test fun generationFenceSurvivesSecondRestoreWithoutExplicitQuery() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val original = coordinator(runtime, store, 1)
        original.start("session", profileA); original.close()
        val changed = coordinator(runtime, store, 2); assertTrue(changed.restore("session")); changed.close()
        val again = coordinator(runtime, store, 2); assertTrue(again.restore("session"))
        again.setVisibility(true, true); again.requestMore(); again.setMode(DiscoveryMode.AGGRESSIVE)
        assertTrue(runtime.calls.isEmpty()); assertFalse(again.currentCheckpoint()!!.queryAuthorized)
    }
    @Test fun candidatesAreEphemeralAndNeverCreateSeenOrVerifiedEntries() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val c = coordinator(runtime, store)
        c.setVisibility(true, true); c.start("session", profileA)
        runtime.calls.single().callback(success(listOf(pending())))
        assertEquals(1, c.pendingCandidates().size); assertTrue(c.currentCheckpoint()!!.pool.entries.isEmpty())
        assertEquals(0L, c.historyStatistics()!!.seenIdentityCount.toLong())
        c.close(); val reopened = coordinator(runtime, store); assertTrue(reopened.restore("session"))
        assertTrue(reopened.pendingCandidates().isEmpty())
    }
    @Test fun pendingPoolHardCapsAt500AndStaysSeparateFromConfirmedPool() {
        val pool = PendingCandidatePool(500)
        assertEquals(500, pool.add((1..600).map { pending(it.toString()) }, emptySet()))
        assertEquals(500, pool.snapshot().size)
        assertEquals(0, pool.add(listOf(pending("601")), emptySet()))
        pool.add(emptyList(), setOf(pending("1").canonicalPageUrl))
        assertEquals(499, pool.size)
    }
    @Test fun sourceCandidatesRequireUnknownDurationAndCannotClaimActualDuration() {
        val candidate = CandidateUrl("https://video.example.org/one", SourceReportedCandidateMetadata(reportedDurationMs = 60000))
        assertNull(PendingCandidatePolicy.normalize(candidate, "searxng-1", profileA.filters.copy(includeUnknownDuration = false)))
        assertNotNull(PendingCandidatePolicy.normalize(candidate, "searxng-1", profileA.filters.copy(minimumDurationMs = 120000)))
    }
    @Test fun safeSearchUnknownRefusesWithoutCreatingTransportCall() {
        var calls = 0
        val provider = SearxngVideoSearchProvider(SearxngEndpoint("searxng-1", "https://search.example.org/search"),
            SearxngTransport { calls++; error("Network construction was not expected") })
        val safe = profileA.copy(filters = SearchFilters(safeSearch = true))
        assertEquals(DiscoveryError.UNSUPPORTED_FILTER, provider.refusal(safe))
        val cancel = DiscoveryCancellation(); val operation = DiscoveryOperation(OperationContext("op", Long.MAX_VALUE, cancel, 0), cancel)
        try { provider.search(SearchRequest(SessionStamp("session", 1), safe, null), operation); fail() }
        catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.UNSUPPORTED_FILTER, failure.code) }
        assertEquals(0, calls)
    }
    @Test fun malformedSignedUnicodeEscapesAreRejected() {
        for (escape in listOf("+041", "-001", " 041", "0x41")) {
            try { StrictJson.read(("{\"results\":[],\"title\":\"\\u" + escape + "\"}").toByteArray()); fail("Accepted malformed Unicode escape") }
            catch (failure: SearxngFailure) { assertEquals(SearxngError.INVALID_RESPONSE, failure.code) }
        }
    }
    @Test fun explicitSameQueryStartsAgainAfterCandidateExhaustion() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val c = coordinator(runtime, store)
        c.setVisibility(true, true); c.start("session", profileA)
        runtime.calls.single().callback(success(listOf(pending())))
        assertNull(store.transaction { progress(profileA.profileId, "searxng-1", profileA.queryKey) })
        c.start("session", profileA)
        assertEquals(2, runtime.calls.size); assertNull(runtime.calls.last().request.progress)
        assertTrue(c.pendingCandidates().isEmpty())
    }
    @Test fun explicitSameQueryAfterRestartCannotBeSuppressedByLostPendingCards() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val first = coordinator(runtime, store)
        first.setVisibility(true, true); first.start("session", profileA)
        runtime.calls.single().callback(success(listOf(pending()))); first.close()
        val reopened = coordinator(runtime, store); assertTrue(reopened.restore("session"))
        reopened.start("session", profileA); reopened.setVisibility(true, true)
        assertEquals(2, runtime.calls.size); assertNull(runtime.calls.last().request.progress)
    }
    @Test fun sessionCandidateCursorContinuesInMemoryWithoutPersisting() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val c = coordinator(runtime, store)
        c.setVisibility(true, true); c.start("session", profileA)
        runtime.calls[0].callback(success(listOf(pending()), cursor = "next-session-page", exhausted = false))
        assertEquals(2, runtime.calls.size)
        assertEquals("next-session-page", runtime.calls.last().request.progress!!.cursor)
        assertNull(store.transaction { progress(profileA.profileId, "searxng-1", profileA.queryKey) })
    }
    @Test fun legacyDurableCandidateExhaustionCannotSuppressFreshSession() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val descriptor = registry(1).all().single().descriptor
        store.transaction { putProgress(ProviderProgress(profileA.profileId, descriptor.id, profileA.queryKey,
            descriptor.version, descriptor.cursorSchemaVersion, "legacy-cursor", null, true, 1000)) }
        val c = coordinator(runtime, store); c.setVisibility(true, true); c.start("session", profileA)
        assertEquals(1, runtime.calls.size); assertNull(runtime.calls.single().request.progress)
    }
    @Test fun sourceLatchRejectsStaleHistoryAndProgressCommands() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val c = coordinator(runtime, store)
        c.setVisibility(true, true); c.start("session", profileA)
        val result = result()
        runtime.calls.single().callback(ProviderOutcome.Success(ValidatedProviderPage(listOf(result), null, null, true)))
        val page = c.page(0)!!
        val before = c.historyStatistics()!!.seenIdentityCount
        assertTrue(before > 0)
        c.suspendSourcesImmediately()
        assertFalse(c.recordSeen(page, result.fingerprint, SeenEvent.OPENED))
        assertFalse(c.clearHistory(profileA.profileId)); assertFalse(c.resetProgress(profileA.profileId, "searxng-1"))
        assertEquals(before, c.historyStatistics()!!.seenIdentityCount)
    }
    @Test fun existingDurableSourceContinuationStillSurvivesRestart() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime()
        val provider = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("existing-source", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("Fixture runtime only")
        }
        assertEquals(ProviderProgressScope.DURABLE, provider.descriptor.progressScope)
        fun create() = VideoDiscoveryCoordinator(ProviderRegistry(listOf(provider)), runtime, store, timer, clock)
        val original = create(); original.setVisibility(true, true); original.start("session", profileA)
        runtime.calls.single().callback(success(emptyList(), exhausted = true))
        assertTrue(store.transaction { progress(profileA.profileId, provider.descriptor.id, profileA.queryKey) }!!.exhausted)
        original.close(); val reopened = create(); assertTrue(reopened.restore("session"))
        reopened.setVisibility(true, true); reopened.start("session", profileA)
        assertEquals(1, runtime.calls.size)
    }
    @Test fun forcedRecoveryRequiresExplicitSearchEvenWhenConfigurationDidNotChange() {
        val store = InMemoryDiscoveryStore(); val runtime = Runtime(); val original = coordinator(runtime, store)
        original.start("session", profileA); original.close()
        val recovered = coordinator(runtime, store); assertTrue(recovered.restore("session", requireExplicitSearch = true))
        recovered.setVisibility(true, true); recovered.requestMore(); recovered.setMode(DiscoveryMode.AGGRESSIVE)
        assertFalse(recovered.currentCheckpoint()!!.queryAuthorized); assertTrue(runtime.calls.isEmpty())
        recovered.close(); val restarted = coordinator(runtime, store); assertTrue(restarted.restore("session"))
        restarted.setVisibility(true, true); restarted.requestMore(); assertTrue(runtime.calls.isEmpty())
        restarted.start("session", profileA); assertEquals(1, runtime.calls.size)
    }
    @Test fun noDefaultEndpointAndOnlyThreeOrderedSlotsAreAccepted() {
        val empty = SearxngConfigurationPolicy.empty()
        assertEquals(3, empty.slots.size); assertTrue(empty.enabledEndpoints.isEmpty())
        try { SearxngSourceConfiguration(0, empty.slots.reversed()); fail() } catch (_: IllegalArgumentException) { }
        try { SearxngSourceSlot("searxng-4"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun acknowledgementCannotTransferAcrossEndpointOrReenableEdit() {
        val empty = SearxngConfigurationPolicy.empty()
        assertTrue(SearxngConfigurationPolicy.replace(empty, "searxng-1", "https://search.example.org/search", true, false) is SearxngConfigurationEdit.Rejected)
        val disabled = SearxngSourceConfiguration(2, config().slots.map { SearxngSourceSlot(it.id, it.searchUrl, false) })
        assertTrue(SearxngConfigurationPolicy.replace(disabled, "searxng-1", "https://search.example.org/search", true, false) is SearxngConfigurationEdit.Rejected)
    }
    @Test fun checkpointExtensionPreservesVersionThreeForHistoricalDefaults() {
        val old = DiscoveryCheckpoint(SessionStamp("session", 1), profileA)
        val oldBytes = DiscoveryCheckpointCodec.encode(old)
        assertEquals(3, ByteBuffer.wrap(oldBytes, 4, 4).int)
        assertEquals(old, DiscoveryCheckpointCodec.decode(oldBytes))
        val fenced = old.copy(sourceConfigurationKey = config().generation, queryAuthorized = false)
        val bytes = DiscoveryCheckpointCodec.encode(fenced)
        assertEquals(4, ByteBuffer.wrap(bytes, 4, 4).int)
        assertEquals(fenced, DiscoveryCheckpointCodec.decode(bytes))
    }
}
