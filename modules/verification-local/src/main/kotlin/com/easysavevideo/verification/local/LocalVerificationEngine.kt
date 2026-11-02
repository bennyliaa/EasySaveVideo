package com.easysavevideo.verification.local

import com.easysavevideo.discovery.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Inline-only rendering: external script/subresource traffic is never authorized here. */
interface LocalDynamicRenderer {
    fun isPhysicallyIdle(): Boolean
    fun render(html: ByteArray, request: EngineRequest, operation: DiscoveryOperation): ByteArray?
}

/** One reserved HTTP exchange, including OkHttp follow-ups that would otherwise be implicit. */
internal class LocalSingleExchange { val started = AtomicBoolean() }

/** Original bounded B implementation; source admission is supplied by the composition root. */
class LocalVerificationEngine private constructor(private val renderer: LocalDynamicRenderer?,
    private val fixtureLoopbackOnly: Boolean,
    private val monotonicMillis: () -> Long,
    private val scriptedCallFactory: ((Request) -> Call)?) : VerificationEngine, AutoCloseable {
    /** Public construction always uses the production-constrained OkHttp transport. */
    constructor(renderer: LocalDynamicRenderer? = null, fixtureLoopbackOnly: Boolean = false,
        monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }) :
        this(renderer, fixtureLoopbackOnly, monotonicMillis, null)
    constructor() : this(null, false, { System.nanoTime() / 1_000_000 }, null)

    companion object {
        /**
         * Module-internal offline composition seam. Only immutable, per-instance Call creation changes.
         * It forces public mode with no renderer; real admission, robots, bounds and cleanup still run.
         * Scripted calls bypass DNS/TLS/interceptors and prove no live-network or wire-retry guarantees.
         * Internal/JvmSynthetic is source API hygiene, not a reflection security boundary.
         */
        @JvmSynthetic
        internal fun withScriptedPublicCalls(newCall: (Request) -> Call,
            monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }): LocalVerificationEngine =
            LocalVerificationEngine(null, false, monotonicMillis, newCall)
    }

    override val id = "android-local"
    override val capabilities = setOf(EngineCapability.STATIC_PAGE, EngineCapability.LOCAL_MATCHING) +
        if (renderer != null) setOf(EngineCapability.DYNAMIC_PAGE) else emptySet()
    override val sendsDataToAnotherProcess = false
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val activeCall = AtomicReference<Call?>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ESV-local-verification").apply { isDaemon = true } }
    private val parser = LocalHtmlEvidenceParser()
    private val refusedOrigins = ConcurrentHashMap.newKeySet<String>()
    override fun isAvailable() = !closed.get()
    override fun close() {
        closed.set(true)
        activeCall.get()?.let { try { it.cancel() } catch (_: Exception) { } }
        // Never discard a queued owner: its finally must release the physical slot.
        executor.shutdown()
    }
    override fun isPhysicallyIdle() = !busy.get() && (renderer?.isPhysicallyIdle() != false)
    private class Stopped(val outcome: EngineOutcome) : IOException("ESV_LOCAL_STOPPED")
    private data class ReadResult(val outcome: EngineOutcome? = null, val bytes: ByteArray? = null)

    override fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult {
        val calls = AtomicInteger()
        val readBytes = AtomicLong()
        val uncertainBytes = AtomicBoolean()
        val cap = minOf(request.maxBytes, 262144L)
        fun result(outcome: EngineOutcome, known: Boolean = true, evidence: EngineEvidence = EngineEvidence.NONE,
            raw: RawVideoResult? = null): EngineResult {
            // Preserve the old compiled/fixture full-cap reservation. New preflights share one cap;
            // rejected unread bodies/late owners conservatively reserve its remainder, never a second cap.
            val charged = if (calls.get() == 0) 0L else if (!request.network.requiresRobotsCheck || uncertainBytes.get() || !known)
                cap.coerceAtLeast(0) else readBytes.get()
            return EngineResult(request.requestId, request.generation, id, outcome, evidence, raw,
                calls.get(), charged, known)
        }
        fun state(): EngineOutcome? = when {
            operation.cancellation.isCancelled || Thread.currentThread().isInterrupted || closed.get() -> EngineOutcome.CANCELLED
            monotonicMillis() >= request.deadlineMonotonicMillis -> EngineOutcome.TIMEOUT
            !request.network.isCurrent(request.candidate, operation) -> EngineOutcome.DENIED
            else -> null
        }
        fun fence() { state()?.let { throw Stopped(it) } }
        val url = try { request.candidate.pageUrl.toHttpUrl() } catch (_: Exception) { return result(EngineOutcome.DENIED) }
        val origin = "${url.scheme}://${url.host}:${url.port}"
        if (origin !in request.network.allowedOrigins || origin in refusedOrigins || refusedOrigins.size >= 16 ||
            request.network.maxRedirects != 0 || ((request.network.requireTls || !fixtureLoopbackOnly) && !url.isHttps) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            request.candidate.pageUrl.length > 4096 ||
            (fixtureLoopbackOnly && (url.host != "127.0.0.1" || request.network.requiresRobotsCheck)) ||
            (request.network.requiresRobotsCheck && VerificationOriginPolicy.origin(request.candidate.pageUrl) != origin))
            return result(EngineOutcome.DENIED)
        // Numeric literals bypass OkHttp DNS. Public candidate admission excludes them as well.
        if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
            val address = try { InetAddress.getByName(url.host) } catch (_: Exception) { return result(EngineOutcome.DENIED) }
            if (if (fixtureLoopbackOnly) !address.isLoopbackAddress else !VerificationOriginPolicy.publicAddress(address.address))
                return result(EngineOutcome.DENIED)
        }
        if (closed.get()) return result(EngineOutcome.UNAVAILABLE)
        state()?.let { return result(it) }
        if (request.maxRequests < (if (request.network.requiresRobotsCheck) 2 else 1) || cap < 1)
            return result(EngineOutcome.UNAVAILABLE)
        if (!isPhysicallyIdle() || !busy.compareAndSet(false, true)) return result(EngineOutcome.UNAVAILABLE)
        val future = try { executor.submit<EngineResult> {
            var client: OkHttpClient? = null
            try {
                fence()
                val dns = LocalPublicDns(url.host, fixtureLoopbackOnly, ::fence)
                val transport = OkHttpClient.Builder().dns(dns).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
                    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                    .addNetworkInterceptor { chain ->
                        fence()
                        val wire = chain.request()
                        val once = wire.tag(LocalSingleExchange::class.java)
                        if (once == null || !once.started.compareAndSet(false, true) ||
                            wire.url.scheme != url.scheme || wire.url.host != url.host || wire.url.port != url.port ||
                            wire.header("Authorization") != null || wire.header("Proxy-Authorization") != null ||
                            wire.header("Cookie") != null) throw IOException("ESV_EXCHANGE_DENIED")
                        chain.proceed(wire)
                    }.build()
                client = transport
                // The test-only Call seam intentionally cannot establish DNS or TLS evidence.
                if (scriptedCallFactory == null) dns.lookup(url.host)
                fence()
                fun exchange(target: HttpUrl, robots: Boolean): ReadResult {
                    fence()
                    val left = cap - readBytes.get()
                    if (calls.get() >= request.maxRequests || left <= 0) return ReadResult(EngineOutcome.UNKNOWN)
                    val bodyCap = if (robots) minOf(left, LocalRobotsPolicy.MAX_BYTES.toLong()) else left
                    val wireRequest = Request.Builder().url(target).tag(LocalSingleExchange::class.java, LocalSingleExchange())
                        .header("Accept", if (robots) "text/plain" else "text/html")
                        .header("Accept-Encoding", "identity").header("User-Agent", LocalRobotsPolicy.USER_AGENT).get().build()
                    // Reserve before Call construction. A failing/foreign-null factory never falls back.
                    calls.incrementAndGet()
                    val call = if (scriptedCallFactory == null) transport.newCall(wireRequest) else scriptedCallFactory.invoke(wireRequest)
                    activeCall.set(call)
                    var response: Response? = null
                    try {
                        call.timeout().timeout((request.deadlineMonotonicMillis - monotonicMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                        fence()
                        val received = call.execute()
                        response = received
                        fence() // A late response cannot start a body read or become evidence.
                        val headerBytes = received.headers.toString().toByteArray(Charsets.UTF_8).size
                        if (headerBytes > request.network.maxHeaderBytes || received.request.url != target ||
                            listOf("Content-Type", "Content-Length", "Content-Encoding", "Transfer-Encoding").any { received.headers.values(it).size > 1 }) {
                            uncertainBytes.set(true); return ReadResult(EngineOutcome.DENIED)
                        }
                        if (received.code == 429) {
                            refusedOrigins.add(origin); uncertainBytes.set(true); return ReadResult(EngineOutcome.RATE_LIMITED)
                        }
                        if (received.code in 300..399 || received.code in setOf(401, 403, 407) ||
                            received.header("WWW-Authenticate") != null || received.header("Proxy-Authenticate") != null ||
                            received.header("cf-mitigated")?.equals("challenge", true) == true) {
                            refusedOrigins.add(origin); uncertainBytes.set(true); return ReadResult(EngineOutcome.DENIED)
                        }
                        // Robots only grants on exactly 200. A missing robots file is never permission.
                        if (received.code != 200) { uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN) }
                        val type = received.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                        val expectedType = if (robots) "text/plain" else "text/html"
                        val media = received.body?.contentType()
                        if (type != expectedType || media?.charset(Charsets.UTF_8) != Charsets.UTF_8 ||
                            received.header("Content-Encoding")?.equals("identity", true) == false) {
                            uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN)
                        }
                        val body = received.body ?: run { uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN) }
                        val length = body.contentLength()
                        val declaredLength = received.header("Content-Length")
                        // This is an application payload budget, not exact HTTP/TLS wire metering.
                        // OkHttp may prebuffer; unknown extents reserve the one full shared cap.
                        // They can succeed on EOF below the cap, never by reading a cap+1 probe.
                        if (length < 0) uncertainBytes.set(true)
                        val transfer = received.header("Transfer-Encoding")
                        if (transfer != null && (declaredLength != null || !transfer.equals("chunked", true))) {
                            uncertainBytes.set(true); return ReadResult(EngineOutcome.DENIED)
                        }
                        if (length < -1 || declaredLength?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } } == null && declaredLength != null ||
                            declaredLength?.toLongOrNull()?.let { length >= 0 && it != length } == true || length > bodyCap) {
                            uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN)
                        }
                        val buffer = ByteArrayOutputStream()
                        val stream = body.byteStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            fence()
                            val room = bodyCap - buffer.size()
                            // No cap+1 probe: unknown-length data exactly at the cap abstains.
                            if (room == 0L) {
                                if (length == bodyCap) break
                                uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN)
                            }
                            val n = stream.read(chunk, 0, minOf(chunk.size.toLong(), room).toInt())
                            if (n > 0) { readBytes.addAndGet(n.toLong()); buffer.write(chunk, 0, n) }
                            fence()
                            if (n < 0) break
                            if (n == 0) { uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN) }
                            if (length >= 0 && buffer.size().toLong() == length) break
                        }
                        if (length >= 0 && buffer.size().toLong() != length) {
                            uncertainBytes.set(true); return ReadResult(EngineOutcome.UNKNOWN)
                        }
                        val bytes = buffer.toByteArray()
                        val lower = bytes.toString(Charsets.UTF_8).lowercase(Locale.ROOT)
                        if (listOf("g-recaptcha", "h-captcha", "cf-turnstile", "verify you are human", "captcha",
                                "type=\"password\"", "type='password'", "type=password", "widevine", "encrypted-media").any(lower::contains)) {
                            refusedOrigins.add(origin); return ReadResult(EngineOutcome.DENIED)
                        }
                        return ReadResult(bytes = bytes)
                    } finally {
                        // Cancel before close: no response-close drain may create another body budget.
                        // Keep activeCall and the physical owner until closing actually completes.
                        try { call.cancel() } finally {
                            try { response?.close() } finally { activeCall.compareAndSet(call, null) }
                        }
                    }
                }
                if (request.network.requiresRobotsCheck) {
                    val robotsUrl = url.newBuilder().encodedPath("/robots.txt").query(null).fragment(null).build()
                    val preflight = exchange(robotsUrl, robots = true)
                    preflight.outcome?.let { return@submit result(it) }
                    val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                    when (LocalRobotsPolicy.evaluate(requireNotNull(preflight.bytes), path)) {
                        LocalRobotsPolicy.Decision.DENY -> return@submit result(EngineOutcome.DENIED)
                        LocalRobotsPolicy.Decision.UNKNOWN -> return@submit result(EngineOutcome.UNKNOWN)
                        LocalRobotsPolicy.Decision.ALLOW -> fence()
                    }
                }
                val page = exchange(url, robots = false)
                page.outcome?.let { return@submit result(it) }
                val bytes = requireNotNull(page.bytes)
                fence()
                val static = parser.parse(bytes, request.candidate.pageUrl)
                fence()
                if (static != null) return@submit result(EngineOutcome.PAGE_EVIDENCE, evidence = EngineEvidence.HTML_VIDEO, raw = static.raw)
                if (request.dynamicAllowed && renderer != null && request.deadlineMonotonicMillis - monotonicMillis() >= 250) {
                    fence()
                    val rendered = renderer.render(bytes, request, operation)
                    fence()
                    val dynamic = rendered?.takeIf { it.size <= cap }?.let { parser.parse(it, request.candidate.pageUrl) }
                    fence()
                    if (dynamic != null) return@submit result(EngineOutcome.PAGE_EVIDENCE, evidence = EngineEvidence.DYNAMIC_VIDEO, raw = dynamic.raw)
                }
                result(EngineOutcome.UNKNOWN)
            } catch (stopped: Stopped) {
                result(stopped.outcome)
            } catch (_: Exception) {
                uncertainBytes.set(true)
                result(state() ?: EngineOutcome.ERROR)
            } finally {
                try {
                    activeCall.get()?.let { try { it.cancel() } catch (_: Exception) { } }
                    client?.connectionPool?.evictAll()
                    client?.dispatcher?.executorService?.shutdown()
                } finally { activeCall.set(null); busy.set(false) }
            }
        } } catch (_: RejectedExecutionException) {
            busy.set(false)
            return result(EngineOutcome.UNAVAILABLE)
        }
        while (true) {
            state()?.let { outcome ->
                activeCall.get()?.let { try { it.cancel() } catch (_: Exception) { } }
                return result(outcome, known = false)
            }
            try {
                val completed = future.get(10, TimeUnit.MILLISECONDS)
                // Session revocation and cancellation beat even a completed evidence result.
                state()?.let { return result(it) }
                return completed
            } catch (_: TimeoutException) { }
            catch (_: InterruptedException) {
                activeCall.get()?.let { try { it.cancel() } catch (_: Exception) { } }
                Thread.currentThread().interrupt()
                return result(EngineOutcome.CANCELLED, known = false)
            } catch (_: Exception) {
                activeCall.get()?.let { try { it.cancel() } catch (_: Exception) { } }
                return result(EngineOutcome.ERROR, known = false)
            }
        }
    }
}
