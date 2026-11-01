package com.easysavevideo.app.discovery.network

import com.easysavevideo.searxng.SearxngError
import com.easysavevideo.searxng.SearxngFailure
import com.easysavevideo.searxng.SearxngHttpCall
import com.easysavevideo.searxng.SearxngHttpRequest
import com.easysavevideo.searxng.SearxngHttpResponse
import com.easysavevideo.searxng.SearxngRetryAfter
import com.easysavevideo.searxng.SearxngTransport
import com.easysavevideo.searxng.SearxngUrlPolicy
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Original anonymous, single-exchange HTTP/1.1 adapter bound to the selected SearXNG endpoint.
 * It never uses URLConnection, ambient
 * CookieHandler/Authenticator/ProxySelector, browser state, connection pools, or global TLS defaults.
 * The public constructor is the production boundary; the internal constructor is for offline tests.
 *
 * One instance has one physical slot. Cancellation/deadline completes execute promptly, while a
 * stuck resolver, socket operation, or close retains that slot until both I/O and cleanup exit.
 * Socket closure never runs on the caller, UI, cancellation, or timer thread. The small bounded
 * successful body is collected before publishing it. Denial/slowdown headers finish logically
 * before cleanup, preserving Retry-After even if close stalls; no live socket escapes the adapter.
 * Process ownership includes the exact HTTPS origin across all slots and protocol choices.
 * Cooldown survives slot edits and physical cleanup; the bounded registry never evicts live cooldowns.
 */
