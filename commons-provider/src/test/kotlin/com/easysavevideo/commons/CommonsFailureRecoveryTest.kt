package com.easysavevideo.commons

import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryTimer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Offline, ordinary fixed-Commons failures. No real sockets, backoff sleeps, or stress payloads. */
class CommonsFailureRecoveryTest {
    @Test fun unreachableAndTimeoutErrorsAreSanitizedSingleAttemptsAndAllowExplicitRecovery() {
        for (error in listOf(UnknownHostException("private hostname"), ConnectException("private endpoint"),
            SocketTimeoutException("private endpoint"))) {
            val transport = ScriptedTransport({ throw error }, { reply(it) })
            val provider = provider(transport)
            val failed = fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation()) }
            assertEquals("SERVICE_ERROR", failed.message)
            assertNull(failed.cause)
            assertEquals(1, transport.requests.size)
            assertEquals(1, transport.executions.get())
            assertEquals(1, transport.cancellations.get())
            assertFreshThenCached(provider, transport, expectedExchanges = 2)
        }
    }

    @Test fun partialReadIOExceptionClosesResponseAndNeverCachesPartialMetadata() {
        val body = object : TrackedBody(fixture()) {
            private var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("private response details")
                first = false
                return super.read(buffer, offset, minOf(length, 17))
            }
        }
        val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
        val provider = provider(transport)
        val failed = fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation()) }
        assertEquals("SERVICE_ERROR", failed.message)
        assertNull(failed.cause)
        assertEquals(17, body.bytesRead)
        assertEquals(1, body.closes)
        assertEquals(1, transport.executions.get())
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun ordinaryMalformedOrTruncatedJsonDoesNotPoisonSameQueryRecovery() {
        for (bytes in listOf("{", "{\"batchcomplete\":", "<html>Service unavailable</html>")) {
            val body = TrackedBody(bytes.toByteArray())
            val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
            val provider = provider(transport)
            val failed = fails(CommonsError.INVALID_RESPONSE) { provider.search(request, operation()) }
            assertEquals("INVALID_RESPONSE", failed.message)
            assertNull(failed.cause)
            assertEquals(1, body.closes)
            assertEquals(1, transport.executions.get())
            assertFreshThenCached(provider, transport, expectedExchanges = 2)
        }
    }

    @Test fun truncatedDeclaredBodyIsRejectedAndSubsequentCompletePageIsFresh() {
        val bytes = fixture()
        val body = TrackedBody(bytes.copyOf(bytes.size - 1))
        val transport = ScriptedTransport({ reply(it, body, contentLength = bytes.size.toLong()) }, { reply(it) })
        val provider = provider(transport)
        fails(CommonsError.INVALID_RESPONSE) { provider.search(request, operation()) }
        assertEquals(bytes.size - 1, body.bytesRead)
        assertEquals(1, body.closes)
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun normalDeclaredResponseLimitRejectsBeforeReadingAndReleasesForRecovery() {
        val body = object : InputStream() {
            var closes = 0
            override fun read(): Int = throw AssertionError("Oversized declared body must remain unread")
            override fun close() { closes++ }
        }
        val transport = ScriptedTransport({ reply(it, body, contentLength = CommonsLimits().maxResponseBytes + 1) },
            { reply(it) })
        val provider = provider(transport)
        fails(CommonsError.RESPONSE_TOO_LARGE) { provider.search(request, operation()) }
        assertEquals(1, body.closes)
        assertEquals(1, transport.cancellations.get())
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun smallOperationBudgetReadsOnlyOneExcessByteAndFailedBodyIsNotCached() {
        val body = TrackedBody(ByteArray(96) { 'x'.code.toByte() })
        val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
        val provider = provider(transport)
        fails(CommonsError.RESPONSE_TOO_LARGE) { provider.search(request, operation(maxBytes = 64)) }
        assertEquals(64L, transport.requests.single().maxBodyBytes)
        assertEquals(65, body.bytesRead)
        assertEquals(1, body.closes)
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun zeroProgressBodyFailsAfterOneReadAndDoesNotBlockFollowingRequest() {
        val body = object : TrackedBody(byteArrayOf()) {
            var reads = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
        val provider = provider(transport)
        fails(CommonsError.INVALID_RESPONSE) { provider.search(request, operation()) }
        assertEquals(1, body.reads)
        assertEquals(1, body.closes)
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun expiredCacheDoesNotBecomeStaleSuccessWhenRefreshFails() {
        val clock = TestClock()
        val transport = ScriptedTransport({ reply(it) }, { throw ConnectException("private endpoint") }, { reply(it) })
        val provider = provider(transport, clock, CommonsLimits(cacheTtlMillis = 50))
        val original = provider.search(request, operation())
        clock.now += 49
        assertTrue(provider.search(request, operation()).fromCache)
        assertEquals(1, transport.executions.get())
        clock.now++ // Expiry is exclusive: equality requires a fresh exchange.
        fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation()) }
        assertEquals(2, transport.executions.get())
        val recovered = provider.search(request, operation())
        assertFalse(recovered.fromCache)
        assertEquals(clock.now, recovered.results.single().rights.retrievedAtEpochMillis)
        assertNotEquals(original.results.single().rights.retrievedAtEpochMillis,
            recovered.results.single().rights.retrievedAtEpochMillis)
        assertTrue(provider.search(request, operation()).fromCache)
        assertEquals(3, transport.executions.get())
    }

    @Test fun failedOtherQueryDoesNotEvictTheOnlyValidCacheEntry() {
        val transport = ScriptedTransport({ reply(it) }, { throw UnknownHostException("private hostname") })
        val provider = provider(transport, limits = CommonsLimits(cacheEntries = 1))
        val original = provider.search(request, operation())
        fails(CommonsError.SERVICE_ERROR) {
            provider.search(CommonsSearchRequest("other query", safeSearchRequired = false), operation())
        }
        val cached = provider.search(request, operation())
        assertTrue(cached.fromCache)
        assertEquals(original.results, cached.results)
        assertEquals(0L, cached.responseBytes)
        assertEquals(2, transport.executions.get())
    }

    @Test fun smallerBudgetRejectsCachedPageWithoutIoOrDestroyingExistingEntry() {
        val transport = ScriptedTransport({ reply(it) })
        val provider = provider(transport)
        val first = provider.search(request, operation())
        fails(CommonsError.RESPONSE_TOO_LARGE) {
            provider.search(request, operation(maxBytes = first.responseBytes - 1))
        }
        val allowed = provider.search(request, operation(maxBytes = first.responseBytes))
        assertTrue(allowed.fromCache)
        assertEquals(first.results, allowed.results)
        assertEquals(1, transport.executions.get())
    }

    @Test fun noStoreResponseDoesNotHideFailureOfFollowingRequest() {
        val transport = ScriptedTransport({ reply(it, cacheControl = "no-store") }, { throw IOException("private details") },
            { reply(it) })
        val provider = provider(transport)
        assertFalse(provider.search(request, operation()).fromCache)
        fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation()) }
        assertFreshThenCached(provider, transport, expectedExchanges = 3)
    }

    @Test fun slowReadAtDeadlineCannotPublishOrCacheAndNextOperationGetsItsOwnBudget() {
        val clock = TestClock()
        val body = object : TrackedBody(fixture()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                clock.now = 1_500
                return count
            }
        }
        val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
        val timer = RecordingTimer()
        val provider = CommonsMetadataProvider(config(), transport, clock = clock, timer = timer)
        fails(CommonsError.DEADLINE_EXCEEDED) { provider.search(request, operation(deadline = 1_500)) }
        assertEquals(500, transport.requests.single().connectTimeoutMillis)
        assertEquals(1_500L, transport.requests.single().deadlineEpochMillis)
        assertEquals(listOf(500L), timer.delays)
        assertEquals(1, body.closes)
        assertEquals(1, transport.cancellations.get())
        assertFalse(provider.search(request, operation(deadline = clock.now + 15_000)).fromCache)
        assertEquals(listOf(500L, 15_000L), timer.delays)
        assertTrue(timer.cancelled.all { it })
        assertEquals(2, transport.executions.get())
    }

    @Test fun cancellationDuringBodyReadRejectsReturnedBytesAndAllowsExplicitRecovery() {
        val op = operation()
        val body = object : TrackedBody(fixture()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                op.cancellation.cancel()
                return count
            }
        }
        val transport = ScriptedTransport({ reply(it, body) }, { reply(it) })
        val provider = provider(transport)
        fails(CommonsError.CANCELLED) { provider.search(request, op) }
        op.cancellation.cancel() // A duplicate signal must not start a second cleanup/exchange.
        assertEquals(1, body.closes)
        assertEquals(1, transport.cancellations.get())
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun deadlineSignalWinsOverIoFailureAndReleasesCallWithoutAnInternalRetry() {
        val timer = TestTimer()
        val transport = ScriptedTransport({
            timer.fire()
            throw SocketTimeoutException("private endpoint")
        }, { reply(it) })
        val provider = provider(transport, timer = timer)
        fails(CommonsError.DEADLINE_EXCEEDED) { provider.search(request, operation()) }
        assertEquals(1, transport.executions.get())
        assertEquals(1, transport.cancellations.get())
        assertFreshThenCached(provider, transport, expectedExchanges = 2)
    }

    @Test fun serverCooldownRejectsEarlyRetryWithoutIoAndAllowsRequestAtExactBoundary() {
        for (status in listOf(429, 503)) {
            for ((header, delay) in listOf("2" to 2_000L, "0" to 1_000L, "not-a-date" to 30_000L)) {
                val clock = TestClock()
                val transport = ScriptedTransport({ http -> CommonsHttpResponse(status, http.url,
                    mapOf("Retry-After" to listOf(header)), ByteArrayInputStream(byteArrayOf())) }, { reply(it) })
                val provider = provider(transport, clock)
                val retryAt = clock.now + delay
                assertEquals(retryAt, fails(CommonsError.RATE_LIMITED) {
                    provider.search(request, operation(deadline = clock.now + 15_000))
                }.retryAtEpochMillis)
                clock.now = retryAt - 1
                assertEquals(retryAt, fails(CommonsError.RATE_LIMITED) {
                    provider.search(request, operation(deadline = clock.now + 15_000))
                }.retryAtEpochMillis)
                assertEquals(1, transport.executions.get())
                clock.now = retryAt
                assertFalse(provider.search(request, operation(deadline = clock.now + 15_000)).fromCache)
                assertEquals(2, transport.executions.get())
            }
        }
    }

    private fun assertFreshThenCached(provider: CommonsMetadataProvider, transport: ScriptedTransport, expectedExchanges: Int) {
        val fresh = provider.search(request, operation())
        assertFalse(fresh.fromCache)
        assertEquals(40108642L, fresh.results.single().pageId)
        assertEquals(fixture().size.toLong(), fresh.responseBytes)
        val cached = provider.search(request, operation())
        assertTrue(cached.fromCache)
        assertEquals(fresh.results, cached.results)
        assertEquals(expectedExchanges, transport.requests.size)
        assertEquals(expectedExchanges, transport.executions.get())
        assertEquals(expectedExchanges, transport.cancellations.get())
    }

    private open class TrackedBody(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
        var closes = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        override fun close() { closes++; super.close() }
    }

    private class ScriptedTransport(vararg replies: (CommonsHttpRequest) -> CommonsHttpResponse) : CommonsTransport {
        private val replies = ArrayDeque(replies.toList())
        val requests = mutableListOf<CommonsHttpRequest>()
        val executions = AtomicInteger()
        val cancellations = AtomicInteger()
        override fun create(request: CommonsHttpRequest): CommonsHttpCall {
            requests += request
            val reply = replies.removeFirst()
            return object : CommonsHttpCall {
                override fun execute(): CommonsHttpResponse { executions.incrementAndGet(); return reply(request) }
                override fun cancel() { cancellations.incrementAndGet() }
            }
        }
    }

    private class RecordingTimer : DiscoveryTimer {
        val delays = mutableListOf<Long>()
        val cancelled = mutableListOf<Boolean>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            val index = delays.size
            delays += delayMillis; cancelled += false
            return CancellationHandle { cancelled[index] = true }
        }
    }

    private fun reply(http: CommonsHttpRequest, body: InputStream = ByteArrayInputStream(fixture()),
        contentLength: Long? = null, cacheControl: String? = null): CommonsHttpResponse {
        val headers = linkedMapOf("Content-Type" to listOf("application/json"))
        contentLength?.let { headers["Content-Length"] = listOf(it.toString()) }
        cacheControl?.let { headers["Cache-Control"] = listOf(it) }
        return CommonsHttpResponse(200, http.url, headers, body)
    }
}
