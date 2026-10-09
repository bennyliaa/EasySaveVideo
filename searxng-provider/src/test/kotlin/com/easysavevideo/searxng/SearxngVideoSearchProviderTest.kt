package com.easysavevideo.searxng

import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SearxngVideoSearchProviderTest {
    @Test fun safeSearchUnknownIsAlwaysRefusedWithoutIoOrFilterDowngrade() {
        val transport = TestTransport()
        val provider = provider(transport)
        assertEquals(DiscoveryError.UNSUPPORTED_FILTER, provider.refusal(request(safe = true).profile))
        fails(DiscoveryError.UNSUPPORTED_FILTER) { provider.search(request(safe = true), operation()) }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun blankControlsAndMalformedUnicodeRefuseBeforeTransport() {
        for (query in listOf(" ", "line\nbreak", "bad\u0000query", "bad\ud800")) {
            val transport = TestTransport()
            fails(DiscoveryError.INVALID_QUERY) { provider(transport).search(request(query), operation()) }
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun exactQueryIsEncodedAsJsonVideoCategoryWithExplicitDisabledSafeSearchAndNoTotalRequirement() {
        val transport = TestTransport(Reply())
        val provider = provider(transport)
        val query = "Film & café + \"quote\""
        val page = provider.search(request(query, "fr-CA"), operation())
        assertTrue(page.results.isEmpty())
        assertEquals(1, page.candidates.size)
        assertNotNull(page.nextCursor)
        assertFalse(page.exhausted)
        val http = transport.requests.single()
        val parameters = params(http.url)
        assertEquals(setOf("q", "format", "categories", "pageno", "language", "safesearch"), parameters.keys)
        assertEquals(query, parameters["q"])
        assertEquals("json", parameters["format"])
        assertEquals("videos", parameters["categories"])
        assertEquals("1", parameters["pageno"])
        assertEquals("fr-ca", parameters["language"])
        assertEquals("0", parameters["safesearch"])
        assertEquals(SearxngIdentity.USER_AGENT, http.headers["User-Agent"])
        assertEquals(setOf("Host", "Connection", "User-Agent", "Accept", "Accept-Encoding"), http.headers.keys)
        assertEquals(5000, http.connectTimeoutMillis)
        assertEquals(16000L, http.deadlineEpochMillis)
        assertEquals(ONE.toByteArray().size.toLong(), page.responseBytes)
    }
    @Test fun sourceNamespaceExclusionRequiresNoRequest() {
        val transport = TestTransport()
        val request = request().let { it.copy(profile = it.profile.copy(filters = SearchFilters(sourceNamespaces = setOf("other"), safeSearch = false))) }
        assertTrue(provider(transport).search(request, operation()).exhausted)
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun invalidLanguageFallsBackToFixedEnglishWithoutHeaderInjection() {
        val transport = TestTransport(Reply())
        provider(transport).search(request(locale = "en&format=html"), operation())
        assertEquals("en", params(transport.requests.single().url)["language"])
    }
    @Test fun sequentialPaginationUsesBoundedPageNumberAndEmptyResultsExhaust() {
        val first = provider(TestTransport(Reply())).search(request(), operation())
        val transport = TestTransport(Reply("{\"results\":[]}"))
        val second = provider(transport).search(request(cursor = first.nextCursor), operation())
        assertEquals("2", params(transport.requests.single().url)["pageno"])
        assertTrue(second.exhausted)
        assertNull(second.nextCursor)
        assertNull(second.cursorExpiresAtEpochMillis)
        val bounded = provider(TestTransport(Reply()), limits = SearxngLimits(maxPages = 1)).search(request(), operation())
        assertTrue(bounded.exhausted)
        assertNull(bounded.nextCursor)
    }
    @Test fun crossQueryOrEndpointCursorIsRejectedWithoutRequest() {
        val token = provider(TestTransport(Reply())).search(request(query = "Film"), operation()).nextCursor!!
        for ((query, endpoint) in listOf("film" to endpoint(), "Film" to endpoint("https://other.example.org/search"))) {
            val transport = TestTransport()
            fails(DiscoveryError.PROVIDER_FAILURE) { provider(transport, endpoint).search(request(query, cursor = token, endpoint = endpoint), operation()) }
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun bodyBoundsContentTypeCompressionLengthAndZeroProgressAreEnforced() {
        val replies = listOf(
            Reply(headers = mapOf("Content-Type" to listOf("text/html"))),
            Reply(headers = mapOf("Content-Type" to listOf("application/json; charset=iso-8859-1"))),
            Reply(headers = mapOf("Content-Type" to listOf("application/json"), "Content-Encoding" to listOf("gzip"))),
            Reply(headers = mapOf("Content-Type" to listOf("application/json"), "content-type" to listOf("application/json"))),
            Reply(headers = mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("0"))),
            Reply(stream = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, o: Int, l: Int) = 0 }),
            Reply("{\"results\":[],\"results\":[]}"),
        )
        for (reply in replies) fails(DiscoveryError.PROVIDER_FAILURE) { provider(TestTransport(reply)).search(request(), operation()) }
        fails(DiscoveryError.RESPONSE_TOO_LARGE) { provider(TestTransport(Reply())).search(request(), operation(maximum = 10)) }
        fails(DiscoveryError.RESPONSE_TOO_LARGE) { provider(TestTransport(Reply(headers = mapOf("Content-Type" to listOf("application/json"),
            "Content-Length" to listOf("999999999999999999999"))))).search(request(), operation()) }
    }
    @Test fun statusAndRedirectFailuresNeverFetchAlternativesOrAuthorizeAccounts() {
        for (status in listOf(301, 302, 401, 403, 404, 500)) {
            val transport = TestTransport(Reply(status = status))
            fails(DiscoveryError.PROVIDER_FAILURE) { provider(transport).search(request(), operation()) }
            assertEquals(1, transport.requests.size)
        }
        fails(DiscoveryError.PROVIDER_FAILURE) { provider(TestTransport(Reply(url = URI("https://other.example.org/search?q=x"))))
            .search(request(), operation()) }
    }
    @Test fun cancellationAndExpiredDeadlinePreventRequestAndPostReadSuccess() {
        val transport = TestTransport()
        val cancelled = operation().also { it.cancellation.cancel() }
        fails(DiscoveryError.CANCELLED) { provider(transport).search(request(), cancelled) }
        fails(DiscoveryError.DEADLINE_EXCEEDED) { provider(transport).search(request(), operation(deadline = 1000)) }
        assertTrue(transport.requests.isEmpty())
        val clock = TestClock()
        val input = object : ByteArrayInputStream(ONE.toByteArray()) {
            override fun read(b: ByteArray, o: Int, l: Int): Int = super.read(b, o, l).also { clock.now = 16_000 }
        }
        fails(DiscoveryError.DEADLINE_EXCEEDED) { provider(TestTransport(Reply(stream = input)), clock = clock).search(request(), operation()) }
    }
    @Test fun rateLimitIsReturnedBeforeStalledCloseAndSlotStaysOccupiedUntilCleanupExits() {
        for (status in listOf(429, 503)) {
            val enteredClose = CountDownLatch(1); val releaseClose = CountDownLatch(1)
            val stream = object : InputStream() {
                override fun read(): Int = throw AssertionError("Error body must not be read")
                override fun close() { enteredClose.countDown(); awaitUninterruptibly(releaseClose) }
            }
            val transport = TestTransport(Reply(status = status, headers = mapOf("Retry-After" to listOf("120")), stream = stream))
            val provider = provider(transport)
            try {
                val failure = fails(DiscoveryError.RATE_LIMITED) { provider.search(request(), operation()) }
                assertEquals(121_000L, failure.retryAtEpochMillis)
                assertTrue(enteredClose.await(2, TimeUnit.SECONDS))
                fails(DiscoveryError.BUSY) { provider.search(request(), operation()) }
                assertEquals(1, transport.requests.size)
            } finally { releaseClose.countDown() }
            eventuallyNotBusy(provider, DiscoveryError.RATE_LIMITED)
            assertEquals(1, transport.requests.size)
        }
    }
    @Test fun uncooperativeWorkerAndCleanupRetainAdmissionAfterDeadlineSignal() {
        val started = CountDownLatch(1); val releaseExecute = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val requests = AtomicInteger()
        val timer = TestTimer()
        val transport = SearxngTransport { http ->
            requests.incrementAndGet()
            object : SearxngHttpCall {
                override fun execute(): SearxngHttpResponse {
                    started.countDown(); awaitUninterruptibly(releaseExecute)
                    return SearxngHttpResponse(200, http.url, mapOf("Content-Type" to listOf("application/json")), ByteArrayInputStream(ONE.toByteArray()))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val provider = provider(transport, timer = timer)
        val outcome = AtomicReference<DiscoveryFailure?>()
        val thread = Thread { outcome.set(fails(DiscoveryError.DEADLINE_EXCEEDED) { provider.search(request(), operation()) }) }
        thread.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS)); timer.fire()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            fails(DiscoveryError.BUSY) { provider.search(request(), operation()) }
            assertEquals(1, requests.get())
        } finally { releaseExecute.countDown(); thread.join(2000) }
        assertFalse(thread.isAlive)
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, outcome.get()?.code)
    }
    @Test fun diagnosticRepresentationsNeverContainQueryOrEndpointOrCandidateText() {
        val endpoint = endpoint()
        val transport = TestTransport(Reply())
        val page = provider(transport, endpoint).search(request("private search"), operation())
        for (value in listOf(endpoint, transport.requests.single(), page, page.candidates.single(), page.candidates.single().sourceReported)) {
            assertFalse(value.toString().contains("private search"))
            assertFalse(value.toString().contains("example.org"))
            assertFalse(value.toString().contains("short film"))
        }
    }
    private fun params(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }
    private fun eventuallyNotBusy(provider: SearxngVideoSearchProvider, expected: DiscoveryError) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try { provider.search(request(), operation()); fail("Expected failure") }
            catch (failure: DiscoveryFailure) {
                if (failure.code != DiscoveryError.BUSY) { assertEquals(expected, failure.code); return }
            }
            if (System.nanoTime() >= end) fail("Physical slot did not release")
            Thread.yield()
        }
    }
}
