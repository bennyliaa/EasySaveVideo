package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class CommonsStageDiagnosticsCoordinatorTest {
    private class Fixture {
        val recorder = CommonsStageDiagnosticsRecorder()
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime()
        val sources = listOf(StubProvider(ProviderDescriptor("wikimedia-commons", "1")), StubProvider(ProviderDescriptor("other", "1")))
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(sources), runtime, InMemoryDiscoveryStore(), timer,
            clock, commonsStageDiagnostics = recorder) { 1000 }
        val profile = SearchProfile("private-profile", "private query")
        init { coordinator.setVisibility(true, true) }
        fun start(capture: CommonsStageCapture? = recorder.captureAuthorization()) =
            coordinator.start("private-session", profile, diagnosticsCapture = capture)
    }
    @Test fun armIsNotConsumedByIncidentalStartOrRestoreAndOtherSourcesAreNeverRecorded() {
        val f = Fixture(); val arm = f.recorder.setEnabled(true)
        f.start(null)
        assertTrue(f.runtime.calls.all { it.operation.commonsStageTrace == null })
        assertSame(arm, f.recorder.captureAuthorization())
        f.coordinator.restore("private-session", requireExplicitSearch = true)
        assertSame(arm, f.recorder.captureAuthorization())
        f.start(arm)
        assertNotNull(f.runtime.calls.last { it.provider.descriptor.id == "wikimedia-commons" }.operation.commonsStageTrace)
        assertNull(f.runtime.calls.last { it.provider.descriptor.id == "other" }.operation.commonsStageTrace)
        assertNull(f.recorder.captureAuthorization())
    }
    @Test fun retryHasFreshCapabilityAndOldDuplicateCannotCompleteIt() {
        val f = Fixture(); f.recorder.setEnabled(true); f.start()
        val first = f.runtime.calls.indexOfFirst { it.provider.descriptor.id == "wikimedia-commons" }
        val old = f.runtime.calls[first].operation
        old.commonsStageTrace!!.begin(CommonsStage.DNS)!!.complete()
        f.runtime.fail(first, retryable = true)
        f.timer.advance(1000)
        val retry = f.runtime.calls.last().operation
        assertNotSame(old, retry); assertNotSame(old.commonsStageTrace, retry.commonsStageTrace)
        assertEquals(1, retry.context.attempt)
        old.commonsStageTrace!!.event(CommonsStage.CANCEL_SIGNAL)
        f.runtime.fail(first)
        assertEquals(1, f.recorder.snapshot().pendingOperations)
        f.runtime.fail(f.runtime.calls.lastIndex)
        assertEquals(0, f.recorder.snapshot().pendingOperations)
        assertEquals(listOf(0, 1), f.recorder.snapshot().samples.filter { it.stage == CommonsStage.OPERATION }.map { it.attempt })
    }
    @Test fun cancelledOperationRetiresUnfinishedStageWithoutInventingDuration() {
        val f = Fixture(); f.recorder.setEnabled(true); f.start()
        val call = f.runtime.calls.first { it.provider.descriptor.id == "wikimedia-commons" }
        val span = call.operation.commonsStageTrace!!.begin(CommonsStage.HTTP_EXCHANGE)!!
        f.coordinator.setVisibility(false, false)
        span.complete()
        val sample = f.recorder.snapshot().samples.single { it.stage == CommonsStage.HTTP_EXCHANGE }
        assertEquals(CommonsStageOutcome.INCOMPLETE, sample.outcome); assertNull(sample.elapsedNanos)
        assertEquals(CommonsStageOutcome.CANCELLED, f.recorder.snapshot().samples.last().outcome)
    }
    @Test fun oldCoordinatorCloseCannotSealAnotherCoordinatorsReplacementCapture() {
        val f = Fixture(); f.recorder.setEnabled(true); f.start()
        val second = VideoDiscoveryCoordinator(ProviderRegistry(f.sources), FakeRuntime(), InMemoryDiscoveryStore(),
            f.timer, f.clock, commonsStageDiagnostics = f.recorder)
        second.setVisibility(true, true)
        second.start("replacement", f.profile, diagnosticsCapture = f.recorder.setEnabled(true))
        f.coordinator.close()
        assertTrue(f.recorder.snapshot().sessionOpen)
        assertEquals(1, f.recorder.snapshot().pendingOperations)
        second.close()
    }
    @Test fun immediateLifecycleFenceRejectsLateStagesAndSuccessBeforeSerializedVisibilityUpdate() =
        immediateFence { it.suspendImmediately() }

    @Test fun immediatePowerFenceRejectsLateStagesAndSuccessBeforeSerializedPolicyUpdate() =
        immediateFence { it.suspendPowerImmediately() }

    @Test fun immediateSourceFenceRejectsLateStagesAndSuccessBeforeSerializedSourceUpdate() =
        immediateFence { it.suspendSourcesImmediately() }

    private fun immediateFence(fence: (VideoDiscoveryCoordinator) -> Unit) {
        val f = Fixture(); f.recorder.setEnabled(true); f.start()
        val index = f.runtime.calls.indexOfFirst { it.provider.descriptor.id == "wikimedia-commons" }
        val trace = f.runtime.calls[index].operation.commonsStageTrace!!
        val span = trace.begin(CommonsStage.DNS)!!
        assertEquals(CommonsStageOutcome.IN_PROGRESS, f.recorder.snapshot().activeStages.single().outcome)
        // Hold the recorder monitor elsewhere: the immediate fence must not acquire it.
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(f.recorder) { held.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS) } }
        holder.start(); assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val finished = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { try { fence(f.coordinator) } finally { finished.countDown() } }
        worker.start()
        try { assertTrue("Immediate cancellation must not wait for diagnostics", finished.await(1, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { release.countDown(); holder.join(2000); worker.join(2000) }
        // No queued setVisibility/setPowerSignals/close or runtime callback has run yet.
        val snapshot = f.recorder.snapshot()
        assertTrue(snapshot.activeStages.isEmpty())
        assertEquals(CommonsStageOutcome.INCOMPLETE, snapshot.samples.single { it.stage == CommonsStage.DNS }.outcome)
        assertNull(snapshot.samples.single { it.stage == CommonsStage.DNS }.elapsedNanos)
        span.complete()
        assertNull(trace.begin(CommonsStage.TCP_CONNECT))
        trace.httpStatus(200)
        f.runtime.complete(index, 1)
        val final = f.recorder.snapshot()
        assertFalse(final.samples.any { it.outcome == CommonsStageOutcome.SUCCESS })
        assertFalse(final.samples.any { it.stage == CommonsStage.HTTP_STATUS })
        assertEquals(CommonsStageOutcome.CANCELLED, final.samples.single { it.stage == CommonsStage.OPERATION }.outcome)
        assertEquals(0, final.pendingOperations)
        assertEquals(0, f.coordinator.status().availableCount)
    }

}
