package com.easysavevideo.discovery

import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.storage.DiscoveryCheckpointCodec
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

/** Independently authored, ordinary bounded fake-clock/store fixtures for M8-B1. */
class IndependentHistoryReviewTest {
    private fun fact(profile: String, alias: String, first: Long = 10, shown: Long? = null,
                     opened: Long? = null, played: Long? = null, saved: Long? = null, dismissed: Long? = null) =
        SeenRecord(profile, alias, first, shown, opened, played, saved, dismissed)
    private fun progress(profile: SearchProfile, provider: String, cursor: String = "saved") =
        ProviderProgress(profile.profileId, provider, profile.queryKey, "1", 1, cursor, null, false, 1)
    private fun checkpoint(session: String, profile: SearchProfile, id: Int, materialized: Boolean = true): DiscoveryCheckpoint {
        val stamp = SessionStamp(session, 1)
        val pool = ResultPool()
        val admitted = pool.admit(PoolState(), listOf(result(id)), 10).state
        return DiscoveryCheckpoint(stamp, profile, if (materialized)
            pool.materialize(admitted, stamp, 0, 1, RepeatPolicy.SHOW_ALL) { null }.first else admitted)
    }
    private fun capacity(action: () -> Unit) {
        try { action(); fail("Expected atomic capacity refusal") }
        catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CAPACITY_REACHED, failure.code) }
    }
    private class Rig(val store: DiscoveryStore = InMemoryDiscoveryStore(), count: Int = 2) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry((1..count).map { StubProvider(ProviderDescriptor("p$it", "1")) }),
            runtime, store, timer, clock, DiscoveryLimits(pageSize = 1, poolCapacity = 10)) { 10L }
        val profile = SearchProfile("profile", "query", historyPolicy = RepeatPolicy.SHOW_ALL)
        fun start() { coordinator.setVisibility(true, true); coordinator.start("session", profile) }
    }

    @Test fun transitiveUnionMergesIndependentEventExtremaWithoutCrossProfileFacts() {
        val store = InMemoryDiscoveryStore()
        store.transaction {
            putSeen(fact("p", "z", first = 30, opened = 90)); putSeen(fact("p", "m", first = 20, saved = 50))
            putSeen(fact("p", "a", first = 40, dismissed = 60)); putSeen(fact("p", "c", first = 10, played = 70))
            putSeen(fact("other", "m", shown = 100))
            linkSeenAliases("p", setOf("z", "m")); linkSeenAliases("p", setOf("a", "c"))
            linkSeenAliases("p", setOf("m", "c", "unknown"))
            putSeen(fact("p", "z", first = 5, opened = 8, saved = 9))
        }
        store.transaction {
            listOf("a", "c", "m", "z").forEach { alias ->
                assertEquals(fact("p", alias, first = 5, opened = 90, played = 70, saved = 50, dismissed = 60), seen("p", alias))
            }
            assertNull(seen("p", "unknown")); assertEquals(100L, seen("other", "m")!!.shownAt)
            assertEquals(HistoryStatistics(2, 0, 2, 5, 0), historyStatistics())
        }
    }

    @Test fun unknownAliasLinkDoesNotInventOrRememberAnUnobservedFact() {
        val store = InMemoryDiscoveryStore()
        store.transaction { putSeen(fact("p", "a", saved = 80)); linkSeenAliases("p", setOf("a", "future")) }
        store.transaction { assertNull(seen("p", "future")); putSeen(fact("p", "future", opened = 90)) }
        store.transaction {
            assertNull(seen("p", "future")!!.savedAt); assertNull(seen("p", "a")!!.openedAt)
            assertEquals(2, historyStatistics().seenIdentityCount)
            linkSeenAliases("p", setOf("a", "future"))
        }
        store.transaction { assertEquals(80L, seen("p", "future")!!.savedAt); assertEquals(90L, seen("p", "a")!!.openedAt) }
    }

    @Test fun currentMaterializedAliasProtectsTransitiveHistoryBeyondItsCheckpointAliasList() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 4))
        val cp = checkpoint("current", SearchProfile("p", "query"), 1)
        val aliases = cp.pool.entries.single().result.identityAliases
        assertEquals(2, aliases.size)
        store.transaction {
            putCheckpoint(cp); aliases.forEach { putSeen(fact("p", it, opened = 40)) }
            putSeen(fact("p", "historical", saved = 50)); linkSeenAliases("p", aliases + "historical")
        }
        store.transaction { putSeen(fact("p", "new-one")); putSeen(fact("p", "new-two")); linkSeenAliases("p", setOf("new-one", "new-two")) }
        store.transaction {
            assertEquals(3, historyStatistics().seenAliasCount); assertEquals(cp, checkpoint("current"))
            (aliases + "historical").forEach { assertEquals(50L, seen("p", it)!!.savedAt) }
            assertNull(seen("p", "new-one")); assertNull(seen("p", "new-two"))
        }
    }

    @Test fun checkpointEvictionDoesNotRefreshItsRemainingProfileHistory() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 3, maxCheckpoints = 1))
        store.transaction { putSeen(fact("old", "a")); putCheckpoint(DiscoveryCheckpoint(SessionStamp("old-session", 1), SearchProfile("old", "one"))) }
        store.transaction { putSeen(fact("middle", "b")) }
        store.transaction { putCheckpoint(DiscoveryCheckpoint(SessionStamp("new-session", 1), SearchProfile("new", "two"))) }
        store.transaction { assertNull(checkpoint("old-session")); assertNotNull(seen("old", "a")) }
        store.transaction { putSeen(fact("fourth", "d")) }
        store.transaction { assertNull(seen("old", "a")); assertNotNull(seen("middle", "b")); assertNotNull(checkpoint("new-session")) }
    }

    @Test fun finalGuardObservesPrunedStateButFailureRestoresFactsSequencesAndCheckpoint() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 2, maxSeenAliases = 2))
        store.transaction { putSeen(fact("old", "a", saved = 20)) }
        store.transaction { putSeen(fact("middle", "b", played = 30)) }
        var guardRan = false
        try {
            store.transaction {
                putSeen(fact("new", "c")); putCheckpoint(DiscoveryCheckpoint(SessionStamp("s", 1), SearchProfile("new", "q")))
                beforeCommit {
                    guardRan = true; assertNull(seen("old", "a")); assertEquals(2, historyStatistics().seenAliasCount)
                    throw DiscoveryFailure(DiscoveryError.CANCELLED)
                }
            }
            fail("Guard should reject")
        } catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, failure.code) }
        assertTrue(guardRan)
        store.transaction { assertEquals(20L, seen("old", "a")!!.savedAt); assertNull(checkpoint("s")); assertNull(seen("new", "c")) }
        store.transaction { putSeen(fact("fourth", "d")) }
        store.transaction { assertNull(seen("old", "a")); assertEquals(30L, seen("middle", "b")!!.playedAt) }
    }

    @Test fun protectedProgressOverflowRollsBackEarlierAliasAndRevisionWrites() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProgress = 1))
        val cp = checkpoint("s", SearchProfile("p", "q"), 7)
        store.transaction { putCheckpoint(cp); putProgress(progress(cp.profile, "a")); putSeen(fact("p", "unrelated", saved = 15)) }
        capacity { store.transaction {
            clearSeen("p"); putSeen(fact("p", "new", played = 20)); putProgress(progress(cp.profile, "b"))
        } }
        store.transaction {
            assertEquals(cp, checkpoint("s")); assertEquals(15L, seen("p", "unrelated")!!.savedAt)
            assertNull(seen("p", "new")); assertNotNull(progress("p", "a", cp.profile.queryKey)); assertNull(progress("p", "b", cp.profile.queryKey))
        }
    }

    @Test fun clearEmptyAndPopulatedProfilesFencesOnlyMatchingCheckpointsAndRetainsIndependentData() {
        val store = InMemoryDiscoveryStore()
        val a = checkpoint("a", SearchProfile("p1", "q"), 1)
        val b = checkpoint("b", SearchProfile("p2", "q"), 2)
        store.transaction { putCheckpoint(a); putCheckpoint(b); putSeen(fact("p2", "history", saved = 60));
            putProgress(progress(a.profile, "source")); putProgress(progress(b.profile, "source")) }
        store.transaction { clearSeen("p1") }
        store.transaction {
            assertEquals(a.pool.viewRevision + 1, checkpoint("a")!!.pool.viewRevision)
            assertEquals(b, checkpoint("b")); assertEquals(60L, seen("p2", "history")!!.savedAt)
            clearSeen()
        }
        store.transaction {
            assertEquals(0, historyStatistics().seenAliasCount); assertEquals(2, historyStatistics().progressCount)
            assertEquals(a.pool.entries, checkpoint("a")!!.pool.entries); assertEquals(b.pool.entries, checkpoint("b")!!.pool.entries)
            assertEquals(2L, checkpoint("a")!!.pool.viewRevision); assertEquals(1L, checkpoint("b")!!.pool.viewRevision)
        }
    }

    @Test fun providerResetRetiresMatchingLateSuccessAndFailureWhileUnrelatedCompletionCommits() {
        val r = Rig(); r.start()
        val first = r.runtime.calls.indexOfFirst { it.provider.descriptor.id == "p1" }
        val second = r.runtime.calls.indexOfFirst { it.provider.descriptor.id == "p2" }
        r.store.transaction { putProgress(progress(r.profile, "p1")); putProgress(progress(r.profile.copy(profileId = "other"), "p1"));
            putSeen(fact("profile", "history", saved = 40)) }
        assertTrue(r.coordinator.resetProgress("profile", "p1"))
        r.runtime.complete(first, 91, nextCursor = "late", exhausted = false)
        r.runtime.fail(first, true)
        r.runtime.complete(second, 2, nextCursor = "keep", exhausted = false)
        r.timer.advance(40)
        assertEquals(2, r.runtime.calls.size); assertFalse(r.runtime.calls[second].cancelled)
        r.store.transaction {
            assertNull(progress("profile", "p1", r.profile.queryKey)); assertNotNull(progress("other", "p1", r.profile.queryKey))
            assertEquals("keep", progress("profile", "p2", r.profile.queryKey)!!.cursor)
            assertNull(seen("profile", result(91).fingerprint)); assertEquals(40L, seen("profile", "history")!!.savedAt)
        }
        r.coordinator.requestMore()
        assertNull(r.runtime.calls.drop(2).first { it.provider.descriptor.id == "p1" }.request.progress)
        r.coordinator.close()
    }

    @Test fun progressResetPreservesSourceWideRetryAfterUntilFakeClockAllowsIt() {
        val r = Rig(count = 1); r.start()
        val retryAt = r.clock.now + 60_000
        r.runtime.calls.single().callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, retryable = false, retryAtEpochMillis = retryAt))
        assertTrue(r.coordinator.resetProgress("profile", "p1")); r.coordinator.requestMore()
        assertEquals(1, r.runtime.calls.size)
        assertTrue(r.coordinator.status().health.getValue("p1").cooldownUntilEpochMillis >= retryAt)
        r.timer.advance(59_999); r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
        r.timer.advance(1); r.coordinator.requestMore(); assertEquals(2, r.runtime.calls.size)
        r.coordinator.close()
    }

    @Test fun allHistoryClearFencesOldViewportAndBothSourceCallbacksWithoutNewDemand() {
        val r = Rig(); r.start(); r.runtime.complete(0, 1, exhausted = true)
        val oldPage = r.coordinator.page(0)!!
        r.store.transaction { putSeen(fact("other", "saved", saved = 30)) }
        assertTrue(r.coordinator.clearHistory())
        val calls = r.runtime.calls.size
        r.runtime.complete(1, 2, exhausted = false)
        assertFalse(r.coordinator.recordSeen(oldPage, oldPage.orderedResultIds.single(), SeenEvent.OPENED))
        assertEquals(calls, r.runtime.calls.size); assertEquals(0, r.coordinator.historyStatistics()!!.seenAliasCount)
        val newPage = r.coordinator.page(0)!!
        assertEquals(oldPage.orderedResultIds, newPage.orderedResultIds)
        assertTrue(r.coordinator.recordSeen(newPage, newPage.orderedResultIds.single(), SeenEvent.OPENED))
        val seen = r.store.transaction { seen("profile", newPage.orderedResultIds.single())!! }
        assertNull(seen.shownAt); assertNull(seen.playedAt); assertNull(seen.savedAt); assertNotNull(seen.openedAt)
        r.coordinator.close()
    }

    @Test fun finalProviderGuardAfterPruningRejectsCompletionWithoutPublishingOrLosingOldFacts() {
        val backing = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 2))
        lateinit var r: Rig
        var stagedPruningObserved = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun beforeCommit(check: () -> Unit) { delegate.beforeCommit {
                        stagedPruningObserved = delegate.seen("profile", "old-a") == null
                        r.coordinator.suspendImmediately(); check()
                    } }
                }.block()
            }
        }
        backing.transaction { putSeen(fact("profile", "old-a", saved = 30)); putSeen(fact("profile", "old-b", played = 40)) }
        r = Rig(store, 1); r.start(); val before = r.coordinator.currentCheckpoint()
        r.runtime.complete(0, 9, nextCursor = "uncommitted", exhausted = false)
        assertTrue(stagedPruningObserved); assertEquals(before, r.coordinator.currentCheckpoint())
        backing.transaction { assertEquals(30L, seen("profile", "old-a")!!.savedAt); assertEquals(40L, seen("profile", "old-b")!!.playedAt)
            assertNull(progress("profile", "p1", r.profile.queryKey)); assertEquals(before, checkpoint("session")) }
        r.coordinator.close()
    }

    @Test fun statisticsNeverRefreshMutationRecencyOrCreateSourceDemand() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 2))
        store.transaction { putSeen(fact("old", "a")) }; store.transaction { putSeen(fact("new", "b")) }
        repeat(5) { assertEquals(1, store.transaction { historyStatistics("old").seenAliasCount }) }
        store.transaction { putSeen(fact("third", "c")) }; assertNull(store.transaction { seen("old", "a") })
        val r = Rig(count = 0); r.start(); val before = r.coordinator.currentCheckpoint()
        repeat(5) { assertEquals(HistoryStatistics(1, 1, 0, 0, 0), r.coordinator.historyStatistics()) }
        assertEquals(before, r.coordinator.currentCheckpoint()); assertTrue(r.runtime.calls.isEmpty()); r.coordinator.close()
    }

    @Test fun controllerPolicyRejectsEveryChangedTargetAndReportsOnlyActualMutationOutcome() {
        val cp = DiscoveryCheckpoint(SessionStamp("s", 4), SearchProfile("p", "q"), PoolState(viewRevision = 3))
        val target = DiscoveryPresentationPolicy.controlTarget(cp, 8)!!
        val request = DiscoveryControlRequest(target, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "provider")
        val changed = listOf(null, target.copy(profileId = "other"), target.copy(stamp = SessionStamp("s", 5)),
            target.copy(stamp = SessionStamp("other", 4)), target.copy(viewRevision = 4), target.copy(controllerGeneration = 9))
        var mutations = 0
        changed.forEach { current ->
            assertEquals(DiscoveryControlOutcome.STALE, DiscoveryPresentationPolicy.executeControl(request, current, setOf("provider"),
                { mutations++; true }, { _, _ -> mutations++; true }))
        }
        assertEquals(0, mutations)
        assertEquals(DiscoveryControlOutcome.STALE, DiscoveryPresentationPolicy.executeControl(request, target, emptySet(), { true }, { _, _ -> true }))
        assertEquals(DiscoveryControlOutcome.FAILED, DiscoveryPresentationPolicy.executeControl(request, target, setOf("provider"),
            { fail("Reset may not clear history"); true }, { profile, provider -> assertEquals("p", profile); assertEquals("provider", provider); false }))
        assertFalse(DiscoveryPresentationPolicy.controlOutcomeText(request.action, DiscoveryControlOutcome.FAILED).contains("已重置"))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(true, true, true, true, false, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, false, true, true, false, true, true))
    }

    @Test fun checkpointCodecRejectsMalformedHeaderAndRoundTripsMaterializedContent() {
        val cp = checkpoint("s", SearchProfile("p", "query"), 3)
        val encoded = DiscoveryCheckpointCodec.encode(cp)
        assertEquals(cp, DiscoveryCheckpointCodec.decode(encoded))
        val corrupt = encoded.copyOf().also { it[7] = 4 }
        try { DiscoveryCheckpointCodec.decode(corrupt); fail("Unknown codec must reject") } catch (_: IllegalArgumentException) { }
        try { DiscoveryCheckpointCodec.decode(encoded + byteArrayOf(1)); fail("Trailing data must reject") } catch (_: IllegalArgumentException) { }
    }
    @Test fun genuineV1HeaderPreservesOldProfileAndHostMigrationQueryKey() { legacyHeader(1) }
    @Test fun genuineV2HeaderPreservesOldProfileAndHostMigrationQueryKey() { legacyHeader(2) }

    private fun legacyHeader(version: Int) {
        val bytes = javaClass.getResourceAsStream("/discovery-checkpoint-v$version.bin")!!.use { it.readBytes() }
        val expectedDigest = if (version == 1) "a58309da3e846d6af207f3726d6745ab8a2b7e82a40943a120b7471b60616bd5"
            else "a0d25e3c6d1b3c1e2f4165f2ff88b75e1b985442500061e089d98b037b65d0e0"
        assertEquals(expectedDigest, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val original = DiscoveryCheckpointCodec.decode(bytes)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        assertEquals(0x45534432, input.readInt()); assertEquals(version, input.readInt())
        fun text() { val size = input.readInt(); require(size in 0..32768); input.readFully(ByteArray(size)) }
        fun optionalLong() { if (input.readBoolean()) input.readLong() }
        text(); input.readLong() // session and epoch
        repeat(3) { text() } // profile, query, locale
        repeat(input.readInt()) { text() } // sources
        optionalLong(); optionalLong(); input.readBoolean(); input.readBoolean(); text() // filters, history policy
        input.readLong(); input.readLong() // view revision and next order
        val prefixSize = bytes.size - input.available()
        val header = bytes.copyOf(prefixSize) + ByteArray(8) // no persisted entries/pages in the codec header
        val restored = DiscoveryCheckpointCodec.decode(header)
        assertEquals(original.profile, restored.profile); assertEquals(original.stamp, restored.stamp)
        assertEquals("legacy-profile", restored.profile.profileId)
        assertEquals("71d0f995f734ca916d68cb7b43a2f8d1d9c02ef7b2309f71ce14c13fabc932a2", restored.profile.queryKey)
        assertEquals(ResultSort.RELEVANCE, restored.profile.sortOrder)
        assertTrue(restored.pool.entries.isEmpty()); assertTrue(restored.pool.pages.isEmpty())
        assertEquals(restored, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(restored)))
        assertEquals(original.pool.entries.single().result.identityAliases, setOf("a".repeat(64)))
        if (version == 1) assertEquals(ResultRights(), original.pool.entries.single().result.rights)
        else assertEquals(setOf(RightsWarning.CONFLICTING_REPORTS), original.pool.entries.single().result.rights.warnings)
    }

}
