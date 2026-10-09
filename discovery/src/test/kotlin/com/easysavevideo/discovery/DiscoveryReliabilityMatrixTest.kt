package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

/** Ordinary fixed-source coordinator faults. No resource stress or dynamic-rule execution. */
class DiscoveryReliabilityMatrixTest {
    private class Fixture(val store: DiscoveryStore = InMemoryDiscoveryStore(), count: Int = 1) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val providers = (1..count).map { StubProvider(ProviderDescriptor("p$it", "1")) }
        val registry = ProviderRegistry(providers)
        val profile = SearchProfile("profile", "query")
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock) { 1000 }
        fun start() { coordinator.setVisibility(true, true); coordinator.start("session", profile) }
    }

    @Test fun oldSuccessCannotCompleteANewerRetryOrPublishItsCursor() {
        val f = Fixture(); f.start(); f.runtime.fail(0, retryable = true); f.timer.advance(1000)
        assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(0, 1, nextCursor = "stale")
        assertEquals(1, f.coordinator.status().inFlightCount)
        assertEquals(0, f.coordinator.status().availableCount)
        f.store.transaction { assertNull(progress("profile", "p1", f.profile.queryKey)) }
        f.runtime.complete(1, 2, nextCursor = "current")
        assertEquals(listOf(result(2, "p1").fingerprint), f.coordinator.currentCheckpoint()!!.pool.entries.map { it.result.fingerprint })
        f.store.transaction { assertEquals("current", progress("profile", "p1", f.profile.queryKey)!!.cursor) }
    }

    @Test fun oldFailureCannotRetireANewerRetryOrScheduleAnotherAttempt() {
        val f = Fixture(); f.start(); f.runtime.fail(0, retryable = true); f.timer.advance(1000)
        f.runtime.fail(0, retryable = true)
        assertEquals(1, f.coordinator.status().inFlightCount)
        f.timer.advance(1000)
        assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(1, 2)
        assertEquals(1, f.coordinator.status().availableCount)
        assertEquals(0, f.coordinator.status().health["p1"]!!.failureCount)
    }

    @Test fun duplicateFailureDuringBackoffCannotMintRetryBudget() {
        val f = Fixture(); f.start(); f.runtime.fail(0, retryable = true)
        repeat(3) { f.runtime.fail(0, retryable = true) }
        f.timer.advance(1000); assertEquals(2, f.runtime.calls.size)
        f.runtime.fail(1, retryable = true); f.timer.advance(1000)
        f.runtime.fail(2, retryable = true); f.timer.advance(16000)
        assertEquals(3, f.runtime.calls.size)
        assertEquals(3, f.coordinator.status().requestsThisRound)
        assertEquals(1, f.coordinator.status().health["p1"]!!.failureCount)
    }

    @Test fun closeDuringBackoffCancelsPendingRetryAndLateCallbacks() {
        val f = Fixture(); f.start(); f.runtime.fail(0, retryable = true)
        f.coordinator.close(); f.timer.advance(16000); f.runtime.complete(0, 1)
        assertEquals(1, f.runtime.calls.size)
        assertTrue(f.runtime.calls.single().operation.cancellation.isCancelled)
        assertNull(f.coordinator.currentCheckpoint())
        f.store.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)) }
    }

    @Test fun unavailableStoreAtCallbackRollsBackAndStopsRetryUntilExplicitRestore() {
        val backing = InMemoryDiscoveryStore(); var unavailable = false
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
                if (unavailable) throw IllegalStateException("injected")
                return backing.transaction(block)
            }
        }
        val f = Fixture(store); f.start(); unavailable = true; f.runtime.complete(0, 1, nextCursor = "new")
        assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, f.coordinator.status().lastError)
        assertEquals(DiscoveryState.FAILED, f.coordinator.status().state)
        f.timer.advance(16000); assertEquals(1, f.runtime.calls.size)
        unavailable = false
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(1).fingerprint)) }
        repeat(3) { f.coordinator.status() }; assertEquals(1, f.runtime.calls.size)
        assertTrue(f.coordinator.restore("session")); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(1, 2); assertEquals(1, f.coordinator.status().availableCount)
    }

    @Test fun cancellationAtFinalCommitRollsBackPoolSeenAndCursorTogether() {
        val backing = InMemoryDiscoveryStore(); var atCommit: (() -> Unit)? = null
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val tx = this
                val wrapped = object : DiscoveryTransaction by tx {
                    override fun beforeCommit(check: () -> Unit) { tx.beforeCommit { atCommit?.invoke(); check() } }
                }
                wrapped.block()
            }
        }
        val f = Fixture(store); f.start(); atCommit = { f.coordinator.suspendImmediately() }
        f.runtime.complete(0, 1, nextCursor = "pending")
        atCommit = null
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(1).fingerprint)) }
        assertEquals(0, f.coordinator.status().health["p1"]!!.failureCount)
        f.coordinator.setVisibility(true, true); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(1, 2); assertEquals(1, f.coordinator.status().availableCount)
    }

    @Test fun deadlineReachedAtFinalCommitRollsBackPoolSeenAndCursorTogether() {
        val backing = InMemoryDiscoveryStore(); var atCommit: (() -> Unit)? = null
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val tx = this
                val wrapped = object : DiscoveryTransaction by tx {
                    override fun beforeCommit(check: () -> Unit) { tx.beforeCommit { atCommit?.invoke(); check() } }
                }
                wrapped.block()
            }
        }
        val f = Fixture(store); f.start()
        atCommit = { f.clock.now = f.runtime.calls[0].operation.context.deadlineEpochMillis }
        f.runtime.complete(0, 1, nextCursor = "pending")
        atCommit = null
        backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(1).fingerprint)) }
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, f.coordinator.status().lastError)
        assertEquals(0, f.coordinator.status().inFlightCount)
        f.timer.advance(60_000); assertEquals(1, f.runtime.calls.size)
        f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
        f.runtime.complete(1, 2); assertEquals(1, f.coordinator.status().availableCount)
    }

    @Test fun incompatibleCursorResetsOnlyItsSourceAndPreservesCommittedSeen() {
        val f = Fixture(count = 2)
        f.store.transaction {
            putProgress(ProviderProgress("profile", "p1", f.profile.queryKey, "1", 2, "old", null, true, 10))
            putProgress(ProviderProgress("profile", "p2", f.profile.queryKey, "1", 1, "done", null, true, 10))
            putSeen(SeenRecord("profile", result(1).fingerprint, 1, openedAt = 2))
        }
        f.start(); assertEquals(1, f.runtime.calls.size); assertEquals("p1", f.runtime.calls[0].provider.descriptor.id)
        assertNull(f.runtime.calls[0].request.progress)
        f.store.transaction { assertTrue(progress("profile", "p2", f.profile.queryKey)!!.exhausted); assertEquals(2L, seen("profile", result(1).fingerprint)!!.openedAt) }
    }

    @Test fun cursorAtExactExpiryRestartsButExhaustedWithoutExpiryRemainsExhausted() {
        val f = Fixture(count = 2)
        f.store.transaction {
            putProgress(ProviderProgress("profile", "p1", f.profile.queryKey, "1", 1, "old", 1000, true, 10))
            putProgress(ProviderProgress("profile", "p2", f.profile.queryKey, "1", 1, null, null, true, 10))
        }
        f.start(); assertEquals(1, f.runtime.calls.size); assertNull(f.runtime.calls[0].request.progress)
        f.runtime.complete(0, 1)
        repeat(3) { f.coordinator.requestMore(); f.coordinator.status() }
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun failedSourceDoesNotCancelSiblingAndZeroRegistryNeedsNoRuntime() {
        val f = Fixture(count = 2); f.start(); f.runtime.fail(0)
        assertFalse(f.runtime.calls[1].cancelled); f.runtime.complete(1, 2)
        assertEquals(1, f.coordinator.page(0)!!.orderedResultIds.size)
        f.registry.remove("p1"); f.registry.remove("p2"); f.coordinator.requestMore()
        assertEquals(DiscoveryState.NO_SOURCES, f.coordinator.status().state)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.coordinator.results(f.coordinator.page(0)!!).size)
    }

    @Test fun retryDeadlineAndCooldownNeverCreateAutomaticPostDeadlineWork() {
        val f = Fixture(); f.start(); f.runtime.fail(0, retryable = true); f.timer.advance(1000)
        f.clock.now = f.runtime.calls[1].operation.context.deadlineEpochMillis
        f.runtime.complete(1, 1)
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, f.coordinator.status().lastError)
        f.timer.advance(60_000); repeat(3) { f.coordinator.status() }
        assertEquals(2, f.runtime.calls.size); assertEquals(0, f.coordinator.status().availableCount)
        f.coordinator.requestMore(); assertEquals(3, f.runtime.calls.size)
    }
}
