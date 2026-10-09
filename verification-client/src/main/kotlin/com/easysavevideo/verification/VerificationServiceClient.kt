package com.easysavevideo.verification

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional page evidence only. One candidate, one attempt, no source metadata or browser state.
 * Service failure remains a typed negative outcome; CandidateValidator returns null so its caller
 * can retain the original candidate. Original operation cancellation/deadline and configuration
 * revocation are terminal. The 1s operation reserve leaves time to publish remaining candidates.
 */
class VerificationServiceClient(
    private val endpoint: VerificationEndpoint,
    private val generation: String,
    private val transport: VerificationTransport,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val isCurrent: () -> Boolean = { true },
    private val dynamicAllowed: Boolean = false,
    private val timer: DiscoveryTimer = SharedVerificationTimer,
    private val deadlineMillis: Long = 3_000,
) : CandidateValidator {
    init {
        require(generation.matches(Regex("[0-9a-f]{64}"))) { "Invalid generation" }
        require(deadlineMillis in 1..10_000) { "Invalid deadline" }
    }
    private val inFlight = AtomicBoolean(false)

    override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult? {
        val result = verify(candidate, operation)
        checkOperation(operation)
        return result.toRaw()
    }

    fun verify(candidate: CandidateUrl, operation: DiscoveryOperation): VerificationResult {
        checkOperation(operation)
        val requestId = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
        fun negative(outcome: VerificationOutcome) = VerificationResult(requestId, generation,
            candidate.pageUrl, outcome, candidate.pageUrl, VerificationEvidence.NONE)
        if (VerificationUrlPolicy.candidate(candidate.pageUrl) == null) return negative(VerificationOutcome.REJECTED)
        val now = clock.nowMillis()
        val remaining = if (operation.context.deadlineEpochMillis <= now) 0 else
            (operation.context.deadlineEpochMillis - now).takeIf { it > 0 } ?: Long.MAX_VALUE
        val budget = minOf(deadlineMillis, remaining - 1_000)
        if (budget < 1) { checkOperation(operation); return negative(VerificationOutcome.TIMEOUT) }
        val deadline = plus(now, budget)
        val timedOut = AtomicBoolean(false)
        fun check() {
            checkOperation(operation)
            if (timedOut.get() || clock.nowMillis() >= deadline)
                throw VerificationFailure(VerificationError.DEADLINE_EXCEEDED)
        }
        check()
        if (!inFlight.compareAndSet(false, true)) return negative(VerificationOutcome.BUSY)
        var cleanup: ExchangeCleanup? = null
        var cancellation: CancellationHandle? = null
        var timeout: CancellationHandle? = null
        try {
            val maximum = minOf(32_768L, operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
            if (maximum < 1) throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
            val body = VerificationProtocol.request(requestId, generation, candidate.pageUrl, budget, dynamicAllowed)
            val http = VerificationHttpRequest(endpoint, endpoint.uri, mapOf(
                "Host" to endpoint.uri.rawAuthority, "Connection" to "close", "User-Agent" to VerificationIdentity.USER_AGENT,
                "Accept" to "application/json", "Accept-Encoding" to "identity", "Content-Type" to "application/json",
                "Content-Length" to body.size.toString()), body, deadline,
                minOf(5_000L, budget).toInt(), maximum)
            check()
            val call = transport.create(http)
            val resources = ExchangeCleanup(call) { inFlight.set(false) }
            cleanup = resources
            cancellation = operation.cancellation.onCancel { resources.start() }
            timeout = timer.schedule((deadline - clock.nowMillis()).coerceAtLeast(0)) {
                timedOut.set(true); resources.start()
            }
            check()
            val response = call.execute()
            resources.register(response)
            check()
            VerificationUrlPolicy.requireApi(endpoint, response.url)
            if (response.url.toString() != endpoint.url || response.status in 300..399)
                throw VerificationFailure(VerificationError.REDIRECT_REJECTED)
            val headers = headers(response)
            if (response.status != 200) return negative(when (response.status) {
                401, 403 -> VerificationOutcome.REJECTED
                429 -> VerificationOutcome.BUSY
                else -> VerificationOutcome.UNAVAILABLE
            }).also { check() }
            val contentType = headers["content-type"]?.lowercase(Locale.ROOT)?.split(';')?.map { it.trim() }
            if (contentType?.firstOrNull() != "application/json" || contentType.drop(1).size > 1 ||
                contentType.drop(1).any { it !in setOf("charset=utf-8", "charset=\"utf-8\"") }) invalidResponse()
            if (headers["content-encoding"]?.equals("identity", ignoreCase = true) == false) invalidResponse()
            val declared = headers["content-length"]?.let { value ->
                if (value.isEmpty() || value.any { it !in '0'..'9' }) invalidResponse()
                value.toLongOrNull() ?: throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
            }
            if (declared != null && declared > maximum) throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val allowance = minOf(buffer.size.toLong(), maximum - bytes.size() + 1).toInt()
                val count = response.body.read(buffer, 0, allowance)
                check()
                if (count < 0) break
                if (count == 0 || count > allowance) invalidResponse()
                if (bytes.size().toLong() + count > maximum) throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
                bytes.write(buffer, 0, count)
            }
            if (declared != null && declared != bytes.size().toLong()) invalidResponse()
            val result = VerificationProtocol.response(bytes.toByteArray(), requestId, generation,
                candidate.pageUrl, dynamicAllowed, ::check)
            check()
            return result
        } catch (failure: DiscoveryFailure) { throw failure
        } catch (failure: VerificationFailure) {
            checkOperation(operation)
            return negative(if (timedOut.get() || clock.nowMillis() >= deadline) VerificationOutcome.TIMEOUT else when (failure.code) {
                VerificationError.DEADLINE_EXCEEDED -> VerificationOutcome.TIMEOUT
                VerificationError.CANCELLED -> VerificationOutcome.CANCELLED
                VerificationError.BUSY -> VerificationOutcome.BUSY
                VerificationError.INVALID_REQUEST, VerificationError.INVALID_RESPONSE, VerificationError.UNSAFE_URL,
                VerificationError.REDIRECT_REJECTED, VerificationError.RESPONSE_TOO_LARGE, VerificationError.SERVICE_DENIED -> VerificationOutcome.REJECTED
                VerificationError.STALE_CONFIGURATION -> throw DiscoveryFailure(DiscoveryError.CANCELLED)
                VerificationError.SERVICE_ERROR -> VerificationOutcome.UNAVAILABLE
            })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DiscoveryFailure(DiscoveryError.CANCELLED)
        } catch (_: Exception) {
            checkOperation(operation)
            return negative(if (timedOut.get() || clock.nowMillis() >= deadline) VerificationOutcome.TIMEOUT else VerificationOutcome.UNAVAILABLE)
        } finally {
            timeout?.cancel(); cancellation?.cancel()
            cleanup?.let { it.start(); it.workerFinished() } ?: inFlight.set(false)
        }
    }

    private fun checkOperation(operation: DiscoveryOperation) {
        operation.check(clock.nowMillis())
        if (Thread.currentThread().isInterrupted || !isCurrent()) throw DiscoveryFailure(DiscoveryError.CANCELLED)
    }

    private fun headers(response: VerificationHttpResponse): Map<String, String> {
        if (response.headers.size > 100) invalidResponse()
        var bytes = 0; var fields = 0
        val result = linkedMapOf<String, String>()
        for ((name, values) in response.headers) {
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) || values.isEmpty() || values.size > 100) invalidResponse()
            for (value in values) {
                if (++fields > 100 || value.length > 32_768) invalidResponse()
                bytes += name.length + value.toByteArray(Charsets.UTF_8).size
                if (bytes > 32_768 || value.any { it != '\t' && it.code !in 32..126 }) invalidResponse()
            }
            val key = name.lowercase(Locale.ROOT)
            if (key in setOf("content-type", "content-length", "content-encoding")) {
                if (key in result || values.size != 1) invalidResponse()
                result[key] = values.single()
            }
        }
        return result
    }

    /** Never release admission while a late response or an uncooperative cleanup still exists. */
    private class ExchangeCleanup(private val call: VerificationHttpCall, private val release: () -> Unit) {
        private val lock = Any()
        private var closing = false
        private var response: VerificationHttpResponse? = null
        private val workerDone = AtomicBoolean(false)
        private val cleanupDone = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        fun register(value: VerificationHttpResponse) {
            val late = synchronized(lock) { if (closing) true else { response = value; false } }
            if (late) value.close()
        }
        fun start() {
            val value = synchronized(lock) {
                if (closing) return
                closing = true
                response.also { response = null }
            }
            Thread({
                try { try { call.cancel() } finally { value?.close() } }
                catch (_: Exception) { } catch (_: LinkageError) { }
                finally { cleanupDone.set(true); releaseIfDone() }
            }, "esv-verification-cleanup").apply { isDaemon = true; start() }
        }
        fun workerFinished() { workerDone.set(true); releaseIfDone() }
        private fun releaseIfDone() {
            if (workerDone.get() && cleanupDone.get() && released.compareAndSet(false, true)) release()
        }
    }
    override fun toString(): String = "VerificationServiceClient([redacted])"
    private companion object { val RANDOM = SecureRandom() }
}

private object SharedVerificationTimer : DiscoveryTimer {
    private val delegate by lazy { JvmDiscoveryTimer() }
    override fun schedule(delayMillis: Long, action: () -> Unit) = delegate.schedule(delayMillis, action)
}
