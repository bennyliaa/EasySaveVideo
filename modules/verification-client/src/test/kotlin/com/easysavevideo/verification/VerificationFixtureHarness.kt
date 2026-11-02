package com.easysavevideo.verification

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryOperation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-source-only cross-language executable. The sole network destination is an explicitly supplied
 * owned 127.0.0.1 fixture port. This adapter is never compiled into Android and is not a production
 * endpoint policy exception. The genuine client JSON and Python evidence response stay unchanged.
 */
object VerificationFixtureHarness {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size in 3..5) { "Expected fixture port, candidate alias and outcome" }
        val port = arguments[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid fixture port")
        val candidate = arguments[1]
        require(candidate.startsWith("https://fixture.example.org/") && VerificationUrlPolicy.candidate(candidate) == candidate) {
            "Invalid fixture alias"
        }
        val expected = VerificationOutcome.valueOf(arguments[2])
        val expectedEvidence = arguments.getOrNull(3)?.let(VerificationEvidence::valueOf)
        val expectedDuration = arguments.getOrNull(4)?.let { if (it == "null") null else it.toLong() }
        check(CookieHandler.getDefault() == null && Authenticator.getDefault() == null) { "Unexpected ambient authentication" }
        // A fresh test JVM only. Fixed-length streaming additionally forbids automatic auth/redirect replay.
        System.setProperty("http.keepAlive", "false")
        System.setProperty("sun.net.http.retryPost", "false")
        val transport = LoopbackFixtureTransport(port)
        val client = VerificationServiceClient(VerificationEndpoint("https://verification.example.org/v1/verify"),
            GENERATION, transport, dynamicAllowed = true, deadlineMillis = 10_000)
        val result = client.verify(CandidateUrl(candidate), freshOperation())
        val sent = transport.request.get() ?: error("No fixture request")
        val fields = StrictJson.read(sent.body).objectValue()
        check(result.outcome == expected) { "Unexpected fixture outcome" }
        check(result.requestId == fields.getValue("requestId").stringOrNull()) { "Request identity mismatch" }
        check(result.generation == GENERATION && result.candidateUrl == candidate && result.pageUrl == candidate) { "Candidate identity mismatch" }
        check(!result.playbackVerified && !result.downloadable) { "Unexpected playback authority" }
        check(transport.calls.get() == 1) { "Unexpected retry" }
        if (expectedEvidence != null) check(result.evidence == expectedEvidence) { "Unexpected fixture evidence" }
        if (arguments.size == 5) check(result.durationMs == expectedDuration) { "Unexpected fixture duration" }
        if (expected == VerificationOutcome.PAGE_EVIDENCE) {
            check(result.evidence != VerificationEvidence.NONE && result.toRaw()?.videoConfirmed == true) { "Missing page evidence" }
            check(result.toRaw()!!.rights == com.easysavevideo.discovery.ResultRights()) { "Unexpected rights authority" }
        } else {
            check(result.toRaw() == null && result.evidence == VerificationEvidence.NONE && result.title == null &&
                result.thumbnailUrl == null && result.durationMs == null) { "Negative response promoted" }
        }
        // No additional HTTP request: original-operation cancellation must still escape as terminal.
        val cancelled = freshOperation().also { it.cancellation.cancel() }
        try { client.verify(CandidateUrl(candidate), cancelled); error("Cancellation was swallowed") }
        catch (failure: DiscoveryFailure) { check(failure.code == DiscoveryError.CANCELLED) }
        check(transport.calls.get() == 1) { "Cancelled fixture contacted service" }
        println("VERIFICATION_FIXTURE_OK outcome=${result.outcome} evidence=${result.evidence}")
    }

    private fun freshOperation(): DiscoveryOperation {
        val cancellation = DiscoveryCancellation()
        return DiscoveryOperation(OperationContext("verification-fixture", System.currentTimeMillis() + 15_000, cancellation), cancellation)
    }

    private class LoopbackFixtureTransport(private val port: Int) : VerificationTransport {
        val calls = AtomicInteger()
        val request = AtomicReference<VerificationHttpRequest?>()
        override fun create(request: VerificationHttpRequest): VerificationHttpCall {
            check(this.request.compareAndSet(null, request)) { "Unexpected fixture attempt" }
            return object : VerificationHttpCall {
                private val cancelled = AtomicBoolean(false)
                private val started = AtomicBoolean(false)
                private val active = AtomicReference<HttpURLConnection?>()
                private fun checkCurrent() {
                    if (cancelled.get()) throw VerificationFailure(VerificationError.CANCELLED)
                    if (System.currentTimeMillis() >= request.deadlineEpochMillis)
                        throw VerificationFailure(VerificationError.DEADLINE_EXCEEDED)
                }
                override fun execute(): VerificationHttpResponse {
                    check(started.compareAndSet(false, true)) { "Duplicate fixture call" }
                    checkCurrent()
                    calls.incrementAndGet()
                    val connection = URL("http://127.0.0.1:$port/v1/verify").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    active.set(connection)
                    try {
                        checkCurrent()
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.allowUserInteraction = false
                        connection.connectTimeout = request.connectTimeoutMillis
                        connection.readTimeout = (request.deadlineEpochMillis - System.currentTimeMillis()).coerceIn(1, 10_000).toInt()
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        request.headers.filterKeys { !it.equals("Host", true) }.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                        val bytes = request.body
                        check(bytes.size in 1..8192)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { checkCurrent(); it.write(bytes); it.flush() }
                        checkCurrent()
                        val status = connection.responseCode
                        if (status in 300..399) throw VerificationFailure(VerificationError.REDIRECT_REJECTED)
                        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
                            .mapValues { it.value.toList() }
                        val body = ByteArrayOutputStream()
                        (if (status >= 400) connection.errorStream else connection.inputStream)?.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                checkCurrent()
                                val allowance = minOf(buffer.size.toLong(), request.maxBodyBytes - body.size() + 1).toInt()
                                val count = input.read(buffer, 0, allowance)
                                checkCurrent()
                                if (count < 0) break
                                if (count == 0 || count > allowance) throw VerificationFailure(VerificationError.INVALID_RESPONSE)
                                if (body.size() + count > request.maxBodyBytes) throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
                                body.write(buffer, 0, count)
                            }
                        }
                        checkCurrent()
                        return VerificationHttpResponse(status, request.url, headers, ByteArrayInputStream(body.toByteArray()))
                    } finally {
                        active.compareAndSet(connection, null)
                        connection.disconnect()
                    }
                }
                override fun cancel() { cancelled.set(true); active.getAndSet(null)?.disconnect() }
            }
        }
    }
}
