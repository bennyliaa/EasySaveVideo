package com.easysavevideo.commons

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.DiscoveryTimer
import com.easysavevideo.discovery.JvmDiscoveryTimer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

/**
 * Original, anonymous Commons metadata client. CommonsVideoSearchProvider preserves rights through
 * the generic Discovery SPI. Execute through an isolated runtime, never an Android UI thread.
 * One instance is owned by the provider registry; it admits at most one physical exchange at a time.
 * It neither fetches thumbnails/video nor exposes an original/derivative media URL to SaveEngine.
 */
class CommonsMetadataProvider(
    private val config: CommonsClientConfig = CommonsClientConfig(),
    private val transport: CommonsTransport? = null,
    private val limits: CommonsLimits = CommonsLimits(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val timer: DiscoveryTimer = SharedCommonsTimer,
    private val maintenance: CommonsMaintenance? = null,
) {
    internal val requestDeadlineMillis: Long get() = limits.deadlineMillis
    private data class CacheEntry(val page: CommonsMetadataPage, val expires: Long)
    private val inFlight = AtomicBoolean(false)
    private val cache = linkedMapOf<String, CacheEntry>()
    private var cacheBytes = 0L
    private var cacheNamespace: String? = null
    @Volatile private var retryNotBefore = 0L

    /** Deliberately synchronous backend contract. Runtime owns background execution and callbacks. */
    fun search(request: CommonsSearchRequest, operation: DiscoveryOperation): CommonsMetadataPage {
        val lease = maintenance?.acquire(operation.cancellation)
        try {
            lease?.check()
            val effective = lease?.limits?.let { shrinkLimits(limits, it) } ?: limits
            val deadline = minOf(operation.context.deadlineEpochMillis, plus(clock.nowMillis(), effective.deadlineMillis))
            fun checkCompletion() {
                if (operation.cancellation.isCancelled || Thread.currentThread().isInterrupted)
                    throw CommonsFailure(CommonsError.CANCELLED)
                if (clock.nowMillis() >= deadline) throw CommonsFailure(CommonsError.DEADLINE_EXCEEDED)
                lease?.check()
            }
            var freshExchangeStarted = false
            try {
                val result = search(request, operation, effective, lease, deadline) { freshExchangeStarted = true }
                checkCompletion()
                // Re-reading the same cached page is not an independent exercise of a candidate.
                if (!result.fromCache) lease?.succeeded(::checkCompletion)
                checkCompletion()
                return result
            } catch (failure: CommonsFailure) {
                if (freshExchangeStarted && failure.code in OBSERVABLE_FAILURES && !operation.cancellation.isCancelled &&
                    !Thread.currentThread().isInterrupted && clock.nowMillis() < deadline) {
                    // A failed health write or cancellation cannot turn a failed request into success.
                    // The lease performs its own exact-authority and cancellation checks.
                    lease?.failed(failure.code, failure.structureFailure, ::checkCompletion)
                }
                throw failure
            }
        } finally { lease?.close() }
    }
    private fun search(request: CommonsSearchRequest, operation: DiscoveryOperation, limits: CommonsLimits,
                       lease: CommonsMaintenanceLease?, deadline: Long, exchangeStarted: () -> Unit): CommonsMetadataPage {
        val userAgent = config.userAgent()
        if (request.safeSearchRequired) throw CommonsFailure(CommonsError.UNSUPPORTED_FILTER)
        val actualTransport = transport ?: throw CommonsFailure(CommonsError.TRANSPORT_UNAVAILABLE)
        val maximumBytes = minOf(limits.maxResponseBytes, operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
        val timedOut = AtomicBoolean(false)
        fun check() {
            lease?.check()
            if (timedOut.get()) throw CommonsFailure(CommonsError.DEADLINE_EXCEEDED)
            if (operation.cancellation.isCancelled || Thread.currentThread().isInterrupted) throw CommonsFailure(CommonsError.CANCELLED)
            if (clock.nowMillis() >= deadline) throw CommonsFailure(CommonsError.DEADLINE_EXCEEDED)
        }
        check()
        if (!inFlight.compareAndSet(false, true)) throw CommonsFailure(CommonsError.BUSY)
        try {
            val namespace = lease?.cacheNamespace ?: "bundled"
            if (cacheNamespace != namespace) { clearCache(); cacheNamespace = namespace }
            val key = CommonsCursor.queryKey(request, limits.pageSize)
            val continuation = request.cursor?.let { CommonsCursor.decode(it, key, clock.nowMillis()) } ?: emptyMap()
            val cacheKey = (lease?.cacheNamespace ?: "bundled") + ":" + key + ":" + (request.cursor ?: "")
            cached(cacheKey, clock.nowMillis())?.let {
                if (it.page.responseBytes > maximumBytes) throw CommonsFailure(CommonsError.RESPONSE_TOO_LARGE)
                check(); return it.page.detached().copy(responseBytes = 0, fromCache = true)
            }
            if (clock.nowMillis() < retryNotBefore) throw CommonsFailure(CommonsError.RATE_LIMITED, retryNotBefore)
            val parameters = parameters(request, limits).apply { putAll(continuation) }
            val query = parameters.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
            val uri = URI(CommonsIdentity.API + "?" + query)
            CommonsUrlPolicy.requireApi(uri)
            val http = CommonsHttpRequest(uri, mapOf("User-Agent" to userAgent, "Accept" to "application/json",
                "Accept-Encoding" to "identity", "Host" to "commons.wikimedia.org", "Connection" to "close"), deadline, minOf(5_000L, deadline - clock.nowMillis()).coerceAtLeast(1).toInt(), maximumBytes)
            val call = try { actualTransport.create(http) } // No I/O until cancellation is attached.
                catch (_: Exception) { check(); throw CommonsFailure(CommonsError.SERVICE_ERROR) }
            val localCancellation = DiscoveryCancellation()
            val cancellationFinished = CountDownLatch(1)
            val transportCancellation = localCancellation.onCancel {
                try { call.cancel() } finally { cancellationFinished.countDown() }
            }
            var userCancellation: CancellationHandle? = null
            var deadlineHandle: CancellationHandle? = null
            try {
                userCancellation = operation.cancellation.onCancel { localCancellation.cancel() }
                deadlineHandle = timer.schedule((deadline - clock.nowMillis()).coerceAtLeast(0)) {
                    timedOut.set(true); localCancellation.cancel()
                }
                check()
                exchangeStarted()
                val response = call.execute()
                response.use {
                    check()
                    CommonsUrlPolicy.requireApi(response.url)
                    if (response.url != uri) throw CommonsFailure(CommonsError.REDIRECT_REJECTED)
                    val headers = headers(response)
                    if (response.status in 300..399) throw CommonsFailure(CommonsError.REDIRECT_REJECTED)
                    if (response.status == 429 || response.status == 503) rateLimit(headers["retry-after"], clock.nowMillis())
                    if (response.status == 401 || response.status == 403) throw CommonsFailure(CommonsError.SERVICE_DENIED)
                    if (response.status != 200) throw CommonsFailure(CommonsError.SERVICE_ERROR)
                    val contentType = headers["content-type"]?.lowercase()?.split(';')?.map { it.trim() }
                    if (contentType?.firstOrNull() != "application/json" ||
                        contentType.drop(1).any { it !in setOf("charset=utf-8", "charset=\"utf-8\"") }) invalidResponse()
                    if (headers["content-encoding"]?.lowercase()?.let { it != "identity" } == true) invalidResponse()
                    val declared = headers["content-length"]?.let { value ->
                        value.toLongOrNull()?.takeIf { it >= 0 } ?: invalidResponse()
                    }
                    if (declared != null && declared > maximumBytes) throw CommonsFailure(CommonsError.RESPONSE_TOO_LARGE)
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val allowance = minOf(buffer.size.toLong(), maximumBytes - bytes.size() + 1).toInt()
                        val count = response.body.read(buffer, 0, allowance)
                        check()
                        if (count < 0) break
                        if (count == 0) invalidResponse() // Do not spin on a broken zero-progress body.
                        if (bytes.size().toLong() + count > maximumBytes) throw CommonsFailure(CommonsError.RESPONSE_TOO_LARGE)
                        bytes.write(buffer, 0, count)
                    }
                    if (declared != null && declared != bytes.size().toLong()) invalidResponse()
                    val now = clock.nowMillis()
                    val page = try {
                        CommonsResponseParser(limits.pageSize).parse(bytes.toByteArray(), key, now, plus(now, 5 * 60_000L), ::check)
                    } catch (failure: CommonsFailure) {
                        if (failure.code == CommonsError.RATE_LIMITED) rateLimit(headers["retry-after"], now)
                        throw failure
                    }
                    check()
                    headers["retry-after"]?.let { retryNotBefore = maxOf(retryNotBefore, CommonsRetryAfter.retryAt(it, now)) }
                    store(cacheKey, page, cacheExpiry(headers, now, limits), limits)
                    return page.detached()
                }
            } catch (failure: CommonsFailure) { throw failure
            } catch (_: Exception) { check(); throw CommonsFailure(CommonsError.SERVICE_ERROR)
            } finally {
                deadlineHandle?.cancel(); userCancellation?.cancel()
                // Finish socket cancellation before releasing the physical provider slot. A broken
                // adapter cannot turn repeated timed-out searches into unbounded cancellation work.
                if (!localCancellation.isCancelled) localCancellation.cancel()
                var interrupted = false
                while (true) {
                    try { cancellationFinished.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
                transportCancellation.cancel()
                if (interrupted) Thread.currentThread().interrupt()
            }
        } finally { inFlight.set(false) }
    }
    fun clearCache() { synchronized(cache) { cache.clear(); cacheBytes = 0 } }
    private fun cached(key: String, now: Long): CacheEntry? = synchronized(cache) {
        val old = cache.entries.filter { it.value.expires <= now }
        old.forEach { cacheBytes -= it.value.page.responseBytes; cache.remove(it.key) }
        cache[key]
    }
    private fun store(key: String, page: CommonsMetadataPage, expiry: Long?, limits: CommonsLimits) {
        if (expiry == null || limits.cacheEntries == 0 || page.responseBytes > limits.maxResponseBytes) return
        synchronized(cache) {
            cache.remove(key)?.let { cacheBytes -= it.page.responseBytes }
            while (cache.isNotEmpty() && (cache.size >= limits.cacheEntries || cacheBytes + page.responseBytes > limits.maxResponseBytes)) {
                val oldest = cache.entries.first(); cacheBytes -= oldest.value.page.responseBytes; cache.remove(oldest.key)
            }
            cache[key] = CacheEntry(page.detached(), expiry); cacheBytes += page.responseBytes
        }
    }
    private fun cacheExpiry(headers: Map<String, String>, now: Long, limits: CommonsLimits): Long? {
        val directives = headers["cache-control"]?.lowercase()?.split(',')?.map { it.trim() } ?: emptyList()
        if (limits.cacheTtlMillis == 0L || directives.any { it in setOf("no-store", "no-cache", "private") }) return null
        val ages = directives.filter { it.startsWith("max-age=") }
        if (ages.size > 1) return null
        val age = if (ages.isEmpty()) limits.cacheTtlMillis else
            ages.single().substringAfter('=').trim('"').toLongOrNull()?.takeIf { it >= 0 }
                ?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: return null
        val ttl = minOf(limits.cacheTtlMillis, age)
        return if (ttl > 0) plus(now, ttl) else null
    }
    private fun headers(response: CommonsHttpResponse): Map<String, String> {
        if (response.headers.size > 100) invalidResponse()
        val result = linkedMapOf<String, String>(); var bytes = 0; var fields = 0
        for ((name, values) in response.headers) {
            if (!name.matches(Regex("[A-Za-z0-9-]{1,128}")) || values.size > 100) invalidResponse()
            for (value in values) {
                if (++fields > 100) invalidResponse()
                bytes += name.length + value.toByteArray(Charsets.UTF_8).size
                if (bytes > 32_768 || value.any { it == '\r' || it == '\n' || it.code == 0 }) invalidResponse()
            }
            val lower = name.lowercase()
            if (lower in setOf("content-type", "content-length", "content-encoding", "retry-after", "cache-control")) {
                if (lower in result || values.size != 1) invalidResponse()
                result[lower] = values.single()
            }
            // Set-Cookie/Authorization and every unneeded header are discarded, never persisted.
        }
        return result
    }
    private fun rateLimit(value: String?, now: Long): Nothing {
        retryNotBefore = maxOf(retryNotBefore, CommonsRetryAfter.retryAt(value, now))
        throw CommonsFailure(CommonsError.RATE_LIMITED, retryNotBefore)
    }
    private fun parameters(request: CommonsSearchRequest, limits: CommonsLimits): LinkedHashMap<String, String> = linkedMapOf(
        "action" to "query", "format" to "json", "formatversion" to "2", "generator" to "search",
        "gsrsearch" to "(${request.query}) filetype:video", "gsrnamespace" to "6", "gsrlimit" to limits.pageSize.toString(),
        "prop" to "videoinfo|info", "inprop" to "url", "viprop" to "mime|mediatype|size|url|extmetadata",
        "vilimit" to "1", "viurlwidth" to "320", "viextmetadatalanguage" to request.language,
        "viextmetadatafilter" to "Artist|Credit|Attribution|LicenseShortName|LicenseUrl|UsageTerms|Copyrighted|AttributionRequired|NonFree|Restrictions|DeletionReason",
        "maxlag" to "5",
    )
    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())
    private fun plus(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    companion object {
        private val OBSERVABLE_FAILURES = setOf(CommonsError.INVALID_RESPONSE, CommonsError.UNSAFE_URL,
            CommonsError.REDIRECT_REJECTED, CommonsError.SERVICE_DENIED, CommonsError.SERVICE_ERROR,
            CommonsError.RATE_LIMITED, CommonsError.RESPONSE_TOO_LARGE)
    }
}
private object SharedCommonsTimer : DiscoveryTimer {
    private val delegate by lazy { JvmDiscoveryTimer() }
    override fun schedule(delayMillis: Long, action: () -> Unit) = delegate.schedule(delayMillis, action)
}
