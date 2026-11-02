package com.easysavevideo.commons

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CommonsMetadataProviderTest {
    @Test fun defaultDisabledAndMissingContactDoNotReachTransport() {
        fails(CommonsError.DISABLED) { CommonsMetadataProvider().search(request, operation()) }
        val transport = TestTransport()
        fails(CommonsError.CONTACT_REQUIRED) { CommonsMetadataProvider(CommonsClientConfig(true), transport).search(request, operation()) }
        assertTrue(transport.requests.isEmpty())
        fails(CommonsError.VERSION_REQUIRED) {
            CommonsMetadataProvider(CommonsClientConfig(true, approvedPublicContact = "mailto:operator@example.invalid"), transport)
                .search(request, operation())
        }
        fails(CommonsError.TRANSPORT_UNAVAILABLE) { CommonsMetadataProvider(config()).search(request, operation()) }
    }
    @Test fun safeSearchFailsExplicitlyWithoutSilentlyDroppingFilter() {
        val transport = TestTransport()
        fails(CommonsError.UNSUPPORTED_FILTER) { provider(transport).search(CommonsSearchRequest("bunny"), operation()) }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun recordedResponseProducesMetadataAndAttributionWithoutMediaAuthority() {
        val transport = TestTransport(Reply())
        val page = provider(transport).search(request, operation())
        val result = page.results.single()
        assertEquals(40108642L, result.pageId)
        assertEquals("Wikimedia Commons", result.sourceName)
        assertEquals(634_553L, result.durationMs)
        assertEquals(4000, result.width); assertEquals(2250, result.height)
        assertEquals("Blender Foundation", result.rights.author)
        assertEquals("CC BY 3.0", result.rights.licenseName)
        assertEquals("https://creativecommons.org/licenses/by/3.0", result.rights.licenseUrl)
        assertEquals(1212972787L, result.rights.sourcePageRevisionId)
        assertTrue(result.rights.customAttribution!!.contains("Blender Foundation"))
        assertEquals("Big Buck Bunny", result.rights.credit)
        assertEquals(CommonsRightsReview.REPORTED_REQUIRES_REVIEW, result.rights.review)
        assertTrue(result.rights.hasDisplayAttribution); assertFalse(result.rights.downloadAuthorized)
        assertNotNull(result.thumbnailUrl); assertTrue(result.thumbnailUrl!!.startsWith("https://thumb.wikimedia.org/"))
        assertTrue(result.rights.provenance.all { it.kind == CommonsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT })
        assertFalse(page.exhausted); assertNotNull(page.nextCursor)
        assertEquals(fixture().size.toLong(), page.responseBytes)
        assertEquals(1, transport.cancellations.get())
    }
    @Test fun requestIsAnonymousBoundedAndOnlyQueriesCommonsVideoNamespace() {
        val transport = TestTransport(Reply()); provider(transport).search(request, operation(maxBytes = 40_000))
        val http = transport.requests.single()
        assertEquals("https", http.url.scheme); assertEquals("commons.wikimedia.org", http.url.host)
        assertEquals("/w/api.php", http.url.path); assertEquals(40_000L, http.maxBodyBytes)
        assertEquals(5_000, http.connectTimeoutMillis); assertEquals(16_000L, http.deadlineEpochMillis)
        assertEquals(setOf("User-Agent", "Accept", "Accept-Encoding", "Host", "Connection"), http.headers.keys)
        assertEquals("identity", http.headers["Accept-Encoding"])
        assertEquals("commons.wikimedia.org", http.headers["Host"]); assertEquals("close", http.headers["Connection"])
        assertTrue(http.headers.getValue("User-Agent").startsWith("EasySaveVideo/0.3.0-m3a-dev"))
        val query = URLDecoder.decode(http.url.rawQuery, "UTF-8")
        assertTrue(query.contains("gsrnamespace=6")); assertTrue(query.contains("filetype:video"))
        assertTrue(query.contains("generator=search")); assertTrue(query.contains("prop=videoinfo|info"))
        assertFalse(query.contains("imageinfo")); assertFalse(query.contains("derivatives"))
        assertFalse(http.toString().contains(request.query)); assertFalse(config().toString().contains("example.invalid"))
    }
    @Test fun realContinuationIsReplayedVerbatimInsteadOfIncrementedLocally() {
        val transport = TestTransport(Reply(), Reply(fixture("search-live-2.json")))
        val provider = provider(transport)
        val first = provider.search(request, operation())
        val second = provider.search(CommonsSearchRequest(request.query, cursor = first.nextCursor, safeSearchRequired = false), operation())
        val query = URLDecoder.decode(transport.requests[1].url.rawQuery, "UTF-8")
        assertTrue(query.contains("gsroffset=1")); assertTrue(query.contains("continue=gsroffset||"))
        assertEquals(158250589L, second.results.single().pageId)
        assertNotEquals(first.nextCursor, second.nextCursor)
    }
    @Test fun cursorCannotOverwriteActionHostOrSearchConstraints() {
        val malicious = mutate { it["continue"] = Json.Obj(mapOf("action" to Json.Str("edit"))) }
        fails(CommonsError.INVALID_RESPONSE) { provider(TestTransport(Reply(malicious))).search(request, operation()) }
        val transport = TestTransport(Reply()); val provider = provider(transport)
        val page = provider.search(request, operation())
        fails(CommonsError.INVALID_CURSOR) { provider.search(CommonsSearchRequest("other", cursor = page.nextCursor, safeSearchRequired = false), operation()) }
        assertEquals(1, transport.requests.size)
    }
    @Test fun cacheIsFiniteAndDoesNotRefreshAttributionTimestamp() {
        val clock = TestClock(); val transport = TestTransport(Reply(), Reply())
        val provider = provider(transport, clock)
        val first = provider.search(request, operation())
        clock.now += 100
        val cached = provider.search(request, operation())
        assertTrue(cached.fromCache); assertEquals(0L, cached.responseBytes)
        assertEquals(first.results.single().rights.retrievedAtEpochMillis, cached.results.single().rights.retrievedAtEpochMillis)
        assertEquals(1, transport.requests.size)
        provider.clearCache(); provider.search(request, operation())
        assertEquals(2, transport.requests.size)
    }
    @Test fun noStoreAndExpiredCacheRequireFreshMetadata() {
        val noStore = mapOf("Content-Type" to listOf("application/json"), "Cache-Control" to listOf("no-store"))
        val transport = TestTransport(Reply(headers = noStore), Reply(headers = noStore))
        val p = provider(transport); p.search(request, operation()); p.search(request, operation())
        assertEquals(2, transport.requests.size)
        val clock = TestClock(); val cachedTransport = TestTransport(Reply(), Reply())
        val cached = provider(cachedTransport, clock, CommonsLimits(cacheTtlMillis = 10))
        cached.search(request, operation()); clock.now += 11; cached.search(request, operation())
        assertEquals(2, cachedTransport.requests.size)
    }
    @Test fun respectsRetryAfterWithoutSleepingRetryingOrChangingIdentity() {
        val headers = mapOf("Retry-After" to listOf("120"))
        val transport = TestTransport(Reply(status = 429, headers = headers), Reply())
        val clock = TestClock(); val p = provider(transport, clock)
        assertEquals(121_000L, fails(CommonsError.RATE_LIMITED) { p.search(request, operation()) }.retryAtEpochMillis)
        fails(CommonsError.RATE_LIMITED) { p.search(request, operation()) }
        assertEquals(1, transport.requests.size)
        clock.now = 121_001; p.search(request, operation(clock.now + 15_000))
        assertEquals(2, transport.requests.size)
    }
    @Test fun httpDateAndOverflowRetryAfterNeverRetryEarly() {
        val date = "Thu, 01 Jan 1970 00:02:00 GMT"
        val p = provider(TestTransport(Reply(status = 503, headers = mapOf("Retry-After" to listOf(date)))))
        assertEquals(120_000L, fails(CommonsError.RATE_LIMITED) { p.search(request, operation()) }.retryAtEpochMillis)
        val overflow = provider(TestTransport(Reply(status = 429, headers = mapOf("Retry-After" to listOf("99999999999999999999")))))
        assertEquals(Long.MAX_VALUE, fails(CommonsError.RATE_LIMITED) { overflow.search(request, operation()) }.retryAtEpochMillis)
    }
    @Test fun maxlagInsideHttp200AlsoCreatesCooldown() {
        val json = """{"error":{"code":"maxlag","info":"untrusted diagnostic"}}""".toByteArray()
        val p = provider(TestTransport(Reply(json)))
        assertEquals(31_000L, fails(CommonsError.RATE_LIMITED) { p.search(request, operation()) }.retryAtEpochMillis)
    }
    @Test fun denialAndRedirectsNeverFollowOrConsumeBodies() {
        val body = object : InputStream() { override fun read(): Int = throw AssertionError("Must not read") }
        for ((status, error) in listOf(403 to CommonsError.SERVICE_DENIED, 302 to CommonsError.REDIRECT_REJECTED, 500 to CommonsError.SERVICE_ERROR)) {
            val transport = TestTransport(Reply(status = status, stream = body))
            fails(error) { provider(transport).search(request, operation()) }
            assertEquals(1, transport.requests.size)
        }
        val transport = TestTransport(Reply(redirectedUrl = URI("https://commons.wikimedia.org/w/api.php?x=other")))
        fails(CommonsError.REDIRECT_REJECTED) { provider(transport).search(request, operation()) }
    }
    @Test fun bodyBudgetIsEnforcedBeforeBufferingAndParsing() {
        val transport = TestTransport(Reply())
        fails(CommonsError.RESPONSE_TOO_LARGE) { provider(transport).search(request, operation(maxBytes = 100)) }
        val headers = mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("2097153"))
        fails(CommonsError.RESPONSE_TOO_LARGE) { provider(TestTransport(Reply(headers = headers))).search(request, operation()) }
    }
    @Test fun malformedHeaderEncodingLengthAndDuplicateControlsAreRejected() {
        val cases = listOf(
            mapOf("Content-Type" to listOf("text/html")),
            mapOf("Content-Type" to listOf("application/json; charset=iso-8859-1")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Encoding" to listOf("gzip")),
            mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("2")),
            mapOf("Content-Type" to listOf("application/json"), "content-type" to listOf("text/html")),
            mapOf("Content-Type" to listOf("application/json\r\nInjected: x")),
        )
        cases.forEach { headers -> fails(CommonsError.INVALID_RESPONSE) { provider(TestTransport(Reply(headers = headers))).search(request, operation()) } }
    }
    @Test fun preCancelledAndExpiredOperationsNeverCreateCalls() {
        val transport = TestTransport(); val p = provider(transport)
        val op = operation(); op.cancellation.cancel()
        fails(CommonsError.CANCELLED) { p.search(request, op) }
        fails(CommonsError.DEADLINE_EXCEEDED) { p.search(request, operation(1000)) }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun budgetDeadlineIsRecheckedAfterEveryBodyRead() {
        val clock = TestClock()
        val stream = object : ByteArrayInputStream(fixture()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { clock.now = 16_000; return super.read(b, off, len) }
        }
        fails(CommonsError.DEADLINE_EXCEEDED) { provider(TestTransport(Reply(stream = stream)), clock).search(request, operation()) }
    }
    @Test fun schedulerFailureCancelsCreatedCallAndReleasesTheSlot() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val transport = CommonsTransport { object : CommonsHttpCall {
            init { calls.incrementAndGet() }
            override fun execute(): CommonsHttpResponse = throw AssertionError("Must not execute")
            override fun cancel() { cancellations.incrementAndGet() }
        } }
        val timer = object : com.easysavevideo.discovery.DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit): com.easysavevideo.discovery.CancellationHandle =
                throw java.util.concurrent.RejectedExecutionException("private text")
        }
        val p = CommonsMetadataProvider(config(), transport, clock = TestClock(), timer = timer)
        repeat(2) { fails(CommonsError.SERVICE_ERROR) { p.search(request, operation()) } }
        assertEquals(2, calls.get()); assertEquals(2, cancellations.get())
    }
    @Test fun exhaustedCancellationHookCapacityStillCleansCreatedCall() {
        val transport = TestTransport(Reply(), Reply())
        val p = provider(transport); val op = operation()
        repeat(8) { op.cancellation.onCancel { } }
        fails(CommonsError.SERVICE_ERROR) { p.search(request, op) }
        assertEquals(1, transport.cancellations.get())
        assertEquals(1, p.search(request, operation()).results.size)
        assertEquals(2, transport.cancellations.get())
    }
    @Test fun transportCreationExceptionIsSanitized() {
        val p = provider(CommonsTransport { throw IllegalArgumentException("private-url-cookie-token") })
        val failure = fails(CommonsError.SERVICE_ERROR) { p.search(request, operation()) }
        assertEquals("SERVICE_ERROR", failure.message); assertNull(failure.cause)
    }
    @Test fun actualCancelClosesInflightReadAndSerialSlotStaysOccupied() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val transport = CommonsTransport { http -> object : CommonsHttpCall {
            override fun execute(): CommonsHttpResponse {
                entered.countDown(); cancelled.await(3, TimeUnit.SECONDS)
                throw IllegalStateException("private-url-cookie-token")
            }
            override fun cancel() { cancelled.countDown() }
        } }
        val p = provider(transport); val op = operation(); val outcome = AtomicReference<CommonsError>()
        val thread = Thread { try { p.search(request, op) } catch (failure: CommonsFailure) { outcome.set(failure.code) } }
        thread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        fails(CommonsError.BUSY) { p.search(request, operation()) }
        op.cancellation.cancel(); assertTrue(cancelled.await(2, TimeUnit.SECONDS)); thread.join(2000)
        assertFalse(thread.isAlive); assertEquals(CommonsError.CANCELLED, outcome.get())
    }
    @Test fun returnedMutableCollectionsCannotRewriteRightsInMetadataCache() {
        val bytes = mutate { root -> changeFirstInfo(root) { info ->
            val metadata = info.getValue("extmetadata").objectValue().toMutableMap()
            metadata["Restrictions"] = Json.Obj(mapOf("value" to Json.Arr(listOf(Json.Str("personality"), Json.Str("trademark")))))
            info["extmetadata"] = Json.Obj(metadata)
        } }
        val transport = TestTransport(Reply(bytes)); val provider = provider(transport)
        val first = provider.search(request, operation())
        val expected = first.detached()
        (first.results.single().rights.restrictions as MutableList).clear()
        (first.results.single().rights.provenance as MutableList).clear()
        (first.results as MutableList).clear()
        val second = provider.search(request, operation())
        assertEquals(expected.results, second.results); assertTrue(second.fromCache)
        (second.results.single().rights.restrictions as MutableList).clear()
        (second.results.single().rights.provenance as MutableList).clear()
        (second.results as MutableList).clear()
        val third = provider.search(request, operation())
        assertEquals(expected.results, third.results)
        assertEquals(1, transport.requests.size)
    }

}
