package com.easysavevideo.searxng

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Original first-layer SearXNG JSON client. It queries only the configured endpoint, never fetches
 * thumbnails or result links, and provides no media, SaveEngine or video-confirmation authority.
 * One instance admits one physical request including all late workers and resource cleanup.
 */
class SearxngVideoSearchProvider(
    private val endpoint: SearxngEndpoint,
    private val transport: SearxngTransport,
    private val limits: SearxngLimits = SearxngLimits(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val timer: DiscoveryTimer = SharedSearxngTimer,
) : VideoSearchProvider {
    override val descriptor = ProviderDescriptor(endpoint.id, endpoint.version, progressScope = ProviderProgressScope.SESSION)
    private val inFlight = AtomicBoolean(false)
    private val retryNotBefore = AtomicLong(0)

    override fun refusal(profile: SearchProfile): DiscoveryError? = when {
        // All configured services are UNKNOWN. Never claim support or silently weaken this filter.
        profile.filters.safeSearch -> DiscoveryError.UNSUPPORTED_FILTER
        profile.query.isBlank() || profile.query.length > 512 || !isWellFormedUnicode(profile.query) ||
            profile.query.any { it.isISOControl() } -> DiscoveryError.INVALID_QUERY
        else -> null
    }

    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
        refusal(request.profile)?.let { throw DiscoveryFailure(it) }
        if (request.profile.filters.sourceNamespaces.isNotEmpty() &&
            request.profile.filters.sourceNamespaces.none { it.equals(endpoint.id, ignoreCase = true) })
            return ProviderPage(exhausted = true)
        try { return exchange(request, operation) }
        catch (failure: SearxngFailure) { throw failure.discoveryFailure() }
    }

    private fun exchange(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
        val deadline = minOf(operation.context.deadlineEpochMillis, plus(clock.nowMillis(), limits.deadlineMillis))
        val timedOut = AtomicBoolean(false)
        fun check() {
            if (timedOut.get()) throw SearxngFailure(SearxngError.DEADLINE_EXCEEDED)
            if (operation.cancellation.isCancelled || Thread.currentThread().isInterrupted)
                throw SearxngFailure(SearxngError.CANCELLED)
            if (clock.nowMillis() >= deadline) throw SearxngFailure(SearxngError.DEADLINE_EXCEEDED)
        }
        check()
        if (!inFlight.compareAndSet(false, true)) throw SearxngFailure(SearxngError.BUSY)
        var cleanup: ExchangeCleanup? = null
        var cancellation: CancellationHandle? = null
        var timeout: CancellationHandle? = null
        try {
            val maximum = minOf(limits.maxResponseBytes, operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
            if (maximum < 1) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            val language = request.profile.locale.takeIf {
                it.length <= 32 && it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            }?.lowercase(Locale.ROOT) ?: "en"
            val key = SearxngCursor.key(endpoint, request.profile.query, language, request.profile.queryKey, limits)
            val page = request.progress?.cursor?.let {
                SearxngCursor.decode(it, key, clock.nowMillis(), limits.maxPages, ::check)
            } ?: 1
            val cooldown = retryNotBefore.get()
            if (clock.nowMillis() < cooldown) throw SearxngFailure(SearxngError.RATE_LIMITED, cooldown)
            val parameters = linkedMapOf("q" to request.profile.query, "format" to "json", "categories" to "videos",
                "pageno" to page.toString(), "language" to language, "safesearch" to "0")
            val uri = URI(endpoint.searchUrl + "?" + parameters.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            })
            val http = SearxngHttpRequest(endpoint, uri, mapOf("Host" to endpoint.uri.host, "Connection" to "close",
                "User-Agent" to SearxngIdentity.USER_AGENT, "Accept" to "application/json", "Accept-Encoding" to "identity"),
                deadline, minOf(5000L, deadline - clock.nowMillis()).coerceAtLeast(1).toInt(), maximum)
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
            SearxngUrlPolicy.requireApi(endpoint, response.url)
            if (response.url != uri || response.status in 300..399) throw SearxngFailure(SearxngError.REDIRECT_REJECTED)
            val headers = headers(response)
            // Persist slowdown before body/close and before a later timeout can obscure the headers.
            if (response.status == 429 || response.status == 503) {
                val until = SearxngRetryAfter.retryAt(headers["retry-after"], clock.nowMillis())
                retryNotBefore.accumulateAndGet(until) { old, new -> maxOf(old, new) }
                throw SearxngFailure(SearxngError.RATE_LIMITED, retryNotBefore.get())
            }
            if (response.status == 401 || response.status == 403) throw SearxngFailure(SearxngError.SERVICE_DENIED)
            check()
            if (response.status != 200) throw SearxngFailure(SearxngError.SERVICE_ERROR)
            val contentType = headers["content-type"]?.lowercase(Locale.ROOT)?.split(';')?.map { it.trim() }
            if (contentType?.firstOrNull() != "application/json" ||
                contentType.drop(1).any { it !in setOf("charset=utf-8", "charset=\"utf-8\"") }) invalidResponse()
            if (headers["content-encoding"]?.equals("identity", ignoreCase = true) == false) invalidResponse()
            val declared = headers["content-length"]?.let { value ->
                if (value.isEmpty() || value.any { it !in '0'..'9' }) invalidResponse()
                value.toLongOrNull() ?: throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            }
            if (declared != null && declared > maximum) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val allowance = minOf(buffer.size.toLong(), maximum - bytes.size() + 1).toInt()
                val count = response.body.read(buffer, 0, allowance)
                check()
                if (count < 0) break
                if (count == 0 || count > allowance) invalidResponse()
                if (bytes.size().toLong() + count > maximum) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
                bytes.write(buffer, 0, count)
            }
            if (declared != null && declared != bytes.size().toLong()) invalidResponse()
            val candidates = SearxngResponseParser.parse(bytes.toByteArray(), limits.maxCandidates, ::check)
            check()
            val exhausted = candidates.isEmpty() || page >= limits.maxPages
            val expires = if (exhausted) null else plus(clock.nowMillis(), SearxngCursor.TTL_MILLIS)
            return ProviderPage(candidates = candidates,
                nextCursor = expires?.let { SearxngCursor.encode(page + 1, key, it) },
                cursorExpiresAtEpochMillis = expires, exhausted = exhausted, responseBytes = bytes.size().toLong())
        } catch (failure: SearxngFailure) { throw failure
        } catch (_: Exception) { check(); throw SearxngFailure(SearxngError.SERVICE_ERROR)
        } finally {
            timeout?.cancel(); cancellation?.cancel()
            cleanup?.let { it.start(); it.workerFinished() } ?: inFlight.set(false)
        }
    }

    private fun headers(response: SearxngHttpResponse): Map<String, String> {
        if (response.headers.size > 100) invalidResponse()
        var bytes = 0; var fields = 0
        val result = linkedMapOf<String, String>()
        for ((name, values) in response.headers) {
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) || values.size > 100) invalidResponse()
            for (value in values) {
                if (++fields > 100 || value.length > 32768) invalidResponse()
                bytes += name.length + value.toByteArray(Charsets.UTF_8).size
                if (bytes > 32768 || value.any { it != '\t' && it.code !in 32..126 }) invalidResponse()
            }
            val key = name.lowercase(Locale.ROOT)
            if (key in setOf("content-type", "content-length", "content-encoding", "retry-after")) {
                if (key in result || values.size != 1) invalidResponse()
                result[key] = values.single()
            }
        }
        return result
    }

    /** A blocked cancel/close cannot block reporting an HTTP failure or free physical admission. */
    private class ExchangeCleanup(private val call: SearxngHttpCall, private val release: () -> Unit) {
        private val lock = Any()
        private var closing = false
        private var response: SearxngHttpResponse? = null
        private val workerDone = AtomicBoolean(false)
        private val cleanupDone = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        fun register(value: SearxngHttpResponse) {
            val late = synchronized(lock) { if (closing) true else { response = value; false } }
            if (late) value.close() // The still-held worker owns any resource returned after cancellation.
        }
        fun start() {
            val value = synchronized(lock) {
                if (closing) return
                closing = true
                response.also { response = null }
            }
            Thread({
                try {
                    try { call.cancel() } finally { value?.close() }
                } catch (_: Exception) { } catch (_: LinkageError) { }
                finally { cleanupDone.set(true); releaseIfDone() }
            }, "esv-searxng-cleanup").apply { isDaemon = true; start() }
        }
        fun workerFinished() { workerDone.set(true); releaseIfDone() }
        private fun releaseIfDone() {
            if (workerDone.get() && cleanupDone.get() && released.compareAndSet(false, true)) release()
        }
    }
}

