package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

/** Real coordinator + in-memory store/fake providers: SETTINGS-style hidden local controls. */
class DiscoveryHiddenLocalControlsTest {
    private class Fixture {
        val clock = FakeClock()
        val runtime = FakeRuntime()
        val store = InMemoryDiscoveryStore()
        val profile = SearchProfile("profile", "query")
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(StubProvider(ProviderDescriptor("p1", "1")),
            StubProvider(ProviderDescriptor("p2", "1")))), runtime, store, FakeTimer(clock), clock)
        val page: PageSnapshot
        val calls: Int
        init {
            coordinator.setVisibility(true, true); coordinator.start("session", profile)
            runtime.complete(0, 1, nextCursor = "first", exhausted = false)
            page = coordinator.page(0)!!
            assertTrue(coordinator.recordSeen(page, result(1).fingerprint, SeenEvent.OPENED))
            store.transaction {
                putSeen(SeenRecord("other", "other-seen", 1, savedAt = 2))
                putProgress(progress(profile, "p1", "first"))
                putProgress(progress(profile, "p2", "second"))
                putProgress(progress(profile.copy(profileId = "other"), "p1", "other-progress"))
            }
            coordinator.setVisibility(true, false)
            calls = runtime.calls.size
        }
        fun assertNoDemandOrPageChange() {
            repeat(10) {
                assertNotNull(coordinator.historyStatistics(profile.profileId))
                assertNotNull(coordinator.historyStatistics())
                coordinator.status()
            }
            assertEquals(calls, runtime.calls.size)
            assertEquals(page.orderedResultIds, coordinator.page(0)!!.orderedResultIds)
            assertEquals(calls, runtime.calls.size)
        }
        private fun progress(profile: SearchProfile, provider: String, cursor: String) =
            ProviderProgress(profile.profileId, provider, profile.queryKey, "1", 1, cursor, null, false, 1)
    }
    @Test fun hiddenCurrentProfileClearPreservesOtherHistoryAndAllProgressWithoutProviderCalls() {
        val f = Fixture(); assertTrue(f.coordinator.clearHistory(f.profile.profileId)); f.assertNoDemandOrPageChange()
        f.store.transaction {
            assertNull(seen(f.profile.profileId, result(1).fingerprint))
            assertEquals(2L, seen("other", "other-seen")!!.savedAt)
            assertEquals("first", progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor)
            assertEquals("second", progress(f.profile.profileId, "p2", f.profile.queryKey)!!.cursor)
            assertEquals("other-progress", progress("other", "p1", f.profile.queryKey)!!.cursor)
        }
    }
    @Test fun hiddenAllProfileClearPreservesEverySourceProgressAndExistingPages() {
        val f = Fixture(); assertTrue(f.coordinator.clearHistory()); f.assertNoDemandOrPageChange()
        f.store.transaction {
            assertEquals(0, historyStatistics().seenAliasCount)
            assertEquals("first", progress(f.profile.profileId, "p1", f.profile.queryKey)!!.cursor)
            assertEquals("second", progress(f.profile.profileId, "p2", f.profile.queryKey)!!.cursor)
            assertEquals("other-progress", progress("other", "p1", f.profile.queryKey)!!.cursor)
        }
    }
    @Test fun hiddenOneProviderResetPreservesSeenOtherProviderAndOtherProfile() {
        val f = Fixture(); assertTrue(f.coordinator.resetProgress(f.profile.profileId, "p1")); f.assertNoDemandOrPageChange()
        f.store.transaction {
            assertNull(progress(f.profile.profileId, "p1", f.profile.queryKey))
            assertNotNull(seen(f.profile.profileId, result(1).fingerprint)!!.openedAt)
            assertEquals(2L, seen("other", "other-seen")!!.savedAt)
            assertEquals("second", progress(f.profile.profileId, "p2", f.profile.queryKey)!!.cursor)
            assertEquals("other-progress", progress("other", "p1", f.profile.queryKey)!!.cursor)
        }
    }
}
