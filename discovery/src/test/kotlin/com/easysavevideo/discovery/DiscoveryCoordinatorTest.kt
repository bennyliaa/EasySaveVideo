package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCoordinatorTest {
    private class Fixture(providerCount: Int = 1, val store: DiscoveryStore = InMemoryDiscoveryStore(), limits: DiscoveryLimits = DiscoveryLimits()) {
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime()
        val providers = (1..providerCount).map { StubProvider(ProviderDescriptor("p$it", "1")) }
        val registry = ProviderRegistry(providers)
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock, limits) { if (it == 1) 1000 else 3000 }
        val profile = SearchProfile("profile", "query")
        fun start(incognito: Boolean = false) { coordinator.setVisibility(true, true); coordinator.start("session", profile, incognito) }
    }
    @Test fun zeroProvidersHasExplicitNoSourcesAndMakesNoRequests() {
        val f = Fixture(0); f.start()
        assertEquals(DiscoveryState.NO_SOURCES, f.coordinator.status().state); assertTrue(f.runtime.calls.isEmpty()); assertNull(f.coordinator.page(0))
    }
    @Test fun fastSourceFirstPageDoesNotWaitForSlowSibling() {
        val f = Fixture(2); f.start(); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(0, 1)
        val page = f.coordinator.page(0)!!
        assertEquals(1, page.orderedResultIds.size); assertEquals(1, f.coordinator.results(page).size)
        assertEquals(1, f.coordinator.status().inFlightCount)
        f.runtime.complete(1, 2)
        assertEquals(page, f.coordinator.page(0)); assertEquals(1, f.coordinator.page(1)!!.orderedResultIds.size)
    }
    @Test fun poolAndCursorCommitTogetherAndDiscoveredDoesNotMeanShown() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1, nextCursor = "next", exhausted = false)
        f.store.transaction {
            assertEquals("next", progress("profile", "p1", f.profile.queryKey)!!.cursor)
            assertEquals(1, checkpoint("session")!!.pool.entries.size)
            val seen = seen("profile", result(1).fingerprint)!!; assertNull(seen.shownAt); assertNull(seen.openedAt)
        }
        f.coordinator.page(0)
        f.store.transaction { assertNull(seen("profile", result(1).fingerprint)!!.shownAt) }
    }
    @Test fun changingSearchRejectsAllOldEpochResultsAndProgress() {
        val f = Fixture(); f.start(); val first = f.runtime.calls[0]
        f.coordinator.start("session", f.profile.copy(query = "new query"))
        assertTrue(first.cancelled); f.runtime.complete(0, 1, nextCursor = "old")
        assertEquals(0, f.coordinator.status().availableCount)
        f.store.transaction { assertNull(progress("profile", "p1", f.profile.queryKey)); assertTrue(checkpoint("session")!!.pool.entries.isEmpty()) }
        f.runtime.complete(1, 2); assertEquals(1, f.coordinator.status().availableCount)
    }
    @Test fun providerVersionChangeDiscardsLatePageAndRestartsOnlyThatSource() {
        val f = Fixture(); f.start(); f.providers[0].descriptor = ProviderDescriptor("p1", "2")
        f.runtime.complete(0, 1, nextCursor = "wrong")
        assertEquals(0, f.coordinator.status().availableCount); assertEquals(2, f.runtime.calls.size)
        assertNull(f.runtime.calls[1].request.progress)
        f.runtime.complete(1, 2)
        f.store.transaction { assertEquals("2", progress("profile", "p1", f.profile.queryKey)!!.providerVersion) }
    }
    @Test fun backgroundClosesGateCancelsAndRejectsLateEvenAfterResume() {
        val f = Fixture(); f.start(); f.coordinator.setVisibility(false, true)
        assertTrue(f.runtime.calls[0].cancelled); assertTrue(f.runtime.calls[0].operation.cancellation.isCancelled)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
        f.coordinator.setVisibility(true, true); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(0, 1, nextCursor = "old"); assertEquals(0, f.coordinator.status().availableCount)
        f.runtime.complete(1, 2); assertEquals(1, f.coordinator.status().availableCount)
    }
    @Test fun leavingDiscoveryAndSaveRecordPriorityCancelInFlight() {
        val f = Fixture(); f.start(); f.coordinator.setVisibility(true, false)
        assertTrue(f.runtime.calls[0].cancelled)
        f.coordinator.setVisibility(true, true); f.coordinator.setPriority(true, false)
        assertTrue(f.runtime.calls[1].cancelled); f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
        f.coordinator.setPriority(false, true); assertEquals(2, f.runtime.calls.size)
        f.coordinator.setPriority(false, false); assertEquals(3, f.runtime.calls.size)
    }
    @Test fun independentProviderFailureDoesNotCancelHealthySibling() {
        val f = Fixture(2); f.start(); f.runtime.fail(0)
        assertFalse(f.runtime.calls[1].cancelled); f.runtime.complete(1, 2)
        assertEquals(1, f.coordinator.status().availableCount)
        assertEquals(1, f.coordinator.status().health["p1"]!!.failureCount)
        assertEquals(0, f.coordinator.status().health["p2"]!!.failureCount)
    }
    @Test fun twoRetriesShareOriginalDeadlineTokenAndRoundBudget() {
        val f = Fixture(); f.start(); val original = f.runtime.calls[0].operation.context
        f.runtime.fail(0, true); f.timer.advance(999); assertEquals(1, f.runtime.calls.size)
        f.timer.advance(1); assertEquals(2, f.runtime.calls.size); f.runtime.fail(1, true)
        f.timer.advance(3000); assertEquals(3, f.runtime.calls.size); f.runtime.fail(2, true)
        f.timer.advance(10000); assertEquals(3, f.runtime.calls.size)
        assertEquals(listOf(0, 1, 2), f.runtime.calls.map { it.operation.context.attempt })
        f.runtime.calls.forEach { assertEquals(original.deadlineEpochMillis, it.operation.context.deadlineEpochMillis); assertSame(original.cancelToken, it.operation.context.cancelToken) }
        assertEquals(3, f.coordinator.status().requestsThisRound)
    }
    @Test fun retryBackoffCannotOutliveOriginalDeadline() {
        val f = Fixture(limits = DiscoveryLimits(requestDeadlineMillis = 500)); f.start(); f.runtime.fail(0, true)
        f.timer.advance(1000); assertEquals(1, f.runtime.calls.size)
        assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
    }
    @Test fun successfulLatePageCannotAdvanceProgressAfterDeadline() {
        val f = Fixture(); f.start(); f.clock.now = 16000; f.runtime.complete(0, 1, nextCursor = "late")
        assertEquals(0, f.coordinator.status().availableCount)
        f.store.transaction { assertNull(progress("profile", "p1", f.profile.queryKey)) }
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, f.coordinator.status().health["p1"]!!.lastError)
    }
    @Test fun twoEmptyPagesCooldownAndRepeatedCursorStopsEarly() {
        val f = Fixture(); f.start()
        f.runtime.complete(0, nextCursor = "a", exhausted = false); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(1, nextCursor = "b", exhausted = false); assertEquals(2, f.runtime.calls.size)
        assertEquals(DiscoveryError.NO_NEW_RESULTS, f.coordinator.status().health["p1"]!!.lastError)
        val g = Fixture(); g.start(); g.runtime.complete(0, 1, nextCursor = "same", exhausted = false)
        g.runtime.complete(1, 2, nextCursor = "same", exhausted = false)
        assertEquals(2, g.runtime.calls.size); assertEquals(DiscoveryError.REPEATED_CURSOR, g.coordinator.status().health["p1"]!!.lastError)
    }
    @Test fun roundStopsAfterSixPagesAndDoesNotRestartOnStatusReads() {
        val f = Fixture(); f.start()
        repeat(6) { f.runtime.complete(it, it + 1, nextCursor = "page${it + 1}", exhausted = false) }
        repeat(100) { f.coordinator.status() }
        f.timer.advance(600000)
        assertEquals(6, f.runtime.calls.size); assertEquals(6, f.coordinator.status().requestsThisRound)
    }
    @Test fun highWatermarkStopsNewWorkUntilMaterializationDrainsBelowLow() {
        val f = Fixture(); f.start(); f.runtime.complete(0, *(1..100).toList().toIntArray(), nextCursor = "next", exhausted = false)
        assertEquals(100, f.coordinator.status().availableCount); assertEquals(1, f.runtime.calls.size)
        repeat(3) { f.coordinator.page(it) }
        assertEquals(40, f.coordinator.status().availableCount); assertEquals(1, f.runtime.calls.size)
        f.coordinator.page(3)
        assertEquals(20, f.coordinator.status().availableCount); assertEquals(2, f.runtime.calls.size)
    }
    @Test fun aggressiveAndPowerSavingModesRespectGlobalBudget() {
        val f = Fixture(4); f.coordinator.setMode(DiscoveryMode.AGGRESSIVE); f.start(); assertEquals(3, f.runtime.calls.size)
        f.coordinator.setMode(DiscoveryMode.POWER_SAVING)
        assertTrue(f.runtime.calls.take(3).all { it.cancelled }); assertEquals(1, f.coordinator.status().inFlightCount)
    }
    @Test fun transactionFailureNeverPublishesPoolOrCursor() {
        val backing = InMemoryDiscoveryStore(); var fail = false
        val failing = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val result = block(); if (fail && checkpoint("session")!!.pool.entries.isNotEmpty()) error("injected commit failure"); result
            }
        }
        val f = Fixture(store = failing); f.start(); fail = true; f.runtime.complete(0, 1, nextCursor = "next")
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(1).fingerprint)) }
        assertEquals(DiscoveryState.FAILED, f.coordinator.status().state); assertEquals(0, f.coordinator.status().availableCount)
    }
    @Test fun expiredCursorResetsOnlyThatProviderAndRetainsSeen() {
        val f = Fixture(2)
        f.store.transaction {
            putProgress(ProviderProgress("profile", "p1", f.profile.queryKey, "1", 1, "expired", 999, false, 1))
            putProgress(ProviderProgress("profile", "p2", f.profile.queryKey, "1", 1, "valid", 2000, false, 1))
            putSeen(SeenRecord("profile", result(1).fingerprint, 1, shownAt = 2))
        }
        f.start(); assertNull(f.runtime.calls[0].request.progress); assertEquals("valid", f.runtime.calls[1].request.progress!!.cursor)
        f.store.transaction { assertNotNull(seen("profile", result(1).fingerprint)) }
    }
    @Test fun incognitoNeverWritesDurableStateAndIsClearedOnExit() {
        var transactions = 0
        val durable = object : DiscoveryStore { override fun <T> transaction(block: DiscoveryTransaction.() -> T): T { transactions++; error("durable access forbidden") } }
        val f = Fixture(store = durable); f.start(incognito = true); f.runtime.complete(0, 1)
        val page = f.coordinator.page(0)!!; assertTrue(f.coordinator.recordSeen(page, page.orderedResultIds.single(), SeenEvent.OPENED))
        f.coordinator.close(); assertEquals(0, transactions); assertTrue(f.coordinator.results(page).isEmpty())
    }
    @Test fun restoreRetainsCommittedPagesSeenAndProgressWithNewEpoch() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1); val page = f.coordinator.page(0)!!
        f.coordinator.recordSeen(page, page.orderedResultIds.single(), SeenEvent.OPENED)
        assertTrue(f.coordinator.restore("session")); val restored = f.coordinator.page(0)!!
        assertTrue(restored.stamp.epoch > page.stamp.epoch); assertEquals(page.orderedResultIds, restored.orderedResultIds)
        assertEquals(1, f.runtime.calls.size)
        f.store.transaction { assertNotNull(seen("profile", result(1).fingerprint)!!.openedAt) }
    }
    @Test fun strictPolicyChangesKeepCurrentPageVisibleAndOnlyAffectNewPages() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1, 2)
        val first = f.coordinator.page(0)!!
        first.orderedResultIds.forEach { f.coordinator.recordSeen(first, it, SeenEvent.SHOWN) }
        f.coordinator.reviseView(RepeatPolicy.HIDE_SHOWN)
        val revised = f.coordinator.page(0)!!; assertEquals(first.orderedResultIds, revised.orderedResultIds)
        assertEquals(first.revision + 1, revised.revision); assertNull(f.coordinator.page(1))
    }
    @Test fun duplicateCallbackCannotAppendOrAdvanceTwice() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1, nextCursor = "once")
        f.runtime.complete(0, 2, nextCursor = "twice")
        assertEquals(1, f.coordinator.status().availableCount)
        f.store.transaction { assertEquals("once", progress("profile", "p1", f.profile.queryKey)!!.cursor) }
    }

    @Test fun rereadingMaterializedPagesDoesNotRefillOrWrite() {
        val backing = InMemoryDiscoveryStore(); var transactions = 0
        val counting = object : DiscoveryStore { override fun <T> transaction(block: DiscoveryTransaction.() -> T): T { transactions++; return backing.transaction(block) } }
        val f = Fixture(store = counting); f.start()
        f.runtime.complete(0, 1, nextCursor = "1", exhausted = false)
        f.coordinator.page(0)
        for (i in 1..5) f.runtime.complete(i, i + 1, nextCursor = "${i + 1}", exhausted = false)
        val calls = f.runtime.calls.size; val writes = transactions
        repeat(100) { f.coordinator.page(0); f.coordinator.currentCheckpoint(); f.coordinator.status() }
        assertEquals(calls, f.runtime.calls.size)
        assertTrue("Only the first status may populate the inventory cache", transactions - writes <= 1)
    }
    @Test fun staleSeenEventsAreRejectedAfterNewEpochViewAndHistoryClear() {
        val f = Fixture(); f.start(); f.runtime.complete(0, 1); val oldPage = f.coordinator.page(0)!!
        f.coordinator.start("session", f.profile.copy(query = "other")); f.runtime.complete(1, 1)
        val newPage = f.coordinator.page(0)!!
        assertFalse(f.coordinator.recordSeen(oldPage, result(1).fingerprint, SeenEvent.SHOWN))
        assertTrue(f.coordinator.recordSeen(newPage, result(1).fingerprint, SeenEvent.SHOWN))
        f.coordinator.clearHistory("profile")
        assertFalse(f.coordinator.recordSeen(newPage, result(1).fingerprint, SeenEvent.SHOWN))
        val clearedPage = f.coordinator.page(0)!!
        assertTrue(f.coordinator.recordSeen(clearedPage, result(1).fingerprint, SeenEvent.SAVED))
        f.store.transaction { val facts = seen("profile", result(1).fingerprint)!!; assertNull(facts.shownAt); assertNotNull(facts.savedAt) }
        f.coordinator.reviseView(RepeatPolicy.SHOW_ALL)
        assertFalse(f.coordinator.recordSeen(clearedPage, result(1).fingerprint, SeenEvent.OPENED))
    }
    @Test fun identityEnrichmentMigratesFactsForLaterCanonicalUrlChanges() {
        val f = Fixture(); f.start()
        val urlOnly = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true), "p1")!!
        fun deliver(index: Int, result: VideoSearchResult, cursor: String, exhausted: Boolean) {
            f.runtime.calls[index].callback(ProviderOutcome.Success(ValidatedProviderPage(listOf(result), cursor, null, exhausted)))
        }
        deliver(0, urlOnly, "1", false)
        val page = f.coordinator.page(0)!!; f.coordinator.recordSeen(page, urlOnly.fingerprint, SeenEvent.SHOWN)
        val enriched = ResultNormalizer.normalize(RawVideoResult("https://video.example/a", true, sourceNamespace = "video", sourceVideoId = "shared"), "p1")!!
        deliver(1, enriched, "2", true)
        f.coordinator.start("next", f.profile.copy(historyPolicy = RepeatPolicy.HIDE_SHOWN))
        // Reset only the source checkpoint; keep Seen facts.
        f.coordinator.resetProgress("profile"); f.coordinator.requestMore()
        val alternate = ResultNormalizer.normalize(RawVideoResult("https://video.example/b", true, sourceNamespace = "video", sourceVideoId = "shared"), "p1")!!
        deliver(f.runtime.calls.lastIndex, alternate, "3", true)
        assertEquals(0, f.coordinator.status().availableCount)
        f.store.transaction { val facts = seen("profile", alternate.fingerprint)!!; assertNotNull(facts.shownAt); assertNull(facts.playedAt); assertNull(facts.savedAt) }
    }
    @Test fun missingRestoreClearsPreviousIncognitoView() {
        val f = Fixture(); f.start(true); f.runtime.complete(0, 1)
        assertFalse(f.coordinator.restore("missing")); assertNull(f.coordinator.currentCheckpoint()); assertNull(f.coordinator.page(0))
    }
    @Test fun corruptExistingCheckpointIsNeverOverwrittenByStart() {
        var writes = 0
        val broken = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = InMemoryDiscoveryStore().transaction {
                val delegate = this
                val tx = object : DiscoveryTransaction by delegate {
                    override fun checkpoint(sessionId: String): DiscoveryCheckpoint? = error("corrupt checkpoint")
                    override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) { writes++ }
                }; tx.block()
            }
        }
        val f = Fixture(store = broken)
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, assertThrows(DiscoveryFailure::class.java) { f.start() }.code)
        assertEquals(0, writes); assertEquals(0, f.runtime.calls.size)
    }
    @Test fun synchronousLifecycleGateDoesNotWaitForBlockedTransaction() {
        val backing = InMemoryDiscoveryStore(); val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val delayed = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                val tx = object : DiscoveryTransaction by delegate {
                    override fun putProgress(progress: ProviderProgress) {
                        entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS)); delegate.putProgress(progress)
                    }
                }; tx.block()
            }
        }
        val f = Fixture(store = delayed); f.start()
        val completed = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { try { f.runtime.complete(0, 1, nextCursor = "next") } finally { completed.countDown() } }.apply { start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val start = System.nanoTime(); f.coordinator.suspendImmediately()
            assertTrue((System.nanoTime() - start) / 1000000 < 100)
            assertTrue(f.runtime.calls[0].operation.cancellation.isCancelled)
        } finally { release.countDown() }
        assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS)); worker.join()
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)) }
    }

    @Test fun backgroundDuringProgressReadPreventsSubsequentLaunch() {
        val backing = InMemoryDiscoveryStore(); val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val delayed = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                val tx = object : DiscoveryTransaction by delegate {
                    override fun progress(profileId: String, providerId: String, queryKey: String): ProviderProgress? {
                        entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        return delegate.progress(profileId, providerId, queryKey)
                    }
                }; tx.block()
            }
        }
        val f = Fixture(store = delayed); val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { try { f.start() } finally { done.countDown() } }.apply { start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            f.coordinator.suspendImmediately()
        } finally { release.countDown() }
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS)); worker.join()
        assertTrue(f.runtime.calls.isEmpty()); assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
    }

    @Test fun loweringModeInvalidatesEvenSinglePendingRetryWithOldBudget() {
        val f = Fixture(); f.coordinator.setMode(DiscoveryMode.AGGRESSIVE); f.start()
        f.runtime.fail(0, retryable = true)
        assertEquals(3, f.runtime.calls[0].operation.context.resourceBudget.maxConcurrentWork)
        f.coordinator.setMode(DiscoveryMode.POWER_SAVING)
        assertTrue(f.runtime.calls[0].operation.cancellation.isCancelled)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.runtime.calls[1].operation.context.resourceBudget.maxConcurrentWork)
        f.timer.advance(1000); assertEquals(2, f.runtime.calls.size)
    }
}
