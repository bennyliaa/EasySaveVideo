package com.easysavevideo.searxng

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit HTML/general candidate protocol. No JSON fallback, result fetch, script or media authority. */
class SearxngHtmlSearchProvider(
    private val endpoint: SearxngEndpoint,
    private val transport: SearxngTransport,
    private val limits: SearxngLimits = SearxngLimits(maxPages = 2),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val timer: DiscoveryTimer = HtmlTimer,
) : VideoSearchProvider {
    init { require(endpoint.format == SearxngResultFormat.HTML_WEB) }
    override val descriptor = ProviderDescriptor(endpoint.id, endpoint.version, progressScope = ProviderProgressScope.SESSION)
    override val searchBudget = SourceSearchBudget(2, limits.maxResponseBytes)
    private val inFlight = AtomicBoolean(false)
    private val retryNotBefore = AtomicLong(0)

    override fun refusal(profile: SearchProfile): DiscoveryError? = when {
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
            if (timedOut.get() || clock.nowMillis() >= deadline) throw SearxngFailure(SearxngError.DEADLINE_EXCEEDED)
            if (operation.cancellation.isCancelled || Thread.currentThread().isInterrupted) throw SearxngFailure(SearxngError.CANCELLED)
        }
        check()
        if (!inFlight.compareAndSet(false, true)) throw SearxngFailure(SearxngError.BUSY)
        val resources = Resources { inFlight.set(false) }
        var cancellation: CancellationHandle? = null
        var timeout: CancellationHandle? = null
        try {
            cancellation = operation.cancellation.onCancel { resources.start() }
            timeout = timer.schedule((deadline - clock.nowMillis()).coerceAtLeast(0)) { timedOut.set(true); resources.start() }
            val maximum = minOf(limits.maxResponseBytes, operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
            if (maximum < 2) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            val notBefore = retryNotBefore.get()
            if (clock.nowMillis() < notBefore) throw SearxngFailure(SearxngError.RATE_LIMITED, notBefore)
            val language = request.profile.locale.takeIf {
                it.length <= 32 && it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            }?.lowercase(Locale.ROOT) ?: "en"
            val key = SearxngCursor.key(endpoint, request.profile.query, language, request.profile.queryKey, limits)
            val page = request.progress?.cursor?.let {
                SearxngCursor.decode(it, key, clock.nowMillis(), limits.maxPages, ::check)
            } ?: 1
            val parameters = linkedMapOf("q" to request.profile.query, "format" to "html", "categories" to "general",
                "pageno" to page.toString(), "language" to language, "safesearch" to "0")
            // Append only locally selected parameters; retain the configured deployment path verbatim.
            val uri = URI(endpoint.searchUrl + "?" + parameters.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            })
            // Robots lives at the origin root, even when the selected search endpoint is prefixed.
            val robotsUri = URI("https", endpoint.uri.rawAuthority, "/robots.txt", null, null)
            val robots = read(robotsUri, SearxngRequestKind.ROBOTS, minOf(32_768L, maximum - 1), deadline, resources, ::check)
            SearxngRobotsPolicy.requireAllowed(robots, endpoint, uri, ::check)
            check()
            val body = read(uri, SearxngRequestKind.HTML_SEARCH, maximum - robots.size, deadline, resources, ::check)
            val parsed = SearxngHtmlResponseParser.parse(body, endpoint, page, limits.maxCandidates, ::check)
            check()
            val exhausted = !parsed.hasNext || page >= limits.maxPages
            val expires = if (exhausted) null else plus(clock.nowMillis(), SearxngCursor.TTL_MILLIS)
            return ProviderPage(candidates = parsed.candidates,
                nextCursor = expires?.let { SearxngCursor.encode(page + 1, key, it) },
                cursorExpiresAtEpochMillis = expires, exhausted = exhausted,
                responseBytes = robots.size.toLong() + body.size)
        } catch (failure: SearxngFailure) {
            if (failure.code == SearxngError.CANCELLED) check()
            throw failure
        } catch (_: Exception) { check(); throw SearxngFailure(SearxngError.SERVICE_ERROR)
        } finally {
            timeout?.cancel(); cancellation?.cancel()
            resources.start(); resources.workerFinished()
        }
    }

    private fun read(uri: URI, kind: SearxngRequestKind, maximum: Long, deadline: Long,
        resources: Resources, check: () -> Unit): ByteArray {
        check()
        val request = SearxngHttpRequest(endpoint, uri, mapOf("Host" to endpoint.uri.host, "Connection" to "close",
            "User-Agent" to SearxngIdentity.USER_AGENT, "Accept" to kind.accept, "Accept-Encoding" to "identity"),
            deadline, minOf(5000L, deadline - clock.nowMillis()).coerceAtLeast(1).toInt(), maximum, kind)
        val call = transport.create(request)
        resources.register(call)
        check()
        val response = call.execute()
        resources.register(response)
        SearxngUrlPolicy.requireRequest(endpoint, response.url, kind)
        if (response.url != uri || response.status in 300..399) throw SearxngFailure(SearxngError.REDIRECT_REJECTED)
        val headers = headers(response)
        if (response.status == 429 || response.status == 503) {
            val until = SearxngRetryAfter.retryAt(headers["retry-after"], clock.nowMillis())
            retryNotBefore.accumulateAndGet(until) { old, new -> maxOf(old, new) }
            throw SearxngFailure(SearxngError.RATE_LIMITED, retryNotBefore.get())
        }
        if (response.status == 401 || response.status == 403) throw SearxngFailure(SearxngError.SERVICE_DENIED)
        check()
        if (response.status != 200) throw SearxngFailure(SearxngError.SERVICE_ERROR)
        val type = headers["content-type"]?.lowercase(Locale.ROOT)?.split(';')?.map { it.trim() }
        if (type?.firstOrNull() != kind.accept ||
            type.drop(1).any { it !in setOf("charset=utf-8", "charset=\"utf-8\"") }) invalid()
        if (headers["content-encoding"]?.equals("identity", ignoreCase = true) == false) invalid()
        val declared = headers["content-length"]?.let {
            if (it.isEmpty() || it.any { c -> c !in '0'..'9' }) invalid()
            it.toLongOrNull() ?: throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
        }
        if (declared != null && declared > maximum) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val room = maximum - bytes.size()
            if (room == 0L) {
                if (declared == maximum) break
                throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            }
            val allowance = minOf(buffer.size.toLong(), room).toInt()
            val count = response.body.read(buffer, 0, allowance)
            check()
            if (count < 0) break
            if (count == 0 || count > allowance) invalid()
            if (bytes.size().toLong() + count > maximum) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            bytes.write(buffer, 0, count)
        }
        if (declared != null && declared != bytes.size().toLong()) invalid()
        return bytes.toByteArray()
    }

    private fun headers(response: SearxngHttpResponse): Map<String, String> {
        if (response.headers.size > 100) invalid()
        var bytes = 0; var fields = 0
        val result = linkedMapOf<String, String>()
        for ((name, values) in response.headers) {
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) || values.size > 100) invalid()
            for (value in values) {
                if (++fields > 100 || value.length > 32768) invalid()
                bytes += name.length + value.toByteArray(Charsets.UTF_8).size
                if (bytes > 32768 || value.any { it != '\t' && it.code !in 32..126 }) invalid()
            }
            val key = name.lowercase(Locale.ROOT)
            if (key in setOf("www-authenticate", "proxy-authenticate") ||
                key == "cf-mitigated" && values.any { it.equals("challenge", ignoreCase = true) })
                throw SearxngFailure(SearxngError.SERVICE_DENIED)
            if (key in setOf("content-type", "content-length", "content-encoding", "retry-after")) {
                if (key in result || values.size != 1) invalid()
                result[key] = values.single()
            }
        }
        return result
    }

    /** Both calls and late responses retain physical ownership until worker and cleanup finish. */
    private class Resources(private val release: () -> Unit) {
        private val lock = Any()
        private val calls = arrayListOf<SearxngHttpCall>()
        private val responses = arrayListOf<SearxngHttpResponse>()
        private var closing = false
        private val workerDone = AtomicBoolean(false)
        private val cleanupDone = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        fun register(call: SearxngHttpCall) {
            val late = synchronized(lock) { if (closing) true else { check(calls.size < 2); calls.add(call); false } }
            if (late) { call.cancel(); throw SearxngFailure(SearxngError.CANCELLED) }
        }
        fun register(response: SearxngHttpResponse) {
            val late = synchronized(lock) { if (closing) true else { check(responses.size < 2); responses.add(response); false } }
            if (late) { response.close(); throw SearxngFailure(SearxngError.CANCELLED) }
        }
        fun start() {
            val owned = synchronized(lock) {
                if (closing) return
                closing = true
                calls.toList() to responses.toList()
            }
            Thread({
                try {
                    owned.first.forEach { try { it.cancel() } catch (_: Exception) { } catch (_: LinkageError) { } }
                    owned.second.forEach { try { it.close() } catch (_: Exception) { } catch (_: LinkageError) { } }
                } finally { cleanupDone.set(true); releaseIfDone() }
            }, "esv-searxng-html-cleanup").apply { isDaemon = true; start() }
        }
        fun workerFinished() { workerDone.set(true); releaseIfDone() }
        private fun releaseIfDone() {
            if (workerDone.get() && cleanupDone.get() && released.compareAndSet(false, true)) release()
        }
    }

    private fun invalid(): Nothing = throw SearxngFailure(SearxngError.INVALID_RESPONSE)
    private object HtmlTimer : DiscoveryTimer {
        private val delegate by lazy { JvmDiscoveryTimer() }
        override fun schedule(delayMillis: Long, action: () -> Unit) = delegate.schedule(delayMillis, action)
    }
}
