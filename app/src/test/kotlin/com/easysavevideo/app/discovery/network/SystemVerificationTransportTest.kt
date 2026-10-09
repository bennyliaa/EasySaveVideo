package com.easysavevideo.app.discovery.network

import com.easysavevideo.verification.VerificationEndpoint
import com.easysavevideo.verification.VerificationError
import com.easysavevideo.verification.VerificationFailure
import com.easysavevideo.verification.VerificationHttpCall
import com.easysavevideo.verification.VerificationHttpRequest
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

class SystemVerificationTransportTest {
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
        assertEquals("POST /v1/verify HTTP/1.1\r\n" +
            "User-Agent: EasySaveVideo/1.0 (page verification)\r\n" +
            "Accept: application/json\r\nAccept-Encoding: identity\r\nHost: $HOST\r\nConnection: close\r\n" +
            "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}",
            wire.output.toString("US-ASCII"))
        assertEquals(1, wire.dnsCalls.get())
        assertEquals(1, wire.openCalls.get())
        assertTrue(wire.raw.closed.count == 0L)
        assertTrue(wire.tls.closed)
    }

    @Test fun differentSelectedPublicEndpointControlsDnsHostSniAndRequestTarget() {
        val selected = VerificationEndpoint("https://verify-other.example.net/v1/verify")
        val resolved = AtomicReference<String?>()
        val wire = Fixture()
        val request = request(endpoint = selected)
        val transport = wire.transport(VerificationDnsResolver { host -> resolved.set(host); arrayOf(PUBLIC) })
        val response = transport.create(request).execute()
        assertEquals(request.url, response.url)
        assertEquals(selected.uri.host, resolved.get())
        assertEquals(selected.uri.host, wire.secureHost)
        assertEquals(selected.uri.host, (wire.tls.parameters.serverNames.single() as SNIHostName).asciiName)
        assertEquals(PUBLIC, (wire.raw.destination as InetSocketAddress).address)
        val sent = wire.output.toString("US-ASCII")
        assertTrue(sent.startsWith("POST /v1/verify HTTP/1.1\r\n"))
        assertTrue(sent.contains("\r\nHost: ${selected.uri.host}\r\n"))
        assertFalse(sent.contains(HOST))
        assertFalse(sent.contains("commons.wikimedia.org"))
    }

    @Test fun explicitStandardPortIsRetainedOnlyInHostHeader() {
        val selected = VerificationEndpoint("https://$HOST:443/v1/verify")
        val wire = Fixture()
        wire.transport().create(request(endpoint = selected)).execute().close()
        assertEquals(HOST, wire.secureHost)
        assertEquals(HOST, (wire.tls.parameters.serverNames.single() as SNIHostName).asciiName)
        assertTrue(wire.output.toString("US-ASCII").contains("\r\nHost: $HOST:443\r\n"))
    }

    @Test fun utf8BodyAndAllCallerOwnedBuffersAreSnapshottedBeforeWorkerStarts() {
        val source = "{\"title\":\"é\"}".toByteArray(Charsets.UTF_8)
        val original = source.copyOf()
        val values = headers().apply { put("Content-Length", source.size.toString()) }
        val request = request(headers = values, body = source)
        val wire = Fixture(); val call = wire.transport().create(request)
        source.fill('x'.code.toByte()); request.body.fill('y'.code.toByte())
        values["Authorization"] = "private"; values["Content-Length"] = "0"
        call.execute().close()
        val sent = wire.output.toByteArray()
        assertArrayEquals(original, sent.copyOfRange(sent.size - original.size, sent.size))
        assertTrue(sent.toString(Charsets.UTF_8).contains("\r\nContent-Length: ${original.size}\r\n"))
        assertFalse(sent.toString(Charsets.UTF_8).contains("Authorization"))
    }

    @Test fun maximumRequestAndResponseBodySizesAreAcceptedWithoutFramingPenalty() {
        val requestBody = ByteArray(8_192) { 'x'.code.toByte() }
        val wire = Fixture("HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\n" + "y".repeat(32_768))
        val response = wire.transport().create(request(body = requestBody,
            headers = headers() + ("Content-Length" to "8192"))).execute()
        assertEquals(32_768, response.body.readBytes().size)
        assertEquals(8_192, wire.output.toString("US-ASCII").substringAfter("\r\n\r\n").length)
    }

    @Test fun crossEndpointUrlPathAndHostHeaderAreRejectedBeforeDnsOrSocket() {
        val selected = endpoint("verify-other.example.net")
        val wire = Fixture(); val transport = wire.transport()
        for (url in listOf(
            "https://$HOST/v1/verify",
            "https://verify-other.example.net/other/v1/verify",
            "https://verify-other.example.net.evil.example/v1/verify",
        )) expect(VerificationError.UNSAFE_URL) { transport.create(request(endpoint = selected, url = URI(url))) }
        expect(VerificationError.INVALID_REQUEST) { transport.create(request(endpoint = selected, headers = headers(HOST))) }
        assertEquals(0, wire.dnsCalls.get())
        assertEquals(0, wire.openCalls.get())
        assertEquals(0, wire.output.size())
    }

    @Test fun onlyExactAnonymousVerificationUserAgentAndSevenFixedHeadersAreAccepted() {
        val wire = Fixture(); val transport = wire.transport()
        for (agent in listOf("EasySaveVideo/1.0", "EasySaveVideo/2.0 (page verification)",
            "EasySaveVideo/1.0 (page verification) ", "EasySaveVideo/1.0 (page verification; account=private)"))
            expect(VerificationError.INVALID_REQUEST) { transport.create(request(headers = headers() + ("User-Agent" to agent))) }
        for (extra in listOf("Authorization", "Proxy-Authorization", "Cookie", "Referer", "Origin", "Cache-Control"))
            expect(VerificationError.INVALID_REQUEST) { transport.create(request(headers = headers() + (extra to "private"))) }
        for (replacement in listOf("Connection" to "keep-alive", "Accept" to "text/html", "Host" to "$HOST:443",
            "Content-Type" to "text/plain", "Content-Type" to "application/json; charset=utf-8",
            "Content-Length" to "1", "Content-Length" to "02", "Content-Length" to "+2"))
            expect(VerificationError.INVALID_REQUEST) { transport.create(request(headers = headers() + replacement)) }
        expect(VerificationError.INVALID_REQUEST) { transport.create(request(headers = headers() + ("host" to HOST))) }
        assertEquals(0, wire.dnsCalls.get())
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun incompatibleTlsApplicationProtocolTransmitsNoHttp() {
        for (protocol in listOf("h2", "h3", "unexpected")) {
            val wire = Fixture(); wire.tls.negotiatedProtocol = protocol
            expect(VerificationError.INVALID_RESPONSE) { wire.transport().create(request()).execute() }
            assertEquals(0, wire.output.size())
            assertEquals(1, wire.openCalls.get())
            assertTrue(wire.raw.closed.count == 0L)
            assertTrue(wire.tls.closed)
        }
        for (protocol in listOf("", "http/1.1")) {
            val wire = Fixture(); wire.tls.negotiatedProtocol = protocol
            assertEquals(200, wire.transport().create(request()).execute().use { it.status })
        }
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
        val transport = wire.transport(VerificationDnsResolver { arrayOf(PUBLIC, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) })
        expect(VerificationError.UNSAFE_URL) { transport.create(request()).execute() }
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun nonPublicIpv4AndIpv6AnswersAreRejectedEvenAfterPublicAnswer() {
        val disallowed = listOf(
            byteArrayOf(0, 0, 0, 0), byteArrayOf(10, 1, 2, 3), byteArrayOf(100, 64, 0, 1),
            byteArrayOf(127, 0, 0, 1), byteArrayOf(-87, -2, 1, 1), byteArrayOf(-64, -88, 1, 1),
            byteArrayOf(-58, 18, 0, 1), byteArrayOf(-53, 0, 113, 1), byteArrayOf(-32, 0, 0, 1),
            ByteArray(16).apply { this[15] = 1 },
            ByteArray(16).apply { this[0] = 0xfc.toByte(); this[15] = 1 },
            ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1 },
            ByteArray(16).apply { this[0] = 0x20; this[1] = 1; this[2] = 0x0d; this[3] = 0xb8.toByte(); this[15] = 1 },
        )
        for (bytes in disallowed) {
            val wire = Fixture()
            val transport = wire.transport(VerificationDnsResolver { arrayOf(PUBLIC, InetAddress.getByAddress(bytes)) })
            expect(VerificationError.UNSAFE_URL) { transport.create(request()).execute() }
            assertEquals(0, wire.openCalls.get())
        }
    }

    @Test fun emptyDnsAndChangedPublicOrPrivatePeerCannotReachTls() {
        val empty = Fixture()
        expect(VerificationError.UNSAFE_URL) { empty.transport(VerificationDnsResolver { emptyArray() }).create(request()).execute() }
        for (peer in listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), InetAddress.getLoopbackAddress())) {
            val wire = Fixture(); wire.raw.reportedPeer = peer
            expect(VerificationError.UNSAFE_URL) { wire.transport().create(request()).execute() }
            assertEquals(0, wire.secureCalls.get())
            assertEquals(0, wire.output.size())
        }
    }

    @Test fun tlsPeerMismatchAndCertificateOrHostnameFailureTransmitNoHttp() {
        val mismatch = Fixture(); mismatch.tls.reportedPeer = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        expect(VerificationError.UNSAFE_URL) { mismatch.transport().create(request()).execute() }
        assertEquals(0, mismatch.output.size())
        val rejected = Fixture(); rejected.tls.handshakeFailure = SSLHandshakeException("test")
        expect(VerificationError.SERVICE_ERROR) { rejected.transport().create(request()).execute() }
        assertEquals(0, rejected.output.size())
        assertEquals("HTTPS", rejected.tls.parameters.endpointIdentificationAlgorithm)
        assertEquals(1, rejected.openCalls.get())
    }

    @Test fun allRedirectsAreRejectedWithNoSecondDnsOrSocket() {
        for (status in listOf(300, 301, 302, 303, 307, 308, 399)) {
            val wire = Fixture("HTTP/1.1 $status Redirect\r\nLocation: https://elsewhere.example/\r\nContent-Length: 0\r\n\r\n")
            expect(VerificationError.REDIRECT_REJECTED) { wire.transport().create(request()).execute() }
            assertEquals(1, wire.openCalls.get()); assertEquals(1, wire.dnsCalls.get())
        }
    }

    @Test fun denialAndErrorResponsesDoNotAuthenticateOrRetry() {
        for (status in listOf(401, 403, 407, 429, 500, 503)) {
            val wire = Fixture("HTTP/1.1 $status Error\r\nWWW-Authenticate: Basic\r\n" +
                "Set-Cookie: private=value\r\nContent-Length: 0\r\n\r\n")
            val response = wire.transport().create(request()).execute()
            assertEquals(status, response.status)
            assertEquals(0, response.body.readBytes().size)
            assertEquals(1, wire.openCalls.get())
            assertEquals(1, wire.dnsCalls.get())
            assertFalse(wire.output.toString("US-ASCII").contains("Authorization"))
            assertFalse(wire.output.toString("US-ASCII").contains("Cookie"))
        }
    }

    @Test fun denialAndErrorBodiesAreStillSubjectToCompressionAndLengthPolicy() {
        for (status in listOf(401, 403, 429, 503)) {
            val compressed = Fixture("HTTP/1.1 $status Error\r\nContent-Encoding: gzip\r\n\r\n")
            expect(VerificationError.INVALID_RESPONSE) { compressed.transport().create(request()).execute() }
            val oversized = Fixture("HTTP/1.1 $status Error\r\nContent-Length: 32769\r\n\r\n")
            expect(VerificationError.RESPONSE_TOO_LARGE) { oversized.transport().create(request()).execute() }
        }
    }

    @Test fun refusesCredentialExtraOrInjectedHeadersAndSnapshotsSuppliedMap() {
        val bad = listOf(headers() + ("Cookie" to "secret"), headers() + ("Authorization" to "Basic secret"),
            headers() + ("User-Agent" to "EasySaveVideo/test\r\nCookie: x"), headers() + ("Accept-Encoding" to "gzip"),
            headers() + ("Host" to "example.org"), headers() - "Connection")
        for (values in bad) {
            val wire = Fixture()
            expect(VerificationError.INVALID_REQUEST) { wire.transport().create(request(headers = values)) }
            assertEquals(0, wire.openCalls.get())
        }
        val values = headers().toMutableMap()
        val wire = Fixture(); val call = wire.transport().create(request(headers = values))
        values["Cookie"] = "secret"; values["User-Agent"] = "changed"
        call.execute().close()
        assertFalse(wire.output.toString("US-ASCII").contains("Cookie"))
        assertTrue(wire.output.toString("US-ASCII").contains("EasySaveVideo/1.0"))
    }

    @Test fun endpointAndBudgetValidationDoesNoIo() {
        val urls = listOf("http://$HOST/v1/verify", "https://$HOST:444/v1/verify",
            "https://user@$HOST/v1/verify", "https://$HOST/v1/verify#x",
            "https://$HOST/w/../v1/verify", "https://example.org/v1/verify", "https://$HOST/v1/verify?a=b",
            "https://$HOST/v1/verify/", "https://$HOST/v1/%76erify")
        for (url in urls) expect(VerificationError.UNSAFE_URL) { Fixture().transport().create(request(url = URI(url))) }
        val wire = Fixture(); val transport = wire.transport()
        expect(VerificationError.INVALID_REQUEST) { transport.create(request(connect = 5_001)) }
        expect(VerificationError.INVALID_REQUEST) { transport.create(request(maximum = 32_769)) }
        expect(VerificationError.DEADLINE_EXCEEDED) { transport.create(request(deadline = 999)).execute() }
        assertEquals(0, wire.openCalls.get())
    }

    @Test fun cancelledBeforeExecutionAndDuplicateExecuteNeverStartAnotherExchange() {
        val wire = Fixture(); val transport = wire.transport()
        val cancelled = transport.create(request()); cancelled.cancel(); cancelled.cancel()
        expect(VerificationError.CANCELLED) { cancelled.execute() }
        assertEquals(0, wire.openCalls.get())
        val call = transport.create(request()); call.execute().close()
        expect(VerificationError.INVALID_REQUEST) { call.execute() }
        assertEquals(1, wire.openCalls.get())
    }

    @Test fun cancelledStuckDnsReturnsPromptlyAndRetainsPhysicalSlotUntilResolverExits() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val wire = Fixture()
        val transport = wire.transport(VerificationDnsResolver { entered.countDown(); release.await(); arrayOf(PUBLIC) })
        val call = transport.create(request()); val result = Async(call)
        await(entered)
        call.cancel()
        result.failure(VerificationError.CANCELLED)
        repeat(20) { expect(VerificationError.BUSY) { transport.create(request()).execute() } }
        assertEquals(0, wire.openCalls.get())
        release.countDown()
        awaitReleased(transport)
    }

    @Test fun deadlineWhileDnsStuckReturnsPromptlyAndNeverRefreshesOriginalBudget() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val timer = ManualTimer(); val wire = Fixture()
        val transport = wire.transport(VerificationDnsResolver { entered.countDown(); release.await(); arrayOf(PUBLIC) }, timer)
        val call = transport.create(request(deadline = 1_800)); val result = Async(call)
        await(entered)
        assertEquals(800L, timer.entries.first().delay)
        wire.clock.epoch = 1_800; wire.clock.nanos += 800_000_000
        timer.fire(0)
        result.failure(VerificationError.DEADLINE_EXCEEDED)
        expect(VerificationError.BUSY) { transport.create(request()).execute() }
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
            call.cancel(); result.failure(VerificationError.CANCELLED)
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
        call.cancel(); result.failure(VerificationError.CANCELLED)
        await(wire.raw.closeEntered)
        expect(VerificationError.BUSY) { transport.create(request()).execute() }
        closeRelease.countDown(); await(wire.raw.closed)
        awaitReleased(transport)
    }

    @Test fun completedBodyCannotPublishWhileCloseIsBlockedAndDeadlineStillReturnsPromptly() {
        val wire = Fixture(); val closeRelease = CountDownLatch(1)
        wire.raw.closeRelease = closeRelease
        val timer = ManualTimer(); val transport = wire.transport(timer = timer)
        val call = transport.create(request()); val result = Async(call)
        try {
            await(wire.raw.closeEntered)
            assertEquals(1L, result.finished.count)
            expect(VerificationError.BUSY) { transport.create(request()).execute() }
            timer.fire(0)
            result.failure(VerificationError.DEADLINE_EXCEEDED)
            call.cancel()
            expect(VerificationError.BUSY) { transport.create(request()).execute() }
            closeRelease.countDown(); awaitReleased(transport)
            assertEquals(1, wire.openCalls.get())
        } finally { closeRelease.countDown() }
    }

    @Test fun socketCreatedAfterCancellationIsClosedBeforePhysicalSlotRelease() {
        val wire = Fixture(); val opened = CountDownLatch(1); val openRelease = CountDownLatch(1)
        val closeRelease = CountDownLatch(1); wire.raw.closeRelease = closeRelease
        val transport = SystemVerificationTransport(VerificationDnsResolver { arrayOf(PUBLIC) }, object : VerificationSockets {
            override fun open(): Socket { opened.countDown(); openRelease.await(); return wire.raw }
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket = error("TLS must not start")
        }, wire.clock, ManualTimer())
        val call = transport.create(request()); val result = Async(call)
        try {
            await(opened); call.cancel(); result.failure(VerificationError.CANCELLED)
            expect(VerificationError.BUSY) { transport.create(request()).execute() }
            openRelease.countDown(); await(wire.raw.closeEntered)
            expect(VerificationError.BUSY) { transport.create(request()).execute() }
            closeRelease.countDown(); awaitReleased(transport)
            assertNull(wire.raw.destination)
        } finally { openRelease.countDown(); closeRelease.countDown() }
    }

    @Test fun staleConnectTimerCannotRevokeTheBodyAfterHandshakeCompleted() {
        val wire = Fixture(stage = Stage.BODY); val timer = ManualTimer()
        val transport = wire.transport(timer = timer)
        val call = transport.create(request()); val result = Async(call)
        await(wire.blocked)
        assertTrue(timer.entries[1].cancelled)
        timer.entries[1].action() // Simulate an already-dispatched callback racing timer removal.
        assertEquals(1L, result.finished.count)
        call.cancel(); result.failure(VerificationError.CANCELLED)
        awaitReleased(transport)
    }

    @Test fun connectAndAbsoluteWatchdogsCloseTheActualSocket() {
        for (index in listOf(0, 1)) {
            val timer = ManualTimer(); val wire = Fixture(stage = Stage.TLS)
            val transport = wire.transport(timer = timer)
            val result = Async(transport.create(request(deadline = 20_000)))
            await(wire.blocked)
            assertEquals(10_000L, timer.entries[0].delay)
            assertEquals(5_000L, timer.entries[1].delay)
            timer.fire(index)
            result.failure(if (index == 0) VerificationError.DEADLINE_EXCEEDED else VerificationError.SERVICE_ERROR)
            await(wire.raw.closed)
            awaitReleased(transport)
        }
    }

    @Test fun deadlineIncludesElapsedDnsAndTlsAndNeverExtendsForBody() {
        val timer = ManualTimer(); val wire = Fixture()
        val transport = wire.transport(VerificationDnsResolver {
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
        expect(VerificationError.CANCELLED) { response.body.read() }
        val expired = Fixture(); val response2 = expired.transport().create(request()).execute()
        expired.clock.epoch = 20_000
        expect(VerificationError.DEADLINE_EXCEEDED) { response2.body.readBytes() }
    }

    @Test fun memoryBodyCannotBypassCancellationWithBulkOrTransferShortcuts() {
        for (read in listOf<(InputStream) -> Unit>(
            { it.readAllBytes() }, { it.readNBytes(2) }, { it.transferTo(ByteArrayOutputStream()) }, { it.skip(1) },
        )) {
            val wire = Fixture(); val call = wire.transport().create(request()); val response = call.execute()
            call.cancel()
            expect(VerificationError.CANCELLED) { read(response.body) }
        }
        val response = Fixture().transport().create(request()).execute()
        response.close()
        expect(VerificationError.CANCELLED) { response.body.read() }
    }

    @Test fun unreachableDnsIsOneSanitizedAttemptAndExplicitNextRequestCanRecover() {
        val wire = Fixture(); val lookups = AtomicInteger(); val timer = ManualTimer()
        val transport = wire.transport(VerificationDnsResolver {
            if (lookups.incrementAndGet() == 1) throw UnknownHostException("private hostname")
            arrayOf(PUBLIC)
        }, timer)
        try { transport.create(request()).execute(); fail("Expected DNS failure") }
        catch (failure: VerificationFailure) {
            assertEquals(VerificationError.SERVICE_ERROR, failure.code)
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
        catch (failure: VerificationFailure) {
            assertEquals(VerificationError.SERVICE_ERROR, failure.code)
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
            expect(VerificationError.INVALID_RESPONSE) { transport.create(request()).execute() }
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
        catch (failure: VerificationFailure) {
            assertEquals(VerificationError.SERVICE_ERROR, failure.code)
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
        val prefix = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 32769\r\n\r\n".toByteArray())
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
        expect(VerificationError.RESPONSE_TOO_LARGE) { transport.create(request()).execute() }
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
                result.failure(VerificationError.DEADLINE_EXCEEDED)
                await(wire.raw.closeEntered)
                expect(VerificationError.BUSY) { transport.create(request()).execute() }
                call.cancel(); call.cancel()
                assertEquals(VerificationError.DEADLINE_EXCEEDED, (result.outcome.get() as VerificationFailure).code)
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
        expect(VerificationError.DEADLINE_EXCEEDED) { transport.create(request(deadline = 1_800)).execute() }
        awaitReleased(transport)
        assertEquals(1, wire.openCalls.get())
        assertTrue(wire.raw.closed.count == 0L)
        assertTrue(wire.tls.closed)
    }

    /** Each admitted exchange receives distinct fake sockets; failed sockets are never reused. */
    private fun sequenceTransport(timer: VerificationTransportTimer, vararg wires: Fixture): SystemVerificationTransport {
        val next = AtomicInteger()
        return SystemVerificationTransport(VerificationDnsResolver { arrayOf(PUBLIC) }, object : VerificationSockets {
            override fun open(): Socket = wires[next.getAndIncrement()].let { it.openCalls.incrementAndGet(); it.raw }
            override fun secure(raw: Socket, host: String, port: Int): SSLSocket =
                wires.single { it.raw === raw }.let { it.secureCalls.incrementAndGet(); it.tls }
        }, wires.first().clock, timer)
    }

    private class Clock : VerificationTransportClock {
        @Volatile var epoch = 1_000L
        @Volatile var nanos = 1_000_000_000L
        override fun epochMillis() = epoch
        override fun nanoTime() = nanos
    }
    private class ManualTimer : VerificationTransportTimer {
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
        var secureHost: String? = null
        var input: InputStream = if (stage == Stage.HEADERS || stage == Stage.BODY) object : InputStream() {
            val prefix = ByteArrayInputStream(if (stage == Stage.BODY) "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n".toByteArray() else byteArrayOf())
            override fun read(): Int {
                val next = prefix.read(); if (next >= 0) return next
                blocked.countDown(); raw.closed.await(); throw IOException("closed")
            }
        } else ByteArrayInputStream(response.toByteArray(Charsets.ISO_8859_1))
        fun transport(dns: VerificationDnsResolver? = null, timer: VerificationTransportTimer = ManualTimer()) = SystemVerificationTransport(
            dns ?: VerificationDnsResolver { dnsCalls.incrementAndGet(); arrayOf(PUBLIC) },
            object : VerificationSockets {
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
        var negotiatedProtocol: String = "http/1.1"
        override fun getApplicationProtocol(): String = negotiatedProtocol
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
    private class Async(call: VerificationHttpCall) {
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        init { Thread({ try { call.execute() } catch (failure: Throwable) { outcome.set(failure) } finally { finished.countDown() } }).apply { isDaemon = true; start() } }
        fun failure(code: VerificationError) {
            await(finished)
            assertEquals(code, (outcome.get() as? VerificationFailure)?.code)
        }
    }
    companion object {
        private const val HOST = "verify.example.org"
        private val PUBLIC = InetAddress.getByAddress(byteArrayOf(91, -58, -82, -64))
        private fun endpoint(host: String = HOST) = VerificationEndpoint("https://$host/v1/verify")
        private fun currentAuthenticator(): Authenticator? =
            Authenticator::class.java.getMethod("getDefault").invoke(null) as? Authenticator
        private fun headers(host: String = HOST) = linkedMapOf("User-Agent" to "EasySaveVideo/1.0 (page verification)",
            "Accept" to "application/json", "Accept-Encoding" to "identity", "Host" to host, "Connection" to "close",
            "Content-Type" to "application/json", "Content-Length" to "2")
        private fun request(endpoint: VerificationEndpoint = endpoint(),
                            url: URI = endpoint.uri,
                            headers: Map<String, String> = headers(endpoint.uri.rawAuthority),
                            body: ByteArray = "{}".toByteArray(),
                            deadline: Long = 16_000, connect: Int = 5_000, maximum: Long = 32_768) =
            VerificationHttpRequest(endpoint, url, headers, body, deadline, connect, maximum)
        private fun expect(code: VerificationError, action: () -> Unit) {
            try { action(); fail("Expected $code") } catch (failure: VerificationFailure) { assertEquals(code, failure.code) }
        }
        private fun await(latch: CountDownLatch) { assertTrue("Operation did not reach expected state", latch.await(3, TimeUnit.SECONDS)) }
        private fun awaitReleased(transport: SystemVerificationTransport) {
            // Observe physical release without manufacturing another network request.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            val field = SystemVerificationTransport::class.java.getDeclaredField("active").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val active = field.get(transport) as AtomicReference<Any?>
            while (active.get() != null && System.nanoTime() < until) Thread.yield()
            assertNull("Physical slot was not released", active.get())
        }
    }
}
