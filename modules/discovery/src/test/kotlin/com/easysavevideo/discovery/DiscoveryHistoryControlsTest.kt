package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryHistoryControlsTest {
    private class Fixture(providerCount: Int = 1, val store: DiscoveryStore = InMemoryDiscoveryStore()) {
        val clock = FakeClock()
        val runtime = FakeRuntime()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry((1..providerCount).map { StubProvider(ProviderDescriptor("p$it", "1")) }),
            runtime, store, FakeTimer(clock), clock)
        val profile = SearchProfile("profile", "query")
        fun start(incognito: Boolean = false) { coordinator.setVisibility(true, true); coordinator.start("session", profile, incognito) }
    }

    @Test fun historyClearCancelsLateProviderAndPageEventsWithoutClearingProgressOrOtherProfile() {
        val f = Fixture(); f.start()
        f.runtime.complete(0, 1, nextCursor = "retained", exhausted = false)
        assertEquals(2, f.runtime.calls.size)
        val oldPage = f.coordinator.page(0)!!
        assertTrue(f.coordinator.recordSeen(oldPage, result(1).fingerprint, SeenEvent.OPENED))
        f.store.transaction { putSeen(SeenRecord("other", "unrelated", 1, savedAt = 2)) }
        assertTrue(f.coordinator.clearHistory(f.profile.profileId))
        assertTrue(f.runtime.calls[1].cancelled)
        assertFalse(f.coordinator.recordSeen(oldPage, result(1).fingerprint, SeenEvent.SAVED))
        f.runtime.complete(1, 99, nextCursor = "late", exhausted = false)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(0, f.coordinator.historyStatistics(f.profile.profileId)!!.seenAliasCount)
        f.store.transaction {
            assertEquals("retained", progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor)
            assertEquals(2L, seen("other", "unrelated")!!.savedAt)
            assertNull(seen(f.profile.profileId, result(99).fingerprint))
        }
        val currentPage = f.coordinator.page(0)!!
        assertEquals(oldPage.orderedResultIds, currentPage.orderedResultIds)
        assertEquals(oldPage.revision + 1, currentPage.revision)
        assertTrue(f.coordinator.recordSeen(currentPage, result(1).fingerprint, SeenEvent.OPENED))
        f.store.transaction {
            val fresh = seen(f.profile.profileId, result(1).fingerprint)!!
            assertNotNull(fresh.openedAt); assertNull(fresh.shownAt); assertNull(fresh.playedAt); assertNull(fresh.savedAt)
        }
    }

    @Test fun providerResetCancelsOnlyMatchingWorkAndDoesNotAutomaticallyRefillIt() {
        val f = Fixture(providerCount = 2); f.start()
        assertEquals(2, f.runtime.calls.size)
        val resetIndex = f.runtime.calls.indexOfFirst { it.provider.descriptor.id == "p1" }
        val otherIndex = f.runtime.calls.indexOfFirst { it.provider.descriptor.id == "p2" }
        f.store.transaction {
            putProgress(progress(f.profile, "p1", "old-one")); putProgress(progress(f.profile, "p2", "old-two"))
            putProgress(progress(f.profile.copy(profileId = "other"), "p1", "other-profile"))
            putSeen(SeenRecord(f.profile.profileId, "history", 1, savedAt = 3))
        }
        assertTrue(f.coordinator.resetProgress(f.profile.profileId, "p1"))
        assertTrue(f.runtime.calls[resetIndex].cancelled); assertFalse(f.runtime.calls[otherIndex].cancelled)
        f.runtime.complete(resetIndex, 91, nextCursor = "late-one", exhausted = false)
        f.runtime.complete(otherIndex, 2, nextCursor = "new-two", exhausted = false)
        assertEquals("Reset creates no new provider demand, including from unrelated completion", 2, f.runtime.calls.size)
        f.store.transaction {
            assertNull(progress(f.profile.profileId, "p1", f.profile.queryKey))
            assertEquals("new-two", progress(f.profile.profileId, "p2", f.profile.queryKey)!!.cursor)
            assertEquals("other-profile", progress("other", "p1", f.profile.queryKey)!!.cursor)
            assertEquals(3L, seen(f.profile.profileId, "history")!!.savedAt)
            assertNull(seen(f.profile.profileId, result(91).fingerprint))
        }
        f.coordinator.requestMore()
        assertTrue(f.runtime.calls.size > 2)
        assertNull(f.runtime.calls.drop(2).first { it.provider.descriptor.id == "p1" }.request.progress)
    }

    @Test fun clearingOrResettingOtherProfileDoesNotCancelCurrentWork() {
        val f = Fixture(); f.start()
        f.store.transaction {
            putSeen(SeenRecord("other", "a", 1)); putProgress(progress(f.profile.copy(profileId = "other"), "p1", "old"))
        }
        assertTrue(f.coordinator.clearHistory("other")); assertTrue(f.coordinator.resetProgress("other", "p1"))
        assertFalse(f.runtime.calls.single().cancelled)
        f.runtime.complete(0, 1)
        assertEquals(1, f.coordinator.currentCheckpoint()!!.pool.entries.size)
    }

    @Test fun directStoreHistoryClearRejectsStaleCoordinatorFactsAndProviderCallbacks() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1, nextCursor = "old", exhausted = false)
        val page = f.coordinator.page(0)!!
        f.store.transaction { clearSeen() }
        assertFalse(f.coordinator.recordSeen(page, result(1).fingerprint, SeenEvent.SAVED))
        f.runtime.complete(1, 99, nextCursor = "late", exhausted = true)
        f.store.transaction {
            assertEquals(0, historyStatistics().seenAliasCount)
            assertEquals("old", progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor)
        }
    }

    @Test fun statisticsWithZeroProvidersDoNotMutateOrStartWork() {
        var mutations = 0
        val backing = InMemoryDiscoveryStore()
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                val tx = object : DiscoveryTransaction by delegate {
                    override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) { mutations++; delegate.putCheckpoint(checkpoint) }
                    override fun putSeen(record: SeenRecord) { mutations++; delegate.putSeen(record) }
                    override fun linkSeenAliases(profileId: String, aliases: Set<String>) { mutations++; delegate.linkSeenAliases(profileId, aliases) }
                    override fun resetProgress(profileId: String, providerId: String?, queryKey: String?) {
                        mutations++; delegate.resetProgress(profileId, providerId, queryKey)
                    }
                    override fun clearSeen(profileId: String?) { mutations++; delegate.clearSeen(profileId) }
                }
                tx.block()
            }
        }
        val f = Fixture(providerCount = 0, store = store); f.start()
        val expected = HistoryStatistics(1, 1, 0, 0, 0)
        val before = mutations
        repeat(100) { assertEquals(expected, f.coordinator.historyStatistics()); assertEquals(DiscoveryState.NO_SOURCES, f.coordinator.status().state) }
        assertEquals(before, mutations); assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun unavailableStatisticsAreNotFakeZeroAndDoNotCancelProvider() {
        val backing = InMemoryDiscoveryStore()
        var fail = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
                if (fail) error("unavailable")
                return backing.transaction(block)
            }
        }
        val f = Fixture(store = store); f.start(); fail = true
        assertNull(f.coordinator.historyStatistics()); assertFalse(f.runtime.calls.single().cancelled)
        fail = false
        assertNotNull(f.coordinator.historyStatistics())
    }

    @Test fun incognitoCountsAndClearAreIsolatedFromDurableHistoryAndDiscardedOnExit() {
        val f = Fixture()
        f.store.transaction { putSeen(SeenRecord("durable", "saved", 1, savedAt = 2)) }
        f.start(incognito = true); f.runtime.complete(0, 1)
        assertEquals(1, f.coordinator.historyStatistics()!!.seenIdentityCount)
        assertEquals(0, f.coordinator.historyStatistics("durable")!!.seenAliasCount)
        assertTrue(f.coordinator.clearHistory())
        assertEquals(0, f.coordinator.historyStatistics()!!.seenAliasCount)
        assertEquals(2L, f.store.transaction { seen("durable", "saved")!!.savedAt })
        f.coordinator.start("ordinary", f.profile.copy(query = ""))
        assertEquals(1, f.coordinator.historyStatistics()!!.seenAliasCount)
        assertNull(f.store.transaction { seen(f.profile.profileId, result(1).fingerprint) })
    }

    @Test fun protectedCapacityFailureIsReportedTruthfullyOnStartAndRecordSeen() {
        val policy = DiscoveryRetentionPolicy(maxProfiles = 1, maxSeenAliases = 1, maxCheckpoints = 1)
        val backing = InMemoryDiscoveryStore(policy)
        val f = Fixture(store = backing); f.start(); f.runtime.complete(0, 1)
        val page = f.coordinator.page(0)!!
        // Normal result has two linked aliases. Before materialization the whole oversized group was
        // evicted. A new real event now cannot fit without deleting this materialized identity.
        assertFalse(f.coordinator.recordSeen(page, result(1).fingerprint, SeenEvent.SAVED))
        assertEquals(DiscoveryState.CAPACITY_REACHED, f.coordinator.status().state)
        assertEquals(DiscoveryError.CAPACITY_REACHED, f.coordinator.status().lastError)
        assertEquals(0, f.coordinator.historyStatistics()!!.seenAliasCount)
        f.coordinator.start("new-session", SearchProfile("new-profile", ""))
        assertEquals(DiscoveryState.CAPACITY_REACHED, f.coordinator.status().state)
        assertEquals(DiscoveryError.CAPACITY_REACHED, f.coordinator.status().lastError)
        assertNotNull(backing.transaction { checkpoint("session") }); assertNull(backing.transaction { checkpoint("new-session") })
    }

    @Test fun cancellationAfterRetentionRollsBackPrunedHistoryResultsAndProgress() {
        val backing = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 2))
        lateinit var coordinator: VideoDiscoveryCoordinator
        var guardRuns = 0
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                val tx = object : DiscoveryTransaction by delegate {
                    override fun beforeCommit(check: () -> Unit) {
                        delegate.beforeCommit {
                            guardRuns++
                            coordinator.suspendImmediately()
                            check()
                        }
                    }
                }
                tx.block()
            }
        }
        val f = Fixture(store = store); coordinator = f.coordinator
        backing.transaction {
            putSeen(SeenRecord(f.profile.profileId, "old-a", 1, savedAt = 2))
            putSeen(SeenRecord(f.profile.profileId, "old-b", 1, playedAt = 3))
        }
        f.start(); f.runtime.complete(0, 1, nextCursor = "not-committed", exhausted = false)
        assertEquals(1, guardRuns)
        backing.transaction {
            assertEquals(2, historyStatistics().seenAliasCount)
            assertEquals(2L, seen(f.profile.profileId, "old-a")!!.savedAt)
            assertEquals(3L, seen(f.profile.profileId, "old-b")!!.playedAt)
            assertNull(seen(f.profile.profileId, result(1).fingerprint))
            assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
            assertNull(progress(f.profile.profileId, "p1", f.profile.queryKey))
        }
    }

    private fun progress(profile: SearchProfile, provider: String, cursor: String) =
        ProviderProgress(profile.profileId, provider, profile.queryKey, "1", 1, cursor, null, false, 1)
}
