package com.easysavevideo.app.discovery.network

import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngProviderSlots
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import com.easysavevideo.discovery.VideoSearchProvider
import com.easysavevideo.searxng.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** Actual dynamic provider rebuilds, with synthetic DNS/TLS only. No real network is used. */
class SearxngDynamicTransportOwnershipTest {
    @Test fun sixteenConfiguredInstancesShareOnlyThreeStablePhysicalTransports() {
        val clock = Clock(); val admission = SearxngOriginAdmission(); val dnsCalls = AtomicInteger()
        assertEquals(3, SearxngProviderSlots.PHYSICAL_CAPACITY)
        val physical = List(SearxngProviderSlots.PHYSICAL_CAPACITY) {
            transport(clock, admission) { dnsCalls.incrementAndGet(); emptyArray() }
        }
        val slots = SearxngProviderSlots(physical)
        var config = SearxngConfigurationPolicy.empty()
        repeat(16) { index ->
            config = accepted(SearxngConfigurationPolicy.add(config, "https://instance$index.example.org/search", true, true,
                if (index % 2 == 0) SearxngResultFormat.JSON_VIDEOS else SearxngResultFormat.HTML_WEB))
        }
        val providers = slots.providers(config)
        assertEquals(16, providers.size)
        assertEquals(3, providers.map(::selected).distinct().size)
        providers.forEachIndexed { index, provider -> assertSame(physical[index % 3], selected(provider)) }
        val unchanged = slots.providers(config)
        unchanged.forEachIndexed { index, provider -> assertSame(selected(providers[index]), selected(provider)) }
        val disabled = accepted(SearxngConfigurationPolicy.replace(config, "searxng-1",
            config.slots.first().searchUrl!!, false, false))
        slots.providers(disabled).forEach { provider ->
            val configuredIndex = disabled.slots.indexOfFirst { it.id == provider.descriptor.id }
            assertSame(physical[configuredIndex % 3], selected(provider))
        }
        assertEquals(0, dnsCalls.get())
    }

    @Test(timeout = 10000) fun deletingCancelledInstanceCannotReuseItsPhysicalIndexBeforeResolverExits() {
        val clock = Clock(); val admission = SearxngOriginAdmission()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val firstDns = AtomicInteger(); val otherDns = AtomicInteger()
        val first = transport(clock, admission) {
            if (firstDns.incrementAndGet() == 1) { entered.countDown(); awaitUninterruptibly(release) }
            emptyArray()
        }
        val others = List(SearxngProviderSlots.PHYSICAL_CAPACITY - 1) {
            transport(clock, admission) { otherDns.incrementAndGet(); emptyArray() }
        }
        val slots = SearxngProviderSlots(listOf(first) + others)
        val original = initial()
        val providers = slots.providers(original)
        assertSame(first, selected(providers[0])); assertSame(others[0], selected(providers[1]))
        val call = first.create(request(original, "searxng-1", clock))
        val running = Async(call)
        try {
            await(entered)
            val disabled = accepted(SearxngConfigurationPolicy.replace(original, "searxng-1", FIRST, false, false))
            assertSame("Disabling a row must not compact enabled transport indices", others[0], selected(slots.providers(disabled).single()))
            call.cancel(); running.failure(SearxngError.CANCELLED)
            val deleted = accepted(SearxngConfigurationPolicy.remove(disabled, "searxng-1"))
            val replacement = selected(slots.providers(deleted).single())
            assertSame(first, replacement)
            fails(SearxngError.BUSY) { replacement.create(request(deleted, "searxng-2", clock)).execute() }
            assertEquals(1, firstDns.get()); assertEquals(0, otherDns.get())
            release.countDown(); awaitReleased(first)
            fails(SearxngError.UNSAFE_URL) { replacement.create(request(deleted, "searxng-2", clock)).execute() }
            assertEquals(2, firstDns.get()); assertEquals(0, otherDns.get())
        } finally { release.countDown(); running.join() }
    }

    @Test(timeout = 10000) fun readdingDeletedOriginAtAnotherIndexCannotBypassCancelledPhysicalOwner() {
        val clock = Clock(); val admission = SearxngOriginAdmission()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val replacementDns = AtomicInteger()
        val first = transport(clock, admission) { entered.countDown(); awaitUninterruptibly(release); emptyArray() }
        val second = transport(clock, admission) { replacementDns.incrementAndGet(); emptyArray() }
        val slots = pool(first, second, clock, admission)
        val original = initial()
        val call = selected(slots.providers(original).first()).create(request(original, "searxng-1", clock))
        val running = Async(call)
        try {
            await(entered); call.cancel(); running.failure(SearxngError.CANCELLED)
            val rebuilt = readded(original)
            assertEquals(listOf("searxng-2", "searxng-3"), rebuilt.slots.map { it.id })
            assertNotEquals(original.generation, rebuilt.generation)
            val replacement = selected(slots.providers(rebuilt).last())
            assertSame(second, replacement)
            fails(SearxngError.BUSY) { replacement.create(request(rebuilt, "searxng-3", clock)).execute() }
            assertEquals(0, replacementDns.get())
            release.countDown(); awaitReleased(first)
            fails(SearxngError.UNSAFE_URL) { replacement.create(request(rebuilt, "searxng-3", clock)).execute() }
            assertEquals(1, replacementDns.get())
        } finally { release.countDown(); running.join() }
    }

