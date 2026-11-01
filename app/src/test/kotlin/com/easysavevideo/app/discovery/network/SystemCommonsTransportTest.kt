package com.easysavevideo.app.discovery.network

import com.easysavevideo.commons.CommonsClientConfig
import com.easysavevideo.commons.CommonsMetadataProvider
import com.easysavevideo.commons.CommonsSearchRequest
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.discovery.*
import com.easysavevideo.commons.CommonsError
import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.CommonsHttpCall
import com.easysavevideo.commons.CommonsHttpRequest
import com.easysavevideo.commons.CommonsHttpResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

class SystemCommonsTransportTest {
    @Test fun anonymousExactRequestPinsPeerAndConfiguresHostnameAndSni() {
        val wire = Fixture()
        val transport = wire.transport()
        val call = transport.create(request())
        assertEquals(0, wire.dnsCalls.get())
        assertEquals(0, wire.openCalls.get())
        val response = call.execute()
        assertEquals("{}", response.body.readBytes().toString(Charsets.UTF_8))
        assertEquals(200, response.status)
        assertEquals(PUBLIC, (wire.raw.destination as InetSocketAddress).address)
        assertFalse((wire.raw.destination as InetSocketAddress).isUnresolved)
        assertEquals(443, (wire.raw.destination as InetSocketAddress).port)
        assertTrue(wire.raw.timeout in 1..5_000)
        assertEquals(HOST, wire.secureHost)
        assertEquals("HTTPS", wire.tls.parameters.endpointIdentificationAlgorithm)
        assertEquals(HOST, (wire.tls.parameters.serverNames.single() as SNIHostName).asciiName)
        assertArrayEquals(arrayOf("http/1.1"), wire.tls.parameters.applicationProtocols)
        assertTrue(wire.tls.clientMode)
        assertEquals("GET /w/api.php?action=query HTTP/1.1\r\n" +
            "User-Agent: EasySaveVideo/0.1 (https://example.org/contact)\r\n" +
            "Accept: application/json\r\nAccept-Encoding: identity\r\nHost: $HOST\r\nConnection: close\r\n\r\n",
            wire.output.toString("US-ASCII"))
        assertEquals(1, wire.dnsCalls.get())
        assertEquals(1, wire.openCalls.get())
        assertTrue(wire.raw.closed.count == 0L)
        assertTrue(wire.tls.closed)
    }

