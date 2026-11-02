package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Host coordinator/runtime contracts only; no device, network or first-visible measurements. */
class ProviderCallbackLatencyIntegrationTest {
    private class TickClock(@Volatile var now: Long = 0) : ProviderLatencyTickClock {
        var reads = 0
        var fail = false
        var onRead: (() -> Unit)? = null
        override fun nowNanos(): Long {
            reads++
            if (fail) throw IllegalStateException("private-clock-detail")
            val value = now
            onRead?.invoke()
            return value
        }
    }

    private class Fixture(
        providers: Int = 1,
        enabled: Boolean = true,
        attach: Boolean = true,
        capacity: Int = 128,
        val ticks: TickClock = TickClock(),
        val store: DiscoveryStore = InMemoryDiscoveryStore(),
    ) {
        val epoch = FakeClock()
        val timer = FakeTimer(epoch)
        val runtime = FakeRuntime()
        val sources = (1..providers).map { StubProvider(ProviderDescriptor("private-provider-$it", "1")) }
        val recorder = ProviderCallbackLatencyRecorder(enabled, ticks, capacity)
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(sources), runtime, store, timer, epoch,
            providerCallbackLatency = recorder.takeIf { attach }) { 1000 }
        val profile = SearchProfile("private-profile", "private query")
        fun start(session: String = "private-session") {
            coordinator.setVisibility(true, true)
            coordinator.start(session, profile)
        }
        fun snapshot() = recorder.snapshot()
    }

    @Test fun disabledAndNullInjectionReadNoDiagnosticClockAndCollectNothing() {
        for ((enabled, attach) in listOf(false to true, true to false)) {
            val f = Fixture(enabled = enabled, attach = attach)
            f.ticks.fail = true
            f.start()
            f.runtime.fail(0, retryable = true)
            f.timer.advance(1000)
            f.runtime.complete(1, 1)
            f.coordinator.start("other-session", f.profile.copy(query = "other query"))
            f.coordinator.setVisibility(false, false)
            f.runtime.complete(2, 2)
            f.coordinator.close()
            assertEquals(0, f.ticks.reads)
            assertTrue(f.snapshot().samples.isEmpty())
            assertEquals(0, f.snapshot().pendingAttempts)
            assertEquals(0L, f.snapshot().callbacksObserved)
        }
        assertFalse(ProviderCallbackLatencyRecorder().snapshot().enabled)
    }

    @Test fun siblingCallbacksAndLaterPagesHaveIndependentIntervalsAndStableSourceAliases() {
        val f = Fixture(providers = 2)
        f.start()
        f.ticks.now = 25
        f.runtime.complete(0, 1, nextCursor = "private-cursor", exhausted = false)
        assertEquals(3, f.runtime.calls.size)
        f.ticks.now = 80
        f.runtime.complete(1, 2)
        f.ticks.now = 120
        f.runtime.complete(2, 3)
        val samples = f.snapshot().samples
        assertEquals(listOf(25L, 80L, 95L), samples.map { it.elapsedNanos })
        assertEquals(listOf(1, 2, 1), samples.map { it.sourceAlias })
        assertEquals(listOf(1L, 2L, 3L), samples.map { it.operationAlias })
        assertEquals(3L, f.snapshot().callbacksObserved)
        assertFalse(f.snapshot().incomplete)
        assertEquals(3, f.coordinator.status().availableCount)
    }

    @Test fun retriesExcludeBackoffAndOldDuplicateCallbacksCannotFinishNewAttempts() {
        val f = Fixture()
        f.start()
        val first = f.runtime.calls[0].operation
        f.ticks.now = 30
        f.runtime.fail(0, retryable = true)
        f.ticks.now = 10_000
        f.timer.advance(1000)
        val second = f.runtime.calls[1].operation
        val reads = f.ticks.reads
        f.runtime.complete(0, 9)
        f.runtime.fail(0, retryable = true)
        assertEquals(reads, f.ticks.reads)
        assertEquals(1, f.snapshot().pendingAttempts)
        f.ticks.now = 10_045
        f.runtime.complete(1, 1)
        val samples = f.snapshot().samples
        assertEquals(listOf(0, 1), samples.map { it.attempt })
        assertEquals(listOf(30L, 45L), samples.map { it.elapsedNanos })
        assertEquals(listOf(ProviderCallbackTerminal.FAILURE_REPORTED, ProviderCallbackTerminal.SUCCESS_REPORTED), samples.map { it.terminal })
        assertSame(first.cancellation, second.cancellation)
        assertEquals(first.context.deadlineEpochMillis, second.context.deadlineEpochMillis)
        assertEquals(2, f.coordinator.status().requestsThisRound)
        assertEquals(1, f.coordinator.status().availableCount)
    }

    @Test fun newSessionReplacesSamplesAndRejectsOldCallbacksEvenForSameSessionAndSourceNames() {
        val f = Fixture()
        f.start()
        f.ticks.now = 100
        f.coordinator.start("private-session", f.profile)
        assertTrue(f.snapshot().samples.isEmpty())
        val before = f.snapshot()
        val reads = f.ticks.reads
        f.runtime.complete(0, 99)
        assertEquals(before, f.snapshot())
        assertEquals(reads, f.ticks.reads)
        f.ticks.now = 120
        f.runtime.complete(1, 1)
        assertEquals(20L, f.snapshot().samples.single().elapsedNanos)
        assertTrue(before.samples.isEmpty())
        assertEquals(1L, f.snapshot().samples.single().operationAlias)
    }

    @Test fun serializedCancellationRecordsIncompleteWithoutClockReadAndIgnoresLateCallbacks() {
        val f = Fixture()
        f.start()
        f.ticks.now = 100
        f.coordinator.setVisibility(false, false)
        val snapshot = f.snapshot()
        assertEquals(1L, snapshot.cancelledUnmeasured)
        assertEquals(ProviderCallbackTerminal.CANCELLED_INCOMPLETE, snapshot.samples.single().terminal)
        assertNull(snapshot.samples.single().elapsedNanos)
        assertEquals(ProviderLatencyTiming.NOT_MEASURED, snapshot.samples.single().timing)
        assertTrue(snapshot.incomplete)
        assertEquals(1, f.ticks.reads)
        f.coordinator.setVisibility(true, true)
        val reads = f.ticks.reads
        f.runtime.complete(0, 99)
        assertEquals(reads, f.ticks.reads)
        assertEquals(1, f.snapshot().pendingAttempts)
        f.ticks.now = 150
        f.runtime.complete(1, 1)
        assertEquals(50L, f.snapshot().samples.last().elapsedNanos)
    }

    @Test fun immediateLifecyclePowerAndSourceFencesRejectLateSuccessWithoutTakingClockSample() {
        val fences: List<(VideoDiscoveryCoordinator) -> Unit> = listOf(
            { it.suspendImmediately() }, { it.suspendPowerImmediately() }, { it.suspendSourcesImmediately() },
        )
        for (fence in fences) {
            val f = Fixture()
            f.start()
            fence(f.coordinator)
            f.ticks.fail = true
            f.runtime.complete(0, 1)
            assertEquals(1, f.ticks.reads)
            assertEquals(1L, f.snapshot().cancelledUnmeasured)
            assertNull(f.snapshot().samples.single().elapsedNanos)
            assertEquals(0, f.coordinator.status().availableCount)
            f.coordinator.close()
        }
    }

    @Test fun sourceSpecificResetAndCloseRetirePendingMetricsWithoutAffectingSibling() {
        val f = Fixture(providers = 2)
        f.start()
        assertTrue(f.coordinator.resetProgress(f.profile.profileId, "private-provider-1"))
        assertEquals(1L, f.snapshot().cancelledUnmeasured)
        assertEquals(1, f.snapshot().pendingAttempts)
        f.ticks.now = 40
        f.runtime.complete(1, 2)
        assertEquals(40L, f.snapshot().samples.last().elapsedNanos)
        f.runtime.complete(0, 1)
        assertEquals(2, f.snapshot().samples.size)
        f.coordinator.start("second-session", f.profile.copy(query = "second query"))
        f.coordinator.close()
        assertEquals(2L, f.snapshot().cancelledUnmeasured)
        assertEquals(0, f.snapshot().pendingAttempts)
    }

    @Test fun pendingCapacityDropsDiagnosticsOnlyAndRingEvictionIsExplicit() {
        val f = Fixture(providers = 2, capacity = 1)
        f.start()
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.snapshot().pendingAttempts)
        assertEquals(1L, f.snapshot().droppedAttempts)
        assertEquals(1, f.ticks.reads)
        f.ticks.now = 20
        f.runtime.complete(0, 1)
        f.runtime.complete(1, 2)
        assertEquals(2, f.coordinator.status().availableCount)
        assertEquals(2, f.ticks.reads)
        assertTrue(f.snapshot().incomplete)

        val g = Fixture(capacity = 2)
        g.start()
        repeat(3) { index ->
            g.ticks.now += 10
            g.runtime.complete(index, index + 1, nextCursor = "cursor-$index", exhausted = index == 2)
        }
        assertEquals(listOf(2L, 3L), g.snapshot().samples.map { it.operationAlias })
        assertEquals(1L, g.snapshot().evictedSamples)
        assertEquals(3L, g.snapshot().callbacksObserved)
        assertEquals(0, g.snapshot().pendingAttempts)
    }

    @Test fun signedMonotonicTicksAreValidButBackwardsAndOverflowIntervalsAreRejected() {
        val cases = listOf(
            Triple(-30L to -10L, 20L, ProviderLatencyTiming.MEASURED),
            Triple(-20L to 20L, 40L, ProviderLatencyTiming.MEASURED),
            Triple(10L to 5L, null, ProviderLatencyTiming.CLOCK_BACKWARDS),
            Triple(Long.MIN_VALUE to Long.MAX_VALUE, null, ProviderLatencyTiming.CLOCK_OVERFLOW),
            Triple(Long.MAX_VALUE to Long.MIN_VALUE, null, ProviderLatencyTiming.CLOCK_OVERFLOW),
        )
        for ((ticks, elapsed, timing) in cases) {
            val f = Fixture(ticks = TickClock(ticks.first))
            f.start()
            f.ticks.now = ticks.second
            f.runtime.complete(0, 1)
            val sample = f.snapshot().samples.single()
            assertEquals(elapsed, sample.elapsedNanos)
            assertEquals(timing, sample.timing)
            assertEquals(if (elapsed == null) 1L else 0L, f.snapshot().invalidDurations)
            assertEquals(1, f.coordinator.status().availableCount)
        }
    }

    @Test fun clockExceptionsAtStartOrCallbackDoNotChangeDiscoveryOrEscapeIntoSnapshot() {
        for (failStart in listOf(true, false)) {
            val f = Fixture()
            f.ticks.fail = failStart
            f.start()
            f.ticks.fail = !failStart
            f.runtime.complete(0, 1)
            assertEquals(1, f.coordinator.status().availableCount)
            assertEquals(1L, f.snapshot().clockReadFailures)
            assertEquals(1L, f.snapshot().invalidDurations)
            assertEquals(ProviderLatencyTiming.CLOCK_READ_FAILED, f.snapshot().samples.single().timing)
            assertNull(f.snapshot().samples.single().elapsedNanos)
            assertFalse(f.snapshot().toString().contains("private-clock-detail"))
        }
    }

    @Test fun runtimeExecuteExceptionIsIncompleteAndSynchronousCallbackIsCountedOnlyOnce() {
        for (callbackFirst in listOf(false, true)) {
            val ticks = TickClock()
            val recorder = ProviderCallbackLatencyRecorder(true, ticks)
            val epoch = FakeClock()
            val runtime = object : ProviderRuntime {
                override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                    callback: (ProviderOutcome) -> Unit): CancellationHandle {
                    if (callbackFirst) {
                        ticks.now = 40
                        val outcome = ProviderOutcome.Success(ValidatedProviderPage(listOf(result(1)), null, null, true))
                        callback(outcome)
                        callback(outcome)
                    }
                    throw IllegalStateException("private-runtime-detail")
                }
            }
            VideoDiscoveryCoordinator(ProviderRegistry(listOf(StubProvider())), runtime, InMemoryDiscoveryStore(),
                FakeTimer(epoch), epoch, providerCallbackLatency = recorder).use { coordinator ->
                coordinator.setVisibility(true, true)
                coordinator.start("session", SearchProfile("profile", "query"))
                val snapshot = recorder.snapshot()
                assertEquals(1, snapshot.samples.size)
                assertEquals(if (callbackFirst) 1L else 0L, snapshot.callbacksObserved)
                assertEquals(if (callbackFirst) 0L else 1L, snapshot.executeFailedWithoutCallback)
                assertEquals(if (callbackFirst) 40L else null, snapshot.samples.single().elapsedNanos)
                assertEquals(if (callbackFirst) ProviderCallbackTerminal.SUCCESS_REPORTED else
                    ProviderCallbackTerminal.EXECUTE_FAILED_INCOMPLETE, snapshot.samples.single().terminal)
                assertFalse(snapshot.toString().contains("private-runtime-detail"))
            }
        }
    }

    @Test fun runtimeReportedSuccessIsNotAClaimThatLateOrChangedSourcePageWasAccepted() {
        for (changeDescriptor in listOf(false, true)) {
            val f = Fixture()
            f.start()
            if (changeDescriptor) f.sources[0].descriptor = f.sources[0].descriptor.copy(version = "2")
            else f.epoch.now = f.runtime.calls[0].operation.context.deadlineEpochMillis
            f.ticks.now = 50
            f.runtime.complete(0, 1)
            assertEquals(ProviderCallbackTerminal.SUCCESS_REPORTED, f.snapshot().samples.first().terminal)
            assertEquals(50L, f.snapshot().samples.first().elapsedNanos)
            assertEquals(0, f.coordinator.status().availableCount)
            f.coordinator.close()
        }
    }

    @Test fun deadlineCancellationAndBusyAreRuntimeReportsWithoutInventingRemoteFailureCauses() {
        for ((error, terminal) in listOf(
            DiscoveryError.DEADLINE_EXCEEDED to ProviderCallbackTerminal.DEADLINE_REPORTED,
            DiscoveryError.CANCELLED to ProviderCallbackTerminal.CANCELLED_REPORTED,
            DiscoveryError.BUSY to ProviderCallbackTerminal.BUSY_REPORTED,
            DiscoveryError.SOURCE_CONFIGURATION to ProviderCallbackTerminal.FAILURE_REPORTED,
        )) {
            val f = Fixture()
            f.start()
            f.ticks.now = 15
            f.runtime.fail(0, code = error)
            assertEquals(terminal, f.snapshot().samples.single().terminal)
            assertEquals(15L, f.snapshot().samples.single().elapsedNanos)
        }
    }

    @Test fun snapshotsContainNoRawSourceSessionQueryUrlTitleOrCursorAndRestoreResetsThem() {
        val f = Fixture()
        f.start()
        f.ticks.now = 10
        f.runtime.complete(0, 1)
        val frozen = f.snapshot()
        val text = frozen.toString()
        listOf("private-provider", "private-profile", "private query", "private-session", "https://",
            "video.example", "Title 1", "cursor").forEach { assertFalse(text.contains(it)) }
        assertTrue(f.coordinator.restore("private-session"))
        assertTrue(f.snapshot().samples.isEmpty())
        assertEquals(0L, f.snapshot().callbacksObserved)
        assertEquals(1, frozen.samples.size)
    }

    @Test fun callbackTimingExcludesWaitingForCoordinatorStoreTransaction() {
        val storeEntered = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val backing = InMemoryDiscoveryStore()
        val store = object : DiscoveryStore {
            @Volatile var block = false
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
                if (this.block) {
                    this.block = false
                    storeEntered.countDown()
                    check(releaseStore.await(3, TimeUnit.SECONDS))
                }
                return backing.transaction(block)
            }
        }
        val f = Fixture(store = store)
        f.start()
        val error = AtomicReference<Throwable?>()
        store.block = true
        val reader = Thread { try { f.coordinator.historyStatistics() } catch (failure: Throwable) { error.set(failure) } }
        val callbackTickRead = CountDownLatch(1)
        var callback: Thread? = null
        try {
            reader.start()
            assertTrue(storeEntered.await(2, TimeUnit.SECONDS))
            f.ticks.now = 40
            f.ticks.onRead = { callbackTickRead.countDown() }
            callback = Thread { try { f.runtime.complete(0, 1) } catch (failure: Throwable) { error.set(failure) } }.also { it.start() }
            assertTrue(callbackTickRead.await(2, TimeUnit.SECONDS))
            assertEquals(40L, f.snapshot().samples.single().elapsedNanos)
            f.ticks.now = 4_000
        } finally {
            releaseStore.countDown()
            reader.join(3000)
            callback?.join(3000)
            f.coordinator.close()
        }
        assertNull(error.get())
        assertFalse(reader.isAlive)
        assertFalse(callback?.isAlive ?: false)
        assertEquals(40L, f.snapshot().samples.single().elapsedNanos)
    }

    @Test fun allExistingPositionalAndTrailingRetryConstructorShapesRemainUsable() {
        val epoch = FakeClock()
        val timer = FakeTimer(epoch)
        val registry = ProviderRegistry()
        val runtime = FakeRuntime()
        val store = InMemoryDiscoveryStore()
        val limits = DiscoveryLimits()
        val retry: (Int) -> Long = { 1000 }
        listOf(
            VideoDiscoveryCoordinator(registry, runtime, store, timer, epoch) { 1000 },
            VideoDiscoveryCoordinator(registry, runtime, store, timer, epoch, limits, null, retry),
            VideoDiscoveryCoordinator(registry, runtime, store, timer, epoch, limits, null, null, retry),
        ).forEach { coordinator -> coordinator.close() }
    }

    @Test fun closingOldCoordinatorCannotRetireNewCoordinatorUsingTheSameRecorder() {
        val ticks = TickClock()
        val recorder = ProviderCallbackLatencyRecorder(true, ticks)
        val epoch = FakeClock()
        val timer = FakeTimer(epoch)
        val runtime = FakeRuntime()
        fun coordinator() = VideoDiscoveryCoordinator(ProviderRegistry(listOf(StubProvider())), runtime,
            InMemoryDiscoveryStore(), timer, epoch, providerCallbackLatency = recorder)
        val first = coordinator()
        val second = coordinator()
        try {
            first.setVisibility(true, true)
            first.start("same-session", SearchProfile("profile", "query"))
            ticks.now = 100
            second.setVisibility(true, true)
            second.start("same-session", SearchProfile("profile", "query"))
            first.close()
            assertEquals(1, recorder.snapshot().pendingAttempts)
            runtime.complete(0, 99)
            assertEquals(2, ticks.reads)
            ticks.now = 125
            runtime.complete(1, 1)
            assertEquals(25L, recorder.snapshot().samples.single().elapsedNanos)
            assertEquals(0L, recorder.snapshot().cancelledUnmeasured)
        } finally { first.close(); second.close() }
    }

    @Test fun actualRuntimeTimeoutCallbackDoesNotMeanUncooperativeWorkerReleasedItsSlot() {
        val epoch = FakeClock()
        val timer = FakeTimer(epoch)
        val ticks = TickClock()
        val recorder = ProviderCallbackLatencyRecorder(true, ticks)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        val source = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("blocked", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                entered.countDown()
                try {
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    return ProviderPage(exhausted = true)
                } finally { exited.countDown() }
            }
        }
        IsolatedProviderRuntime(ValidationPipeline(clock = epoch), timer, epoch).use { isolated ->
            val runtime = object : ProviderRuntime {
                override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                    callback: (ProviderOutcome) -> Unit): CancellationHandle = isolated.execute(provider, request, operation) {
                    callback(it)
                    callbackCompleted.countDown()
                }
            }
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime,
                InMemoryDiscoveryStore(), timer, epoch, providerCallbackLatency = recorder)
            try {
                coordinator.setVisibility(true, true)
                coordinator.start("session", SearchProfile("profile", "query"))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                ticks.now = 90
                timer.advance(15_000)
                assertTrue(callbackCompleted.await(2, TimeUnit.SECONDS))
                val sample = recorder.snapshot().samples.single()
                assertEquals(ProviderCallbackTerminal.DEADLINE_REPORTED, sample.terminal)
                assertEquals(90L, sample.elapsedNanos)
                assertEquals(1L, exited.count)
                val busy = AtomicReference<ProviderOutcome>()
                isolated.execute(source, SearchRequest(SessionStamp("probe", 1), SearchProfile("p", "q"), null),
                    operation(epoch.now + 1000)) { busy.set(it) }
                assertEquals(DiscoveryError.BUSY, (busy.get() as ProviderOutcome.Failure).code)
                assertEquals(1L, recorder.snapshot().callbacksObserved)
            } finally {
                release.countDown()
                assertTrue(exited.await(2, TimeUnit.SECONDS))
                coordinator.close()
            }
        }
    }
}
