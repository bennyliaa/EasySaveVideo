package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import org.junit.Assert.*
import org.junit.Test

class SourceConfigurationFenceTest {
    private val source = StubProvider(ProviderDescriptor("searxng-1", "endpoint-one"))
    private val registry = ProviderRegistry(listOf(source))
    private val clock = FakeClock()
    private val store = InMemoryDiscoveryStore()
    private val runtime = FakeRuntime()
    private fun coordinator(key: String) = VideoDiscoveryCoordinator(registry, runtime, store, FakeTimer(clock), clock,
        sourceConfigurationKey = key.repeat(64))
    private val profile = SearchProfile("profile", "query", filters = SearchFilters(safeSearch = false))
    private fun pending() = PendingCandidatePolicy.normalize(CandidateUrl("https://video.example/pending",
        SourceReportedCandidateMetadata(title = "reported")), "searxng-1", profile.filters)!!
    @Test fun pendingCardsDoNotEnterVerifiedPoolHistoryOrPersistence() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        runtime.calls.single().callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true, listOf(pending()))))
        assertEquals(1, c.pendingCandidates().size); assertTrue(c.currentCheckpoint()!!.pool.entries.isEmpty())
        assertEquals(0, c.historyStatistics()!!.seenIdentityCount)
        c.close()
        val restored = coordinator("a"); assertTrue(restored.restore("session")); assertTrue(restored.pendingCandidates().isEmpty())
    }
    @Test fun changedConfigurationClearsVerifiedPoolCursorsAndDemandOnRestore() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        runtime.complete(0, 1, nextCursor = "old-cursor", exhausted = true); assertNotNull(c.page(0)); c.close()
        val restored = coordinator("b"); assertTrue(restored.restore("session")); restored.setVisibility(true, true)
        assertFalse(restored.currentCheckpoint()!!.queryAuthorized); assertTrue(restored.currentCheckpoint()!!.pool.entries.isEmpty())
        assertNull(store.transaction { progress("profile", "searxng-1", profile.queryKey) })
        restored.requestMore(); restored.setMode(DiscoveryMode.AGGRESSIVE); restored.page(0)
        assertEquals(1, runtime.calls.size)
        restored.start("session", profile); assertEquals(2, runtime.calls.size)
    }
    @Test fun sourceChangeDemandFencePersistsAcrossAnotherProcessRestore() {
        val c = coordinator("a"); c.start("session", profile); c.close()
        val changed = coordinator("b"); assertTrue(changed.restore("session")); changed.close()
        val reopened = coordinator("b"); assertTrue(reopened.restore("session")); reopened.setVisibility(true, true); reopened.requestMore()
        assertFalse(reopened.currentCheckpoint()!!.queryAuthorized); assertTrue(runtime.calls.isEmpty())
    }
    @Test fun staleSourceResponseCannotPublishIntoNewQueryCandidates() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        val old = runtime.calls.single(); c.start("session", profile.copy(query = "new"))
        old.callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true, listOf(pending()))))
        assertTrue(c.pendingCandidates().isEmpty())
    }
    @Test fun sourceWriteFenceCannotBeReopenedByLifecyclePriorityPowerOrDemand() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        c.suspendSourcesImmediately()
        c.setVisibility(false, false); c.setVisibility(true, true)
        c.setPriority(true, false); c.setPriority(false, false)
        c.setMode(DiscoveryMode.AGGRESSIVE); c.requestMore()
        runtime.calls[0].callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), "later", null, false, listOf(pending()))))
        assertEquals(1, runtime.calls.size); assertTrue(c.pendingCandidates().isEmpty())
        assertEquals(DiscoveryState.SUSPENDED, c.status().state)
    }
    @Test fun sourceProgressResetAndNewQueryClearPendingOnly() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        runtime.calls.single().callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true, listOf(pending()))))
        assertEquals(1, c.pendingCandidates().size); c.resetProgress("profile", "searxng-1")
        assertTrue(c.pendingCandidates().isEmpty()); assertEquals(1, runtime.calls.size)
    }

    @Test fun ephemeralProgressNeverSurvivesFreshSearchOrProcessRestore() {
        source.descriptor = source.descriptor.copy(progressScope = ProviderProgressScope.SESSION)
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        runtime.calls[0].callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(), null, null, true, listOf(pending()))))
        assertNull(store.transaction { progress("profile", "searxng-1", profile.queryKey) })
        c.start("session", profile); assertEquals(2, runtime.calls.size); c.close()
        val reopened = coordinator("a"); reopened.restore("session"); reopened.setVisibility(true, true)
        assertEquals(3, runtime.calls.size)
    }
    @Test fun sourceWriteFenceRejectsCapturedLocalHistoryAndProgressCommands() {
        val c = coordinator("a"); c.setVisibility(true, true); c.start("session", profile)
        runtime.complete(0, 7); val page = c.page(0)!!; val result = c.results(page).single()
        c.suspendSourcesImmediately()
        assertFalse(c.recordSeen(page, result.fingerprint, SeenEvent.OPENED))
        assertFalse(c.clearHistory("profile")); assertFalse(c.resetProgress("profile", "searxng-1"))
        assertEquals(1, c.historyStatistics("profile")!!.seenIdentityCount)
    }
    @Test fun recoveryRestoreRetiresDemandEvenWhenSourceGenerationIsUnchanged() {
        val c = coordinator("a"); c.start("session", profile); c.close()
        val recovered = coordinator("a"); recovered.restore("session", requireExplicitSearch = true)
        recovered.setVisibility(true, true); recovered.requestMore()
        assertFalse(recovered.currentCheckpoint()!!.queryAuthorized); assertTrue(runtime.calls.isEmpty())
    }
}
