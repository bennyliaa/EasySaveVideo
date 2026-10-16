package com.easysavevideo.commons.rules

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Same actual transport, with test-only physical loopback routing and unchanged logical binding. */
internal fun ownedLoopbackRuleHttpSource(endpoint: RuleHttpEndpoint, port: Int, clock: EpochClock): RuleResponseSource =
    AnonymousRuleHttpSource.ownedFixture(endpoint, port, clock)

class AnonymousRuleHttpSourceTest {
    private val clock = EpochClock(System::currentTimeMillis)
    private fun endpoint(format: RuleResponseFormat = RuleResponseFormat.JSON) = RuleHttpEndpoint.https(
        "owned-fixture.invalid", "/search", format, setOf("und"), mapOf(
            "q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY), "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR)))
    private fun request(endpoint: RuleHttpEndpoint, cap: Long = 65536, duration: Long = 5000,
        query: String = "owned &=?%#+", cursor: String? = null): RuleResponseRequest {
        val binding = endpoint.bind(endpoint.endpointId, "/search", listOf(RuleHttpQueryValue("q", RuleInputSlot.QUERY),
            RuleHttpQueryValue("after", RuleInputSlot.CURSOR)), query, "und", cursor)
        return RuleResponseRequest(query, "und", cursor, clock.nowMillis() + duration, cap, binding)
    }
    private fun refused(code: DiscoveryError? = null, action: () -> Unit) {
        try { action(); fail("expected transport refusal") }
        catch (failure: DiscoveryFailure) { if (code != null) assertEquals(code, failure.code) }
    }
    private fun server(status: Int = 200, body: ByteArray = "{}".toByteArray(), mime: String = "application/json; charset=UTF-8",
        headers: Map<String, String> = emptyMap(), chunked: Boolean = false,
        action: (Int, AtomicInteger, CopyOnWriteArrayList<String>) -> Unit) {
        val count = AtomicInteger(); val requests = CopyOnWriteArrayList<String>()
        val methods = CopyOnWriteArrayList<String>()
        val requestHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Count every path so a redirect to a different resource cannot escape the assertion.
        server.createContext("/") { exchange ->
            count.incrementAndGet(); requests += exchange.requestURI.toASCIIString()
            methods += exchange.requestMethod
            requestHeaders += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
            try {
                exchange.responseHeaders.add("Content-Type", mime)
                headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
                exchange.sendResponseHeaders(status, if (chunked) 0 else body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        try {
            action(server.address.port, count, requests)
            assertTrue(methods.all { it == "GET" })
            for (headersSeen in requestHeaders) {
                assertTrue(setOf("authorization", "proxy-authorization", "cookie", "cookie2", "referer", "origin")
                    .none { it in headersSeen })
                assertEquals(listOf("identity"), headersSeen["accept-encoding"])
                assertEquals(listOf(RuleHttpEndpoint.USER_AGENT), headersSeen["user-agent"])
            }
        } finally { server.stop(0) }
    }
    private fun readHeader(input: java.io.InputStream) {
        var rolling = 0
        repeat(20000) {
            val value = input.read(); if (value < 0) return
            rolling = (rolling shl 8) or value
            if (rolling == 0x0d0a0d0a) return
        }
        error("owned request exceeded fixture limit")
    }
    /** Raw owned socket fixture is necessary for duplicate headers, EOF framing and truncated bodies. */
    private fun raw(response: String, action: (Int, AtomicInteger) -> Unit) {
        val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val requests = AtomicInteger()
        val worker = Thread({
            try { listener.accept().use { socket ->
                socket.soTimeout = 3000; readHeader(socket.getInputStream()); requests.incrementAndGet()
                socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
            } } catch (_: Exception) { }
        }, "esv-owned-rule-http").apply { isDaemon = true; start() }
        try { action(listener.localPort, requests) } finally { listener.close(); worker.join(4000); assertFalse(worker.isAlive) }
    }
    @Test fun actualHttpIsAnonymousSingleGetAndKeepsEncodedValuesAndLogicalBase() = server { port, count, requests ->
        val endpoint = endpoint(); val source = ownedLoopbackRuleHttpSource(endpoint, port, clock)
        val request = request(endpoint, cursor = "https://evil.invalid/?x=1&y=%#+")
        source.create(request).execute().use { assertArrayEquals("{}".toByteArray(), it.readBytes()) }
        assertEquals(1, count.get()); assertEquals(request.httpBinding!!.encodedPathAndQuery, requests.single())
        assertTrue(request.httpBinding!!.url.startsWith("https://owned-fixture.invalid/search?"))
    }
    @Test fun actualHtmlMimeIsDistinctFromJson() = server(body = "<main></main>".toByteArray(), mime = "text/html") { port, _, _ ->
        val endpoint = endpoint(RuleResponseFormat.HTML)
        ownedLoopbackRuleHttpSource(endpoint, port, clock).create(request(endpoint)).execute().use {
            assertEquals("<main></main>", String(it.readBytes()))
        }
    }
    @Test fun allFollowupStatusesIncluding503ZeroAreOnlyOneActualExchange() {
        for (status in listOf(302, 401, 403, 407, 408, 429, 503)) server(status = status,
            headers = mapOf("Retry-After" to "0", "Location" to "/should-not-follow")) { port, count, _ ->
            val endpoint = endpoint(); val source = ownedLoopbackRuleHttpSource(endpoint, port, clock)
            refused(if (status in setOf(429, 503)) DiscoveryError.RATE_LIMITED else DiscoveryError.PROVIDER_FAILURE) {
                source.create(request(endpoint)).execute().use { it.readBytes() }
            }
            assertEquals(1, count.get())
        }
    }
    @Test fun transferEncodingIsRefusedBeforeChunkExtensionOrBodyRead() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0;" + "x".repeat(20000) + "\r\n\r\n"
        raw(response) { port, count ->
            val endpoint = endpoint()
            refused(DiscoveryError.INVALID_RESULT) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
                .create(request(endpoint)).execute().use { it.readBytes() } }
            assertEquals(1, count.get())
        }
    }
    @Test fun realChunkedJdkServerIsOutsideThisFiniteProfile() = server(chunked = true) { port, count, _ ->
        val endpoint = endpoint()
        refused(DiscoveryError.INVALID_RESULT) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
            .create(request(endpoint)).execute().use { it.readBytes() } }
        assertEquals(1, count.get())
    }
    @Test fun eofDelimitedBodyIsBoundedAndExactCapStillRequiresEof() {
        for (body in listOf("12345678", "123456789")) raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$body") { port, _ ->
            val endpoint = endpoint(); val call = ownedLoopbackRuleHttpSource(endpoint, port, clock).create(request(endpoint, cap = 8))
            if (body.length == 8) call.execute().use { assertEquals(body, String(it.readBytes())) }
            else refused(DiscoveryError.RESPONSE_TOO_LARGE) { call.execute().use { it.readBytes() } }
        }
    }
    @Test fun declaredOversizeAndTruncatedBodyAreRejected() {
        raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10\r\nConnection: close\r\n\r\n{}") { port, _ ->
            val endpoint = endpoint()
            refused(DiscoveryError.RESPONSE_TOO_LARGE) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
                .create(request(endpoint, cap = 8)).execute().use { it.readBytes() } }
        }
        raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10\r\nConnection: close\r\n\r\n{}") { port, _ ->
            val endpoint = endpoint()
            refused(DiscoveryError.INVALID_RESULT) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
                .create(request(endpoint)).execute().use { it.readBytes() } }
        }
    }
    @Test fun duplicateCriticalHeadersAndTrailerAreRejected() {
        val invalid = listOf("Content-Length: 2\r\nContent-Length: 2\r\n", "Content-Length: -1\r\n",
            "Content-Type: application/json\r\n", "Content-Encoding: identity\r\nContent-Encoding: identity\r\n", "Trailer: X-Secret\r\n")
        for (headers in invalid) raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${headers}Connection: close\r\n\r\n{}") { port, _ ->
            val endpoint = endpoint()
            refused { ownedLoopbackRuleHttpSource(endpoint, port, clock).create(request(endpoint)).execute().use { it.readBytes() } }
        }
    }
    @Test fun wrongMimeCharsetAndCompressionAreRejected() {
        for (mime in listOf("text/html", "application/jsonx", "application/json; charset=iso-8859-1", "application/json; charset=utf-8; charset=utf-8"))
            server(mime = mime) { port, _, _ ->
                val endpoint = endpoint()
                refused(DiscoveryError.INVALID_RESULT) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
                    .create(request(endpoint)).execute().use { it.readBytes() } }
            }
        server(headers = mapOf("Content-Encoding" to "gzip")) { port, _, _ ->
            val endpoint = endpoint()
            refused(DiscoveryError.INVALID_RESULT) { ownedLoopbackRuleHttpSource(endpoint, port, clock)
                .create(request(endpoint)).execute().use { it.readBytes() } }
        }
    }
    @Test fun parsedHeaderAndHeaderCountAcceptanceLimitsReject() {
        for (headers in listOf("X-Owned: " + "x".repeat(17000) + "\r\n", (1..65).joinToString("") { "X-Owned-$it: x\r\n" }))
            raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n${headers}Connection: close\r\n\r\n{}") { port, _ ->
                val endpoint = endpoint()
                refused { ownedLoopbackRuleHttpSource(endpoint, port, clock).create(request(endpoint)).execute().use { it.readBytes() } }
            }
    }
    @Test fun nativeOversizedHeaderFailsWithoutClaimingTheSmallerParsedBudget() = raw(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Owned: " + "x".repeat(300000) + "\r\n\r\n") { port, _ ->
        val endpoint = endpoint()
        refused { ownedLoopbackRuleHttpSource(endpoint, port, clock).create(request(endpoint)).execute().use { it.readBytes() } }
    }
    @Test fun missingOrCrossEndpointBindingRefusesWithoutAnyHttp() = server { port, count, _ ->
        val endpoint = endpoint(); val source = ownedLoopbackRuleHttpSource(endpoint, port, clock)
        refused(DiscoveryError.SOURCE_CONFIGURATION) { source.create(RuleResponseRequest("owned", "und", null, clock.nowMillis() + 1000, 64)) }
        refused(DiscoveryError.SOURCE_CONFIGURATION) { source.create(request(endpoint())) }
        assertEquals(0, count.get())
    }
    @Test fun cancelledAndExpiredCallsCannotStartAndCallsCannotExecuteTwice() = server { port, count, _ ->
        val endpoint = endpoint(); val source = ownedLoopbackRuleHttpSource(endpoint, port, clock)
        val cancelled = source.create(request(endpoint)); cancelled.cancel(); cancelled.cancel()
        refused(DiscoveryError.CANCELLED) { cancelled.execute() }
        refused(DiscoveryError.DEADLINE_EXCEEDED) { source.create(request(endpoint, duration = -1)).execute() }
        assertEquals(0, count.get())
        val once = source.create(request(endpoint)); once.execute().use { it.readBytes() }
        refused(DiscoveryError.SOURCE_CONFIGURATION) { once.execute() }
        assertEquals(1, count.get())
    }
    @Test fun closingBeforeEofCancelsNativeCallAndDisconnectsWithoutReleasingRemainingBody() {
        val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val disconnected = CountDownLatch(1); val peer = AtomicReference<java.net.Socket>()
        val worker = Thread({
            try { listener.accept().use { socket ->
                peer.set(socket); socket.soTimeout = 3000; readHeader(socket.getInputStream())
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: 20\r\nConnection: close\r\n\r\nx").toByteArray())
                socket.getOutputStream().flush()
                // Deliberately never release the remaining 19 bytes. Only client EOF/reset completes this latch.
                try { if (socket.getInputStream().read() == -1) disconnected.countDown() }
                catch (_: SocketException) { disconnected.countDown() }
            } } catch (_: Exception) { }
        }, "esv-owned-partial-body").apply { isDaemon = true; start() }
        var stream: java.io.InputStream? = null
        try {
            val endpoint = endpoint()
            val call = ownedLoopbackRuleHttpSource(endpoint, listener.localPort, clock).create(request(endpoint))
            stream = call.execute(); assertEquals('x'.code, stream.read())
            // Read-only test inspection of the real native Call avoids mistaking close's discard timeout for cancel.
            val field = call.javaClass.getDeclaredField("nativeCall").apply { isAccessible = true }
            val native = (field.get(call) as AtomicReference<*>).get() as okhttp3.Call
            assertFalse(native.isCanceled())
            stream.close()
            assertTrue(native.isCanceled())
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
        } finally {
            stream?.close(); peer.get()?.close(); listener.close(); worker.join(4000); assertFalse(worker.isAlive)
        }
    }
    @Test fun productionDnsRejectsPrivateAndMixedAnswersBeforeAnyConnection() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val private = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (answers in listOf(emptyList(), listOf(private), listOf(public, private))) {
            val endpoint = endpoint(); val count = AtomicInteger()
            val source = AnonymousRuleHttpSource.resolverFixture(endpoint, clock) { count.incrementAndGet(); answers }
            refused { source.create(request(endpoint)).execute() }
            assertEquals(1, count.get())
        }
    }
    @Test fun cancelledStuckDnsRetainsExistingRuntimeSlotAndSiblingStillFinishes() {
        val endpoint = endpoint(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = AnonymousRuleHttpSource.resolverFixture(endpoint, clock) {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        val provider = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("owned-http", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                val call = source.create(this@AnonymousRuleHttpSourceTest.request(endpoint)); val registration = operation.cancellation.onCancel { call.cancel() }
                try { call.execute().use { it.readBytes() }; return ProviderPage(exhausted = true) }
                finally { registration.cancel(); call.cancel() }
            }
        }
        val timer = JvmDiscoveryTimer(); val runtime = IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock)
        fun operation(): DiscoveryOperation { val cancellation = DiscoveryCancellation()
            return DiscoveryOperation(OperationContext("owned-http-op", clock.nowMillis() + 5000, cancellation,
                resourceBudget = com.easysavevideo.core.model.ResourceBudget(maxConcurrentWork = 2)), cancellation) }
        val search = SearchRequest(SessionStamp("session", 1), SearchProfile("profile", "owned"), null)
        fun execute(p: VideoSearchProvider, operation: DiscoveryOperation = operation()): ProviderOutcome {
            val done = CountDownLatch(1); val result = AtomicReference<ProviderOutcome>()
            runtime.execute(p, search, operation) { result.set(it); done.countDown() }
            assertTrue(done.await(3, TimeUnit.SECONDS)); return result.get()
        }
        try {
            val op = operation(); val finished = CountDownLatch(1); val outcome = AtomicReference<ProviderOutcome>()
            runtime.execute(provider, search, op) { outcome.set(it); finished.countDown() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); op.cancellation.cancel()
            assertTrue(finished.await(3, TimeUnit.SECONDS)); assertEquals(DiscoveryError.CANCELLED, (outcome.get() as ProviderOutcome.Failure).code)
            assertEquals(DiscoveryError.BUSY, (execute(provider) as ProviderOutcome.Failure).code)
            val sibling = object : VideoSearchProvider {
                override val descriptor = ProviderDescriptor("owned-sibling", "1")
                override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage(exhausted = true)
            }
            val siblingOutcome = execute(sibling)
            assertTrue("sibling outcome: $siblingOutcome", siblingOutcome is ProviderOutcome.Success)
            release.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var afterRelease = execute(provider)
            while (afterRelease is ProviderOutcome.Failure && afterRelease.code == DiscoveryError.BUSY && System.nanoTime() < until) {
                Thread.sleep(5); afterRelease = execute(provider)
            }
            assertTrue(afterRelease is ProviderOutcome.Failure)
            assertNotEquals(DiscoveryError.BUSY, (afterRelease as ProviderOutcome.Failure).code)
        } finally { release.countDown(); runtime.close(); timer.close() }
    }
}