    @Test(timeout = 10000) fun readdingOriginPreservesSlowdownAcrossStuckCleanupAndLateCancellation() {
        val clock = Clock(); val admission = SearxngOriginAdmission(); val wire = SlowdownWire()
        val replacementDns = AtomicInteger()
        val first = SystemSearxngTransport(SearxngDnsResolver { arrayOf(PUBLIC) }, wire, clock, TIMER, admission)
        val second = transport(clock, admission) { replacementDns.incrementAndGet(); emptyArray() }
        val slots = pool(first, second, clock, admission)
        val original = initial()
        val call = selected(slots.providers(original).first()).create(request(original, "searxng-1", clock))
        try {
            val response = call.execute(); assertEquals(429, response.status); response.close()
            await(wire.closeEntered)
            val rebuilt = readded(original)
            val replacement = selected(slots.providers(rebuilt).last())
            assertSame(second, replacement)
            assertEquals(9000L, fails(SearxngError.RATE_LIMITED) {
                replacement.create(request(rebuilt, "searxng-3", clock)).execute()
            }.retryAtEpochMillis)
            fails(SearxngError.BUSY) {
                selected(slots.providers(rebuilt).first()).create(request(rebuilt, "searxng-2", clock)).execute()
            }
            assertEquals(0, replacementDns.get()); assertEquals(1, wire.opened.get())
            wire.releaseClose.countDown(); awaitReleased(first)
            call.cancel(); call.cancel() // A late old-owner callback cannot erase the origin cooldown.
            clock.epoch = 8999
            assertEquals(9000L, fails(SearxngError.RATE_LIMITED) {
                replacement.create(request(rebuilt, "searxng-3", clock)).execute()
            }.retryAtEpochMillis)
            assertEquals(0, replacementDns.get())
            clock.epoch = 9000
            fails(SearxngError.UNSAFE_URL) { replacement.create(request(rebuilt, "searxng-3", clock)).execute() }
            assertEquals(1, replacementDns.get()); assertEquals(1, wire.opened.get())
        } finally { wire.releaseClose.countDown() }
    }

