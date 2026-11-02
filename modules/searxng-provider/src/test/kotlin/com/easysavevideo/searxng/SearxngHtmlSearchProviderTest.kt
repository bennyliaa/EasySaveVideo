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

/** Independently authored bounded fixtures. No service, DNS, socket, account or public page is accessed. */
class SearxngHtmlSearchProviderTest {
    private val robots = "User-agent: *\nAllow: /search\n"
    private val html = "<!doctype html><html><head><meta name='generator' content='searxng/synthetic'><title>Fixture</title></head>" +
        "<body class='search_endpoint'><main id='main_results'><div id='results'><div id='urls'>" +
        "<article class='result result-default'><h3><a href='https://videos.example.org/watch/one'>Original fixture</a></h3>" +
        "<p class='content'>Source text</p></article></div></div></main></body></html>"
    private fun htmlEndpoint(id: String = "searxng-1", path: String = "/search") =
        SearxngEndpoint(id, "https://search.example.org$path", SearxngResultFormat.HTML_WEB)
    private fun htmlProvider(transport: SearxngTransport, clock: TestClock = TestClock(), timer: TestTimer = TestTimer(),
        maximum: Long = 2_097_152, endpoint: SearxngEndpoint = htmlEndpoint()) = SearxngHtmlSearchProvider(endpoint, transport,
        SearxngLimits(maxPages = 2, maxResponseBytes = maximum), clock, timer)
    private fun reply(text: String, type: String, status: Int = 200, stream: InputStream? = null) =
        Reply(text, status, mapOf("Content-Type" to listOf(type), "Content-Length" to listOf(text.toByteArray().size.toString())), stream = stream)

