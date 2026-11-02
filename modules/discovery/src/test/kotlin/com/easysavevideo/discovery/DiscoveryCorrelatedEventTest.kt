package com.easysavevideo.discovery

import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

/** Pure host contracts for correlated events and scheduling; no Android runtime is exercised. */
class DiscoveryCorrelatedEventTest {
    private class Fixture(
        providerCount: Int = 1,
        val store: DiscoveryStore = InMemoryDiscoveryStore(),
        limits: DiscoveryLimits = DiscoveryLimits(),
    ) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val providers = (1..providerCount).map { StubProvider(ProviderDescriptor("p$it", "1")) }
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(providers), runtime, store, timer, clock, limits) { 1000 }
        val profile = SearchProfile("profile", "query")
        fun start() { coordinator.setVisibility(true, true); coordinator.start("session", profile) }
        fun materialize(vararg ids: Int): PageSnapshot {
            start()
            runtime.complete(0, *ids)
            return coordinator.page(0)!!
        }
    }

    /** Hooks only the actual transaction's read/last guard, leaving its atomic staging intact. */
    private class HookStore(val backing: InMemoryDiscoveryStore = InMemoryDiscoveryStore()) : DiscoveryStore {
        var checkpointRead: ((DiscoveryCheckpoint?) -> DiscoveryCheckpoint?)? = null
        var beforeGuard: ((DiscoveryTransaction) -> Unit)? = null
        var transactionCount = 0
        var guardCount = 0
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
            transactionCount++
            return backing.transaction {
                val delegate = this
                val proxy = object : DiscoveryTransaction by delegate {
                    override fun checkpoint(sessionId: String): DiscoveryCheckpoint? {
                        val value = delegate.checkpoint(sessionId)
                        val transform = checkpointRead
                        return if (transform == null) value else transform(value)
                    }
                    override fun beforeCommit(check: () -> Unit) {
                        delegate.beforeCommit {
                            guardCount++
                            beforeGuard?.invoke(delegate)
                            check()
                        }
                    }
                }
                proxy.block()
            }
        }
    }

    @Test fun currentSavedEventAddsOnlySavedAndKeepsDiscoveryPagesAndProgress() {
        val f = Fixture()
        val page = f.materialize(1)
        val candidate = f.coordinator.results(page).single()
        val before = facts(f.store, f.profile.profileId, candidate.identityAliases + candidate.fingerprint)
        val checkpoint = f.coordinator.currentCheckpoint()
        val progress = f.store.transaction { progress(f.profile.profileId, "p1", f.profile.queryKey) }
        assertTrue(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 2000))
        val after = facts(f.store, f.profile.profileId, before.keys)
        before.forEach { (alias, original) -> assertEquals(original!!.copy(savedAt = 2000), after[alias]) }
        assertEquals(checkpoint, f.coordinator.currentCheckpoint())
        assertEquals(progress, f.store.transaction { progress(f.profile.profileId, "p1", f.profile.queryKey) })
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun savedAliasesMergeIndependentMaximaAcrossHistoricalGroupWithoutRegressing() {
        val f = Fixture()
        val page = f.materialize(1)
        val candidate = f.coordinator.results(page).single()
        val aliases = candidate.identityAliases + candidate.fingerprint + "historical-alias"
        f.store.transaction {
            putSeen(SeenRecord(f.profile.profileId, candidate.fingerprint, 10,
                shownAt = 50, openedAt = 70, savedAt = 120, dismissedAt = 90))
            putSeen(SeenRecord(f.profile.profileId, "historical-alias", 5,
                shownAt = 60, playedAt = 80, savedAt = 150, dismissedAt = 85))
            linkSeenAliases(f.profile.profileId, aliases)
            putSeen(SeenRecord("other-profile", candidate.fingerprint, 3, playedAt = 4))
        }
        val original = facts(f.store, f.profile.profileId, aliases)
        assertTrue(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 140))
        assertEquals(original, facts(f.store, f.profile.profileId, aliases))
        assertTrue(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 200))
        aliases.forEach { alias ->
            assertEquals(SeenRecord(f.profile.profileId, alias, 5, 60, 70, 80, 200, 90),
                f.store.transaction { seen(f.profile.profileId, alias) })
        }
        assertEquals(SeenRecord("other-profile", candidate.fingerprint, 3, playedAt = 4),
            f.store.transaction { seen("other-profile", candidate.fingerprint) })
    }

    @Test fun cancelledCorrelationBeforeEntryDoesNotEvenOpenTransaction() {
        val store = HookStore()
        val f = Fixture(store = store)
        val page = f.materialize(1)
        val transactions = store.transactionCount
        val guards = store.guardCount
        assertFalse(f.coordinator.recordSeen(page, page.orderedResultIds.single(), SeenEvent.SAVED,
            stillCurrent = { false }))
        assertEquals(transactions, store.transactionCount)
        assertEquals(guards, store.guardCount)
        assertNull(f.coordinator.status().lastError)
    }

    @Test fun cancellationAtPersistedRecheckLeavesFactsAndStateUnchanged() {
        val store = HookStore()
        val f = Fixture(store = store)
        val page = f.materialize(1)
        val candidate = f.coordinator.results(page).single()
        val original = facts(store.backing, f.profile.profileId, candidate.identityAliases)
        val before = f.coordinator.status()
        val guards = store.guardCount
        var checks = 0
        assertFalse(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED,
            stillCurrent = { ++checks == 1 }))
        assertEquals(2, checks)
        assertEquals(guards, store.guardCount)
        assertEquals(original, facts(store.backing, f.profile.profileId, candidate.identityAliases))
        assertEquals(before, f.coordinator.status())
    }

    @Test fun eachForgedPageFieldIsRejectedEvenWhenStampRevisionAndFingerprintOtherwiseMatch() {
        val f = Fixture()
        val page = f.materialize(1, 2)
        val fingerprint = page.orderedResultIds.first()
        val original = facts(f.store, f.profile.profileId, f.coordinator.results(page).flatMap { it.identityAliases }.toSet())
        val forgeries = listOf(
            page.copy(stamp = page.stamp.copy(sessionId = "other-session")),
            page.copy(stamp = page.stamp.copy(epoch = page.stamp.epoch + 1)),
            page.copy(revision = page.revision + 1),
            page.copy(pageIndex = page.pageIndex + 1),
            page.copy(pageSize = page.pageSize + 1),
            page.copy(orderedResultIds = page.orderedResultIds.reversed()),
            page.copy(orderedResultIds = listOf(fingerprint)),
            page.copy(orderedResultIds = page.orderedResultIds + fingerprint),
        )
        forgeries.forEachIndexed { index, forged ->
            assertFalse("Forged page $index must not authorize an event",
                f.coordinator.recordSeen(forged, fingerprint, SeenEvent.SAVED, 2000))
        }
        assertEquals(original, facts(f.store, f.profile.profileId, original.keys))
        assertNull(f.coordinator.status().lastError)
        assertTrue(f.coordinator.recordSeen(page.copy(orderedResultIds = page.orderedResultIds.toList()),
            fingerprint, SeenEvent.SAVED, 2000))
    }

    @Test fun pooledAndOtherPageResultsCannotBorrowAMaterializedPagesAuthority() {
        val f = Fixture(limits = DiscoveryLimits(pageSize = 1))
        val first = f.materialize(1, 2, 3)
        val second = f.coordinator.page(1)!!
        assertFalse(f.coordinator.recordSeen(first, second.orderedResultIds.single(), SeenEvent.SAVED))
        assertFalse(f.coordinator.recordSeen(first, result(3).fingerprint, SeenEvent.SAVED))
        assertFalse(f.coordinator.recordSeen(first, "unknown-result", SeenEvent.SAVED))
        f.store.transaction {
            (1..3).forEach { assertNull(seen(f.profile.profileId, result(it).fingerprint)!!.savedAt) }
        }
    }

    @Test fun persistedStampRevisionProfileAndExactPageAreRecheckedBeforeAnyWrite() {
        val changes: List<(DiscoveryCheckpoint) -> DiscoveryCheckpoint?> = listOf(
            { null },
            { it.copy(stamp = it.stamp.copy(epoch = it.stamp.epoch + 1)) },
            { it.copy(stamp = it.stamp.copy(sessionId = "other-session")) },
            { it.copy(pool = it.pool.copy(viewRevision = it.pool.viewRevision + 1)) },
            { it.copy(profile = it.profile.copy(profileId = "other-profile")) },
            { it.copy(pool = it.pool.copy(pages = emptyList())) },
            { it.copy(pool = it.pool.copy(pages = it.pool.pages.map { page -> page.copy(pageSize = page.pageSize + 1) })) },
            { it.copy(pool = it.pool.copy(pages = it.pool.pages.map { page -> page.copy(orderedResultIds = emptyList()) })) },
        )
        changes.forEachIndexed { index, change ->
            val store = HookStore()
            val f = Fixture(store = store)
            val page = f.materialize(1)
            val candidate = f.coordinator.results(page).single()
            val original = facts(store.backing, f.profile.profileId, candidate.identityAliases)
            val checkpoint = store.backing.transaction { checkpoint("session") }
            val guards = store.guardCount
            store.checkpointRead = { it?.let(change) }
            assertFalse("Persisted mismatch $index must reject", f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED))
            store.checkpointRead = null
            assertEquals(guards, store.guardCount)
            assertEquals(original, facts(store.backing, f.profile.profileId, candidate.identityAliases))
            assertEquals(checkpoint, store.backing.transaction { checkpoint("session") })
            assertNull(f.coordinator.status().lastError)
        }
    }

    @Test fun historyClearRejectsCapturedSavedEventWithoutRepopulatingFacts() = rejectsOldSavedEvent {
        assertTrue(it.coordinator.clearHistory(it.profile.profileId))
    }

    @Test fun viewRevisionRejectsCapturedSavedEvent() = rejectsOldSavedEvent {
        assertTrue(it.coordinator.reviseView(RepeatPolicy.HIDE_WATCHED))
    }

    @Test fun newSearchRejectsCapturedSavedEvent() = rejectsOldSavedEvent {
        it.coordinator.start("session", it.profile.copy(query = "new query"))
    }

    @Test fun restoredEpochRejectsCapturedSavedEventWhileRetainingThePage() = rejectsOldSavedEvent {
        assertTrue(it.coordinator.restore("session"))
        assertNotNull(it.coordinator.page(0))
    }

    @Test fun closedCoordinatorRejectsCapturedSavedEvent() = rejectsOldSavedEvent {
        it.coordinator.close()
    }

    @Test fun cancellationInFinalPostRetentionGuardRollsBackAllFactsAndDoesNotReportStorageFailure() {
        finalGuardRollback(expire = false)
    }

    @Test fun correlationTtlExpiringInFinalPostRetentionGuardRollsBackAllFacts() {
        finalGuardRollback(expire = true)
    }

    @Test fun genuineStorageFailureIsFalseWithStorageStateAndNoPartialSavedFact() {
        val store = HookStore()
        val f = Fixture(store = store)
        val page = f.materialize(1)
        val candidate = f.coordinator.results(page).single()
        val original = facts(store.backing, f.profile.profileId, candidate.identityAliases)
        val checkpoint = f.coordinator.currentCheckpoint()
        val progress = store.backing.transaction { progress(f.profile.profileId, "p1", f.profile.queryKey) }
        store.beforeGuard = { error("injected storage failure") }
        assertFalse(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 2000))
        assertEquals(DiscoveryState.FAILED, f.coordinator.status().state)
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, f.coordinator.status().lastError)
        assertEquals(original, facts(store.backing, f.profile.profileId, candidate.identityAliases))
        assertEquals(checkpoint, f.coordinator.currentCheckpoint())
        assertEquals(checkpoint, store.backing.transaction { checkpoint("session") })
        assertEquals(progress, store.backing.transaction { progress(f.profile.profileId, "p1", f.profile.queryKey) })
    }

    @Test fun saveRecordOverlapAndDuplicatePriorityUpdatesResumeOnlyAfterBothAreInactive() {
        val f = Fixture()
        f.start()
        f.coordinator.setPriority(saveActive = true, recordingActive = false)
        assertTrue(f.runtime.calls.single().cancelled)
        val blocked = listOf(true to false, true to true, false to true, false to true)
        blocked.forEach { (save, record) ->
            f.coordinator.setPriority(save, record)
            f.coordinator.requestMore()
            assertEquals(1, f.runtime.calls.size)
            assertEquals(0, f.coordinator.status().inFlightCount)
            assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        }
        f.coordinator.setPriority(false, false)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.coordinator.status().inFlightCount)
        repeat(20) { f.coordinator.setPriority(false, false) }
        assertEquals(2, f.runtime.calls.size)
        // The canceled first attempt remains charged to the interrupted round.
        assertEquals(2, f.coordinator.status().requestsThisRound)
    }

    @Test fun releasingMediaPriorityDoesNotOverrideForegroundOrDiscoveryScreenVisibility() {
        listOf(false to true, true to false, false to false).forEach { (foreground, visible) ->
            val f = Fixture()
            f.start()
            f.coordinator.setPriority(true, false)
            f.coordinator.setVisibility(foreground, visible)
            f.coordinator.setPriority(false, false)
            repeat(10) { f.coordinator.setPriority(false, false); f.coordinator.requestMore() }
            assertEquals(1, f.runtime.calls.size)
            assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
            f.coordinator.setVisibility(true, true)
            assertEquals(2, f.runtime.calls.size)
            assertEquals(1, f.coordinator.status().inFlightCount)
        }
    }

    @Test fun savedCompletionCanArriveOffScreenButCannotManufactureShownOrPlayed() {
        val f = Fixture()
        val page = f.materialize(1)
        val fingerprint = page.orderedResultIds.single()
        f.coordinator.setVisibility(true, false)
        f.coordinator.setPriority(true, false)
        assertFalse(f.coordinator.recordSeen(page, fingerprint, SeenEvent.SHOWN, 2000))
        assertTrue(f.coordinator.recordSeen(page, fingerprint, SeenEvent.SAVED, 2000))
        f.store.transaction {
            val observed = seen(f.profile.profileId, fingerprint)!!
            assertEquals(2000L, observed.savedAt)
            assertNull(observed.shownAt)
            assertNull(observed.openedAt)
            assertNull(observed.playedAt)
            assertNull(observed.dismissedAt)
        }
        f.coordinator.setPriority(false, false)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun zeroSourcesSurvivesRepeatedPriorityAndVisibilityResumptionWithoutDemand() {
        val f = Fixture(providerCount = 0)
        f.start()
        repeat(20) {
            f.coordinator.setPriority(true, false)
            f.coordinator.setPriority(true, true)
            f.coordinator.setPriority(false, true)
            f.coordinator.setVisibility(true, false)
            f.coordinator.setPriority(false, false)
            f.coordinator.setVisibility(true, true)
            f.coordinator.setPriority(false, false)
            f.coordinator.status()
        }
        assertEquals(DiscoveryState.NO_SOURCES, f.coordinator.status().state)
        assertEquals(0, f.coordinator.status().requestsThisRound)
        assertEquals(0, f.coordinator.status().inFlightCount)
        assertTrue(f.runtime.calls.isEmpty())
        assertNull(f.coordinator.page(0))
    }

    @Test fun resumedProviderRoundIsBoundedAndOldSuccessOrFailureCannotPolluteIt() {
        val f = Fixture(providerCount = 2)
        f.start()
        f.coordinator.setPriority(true, false)
        assertTrue(f.runtime.calls.all { it.cancelled })
        f.coordinator.setPriority(false, false)
        assertEquals(4, f.runtime.calls.size)
        val before = f.coordinator.status()
        f.runtime.complete(0, 90, nextCursor = "old-success", exhausted = false)
        f.runtime.fail(1, retryable = true)
        assertEquals(before, f.coordinator.status())
        assertEquals(4, f.runtime.calls.size)
        f.store.transaction {
            assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
            assertNull(seen(f.profile.profileId, result(90).fingerprint))
            assertNull(progress(f.profile.profileId, "p1", f.profile.queryKey))
            assertNull(progress(f.profile.profileId, "p2", f.profile.queryKey))
        }
        for (index in 2..5) f.runtime.complete(index, index, nextCursor = "new-$index", exhausted = false)
        assertEquals(6, f.runtime.calls.size)
        assertEquals(6, f.coordinator.status().requestsThisRound)
        assertEquals(0, f.coordinator.status().inFlightCount)
        repeat(100) { f.coordinator.setPriority(false, false); f.coordinator.status() }
        f.timer.advance(60_000)
        assertEquals(6, f.runtime.calls.size)
        f.coordinator.status().health.values.forEach { assertEquals(0, it.failureCount); assertNull(it.lastError) }
        f.store.transaction {
            assertEquals((2..5).map { result(it).fingerprint }.toSet(), checkpoint("session")!!.pool.entries.map { it.result.fingerprint }.toSet())
            assertFalse(progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor == "old-success")
        }
    }

    @Test fun priorityCancellationRetiresScheduledRetriesAndLateFailuresAfterResume() {
        val f = Fixture()
        f.start()
        f.runtime.fail(0, retryable = true)
        assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
        f.coordinator.setPriority(false, true)
        f.timer.advance(1000)
        assertEquals(1, f.runtime.calls.size)
        f.coordinator.setPriority(false, false)
        assertEquals(2, f.runtime.calls.size)
        f.runtime.fail(0, retryable = true)
        f.timer.advance(4000)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.coordinator.status().inFlightCount)
        assertEquals(0, f.coordinator.status().health.getValue("p1").failureCount)
        f.runtime.complete(1, 1)
        assertNull(f.coordinator.status().lastError)
    }

    private fun rejectsOldSavedEvent(invalidate: (Fixture) -> Unit) {
        val f = Fixture()
        val page = f.materialize(1)
        val candidate = f.coordinator.results(page).single()
        invalidate(f)
        val original = facts(f.store, f.profile.profileId, candidate.identityAliases)
        val checkpoint = f.store.transaction { checkpoint("session") }
        assertFalse(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 2000))
        assertEquals(original, facts(f.store, f.profile.profileId, candidate.identityAliases))
        assertEquals(checkpoint, f.store.transaction { checkpoint("session") })
    }

    private fun finalGuardRollback(expire: Boolean) {
        val store = HookStore(InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 2)))
        val f = Fixture(store = store)
        f.materialize(1)
        assertTrue(f.coordinator.clearHistory(f.profile.profileId))
        val page = f.coordinator.page(0)!!
        val candidate = f.coordinator.results(page).single()
        val old = listOf(
            SeenRecord(f.profile.profileId, "old-a", 1, shownAt = 2, openedAt = 3, playedAt = 4, savedAt = 5, dismissedAt = 6),
            SeenRecord(f.profile.profileId, "old-b", 7, shownAt = 8, openedAt = 9, playedAt = 10, savedAt = 11, dismissedAt = 12),
        )
        store.backing.transaction { old.forEach(::putSeen) }
        val checkpoint = store.backing.transaction { checkpoint("session") }
        val progress = store.backing.transaction { progress(f.profile.profileId, "p1", f.profile.queryKey) }
        val counts = store.backing.transaction { historyStatistics() }
        val status = f.coordinator.status()
        val deadline = f.clock.now + 10
        var current = true
        var guarded = false
        val previousGuards = store.guardCount
        store.beforeGuard = { staged ->
            guarded = true
            // This is after retention: both previous unprotected rows have already been pruned.
            old.forEach { assertNull(staged.seen(it.profileId, it.fingerprint)) }
            assertEquals(2000L, staged.seen(f.profile.profileId, candidate.fingerprint)!!.savedAt)
            assertEquals(2, staged.historyStatistics().seenAliasCount)
            if (expire) f.clock.now = deadline else current = false
        }
        assertFalse(f.coordinator.recordSeen(page, candidate.fingerprint, SeenEvent.SAVED, 2000,
            stillCurrent = { current && f.clock.now < deadline }))
        assertTrue(guarded)
        assertEquals(previousGuards + 1, store.guardCount)
        store.backing.transaction {
            old.forEach { assertEquals(it, seen(it.profileId, it.fingerprint)) }
            candidate.identityAliases.forEach { assertNull(seen(f.profile.profileId, it)) }
            assertEquals(checkpoint, checkpoint("session"))
            assertEquals(progress, progress(f.profile.profileId, "p1", f.profile.queryKey))
            assertEquals(counts, historyStatistics())
        }
        assertEquals(checkpoint, f.coordinator.currentCheckpoint())
        assertEquals(status, f.coordinator.status())
        assertNull(f.coordinator.status().lastError)
    }

    private fun facts(store: DiscoveryStore, profile: String, aliases: Set<String>): Map<String, SeenRecord?> =
        store.transaction { aliases.associateWith { seen(profile, it) } }
}
