package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

/** M8 regressions use only deterministic local providers, clocks and transactional stores. */
class DiscoveryQualityCoordinatorTest {
    private class CountingStore : DiscoveryStore {
        val backing = InMemoryDiscoveryStore()
        var transactions = 0
        var mutations = 0

        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
            transactions++
            return backing.transaction {
                val delegate = this
                val counted = object : DiscoveryTransaction by delegate {
                    override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) {
                        mutations++; delegate.putCheckpoint(checkpoint)
                    }
                    override fun removeCheckpoint(sessionId: String) {
                        mutations++; delegate.removeCheckpoint(sessionId)
                    }
                    override fun putProgress(progress: ProviderProgress) {
                        mutations++; delegate.putProgress(progress)
                    }
                    override fun resetProgress(profileId: String, providerId: String?, queryKey: String?) {
                        mutations++; delegate.resetProgress(profileId, providerId, queryKey)
                    }
                    override fun putSeen(record: SeenRecord) {
                        mutations++; delegate.putSeen(record)
                    }
                    override fun clearSeen(profileId: String?) {
                        mutations++; delegate.clearSeen(profileId)
                    }
                }
                counted.block()
            }
        }
    }

    private class Fixture(
        val providers: List<VideoSearchProvider> = listOf(StubProvider(ProviderDescriptor("p1", "1"))),
        val limits: DiscoveryLimits = DiscoveryLimits(),
        val store: DiscoveryStore = InMemoryDiscoveryStore(),
    ) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val registry = ProviderRegistry(providers)
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock, limits) { 1000 }
        val profile = SearchProfile("profile", "query")

        fun start(value: SearchProfile = profile) {
            coordinator.setVisibility(true, true)
            coordinator.start(SESSION, value)
        }

        fun deliver(index: Int, results: List<VideoSearchResult>, cursor: String? = null,
                    exhausted: Boolean = true, expiresAt: Long? = null) {
            runtime.calls[index].callback(ProviderOutcome.Success(
                ValidatedProviderPage(results, cursor, expiresAt, exhausted)))
        }

        fun exhaustPagingYield() {
            runtime.complete(0, nextCursor = "empty-1", exhausted = false)
            runtime.complete(1, nextCursor = "empty-2", exhausted = false)
            assertEquals(2, runtime.calls.size)
            assertEquals(DiscoveryError.NO_NEW_RESULTS, coordinator.status().health.getValue("p1").lastError)
        }

        fun restoreFullPool(pinnedCount: Int) {
            val stamp = SessionStamp(SESSION, 1)
            val pool = ResultPool(limits.poolCapacity)
            var state = pool.admit(PoolState(), (1..limits.poolCapacity).map { result(it) }, clock.now).state
            repeat(pinnedCount) { index ->
                state = pool.materialize(state, stamp, index, 1, RepeatPolicy.SHOW_ALL) { null }.first
            }
            store.transaction { putCheckpoint(DiscoveryCheckpoint(stamp, profile, state)) }
            assertTrue(coordinator.restore(SESSION))
            coordinator.setVisibility(true, true)
        }

        fun progress(): ProviderProgress? = store.transaction { progress(profile.profileId, "p1", profile.queryKey) }
        fun provider(id: String = "p1") = coordinator.status().providers.single { it.providerId == id }
    }

    @Test fun uniqueReplacementsAtCapacityCountAsNewEvenWhenInventoryDoesNotGrow() {
        val f = Fixture(limits = DiscoveryLimits(pageSize = 1, poolCapacity = 5))
        f.restoreFullPool(pinnedCount = 4)
        val pinned = f.coordinator.currentCheckpoint()!!.pool.pages
        assertEquals(1, f.coordinator.status().availableCount)
        f.runtime.complete(0, 6, nextCursor = "next-1", exhausted = false)
        f.runtime.complete(1, 7, nextCursor = "next-2", exhausted = false)

        assertEquals("Both unique replacements must allow continued replenishment", 3, f.runtime.calls.size)
        assertEquals(1, f.coordinator.status().availableCount)
        assertNull(f.coordinator.status().health.getValue("p1").lastError)
        val state = f.coordinator.currentCheckpoint()!!.pool
        assertEquals(pinned, state.pages)
        assertEquals(5, state.entries.size)
        assertTrue(state.entries.any { it.result.resultId == result(7).resultId })
        assertFalse(state.entries.any { it.result.resultId == result(6).resultId })
    }

    @Test fun noNewCooldownDoesNotBlockAnotherQuery() {
        val f = Fixture(); f.start(); f.exhaustPagingYield()
        f.coordinator.start(SESSION, f.profile.copy(query = "another query"))

        assertEquals(3, f.runtime.calls.size)
        assertNull(f.runtime.calls.last().request.progress)
        assertEquals(ProviderDiscoveryState.REQUESTING, f.provider().state)
        assertNull(f.coordinator.status().health.getValue("p1").lastError)
    }

    @Test fun changingProviderVersionStartsANewConsecutiveNoNewSequence() {
        val f = Fixture(); f.start(); f.exhaustPagingYield()
        (f.providers.single() as StubProvider).descriptor = ProviderDescriptor("p1", "2")
        f.coordinator.requestMore()
        assertEquals(3, f.runtime.calls.size)
        assertNull(f.runtime.calls[2].request.progress)

        f.runtime.complete(2, nextCursor = "v2-first", exhausted = false)
        assertEquals("The first empty page of v2 must not inherit v1's two-page streak", 4, f.runtime.calls.size)
        assertNull(f.coordinator.status().health.getValue("p1").lastError)
        f.runtime.complete(3, nextCursor = "v2-second", exhausted = false)
        assertEquals(4, f.runtime.calls.size)
        assertEquals(DiscoveryError.NO_NEW_RESULTS, f.coordinator.status().health.getValue("p1").lastError)
        assertEquals("2", f.coordinator.status().health.getValue("p1").cooldownProviderVersion)
    }

    @Test fun repeatedCursorCooldownIsQueryScoped() {
        val f = Fixture(); f.start()
        f.runtime.complete(0, 1, nextCursor = "same", exhausted = false)
        f.runtime.complete(1, 2, nextCursor = "same", exhausted = false)
        assertEquals(DiscoveryError.REPEATED_CURSOR, f.coordinator.status().health.getValue("p1").lastError)

        f.coordinator.start(SESSION, f.profile.copy(query = "different"))
        assertEquals(3, f.runtime.calls.size)
        assertNull(f.coordinator.status().health.getValue("p1").lastError)
    }

    @Test fun explicitProviderExhaustionDoesNotBecomeAnEmptyPageCooldown() {
        val f = Fixture(); f.start()
        f.runtime.complete(0, nextCursor = "first-empty", exhausted = false)
        f.runtime.complete(1, exhausted = true)

        assertEquals(2, f.runtime.calls.size)
        assertEquals(DiscoveryState.EXHAUSTED, f.coordinator.status().state)
        assertEquals(ProviderDiscoveryState.EXHAUSTED, f.provider().state)
        assertNull(f.provider().retryAtEpochMillis)
        assertNull(f.provider().lastError)
        f.coordinator.requestMore()
        assertEquals(2, f.runtime.calls.size)
    }

    @Test fun serverRateLimitSurvivesProgressResetQueryChangeAndProviderVersionChange() {
        val f = Fixture(); f.start()
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 90_000))
        f.coordinator.resetProgress(f.profile.profileId, "p1")
        f.coordinator.requestMore()
        (f.providers.single() as StubProvider).descriptor = ProviderDescriptor("p1", "2")
        f.coordinator.start(SESSION, f.profile.copy(query = "other"))

        assertEquals(1, f.runtime.calls.size)
        assertEquals(90_000L, f.provider().retryAtEpochMillis)
        assertEquals(ProviderDiscoveryState.COOLDOWN, f.provider().state)
        f.timer.advance(88_999); f.coordinator.requestMore()
        assertEquals(1, f.runtime.calls.size)
        f.timer.advance(1); f.coordinator.requestMore()
        assertEquals(2, f.runtime.calls.size)
    }

    @Test fun inDeadlineServerRetryReplacesAnyEarlierQueryScopedPagingCooldown() {
        val f = Fixture(); f.start(); f.exhaustPagingYield()
        f.coordinator.start(SESSION, f.profile.copy(query = "query B"))
        f.runtime.calls[2].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 6_000))
        assertEquals(ProviderDiscoveryState.RETRY_WAIT, f.provider().state)
        f.coordinator.start(SESSION, f.profile.copy(query = "query C"))

        assertEquals("Changing query must not bypass a server Retry-After", 3, f.runtime.calls.size)
        assertEquals(6_000L, f.provider().retryAtEpochMillis)
        assertNull(f.coordinator.status().health.getValue("p1").cooldownQueryKey)
        f.timer.advance(4_999); f.coordinator.requestMore()
        assertEquals(3, f.runtime.calls.size)
        f.timer.advance(1); f.coordinator.requestMore()
        assertEquals(4, f.runtime.calls.size)
    }

    @Test fun progressResetClearsPagingCooldownAndCounterButPreservesSeenFacts() {
        val f = Fixture(); f.start(); f.exhaustPagingYield()
        val facts = SeenRecord(f.profile.profileId, result(99).fingerprint, 1, openedAt = 2, savedAt = 3)
        f.store.transaction { putSeen(facts) }
        f.coordinator.resetProgress(f.profile.profileId, "p1")

        assertNull(f.progress())
        assertEquals(ProviderDiscoveryState.NOT_STARTED, f.provider().state)
        assertFalse(f.provider().hasContinuation)
        f.store.transaction { assertEquals(facts, seen(f.profile.profileId, facts.fingerprint)) }
        f.coordinator.requestMore()
        assertEquals(3, f.runtime.calls.size)
        assertNull(f.runtime.calls[2].request.progress)
        f.runtime.complete(2, nextCursor = "reset-first", exhausted = false)
        assertEquals("Reset must clear the consecutive empty-page counter", 4, f.runtime.calls.size)
        assertNull(f.coordinator.status().health.getValue("p1").lastError)
    }

    @Test fun materializingAfterCooldownReportsTheNewLiveRound() {
        val f = Fixture(); f.start()
        f.runtime.complete(0, 1, nextCursor = "next", exhausted = false)
        f.runtime.fail(1)
        assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
        f.timer.advance(30_000)
        assertNotNull(f.coordinator.page(0))

        assertEquals(3, f.runtime.calls.size)
        assertEquals(1, f.coordinator.status().inFlightCount)
        assertEquals(DiscoveryState.FILLING, f.coordinator.status().state)
        assertEquals(ProviderDiscoveryState.REQUESTING, f.provider().state)
    }

    @Test fun changingModeAfterCooldownReportsTheNewLiveRound() {
        val f = Fixture(); f.start(); f.runtime.fail(0)
        f.timer.advance(30_000)
        f.coordinator.setMode(DiscoveryMode.POWER_SAVING)

        assertEquals(2, f.runtime.calls.size)
        assertEquals(DiscoveryState.FILLING, f.coordinator.status().state)
        assertEquals(1, f.runtime.calls.last().operation.context.resourceBudget.maxConcurrentWork)
    }

    @Test fun clearingHistoryMakesPreviouslyFilteredInventoryBufferedWithoutNewRequests() {
        val f = Fixture()
        f.store.transaction {
            (1..40).forEach { putSeen(SeenRecord(f.profile.profileId, result(it).fingerprint, 1, shownAt = 2)) }
        }
        f.start(f.profile.copy(historyPolicy = RepeatPolicy.HIDE_SHOWN))
        f.runtime.complete(0, *(1..40).toList().toIntArray())
        assertEquals(DiscoveryState.EXHAUSTED, f.coordinator.status().state)
        assertEquals(0, f.coordinator.status().availableCount)

        f.coordinator.clearHistory(f.profile.profileId)
        assertEquals(40, f.coordinator.status().availableCount)
        assertEquals(DiscoveryState.BUFFERED, f.coordinator.status().state)
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun rejectedProviderDoesNotMakeHealthyAggregateUnsupported() {
        val rejected = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("rejected", "1")
            override fun refusal(profile: SearchProfile) = DiscoveryError.SOURCE_CONFIGURATION
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("Refused source must not run")
        }
        val f = Fixture(providers = listOf(rejected, StubProvider(ProviderDescriptor("healthy", "1"))))
        f.start()
        assertEquals(1, f.runtime.calls.size)
        assertEquals("healthy", f.runtime.calls.single().provider.descriptor.id)
        assertEquals(DiscoveryState.FILLING, f.coordinator.status().state)
        assertEquals(ProviderDiscoveryState.BLOCKED, f.provider("rejected").state)
        f.runtime.complete(0, *(1..100).toList().toIntArray())

        assertEquals(DiscoveryState.BUFFERED, f.coordinator.status().state)
        assertEquals(ProviderDiscoveryState.EXHAUSTED, f.provider("healthy").state)
        assertEquals(0, f.provider("healthy").failureCount)
        repeat(4) { assertNotNull(f.coordinator.page(it)) }
        assertEquals(DiscoveryState.EXHAUSTED, f.coordinator.status().state)
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun fullyPinnedPoolRejectsFurtherDemandBeforeAnyRequest() {
        val f = Fixture(limits = DiscoveryLimits(pageSize = 1, poolCapacity = 5))
        f.restoreFullPool(pinnedCount = 5)
        val pages = f.coordinator.currentCheckpoint()!!.pool.pages
        repeat(3) { f.coordinator.requestMore() }

        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(DiscoveryState.CAPACITY_REACHED, f.coordinator.status().state)
        assertEquals(DiscoveryError.CAPACITY_REACHED, f.coordinator.status().lastError)
        assertEquals(pages, f.coordinator.currentCheckpoint()!!.pool.pages)
        assertEquals(0, f.coordinator.status().inFlightCount)
    }

    @Test fun rapidImmediateSuspendResumeRetiresLateCancellationAndSuccess() {
        val f = Fixture(); f.start()
        val old = f.runtime.calls[0]
        f.coordinator.suspendImmediately()
        f.coordinator.setVisibility(true, true)
        assertTrue(old.operation.cancellation.isCancelled)
        assertEquals(2, f.runtime.calls.size)

        f.runtime.fail(0, code = DiscoveryError.CANCELLED)
        f.runtime.complete(0, 99, nextCursor = "late")
        assertEquals(DiscoveryState.FILLING, f.coordinator.status().state)
        assertEquals(0, f.provider().failureCount)
        assertNull(f.provider().lastError)
        assertEquals(0, f.coordinator.status().availableCount)
        assertNull(f.progress())
        f.runtime.complete(1, 2)
        assertEquals(listOf(result(2).resultId), f.coordinator.currentCheckpoint()!!.pool.entries.map { it.result.resultId })
    }

    @Test fun revisingSortPreservesCurrentAnchorRejectsLateCallbackAndKeepsCursor() {
        val f = Fixture(limits = DiscoveryLimits(pageSize = 2))
        f.start()
        f.deliver(0, (1..8).map { result(it).copy(publishedAtEpochMillis = it * 1000L) }, "continue", false)
        assertNotNull(f.coordinator.page(0))
        val oldAnchor = f.coordinator.page(1)!!
        val queryKey = f.coordinator.currentCheckpoint()!!.profile.queryKey
        assertEquals(2, f.runtime.calls.size)
        f.coordinator.reviseView(RepeatPolicy.SHOW_ALL, pageSize = 2, currentPageIndex = 1, sortOrder = ResultSort.NEWEST)

        val anchor = f.coordinator.page(0)!!
        assertEquals(oldAnchor.orderedResultIds, anchor.orderedResultIds)
        assertEquals(oldAnchor.revision + 1, anchor.revision)
        assertEquals(queryKey, f.coordinator.currentCheckpoint()!!.profile.queryKey)
        assertTrue(f.runtime.calls[1].cancelled)
        f.runtime.complete(1, 99, nextCursor = "stale-sort", exhausted = false)
        assertEquals("continue", f.progress()!!.cursor)
        assertFalse(f.coordinator.recordSeen(oldAnchor, oldAnchor.orderedResultIds.first(), SeenEvent.SHOWN))
        val second = f.coordinator.page(1)!!
        assertEquals(listOf(result(8).resultId, result(7).resultId), second.orderedResultIds)
        assertNotNull(f.coordinator.page(2))
        assertEquals(3, f.runtime.calls.size)
        assertEquals("continue", f.runtime.calls[2].request.progress!!.cursor)
        assertEquals(ResultSort.NEWEST, f.runtime.calls[2].request.profile.sortOrder)
        f.deliver(2, listOf(result(10).copy(publishedAtEpochMillis = 10_000L)))
        assertEquals(anchor, f.coordinator.page(0))
        assertEquals(second, f.coordinator.page(1))
        assertFalse(f.coordinator.currentCheckpoint()!!.pool.entries.any { it.result.resultId == result(99).resultId })
    }

    @Test fun providerProgressPollingUsesCachedFactsAndDoesNotResetExpiredOrIncompatibleCursor() {
        val store = CountingStore()
        val f = Fixture(store = store); f.start()
        assertEquals(ProviderDiscoveryState.REQUESTING, f.provider().state)
        f.deliver(0, (1..100).map { result(it) }, "opaque-continuation", false, expiresAt = 2_000)
        val ready = f.provider()
        assertEquals(ProviderDiscoveryState.READY, ready.state)
        assertTrue(ready.hasContinuation)
        assertEquals(1_000L, ready.lastSuccessAtEpochMillis)
        assertFalse(ready.toString().contains("opaque-continuation"))
        val transactions = store.transactions
        val mutations = store.mutations
        repeat(100) { assertEquals(ready, f.provider()) }
        assertEquals(transactions, store.transactions)
        assertEquals(mutations, store.mutations)
        assertEquals(1, f.runtime.calls.size)

        (f.providers.single() as StubProvider).descriptor = ProviderDescriptor("p1", "2")
        assertEquals(ProviderDiscoveryState.CURSOR_INCOMPATIBLE, f.provider().state)
        assertFalse(f.provider().hasContinuation)
        (f.providers.single() as StubProvider).descriptor = ProviderDescriptor("p1", "1")
        f.timer.advance(1_000)
        assertEquals(ProviderDiscoveryState.CURSOR_EXPIRED, f.provider().state)
        assertFalse(f.provider().hasContinuation)
        assertEquals(transactions, store.transactions)
        assertEquals(mutations, store.mutations)
        store.backing.transaction {
            assertEquals("opaque-continuation", progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor)
        }
        f.coordinator.resetProgress(f.profile.profileId, "p1")
        assertEquals(ProviderDiscoveryState.NOT_STARTED, f.provider().state)
        assertNull(f.provider().lastSuccessAtEpochMillis)
        assertFalse(f.provider().hasContinuation)
        val afterResetReads = store.transactions
        val afterResetMutations = store.mutations
        repeat(100) { f.coordinator.status() }
        assertEquals(afterResetReads, store.transactions)
        assertEquals(afterResetMutations, store.mutations)
    }

    @Test fun progressSnapshotsReflectRegistryChangesWithoutQueryingOrMutatingSources() {
        val store = CountingStore()
        val f = Fixture(store = store); f.start()
        f.runtime.complete(0, *(1..100).toList().toIntArray())
        assertEquals(listOf("p1"), f.coordinator.status().providers.map { it.providerId })
        val mutations = store.mutations
        f.registry.register(StubProvider(ProviderDescriptor("p2", "1")))
        val added = f.coordinator.status().providers
        assertEquals(listOf("p1", "p2"), added.map { it.providerId })
        assertEquals(ProviderDiscoveryState.NOT_STARTED, added.single { it.providerId == "p2" }.state)
        val reads = store.transactions
        repeat(20) { f.coordinator.status() }
        assertEquals(reads, store.transactions)
        assertEquals(mutations, store.mutations)
        assertEquals(1, f.runtime.calls.size)
        f.registry.remove("p1")
        assertEquals(listOf("p2"), f.coordinator.status().providers.map { it.providerId })
        assertEquals(mutations, store.mutations)
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun statusExpiresBackoffWithoutCreatingAutomaticDemand() {
        val f = Fixture(); f.start(); f.exhaustPagingYield()
        val calls = f.runtime.calls.size
        f.timer.advance(f.limits.cooldownMillis)
        repeat(20) {
            val status = f.coordinator.status()
            assertEquals(DiscoveryState.EXHAUSTED, status.state)
            assertEquals(ProviderDiscoveryState.READY, status.providers.single().state)
            assertNull(status.providers.single().retryAtEpochMillis)
        }
        assertEquals(calls, f.runtime.calls.size)
    }

    private companion object { const val SESSION = "quality-session" }
}