    @Test fun robotsMustPrecedeExactTypedHtmlSearchAndNoResultUrlIsFetched() {
        val transport = TestTransport(reply(robots, "text/plain"), reply(html, "text/html"))
        val query = "Café & short + film"
        val result = htmlProvider(transport).search(request(query, locale = "fr-CA"), operation())
        assertEquals(2, transport.requests.size)
        val preflight = transport.requests[0]; val search = transport.requests[1]
        assertEquals(SearxngRequestKind.ROBOTS, preflight.kind); assertEquals("/robots.txt", preflight.url.rawPath); assertNull(preflight.url.rawQuery)
        assertEquals(SearxngRequestKind.HTML_SEARCH, search.kind)
        val params = search.url.rawQuery.split('&').associate { pair -> pair.split('=', limit = 2).let {
            URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") } }
        assertEquals(mapOf("q" to query, "format" to "html", "categories" to "general", "pageno" to "1", "language" to "fr-ca", "safesearch" to "0"), params)
        assertEquals("text/plain", preflight.headers["Accept"]); assertEquals("text/html", search.headers["Accept"])
        assertEquals(16000L, preflight.deadlineEpochMillis); assertEquals(preflight.deadlineEpochMillis, search.deadlineEpochMillis)
        assertEquals(32768L, preflight.maxBodyBytes); assertEquals(2097152L - robots.length, search.maxBodyBytes)
        assertTrue(transport.requests.all { it.url.host == "search.example.org" && it.headers["User-Agent"] == SearxngIdentity.USER_AGENT &&
            it.headers.keys == setOf("Host", "Connection", "User-Agent", "Accept", "Accept-Encoding") })
        assertTrue(result.results.isEmpty()); assertEquals(1, result.candidates.size)
        assertEquals("https://videos.example.org/watch/one", result.candidates.single().pageUrl)
        assertEquals((robots.length + html.length).toLong(), result.responseBytes); assertTrue(result.exhausted)
    }

    @Test fun prefixedHtmlRequestsUseOriginRobotsAndPreserveQueryAcrossBoundedPagination() {
        val configured = htmlEndpoint(path = "/tenant/searxng/search")
        val policy = "User-agent: *\nDisallow: /\nAllow: /tenant/searxng/search\n"
        val next = "<nav id='pagination'><form class='next_page' action='/tenant/searxng/search' method='get'>" +
            "<input type='hidden' name='pageno' value='2'><input type='hidden' name='q' value='changed'>" +
            "<input type='hidden' name='engine_data' value='untrusted'></form></nav>"
        val firstHtml = html.replace("</div></main>", "$next</div></main>")
        val query = "Café / ? q=original + 中文"
        val transport = TestTransport(reply(policy, "text/plain"), reply(firstHtml, "text/html"))
        val first = htmlProvider(transport, endpoint = configured).search(request(query, endpoint = configured), operation())
        assertFalse(first.exhausted)
        assertEquals(listOf("/robots.txt", "/tenant/searxng/search"), transport.requests.map { it.url.rawPath })
        assertNull(transport.requests[0].url.rawQuery)
        assertEquals(query, params(transport.requests[1].url)["q"])
        assertEquals("1", params(transport.requests[1].url)["pageno"])
        assertEquals("html", params(transport.requests[1].url)["format"])
        assertTrue(first.results.isEmpty())
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, first.candidates.single().sourceReported!!.metadataLevel)
        val secondTransport = TestTransport(reply(policy, "text/plain"), reply(html, "text/html"))
        val second = htmlProvider(secondTransport, endpoint = configured).search(
            request(query, cursor = first.nextCursor, endpoint = configured), operation())
        assertEquals(listOf("/robots.txt", "/tenant/searxng/search"), secondTransport.requests.map { it.url.rawPath })
        assertEquals(query, params(secondTransport.requests[1].url)["q"])
        assertEquals("2", params(secondTransport.requests[1].url)["pageno"])
        assertEquals(setOf("q", "format", "categories", "pageno", "language", "safesearch"), params(secondTransport.requests[1].url).keys)
        assertTrue(second.exhausted)
    }

    @Test fun prefixedRobotsDenialStopsBeforeSearchAndRootPolicyCannotBeBorrowed() {
        val configured = htmlEndpoint(path = "/tenant/searxng/search")
        for (policy in listOf("User-agent: *\nDisallow: /\nAllow: /search",
            "User-agent: *\nAllow: /\nDisallow: /tenant/searxng/search",
            "User-agent: *\nAllow: /\nDisallow: /tenant/searxng/search?*q=*",
            "User-agent: *\nAllow: /\nDisallow: /*?*q=*")) {
            val transport = TestTransport(reply(policy, "text/plain"))
            fails(DiscoveryError.PROVIDER_FAILURE) {
                htmlProvider(transport, endpoint = configured).search(request(endpoint = configured), operation())
            }
            assertEquals(1, transport.requests.size)
            assertEquals("/robots.txt", transport.requests.single().url.rawPath)
        }
    }

    @Test fun prefixCannotChangeResponseRouteOrBorrowAnotherPrefixesCursor() {
        val configured = htmlEndpoint(path = "/tenant/searxng/search")
        val policy = "User-agent: *\nAllow: /\n"
        val next = "<nav id='pagination'><form class='next_page' action='/tenant/searxng/search' method='get'>" +
            "<input type='hidden' name='pageno' value='2'></form></nav>"
        val nextHtml = html.replace("</div></main>", "$next</div></main>")
        val first = htmlProvider(TestTransport(reply(policy, "text/plain"), reply(nextHtml, "text/html")), endpoint = configured)
            .search(request(endpoint = configured), operation())
        for (other in listOf(htmlEndpoint(), htmlEndpoint(path = "/other/search"))) {
            val transport = TestTransport()
            fails(DiscoveryError.PROVIDER_FAILURE) {
                htmlProvider(transport, endpoint = other).search(request(cursor = first.nextCursor, endpoint = other), operation())
            }
            assertTrue(transport.requests.isEmpty())
        }
        val wrongSearch = TestTransport(reply(policy, "text/plain"), Reply(html, headers = mapOf("Content-Type" to listOf("text/html")),
            url = URI("https://search.example.org/search?q=short+film")))
        fails(DiscoveryError.PROVIDER_FAILURE) {
            htmlProvider(wrongSearch, endpoint = configured).search(request(endpoint = configured), operation())
        }
        assertEquals(2, wrongSearch.requests.size)
        val wrongRobots = TestTransport(Reply(policy, headers = mapOf("Content-Type" to listOf("text/plain")),
            url = URI("https://search.example.org/tenant/searxng/robots.txt")))
        fails(DiscoveryError.PROVIDER_FAILURE) {
            htmlProvider(wrongRobots, endpoint = configured).search(request(endpoint = configured), operation())
        }
        assertEquals(1, wrongRobots.requests.size)
        val wrongAction = TestTransport(reply(policy, "text/plain"), reply(nextHtml.replace("action='/tenant/searxng/search'", "action='/search'"), "text/html"))
        fails(DiscoveryError.PROVIDER_FAILURE) {
            htmlProvider(wrongAction, endpoint = configured).search(request(endpoint = configured), operation())
        }
        assertEquals(2, wrongAction.requests.size)
    }

    @Test fun safeSearchInvalidQueryExcludedSourceAndCancelledOperationsDoNoIo() {
        val transport = TestTransport(); val provider = htmlProvider(transport)
        fails(DiscoveryError.UNSUPPORTED_FILTER) { provider.search(request(safe = true), operation()) }
        for (query in listOf(" ", "line\nbreak", "bad\ud800"))
            fails(DiscoveryError.INVALID_QUERY) { provider.search(request(query), operation()) }
        val excluded = request().let { it.copy(profile = it.profile.copy(filters = SearchFilters(sourceNamespaces = setOf("other"), safeSearch = false))) }
        assertTrue(provider.search(excluded, operation()).exhausted)
        fails(DiscoveryError.CANCELLED) { provider.search(request(), operation().also { it.cancellation.cancel() }) }
        fails(DiscoveryError.DEADLINE_EXCEEDED) { provider.search(request(), operation(deadline = 1000)) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun deniedUnknownMalformedAndUnreadableRobotsNeverStartSearch() {
        for (text in listOf("User-agent: *\nDisallow: /", "User-agent: *\nAllow: /\nDisallow: /*?*q=*", "",
            "<html>not robots</html>", "User-agent: *\nAllow: /\nCrawl-delay: 30", "Allow: /")) {
            val transport = TestTransport(reply(text, "text/plain"))
            fails(DiscoveryError.PROVIDER_FAILURE) { htmlProvider(transport).search(request(), operation()) }
            assertEquals(1, transport.requests.size); assertEquals(SearxngRequestKind.ROBOTS, transport.requests.single().kind)
        }
        for (status in listOf(201, 204, 301, 302, 307, 308, 401, 403, 404, 410, 500)) {
            val transport = TestTransport(reply(robots, "text/plain", status))
            fails(DiscoveryError.PROVIDER_FAILURE) { htmlProvider(transport).search(request(), operation()) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun exactRoutesCannotCrossProtocolOriginOrBorrowRobotsForResults() {
        val json = endpoint(); val html = htmlEndpoint()
        fun reject(endpoint: SearxngEndpoint, uri: String, kind: SearxngRequestKind) =
            protocolFails(SearxngError.UNSAFE_URL) { SearxngUrlPolicy.requireRequest(endpoint, URI(uri), kind) }
        reject(json, "https://search.example.org/robots.txt", SearxngRequestKind.ROBOTS)
        reject(json, "https://search.example.org/search?q=x", SearxngRequestKind.HTML_SEARCH)
        reject(html, "https://search.example.org/search?q=x", SearxngRequestKind.JSON_SEARCH)
        for (uri in listOf("https://videos.example.org/robots.txt", "https://search.example.org/robots.txt?q=x",
            "https://search.example.org/search", "http://search.example.org/robots.txt")) reject(html, uri, SearxngRequestKind.ROBOTS)
        reject(html, "https://videos.example.org/watch?q=x", SearxngRequestKind.HTML_SEARCH)
        SearxngUrlPolicy.requireRequest(html, URI("https://search.example.org/robots.txt"), SearxngRequestKind.ROBOTS)
        SearxngUrlPolicy.requireRequest(html, URI("https://search.example.org/search?q=x"), SearxngRequestKind.HTML_SEARCH)
    }

    @Test fun prefixedTypedRoutesPermitOnlySelectedSearchAndOriginRobots() {
        val configured = htmlEndpoint(path = "/tenant/search")
        SearxngUrlPolicy.requireRequest(configured, URI(configured.searchUrl + "?q=x"), SearxngRequestKind.HTML_SEARCH)
        SearxngUrlPolicy.requireRequest(configured, URI("https://search.example.org/robots.txt"), SearxngRequestKind.ROBOTS)
        for (path in listOf("/search", "/other/search", "/tenant/search/", "/tenant/robots.txt", "/robots.txt"))
            protocolFails(SearxngError.UNSAFE_URL) {
                SearxngUrlPolicy.requireRequest(configured, URI("https://search.example.org$path?q=x"), SearxngRequestKind.HTML_SEARCH)
            }
        for (path in listOf("/tenant/robots.txt", "/tenant/search", "/robots.txt/", "/%72obots.txt"))
            protocolFails(SearxngError.UNSAFE_URL) {
                SearxngUrlPolicy.requireRequest(configured, URI("https://search.example.org$path"), SearxngRequestKind.ROBOTS)
            }
        protocolFails(SearxngError.UNSAFE_URL) {
            SearxngUrlPolicy.requireRequest(configured, URI(configured.searchUrl + "?q=x"), SearxngRequestKind.JSON_SEARCH)
        }
    }

    @Test fun bodyBudgetsAreSharedAndDoNotResetAfterRobots() {
        val total = robots.length + html.length
        val exact = TestTransport(reply(robots, "text/plain"), reply(html, "text/html"))
        assertEquals(total.toLong(), htmlProvider(exact, maximum = total.toLong()).search(request(), operation()).responseBytes)
        assertEquals(html.length.toLong(), exact.requests[1].maxBodyBytes)
        val over = TestTransport(reply(robots, "text/plain"), reply(html, "text/html"))
        fails(DiscoveryError.RESPONSE_TOO_LARGE) { htmlProvider(over, maximum = total - 1L).search(request(), operation()) }
        assertEquals(html.length - 1L, over.requests[1].maxBodyBytes)
        val zero = TestTransport()
        fails(DiscoveryError.RESPONSE_TOO_LARGE) { htmlProvider(zero).search(request(), operation(maximum = 1)) }
        assertTrue(zero.requests.isEmpty())
    }

    @Test fun malformedHeadersEncodingChallengeAndRedirectCannotYieldCandidates() {
        val badRobots = listOf(
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/html"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain"), "content-type" to listOf("text/plain"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain"), "Content-Encoding" to listOf("gzip"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain"), "WWW-Authenticate" to listOf("Basic realm=private"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain"), "Proxy-Authenticate" to listOf("Basic realm=private"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain"), "cf-mitigated" to listOf("challenge"))),
            Reply(robots, headers = mapOf("Content-Type" to listOf("text/plain")), url = URI("https://other.example.org/robots.txt")))
        for (reply in badRobots) {
            val transport = TestTransport(reply)
            fails(DiscoveryError.PROVIDER_FAILURE) { htmlProvider(transport).search(request(), operation()) }
            assertEquals(1, transport.requests.size)
        }
        val challenge = html.replace("<main", "<input type='password'><main")
        val transport = TestTransport(reply(robots, "text/plain"), reply(challenge, "text/html"))
        fails(DiscoveryError.PROVIDER_FAILURE) { htmlProvider(transport).search(request(), operation()) }
        assertEquals(2, transport.requests.size)
    }

    @Test fun robotsSlowdownPreservesRetryTimeAndPhysicalBusyWhileCloseStalls() {
        for (status in listOf(429, 503)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val input = object : InputStream() {
                override fun read(): Int = error("Denial body must not be read")
                override fun close() { entered.countDown(); awaitUninterruptibly(release) }
            }
            val transport = TestTransport(Reply(status = status, headers = mapOf("Retry-After" to listOf("120")), stream = input))
            val provider = htmlProvider(transport)
            try {
                assertEquals(121000L, fails(DiscoveryError.RATE_LIMITED) { provider.search(request(), operation()) }.retryAtEpochMillis)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                fails(DiscoveryError.BUSY) { provider.search(request(), operation()) }; assertEquals(1, transport.requests.size)
            } finally { release.countDown() }
            awaitNotBusy(provider, DiscoveryError.RATE_LIMITED); assertEquals(1, transport.requests.size)
        }
    }

    @Test(timeout = 10000) fun lateRobotsResponseCannotReleasePhysicalOwnerBeforeItsClose() {
        for (deadline in listOf(false, true)) {
            val started = CountDownLatch(1); val releaseExecute = CountDownLatch(1); val enteredClose = CountDownLatch(1)
            val releaseClose = CountDownLatch(1); val cancelled = CountDownLatch(1); val count = AtomicInteger()
            val timer = TestTimer(); val op = operation()
            val transport = SearxngTransport { http ->
                count.incrementAndGet()
                object : SearxngHttpCall {
                    override fun execute(): SearxngHttpResponse {
                        started.countDown(); awaitUninterruptibly(releaseExecute)
                        return SearxngHttpResponse(200, http.url, mapOf("Content-Type" to listOf("text/plain")),
                            object : ByteArrayInputStream(robots.toByteArray()) {
                                override fun close() { enteredClose.countDown(); awaitUninterruptibly(releaseClose) }
                            })
                    }
                    override fun cancel() { cancelled.countDown() }
                }
            }
            val provider = htmlProvider(transport, timer = timer); val outcome = AtomicReference<Throwable?>()
            val thread = Thread { try { provider.search(request(), op) } catch (failure: Throwable) { outcome.set(failure) } }
            thread.start()
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS)); if (deadline) timer.fire() else op.cancellation.cancel()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                fails(DiscoveryError.BUSY) { provider.search(request(), operation()) }
                releaseExecute.countDown(); assertTrue(enteredClose.await(2, TimeUnit.SECONDS))
                fails(DiscoveryError.BUSY) { provider.search(request(), operation()) }; assertEquals(1, count.get())
                releaseClose.countDown(); thread.join(2000)
                assertFalse(thread.isAlive)
                assertEquals(if (deadline) DiscoveryError.DEADLINE_EXCEEDED else DiscoveryError.CANCELLED,
                    (outcome.get() as? DiscoveryFailure)?.code)
            } finally { releaseExecute.countDown(); releaseClose.countDown(); thread.join(2000) }
        }
    }

    @Test fun timeSpentOnRobotsCannotInflateSearchDeadline() {
        val clock = TestClock(); val input = object : ByteArrayInputStream(robots.toByteArray()) {
            override fun read(b: ByteArray, off: Int, length: Int): Int = super.read(b, off, length).also { clock.now += 1000 }
        }
        val transport = TestTransport(reply(robots, "text/plain", stream = input), reply(html, "text/html"))
        htmlProvider(transport, clock).search(request(), operation(deadline = 6000))
        assertEquals(6000L, transport.requests[0].deadlineEpochMillis); assertEquals(6000L, transport.requests[1].deadlineEpochMillis)
        assertTrue(transport.requests[1].connectTimeoutMillis <= 4000)
    }

    private fun params(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    private fun awaitNotBusy(provider: SearxngHtmlSearchProvider, expected: DiscoveryError) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try { provider.search(request(), operation()); fail("Expected rejection") }
            catch (failure: DiscoveryFailure) { if (failure.code != DiscoveryError.BUSY) { assertEquals(expected, failure.code); return } }
            if (System.nanoTime() >= end) fail("Physical slot did not release")
            Thread.yield()
        }
    }
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