class SystemSearxngTransport internal constructor(
    private val resolver: SearxngDnsResolver,
    private val sockets: SearxngSockets,
    private val clock: SearxngTransportClock = SystemSearxngClock,
    private val timer: SearxngTransportTimer = SystemSearxngTimer,
    private val originAdmission: SearxngOriginAdmission = SearxngOriginAdmission(),
) : SearxngTransport {
    constructor() : this(SystemSearxngDns, SystemSearxngSockets(), originAdmission = ProcessSearxngOriginAdmission.shared)

    private class Outcome(val value: Result<SearxngHttpResponse>, val definitiveHeaders: Boolean = false)
    private val active = AtomicReference<Call?>()

    override fun create(request: SearxngHttpRequest): SearxngHttpCall {
        SearxngUrlPolicy.requireRequest(request.endpoint, request.url, request.kind)
        if (request.url.host != request.endpoint.uri.host || request.url.toASCIIString().length > 16_384 ||
            request.connectTimeoutMillis !in 1..5_000 || request.maxBodyBytes !in 1..MAX_BODY) fail(SearxngError.INVALID_REQUEST)
        val headers = LinkedHashMap(request.headers)
        if (headers.size != 5 || headers.keys.map { it.lowercase(Locale.ROOT) }.toSet() != REQUEST_HEADERS ||
            headers.keys.any { !it.matches(Regex("[A-Za-z-]{1,32}")) } ||
            headers.values.any { it.length !in 1..512 || it.any { c -> c.code !in 32..126 } }) fail(SearxngError.INVALID_REQUEST)
        val values = headers.mapKeys { it.key.lowercase(Locale.ROOT) }
        if (values["host"] != request.endpoint.uri.host || values["connection"] != "close" || values["accept"] != request.kind.accept ||
            values["accept-encoding"] != "identity" || values["user-agent"] != USER_AGENT)
            fail(SearxngError.INVALID_REQUEST)
        val target = request.url.rawPath + (request.url.rawQuery?.let { "?" + it } ?: "")
        if (target.any { it.code !in 33..126 }) fail(SearxngError.INVALID_REQUEST)
        val bytes = buildString {
            append("GET ").append(target).append(" HTTP/1.1\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return Call(request, bytes)
    }

    private inner class Call(private val request: SearxngHttpRequest, private val requestBytes: ByteArray) : SearxngHttpCall {
        private val host = request.endpoint.uri.host
        private var originLease: SearxngOriginAdmission.Lease? = null
        private val started = AtomicBoolean(false)
        private val result = AtomicReference<Outcome?>()
        private val terminal = CountDownLatch(1)
        private val failure = AtomicReference<SearxngError?>()
        private val workerDone = AtomicBoolean(false)
        private val cleanupDone = AtomicBoolean(false)
        private val cleanupStarted = AtomicBoolean(false)
        private val resources = Any()
        private var raw: Socket? = null
        private var tls: SSLSocket? = null
        private var closing = false
        private var workerResult: Result<SearxngHttpResponse>? = null
        @Volatile private var monotonicDeadline = Long.MAX_VALUE
        @Volatile private var absoluteTimer: AutoCloseable? = null
        @Volatile private var connectTimer: AutoCloseable? = null

        override fun execute(): SearxngHttpResponse {
            if (!started.compareAndSet(false, true)) fail(SearxngError.INVALID_REQUEST)
            failure.get()?.let(::fail)
            val duration = minOf(15_000L, remainingEpoch())
            if (duration <= 0) fail(SearxngError.DEADLINE_EXCEEDED)
            monotonicDeadline = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration)
            if (!active.compareAndSet(null, this)) fail(SearxngError.BUSY)
            try {
                originLease = originAdmission.acquire(host, clock.epochMillis())
            } catch (failure: SearxngFailure) { active.compareAndSet(this, null); throw failure }
            try {
                absoluteTimer = timer.schedule(duration) { stop(SearxngError.DEADLINE_EXCEEDED) }
                Thread({ runExchange() }, "esv-searxng-http").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                workerDone.set(true)
                stop(SearxngError.SERVICE_ERROR)
                cleanup()
                finishIfReleased()
            }
            try { terminal.await() }
            catch (_: InterruptedException) {
                stop(SearxngError.CANCELLED)
                Thread.currentThread().interrupt()
                fail(SearxngError.CANCELLED)
            }
            val outcome = result.get()!!
            // A bounded slowdown/denial header response is logically complete before cleanup.
            // Later deadline/cancellation of stuck close cannot erase its server retry time.
            if (!outcome.definitiveHeaders) check()
            return outcome.value.getOrThrow()
        }

        override fun cancel() { stop(SearxngError.CANCELLED) }

        private fun runExchange() {
            workerResult = try { Result.success(exchange()) }
            catch (problem: SearxngFailure) { Result.failure(problem) }
            catch (_: Exception) { Result.failure(SearxngFailure(failure.get() ?: SearxngError.SERVICE_ERROR)) }
            catch (_: LinkageError) { Result.failure(SearxngFailure(SearxngError.SERVICE_ERROR)) }
            finally {
                connectTimer?.close()
                cleanup()
            }
            workerDone.set(true)
            finishIfReleased()
        }

        private fun exchange(): SearxngHttpResponse {
            check()
            val answers = resolver.resolve(host).toList()
            check()
            if (answers.isEmpty() || answers.any { !SearxngUrlPolicy.isPublicAddress(it) }) fail(SearxngError.UNSAFE_URL)
            val address = answers.first()
            val connectDuration = minOf(request.connectTimeoutMillis.toLong(), remainingMillis())
            val connectEnd = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectDuration)
            connectTimer = timer.schedule(connectDuration) { stop(SearxngError.SERVICE_ERROR) }
            check()
            val socket = sockets.open()
            register(socket, secure = false)
            socket.soLingerDisabled()
            socket.connect(InetSocketAddress(address, 443), connectDuration.coerceAtLeast(1).toInt())
            check()
            requirePeer(socket, address)
            val secure = sockets.secure(socket, host, 443)
            register(secure, secure = true)
            requirePeer(secure, address)
            val parameters = secure.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(host))
            parameters.applicationProtocols = arrayOf("http/1.1")
            secure.sslParameters = parameters
            secure.useClientMode = true
            secure.soTimeout = phaseRemaining(connectEnd)
            secure.startHandshake()
            check()
            if (clock.nanoTime() - connectEnd >= 0) fail(SearxngError.SERVICE_ERROR)
            if (secure.applicationProtocol !in listOf(null, "", "http/1.1")) fail(SearxngError.INVALID_RESPONSE)
            requirePeer(secure, address)
            connectTimer?.close()
            secure.soTimeout = remainingMillis().coerceAtLeast(1).toInt()
            secure.outputStream.write(requestBytes)
            secure.outputStream.flush()
            check()
            val parsed = StrictSearxngHttp.read(secure.inputStream, request.maxBodyBytes, ::check)
            if (parsed.status == 429 || parsed.status == 503) {
                // Record at the transport boundary even when the caller has already stopped
                // awaiting this response. Releasing a stuck close can never bypass server cooldown.
                val notBefore = SearxngRetryAfter.retryAt(parsed.headers["retry-after"]?.singleOrNull(), clock.epochMillis())
                originLease?.slowdown(notBefore)
            }
            val definitiveHeaders = parsed.status in setOf(401, 403, 429, 503)
            if (!definitiveHeaders) check()
            val body = object : ByteArrayInputStream(parsed.body) {
                override fun read(): Int { check(); return super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    check(); return super.read(buffer, offset, length)
                }
            }
            val response = SearxngHttpResponse(parsed.status, request.url, parsed.headers, body)
            if (definitiveHeaders) publish(Result.success(response), definitiveHeaders = true)
            return response
        }

        private fun Socket.soLingerDisabled() { setSoLinger(false, 0) }
        private fun phaseRemaining(end: Long): Int {
            check()
            val nanos = end - clock.nanoTime()
            if (nanos <= 0) fail(SearxngError.SERVICE_ERROR)
            return minOf(remainingMillis(), (nanos / 1_000_000L).coerceAtLeast(1)).coerceAtLeast(1).toInt()
        }
        private fun requirePeer(socket: Socket, expected: InetAddress) {
            if (!socket.isConnected || socket.port != 443 || socket.inetAddress != expected ||
                !SearxngUrlPolicy.isPublicAddress(socket.inetAddress)) fail(SearxngError.UNSAFE_URL)
        }
        private fun register(socket: Socket, secure: Boolean) {
            val rejected = synchronized(resources) {
                if (closing || failure.get() != null) true else {
                    if (secure) tls = socket as SSLSocket else raw = socket
                    false
                }
            }
            if (rejected) {
                try { socket.close() } catch (_: Exception) { }
                check()
                fail(SearxngError.CANCELLED)
            }
        }
        private fun remainingEpoch(): Long {
            val now = clock.epochMillis()
            return if (request.deadlineEpochMillis <= now) 0 else
                (request.deadlineEpochMillis - now).takeIf { it > 0 } ?: Long.MAX_VALUE
        }
        private fun remainingMillis(): Long {
            check()
            return minOf(remainingEpoch(), ((monotonicDeadline - clock.nanoTime()) / 1_000_000L).coerceAtLeast(1), 15_000L)
        }
        private fun check() {
            failure.get()?.let(::fail)
            if (remainingEpoch() <= 0 || clock.nanoTime() - monotonicDeadline >= 0) fail(SearxngError.DEADLINE_EXCEEDED)
        }
        private fun stop(code: SearxngError) {
            failure.compareAndSet(null, code)
            publish(Result.failure(SearxngFailure(failure.get()!!)))
            cleanup()
        }
        private fun cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) return
            val pair = synchronized(resources) {
                closing = true
                Pair(raw.also { raw = null }, tls.also { tls = null })
            }
            // Closing the raw socket first prevents TLS close_notify from blocking on network I/O.
            // Even an anomalously stuck close cannot release the physical admission slot.
            if (pair.first == null && pair.second == null) {
                cleanupDone.set(true)
                finishIfReleased()
                return
            }
            Thread({
                try {
                    try { pair.first?.close() } catch (_: Exception) { }
                    try { pair.second?.close() } catch (_: Exception) { }
                } finally { cleanupDone.set(true); finishIfReleased() }
            }, "esv-searxng-close").apply { isDaemon = true; start() }
        }
        private fun finishIfReleased() {
            if (!workerDone.get() || !cleanupDone.get()) return
            absoluteTimer?.close()
            originLease?.release()
            active.compareAndSet(this, null)
            workerResult?.let { publish(it) }
        }
        private fun publish(value: Result<SearxngHttpResponse>, definitiveHeaders: Boolean = false) {
            if (result.compareAndSet(null, Outcome(value, definitiveHeaders))) {
                if (definitiveHeaders) absoluteTimer?.close()
                terminal.countDown()
            }
        }
    }

    private companion object {
        const val USER_AGENT = "EasySaveVideo/1.0 (SearXNG metadata)"
        const val MAX_BODY = 2L * 1024 * 1024
        val REQUEST_HEADERS = setOf("host", "connection", "user-agent", "accept", "accept-encoding")
        fun fail(code: SearxngError): Nothing = throw SearxngFailure(code)
    }
}

internal fun interface SearxngDnsResolver { fun resolve(host: String): Array<InetAddress> }
internal interface SearxngSockets {
    fun open(): Socket
    fun secure(raw: Socket, host: String, port: Int): SSLSocket
}
internal interface SearxngTransportClock { fun epochMillis(): Long; fun nanoTime(): Long }
internal fun interface SearxngTransportTimer { fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable }
private object SystemSearxngDns : SearxngDnsResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}
private object SystemSearxngClock : SearxngTransportClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}
private class SystemSearxngSockets : SearxngSockets {
    // An instance-owned context, with no client keys, uses platform trust, not a caller-replaced
    // HttpsURLConnection verifier/factory or SSLContext default. No system setting is changed.
    private val factory by lazy {
        SSLContext.getInstance("TLS").apply { init(emptyArray<KeyManager>(), null, null) }.socketFactory
    }
    override fun open(): Socket = Socket(Proxy.NO_PROXY)
    override fun secure(raw: Socket, host: String, port: Int): SSLSocket =
        factory.createSocket(raw, host, port, true) as SSLSocket
}
private object SystemSearxngTimer : SearxngTransportTimer {
    private val executor by lazy {
        ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "esv-searxng-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
