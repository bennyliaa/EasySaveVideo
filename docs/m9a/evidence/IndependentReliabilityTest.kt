package com.easysavevideo.review.m9a

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

/** Separate bounded reviewer fixtures. All callbacks, time and transactions are local test actors. */
class IndependentReliabilityTest {
    private class Clock(var now: Long = 5_000) : EpochClock { override fun nowMillis() = now }
    private class Timer(private val clock: Clock) : DiscoveryTimer {
        data class Event(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val events = mutableListOf<Event>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            val event = Event(clock.now + delayMillis, action); events += event
            return CancellationHandle { event.cancelled = true }
        }
        fun next() {
            val event = events.first { !it.cancelled }; events.remove(event)
            clock.now = maxOf(clock.now, event.at); event.action()
        }
        fun active() = events.count { !it.cancelled }
    }
    private class Source(override val descriptor: ProviderDescriptor = ProviderDescriptor("fixed", "1")) : VideoSearchProvider {
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
            throw AssertionError("UNEXPECTED_DIRECT_EXECUTION")
    }
    private class Runtime : ProviderRuntime {
        data class Call(val request: SearchRequest, val operation: DiscoveryOperation,
            val done: (ProviderOutcome) -> Unit, var cancels: Int = 0)
        val calls = mutableListOf<Call>()
        var onExecute: ((Call) -> Unit)? = null
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle {
            val call = Call(request, operation, callback); calls += call
            onExecute?.invoke(call)
            return CancellationHandle { call.cancels++; operation.cancellation.cancel() }
        }
    }
    private class Store : DiscoveryStore {
        val backing = InMemoryDiscoveryStore()
        var finalHook: (() -> Unit)? = null
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
            val tx = this
            val wrapper = object : DiscoveryTransaction by tx {
                override fun beforeCommit(check: () -> Unit) { tx.beforeCommit { finalHook?.invoke(); check() } }
            }
            wrapper.block()
        }
    }
    private class Fixture(val descriptor: ProviderDescriptor = ProviderDescriptor("fixed", "1"),
        val store: Store = Store()) : AutoCloseable {
        val clock = Clock(); val timer = Timer(clock); val runtime = Runtime()
        val profile = SearchProfile("profile", "ordinary query")
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(Source(descriptor))), runtime,
            store, timer, clock, retryDelayMillis = { 500 })
        fun start(session: String = "session") { coordinator.setVisibility(true, true); coordinator.start(session, profile) }
        fun fail(index: Int) = runtime.calls[index].done(ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE, true))
        fun success(index: Int, id: Int = 1, cursor: String? = null) = runtime.calls[index].done(
            ProviderOutcome.Success(ValidatedProviderPage(listOf(result(id)), cursor, null, true)))
        fun progress() = store.backing.transaction { progress("profile", "fixed", profile.queryKey) }
        override fun close() = coordinator.close()
    }

    @Test fun retriesKeepOriginalDeadlineAndCancellationButHaveDistinctAuthority() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next(); f.fail(1); f.timer.next()
            assertEquals(listOf(0, 1, 2), f.runtime.calls.map { it.operation.context.attempt })
            val first = f.runtime.calls[0].operation
            for (call in f.runtime.calls.drop(1)) {
                assertNotSame(first, call.operation)
                assertSame(first.cancellation, call.operation.cancellation)
                assertEquals(first.context.deadlineEpochMillis, call.operation.context.deadlineEpochMillis)
                assertEquals(first.context.operationId, call.operation.context.operationId)
            }
            f.fail(2)
            assertEquals(0, f.timer.active()); assertEquals(3, f.runtime.calls.size)
            assertEquals(3, f.coordinator.status().requestsThisRound)
            assertEquals(1, f.coordinator.status().health.getValue("fixed").failureCount)
        }
    }

    @Test fun retiredRateLimitCannotChangeCurrentAttemptAccountingOrHealth() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next()
            f.runtime.calls[0].done(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 3_600_000))
            assertEquals(1, f.coordinator.status().inFlightCount)
            assertEquals(0, f.timer.active()); assertNull(f.coordinator.status().health["fixed"]?.lastError)
            f.success(1, cursor = "accepted")
            assertEquals("accepted", f.progress()!!.cursor)
            assertEquals(0L, f.coordinator.status().health.getValue("fixed").cooldownUntilEpochMillis)
        }
    }

    @Test fun bothRetiredSuccessesLeaveThirdAttemptAndAllPersistentRowsUntouched() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next(); f.fail(1); f.timer.next()
            f.success(1, 2, "retired-second"); f.success(0, 3, "retired-first")
            assertEquals(1, f.coordinator.status().inFlightCount)
            assertEquals(0, f.coordinator.status().availableCount); assertNull(f.progress())
            f.store.backing.transaction { assertNull(seen("profile", result(2).fingerprint)); assertNull(seen("profile", result(3).fingerprint)) }
            f.success(2, 4, "third")
            assertEquals(listOf(result(4).fingerprint), f.coordinator.currentCheckpoint()!!.pool.entries.map { it.result.fingerprint })
            assertEquals("third", f.progress()!!.cursor); assertEquals(3, f.runtime.calls.size)
        }
    }

    @Test fun synchronousSuccessfulCallbackFollowedByRuntimeExceptionCannotUndoCommit() {
        Fixture().use { f ->
            f.runtime.onExecute = { call ->
                call.done(ProviderOutcome.Success(ValidatedProviderPage(listOf(result(1)), null, null, true)))
                throw IllegalStateException("INJECTED_AFTER_CALLBACK")
            }
            f.start()
            assertEquals(1, f.coordinator.status().availableCount)
            assertEquals(0, f.coordinator.status().inFlightCount)
            assertEquals(0, f.coordinator.status().health.getValue("fixed").failureCount)
            assertTrue(f.progress()!!.exhausted); assertEquals(1, f.runtime.calls.size)
        }
    }

    @Test fun cancellationAfterRetryCancelsCurrentHandleAndDoesNotCountLateFailure() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next()
            f.coordinator.setPriority(saveActive = true, recordingActive = false)
            assertEquals(0, f.runtime.calls[0].cancels); assertEquals(1, f.runtime.calls[1].cancels)
            f.runtime.calls[1].done(ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE, true))
            f.success(0, cursor = "retired")
            assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
            assertEquals(0, f.timer.active()); assertNull(f.progress())
            assertEquals(0, f.coordinator.status().health.getValue("fixed").failureCount)
            f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
            f.coordinator.setPriority(saveActive = false, recordingActive = false)
            assertEquals(3, f.runtime.calls.size)
            assertNotSame(f.runtime.calls[1].operation.cancellation, f.runtime.calls[2].operation.cancellation)
        }
    }

    @Test fun retrySuccessAtFinalDeadlinePreservesPriorCursorAndAliasFacts() {
        Fixture().use { f ->
            val prior = ProviderProgress("profile", "fixed", f.profile.queryKey, "1", 1, "prior", null, false, 1)
            val existing = result(1)
            f.store.backing.transaction {
                putProgress(prior)
                putSeen(SeenRecord("profile", existing.fingerprint, 1, openedAt = 2, savedAt = 3))
            }
            val before = f.store.backing.transaction { historyStatistics() }
            f.start(); assertEquals(prior, f.runtime.calls[0].request.progress)
            f.fail(0); f.timer.next()
            val deadline = f.runtime.calls[0].operation.context.deadlineEpochMillis
            f.store.finalHook = { f.clock.now = deadline }
            f.success(1, cursor = "must-rollback"); f.store.finalHook = null
            assertEquals(DiscoveryError.DEADLINE_EXCEEDED, f.coordinator.status().lastError)
            assertEquals(prior, f.progress()); assertTrue(f.coordinator.currentCheckpoint()!!.pool.entries.isEmpty())
            f.store.backing.transaction {
                assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
                assertEquals(2L, seen("profile", existing.fingerprint)!!.openedAt)
                assertEquals(3L, seen("profile", existing.fingerprint)!!.savedAt)
                assertEquals(before.seenAliasCount, historyStatistics().seenAliasCount)
            }
            assertEquals(0, f.timer.active()); assertEquals(0, f.coordinator.status().inFlightCount)
        }
    }

    @Test fun finalCommitOneMillisecondBeforeAbsoluteDeadlineRemainsValid() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next()
            f.store.finalHook = { f.clock.now = f.runtime.calls[0].operation.context.deadlineEpochMillis - 1 }
            f.success(1, cursor = "boundary"); f.store.finalHook = null
            assertEquals("boundary", f.progress()!!.cursor)
            assertEquals(1, f.coordinator.status().availableCount)
            assertNull(f.coordinator.status().lastError)
        }
    }

    @Test fun finalStorageExceptionRollsBackAndExplicitRestoreCanRecover() {
        Fixture().use { f ->
            f.start(); f.store.finalHook = { throw IllegalStateException("INJECTED_FINAL_STORAGE") }
            f.success(0, cursor = "discarded"); f.store.finalHook = null
            assertEquals(DiscoveryState.FAILED, f.coordinator.status().state)
            assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, f.coordinator.status().lastError)
            assertNull(f.progress())
            f.store.backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()); assertEquals(0, historyStatistics().seenAliasCount) }
            assertTrue(f.coordinator.restore("session")); assertEquals(2, f.runtime.calls.size)
            f.success(1, 2, "recovered")
            assertEquals("recovered", f.progress()!!.cursor); assertEquals(1, f.coordinator.status().availableCount)
        }
    }

    @Test fun newSessionCannotBeMutatedByOldRetrySuccessOrFailure() {
        Fixture().use { f ->
            f.start(); f.fail(0); f.timer.next(); f.start("new-session")
            f.success(1, 2, "stale"); f.fail(0)
            assertEquals(1, f.coordinator.status().inFlightCount)
            assertEquals("new-session", f.coordinator.currentCheckpoint()!!.stamp.sessionId)
            assertNull(f.progress()); f.success(2, 3, "fresh")
            f.store.backing.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()) }
            assertEquals("fresh", f.progress()!!.cursor)
        }
    }

    @Test fun compatibleOlderVersionContinuesAndExpiredCursorRestartsAtEquality() {
        for (expired in listOf(false, true)) Fixture(ProviderDescriptor("fixed", "2", 1, setOf("1"))).use { f ->
            val prior = ProviderProgress("profile", "fixed", f.profile.queryKey, "1", 1, "continuation",
                if (expired) f.clock.now else f.clock.now + 1, false, 1)
            f.store.backing.transaction { putProgress(prior) }; f.start()
            if (expired) assertNull(f.runtime.calls[0].request.progress)
            else assertEquals(prior, f.runtime.calls[0].request.progress)
            f.success(0)
            f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
            assertEquals("2", f.progress()!!.providerVersion); assertTrue(f.progress()!!.exhausted)
        }
    }

    companion object {
        private fun result(id: Int) = requireNotNull(ResultNormalizer.normalize(RawVideoResult(
            "https://example.invalid/watch?id=$id", true, title = "Fixture", sourceNamespace = "example.invalid",
            sourceVideoId = id.toString()), "fixed"))
    }
}