    private class Clock : SearxngTransportClock {
        @Volatile var epoch = 1000L
        override fun epochMillis() = epoch
        override fun nanoTime() = 0L
    }
    private class Async(call: SearxngHttpCall) {
        private val result = AtomicReference<Throwable?>()
        private val finished = CountDownLatch(1)
        private val thread = Thread {
            try { call.execute() } catch (failure: Throwable) { result.set(failure) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        fun failure(code: SearxngError) { await(finished); assertEquals(code, (result.get() as? SearxngFailure)?.code) }
        fun join() { thread.join(2000); assertFalse(thread.isAlive) }
    }

    /** A complete in-memory exchange whose raw close can remain physically outstanding. */
    private class SlowdownWire : SearxngSockets {
        val closeEntered = CountDownLatch(1); val releaseClose = CountDownLatch(1); val opened = AtomicInteger()
        private val raw = object : Socket() {
            private var connected = false
            override fun connect(endpoint: SocketAddress, timeout: Int) { connected = true }
            override fun isConnected() = connected
            override fun getInetAddress(): InetAddress = PUBLIC
            override fun getPort() = 443
            override fun setSoLinger(on: Boolean, linger: Int) { assertFalse(on) }
            override fun close() { closeEntered.countDown(); awaitUninterruptibly(releaseClose) }
        }
        private val tls = object : SSLSocket() {
            private var parameters = SSLParameters()
            private val input = ByteArrayInputStream("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 8\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            private val output = ByteArrayOutputStream()
            override fun getSSLParameters() = parameters
            override fun setSSLParameters(value: SSLParameters) { parameters = value }
            override fun getApplicationProtocol() = "http/1.1"
            override fun getInetAddress(): InetAddress = PUBLIC
            override fun getPort() = 443
            override fun isConnected() = true
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun setSoTimeout(timeout: Int) {}
            override fun startHandshake() {}
            override fun close() {}
            override fun setUseClientMode(mode: Boolean) {}
            override fun getUseClientMode() = true
            override fun getSupportedCipherSuites(): Array<String> = emptyArray()
            override fun getEnabledCipherSuites(): Array<String> = emptyArray()
            override fun setEnabledCipherSuites(suites: Array<out String>?) {}
            override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3", "TLSv1.2")
            override fun getEnabledProtocols() = supportedProtocols
            override fun setEnabledProtocols(protocols: Array<out String>?) {}
            override fun getSession(): SSLSession = throw UnsupportedOperationException()
            override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
            override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
            override fun setNeedClientAuth(need: Boolean) {}
            override fun getNeedClientAuth() = false
            override fun setWantClientAuth(want: Boolean) {}
            override fun getWantClientAuth() = false
            override fun setEnableSessionCreation(flag: Boolean) {}
            override fun getEnableSessionCreation() = true
        }
        override fun open(): Socket { opened.incrementAndGet(); return raw }
        override fun secure(raw: Socket, host: String, port: Int): SSLSocket = tls
    }

    companion object {
        private const val FIRST = "https://search.example.org/search"
        private const val SECOND = "https://other.example.org/search"
        private val PUBLIC = InetAddress.getByAddress(byteArrayOf(91, -58, -82, -64))
        private val TIMER = SearxngTransportTimer { _, _ -> AutoCloseable {} }
        private val NO_SOCKETS = object : SearxngSockets {
            override fun open(): Socket = error("No socket is authorized in the DNS fixture")
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket = error("No TLS is authorized in the DNS fixture")
        }
        private fun transport(clock: Clock, admission: SearxngOriginAdmission, resolve: (String) -> Array<InetAddress>) =
            SystemSearxngTransport(SearxngDnsResolver(resolve), NO_SOCKETS, clock, TIMER, admission)
        private fun pool(first: SystemSearxngTransport, second: SystemSearxngTransport,
            clock: Clock, admission: SearxngOriginAdmission) = SearxngProviderSlots(listOf(first, second) +
                List(SearxngProviderSlots.PHYSICAL_CAPACITY - 2) { transport(clock, admission) { emptyArray() } })
        private fun accepted(edit: SearxngConfigurationEdit) = (edit as SearxngConfigurationEdit.Accepted).config
        private fun initial(): SearxngSourceConfiguration {
            val first = accepted(SearxngConfigurationPolicy.add(SearxngConfigurationPolicy.empty(), FIRST, true, true))
            return accepted(SearxngConfigurationPolicy.add(first, SECOND, true, true))
        }
        private fun readded(original: SearxngSourceConfiguration): SearxngSourceConfiguration {
            val deleted = accepted(SearxngConfigurationPolicy.remove(original, "searxng-1"))
            return accepted(SearxngConfigurationPolicy.add(deleted, FIRST, true, true, SearxngResultFormat.HTML_WEB))
        }
        private fun selected(provider: VideoSearchProvider): SystemSearxngTransport {
            val field = provider.javaClass.getDeclaredField("transport").apply { isAccessible = true }
            return field.get(provider) as SystemSearxngTransport
        }
        private fun request(config: SearxngSourceConfiguration, id: String, clock: Clock): SearxngHttpRequest {
            val endpoint = config.slots.single { it.id == id }.endpoint!!
            val kind = if (endpoint.format == SearxngResultFormat.JSON_VIDEOS) SearxngRequestKind.JSON_SEARCH else SearxngRequestKind.ROBOTS
            val url = if (kind == SearxngRequestKind.ROBOTS) URI("https://${endpoint.uri.host}/robots.txt")
                else URI("${endpoint.searchUrl}?q=fixture&format=json&categories=videos&safesearch=0")
            return SearxngHttpRequest(endpoint, url, mapOf("Host" to endpoint.uri.host, "Connection" to "close",
                "User-Agent" to SearxngIdentity.USER_AGENT, "Accept" to kind.accept, "Accept-Encoding" to "identity"),
                clock.epoch + 15000, 5000, 32768, kind)
        }
        private fun fails(code: SearxngError, action: () -> Unit): SearxngFailure {
            try { action(); fail("Expected $code") }
            catch (failure: SearxngFailure) { assertEquals(code, failure.code); return failure }
            throw AssertionError("Unreachable")
        }
        private fun await(latch: CountDownLatch) { assertTrue("Operation did not reach expected state", latch.await(2, TimeUnit.SECONDS)) }
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            var interrupted = false
            while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
        private fun awaitReleased(transport: SystemSearxngTransport) {
            val field = SystemSearxngTransport::class.java.getDeclaredField("active").apply { isAccessible = true }
            val active = field.get(transport) as AtomicReference<*>
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (active.get() != null && System.nanoTime() < until) Thread.yield()
            assertNull("Physical slot was not released", active.get())
        }
    }
}
