package com.easysavevideo.discovery

import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DiscoveryRetentionTest {
    @Test fun profileMutationLruIgnoresReadsAndBreaksBatchTiesLexically() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 2))
        store.transaction { putSeen(fact("a", "one")) }
        store.transaction { putSeen(fact("b", "two")) }
        repeat(30) { store.transaction { seen("a", "one"); historyStatistics("a") } }
        store.transaction { putSeen(fact("c", "three")) }
        store.transaction {
            assertNull(seen("a", "one")); assertNotNull(seen("b", "two")); assertNotNull(seen("c", "three"))
        }
        store.transaction { putSeen(fact("b", "two", openedAt = 5)) }
        store.transaction { putSeen(fact("d", "four")) }
        store.transaction { assertNull(seen("c", "three")); assertNotNull(seen("b", "two")) }

        val tied = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 1))
        tied.transaction { putSeen(fact("b", "same")); putSeen(fact("a", "same")) }
        tied.transaction { assertNull(seen("a", "same")); assertNotNull(seen("b", "same")) }
    }

    @Test fun explicitAliasUnionsAreTransitiveAndPreserveOnlyObservedIndependentEvents() {
        val store = InMemoryDiscoveryStore()
        store.transaction {
            putSeen(fact("p", "a", openedAt = 8)); putSeen(fact("p", "b", savedAt = 11))
            putSeen(fact("p", "c", playedAt = 9)); putSeen(fact("p", "d"))
            linkSeenAliases("p", setOf("a", "b")); linkSeenAliases("p", setOf("c", "d"))
            linkSeenAliases("p", setOf("b", "c", "unknown"))
        }
        store.transaction {
            assertNull(seen("p", "unknown"))
            for (alias in listOf("a", "b", "c", "d")) {
                val record = seen("p", alias)!!
                assertEquals(8L, record.openedAt); assertEquals(9L, record.playedAt); assertEquals(11L, record.savedAt)
                assertNull(record.shownAt); assertNull(record.dismissedAt)
            }
            assertEquals(HistoryStatistics(1, 0, 1, 4, 0), historyStatistics())
            // A lower-time write to one alias cannot regress a genuine event on any other alias.
            putSeen(fact("p", "d", openedAt = 2, shownAt = 3))
        }
        store.transaction {
            for (alias in listOf("a", "b", "c", "d")) {
                assertEquals(8L, seen("p", alias)!!.openedAt); assertEquals(3L, seen("p", alias)!!.shownAt)
                assertEquals(11L, seen("p", alias)!!.savedAt)
            }
        }
    }

    @Test fun matchingFactsWithoutIdentityEvidenceRemainIndependent() {
        val store = InMemoryDiscoveryStore()
        store.transaction {
            putSeen(fact("p", "unknown-a", shownAt = 3))
            putSeen(fact("p", "unknown-b", shownAt = 3))
        }
        assertEquals(2, store.transaction { historyStatistics().seenIdentityCount })
    }

    @Test fun pruningDeletesWholeAliasGroupRatherThanLeavingFalsePartialHistory() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 3))
        store.transaction {
            putSeen(fact("p", "a", openedAt = 4)); putSeen(fact("p", "b")); linkSeenAliases("p", setOf("a", "b"))
        }
        store.transaction {
            putSeen(fact("p", "c", savedAt = 5)); putSeen(fact("p", "d")); linkSeenAliases("p", setOf("c", "d"))
        }
        store.transaction {
            assertNull(seen("p", "a")); assertNull(seen("p", "b"))
            assertEquals(5L, seen("p", "c")!!.savedAt); assertEquals(5L, seen("p", "d")!!.savedAt)
            assertNull(seen("p", "c")!!.openedAt); assertEquals(2, historyStatistics().seenAliasCount)
        }
    }

    @Test fun allMaterializedCheckpointsProtectTheirProfileAliasClosureAndProgress() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 2, maxSeenAliases = 4, maxProgress = 2, maxCheckpoints = 2))
        val first = pinned("s1", "p1", 1)
        val second = pinned("s2", "p2", 2)
        listOf(first, second).forEach { checkpoint ->
            store.transaction {
                putCheckpoint(checkpoint)
                val aliases = checkpoint.pool.entries.single().result.identityAliases
                aliases.forEach { putSeen(fact(checkpoint.profile.profileId, it, openedAt = 7)) }
                linkSeenAliases(checkpoint.profile.profileId, aliases)
                putProgress(progress(checkpoint.profile, "provider"))
            }
        }
        val before = store.transaction { historyStatistics() }
        expectCapacity { store.transaction { putCheckpoint(DiscoveryCheckpoint(SessionStamp("s3", 1), SearchProfile("p3", "new"))) } }
        assertEquals(before, store.transaction { historyStatistics() })
        store.transaction {
            assertEquals(first, checkpoint("s1")); assertEquals(second, checkpoint("s2")); assertNull(checkpoint("s3"))
            assertNotNull(progress(first.profile.profileId, "provider", first.profile.queryKey))
            assertNotNull(progress(second.profile.profileId, "provider", second.profile.queryKey))
        }
    }

    @Test fun protectedOversizeAliasUnionRollsBackEveryFactAndCheckpointWrite() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 4))
        val cp = pinned("s", "p", 1)
        val aliases = cp.pool.entries.single().result.identityAliases
        store.transaction {
            putCheckpoint(cp)
            aliases.forEach { putSeen(fact("p", it)) }; linkSeenAliases("p", aliases)
            putSeen(fact("p", "older-a")); putSeen(fact("p", "older-b"))
        }
        val before = store.transaction { historyStatistics() }
        expectCapacity {
            store.transaction {
                putSeen(fact("p", "new", savedAt = 12))
                linkSeenAliases("p", aliases + setOf("older-a", "older-b", "new"))
                putCheckpoint(cp.copy(pool = cp.pool.copy(viewRevision = 10)))
            }
        }
        store.transaction {
            assertEquals(before, historyStatistics()); assertEquals(cp, checkpoint("s")); assertNull(seen("p", "new"))
            aliases.forEach { assertNull(seen("p", it)!!.savedAt) }
        }
    }

    @Test fun protectedProgressAndCheckpointOverflowRefuseRatherThanForget() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProgress = 1, maxCheckpoints = 1))
        val cp = pinned("s", "p", 1)
        store.transaction { putCheckpoint(cp); putProgress(progress(cp.profile, "one")) }
        expectCapacity { store.transaction { putProgress(progress(cp.profile, "two")) } }
        expectCapacity { store.transaction { putCheckpoint(pinned("other", "p", 2)) } }
        store.transaction {
            assertEquals(HistoryStatistics(1, 1, 0, 0, 1), historyStatistics())
            assertNull(progress("p", "two", cp.profile.queryKey)); assertEquals(cp, checkpoint("s"))
        }
    }

    @Test fun arbitraryTransactionFailurePreservesDataAndMutationLruRecovery() {
        val store = InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxProfiles = 2))
        store.transaction { putSeen(fact("a", "x")) }
        store.transaction { putSeen(fact("b", "y")) }
        try { store.transaction<Unit> { putSeen(fact("a", "x", savedAt = 7)); putSeen(fact("c", "z")); error("injected") } }
        catch (_: IllegalStateException) { }
        store.transaction { assertNull(seen("a", "x")!!.savedAt); assertNull(seen("c", "z")) }
        store.transaction { putSeen(fact("d", "w")) }
        store.transaction { assertNull(seen("a", "x")); assertNotNull(seen("b", "y")); assertNotNull(seen("d", "w")) }
    }

    @Test fun currentAndAllHistoryClearFenceEveryCheckpointAndKeepProgress() {
        val store = InMemoryDiscoveryStore()
        val a = pinned("a", "p1", 1); val b = pinned("b", "p1", 2); val c = pinned("c", "p2", 3)
        store.transaction {
            listOf(a, b, c).forEach { putCheckpoint(it); putProgress(progress(it.profile, "provider")); putSeen(fact(it.profile.profileId, it.stamp.sessionId)) }
        }
        store.transaction { clearSeen("p1") }
        store.transaction {
            assertEquals(0, historyStatistics("p1").seenAliasCount); assertEquals(1, historyStatistics("p2").seenAliasCount)
            listOf(a, b).forEach {
                val actual = checkpoint(it.stamp.sessionId)!!
                assertEquals(it.pool.viewRevision + 1, actual.pool.viewRevision)
                assertEquals(it.pool.entries, actual.pool.entries)
                assertEquals(it.pool.pages.single().orderedResultIds, actual.pool.pages.single().orderedResultIds)
                assertNotNull(progress(it.profile.profileId, "provider", it.profile.queryKey))
            }
            assertEquals(c, checkpoint("c"))
            clearSeen()
        }
        store.transaction {
            assertEquals(0, historyStatistics().seenAliasCount); assertEquals(2, historyStatistics().progressCount)
            assertEquals(2L, checkpoint("a")!!.pool.viewRevision); assertEquals(1L, checkpoint("c")!!.pool.viewRevision)
            clearSeen("p1") // Empty Seen still fences outstanding callbacks.
        }
        assertEquals(3L, store.transaction { checkpoint("a")!!.pool.viewRevision })
    }

    @Test fun progressResetIsExactAcrossProfileProviderAndQuery() {
        val store = InMemoryDiscoveryStore()
        val first = SearchProfile("p", "first"); val second = first.copy(query = "second"); val other = SearchProfile("other", "first")
        store.transaction {
            listOf(first, second, other).forEach { putProgress(progress(it, "one")); putProgress(progress(it, "two")) }
            putSeen(fact("p", "saved", savedAt = 2))
            resetProgress("p", "one", first.queryKey)
        }
        store.transaction {
            assertNull(progress("p", "one", first.queryKey)); assertNotNull(progress("p", "one", second.queryKey))
            assertNotNull(progress("p", "two", first.queryKey)); assertNotNull(progress("other", "one", first.queryKey))
            assertEquals(2L, seen("p", "saved")!!.savedAt)
            resetProgress("p", "one")
        }
        assertEquals(4, store.transaction { historyStatistics().progressCount })
    }

    @Test fun boundedLongRunHasNoOrphanAliasesAndNoUnboundedProfileOrCheckpointGrowth() {
        val policy = DiscoveryRetentionPolicy(maxProfiles = 4, maxSeenAliases = 16, maxProgress = 7, maxCheckpoints = 3)
        val store = InMemoryDiscoveryStore(policy)
        val random = Random(817)
        val groups = mutableListOf<Pair<String, Set<String>>>()
        repeat(400) { index ->
            val profile = SearchProfile("p${random.nextInt(12)}", "q${index % 9}")
            val aliases = (0..random.nextInt(3)).map { "alias-$index-$it" }.toSet()
            groups += profile.profileId to aliases
            store.transaction {
                aliases.forEach { putSeen(fact(profile.profileId, it, savedAt = index.toLong())) }
                linkSeenAliases(profile.profileId, aliases)
                putProgress(progress(profile, "provider${index % 3}"))
                putCheckpoint(DiscoveryCheckpoint(SessionStamp("s$index", 1), profile))
            }
            store.transaction {
                val stats = historyStatistics()
                assertTrue(stats.profileCount <= policy.maxProfiles); assertTrue(stats.seenAliasCount <= policy.maxSeenAliases)
                assertTrue(stats.progressCount <= policy.maxProgress); assertTrue(stats.checkpointCount <= policy.maxCheckpoints)
                groups.forEach { (profileId, group) ->
                    val remaining = group.mapNotNull { seen(profileId, it) }
                    assertTrue(remaining.isEmpty() || remaining.size == group.size)
                    remaining.forEach { assertNull(it.shownAt); assertNull(it.openedAt); assertNull(it.playedAt) }
                }
            }
        }
    }

    @Test fun purePlannerIsStableUnderInputPermutationAndProtectsEntireAliasClosure() {
        val snapshot = DiscoveryRetentionSnapshot(
            listOf(RetentionProfile("p", 1)),
            listOf(RetentionSeenAlias("p", "a", "a", 1), RetentionSeenAlias("p", "b", "a", 1),
                RetentionSeenAlias("p", "c", "c", 1), RetentionSeenAlias("p", "d", "d", 1)),
            listOf(RetentionProgress(RetentionProgressKey("p", "b", "old"), 1), RetentionProgress(RetentionProgressKey("p", "a", "old"), 1)),
            listOf(RetentionCheckpoint("a", "p", "q", 1, setOf("b"), true), RetentionCheckpoint("b", "p", "q", 1)),
        )
        val policy = DiscoveryRetentionPolicy(maxSeenAliases = 2, maxProgress = 1, maxCheckpoints = 1)
        val expected = DiscoveryRetentionPlanner.plan(snapshot, policy)
        assertEquals(setOf(RetentionSeenKey("p", "c"), RetentionSeenKey("p", "d")), expected.removeSeenAliases)
        assertEquals(setOf(RetentionProgressKey("p", "a", "old")), expected.removeProgress)
        assertEquals(setOf("b"), expected.removeCheckpoints)
        val random = kotlin.random.Random(28)
        repeat(50) {
            assertEquals(expected, DiscoveryRetentionPlanner.plan(snapshot.copy(profiles = snapshot.profiles.shuffled(random),
                seenAliases = snapshot.seenAliases.shuffled(random), progress = snapshot.progress.shuffled(random),
                checkpoints = snapshot.checkpoints.shuffled(random)), policy))
        }
    }

    private fun fact(profile: String, alias: String, openedAt: Long? = null, savedAt: Long? = null,
                     playedAt: Long? = null, shownAt: Long? = null) =
        SeenRecord(profile, alias, 1, shownAt = shownAt, openedAt = openedAt, playedAt = playedAt, savedAt = savedAt)
    private fun progress(profile: SearchProfile, provider: String) =
        ProviderProgress(profile.profileId, provider, profile.queryKey, "1", 1, "cursor", null, false, 1)
    private fun pinned(session: String, profile: String, id: Int): DiscoveryCheckpoint {
        val stamp = SessionStamp(session, 1)
        val pool = ResultPool()
        val state = pool.admit(PoolState(), listOf(result(id)), 1).state
        return DiscoveryCheckpoint(stamp, SearchProfile(profile, "query"),
            pool.materialize(state, stamp, 0, 1, RepeatPolicy.SHOW_ALL) { null }.first)
    }
    private fun expectCapacity(action: () -> Unit) {
        try { action(); fail("Protected capacity must reject the whole transaction") }
        catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CAPACITY_REACHED, failure.code) }
    }
}