private fun SearxngFailure.discoveryFailure(): DiscoveryFailure = DiscoveryFailure(when (code) {
    SearxngError.UNSUPPORTED_FILTER -> DiscoveryError.UNSUPPORTED_FILTER
    SearxngError.INVALID_REQUEST -> DiscoveryError.INVALID_QUERY
    SearxngError.CANCELLED -> DiscoveryError.CANCELLED
    SearxngError.DEADLINE_EXCEEDED -> DiscoveryError.DEADLINE_EXCEEDED
    SearxngError.RESPONSE_TOO_LARGE -> DiscoveryError.RESPONSE_TOO_LARGE
    SearxngError.TOO_MANY_CANDIDATES -> DiscoveryError.TOO_MANY_CANDIDATES
    SearxngError.RATE_LIMITED -> DiscoveryError.RATE_LIMITED
    SearxngError.BUSY -> DiscoveryError.BUSY
    else -> DiscoveryError.PROVIDER_FAILURE
}, code in setOf(SearxngError.RATE_LIMITED, SearxngError.BUSY, SearxngError.SERVICE_ERROR), retryAtEpochMillis)

private object SharedSearxngTimer : DiscoveryTimer {
    private val delegate by lazy { JvmDiscoveryTimer() }
    override fun schedule(delayMillis: Long, action: () -> Unit) = delegate.schedule(delayMillis, action)
}
