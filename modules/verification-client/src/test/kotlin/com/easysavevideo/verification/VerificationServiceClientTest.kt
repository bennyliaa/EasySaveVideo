package com.easysavevideo.verification

import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VerificationServiceClientTest {
    @Test fun sharedEngineIdentityReachesWireAndResponseWithoutChangingLegacyFreshIds() {
        val transport=TestTransport()
        val id="b".repeat(32)
        val result=client(transport).verify(candidate,operation(),id)
        assertEquals(id,result.requestId)
        assertEquals(id,StrictJson.read(transport.requests.single().body).objectValue()["requestId"]?.stringOrNull())
        assertThrows(IllegalArgumentException::class.java) {client(TestTransport()).verify(candidate,operation(),"invalid")}
    }
    @Test fun unspecifiedTransportCleanupProofIsUnavailable() {
        assertFalse(TestTransport().isPhysicallyIdle())
    }
    private val candidate = CandidateUrl(CANDIDATE, SourceReportedCandidateMetadata("Untrusted search title", "Untrusted source", "Search query private",
        reportedDurationMs = 1234))
    private fun reply(request: VerificationHttpRequest, json: String = jsonFor(request), status: Int = 200,
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json")), stream: InputStream? = null,
        url: URI = request.url) = VerificationHttpResponse(status, url, headers, stream ?: ByteArrayInputStream(json.toByteArray()))

    @Test fun candidateValidatorUsesOnlyPageEvidenceWithoutSourceFallback() {
        val transport = TestTransport()
        val raw = client(transport).validate(candidate, operation())!!
        assertTrue(raw.videoConfirmed); assertNull(raw.title); assertNull(raw.durationMs); assertNull(raw.sourceName)
        assertNull(raw.description); assertNull(raw.thumbnailUrl)
        val request = transport.requests.single()
        assertEquals(endpoint().url, request.url.toString())
        assertEquals(4000L, request.deadlineEpochMillis)
        assertEquals(3000, request.connectTimeoutMillis)
        assertEquals(32768L, request.maxBodyBytes)
        assertEquals(request.body.size.toString(), request.headers["Content-Length"])
        assertFalse(String(request.body).contains("Untrusted")); assertFalse(String(request.body).contains("Search query"))
        assertEquals(3000L, StrictJson.read(request.body).objectValue()["budgetMs"]?.longOrNull())
    }
    @Test fun allServiceNegativeOutcomesRetainCandidateAsNull() {
        for (outcome in VerificationOutcome.entries.filter { it != VerificationOutcome.PAGE_EVIDENCE }) {
            val transport = TestTransport { reply(it, jsonFor(it, outcome.name, "NONE")) }
            assertNull(client(transport).validate(candidate, operation()))
            assertEquals(1, transport.requests.size)
        }
    }
    @Test fun httpSuccessAloneCannotEstablishEvidence() {
        for (body in listOf("{}", "{\"video\":true}", "{\"@type\":\"VideoObject\"}", "{\"status\":\"success\"}"))
            assertNull(client(TestTransport { reply(it, body) }).validate(candidate, operation()))
    }
    @Test fun nonSuccessStatusesAndRedirectsHaveNoRetries() {
        for (status in listOf(201, 204, 301, 302, 307, 308, 400, 401, 403, 404, 429, 500, 503)) {
            val transport = TestTransport { reply(it, status = status) }
            assertNull(client(transport).validate(candidate, operation()))
            assertEquals(1, transport.requests.size)
        }
    }
    @Test fun wrongResponseUrlFailsEvenWithGoodBody() {
        val transport = TestTransport { reply(it, url = URI("https://other.example.org/v1/verify")) }
        assertEquals(VerificationOutcome.REJECTED, client(transport).verify(candidate, operation()).outcome)
    }
    @Test fun generationAndRequestCorrelationRejectReplay() {
        for (key in listOf("requestId", "generation", "candidateUrl", "pageUrl")) {
            val transport = TestTransport {
                val fields = StrictJson.read(jsonFor(it).toByteArray()).objectValue().toMutableMap()
                fields[key] = Json.Str("replayed")
                reply(it, StrictJson.write(Json.Obj(fields)))
            }
            assertEquals(VerificationOutcome.REJECTED, client(transport).verify(candidate, operation()).outcome)
        }
    }
    @Test fun requestIdsAreFreshEveryAttempt() {
        val ids = mutableSetOf<String>()
        repeat(32) {
            val transport = TestTransport()
            client(transport).verify(candidate, operation())
            val id = StrictJson.read(transport.requests.single().body).objectValue()["requestId"]?.stringOrNull()!!
            assertTrue(id.matches(Regex("[0-9a-f]{32}"))); assertTrue(ids.add(id))
        }
    }
    @Test fun dynamicOptInIsExplicitAndForwarded() {
        val transport = TestTransport { reply(it, jsonFor(it, evidence = "DYNAMIC_VIDEO_XHR")) }
        assertNull(client(transport).validate(candidate, operation()))
        val allowed = TestTransport { reply(it, jsonFor(it, evidence = "DYNAMIC_VIDEO_XHR")) }
        assertNotNull(client(allowed, dynamic = true).validate(candidate, operation()))
        assertEquals(Json.Bool(true), StrictJson.read(allowed.requests.single().body).objectValue()["dynamicAllowed"])
    }
    @Test fun unsafeCandidatesCauseNoCall() {
        val transport = TestTransport()
        assertNull(client(transport).validate(CandidateUrl("http://127.0.0.1/private"), operation()))
        assertEquals(0, transport.requests.size)
    }
    @Test fun operationBudgetReservePreventsLateOptionalCall() {
        val transport = TestTransport(); val clock = TestClock()
        assertEquals(VerificationOutcome.TIMEOUT, client(transport, clock).verify(candidate, operation(deadline = 2000)).outcome)
        assertTrue(transport.requests.isEmpty())
        val bounded = TestTransport()
        client(bounded, clock).verify(candidate, operation(deadline = 2001))
        assertEquals(1001L, bounded.requests.single().deadlineEpochMillis)
        assertEquals(1L, StrictJson.read(bounded.requests.single().body).objectValue()["budgetMs"]?.longOrNull())
    }
    @Test fun originalDeadlineAndCancellationPropagateBeforeIO() {
        val transport = TestTransport()
        discoveryFails(DiscoveryError.DEADLINE_EXCEEDED) { client(transport).verify(candidate, operation(deadline = 1000)) }
        val cancelled = operation(); cancelled.cancellation.cancel()
        discoveryFails(DiscoveryError.CANCELLED) { client(transport).verify(candidate, cancelled) }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun originalDeadlinePropagatesAfterTransportInsteadOfPendingSuccess() {
        val clock = TestClock()
        val transport = TestTransport { clock.now = 16000; reply(it) }
        discoveryFails(DiscoveryError.DEADLINE_EXCEEDED) { client(transport, clock).validate(candidate, operation()) }
    }
    @Test fun originalCancellationPropagatesAfterTransportInsteadOfPendingSuccess() {
        val op = operation()
        val transport = TestTransport { op.cancellation.cancel(); reply(it) }
        discoveryFails(DiscoveryError.CANCELLED) { client(transport).validate(candidate, op) }
    }
    @Test fun configurationGenerationRevocationPropagatesBeforeAndAfterTransport() {
        val transport = TestTransport()
        discoveryFails(DiscoveryError.CANCELLED) { client(transport, isCurrent = { false }).verify(candidate, operation()) }
        assertTrue(transport.requests.isEmpty())
        val current = AtomicBoolean(true)
        val late = TestTransport { current.set(false); reply(it) }
        discoveryFails(DiscoveryError.CANCELLED) { client(late, isCurrent = current::get).validate(candidate, operation()) }
    }
    @Test fun interruptedCallerCannotPublish() {
        try {
            Thread.currentThread().interrupt()
            discoveryFails(DiscoveryError.CANCELLED) { client(TestTransport()).validate(candidate, operation()) }
        } finally { Thread.interrupted() }
    }
    @Test fun rawInterruptedExceptionRestoresFlagAndPropagatesWithoutTokenCancellation() {
        val op = operation()
        try {
            discoveryFails(DiscoveryError.CANCELLED) {
                client(TestTransport { throw InterruptedException("private details") }).verify(candidate, op)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(op.cancellation.isCancelled)
        } finally { Thread.interrupted() }
    }
    @Test fun serviceLocalDeadlineAndTimerReturnPendingWithoutCancellingOriginalOperation() {
        val clock = TestClock(); val op = operation()
        val transport = TestTransport { clock.now = 4000; reply(it) }
        assertEquals(VerificationOutcome.TIMEOUT, client(transport, clock).verify(candidate, op).outcome)
        assertFalse(op.cancellation.isCancelled)
        val timer = TestTimer(); val timerTransport = TestTransport { timer.fire(); reply(it) }
        assertEquals(VerificationOutcome.TIMEOUT, client(timerTransport, timer = timer).verify(candidate, operation()).outcome)
    }
    @Test fun transportAndConstructionFailuresAreIsolated() {
        val broken = VerificationTransport { throw IllegalStateException("private details") }
        assertEquals(VerificationOutcome.UNAVAILABLE, client(broken).verify(candidate, operation()).outcome)
        for (error in VerificationError.entries.filter { it != VerificationError.STALE_CONFIGURATION }) {
            val transport = TestTransport { throw VerificationFailure(error) }
            assertNull(client(transport).validate(candidate, operation()))
        }
        discoveryFails(DiscoveryError.CANCELLED) {
            client(TestTransport { throw VerificationFailure(VerificationError.STALE_CONFIGURATION) }).verify(candidate, operation())
        }
    }
    @Test fun contentTypeEncodingLengthsAndDuplicateHeadersAreStrict() {
        val invalid = listOf(
            mapOf("Content-Type" to listOf("text/html")), mapOf("Content-Type" to listOf("application/json; charset=latin1")),
            mapOf("Content-Type" to listOf("application/json; charset=utf-8; charset=utf-8")),
            mapOf("Content-Type" to listOf("application/json", "application/json")),
            mapOf("Content-Type" to listOf("application/json"), "content-type" to listOf("application/json")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Encoding" to listOf("gzip")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("9223372036854775808")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("1")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("-1")),
            mapOf("Content-Type" to listOf("application/json"), "Injected\r\n" to listOf("x")),
            mapOf("Content-Type" to listOf("application/json"), "X-Test" to listOf("private\r\nvalue")),
        )
        for (headers in invalid) assertNull(client(TestTransport { reply(it, headers = headers) }).validate(candidate, operation()))
    }
    @Test fun finiteBodyRejectsOversizeAndZeroProgress() {
        assertNull(client(TestTransport { reply(it, " ".repeat(32769)) }).validate(candidate, operation()))
        assertNull(client(TestTransport { reply(it, stream = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }) }).validate(candidate, operation()))
        assertNull(client(TestTransport()).validate(candidate, operation(maximum = 1)))
    }
    @Test fun requestIsImmutableAndFunctionalObjectsRedacted() {
        val headers = linkedMapOf("Host" to "verification.example.org")
        val body = byteArrayOf(1, 2, 3)
        val request = VerificationHttpRequest(endpoint(), endpoint().uri, headers, body, 4000, 1000, 32768)
        headers["Host"] = "evil.example.org"; body[0] = 9; request.body[1] = 9
        assertEquals("verification.example.org", request.headers["Host"])
        assertArrayEquals(byteArrayOf(1, 2, 3), request.body)
        assertFalse(request.toString().contains("example.org"))
        assertFalse(client(TestTransport()).toString().contains(GENERATION))
        assertFalse(reply(request, json = "{}").toString().contains("example.org"))
    }
    @Test fun blockedCleanupRetainsClientPhysicalAdmission() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val transport = VerificationTransport { request ->
            calls.incrementAndGet()
            object : VerificationHttpCall {
                override fun execute() = reply(request)
                override fun cancel() { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            }
        }
        val client = client(transport)
        try {
            assertNotNull(client.validate(candidate, operation()))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(VerificationOutcome.BUSY, client.verify(candidate, operation()).outcome)
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }
    @Test fun concurrentAttemptDoesNotAcquireSecondPhysicalClientSlot() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val client = client(TestTransport { entered.countDown(); release.await(2, TimeUnit.SECONDS); reply(it) })
        val result = AtomicReference<VerificationResult?>()
        val thread = Thread { result.set(client.verify(candidate, operation())) }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(VerificationOutcome.BUSY, client.verify(candidate, operation()).outcome)
        } finally { release.countDown(); thread.join(2000) }
        assertEquals(VerificationOutcome.PAGE_EVIDENCE, result.get()!!.outcome)
    }
}
