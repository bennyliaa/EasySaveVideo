package com.easysavevideo.commons

import com.easysavevideo.discovery.DiscoveryCancellation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ProviderHealthAttributionTest {
    private class Gate : CommonsMaintenance {
        val failures = mutableListOf<Pair<CommonsError, Boolean>>()
        var successes = 0
        override fun acquire(cancellation: DiscoveryCancellation) = object : CommonsMaintenanceLease {
            override val limits = CommonsLimits()
            override val cacheNamespace = "test"
            override fun check() {
                if (cancellation.isCancelled) throw CommonsFailure(CommonsError.CANCELLED)
            }
            override fun succeeded(checkBeforePublish: () -> Unit) { checkBeforePublish(); successes++ }
            override fun failed(code: CommonsError, structureFailure: Boolean, checkBeforePublish: () -> Unit) {
                checkBeforePublish(); failures += code to structureFailure
            }
            override fun close() {}
        }
    }
    private fun provider(transport: CommonsTransport, gate: Gate, clock: TestClock = TestClock()) =
        CommonsMetadataProvider(config(), transport, clock = clock, timer = TestTimer(), maintenance = gate)

    @Test fun onlyFreshResponsesProvideSuccessfulHealthEvidence() {
        val gate = Gate(); val transport = TestTransport(Reply(), Reply())
        val provider = provider(transport, gate)
        provider.search(request, operation()); provider.search(request, operation())
        assertEquals(1, gate.successes); assertEquals(1, transport.requests.size)
        provider.clearCache(); provider.search(request, operation())
        assertEquals(2, gate.successes)
    }
    @Test fun recognizableApiJsonSemanticFailureIsAnObservationNotCodeBlame() {
        for (bytes in listOf("""{"query":{"pages":"bad"}}""", """{"query":{"pages":[{}]}}""",
            """{"continue":{"action":"edit"},"query":{"pages":[]}}""", """{"batchcomplete":false}""",
            """{"batchcomplete":null}""", """{"query":{}}""")) {
            val gate = Gate()
            fails(CommonsError.INVALID_RESPONSE) { provider(TestTransport(Reply(bytes.toByteArray())), gate).search(request, operation()) }
            assertEquals(listOf(CommonsError.INVALID_RESPONSE to true), gate.failures)
            assertEquals(0, gate.successes)
        }
    }
    @Test fun malformedJsonHtmlChallengeAndUnrecognizedJsonAreInconclusive() {
        for (bytes in listOf("<html>challenge</html>", "{broken", """{"challenge":"login"}""", "[]",
            """{"warnings":{},"query":{"pages":"bad"}}""")) {
            val gate = Gate()
            fails(CommonsError.INVALID_RESPONSE) { provider(TestTransport(Reply(bytes.toByteArray())), gate).search(request, operation()) }
            assertEquals(listOf(CommonsError.INVALID_RESPONSE to false), gate.failures)
        }
    }
    @Test fun apiErrorsAndHttpServiceFailuresDoNotCountAsStructureFailures() {
        val replies = listOf(
            Reply("""{"error":{"code":"permissiondenied"}}""".toByteArray()) to CommonsError.SERVICE_ERROR,
            Reply("""{"errors":[]}""".toByteArray()) to CommonsError.SERVICE_ERROR,
            Reply("""{"error":{"code":"maxlag"}}""".toByteArray()) to CommonsError.RATE_LIMITED,
            Reply(status = 429) to CommonsError.RATE_LIMITED,
            Reply(status = 503) to CommonsError.RATE_LIMITED,
            Reply(status = 500) to CommonsError.SERVICE_ERROR,
            Reply(status = 401) to CommonsError.SERVICE_DENIED,
            Reply(status = 403) to CommonsError.SERVICE_DENIED,
            Reply(status = 302) to CommonsError.REDIRECT_REJECTED,
            Reply(headers = mapOf("Content-Type" to listOf("text/html"))) to CommonsError.INVALID_RESPONSE,
        )
        for ((reply, error) in replies) {
            val gate = Gate()
            fails(error) { provider(TestTransport(reply), gate).search(request, operation()) }
            assertEquals(listOf(error to false), gate.failures)
        }
    }
    @Test fun truncatedAndZeroProgressBodiesCannotProvideStructureEvidence() {
        val bodies = listOf<InputStream>(object : InputStream() { override fun read(): Int = throw IOException("synthetic") },
            object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 })
        for (body in bodies) {
            val gate = Gate()
            try { provider(TestTransport(Reply(stream = body)), gate).search(request, operation()); fail() }
            catch (_: CommonsFailure) {}
            assertEquals(1, gate.failures.size); assertFalse(gate.failures.single().second)
        }
        val gate = Gate()
        fails(CommonsError.INVALID_RESPONSE) {
            provider(TestTransport(Reply("""{"query":{"pages":"bad"}}""".toByteArray(),
                headers = mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("999")))), gate)
                .search(request, operation())
        }
        assertFalse(gate.failures.single().second)
    }
    @Test fun cancellationAndDeadlineAfterBodyCleanupDiscardStructureEvidence() {
        for (cancel in listOf(false, true)) {
            val gate = Gate(); val clock = TestClock(); val op = operation()
            val backend = TestTransport(Reply("""{"query":{"pages":"bad"}}""".toByteArray()))
            val transport = CommonsTransport { http ->
                val call = backend.create(http)
                object : CommonsHttpCall {
                    override fun execute() = call.execute()
                    override fun cancel() { call.cancel(); if (cancel) op.cancellation.cancel() else clock.now = 16_000 }
                }
            }
            try { provider(transport, gate, clock).search(request, op); fail() } catch (_: CommonsFailure) {}
            assertTrue(gate.failures.isEmpty()); assertEquals(0, gate.successes)
        }
    }
    @Test fun configurationFilterAndBusyRejectionsDoNotConsumeHealthEvidence() {
        val gate = Gate()
        fails(CommonsError.UNSUPPORTED_FILTER) {
            provider(TestTransport(), gate).search(CommonsSearchRequest("synthetic"), operation())
        }
        fails(CommonsError.CONTACT_REQUIRED) {
            CommonsMetadataProvider(CommonsClientConfig(true), TestTransport(), clock = TestClock(), timer = TestTimer(), maintenance = gate)
                .search(request, operation())
        }
        assertTrue(gate.failures.isEmpty()); assertEquals(0, gate.successes)
    }
    @Test fun localCooldownCannotExhaustTheRemoteObservationBudget() {
        val gate = Gate(); val transport = TestTransport(Reply(status = 429))
        val provider = provider(transport, gate)
        repeat(25) { fails(CommonsError.RATE_LIMITED) { provider.search(request, operation()) } }
        assertEquals(1, transport.requests.size)
        assertEquals(listOf(CommonsError.RATE_LIMITED to false), gate.failures)
    }
}
