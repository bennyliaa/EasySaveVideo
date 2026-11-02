package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiscoveryPowerCoordinatorTest {
    private val healthy = DiscoveryPowerSignals(true, BatterySignal.NORMAL, ThermalSignal.NORMAL, NetworkSignal.UNMETERED)
    private val conserving = healthy.copy(network = NetworkSignal.METERED)
    private val paused = healthy.copy(thermal = ThermalSignal.SEVERE)

    private class Fixture(providerCount: Int = 1, val store: DiscoveryStore = InMemoryDiscoveryStore(), limits: DiscoveryLimits = DiscoveryLimits()) {
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime()
        var scheduled = 0
        val trackingTimer = object : DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
                scheduled++; return timer.schedule(delayMillis, action)
            }
        }
        val registry = ProviderRegistry((1..providerCount).map { StubProvider(ProviderDescriptor("p$it", "1")) })
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, store, trackingTimer, clock, limits) { 1000 }
        val profile = SearchProfile("profile", "query")
        fun start() { coordinator.setVisibility(true, true); coordinator.start("session", profile) }
    }

    @Test fun enablingUnknownCancelsWithoutHealthPenaltyCursorCommitOrAutomaticRelaxation() {
        val f = Fixture(2); f.start(); assertEquals(2, f.runtime.calls.size)
        f.coordinator.setPowerSignals(DiscoveryPowerSignals(enabled = true))
        val stopped = f.coordinator.status()
        assertEquals(DiscoveryState.SUSPENDED, stopped.state)
        assertEquals(DiscoveryPowerPolicyState.PAUSED, stopped.powerPolicy.state)
        assertEquals(2, stopped.requestsThisRound); assertFalse(stopped.roundActive)
        assertTrue(f.runtime.calls.all { it.cancelled && it.operation.cancellation.isCancelled })
        f.runtime.complete(0, 90, nextCursor = "late"); f.runtime.fail(1, true)
        assertEquals(stopped, f.coordinator.status())
        f.coordinator.setPowerSignals(healthy)
        repeat(20) { f.coordinator.status(); f.coordinator.historyStatistics(); f.coordinator.setVisibility(true, true) }
        f.timer.advance(100_000)
        assertEquals(2, f.runtime.calls.size); assertEquals(0, f.scheduled)
        assertEquals(2, f.coordinator.status().requestsThisRound)
        f.store.transaction {
            assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
            assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(90).fingerprint))
        }
        f.coordinator.requestMore(); assertEquals(4, f.runtime.calls.size)
        assertEquals(2, f.coordinator.status().requestsThisRound)
    }

    @Test fun rapidTightenRelaxAndPriorityVisibilityOverlapNeverRefundsOrInventsDemand() {
        val f = Fixture(3); f.coordinator.setMode(DiscoveryMode.AGGRESSIVE); f.start()
        repeat(50) {
            f.coordinator.setPowerSignals(paused)
            f.coordinator.setVisibility(false, false)
            f.coordinator.setPriority(true, true)
            f.coordinator.setPowerSignals(healthy)
            f.coordinator.setVisibility(true, true)
            f.coordinator.setPriority(false, true)
            f.coordinator.setPowerSignals(conserving)
            f.coordinator.setPriority(false, false)
            f.coordinator.setPowerSignals(DiscoveryPowerSignals())
        }
        assertEquals(3, f.runtime.calls.size)
        assertEquals(3, f.coordinator.status().requestsThisRound)
        assertFalse(f.coordinator.status().roundActive)
        assertEquals(0, f.scheduled)
        f.coordinator.requestMore(); assertEquals(6, f.runtime.calls.size)
    }

    @Test fun powerChangesAfterExhaustionCannotRestartEvenThroughLifecycleAndTaskGates() {
        val f = Fixture(); f.start()
        repeat(6) { f.runtime.complete(it, it + 1, nextCursor = "cursor-$it", exhausted = false) }
        repeat(20) {
            f.coordinator.setVisibility(false, true); f.coordinator.setPriority(true, false)
            f.coordinator.setPowerSignals(conserving); f.coordinator.setPowerSignals(healthy)
            f.coordinator.setVisibility(true, true); f.coordinator.setPriority(false, false)
        }
        assertEquals(6, f.runtime.calls.size); assertEquals(6, f.coordinator.status().requestsThisRound)
        assertFalse(f.coordinator.status().roundActive)
        f.coordinator.requestMore(); assertEquals(7, f.runtime.calls.size)
    }

    @Test fun lifecycleAndMediaPriorityAloneResumeOnlyTheRemainingOriginalBudget() {
        val f = Fixture(); f.start()
        repeat(30) { index ->
            f.coordinator.setPriority(index % 2 == 0, index % 2 != 0)
            f.coordinator.setVisibility(false, true)
            f.coordinator.setPriority(false, false)
            f.coordinator.setVisibility(true, true)
        }
        assertEquals(6, f.runtime.calls.size)
        assertEquals(6, f.coordinator.status().requestsThisRound)
        assertFalse(f.coordinator.status().roundActive)
        assertTrue(f.runtime.calls.all { it.operation.cancellation.isCancelled })
    }

    @Test fun conservativeExplicitRoundsRespectAttemptsIncludingRetriesAndStricterCallerCaps() {
        for (cap in listOf(2, 6)) {
            val f = Fixture(limits = DiscoveryLimits(maxRequestsPerRound = cap))
            f.coordinator.setPowerSignals(conserving); f.coordinator.setMode(DiscoveryMode.AGGRESSIVE); f.start()
            val count = minOf(cap, 3)
            repeat(count) { index -> f.runtime.fail(index, true); f.timer.advance(1000) }
            assertEquals(count, f.runtime.calls.size)
            assertEquals(count, f.coordinator.status().requestsThisRound)
            assertTrue(f.runtime.calls.all { it.operation.context.resourceBudget.maxConcurrentWork == 1 })
            assertEquals(count - 1, f.scheduled)
            f.timer.advance(100_000); assertEquals(count, f.runtime.calls.size)
        }
    }

    @Test fun changedPolicyCancelsScheduledRetryAndItsLateFailureWithoutSchedulingAnotherTimer() {
        val f = Fixture(); f.start(); f.runtime.fail(0, true)
        assertEquals(1, f.scheduled); assertEquals(DiscoveryState.BACKOFF, f.coordinator.status().state)
        f.coordinator.setPowerSignals(conserving)
        assertTrue(f.runtime.calls[0].operation.cancellation.isCancelled)
        f.timer.advance(5000); f.runtime.fail(0, true)
        assertEquals(1, f.runtime.calls.size); assertEquals(1, f.scheduled)
        assertEquals(0, f.coordinator.status().health.getValue("p1").failureCount)
        assertEquals(1, f.coordinator.status().requestsThisRound)
        f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.runtime.calls[1].operation.context.resourceBudget.maxConcurrentWork)
    }

    @Test fun serverCooldownSurvivesPolicyCancellationAndRelaxation() {
        val f = Fixture(); f.start()
        f.runtime.calls[0].callback(ProviderOutcome.Failure(DiscoveryError.RATE_LIMITED, true, 90_000))
        f.coordinator.setPowerSignals(paused); f.coordinator.setPowerSignals(healthy); f.coordinator.requestMore()
        assertEquals(1, f.runtime.calls.size)
        assertEquals(90_000L, f.coordinator.status().health.getValue("p1").cooldownUntilEpochMillis)
        f.timer.advance(89_000); f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
    }

    @Test fun conservingWatermarkNeedsActualPageDemandBelowLowerBound() {
        val f = Fixture(); f.coordinator.setPowerSignals(conserving); f.start()
        f.runtime.complete(0, *(1..40).toList().toIntArray(), nextCursor = "next", exhausted = false)
        assertEquals(1, f.runtime.calls.size); assertFalse(f.coordinator.status().roundActive)
        f.coordinator.requestMore(); assertEquals(1, f.runtime.calls.size)
        f.coordinator.page(0); assertEquals(20, f.coordinator.status().availableCount); assertEquals(1, f.runtime.calls.size)
        f.coordinator.page(1); assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.coordinator.status().requestsThisRound)
    }

    @Test fun sameSnapshotIsReadOnlyUnlessAnImmediateFenceWasIssued() {
        val f = Fixture(); f.coordinator.setPowerSignals(healthy); f.start()
        repeat(100) { f.coordinator.setPowerSignals(healthy) }
        assertEquals(1, f.runtime.calls.size); assertFalse(f.runtime.calls[0].cancelled)
        val generation = f.coordinator.suspendPowerImmediately()
        assertTrue(f.coordinator.status().powerPolicyUpdatePending)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        f.coordinator.setPowerSignals(healthy, generation)
        assertFalse(f.coordinator.status().powerPolicyUpdatePending)
        assertFalse(f.coordinator.status().roundActive)
        assertTrue(f.runtime.calls[0].cancelled)
        f.coordinator.setVisibility(true, true); assertEquals(1, f.runtime.calls.size)
        f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
    }

    @Test fun newerImmediateFenceSurvivesStaleApplyAndStaleApplyCannotReplaceNewerSignals() {
        val f = Fixture(); f.start()
        val first = f.coordinator.suspendPowerImmediately()
        val second = f.coordinator.suspendPowerImmediately()
        f.coordinator.setPowerSignals(healthy, first)
        assertTrue(f.coordinator.status().powerPolicyUpdatePending)
        f.coordinator.setVisibility(true, true); f.coordinator.setPriority(false, false); f.coordinator.requestMore()
        assertEquals(1, f.runtime.calls.size)
        f.coordinator.setPowerSignals(paused, second)
        assertFalse(f.coordinator.status().powerPolicyUpdatePending)
        f.coordinator.setPowerSignals(healthy, first)
        assertEquals(paused, f.coordinator.status().powerPolicy.signals)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        assertFalse(f.coordinator.status().roundActive)
    }

    @Test fun immediatePolicyFenceDoesNotClearIndependentLifecycleOrSaveRecordSuspension() {
        for (save in listOf(false, true)) for (record in listOf(false, true)) {
            val f = Fixture(); f.start(); f.coordinator.suspendImmediately()
            val token = f.coordinator.suspendPowerImmediately()
            f.coordinator.setPriority(save, record)
            f.coordinator.setPowerSignals(healthy, token)
            f.coordinator.requestMore()
            assertEquals(1, f.runtime.calls.size)
            f.coordinator.setVisibility(true, true)
            assertEquals(if (save || record) 1 else 2, f.runtime.calls.size)
            if (save || record) {
                f.coordinator.setPriority(false, false)
                assertEquals(2, f.runtime.calls.size)
            }
        }
    }

    @Test fun powerTransitionRetainsViewCursorHistoryAndStatisticsWithoutWritingStore() {
        val backing = InMemoryDiscoveryStore(); var writes = 0
        val counting = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) { writes++; delegate.putCheckpoint(checkpoint) }
                    override fun putProgress(progress: ProviderProgress) { writes++; delegate.putProgress(progress) }
                    override fun putSeen(record: SeenRecord) { writes++; delegate.putSeen(record) }
                }.block()
            }
        }
        val f = Fixture(store = counting); f.start(); f.runtime.complete(0, 1, nextCursor = "next", exhausted = false)
        val page = f.coordinator.page(0)!!; f.coordinator.recordSeen(page, result(1).fingerprint, SeenEvent.OPENED)
        val checkpoint = f.coordinator.currentCheckpoint(); val counts = f.coordinator.historyStatistics(); val before = writes
        repeat(20) { f.coordinator.setPowerSignals(paused); f.coordinator.setPowerSignals(conserving) }
        assertEquals(before, writes)
        assertEquals(checkpoint, f.coordinator.currentCheckpoint()); assertEquals(page, f.coordinator.page(0))
        assertEquals(counts, f.coordinator.historyStatistics())
        f.store.transaction { assertEquals("next", progress("profile", "p1", f.profile.queryKey)!!.cursor) }
    }

    @Test fun reviseAndRestoreRetainInjectedPolicyButNewCoordinatorDoesNotRestoreSignals() {
        val f = Fixture(); f.coordinator.setPowerSignals(conserving); f.coordinator.setMode(DiscoveryMode.AGGRESSIVE); f.start()
        f.runtime.complete(0, 1, nextCursor = "next", exhausted = false)
        val page = f.coordinator.page(0)!!
        assertTrue(f.coordinator.reviseView(RepeatPolicy.HIDE_SHOWN, pageSize = 2))
        assertEquals(page.orderedResultIds, f.coordinator.page(0)!!.orderedResultIds)
        assertEquals(conserving, f.coordinator.status().powerPolicy.signals)
        assertEquals(2, f.coordinator.status().powerPolicy.lowWatermark)
        assertEquals(4, f.coordinator.status().powerPolicy.highWatermark)
        assertEquals(1, f.runtime.calls.last().operation.context.resourceBudget.maxConcurrentWork)
        f.coordinator.setPowerSignals(paused)
        val calls = f.runtime.calls.size
        assertTrue(f.coordinator.reviseView(RepeatPolicy.SHOW_ALL, pageSize = 3))
        assertTrue(f.coordinator.restore("session"))
        assertEquals(paused, f.coordinator.status().powerPolicy.signals)
        assertEquals(calls, f.runtime.calls.size)
        f.coordinator.setPowerSignals(healthy); assertEquals(calls, f.runtime.calls.size)
        f.coordinator.close()
        val fresh = Fixture(store = f.store)
        assertTrue(fresh.coordinator.restore("session"))
        assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, fresh.coordinator.status().powerPolicy.state)
        assertFalse(fresh.coordinator.status().powerPolicy.signals.enabled)
        assertTrue(fresh.runtime.calls.isEmpty())
        fresh.coordinator.setVisibility(true, true)
        assertEquals(1, fresh.runtime.calls.size)
    }

    @Test fun demandBeforeVisibilityIsDeferredButPowerChangeRetiresIt() {
        val f = Fixture()
        f.coordinator.start("session", f.profile)
        assertTrue(f.coordinator.status().roundActive); assertTrue(f.runtime.calls.isEmpty())
        f.coordinator.setVisibility(true, true); assertEquals(1, f.runtime.calls.size)
        val g = Fixture()
        g.coordinator.start("session", g.profile)
        g.coordinator.setPowerSignals(healthy)
        g.coordinator.setVisibility(true, true)
        assertTrue(g.runtime.calls.isEmpty()); assertFalse(g.coordinator.status().roundActive)
        g.coordinator.requestMore(); assertEquals(1, g.runtime.calls.size)
    }

    @Test fun unknownStartAndZeroSourcesNeverCreateNetworkDemand() {
        val f = Fixture(); f.coordinator.setPowerSignals(DiscoveryPowerSignals(enabled = true)); f.start()
        f.coordinator.requestMore(); assertFalse(f.coordinator.status().roundActive)
        f.coordinator.setPowerSignals(healthy); assertTrue(f.runtime.calls.isEmpty())
        val zero = Fixture(0); zero.coordinator.setPowerSignals(conserving); zero.start()
        repeat(10) { zero.coordinator.requestMore(); zero.coordinator.historyStatistics(); zero.coordinator.status() }
        assertTrue(zero.runtime.calls.isEmpty()); assertEquals(0, zero.coordinator.status().requestsThisRound)
        assertEquals(DiscoveryState.NO_SOURCES, zero.coordinator.status().state)
    }

    @Test fun immediatePowerFenceRollsBackBlockedCommitWithoutWaitingForCoordinatorLock() {
        val backing = InMemoryDiscoveryStore(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val delayed = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun putProgress(progress: ProviderProgress) {
                        entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); delegate.putProgress(progress)
                    }
                }.block()
            }
        }
        val f = Fixture(store = delayed); f.start()
        val done = CountDownLatch(1)
        val worker = Thread { try { f.runtime.complete(0, 1, nextCursor = "late") } finally { done.countDown() } }.apply { start() }
        var generation = 0L
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            generation = f.coordinator.suspendPowerImmediately()
            // The completion thread still holds the coordinator lock and store transaction here.
            assertEquals(1L, done.count)
            assertTrue(f.runtime.calls[0].operation.cancellation.isCancelled)
        } finally { release.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS)); worker.join()
        f.coordinator.setPowerSignals(healthy, generation)
        backing.transaction {
            assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
            assertNull(progress("profile", "p1", f.profile.queryKey)); assertNull(seen("profile", result(1).fingerprint))
        }
        assertEquals(1, f.runtime.calls.size)
        assertEquals(0, f.coordinator.status().health.getValue("p1").failureCount)
    }
    @Test fun immediatePowerFenceDuringProgressReadPreventsLaunchAndAccountingReservation() {
        val backing = InMemoryDiscoveryStore(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val delayed = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val delegate = this
                object : DiscoveryTransaction by delegate {
                    override fun progress(profileId: String, providerId: String, queryKey: String): ProviderProgress? {
                        entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
                        return delegate.progress(profileId, providerId, queryKey)
                    }
                }.block()
            }
        }
        val f = Fixture(store = delayed); val done = CountDownLatch(1)
        val worker = Thread { try { f.start() } finally { done.countDown() } }.apply { start() }
        var generation = 0L
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            generation = f.coordinator.suspendPowerImmediately()
            assertEquals(1L, done.count)
        } finally { release.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS)); worker.join()
        f.coordinator.setPowerSignals(healthy, generation)
        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(0, f.coordinator.status().requestsThisRound)
        assertFalse(f.coordinator.status().roundActive)
    }

    @Test fun retainedPowerStatusIsAnImmutableSnapshotAndNeverMutatesOnRelaxation() {
        val f = Fixture(); f.coordinator.setPowerSignals(conserving); f.start()
        val before = f.coordinator.status()
        f.coordinator.setPowerSignals(healthy)
        assertEquals(conserving, before.powerPolicy.signals)
        assertEquals(DiscoveryPowerPolicyState.CONSERVING, before.powerPolicy.state)
        assertEquals(1, before.powerPolicy.concurrency)
        assertEquals(DiscoveryPowerPolicyState.UNRESTRICTED, f.coordinator.status().powerPolicy.state)
        assertEquals(1, f.runtime.calls.size)
        assertEquals(before.requestsThisRound, f.coordinator.status().requestsThisRound)
    }

    @Test fun pendingPowerFencePreventsDeferredMaterializationFromRenewingExhaustedAccounting() {
        val f = Fixture(); f.start()
        repeat(6) { f.runtime.complete(it, it + 1, nextCursor = "cursor-$it", exhausted = false) }
        val generation = f.coordinator.suspendPowerImmediately()
        assertNotNull(f.coordinator.page(0))
        f.coordinator.requestMore()
        assertEquals(6, f.coordinator.status().requestsThisRound)
        assertFalse(f.coordinator.status().roundActive)
        f.coordinator.setPowerSignals(healthy, generation)
        assertEquals(6, f.coordinator.status().requestsThisRound)
        assertEquals(6, f.runtime.calls.size)
        f.coordinator.requestMore(); assertEquals(7, f.runtime.calls.size)
    }

    @Test fun staleSearchResumeCannotClearNewerImmediateSaveSuspension() {
        val f = Fixture(); f.start()
        val searchGeneration = f.coordinator.suspendImmediately()
        assertEquals(searchGeneration, f.coordinator.currentSuspensionGeneration())
        val saveGeneration = f.coordinator.suspendImmediately()
        f.coordinator.start("session", f.profile.copy(query = "next"))
        f.coordinator.setPriority(false, false) // Snapshot captured before Save became active.
        f.coordinator.setVisibility(true, true, searchGeneration)
        assertEquals(1, f.runtime.calls.size)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        f.coordinator.setPriority(true, false)
        f.coordinator.setVisibility(true, true, saveGeneration)
        assertEquals(1, f.runtime.calls.size)
        f.coordinator.setPriority(false, false)
        assertEquals(2, f.runtime.calls.size)
        assertEquals("next", f.runtime.calls.last().request.profile.query)
        assertEquals(1, f.coordinator.status().requestsThisRound)
    }

    @Test fun olderVisibilityApplyCannotReplaceNewerAppliedHiddenState() {
        val f = Fixture(); f.start()
        val older = f.coordinator.suspendImmediately()
        val newer = f.coordinator.suspendImmediately()
        f.coordinator.setVisibility(true, false, newer)
        f.coordinator.setVisibility(true, true, older)
        assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
        assertEquals(1, f.runtime.calls.size)
        f.coordinator.setVisibility(true, true, newer)
        assertEquals(2, f.runtime.calls.size)
        assertEquals(2, f.coordinator.status().requestsThisRound)
    }

    @Test fun lifecycleAndPowerGenerationAcknowledgementsCannotClearEachOthersPendingFence() {
        for (powerFirst in listOf(false, true)) {
            val f = Fixture(); f.start()
            val lifecycle = f.coordinator.suspendImmediately()
            val power = f.coordinator.suspendPowerImmediately()
            if (powerFirst) {
                f.coordinator.setPowerSignals(healthy, power)
                f.coordinator.requestMore()
                assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
                assertEquals(1, f.runtime.calls.size)
                f.coordinator.setVisibility(true, true, lifecycle)
                assertEquals(2, f.runtime.calls.size)
            } else {
                f.coordinator.setVisibility(true, true, lifecycle)
                assertEquals(DiscoveryState.SUSPENDED, f.coordinator.status().state)
                assertEquals(1, f.runtime.calls.size)
                f.coordinator.setPowerSignals(healthy, power)
                assertEquals(1, f.runtime.calls.size)
                assertFalse(f.coordinator.status().roundActive)
                f.coordinator.requestMore(); assertEquals(2, f.runtime.calls.size)
            }
        }
    }

}
