package com.easysavevideo.commons

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual metadata parser/cache/timer paths, with an owned offline transport fixture. */
class CommonsStageDiagnosticsMetadataTest {
    private class Capture(val recorder: CommonsStageDiagnosticsRecorder = CommonsStageDiagnosticsRecorder()) {
        lateinit var operation: DiscoveryOperation
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor(CommonsIdentity.PROVIDER_ID, "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage()
        })), object : ProviderRuntime {
            override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                callback: (ProviderOutcome) -> Unit): CancellationHandle {
                this@Capture.operation = operation; return CancellationHandle { }
            }
        }, InMemoryDiscoveryStore(), TestTimer(), TestClock(), commonsStageDiagnostics = recorder)
        init {
            coordinator.setVisibility(true, true)
            coordinator.start("private-session", SearchProfile("private-profile", "private query"),
                diagnosticsCapture = recorder.setEnabled(true))
        }
    }
    @Test fun realParsingRecordsOnlyParseAndSuccessfulCleanupDoesNotFabricateCancellation() {
        val capture = Capture(); val transport = TestTransport(Reply())
        val page = provider(transport).search(request, capture.operation)
        assertEquals(1, page.results.size); assertEquals(1, transport.cancellations.get())
        assertSame(capture.operation.commonsStageTrace, transport.requests.single().stageTrace)
        val samples = capture.recorder.snapshot().samples
        assertEquals(listOf(CommonsStage.JSON_PARSE), samples.map { it.stage })
        assertEquals(CommonsStageOutcome.SUCCESS, samples.single().outcome)
        assertNotNull(samples.single().elapsedNanos)
        assertFalse(samples.any { it.stage == CommonsStage.CANCEL_SIGNAL })
        assertFalse(samples.toString().contains("Big Buck Bunny"))
        assertFalse(samples.toString().contains("operator@example.invalid"))
    }
    @Test fun cacheHitHasNoInventedNetworkOrParseMeasurements() {
        val transport = TestTransport(Reply()); val provider = provider(transport)
        provider.search(request, operation())
        val capture = Capture()
        assertTrue(provider.search(request, capture.operation).fromCache)
        assertEquals(1, transport.requests.size)
        val samples = capture.recorder.snapshot().samples
        assertEquals(listOf(CommonsStage.CACHE_HIT), samples.map { it.stage })
        assertNull(samples.single().elapsedNanos)
    }
    @Test fun failedActualParseHasTechnicalClassificationWithoutResponseText() {
        val capture = Capture()
        fails(CommonsError.INVALID_RESPONSE) {
            provider(TestTransport(Reply("{\"private-body\":".toByteArray()))).search(request, capture.operation)
        }
        val samples = capture.recorder.snapshot().samples
        val parse = samples.single { it.stage == CommonsStage.JSON_PARSE }
        assertEquals(CommonsStageOutcome.FAILURE, parse.outcome)
        assertEquals(CommonsStageFailure.INVALID_RESPONSE, parse.failure)
        assertTrue(samples.any { it.stage == CommonsStage.PROVIDER_FAILURE })
        assertFalse(samples.toString().contains("private-body"))
    }
    @Test fun serverCooldownAndMetadataGateAreObservedWithoutWaitingOrNetworkRepeat() {
        val transport = TestTransport(Reply(status = 429, headers = mapOf("Retry-After" to listOf("60"))))
        val provider = provider(transport)
        val first = Capture()
        fails(CommonsError.RATE_LIMITED) { provider.search(request, first.operation) }
        assertTrue(first.recorder.snapshot().samples.any { it.stage == CommonsStage.SERVER_COOLDOWN })
        val second = Capture()
        fails(CommonsError.RATE_LIMITED) { provider.search(request, second.operation) }
        assertEquals(1, transport.requests.size)
        val gate = second.recorder.snapshot().samples.single { it.stage == CommonsStage.METADATA_COOLDOWN }
        assertNull(gate.elapsedNanos); assertEquals(CommonsStageOutcome.OBSERVED, gate.outcome)
        assertFalse(second.recorder.snapshot().samples.any { it.stage == CommonsStage.DNS })
    }
    @Test fun preNetworkConfigurationFailureIsClassifiedWithoutFabricatedPhase() {
        val capture = Capture()
        fails(CommonsError.DISABLED) { CommonsMetadataProvider().search(request, capture.operation) }
        assertEquals(CommonsStageFailure.CONFIGURATION, capture.recorder.snapshot().samples.single().failure)
        assertEquals(CommonsStage.PROVIDER_FAILURE, capture.recorder.snapshot().samples.single().stage)
    }
    @Test fun actualOperationCancellationAndMetadataDeadlineAreSeparateFromFinallyCleanup() {
        for (deadline in listOf(false, true)) {
            val capture = Capture(); val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
            val timer = TestTimer(); val error = AtomicReference<CommonsError>()
            val transport = CommonsTransport { object : CommonsHttpCall {
                override fun execute(): CommonsHttpResponse {
                    entered.countDown(); assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                    throw IllegalStateException("private-runtime-detail")
                }
                override fun cancel() { cancelled.countDown() }
            } }
            val provider = provider(transport, timer = timer)
            val worker = Thread { try { provider.search(request, capture.operation) }
                catch (failure: CommonsFailure) { error.set(failure.code) } }
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            if (deadline) timer.fire() else capture.operation.cancellation.cancel()
            worker.join(3000); assertFalse(worker.isAlive)
            assertEquals(if (deadline) CommonsError.DEADLINE_EXCEEDED else CommonsError.CANCELLED, error.get())
            val stages = capture.recorder.snapshot().samples.map { it.stage }
            assertTrue((if (deadline) CommonsStage.DEADLINE_SIGNAL else CommonsStage.CANCEL_SIGNAL) in stages)
            assertFalse((if (deadline) CommonsStage.CANCEL_SIGNAL else CommonsStage.DEADLINE_SIGNAL) in stages)
            assertFalse(capture.recorder.snapshot().toString().contains("private-runtime-detail"))
        }
    }
}