    @Test fun ambientCookieAuthenticatorAndProxyAreNeverConsultedOrChanged() {
        val originalCookies = CookieHandler.getDefault()
        val originalAuth = currentAuthenticator()
        val originalProxy = ProxySelector.getDefault()
        val consulted = AtomicInteger()
        val cookies = object : CookieHandler() {
            override fun get(uri: URI, headers: MutableMap<String, MutableList<String>>): MutableMap<String, MutableList<String>> {
                consulted.incrementAndGet(); return mutableMapOf("Cookie" to mutableListOf("secret"))
            }
            override fun put(uri: URI, headers: MutableMap<String, MutableList<String>>) { consulted.incrementAndGet() }
        }
        val authenticator = object : Authenticator() { override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
            consulted.incrementAndGet(); return null
        } }
        val proxy = object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> { consulted.incrementAndGet(); return mutableListOf(Proxy.NO_PROXY) }
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) { consulted.incrementAndGet() }
        }
        try {
            CookieHandler.setDefault(cookies); Authenticator.setDefault(authenticator); ProxySelector.setDefault(proxy)
            Fixture().transport().create(request()).execute().close()
            assertEquals(0, consulted.get())
            assertSame(cookies, CookieHandler.getDefault())
            assertSame(authenticator, currentAuthenticator())
            assertSame(proxy, ProxySelector.getDefault())
        } finally {
            CookieHandler.setDefault(originalCookies); Authenticator.setDefault(originalAuth); ProxySelector.setDefault(originalProxy)
        }
    }

    @Test fun checksEveryDnsAnswerBeforeOpeningEvenIfFirstAnswerIsPublic() {
        val wire = Fixture()
        val transport = wire.transport(CommonsDnsResolver { arrayOf(PUBLIC, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) })
        expect(CommonsError.UNSAFE_URL) { transport.create(request()).execute() }
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun emptyDnsAndChangedPublicOrPrivatePeerCannotReachTls() {
        val empty = Fixture()
        expect(CommonsError.UNSAFE_URL) { empty.transport(CommonsDnsResolver { emptyArray() }).create(request()).execute() }
        for (peer in listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), InetAddress.getLoopbackAddress())) {
            val wire = Fixture(); wire.raw.reportedPeer = peer
            expect(CommonsError.UNSAFE_URL) { wire.transport().create(request()).execute() }
            assertEquals(0, wire.secureCalls.get())
            assertEquals(0, wire.output.size())
        }
    }

    @Test fun tlsPeerMismatchAndCertificateOrHostnameFailureTransmitNoHttp() {
        val mismatch = Fixture(); mismatch.tls.reportedPeer = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        expect(CommonsError.UNSAFE_URL) { mismatch.transport().create(request()).execute() }
        assertEquals(0, mismatch.output.size())
        val rejected = Fixture(); rejected.tls.handshakeFailure = SSLHandshakeException("test")
        expect(CommonsError.SERVICE_ERROR) { rejected.transport().create(request()).execute() }
        assertEquals(0, rejected.output.size())
        assertEquals("HTTPS", rejected.tls.parameters.endpointIdentificationAlgorithm)
        assertEquals(1, rejected.openCalls.get())
    }

    @Test fun allRedirectsAreRejectedWithNoSecondDnsOrSocket() {
        for (status in listOf(300, 301, 302, 303, 307, 308, 399)) {
            val wire = Fixture("HTTP/1.1 $status Redirect\r\nLocation: https://elsewhere.example/\r\nContent-Length: 0\r\n\r\n")
            expect(CommonsError.REDIRECT_REJECTED) { wire.transport().create(request()).execute() }
            assertEquals(1, wire.openCalls.get()); assertEquals(1, wire.dnsCalls.get())
        }
    }

    @Test fun rateLimitAndDenialHeadersSurviveUnreadableCompressedOversizedErrorBodies() {
        for (status in listOf(401, 403, 429, 503)) {
            for (extra in listOf("", "Content-Encoding: gzip\r\n", "Content-Length: 999999999999999999999\r\n",
                "Transfer-Encoding: chunked\r\n")) {
                val wire = Fixture()
                val prefix = ByteArrayInputStream(("HTTP/1.1 $status Error\r\nRetry-After: 3600\r\n" + extra + "\r\n").toByteArray())
                wire.input = object : InputStream() {
                    override fun read(): Int {
                        val next = prefix.read()
                        if (next < 0) throw IOException("Error body must not be consumed")
                        return next
                    }
                }
                val response = wire.transport().create(request()).execute()
                assertEquals(status, response.status)
                assertEquals(listOf("3600"), response.headers["retry-after"])
                assertEquals(0, response.body.readBytes().size)
                await(wire.raw.closed)
                assertEquals(1, wire.openCalls.get())
            }
        }
    }

    @Test fun rateLimitReachesProviderBeforeStuckCloseAndSurvivesDeadlineAndPhysicalRelease() {
        for (status in listOf(429, 503)) {
            val wire = Fixture("HTTP/1.1 $status Slow Down\r\nRetry-After: 3600\r\nContent-Encoding: gzip\r\n\r\n")
            val closeRelease = CountDownLatch(1); wire.raw.closeRelease = closeRelease
            val timer = ManualTimer(); val transport = wire.transport(timer = timer)
            val provider = CommonsMetadataProvider(
                CommonsClientConfig(true, "test", "https://example.org/contact"), transport,
                clock = EpochClock { wire.clock.epoch }, timer = object : DiscoveryTimer {
                    override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {}
                })
            val search = CommonsSearchRequest("test", safeSearchRequired = false)
            fun operation(): DiscoveryOperation {
                val cancellation = DiscoveryCancellation()
                return DiscoveryOperation(OperationContext("test", wire.clock.epoch + 15_000, cancellation,
                    resourceBudget = ResourceBudget(1, 2_097_152)), cancellation)
            }
            val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
            Thread({ try { provider.search(search, operation()) } catch (problem: Throwable) { failure.set(problem) }
                finally { done.countDown() } }).apply { isDaemon = true; start() }
            try {
                await(wire.raw.closeEntered)
                await(done)
                val slowed = failure.get() as? CommonsFailure
                assertEquals(CommonsError.RATE_LIMITED, slowed?.code)
                assertEquals(3_601_000L, slowed?.retryAtEpochMillis)
                expect(CommonsError.BUSY) { transport.create(request()).execute() }
                wire.clock.epoch = 17_000; wire.clock.nanos += 16_000_000_000
                timer.entries[0].action() // Simulate a timeout callback already racing cancellation.
                closeRelease.countDown(); awaitReleased(transport)
                expect(CommonsError.RATE_LIMITED) { provider.search(search, operation()) }
                assertEquals(1, wire.openCalls.get())
                assertEquals(1, wire.dnsCalls.get())
            } finally { closeRelease.countDown() }
        }
    }

    @Test fun transportCooldownSurvivesCallerDiscardingHeadersCancellationAndLatePhysicalClose() {
        val wire = Fixture("HTTP/1.1 429 Slow Down\r\nRetry-After: 3600\r\n\r\n")
        val closeRelease = CountDownLatch(1); wire.raw.closeRelease = closeRelease
        val timer = ManualTimer(); val transport = wire.transport(timer = timer)
        val call = transport.create(request())
        try {
            // Deliberately discard the response: the provider never observes these headers.
            call.execute()
            await(wire.raw.closeEntered)
            call.cancel()
            wire.clock.epoch = 17_000; wire.clock.nanos += 16_000_000_000
            timer.entries[0].action() // Even a late callback cannot erase the observed cooldown.
            closeRelease.countDown(); awaitReleased(transport)
            try {
                transport.create(request(deadline = 32_000)).execute()
                fail("Transport must retain server cooldown without provider cooperation")
            } catch (failure: CommonsFailure) {
                assertEquals(CommonsError.RATE_LIMITED, failure.code)
                assertEquals(3_601_000L, failure.retryAtEpochMillis)
            }
            assertEquals(1, wire.openCalls.get())
            assertEquals(1, wire.dnsCalls.get())
        } finally { closeRelease.countDown() }
    }

    @Test fun refusesCredentialExtraOrInjectedHeadersAndSnapshotsSuppliedMap() {
        val bad = listOf(headers() + ("Cookie" to "secret"), headers() + ("Authorization" to "Basic secret"),
            headers() + ("User-Agent" to "EasySaveVideo/test\r\nCookie: x"), headers() + ("Accept-Encoding" to "gzip"),
            headers() + ("Host" to "example.org"), headers() - "Connection")
        for (values in bad) {
            val wire = Fixture()
            expect(CommonsError.INVALID_REQUEST) { wire.transport().create(request(headers = values)) }
            assertEquals(0, wire.openCalls.get())
        }
        val values = headers().toMutableMap()
        val wire = Fixture(); val call = wire.transport().create(request(headers = values))
        values["Cookie"] = "secret"; values["User-Agent"] = "changed"
        call.execute().close()
        assertFalse(wire.output.toString("US-ASCII").contains("Cookie"))
        assertTrue(wire.output.toString("US-ASCII").contains("EasySaveVideo/0.1"))
    }

    @Test fun endpointAndBudgetValidationDoesNoIo() {
        val urls = listOf("http://commons.wikimedia.org/w/api.php?a=b", "https://commons.wikimedia.org:443/w/api.php?a=b",
            "https://user@commons.wikimedia.org/w/api.php?a=b", "https://commons.wikimedia.org/w/api.php?a=b#x",
            "https://commons.wikimedia.org/w/../w/api.php?a=b", "https://example.org/w/api.php?a=b", "https://commons.wikimedia.org/w/api.php")
        for (url in urls) expect(CommonsError.UNSAFE_URL) { request(url = URI(url)) }
        val wire = Fixture(); val transport = wire.transport()
        expect(CommonsError.INVALID_REQUEST) { transport.create(request(connect = 5_001)) }
        expect(CommonsError.INVALID_REQUEST) { transport.create(request(maximum = 2_097_153)) }
        expect(CommonsError.DEADLINE_EXCEEDED) { transport.create(request(deadline = 999)).execute() }
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun cancelledBeforeExecutionAndDuplicateExecuteNeverStartAnotherExchange() {
        val wire = Fixture(); val transport = wire.transport()
        val cancelled = transport.create(request()); cancelled.cancel(); cancelled.cancel()
        expect(CommonsError.CANCELLED) { cancelled.execute() }
        assertEquals(0, wire.openCalls.get())
        val call = transport.create(request()); call.execute().close()
        expect(CommonsError.INVALID_REQUEST) { call.execute() }
        assertEquals(1, wire.openCalls.get())
    }

    @Test fun cancelledStuckDnsReturnsPromptlyAndRetainsPhysicalSlotUntilResolverExits() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val wire = Fixture()
        val transport = wire.transport(CommonsDnsResolver { entered.countDown(); release.await(); arrayOf(PUBLIC) })
        val call = transport.create(request()); val result = Async(call)
        await(entered)
        call.cancel()
        result.failure(CommonsError.CANCELLED)
        repeat(20) { expect(CommonsError.BUSY) { transport.create(request()).execute() } }
        assertEquals(0, wire.openCalls.get())
        release.countDown()
        awaitReleased(transport)
    }

    @Test fun deadlineWhileDnsStuckReturnsPromptlyAndNeverRefreshesOriginalBudget() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val timer = ManualTimer(); val wire = Fixture()
        val transport = wire.transport(CommonsDnsResolver { entered.countDown(); release.await(); arrayOf(PUBLIC) }, timer)
        val call = transport.create(request(deadline = 1_800)); val result = Async(call)
        await(entered)
        assertEquals(800L, timer.entries.first().delay)
        wire.clock.epoch = 1_800; wire.clock.nanos += 800_000_000
        timer.fire(0)
        result.failure(CommonsError.DEADLINE_EXCEEDED)
        expect(CommonsError.BUSY) { transport.create(request()).execute() }
        release.countDown()
        awaitReleased(transport)
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun cancellationClosesConnectTlsHeadersAndBodyIoWithoutWaitingOnTheirThread() {
        for (stage in Stage.entries) {
            val wire = Fixture(stage = stage)
            val transport = wire.transport()
            val call = transport.create(request()); val result = Async(call)
            await(wire.blocked)
            call.cancel(); result.failure(CommonsError.CANCELLED)
            await(wire.raw.closed)
            awaitReleased(transport)
            assertEquals(1, wire.openCalls.get())
        }
    }

    @Test fun blockedCloseKeepsSlotButDoesNotBlockCancellationOrDeadlineThread() {
        val wire = Fixture(stage = Stage.BODY); val closeRelease = CountDownLatch(1)
        wire.raw.closeRelease = closeRelease
        val transport = wire.transport()
        val call = transport.create(request()); val result = Async(call)
        await(wire.blocked)
        call.cancel(); result.failure(CommonsError.CANCELLED)
        await(wire.raw.closeEntered)
        expect(CommonsError.BUSY) { transport.create(request()).execute() }
        closeRelease.countDown(); await(wire.raw.closed)
        awaitReleased(transport)
    }

    @Test fun connectAndAbsoluteWatchdogsCloseTheActualSocket() {
        for (index in listOf(0, 1)) {
            val timer = ManualTimer(); val wire = Fixture(stage = Stage.TLS)
            val transport = wire.transport(timer = timer)
            val result = Async(transport.create(request(deadline = 20_000)))
            await(wire.blocked)
            assertEquals(15_000L, timer.entries[0].delay)
            assertEquals(5_000L, timer.entries[1].delay)
            timer.fire(index)
            result.failure(if (index == 0) CommonsError.DEADLINE_EXCEEDED else CommonsError.SERVICE_ERROR)
            await(wire.raw.closed)
            awaitReleased(transport)
        }
    }

    @Test fun deadlineIncludesElapsedDnsAndTlsAndNeverExtendsForBody() {
        val timer = ManualTimer(); val wire = Fixture()
        val transport = wire.transport(CommonsDnsResolver {
            wire.clock.epoch += 700; wire.clock.nanos += 700_000_000; arrayOf(PUBLIC)
        }, timer)
        transport.create(request(deadline = 2_000)).execute().close()
        assertEquals(1_000L, timer.entries[0].delay)
        assertEquals(300L, timer.entries[1].delay)
        assertTrue(wire.raw.timeout <= 300)
        assertTrue(wire.tls.readTimeout <= 300)
    }

    @Test fun bodyMemoryReadsStillRespectCancellationAndAbsoluteDeadline() {
        val wire = Fixture(); val call = wire.transport().create(request()); val response = call.execute()
        call.cancel()
        expect(CommonsError.CANCELLED) { response.body.read() }
        val expired = Fixture(); val response2 = expired.transport().create(request()).execute()
        expired.clock.epoch = 20_000
        expect(CommonsError.DEADLINE_EXCEEDED) { response2.body.readBytes() }
    }

    @Test fun unreachableDnsIsOneSanitizedAttemptAndExplicitNextRequestCanRecover() {
        val wire = Fixture(); val lookups = AtomicInteger(); val timer = ManualTimer()
        val transport = wire.transport(CommonsDnsResolver {
            if (lookups.incrementAndGet() == 1) throw UnknownHostException("private hostname")
            arrayOf(PUBLIC)
        }, timer)
        try { transport.create(request()).execute(); fail("Expected DNS failure") }
        catch (failure: CommonsFailure) {
            assertEquals(CommonsError.SERVICE_ERROR, failure.code)
            assertEquals("SERVICE_ERROR", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(1, lookups.get())
        assertEquals(0, wire.openCalls.get())
        assertTrue(timer.entries.all { it.cancelled })
        assertEquals("{}", transport.create(request()).execute().use { it.body.readBytes().toString(Charsets.UTF_8) })
        assertEquals(2, lookups.get())
        assertEquals(1, wire.openCalls.get())
        assertTrue(timer.entries.all { it.cancelled })
    }

    @Test fun refusedConnectionIsClosedWithoutTlsOrAutomaticRetry() {
        val wire = Fixture(); val timer = ManualTimer()
        wire.raw.connectFailure = ConnectException("private endpoint")
        val transport = wire.transport(timer = timer)
        try { transport.create(request()).execute(); fail("Expected refused connection") }
        catch (failure: CommonsFailure) {
            assertEquals(CommonsError.SERVICE_ERROR, failure.code)
            assertEquals("SERVICE_ERROR", failure.message)
            assertNull(failure.cause)
        }
        await(wire.raw.closed)
        awaitReleased(transport)
        assertEquals(1, wire.dnsCalls.get())
        assertEquals(1, wire.openCalls.get())
        assertEquals(0, wire.secureCalls.get())
        assertEquals(0, wire.output.size())
        assertTrue(timer.entries.all { it.cancelled })
    }

    @Test fun ordinaryPartialAndMalformedWireResponsesReleaseForOneExplicitFreshConnection() {
        val invalidResponses = listOf(
            "HTTP/1.1 200 OK\r", // Partial status line.
            "HTTP/1.1 200 OK\r\nContent-Type: application/json", // Partial headers.
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{", // Partial fixed body.
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n", // Partial trailer end.
            "HTTP/1.1 200 OK\n\n{}", // Invalid line framing.
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\ncontent-length: 2\r\n\r\n{}",
        )
        for (response in invalidResponses) {
            val bad = Fixture(response); val good = Fixture()
            val timer = ManualTimer(); val transport = sequenceTransport(timer, bad, good)
            expect(CommonsError.INVALID_RESPONSE) { transport.create(request()).execute() }
            assertEquals(1, bad.openCalls.get())
            assertEquals(0, good.openCalls.get())
            await(bad.raw.closed)
            assertTrue(bad.tls.closed)
            assertEquals("{}", transport.create(request()).execute().use { it.body.readBytes().toString(Charsets.UTF_8) })
            assertEquals(1, good.openCalls.get())
            assertTrue(timer.entries.all { it.cancelled })
        }
    }

    @Test fun interruptedBodyIoDoesNotPublishPartialResponseAndFreshConnectionRecovers() {
        val bad = Fixture(); val good = Fixture()
        val prefix = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{".toByteArray())
        bad.input = object : InputStream() {
            override fun read(): Int {
                val next = prefix.read()
                if (next < 0) throw IOException("private body details")
                return next
            }
        }
        val transport = sequenceTransport(ManualTimer(), bad, good)
        try { transport.create(request()).execute(); fail("Partial I/O must fail") }
        catch (failure: CommonsFailure) {
            assertEquals(CommonsError.SERVICE_ERROR, failure.code)
            assertEquals("SERVICE_ERROR", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(0, good.openCalls.get())
        assertTrue(bad.raw.closed.count == 0L)
        assertTrue(bad.tls.closed)
        assertEquals("{}", transport.create(request()).execute().use { it.body.readBytes().toString(Charsets.UTF_8) })
        assertEquals(1, bad.openCalls.get())
        assertEquals(1, good.openCalls.get())
    }

    @Test fun ordinaryDeclaredLimitRejectionDoesNotConsumeBodyAndNextConnectionRecovers() {
        val bad = Fixture(); val good = Fixture()
        val bodyReads = AtomicInteger()
        val prefix = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2097153\r\n\r\n".toByteArray())
        bad.input = object : InputStream() {
            override fun read(): Int {
                val next = prefix.read()
                if (next < 0) {
                    bodyReads.incrementAndGet()
                    throw IOException("Unexpected body read")
                }
                return next
            }
        }
        val transport = sequenceTransport(ManualTimer(), bad, good)
        expect(CommonsError.RESPONSE_TOO_LARGE) { transport.create(request()).execute() }
        assertEquals(0, bodyReads.get())
        assertEquals(1, bad.openCalls.get())
        assertEquals(0, good.openCalls.get())
        assertTrue(bad.raw.closed.count == 0L)
        assertEquals(200, transport.create(request()).execute().use { it.status })
        assertEquals(1, good.openCalls.get())
    }

    @Test fun slowHeadersAndBodyDeadlineRetainSlotUntilCleanupThenReleaseWithoutRetry() {
        for (stage in listOf(Stage.HEADERS, Stage.BODY)) {
            val wire = Fixture(stage = stage); val timer = ManualTimer()
            val releaseClose = CountDownLatch(1); wire.raw.closeRelease = releaseClose
            val transport = wire.transport(timer = timer)
            val call = transport.create(request(deadline = 1_800)); val result = Async(call)
            try {
                await(wire.blocked)
                assertEquals(800L, timer.entries.first().delay)
                timer.fire(0)
                result.failure(CommonsError.DEADLINE_EXCEEDED)
                await(wire.raw.closeEntered)
                expect(CommonsError.BUSY) { transport.create(request()).execute() }
                call.cancel(); call.cancel()
                assertEquals(CommonsError.DEADLINE_EXCEEDED, (result.outcome.get() as CommonsFailure).code)
                assertEquals(1, wire.dnsCalls.get())
                assertEquals(1, wire.openCalls.get())
                releaseClose.countDown()
                awaitReleased(transport)
                assertTrue(timer.entries.all { it.cancelled })
                assertTrue(wire.tls.closed)
            } finally { releaseClose.countDown() }
        }
    }

    @Test fun elapsedMonotonicDeadlineCannotBeExtendedByWallClockRollbackDuringBodyRead() {
        val wire = Fixture()
        val prefix = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n".toByteArray())
        wire.input = object : InputStream() {
            override fun read(): Int = prefix.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                wire.clock.epoch = 900 // Wall clock correction must not extend the admitted duration.
                wire.clock.nanos += 800_000_000
                buffer[offset] = '{'.code.toByte()
                return 1
            }
        }
        val transport = wire.transport()
        expect(CommonsError.DEADLINE_EXCEEDED) { transport.create(request(deadline = 1_800)).execute() }
        awaitReleased(transport)
        assertEquals(1, wire.openCalls.get())
        assertTrue(wire.raw.closed.count == 0L)
        assertTrue(wire.tls.closed)
    }

    @Test fun stageDiagnosticsObserveActualSocketPhasesAndNeverInterpretCleanupAsCancellation() {
        val capture = StageCapture(); val wire = Fixture()
        val response = wire.transport().create(request(trace = capture.trace)).execute()
        response.close()
        val samples = capture.recorder.snapshot().samples
        assertEquals(listOf(CommonsStage.DNS, CommonsStage.TCP_CONNECT, CommonsStage.TLS_HANDSHAKE,
            CommonsStage.REQUEST_WRITE, CommonsStage.HTTP_STATUS, CommonsStage.HTTP_EXCHANGE), samples.map { it.stage })
        assertEquals(200, samples.single { it.stage == CommonsStage.HTTP_STATUS }.httpStatus)
        assertTrue(samples.filter { it.stage != CommonsStage.HTTP_STATUS }.all {
            it.outcome == CommonsStageOutcome.SUCCESS && it.elapsedNanos?.let { elapsed -> elapsed >= 0 } == true })
        assertFalse(samples.toString().contains(HOST))
        assertFalse(samples.toString().contains("example.org"))
        assertEquals(1, wire.dnsCalls.get()); assertEquals(1, wire.openCalls.get())
    }

    @Test fun stageDiagnosticsAlsoCoverActualMetadataParserOnFakeSocketStack() {
        val body = "{\"batchcomplete\":true,\"query\":{\"pages\":[]}}"
        val wire = Fixture("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
        val capture = StageCapture()
        val provider = CommonsMetadataProvider(CommonsClientConfig(true, "0.1", "https://example.org/contact"),
            wire.transport(), clock = EpochClock { 1_000 }, timer = object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle { }
            })
        assertTrue(provider.search(CommonsSearchRequest("private query", safeSearchRequired = false), capture.operation).exhausted)
        val samples = capture.recorder.snapshot().samples
        assertEquals(CommonsStage.JSON_PARSE, samples.last().stage)
        assertEquals(CommonsStageOutcome.SUCCESS, samples.last().outcome)
        assertFalse(samples.any { it.stage == CommonsStage.CANCEL_SIGNAL })
        assertEquals(1, wire.dnsCalls.get()); assertEquals(1, wire.openCalls.get())
    }

    @Test fun stageDiagnosticsPreserveKnownDnsConnectAndTlsExceptionCategoriesOnly() {
        for ((phase, expected) in listOf(CommonsStage.DNS to CommonsStageFailure.DNS_FAILURE,
            CommonsStage.TCP_CONNECT to CommonsStageFailure.CONNECT_FAILURE,
            CommonsStage.TLS_HANDSHAKE to CommonsStageFailure.TLS_FAILURE)) {
            val capture = StageCapture(); val wire = Fixture()
            if (phase == CommonsStage.TCP_CONNECT) wire.raw.connectFailure = ConnectException("private-host-path")
            if (phase == CommonsStage.TLS_HANDSHAKE) wire.tls.handshakeFailure = SSLHandshakeException("private-cert-detail")
            val transport = wire.transport(dns = if (phase == CommonsStage.DNS)
                CommonsDnsResolver { throw UnknownHostException("private-host") } else null)
            expect(CommonsError.SERVICE_ERROR) { transport.create(request(trace = capture.trace)).execute() }
            val samples = capture.recorder.snapshot().samples
            assertEquals(expected, samples.single { it.stage == phase }.failure)
            assertEquals(CommonsStageOutcome.FAILURE, samples.single { it.stage == phase }.outcome)
            assertFalse(samples.toString().contains("private-")); assertFalse(samples.toString().contains("Exception"))
            assertFalse(samples.any { it.stage == CommonsStage.HTTP_STATUS })
        }
    }

    @Test fun stageDiagnosticsSharedConnectBudgetFencesTlsCleanupBeforeAndAfterRuntimeFinish() {
        for (finishBeforeClose in listOf(false, true)) {
            val capture = StageCapture(); val wire = Fixture(stage = Stage.TLS); val timer = ManualTimer()
            val releaseClose = CountDownLatch(1); wire.raw.closeRelease = releaseClose
            val transport = wire.transport(timer = timer)
            val call = transport.create(request(trace = capture.trace)); val result = Async(call)
            try {
                await(wire.blocked)
                assertEquals(15_000L, timer.entries[0].delay)
                assertEquals(5_000L, timer.entries[1].delay)
                assertEquals(CommonsStage.TLS_HANDSHAKE, capture.recorder.snapshot().activeStages.single().stage)
                wire.clock.epoch += 5_000
                wire.clock.nanos += TimeUnit.MILLISECONDS.toNanos(5_000)
                timer.fire(1) // The shared TCP/TLS budget expires before the absolute deadline.
                result.failure(CommonsError.SERVICE_ERROR)
                await(wire.raw.closeEntered)
                // Keep physical close blocked so logical failure cannot race the final worker evidence.
                assertEquals(1L, wire.raw.closed.count)
                assertTrue(capture.recorder.snapshot().activeStages.isEmpty())
                if (finishBeforeClose) capture.finishFailure()
                timer.entries[0].action(); call.cancel(); call.cancel()
                expect(CommonsError.BUSY) { transport.create(request()).execute() }
                releaseClose.countDown()
                awaitReleased(transport)

                val snapshot = capture.recorder.snapshot()
                val samples = snapshot.samples
                assertEquals(listOf(CommonsStage.DNS, CommonsStage.TCP_CONNECT,
                    CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStage.TLS_HANDSHAKE) +
                    (if (finishBeforeClose) listOf(CommonsStage.OPERATION) else emptyList()), samples.map { it.stage })
                val budget = samples.single { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL }
                assertEquals(CommonsStageOutcome.OBSERVED, budget.outcome)
                assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, budget.failure)
                assertEquals(CommonsStageTiming.NOT_MEASURED, budget.timing)
                assertNull(budget.elapsedNanos)
                val handshake = samples.single { it.stage == CommonsStage.TLS_HANDSHAKE }
                assertEquals(CommonsStageOutcome.INCOMPLETE, handshake.outcome)
                assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, handshake.failure)
                assertEquals(CommonsStageTiming.NOT_MEASURED, handshake.timing)
                assertNull(handshake.elapsedNanos)
                assertFalse(samples.any { it.stage == CommonsStage.PROVIDER_FAILURE })
                if (finishBeforeClose) {
                    val operation = samples.single { it.stage == CommonsStage.OPERATION }
                    assertEquals(CommonsStageOutcome.FAILURE, operation.outcome)
                    assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, operation.failure)
                }
                assertTrue(snapshot.activeStages.isEmpty())
                assertFalse(samples.any { it.stage == CommonsStage.DEADLINE_SIGNAL || it.stage == CommonsStage.CANCEL_SIGNAL })
                assertEquals(0, wire.output.size())
                assertEquals(1, wire.dnsCalls.get()); assertEquals(1, wire.openCalls.get())
                assertTrue(wire.tls.closed)
                assertTrue(timer.entries.all { it.cancelled })
            } finally { releaseClose.countDown() }
        }
    }

    @Test fun stageDiagnosticsSharedBudgetDuringTcpDoesNotInventTls() {
        val capture = StageCapture(); val wire = Fixture(stage = Stage.CONNECT); val timer = ManualTimer()
        val transport = wire.transport(timer = timer)
        val result = Async(transport.create(request(trace = capture.trace)))
        await(wire.blocked)
        timer.fire(1); result.failure(CommonsError.SERVICE_ERROR)
        awaitReleased(transport)
        val samples = capture.recorder.snapshot().samples
        assertEquals(listOf(CommonsStage.DNS, CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStage.TCP_CONNECT), samples.map { it.stage })
        assertEquals(CommonsStageOutcome.INCOMPLETE, samples.single { it.stage == CommonsStage.TCP_CONNECT }.outcome)
        assertNull(samples.single { it.stage == CommonsStage.TCP_CONNECT }.elapsedNanos)
        assertEquals(0, wire.secureCalls.get()); assertEquals(0, wire.output.size())
    }

    @Test fun stageDiagnosticsSynchronousBudgetBoundariesPreserveOnlyActuallyCompletedPhases() {
        for (afterHandshake in listOf(false, true)) {
            val capture = StageCapture(); val wire = Fixture()
            val expire = { wire.clock.epoch += 5_000; wire.clock.nanos += TimeUnit.MILLISECONDS.toNanos(5_000) }
            if (afterHandshake) wire.afterHandshake = expire else wire.afterConnect = expire
            val transport = wire.transport()
            expect(CommonsError.SERVICE_ERROR) { transport.create(request(trace = capture.trace)).execute() }
            awaitReleased(transport)
            val samples = capture.recorder.snapshot().samples
            assertEquals(listOf(CommonsStage.DNS, CommonsStage.TCP_CONNECT) +
                (if (afterHandshake) listOf(CommonsStage.TLS_HANDSHAKE) else emptyList()) +
                CommonsStage.CONNECT_BUDGET_SIGNAL, samples.map { it.stage })
            assertTrue(samples.filter { it.stage != CommonsStage.CONNECT_BUDGET_SIGNAL }.all {
                it.outcome == CommonsStageOutcome.SUCCESS && it.timing == CommonsStageTiming.MEASURED })
            assertNull(samples.last().elapsedNanos)
            assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, samples.last().failure)
            assertEquals(0, wire.output.size())
        }
    }

    @Test fun synchronousBudgetFailureStillWaitsForCloseAndAbsoluteDeadlineCanFinishFirst() {
        for (afterHandshake in listOf(false, true)) {
            for (deadlineDuringClose in listOf(false, true)) {
                val capture = StageCapture(); val wire = Fixture(); val timer = ManualTimer()
                val expire = { wire.clock.epoch += 5_000; wire.clock.nanos += TimeUnit.MILLISECONDS.toNanos(5_000) }
                if (afterHandshake) wire.afterHandshake = expire else wire.afterConnect = expire
                val releaseClose = CountDownLatch(1); wire.raw.closeRelease = releaseClose
                val transport = wire.transport(timer = timer)
                val result = Async(transport.create(request(trace = capture.trace)))
                try {
                    await(wire.raw.closeEntered)
                    assertEquals(1L, result.finished.count)
                    timer.entries[1].action() // The ended shared budget must not publish early either.
                    assertEquals(1L, result.finished.count)
                    expect(CommonsError.BUSY) { transport.create(request()).execute() }
                    assertEquals(1, capture.recorder.snapshot().samples.count { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
                    if (deadlineDuringClose) {
                        timer.fire(0)
                        result.failure(CommonsError.DEADLINE_EXCEEDED)
                        capture.finishFailure(DiscoveryError.DEADLINE_EXCEEDED)
                        val samples = capture.recorder.snapshot().samples
                        assertEquals(1, samples.count { it.stage == CommonsStage.DEADLINE_SIGNAL })
                        val operation = samples.single { it.stage == CommonsStage.OPERATION }
                        assertEquals(CommonsStageOutcome.DEADLINE, operation.outcome)
                        assertEquals(CommonsStageFailure.DEADLINE_EXCEEDED, operation.failure)
                        assertEquals(1L, wire.raw.closed.count)
                    }
                    releaseClose.countDown(); awaitReleased(transport)
                    result.failure(if (deadlineDuringClose) CommonsError.DEADLINE_EXCEEDED else CommonsError.SERVICE_ERROR)
                    assertEquals(0, wire.output.size()); assertTrue(wire.tls.closed)
                } finally { releaseClose.countDown() }
            }
        }
    }

    @Test fun earlierCancellationOrAbsoluteDeadlineCannotBecomeConnectBudgetFailure() {
        for (deadline in listOf(false, true)) {
            val capture = StageCapture(); val wire = Fixture(stage = Stage.TLS); val timer = ManualTimer()
            val releaseClose = CountDownLatch(1); wire.raw.closeRelease = releaseClose
            val transport = wire.transport(timer = timer)
            val call = transport.create(request(trace = capture.trace)); val result = Async(call)
            try {
                await(wire.blocked)
                if (deadline) timer.fire(0) else call.cancel()
                result.failure(if (deadline) CommonsError.DEADLINE_EXCEEDED else CommonsError.CANCELLED)
                timer.entries[1].action() // A callback already racing its cancellation.
                releaseClose.countDown(); awaitReleased(transport)
                assertFalse(capture.recorder.snapshot().samples.any { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
            } finally { releaseClose.countDown() }
        }
    }

    @Test fun endedConnectBudgetIgnoresLateWatchdogDuringBodyAndAfterOrdinaryTlsFailure() {
        for (tlsFailure in listOf(false, true)) {
            val capture = StageCapture(); val wire = Fixture(stage = if (tlsFailure) null else Stage.BODY)
            val timer = ManualTimer(); val releaseClose = CountDownLatch(1); wire.raw.closeRelease = releaseClose
            if (tlsFailure) wire.tls.handshakeFailure = SSLHandshakeException("private-cert-detail")
            val transport = wire.transport(timer = timer)
            val call = transport.create(request(trace = capture.trace)); val result = Async(call)
            try {
                if (tlsFailure) await(wire.raw.closeEntered) else await(wire.blocked)
                timer.entries[1].action()
                assertEquals(1L, result.finished.count)
                assertFalse(capture.recorder.snapshot().samples.any { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
                if (!tlsFailure) {
                    assertEquals(1L, wire.raw.closeEntered.count)
                    call.cancel()
                }
                releaseClose.countDown(); awaitReleased(transport)
                result.failure(if (tlsFailure) CommonsError.SERVICE_ERROR else CommonsError.CANCELLED)
                val handshake = capture.recorder.snapshot().samples.single { it.stage == CommonsStage.TLS_HANDSHAKE }
                assertEquals(if (tlsFailure) CommonsStageFailure.TLS_FAILURE else null, handshake.failure)
            } finally { releaseClose.countDown() }
        }
    }

    @Test fun claimedConnectBudgetRecordsObservationBeforeCompetingTransportStopCanPublishOrClose() {
        val capture = StageCapture(); val wire = Fixture(stage = Stage.TLS); val timer = ManualTimer()
        val transport = wire.transport(timer = timer)
        val call = transport.create(request(trace = capture.trace)); val result = Async(call)
        await(wire.blocked)
        val timerDone = CountDownLatch(1); val cancelDone = CountDownLatch(1)
        synchronized(capture.recorder) {
            Thread({ try { timer.fire(1) } finally { timerDone.countDown() } }).apply { isDaemon = true; start() }
            val field = call.javaClass.getDeclaredField("failure").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val failure = field.get(call) as AtomicReference<CommonsError?>
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (failure.get() == null && System.nanoTime() < until) Thread.yield()
            assertEquals(CommonsError.SERVICE_ERROR, failure.get())
            val cancel = Thread({ try { call.cancel() } finally { cancelDone.countDown() } }).apply { isDaemon = true; start() }
            val cancelUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (cancel.state != Thread.State.BLOCKED && System.nanoTime() < cancelUntil) Thread.yield()
            assertEquals(Thread.State.BLOCKED, cancel.state)
            assertEquals(1L, result.finished.count)
            assertEquals(1L, wire.raw.closeEntered.count)
        }
        await(timerDone); await(cancelDone)
        result.failure(CommonsError.SERVICE_ERROR); awaitReleased(transport)
        assertEquals(1, capture.recorder.snapshot().samples.count { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
        assertFalse(capture.recorder.snapshot().samples.any { it.stage == CommonsStage.CANCEL_SIGNAL })
    }

    @Test fun realOwnerCancellationFlagCanPrecedeObservationAfterTransportBudgetClaim() {
        val capture = StageCapture(); val wire = Fixture(stage = Stage.TLS); val timer = ManualTimer()
        val transport = wire.transport(timer = timer)
        val call = transport.create(request(trace = capture.trace)); val result = Async(call)
        await(wire.blocked)
        val timerDone = CountDownLatch(1)
        synchronized(capture.recorder) {
            Thread({ try { timer.fire(1) } finally { timerDone.countDown() } }).apply { isDaemon = true; start() }
            val field = call.javaClass.getDeclaredField("failure").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val failure = field.get(call) as AtomicReference<CommonsError?>
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (failure.get() == null && System.nanoTime() < until) Thread.yield()
            assertEquals(CommonsError.SERVICE_ERROR, failure.get())
            // The transport CAS and the owner's shared flag are independent. Observation must
            // honor the real flag rather than claim that the transport won a global cause race.
            capture.operation.cancellation.cancel()
            assertTrue(capture.operation.cancellation.isCancelled)
            assertEquals(1L, result.finished.count)
            assertEquals(1L, wire.raw.closeEntered.count)
        }
        await(timerDone); result.failure(CommonsError.SERVICE_ERROR); awaitReleased(transport)
        capture.finishFailure()
        val samples = capture.recorder.snapshot().samples
        assertFalse(samples.any { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
        assertEquals(1, samples.count { it.stage == CommonsStage.CANCEL_SIGNAL })
        val tls = samples.single { it.stage == CommonsStage.TLS_HANDSHAKE }
        assertEquals(CommonsStageOutcome.INCOMPLETE, tls.outcome)
        assertEquals(CommonsStageFailure.CANCELLED, tls.failure)
        assertNull(tls.elapsedNanos)
        assertEquals(CommonsStageOutcome.CANCELLED, samples.single { it.stage == CommonsStage.OPERATION }.outcome)
    }

    @Test fun successfulResponseFinallyCancellationAndLateBudgetCallbackRemainOnlyCleanup() {
        val capture = StageCapture(); val wire = Fixture(); val timer = ManualTimer()
        val transport = wire.transport(timer = timer)
        val call = transport.create(request(trace = capture.trace))
        call.execute().close()
        call.cancel(); call.cancel()
        timer.entries[1].action()
        awaitReleased(transport)
        val samples = capture.recorder.snapshot().samples
        assertFalse(samples.any { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL ||
            it.stage == CommonsStage.CANCEL_SIGNAL || it.stage == CommonsStage.DEADLINE_SIGNAL })
        assertEquals(CommonsStageOutcome.SUCCESS, samples.single { it.stage == CommonsStage.TLS_HANDSHAKE }.outcome)
        assertEquals(0L, wire.raw.closed.count); assertTrue(wire.tls.closed)
        assertTrue(timer.entries.all { it.cancelled })
    }

    @Test fun stageDiagnosticsKeepActualHttpStatusEvenWhenHeadersOrBodyFail() {
        for (wireText in listOf("HTTP/1.1 302 Found\r\nLocation: https://private.invalid/\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n{}")) {
            val capture = StageCapture(); val wire = Fixture(wireText)
            expect(if (wireText.contains("302")) CommonsError.REDIRECT_REJECTED else CommonsError.INVALID_RESPONSE) {
                wire.transport().create(request(trace = capture.trace)).execute()
            }
            val samples = capture.recorder.snapshot().samples
            assertEquals(if (wireText.contains("302")) 302 else 200, samples.single { it.stage == CommonsStage.HTTP_STATUS }.httpStatus)
            assertEquals(CommonsStageOutcome.FAILURE, samples.single { it.stage == CommonsStage.HTTP_EXCHANGE }.outcome)
            assertFalse(samples.toString().contains("private.invalid"))
        }
    }

    @Test fun stageDiagnosticsReportTransportCooldownWithoutSyntheticWaitOrDns() {
        val wire = Fixture("HTTP/1.1 429 Slow Down\r\nRetry-After: 60\r\n\r\n")
        val transport = wire.transport()
        val first = StageCapture()
        assertEquals(429, transport.create(request(trace = first.trace)).execute().use { it.status })
        awaitReleased(transport)
        assertTrue(first.recorder.snapshot().samples.any { it.stage == CommonsStage.SERVER_COOLDOWN })
        val next = StageCapture()
        expect(CommonsError.RATE_LIMITED) { transport.create(request(trace = next.trace)).execute() }
        val samples = next.recorder.snapshot().samples
        assertEquals(listOf(CommonsStage.TRANSPORT_COOLDOWN), samples.map { it.stage })
        assertNull(samples.single().elapsedNanos)
        assertEquals(1, wire.dnsCalls.get()); assertEquals(1, wire.openCalls.get())
    }

    @Test fun stageDiagnosticsExposeActiveDnsThenDeadlineAndRejectLateCompletion() {
        val capture = StageCapture(); val wire = Fixture(); val timer = ManualTimer()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = wire.transport(dns = CommonsDnsResolver {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); arrayOf(PUBLIC)
        }, timer = timer)
        val result = Async(transport.create(request(trace = capture.trace)))
        try {
            await(entered)
            val active = capture.recorder.snapshot().activeStages.single()
            assertEquals(CommonsStage.DNS, active.stage)
            assertEquals(CommonsStageOutcome.IN_PROGRESS, active.outcome); assertNull(active.elapsedNanos)
            timer.fire(0); result.failure(CommonsError.DEADLINE_EXCEEDED)
            val samples = capture.recorder.snapshot().samples
            assertEquals(CommonsStageOutcome.INCOMPLETE, samples.single { it.stage == CommonsStage.DNS }.outcome)
            assertNull(samples.single { it.stage == CommonsStage.DNS }.elapsedNanos)
            release.countDown(); awaitReleased(transport)
            assertEquals(1, capture.recorder.snapshot().samples.count { it.stage == CommonsStage.DNS })
            assertEquals(0, wire.openCalls.get())
            assertFalse(capture.recorder.snapshot().samples.any { it.stage == CommonsStage.CANCEL_SIGNAL })
        } finally { release.countDown() }
    }

    @Test fun stageDiagnosticsMapOutOfPhaseSocketConstructionFailureWithoutInventingPhase() {
        val capture = StageCapture(); val wire = Fixture()
        val transport = SystemCommonsTransport(CommonsDnsResolver { arrayOf(PUBLIC) }, object : CommonsSockets {
            override fun open(): Socket = throw IOException("private-open-detail")
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket = error("Not reached")
        }, wire.clock, ManualTimer())
        expect(CommonsError.SERVICE_ERROR) { transport.create(request(trace = capture.trace)).execute() }
        assertEquals(CommonsStageFailure.IO_FAILURE,
            capture.recorder.snapshot().samples.single { it.stage == CommonsStage.PROVIDER_FAILURE }.failure)
        assertFalse(capture.recorder.snapshot().samples.any { it.stage == CommonsStage.TCP_CONNECT })
    }

    private class StageCapture {
        val recorder = CommonsStageDiagnosticsRecorder()
        lateinit var operation: DiscoveryOperation
        private lateinit var runtimeCallback: (ProviderOutcome) -> Unit
        val trace get() = operation.commonsStageTrace!!
        fun finishFailure(error: DiscoveryError = DiscoveryError.PROVIDER_FAILURE) { runtimeCallback(ProviderOutcome.Failure(error)) }
        private val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("wikimedia-commons", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation) = ProviderPage()
        })), object : ProviderRuntime {
            override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                callback: (ProviderOutcome) -> Unit): CancellationHandle {
                this@StageCapture.operation = operation; runtimeCallback = callback; return CancellationHandle { }
            }
        }, InMemoryDiscoveryStore(), object : DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle { }
        }, EpochClock { 1_000 }, commonsStageDiagnostics = recorder)
        init {
            coordinator.setVisibility(true, true)
            coordinator.start("private-session", SearchProfile("private-profile", "private query"),
                diagnosticsCapture = recorder.setEnabled(true))
        }
    }

    /** Each admitted exchange receives distinct fake sockets; failed sockets are never reused. */
    private fun sequenceTransport(timer: CommonsTransportTimer, vararg wires: Fixture): SystemCommonsTransport {
        val next = AtomicInteger()
        return SystemCommonsTransport(CommonsDnsResolver { arrayOf(PUBLIC) }, object : CommonsSockets {
            override fun open(): Socket = wires[next.getAndIncrement()].let { it.openCalls.incrementAndGet(); it.raw }
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket =
                wires.single { it.raw === raw }.let { it.secureCalls.incrementAndGet(); it.tls }
        }, wires.first().clock, timer)
    }

    private class Clock : CommonsTransportClock {
        @Volatile var epoch = 1_000L
        @Volatile var nanos = 1_000_000_000L
        override fun epochMillis() = epoch
        override fun nanoTime() = nanos
    }
    private class ManualTimer : CommonsTransportTimer {
        data class Entry(val delay: Long, val action: () -> Unit, @Volatile var cancelled: Boolean = false)
        val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()
        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val entry = Entry(delayMillis, action); entries.add(entry)
            return AutoCloseable { entry.cancelled = true }
        }
        fun fire(index: Int) { entries[index].let { if (!it.cancelled) it.action() } }
    }
    private enum class Stage { CONNECT, TLS, WRITE, HEADERS, BODY }
    private class Fixture(response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}", val stage: Stage? = null) {
        val clock = Clock()
        val dnsCalls = AtomicInteger(); val openCalls = AtomicInteger(); val secureCalls = AtomicInteger()
        val blocked = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val raw = RawSocket(this)
        val tls = SecureSocket(this)
        var afterConnect: (() -> Unit)? = null
        var afterHandshake: (() -> Unit)? = null
        var secureHost: String? = null
        var input: InputStream = if (stage == Stage.HEADERS || stage == Stage.BODY) object : InputStream() {
            val prefix = ByteArrayInputStream(if (stage == Stage.BODY) "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n".toByteArray() else byteArrayOf())
            override fun read(): Int {
                val next = prefix.read(); if (next >= 0) return next
                blocked.countDown(); raw.closed.await(); throw IOException("closed")
            }
        } else ByteArrayInputStream(response.toByteArray(Charsets.ISO_8859_1))
        fun transport(dns: CommonsDnsResolver? = null, timer: CommonsTransportTimer = ManualTimer()) = SystemCommonsTransport(
            dns ?: CommonsDnsResolver { dnsCalls.incrementAndGet(); arrayOf(PUBLIC) },
            object : CommonsSockets {
                override fun open(): Socket { openCalls.incrementAndGet(); return raw }
                override fun secure(raw: Socket, host: String, port: Int): SSLSocket {
                    secureCalls.incrementAndGet(); secureHost = host; assertEquals(443, port); return tls
                }
            }, clock, timer)
    }
    private class RawSocket(private val fixture: Fixture) : Socket() {
        var destination: SocketAddress? = null; var timeout = 0
        var connectFailure: IOException? = null
        var reportedPeer: InetAddress = PUBLIC
        val closed = CountDownLatch(1); val closeEntered = CountDownLatch(1)
        var closeRelease: CountDownLatch? = null
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            destination = endpoint; this.timeout = timeout
            connectFailure?.let { throw it }
            if (fixture.stage == Stage.CONNECT) {
                fixture.blocked.countDown(); closed.await(); throw IOException("closed")
            }
            fixture.afterConnect?.invoke()
        }
        override fun isConnected() = destination != null
        override fun getInetAddress() = reportedPeer
        override fun getPort() = 443
        override fun setSoLinger(on: Boolean, linger: Int) { assertFalse(on) }
        override fun close() { closeEntered.countDown(); closeRelease?.await(); closed.countDown() }
    }
    private class SecureSocket(private val fixture: Fixture) : SSLSocket() {
        var parameters = SSLParameters()
        var reportedPeer: InetAddress = PUBLIC
        var clientMode = false; var closed = false; var readTimeout = 0
        var handshakeFailure: IOException? = null
        override fun getSSLParameters(): SSLParameters = parameters
        override fun setSSLParameters(parameters: SSLParameters) { this.parameters = parameters }
        override fun getApplicationProtocol(): String = "http/1.1"
        override fun getInetAddress(): InetAddress = reportedPeer
        override fun getPort() = 443
        override fun isConnected() = true
        override fun getInputStream(): InputStream = fixture.input
        override fun getOutputStream(): OutputStream = if (fixture.stage == Stage.WRITE) object : OutputStream() {
            override fun write(value: Int) {
                fixture.blocked.countDown(); fixture.raw.closed.await(); throw IOException("closed")
            }
        } else fixture.output
        override fun setSoTimeout(timeout: Int) { readTimeout = timeout }
        override fun startHandshake() {
            handshakeFailure?.let { throw it }
            if (fixture.stage == Stage.TLS) {
                fixture.blocked.countDown(); fixture.raw.closed.await(); throw IOException("closed")
            }
            fixture.afterHandshake?.invoke()
        }
        override fun close() { closed = true }
        override fun setUseClientMode(mode: Boolean) { clientMode = mode }
        override fun getUseClientMode() = clientMode
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3", "TLSv1.2")
        override fun getEnabledProtocols(): Array<String> = supportedProtocols
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
    private class Async(call: CommonsHttpCall) {
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        init { Thread({ try { call.execute() } catch (failure: Throwable) { outcome.set(failure) } finally { finished.countDown() } }).apply { isDaemon = true; start() } }
        fun failure(code: CommonsError) {
            await(finished)
            assertEquals(code, (outcome.get() as? CommonsFailure)?.code)
        }
    }
    companion object {
        private const val HOST = "commons.wikimedia.org"
        private val PUBLIC = InetAddress.getByAddress(byteArrayOf(91, -58, -82, -64))
        private fun currentAuthenticator(): Authenticator? =
            Authenticator::class.java.getMethod("getDefault").invoke(null) as? Authenticator
        private fun headers() = linkedMapOf("User-Agent" to "EasySaveVideo/0.1 (https://example.org/contact)",
            "Accept" to "application/json", "Accept-Encoding" to "identity", "Host" to HOST, "Connection" to "close")
        private fun request(url: URI = URI("https://$HOST/w/api.php?action=query"), headers: Map<String, String> = headers(),
                            deadline: Long = 16_000, connect: Int = 5_000, maximum: Long = 2_097_152, trace: CommonsStageTrace? = null) =
            CommonsHttpRequest(url, headers, deadline, connect, maximum, trace)
        private fun expect(code: CommonsError, action: () -> Unit) {
            try { action(); fail("Expected $code") } catch (failure: CommonsFailure) { assertEquals(code, failure.code) }
        }
        private fun await(latch: CountDownLatch) { assertTrue("Operation did not reach expected state", latch.await(3, TimeUnit.SECONDS)) }
        private fun awaitReleased(transport: SystemCommonsTransport) {
            // Observe physical release without manufacturing another network request.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            val field = SystemCommonsTransport::class.java.getDeclaredField("active").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val active = field.get(transport) as AtomicReference<Any?>
            while (active.get() != null && System.nanoTime() < until) Thread.yield()
            assertNull("Physical slot was not released", active.get())
        }
    }
}
