package com.easysavevideo.discovery

import com.easysavevideo.app.discovery.BuildDiscoveryPower
import com.easysavevideo.app.discovery.DiscoveryPowerPresentationPolicy
import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy
import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Independent ordinary injected host fixtures; no Android, OS observations, or network. */
class IndependentPowerReviewTest {
    private val healthy = DiscoveryPowerSignals(true, BatterySignal.NORMAL, ThermalSignal.NORMAL, NetworkSignal.UNMETERED)
    private val conservative = healthy.copy(battery = BatterySignal.LOW)
    private val pause = healthy.copy(network = NetworkSignal.OFFLINE)
    private class Clock(var value: Long = 1_000) : EpochClock { override fun nowMillis() = value }
    private class Timer : DiscoveryTimer {
        data class Item(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val items = mutableListOf<Item>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            val item = Item(delayMillis, action); items += item
            return CancellationHandle { item.cancelled = true }
        }
        // Deliberately executes cancelled callbacks too, to verify identity fences independently.
        fun deliver(index: Int) = items[index].action()
    }
    private class Source(override val descriptor: ProviderDescriptor) : VideoSearchProvider {
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
            error("The independent injected runtime is the sole result source")
    }
    private class Runtime : ProviderRuntime {
        data class Call(val provider: VideoSearchProvider, val request: SearchRequest,
            val operation: DiscoveryOperation, val callback: (ProviderOutcome) -> Unit, var cancelled: Boolean = false)
        val calls = mutableListOf<Call>()
        var callbackOnCancel = false
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle {
            val call = Call(provider, request, operation, callback); calls += call
            return CancellationHandle {
                call.cancelled = true
                if (callbackOnCancel) callback(ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE, true))
            }
        }
        fun success(index: Int, ids: List<Int>, cursor: String? = "cursor-$index", exhausted: Boolean = false) {
            val call = calls[index]
            call.callback(ProviderOutcome.Success(ValidatedProviderPage(ids.map { row(it, call.provider.descriptor.id) }, cursor, null, exhausted)))
        }
        fun failure(index: Int, retryable: Boolean = true, retryAt: Long? = null) = calls[index].callback(
            ProviderOutcome.Failure(if (retryAt == null) DiscoveryError.PROVIDER_FAILURE else DiscoveryError.RATE_LIMITED, retryable, retryAt))
    }
    private class Rig(val store: DiscoveryStore = InMemoryDiscoveryStore(), sources: Int = 1,
        limits: DiscoveryLimits = DiscoveryLimits()) : AutoCloseable {
        val clock = Clock(); val timer = Timer(); val runtime = Runtime()
        val profile = SearchProfile("power-profile", "ordinary synthetic query", historyPolicy = RepeatPolicy.SHOW_ALL)
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry((1..sources).map { Source(ProviderDescriptor("p$it", "1")) }),
            runtime, store, timer, clock, limits) { 1_000 }
        fun start() { coordinator.setVisibility(true, true); coordinator.start("power-session", profile) }
        override fun close() = coordinator.close()
    }
    companion object {
        private fun row(id: Int, provider: String = "p1") = requireNotNull(ResultNormalizer.normalize(
            RawVideoResult("https://independent.example/video/$id", true, title = "Synthetic $id",
                sourceNamespace = "independent.example", sourceVideoId = id.toString()), provider))
    }

    @Test fun completeSignalMatrixOnlyIntersectsExistingLimits() {
        val limits = listOf(DiscoveryLimits(), DiscoveryLimits(pageSize = 1, poolCapacity = 5, maxRequestsPerRound = 1),
            DiscoveryLimits(pageSize = 100, poolCapacity = 500, maxRequestsPerRound = 2))
        for (l in limits) for (mode in DiscoveryMode.entries) for (enabled in listOf(false, true))
            for (battery in BatterySignal.entries) for (thermal in ThermalSignal.entries) for (network in NetworkSignal.entries) {
                val s = DiscoveryPowerSignals(enabled, battery, thermal, network)
                val p = DiscoveryPowerPolicy.evaluate(s, mode, l)
                val stop = enabled && (battery in setOf(BatterySignal.UNKNOWN, BatterySignal.CRITICAL) ||
                    thermal in setOf(ThermalSignal.UNKNOWN, ThermalSignal.SEVERE) || network in setOf(NetworkSignal.UNKNOWN, NetworkSignal.OFFLINE))
                val reduce = enabled && !stop && (battery == BatterySignal.LOW || thermal == ThermalSignal.WARM || network == NetworkSignal.METERED)
                assertEquals(s, p.signals); assertEquals(mode, p.requestedMode)
                assertEquals(!stop, p.allowsRequests)
                assertTrue(p.concurrency in 0..l.concurrency(mode)); assertTrue(p.maxRequestsPerRound in 0..l.maxRequestsPerRound)
                assertTrue(p.lowWatermark in 0..l.lowWatermark); assertTrue(p.highWatermark in p.lowWatermark..l.highWatermark)
                when {
                    !enabled -> { assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, p.state); assertEquals(l.concurrency(mode), p.concurrency) }
                    stop -> { assertEquals(DiscoveryPowerPolicyState.PAUSED, p.state); assertEquals(0, p.maxRequestsPerRound); assertEquals(0, p.highWatermark) }
                    reduce -> { assertEquals(DiscoveryPowerPolicyState.CONSERVING, p.state); assertEquals(1, p.concurrency)
                        assertEquals(minOf(3, l.maxRequestsPerRound), p.maxRequestsPerRound); assertEquals(l.pageSize, p.lowWatermark) }
                    else -> { assertEquals(DiscoveryPowerPolicyState.UNRESTRICTED, p.state); assertEquals(mode, p.effectiveMode) }
                }
            }
    }

    @Test fun schedulerHonorsEveryLifecycleAndPriorityGateWithAllPolicyStates() {
        for (signals in listOf(DiscoveryPowerSignals(), healthy, conservative, pause, DiscoveryPowerSignals(enabled = true)))
            for (foreground in listOf(false, true)) for (visible in listOf(false, true))
                for (save in listOf(false, true)) for (record in listOf(false, true)) {
                    val s = DiscoveryScheduler(); s.powerSignals(signals); s.visibility(foreground, visible); s.priority(save, record)
                    s.beginRound(0)
                    val expected = foreground && visible && !save && !record && s.powerPolicy.allowsRequests
                    assertEquals(expected, s.reserve("p1", 0)); assertEquals(if (expected) 1 else 0, s.requestsThisRound)
                }
    }

    @Test fun initializationSnapshotAtGenerationZeroCannotConsumeNewerImmediateFence() {
        Rig().use { r ->
            val capturedBeforeUserChange = DiscoveryPowerSignals()
            val newer = r.coordinator.suspendPowerImmediately()
            r.coordinator.setPowerSignals(capturedBeforeUserChange, 0L)
            r.coordinator.start("power-session", r.profile)
            r.coordinator.setVisibility(true, true)
            assertTrue(r.coordinator.status().powerPolicyUpdatePending); assertTrue(r.runtime.calls.isEmpty())
            r.coordinator.setPowerSignals(pause, newer)
            assertFalse(r.coordinator.status().roundActive); assertTrue(r.runtime.calls.isEmpty())
            r.coordinator.setPowerSignals(healthy); r.coordinator.setVisibility(true, true)
            assertTrue(r.runtime.calls.isEmpty())
            r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
        }
    }

    @Test fun capturedLifecycleSnapshotCannotClearNewerSaveOrForegroundSuspension() {
        for (save in listOf(false, true)) Rig().use { r ->
            r.start()
            val captured = r.coordinator.currentSuspensionGeneration()
            val newer = r.coordinator.suspendImmediately()
            r.coordinator.start("power-session", r.profile.copy(query = "new explicit search"))
            // The controller helper captured these flags before the newer owner event.
            r.coordinator.setPriority(false, false); r.coordinator.setVisibility(true, true, captured)
            assertEquals(1, r.runtime.calls.size); assertEquals(DiscoveryState.SUSPENDED, r.coordinator.status().state)
            r.coordinator.setPriority(save, false); r.coordinator.setVisibility(!save, false, newer)
            r.coordinator.setVisibility(true, true, captured)
            assertEquals(1, r.runtime.calls.size)
            r.coordinator.setPriority(false, false); r.coordinator.setVisibility(true, true, newer)
            assertEquals(2, r.runtime.calls.size)
            assertEquals("new explicit search", r.runtime.calls.last().request.profile.query)
        }
    }

    @Test fun coordinatorPublicationStartsClosedAndGenerationBeforeFlagReadFencesNewBusyState() {
        Rig().use { r ->
            r.coordinator.start("power-session", r.profile)
            val capturedAfterPublication = r.coordinator.currentSuspensionGeneration()
            val saveBeforeHelperApply = r.coordinator.suspendImmediately()
            r.coordinator.setPriority(false, false)
            r.coordinator.setVisibility(true, true, capturedAfterPublication)
            assertTrue(r.runtime.calls.isEmpty())
            // Latest helper observes owner busy before acknowledging the same generation.
            r.coordinator.setPriority(true, false)
            r.coordinator.setVisibility(true, true, saveBeforeHelperApply)
            assertTrue(r.runtime.calls.isEmpty())
            r.coordinator.setPriority(false, false)
            assertEquals(1, r.runtime.calls.size)
        }
    }

    @Test fun independentLifecycleAndPowerAcknowledgementsCannotReopenOtherPendingFence() {
        for (powerFirst in listOf(false, true)) Rig().use { r ->
            r.start(); val life = r.coordinator.suspendImmediately(); val power = r.coordinator.suspendPowerImmediately()
            if (powerFirst) r.coordinator.setPowerSignals(healthy, power)
            else r.coordinator.setVisibility(true, true, life)
            assertEquals(DiscoveryState.SUSPENDED, r.coordinator.status().state)
            assertEquals(1, r.runtime.calls.size)
            if (powerFirst) r.coordinator.setVisibility(true, true, life)
            else r.coordinator.setPowerSignals(healthy, power)
            assertEquals(1, r.runtime.calls.size); assertFalse(r.coordinator.status().roundActive)
            r.coordinator.requestMore(); assertEquals(2, r.runtime.calls.size)
        }
    }

    @Test fun pageMaterializationUnderPendingPowerCannotRenewExhaustedRoundAccounting() {
        Rig().use { r ->
            r.start(); repeat(6) { r.runtime.success(it, listOf(it)) }
            assertEquals(6, r.runtime.calls.size); assertFalse(r.coordinator.status().roundActive)
            val generation = r.coordinator.suspendPowerImmediately()
            assertNotNull(r.coordinator.page(0)); r.coordinator.requestMore()
            assertEquals(6, r.coordinator.status().requestsThisRound); assertFalse(r.coordinator.status().roundActive)
            r.coordinator.setPowerSignals(healthy, generation)
            assertEquals(6, r.runtime.calls.size); assertEquals(6, r.coordinator.status().requestsThisRound)
            r.coordinator.requestMore(); assertEquals(7, r.runtime.calls.size)
        }
    }

    @Test fun unknownObservationsDoNotAccumulateFutureAutomaticDemand() {
        Rig().use { r ->
            r.coordinator.setPowerSignals(DiscoveryPowerSignals(enabled = true)); r.start()
            repeat(30) { r.coordinator.requestMore(); r.coordinator.status(); r.coordinator.setVisibility(true, true) }
            assertFalse(r.coordinator.status().roundActive); assertTrue(r.runtime.calls.isEmpty())
            r.coordinator.setPowerSignals(healthy)
            repeat(30) { r.coordinator.historyStatistics(); r.coordinator.status(); r.coordinator.setVisibility(true, true) }
            assertTrue(r.runtime.calls.isEmpty()); assertTrue(r.timer.items.isEmpty())
            r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
        }
    }

    @Test fun distinctGenerationsRejectOldApplicationAndKeepNewestFenceClosed() {
        Rig(sources = 3).use { r ->
            r.coordinator.setMode(DiscoveryMode.AGGRESSIVE); r.start()
            val a = r.coordinator.suspendPowerImmediately(); val b = r.coordinator.suspendPowerImmediately()
            r.coordinator.setPowerSignals(conservative, a)
            assertTrue(r.coordinator.status().powerPolicyUpdatePending)
            r.coordinator.requestMore(); r.coordinator.setVisibility(true, true); r.coordinator.setPriority(false, false)
            assertEquals(3, r.runtime.calls.size)
            r.coordinator.setPowerSignals(pause, b); r.coordinator.setPowerSignals(healthy, a)
            assertEquals(pause, r.coordinator.status().powerPolicy.signals); assertFalse(r.coordinator.status().roundActive)
            repeat(3) { r.runtime.success(it, listOf(50 + it)); r.runtime.failure(it) }
            assertTrue(r.coordinator.currentCheckpoint()!!.pool.entries.isEmpty())
            assertTrue(r.coordinator.status().health.values.all { it.failureCount == 0 })
            assertEquals(3, r.coordinator.status().requestsThisRound); assertTrue(r.timer.items.isEmpty())
        }
    }

    @Test fun lifecycleChurnSpendsOnlyOriginalRoundAndCannotRefundCancelledAttempts() {
        Rig().use { r ->
            r.start()
            repeat(80) { index ->
                r.coordinator.suspendImmediately(); r.coordinator.setVisibility(false, false)
                r.coordinator.setPriority(index % 2 == 0, index % 2 != 0)
                r.coordinator.setVisibility(true, true); r.coordinator.setPriority(false, false)
            }
            assertEquals(6, r.runtime.calls.size); assertEquals(6, r.coordinator.status().requestsThisRound)
            assertFalse(r.coordinator.status().roundActive); assertTrue(r.timer.items.isEmpty())
            r.runtime.calls.indices.forEach { r.runtime.failure(it) }
            assertEquals(0, r.coordinator.status().health.getValue("p1").failureCount)
        }
    }

    @Test fun changingSignalsRetiresDemandWithoutChangingSpentAccountingOrAddingTimers() {
        Rig(sources = 3).use { r ->
            r.coordinator.setMode(DiscoveryMode.AGGRESSIVE); r.start()
            repeat(100) {
                r.coordinator.setPowerSignals(conservative); r.coordinator.setPowerSignals(pause)
                r.coordinator.setVisibility(false, true); r.coordinator.setPriority(true, true)
                r.coordinator.setPowerSignals(healthy); r.coordinator.setPowerSignals(DiscoveryPowerSignals())
                r.coordinator.setVisibility(true, true); r.coordinator.setPriority(false, false)
            }
            assertEquals(3, r.runtime.calls.size); assertEquals(3, r.coordinator.status().requestsThisRound)
            assertFalse(r.coordinator.status().roundActive); assertTrue(r.timer.items.isEmpty())
            r.coordinator.requestMore(); assertEquals(6, r.runtime.calls.size)
        }
    }

    @Test fun concurrentPowerAndMediaFencesCannotReopenEachOther() {
        for (save in listOf(false, true)) for (record in listOf(false, true)) Rig().use { r ->
            r.start(); r.coordinator.suspendImmediately(); val revision = r.coordinator.suspendPowerImmediately()
            r.coordinator.setPriority(save, record); r.coordinator.setPowerSignals(conservative, revision)
            r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
            r.coordinator.setVisibility(true, true)
            assertEquals(if (save || record) 1 else 2, r.runtime.calls.size)
            if (save || record) { r.coordinator.setPriority(false, false); assertEquals(2, r.runtime.calls.size) }
        }
    }

    @Test fun synchronousCancelCallbacksAndLaterUncooperativeSuccessCannotCommitOrPenalize() {
        Rig(sources = 2).use { r ->
            r.runtime.callbackOnCancel = true; r.start(); r.coordinator.setPowerSignals(pause)
            r.runtime.calls.indices.forEach { r.runtime.success(it, listOf(99)); r.runtime.failure(it) }
            assertEquals(2, r.runtime.calls.size); assertEquals(0, r.coordinator.status().availableCount)
            assertTrue(r.timer.items.isEmpty()); assertTrue(r.runtime.calls.all { it.cancelled && it.operation.cancellation.isCancelled })
            r.store.transaction { assertEquals(0, historyStatistics().seenAliasCount); assertEquals(0, historyStatistics().progressCount) }
            assertTrue(r.coordinator.status().health.values.all { it.failureCount == 0 && it.lastError == null })
        }
    }

    @Test fun conservativeRetryBudgetCountsAttemptsAndNeverRenewsDeadlineOrResourceBound() {
        for (cap in 1..6) Rig(limits = DiscoveryLimits(maxRequestsPerRound = cap)).use { r ->
            r.coordinator.setPowerSignals(conservative); r.start()
            val attempts = minOf(cap, 3); val deadline = r.runtime.calls.single().operation.context.deadlineEpochMillis
            repeat(attempts) { index ->
                r.runtime.failure(index)
                if (index + 1 < attempts) { r.clock.value += 1_000; r.timer.deliver(index) }
            }
            assertEquals(attempts, r.runtime.calls.size); assertEquals(attempts - 1, r.timer.items.size)
            r.runtime.calls.forEach { assertEquals(deadline, it.operation.context.deadlineEpochMillis)
                assertEquals(1, it.operation.context.resourceBudget.maxConcurrentWork) }
            assertEquals(attempts, r.coordinator.status().requestsThisRound)
        }
    }

    @Test fun leakedCancelledRetryCannotLaunchOrDamageNewExplicitRound() {
        Rig().use { r ->
            r.start(); r.runtime.failure(0); assertEquals(1, r.timer.items.size)
            r.coordinator.setPowerSignals(conservative); assertTrue(r.timer.items.single().cancelled)
            r.coordinator.requestMore(); assertEquals(2, r.runtime.calls.size)
            r.clock.value += 2_000; r.timer.deliver(0); r.runtime.failure(0)
            assertEquals(2, r.runtime.calls.size); assertEquals(1, r.timer.items.size)
            assertEquals(1, r.coordinator.status().inFlightCount)
            assertEquals(0, r.coordinator.status().health.getValue("p1").failureCount)
            r.runtime.success(1, listOf(1), exhausted = true)
            assertEquals(listOf(row(1).fingerprint), r.coordinator.currentCheckpoint()!!.pool.entries.map { it.result.fingerprint })
        }
    }

    @Test fun backwardsAndForwardsClockChangesCannotBypassRetryAfterOrResurrectCancelledTimer() {
        Rig().use { r ->
            r.start(); r.runtime.failure(0, retryAt = 10_000)
            assertEquals(9_000L, r.timer.items.single().delay)
            r.coordinator.setPowerSignals(pause); r.coordinator.setPowerSignals(healthy)
            r.clock.value = 500; r.timer.deliver(0); r.coordinator.requestMore()
            assertEquals(1, r.runtime.calls.size)
            r.clock.value = 9_999; r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
            r.clock.value = 10_000; r.coordinator.requestMore(); assertEquals(2, r.runtime.calls.size)
            assertEquals(25_000L, r.runtime.calls[1].operation.context.deadlineEpochMillis)
            assertEquals(1, r.timer.items.size)
        }
    }

    @Test fun delayedRetryPastDeadlineStopsAndNeedsFreshExplicitDemandAfterCooldown() {
        Rig().use { r ->
            r.coordinator.setPowerSignals(conservative); r.start(); r.runtime.failure(0)
            r.clock.value = 16_000; r.timer.deliver(0)
            assertEquals(1, r.runtime.calls.size)
            assertEquals(DiscoveryError.DEADLINE_EXCEEDED, r.coordinator.status().lastError)
            r.clock.value = 100_000; r.coordinator.status(); r.coordinator.setVisibility(true, true)
            assertEquals(1, r.runtime.calls.size)
            r.coordinator.requestMore(); assertEquals(2, r.runtime.calls.size)
        }
    }

    @Test fun effectiveWatermarkCountsUnmaterializedRowsAndOnlyPageDemandReopensRound() {
        Rig(limits = DiscoveryLimits(pageSize = 2, poolCapacity = 15)).use { r ->
            r.coordinator.setPowerSignals(conservative); r.start(); r.runtime.success(0, listOf(1, 2, 3, 4))
            assertEquals(4, r.coordinator.status().availableCount); assertEquals(1, r.runtime.calls.size)
            r.coordinator.requestMore(); assertEquals(1, r.runtime.calls.size)
            r.coordinator.page(0); assertEquals(2, r.coordinator.status().availableCount); assertEquals(1, r.runtime.calls.size)
            r.coordinator.page(1); assertEquals(2, r.runtime.calls.size)
            assertEquals(1, r.coordinator.status().requestsThisRound)
        }
    }

    @Test fun powerFenceDuringBlockedStoreCommitRollsBackRowsCursorAndAliasFactsAtomically() {
        val backing = InMemoryDiscoveryStore(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
                val original = this
                object : DiscoveryTransaction by original {
                    override fun putProgress(progress: ProviderProgress) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); original.putProgress(progress)
                    }
                }.block()
            }
        }
        Rig(store).use { r ->
            r.start(); val failure = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
            val thread = Thread { try { r.runtime.success(0, listOf(7)) } catch (t: Throwable) { failure.set(t) } finally { done.countDown() } }
            thread.start(); var revision = 0L
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); revision = r.coordinator.suspendPowerImmediately()
                assertEquals(1L, done.count); assertTrue(r.runtime.calls.single().operation.cancellation.isCancelled)
            } finally { release.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS)); thread.join(); failure.get()?.let { throw AssertionError(it) }
            r.coordinator.setPowerSignals(healthy, revision)
            backing.transaction { assertTrue(checkpoint("power-session")!!.pool.entries.isEmpty())
                assertEquals(0, historyStatistics().progressCount); assertEquals(0, historyStatistics().seenAliasCount) }
            assertEquals(1, r.runtime.calls.size); assertEquals(0, r.coordinator.status().health.getValue("p1").failureCount)
        }
    }

    @Test fun sameSnapshotIsNonMutatingButItsExplicitFenceStillRetiresTheRound() {
        Rig().use { r ->
            r.coordinator.setPowerSignals(healthy); r.start()
            repeat(100) { r.coordinator.setPowerSignals(healthy) }
            assertFalse(r.runtime.calls.single().cancelled); assertTrue(r.coordinator.status().roundActive)
            val revision = r.coordinator.suspendPowerImmediately(); r.coordinator.setPowerSignals(healthy, revision)
            assertTrue(r.runtime.calls.single().cancelled); assertFalse(r.coordinator.status().roundActive)
            r.coordinator.setVisibility(true, true); assertEquals(1, r.runtime.calls.size)
        }
    }

    @Test fun changingPowerPreservesCommittedPageIdentityCursorAndIndependentHistoryFacts() {
        Rig().use { r ->
            r.start(); r.runtime.success(0, listOf(1, 2), "kept", true)
            val page = requireNotNull(r.coordinator.page(0)); val result = r.coordinator.results(page).first()
            assertTrue(r.coordinator.recordSeen(page, result.fingerprint, SeenEvent.OPENED, 400))
            assertTrue(r.coordinator.recordSeen(page, result.fingerprint, SeenEvent.DISMISSED, 500))
            val checkpoint = r.coordinator.currentCheckpoint(); val counts = r.coordinator.historyStatistics()
            repeat(30) { r.coordinator.setPowerSignals(pause); r.coordinator.setPowerSignals(conservative) }
            assertEquals(checkpoint, r.coordinator.currentCheckpoint()); assertEquals(page, r.coordinator.page(0))
            assertEquals(counts, r.coordinator.historyStatistics())
            assertTrue(r.coordinator.recordSeen(page, result.fingerprint, SeenEvent.OPENED, 20))
            r.store.transaction {
                assertEquals("kept", progress(r.profile.profileId, "p1", r.profile.queryKey)!!.cursor)
                result.identityAliases.forEach { alias -> val facts = requireNotNull(seen(r.profile.profileId, alias))
                    assertEquals(400L, facts.openedAt); assertEquals(500L, facts.dismissedAt)
                    assertNull(facts.savedAt); assertNull(facts.playedAt) }
            }
        }
    }

    @Test fun restoreKeepsCommittedQueryAndViewButDoesNotPersistSyntheticPower() {
        val store = InMemoryDiscoveryStore(); var oldStamp: SessionStamp? = null
        Rig(store).use { r ->
            r.coordinator.setPowerSignals(conservative); r.start(); r.runtime.success(0, listOf(1), "resume", false)
            r.coordinator.page(0); r.coordinator.setPowerSignals(pause)
            oldStamp = r.coordinator.currentCheckpoint()!!.stamp
            assertTrue(r.coordinator.restore("power-session")); assertEquals(pause, r.coordinator.status().powerPolicy.signals)
        }
        Rig(store).use { r ->
            assertTrue(r.coordinator.restore("power-session")); assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, r.coordinator.status().powerPolicy.state)
            assertTrue(r.coordinator.currentCheckpoint()!!.stamp.epoch > oldStamp!!.epoch)
            assertEquals(r.profile, r.coordinator.currentCheckpoint()!!.profile)
            assertTrue(r.runtime.calls.isEmpty()); r.coordinator.setPriority(true, false); r.coordinator.setVisibility(true, true)
            assertTrue(r.runtime.calls.isEmpty()); r.coordinator.setPriority(false, false)
            assertEquals("resume", r.runtime.calls.single().request.progress!!.cursor)
        }
    }

    @Test fun zeroSourcesAndReadOnlyDemandPolicyNeverManufactureWork() {
        Rig(sources = 0).use { r ->
            r.coordinator.setPowerSignals(conservative); r.start()
            repeat(20) { r.coordinator.historyStatistics(); r.coordinator.status(); r.coordinator.requestMore() }
            assertEquals(DiscoveryState.NO_SOURCES, r.coordinator.status().state); assertEquals(0, r.coordinator.status().requestsThisRound)
            assertTrue(r.runtime.calls.isEmpty()); assertTrue(r.timer.items.isEmpty())
        }
        for (readOnly in listOf(false, true)) for (pending in listOf(false, true))
            assertEquals(!readOnly && pending, DiscoveryPresentationPolicy.mayFulfillPageDemand(readOnly, pending, true, true, false, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, false, false, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, true, true, true))
    }

    @Test fun debugAndReleaseScenarioContractsAndPowerLabelsAreTruthful() {
        val ids = listOf("disconnected", "normal", "conserving", "unknown", "paused")
        if (System.getProperty("esv.review.variant") == "release") {
            assertTrue(BuildDiscoveryPower.scenarios.isEmpty())
            (ids + "unregistered").forEach { assertNull(BuildDiscoveryPower.selection(it)) }
        } else {
            assertEquals(ids.toSet(), BuildDiscoveryPower.scenarios.map { it.id }.toSet())
            assertNull(BuildDiscoveryPower.selection("unregistered"))
            assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, DiscoveryPowerPolicy.evaluate(BuildDiscoveryPower.selection("disconnected")!!.signals, DiscoveryMode.BALANCED).state)
            listOf("unknown", "paused").forEach { assertFalse(DiscoveryPowerPolicy.evaluate(BuildDiscoveryPower.selection(it)!!.signals, DiscoveryMode.AGGRESSIVE).allowsRequests) }
        }
        val lines = DiscoveryPowerPresentationPolicy.lines(DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(), DiscoveryMode.BALANCED), true, false).joinToString()
        assertTrue(lines.contains("未接入 Android")); assertTrue(lines.contains("未测量")); assertTrue(lines.contains("尚未完成"))
        assertTrue(lines.contains("恢复不会自动开启新一轮"))
    }
}
