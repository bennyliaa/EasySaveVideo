package com.easysavevideo.app.discovery.network

import com.easysavevideo.searxng.*
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

/** Pure admission and in-memory resolver fixtures; no socket can be opened by these tests. */
class SearxngOriginAdmissionTest {
    @Test fun sameServiceDifferentSlotsCaseAndProtocolShareOnePhysicalLease() {
        val admission = SearxngOriginAdmission()
        val first = admission.acquire("SEARCH.example.org", 1000)
        try {
            for (slot in listOf("searxng-1", "searxng-2", "searxng-3")) for (format in SearxngResultFormat.values()) {
                val endpoint = SearxngEndpoint(slot, "https://search.example.org/search", format)
                fails(SearxngError.BUSY) { admission.acquire(endpoint.uri.host, 1000) }
            }
            admission.acquire("other.example.org", 1000).release()
        } finally { first.release() }
        admission.acquire("search.example.org", 1000).release()
    }

    @Test fun cooldownSurvivesReleaseAndSlotEditsAndOnlyMovesForward() {
        val admission = SearxngOriginAdmission()
        val first = admission.acquire("search.example.org", 1000)
        first.slowdown(9000); first.slowdown(4000)
        val during = fails(SearxngError.RATE_LIMITED) { admission.acquire("search.example.org", 2000) }
        assertEquals(9000L, during.retryAtEpochMillis)
        first.release()
        for (slot in listOf("searxng-1", "searxng-3")) {
            val edited = SearxngEndpoint(slot, "https://search.example.org/search", SearxngResultFormat.HTML_WEB)
            assertEquals(9000L, fails(SearxngError.RATE_LIMITED) { admission.acquire(edited.uri.host, 8999) }.retryAtEpochMillis)
        }
        admission.acquire("search.example.org", 9000).release()
    }

    @Test fun boundedTableNeverEvictsActiveOrUnexpiredCooldowns() {
        val admission = SearxngOriginAdmission(maximumOrigins = 2)
        val first = admission.acquire("one.example.org", 1000)
        val second = admission.acquire("two.example.org", 1000)
        second.slowdown(8000); second.release()
        fails(SearxngError.BUSY) { admission.acquire("three.example.org", 1001) }
        first.release()
        val third = admission.acquire("three.example.org", 1001)
        fails(SearxngError.RATE_LIMITED) { admission.acquire("two.example.org", 2000) }
        fails(SearxngError.BUSY) { admission.acquire("four.example.org", 2000) }
        third.release()
        admission.acquire("four.example.org", 8000).release()
    }

    @Test fun releasedLeaseCannotChangeOrReleaseANewerOwner() {
        val admission = SearxngOriginAdmission()
        val old = admission.acquire("search.example.org", 1000); old.release()
        val current = admission.acquire("search.example.org", 1000)
        old.slowdown(Long.MAX_VALUE); old.release()
        fails(SearxngError.BUSY) { admission.acquire("search.example.org", 1000) }
        current.release(); admission.acquire("search.example.org", 1000).release()
    }

    @Test fun publicTransportInstancesActuallySelectSharedProcessAdmission() {
        val field = SystemSearxngTransport::class.java.getDeclaredField("originAdmission").apply { isAccessible = true }
        val first = SystemSearxngTransport(); val second = SystemSearxngTransport()
        assertSame(ProcessSearxngOriginAdmission.shared, field.get(first))
        assertSame(field.get(first), field.get(second))
    }

    @Test(timeout = 10000) fun cancelledResolverKeepsSameOriginBusyAcrossTransportReplacementUntilItExits() {
        val admission = SearxngOriginAdmission(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val secondDns = AtomicInteger(); val opened = AtomicInteger()
        val sockets = object : SearxngSockets {
            override fun open(): Socket { opened.incrementAndGet(); error("No socket is authorized in this fixture") }
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket = error("No TLS in this fixture")
        }
        val clock = object : SearxngTransportClock { override fun epochMillis() = 1000L; override fun nanoTime() = 0L }
        val timer = SearxngTransportTimer { _, _ -> AutoCloseable {} }
        val first = SystemSearxngTransport(SearxngDnsResolver {
            entered.countDown(); awaitUninterruptibly(release); throw UnknownHostException("ESV_SYNTHETIC")
        }, sockets, clock, timer, admission)
        val replacement = SystemSearxngTransport(SearxngDnsResolver { secondDns.incrementAndGet(); emptyArray<InetAddress>() },
            sockets, clock, timer, admission)
        val call = first.create(request("searxng-1", SearxngResultFormat.JSON_VIDEOS))
        val outcome = AtomicReference<Throwable?>(); val finished = CountDownLatch(1)
        val worker = Thread { try { call.execute() } catch (failure: Throwable) { outcome.set(failure) } finally { finished.countDown() } }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); call.cancel()
            assertTrue(finished.await(2, TimeUnit.SECONDS)); assertEquals(SearxngError.CANCELLED, (outcome.get() as? SearxngFailure)?.code)
            fails(SearxngError.BUSY) { replacement.create(request("searxng-3", SearxngResultFormat.HTML_WEB)).execute() }
            assertEquals(0, secondDns.get()); assertEquals(0, opened.get())
            release.countDown(); awaitAdmission(admission)
            fails(SearxngError.UNSAFE_URL) { replacement.create(request("searxng-3", SearxngResultFormat.HTML_WEB)).execute() }
            assertEquals(1, secondDns.get()); assertEquals(0, opened.get())
        } finally { release.countDown(); worker.join(2000) }
    }

    private fun request(id: String, format: SearxngResultFormat): SearxngHttpRequest {
        val endpoint = SearxngEndpoint(id, "https://search.example.org/search", format)
        val kind = if (format == SearxngResultFormat.JSON_VIDEOS) SearxngRequestKind.JSON_SEARCH else SearxngRequestKind.ROBOTS
        val uri = if (kind == SearxngRequestKind.ROBOTS) URI("https://search.example.org/robots.txt")
            else URI("https://search.example.org/search?q=fixture&format=json&categories=videos&safesearch=0")
        return SearxngHttpRequest(endpoint, uri, mapOf("Host" to endpoint.uri.host, "Connection" to "close",
            "User-Agent" to SearxngIdentity.USER_AGENT, "Accept" to kind.accept, "Accept-Encoding" to "identity"),
            16000, 5000, 32768, kind)
    }
    private fun fails(code: SearxngError, action: () -> Unit): SearxngFailure {
        try { action(); fail("Expected $code") } catch (failure: SearxngFailure) { assertEquals(code, failure.code); return failure }
        throw AssertionError("Unreachable")
    }
    private fun awaitAdmission(admission: SearxngOriginAdmission) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try { admission.acquire("search.example.org", 1000).release(); return }
            catch (failure: SearxngFailure) { assertEquals(SearxngError.BUSY, failure.code) }
            if (System.nanoTime() >= until) fail("Cancelled physical owner did not release")
            Thread.yield()
        }
    }
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
