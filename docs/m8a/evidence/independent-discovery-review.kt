package com.easysavevideo.discovery

import com.easysavevideo.app.storage.DiscoveryCheckpointCodec
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

/** Independent ordinary M8-A source probes; local fake providers only. */
class IndependentDiscoveryReviewTest {
    private class Rig(val store: DiscoveryStore = InMemoryDiscoveryStore(), val limits: DiscoveryLimits = DiscoveryLimits(pageSize = 1, poolCapacity = 10)) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val provider = StubProvider()
        val registry = ProviderRegistry(listOf(provider))
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock, limits) { 10L }
        val profile = SearchProfile("profile", "first", historyPolicy = RepeatPolicy.SHOW_ALL)
        fun start() { coordinator.setVisibility(true, true); coordinator.start("session", profile) }
    }

    @Test fun failedViewRevisionLeavesOldCommittedSortAndPageUntouched() {
        val backing = InMemoryDiscoveryStore()
        var rejectWrite = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) {
                        if (rejectWrite) error("injected revision write failure")
                        delegate.putCheckpoint(checkpoint)
                    }
                }.block()
            }
        }
        val rig = Rig(store)
        rig.start(); rig.runtime.complete(0, 1, 2)
        rig.coordinator.page(0)
        val before = rig.coordinator.currentCheckpoint()!!
        rejectWrite = true
        assertFalse(rig.coordinator.reviseView(RepeatPolicy.SHOW_ALL, 1, 0, ResultSort.NEWEST))
        assertEquals(before, rig.coordinator.currentCheckpoint())
        assertEquals(DiscoveryState.FAILED, rig.coordinator.status().state)
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, rig.coordinator.status().lastError)
        backing.transaction { assertEquals(before, checkpoint("session")) }
    }

    @Test fun failedHistoryClearReturnsFalseAndKeepsSeenFactsAndRevision() {
        val backing = InMemoryDiscoveryStore()
        var reject = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val value = block()
                if (reject) error("injected transaction rollback")
                value
            }
        }
        val rig = Rig(store); rig.start(); rig.runtime.complete(0, 1)
        val page = rig.coordinator.page(0)!!
        assertTrue(rig.coordinator.recordSeen(page, page.orderedResultIds.single(), SeenEvent.SHOWN))
        val before = rig.coordinator.currentCheckpoint()!!
        reject = true
        assertFalse(rig.coordinator.clearHistory("profile"))
        assertEquals(before, rig.coordinator.currentCheckpoint())
        backing.transaction { assertNotNull(seen("profile", result(1).fingerprint)!!.shownAt) }
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, rig.coordinator.status().lastError)
    }

    @Test fun failedProgressResetReturnsFalseAndKeepsCommittedCursor() {
        val backing = InMemoryDiscoveryStore()
        var reject = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val value = block()
                if (reject) error("injected transaction rollback")
                value
            }
        }
        val rig = Rig(store); rig.start()
        rig.runtime.complete(0, 1, nextCursor = "saved", exhausted = false)
        reject = true
        assertFalse(rig.coordinator.resetProgress("profile"))
        backing.transaction { assertEquals("saved", progress("profile", "source", rig.profile.queryKey)!!.cursor) }
        assertTrue(rig.runtime.calls.last().operation.cancellation.isCancelled)
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, rig.coordinator.status().lastError)
    }

    @Test fun progressSnapshotReadFailureCancelsWorkWithoutPublishingSuccess() {
        val backing = InMemoryDiscoveryStore()
        var rejectRead = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun progress(profileId: String, providerId: String, queryKey: String): ProviderProgress? {
                        if (rejectRead) error("injected progress read failure")
                        return delegate.progress(profileId, providerId, queryKey)
                    }
                }.block()
            }
        }
        val rig = Rig(store); rig.start()
        rejectRead = true
        rig.registry.register(StubProvider(ProviderDescriptor("added", "1")))
        val status = rig.coordinator.status()
        assertEquals(DiscoveryState.FAILED, status.state)
        assertTrue(status.providers.all { it.state == ProviderDiscoveryState.STORAGE_UNAVAILABLE })
        assertEquals(0, status.inFlightCount)
        assertTrue(rig.runtime.calls.single().operation.cancellation.isCancelled)
        rig.runtime.complete(0, 1)
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()) }
    }

    @Test fun completedCooldownDoesNotContinueReportingActiveBackoff() {
        val rig = Rig(); rig.start()
        rig.runtime.complete(0, nextCursor = "one", exhausted = false)
        rig.runtime.complete(1, nextCursor = "two", exhausted = false)
        assertEquals(DiscoveryState.BACKOFF, rig.coordinator.status().state)
        rig.clock.now += rig.limits.cooldownMillis
        val status = rig.coordinator.status()
        assertEquals(ProviderDiscoveryState.READY, status.providers.single().state)
        assertNotEquals("No cooldown or retry remains; status reads must report the current state without launching work", DiscoveryState.BACKOFF, status.state)
        assertEquals(2, rig.runtime.calls.size)
    }

    @Test fun coalescedResumeDiscardsBothLateFailureAndSuccess() {
        val rig = Rig(); rig.start()
        rig.coordinator.suspendImmediately()
        rig.coordinator.setVisibility(true, true)
        assertEquals(2, rig.runtime.calls.size)
        rig.runtime.fail(0, code = DiscoveryError.SOURCE_CONFIGURATION)
        rig.runtime.complete(0, 999)
        assertEquals(ProviderDiscoveryState.REQUESTING, rig.coordinator.status().providers.single().state)
        assertEquals(0, rig.coordinator.status().health.getValue("source").failureCount)
        assertEquals(0, rig.coordinator.status().availableCount)
        rig.runtime.complete(1, 1)
        assertEquals(1, rig.coordinator.status().availableCount)
    }

    @Test fun rateLimitSurvivesQueryChangeAndProgressReset() {
        val rig = Rig(); rig.start()
        val retryAt = rig.clock.now + 60_000
        rig.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, false, retryAt))
        rig.coordinator.start("session", rig.profile.copy(query = "second"))
        rig.coordinator.resetProgress("profile")
        rig.coordinator.requestMore()
        assertEquals(1, rig.runtime.calls.size)
        assertEquals(retryAt, rig.coordinator.status().health.getValue("source").cooldownUntilEpochMillis)
        assertEquals(ProviderDiscoveryState.COOLDOWN, rig.coordinator.status().providers.single().state)
    }

    @Test fun noNewCooldownDoesNotFollowDifferentProviderVersion() {
        val rig = Rig(); rig.start()
        rig.runtime.complete(0, nextCursor = "one", exhausted = false)
        rig.runtime.complete(1, nextCursor = "two", exhausted = false)
        rig.provider.descriptor = ProviderDescriptor("source", "2")
        rig.coordinator.requestMore()
        assertEquals(3, rig.runtime.calls.size)
        assertNull(rig.runtime.calls.last().request.progress)
        assertEquals(ProviderDiscoveryState.REQUESTING, rig.coordinator.status().providers.single().state)
    }

    @Test fun exhaustedEmptyResponseIsCommittedWithoutAnInventedCooldown() {
        val rig = Rig(); rig.start()
        rig.runtime.complete(0, nextCursor = "one", exhausted = false)
        rig.runtime.complete(1, exhausted = true)
        val progress = rig.coordinator.status().providers.single()
        assertEquals(ProviderDiscoveryState.EXHAUSTED, progress.state)
        assertFalse(progress.hasContinuation)
        assertNotNull(progress.lastSuccessAtEpochMillis)
        assertNull(progress.retryAtEpochMillis)
        assertNull(progress.lastError)
    }

    @Test fun cachedCursorExpiryIsVisibleWithoutResetOrAdditionalRequests() {
        val backing = InMemoryDiscoveryStore()
        var reads = 0
        val counting = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T { reads++; return backing.transaction(block) }
        }
        val rig = Rig(counting); rig.start()
        rig.runtime.calls[0].callback(ProviderOutcome.Success(ValidatedProviderPage((1..5).map(::result), "saved", rig.clock.now + 100, false)))
        val ready = rig.coordinator.status()
        assertTrue(ready.providers.single().hasContinuation)
        val before = reads
        rig.clock.now += 100
        repeat(30) {
            val expired = rig.coordinator.status().providers.single()
            assertEquals(ProviderDiscoveryState.CURSOR_EXPIRED, expired.state)
            assertFalse(expired.hasContinuation)
        }
        assertEquals(before, reads)
        assertEquals(1, rig.runtime.calls.size)
        backing.transaction { assertEquals("saved", progress("profile", "source", rig.profile.queryKey)!!.cursor) }
    }

    @Test fun sortRevisionKeepsCurrentPageAsAnchorAndReordersOnlyLaterPages() {
        val rig = Rig(); rig.start()
        val videos = (1..5).map { result(it).copy(durationMs = it.toLong(), publishedAtEpochMillis = it.toLong()) }
        rig.runtime.calls[0].callback(ProviderOutcome.Success(ValidatedProviderPage(videos, null, null, true)))
        rig.coordinator.page(0)
        val anchor = rig.coordinator.page(1)!!
        rig.coordinator.reviseView(RepeatPolicy.SHOW_ALL, 1, 1, ResultSort.NEWEST)
        val revised = rig.coordinator.page(0)!!
        assertEquals(anchor.orderedResultIds, revised.orderedResultIds)
        assertTrue(revised.revision > anchor.revision)
        assertEquals(listOf(videos.last().resultId), rig.coordinator.page(1)!!.orderedResultIds)
        assertEquals(rig.profile.queryKey, rig.coordinator.currentCheckpoint()!!.profile.queryKey)
        assertTrue(rig.coordinator.results(anchor).isEmpty())
    }

    @Test fun checkpointV3SortRoundTripsAndLegacyV2DefaultsWithoutLostRows() {
        val legacy = javaClass.getResourceAsStream("/discovery-checkpoint-v2.bin")!!.use { it.readBytes() }
        val original = DiscoveryCheckpointCodec.decode(legacy)
        assertEquals(ResultSort.RELEVANCE, original.profile.sortOrder)
        ResultSort.entries.forEach { sort ->
            val changed = original.copy(profile = original.profile.copy(sortOrder = sort))
            val encoded = DiscoveryCheckpointCodec.encode(changed)
            assertEquals(3, java.nio.ByteBuffer.wrap(encoded, 4, 4).int)
            assertEquals(changed, DiscoveryCheckpointCodec.decode(encoded))
            assertEquals(original.profile.queryKey, changed.profile.queryKey)
        }
    }
}
