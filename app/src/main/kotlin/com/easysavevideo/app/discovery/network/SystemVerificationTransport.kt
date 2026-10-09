package com.easysavevideo.app.discovery.network

import com.easysavevideo.verification.VerificationError
import com.easysavevideo.verification.VerificationFailure
import com.easysavevideo.verification.VerificationHttpCall
import com.easysavevideo.verification.VerificationHttpRequest
import com.easysavevideo.verification.VerificationHttpResponse
import com.easysavevideo.verification.VerificationIdentity
import com.easysavevideo.verification.VerificationTransport
import com.easysavevideo.verification.VerificationUrlPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
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
 * Original anonymous, single-exchange HTTP/1.1 adapter bound to the selected verification endpoint.
 * It never uses URLConnection, ambient
 * CookieHandler/Authenticator/ProxySelector, browser state, connection pools, or global TLS defaults.
 * The public constructor is the production boundary; the internal constructor is for offline tests.
 *
 * One instance has one physical slot. Cancellation/deadline completes execute promptly, while a
 * stuck resolver, socket operation, or close retains that slot until both I/O and cleanup exit.
 * Socket closure never runs on the caller, UI, cancellation, or timer thread. The small bounded
 * body is collected before publishing it, and no live socket escapes the adapter. No retry or
 * cooldown is applied. App ownership is endpoint-scoped: retain one transport per configuration.
 */
class SystemVerificationTransport internal constructor(
    private val resolver: VerificationDnsResolver,
    private val sockets: VerificationSockets,
    private val clock: VerificationTransportClock = SystemVerificationClock,
    private val timer: VerificationTransportTimer = SystemVerificationTimer,
) : VerificationTransport {
    constructor() : this(SystemVerificationDns, SystemVerificationSockets())

    private class Outcome(val value: Result<VerificationHttpResponse>)
    private val active = AtomicReference<Call?>()

    override fun create(request: VerificationHttpRequest): VerificationHttpCall {
        VerificationUrlPolicy.requireApi(request.endpoint, request.url)
        if (request.url != request.endpoint.uri || request.url.toASCIIString().length > 4_096 ||
            request.connectTimeoutMillis !in 1..5_000 || request.maxBodyBytes !in 1..MAX_BODY ||
            request.body.size !in 1..MAX_REQUEST) fail(VerificationError.INVALID_REQUEST)
        // Snapshot all caller-owned mutable data before creating a worker.
        val body = request.body.copyOf()
        val headers = LinkedHashMap(request.headers)
        if (headers.size != 7 || headers.keys.map { it.lowercase(Locale.ROOT) }.toSet() != REQUEST_HEADERS ||
            headers.keys.any { !it.matches(Regex("[A-Za-z-]{1,32}")) } ||
            headers.values.any { it.length !in 1..512 || it.any { c -> c.code !in 32..126 } }) fail(VerificationError.INVALID_REQUEST)
        val values = headers.mapKeys { it.key.lowercase(Locale.ROOT) }
        if (values["host"] != request.endpoint.uri.rawAuthority || values["connection"] != "close" ||
            values["accept"] != "application/json" || values["accept-encoding"] != "identity" ||
            values["user-agent"] != VerificationIdentity.USER_AGENT || values["content-type"] != "application/json" ||
            values["content-length"] != body.size.toString()) fail(VerificationError.INVALID_REQUEST)
        val bytes = buildString {
            append("POST /v1/verify HTTP/1.1\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII) + body
        return Call(request, bytes)
    }

    private inner class Call(private val request: VerificationHttpRequest, private val requestBytes: ByteArray) : VerificationHttpCall {
        private val host = request.endpoint.uri.host
        private val started = AtomicBoolean(false)
        private val result = AtomicReference<Outcome?>()
        private val terminal = CountDownLatch(1)
        private val failure = AtomicReference<VerificationError?>()
        private val workerDone = AtomicBoolean(false)
        private val cleanupDone = AtomicBoolean(false)
        private val cleanupStarted = AtomicBoolean(false)
        private val connecting = AtomicBoolean(false)
        private val resources = Any()
        private var raw: Socket? = null
        private var tls: SSLSocket? = null
        private var closing = false
        private var workerResult: Result<VerificationHttpResponse>? = null
        @Volatile private var monotonicDeadline = Long.MAX_VALUE
        @Volatile private var absoluteTimer: AutoCloseable? = null
        @Volatile private var connectTimer: AutoCloseable? = null

        override fun execute(): VerificationHttpResponse {
            if (!started.compareAndSet(false, true)) fail(VerificationError.INVALID_REQUEST)
            failure.get()?.let(::fail)
            val duration = minOf(10_000L, remainingEpoch())
            if (duration <= 0) fail(VerificationError.DEADLINE_EXCEEDED)
            monotonicDeadline = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration)
            if (!active.compareAndSet(null, this)) fail(VerificationError.BUSY)
            try {
                absoluteTimer = timer.schedule(duration) { stop(VerificationError.DEADLINE_EXCEEDED) }
                Thread({ runExchange() }, "esv-verification-http").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                workerDone.set(true)
                stop(VerificationError.SERVICE_ERROR)
                cleanup()
                finishIfReleased()
            }
            try { terminal.await() }
            catch (_: InterruptedException) {
                stop(VerificationError.CANCELLED)
                Thread.currentThread().interrupt()
                fail(VerificationError.CANCELLED)
            }
            val outcome = result.get()!!
            check()
            return outcome.value.getOrThrow()
        }

        override fun cancel() { stop(VerificationError.CANCELLED) }

        private fun runExchange() {
            workerResult = try { Result.success(exchange()) }
            catch (problem: VerificationFailure) { Result.failure(problem) }
            catch (_: Exception) { Result.failure(VerificationFailure(failure.get() ?: VerificationError.SERVICE_ERROR)) }
            catch (_: LinkageError) { Result.failure(VerificationFailure(VerificationError.SERVICE_ERROR)) }
            finally {
                connecting.set(false)
                connectTimer?.close()
                cleanup()
            }
            workerDone.set(true)
            finishIfReleased()
        }

        private fun exchange(): VerificationHttpResponse {
            check()
            val answers = resolver.resolve(host).toList()
            check()
            if (answers.isEmpty() || answers.any { !VerificationUrlPolicy.isPublicAddress(it) }) fail(VerificationError.UNSAFE_URL)
            val address = answers.first()
            val connectDuration = minOf(request.connectTimeoutMillis.toLong(), remainingMillis())
            val connectEnd = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectDuration)
            connecting.set(true)
            connectTimer = timer.schedule(connectDuration) {
                if (connecting.compareAndSet(true, false)) stop(VerificationError.SERVICE_ERROR)
            }
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
            if (clock.nanoTime() - connectEnd >= 0) fail(VerificationError.SERVICE_ERROR)
            if (secure.applicationProtocol !in listOf(null, "", "http/1.1")) fail(VerificationError.INVALID_RESPONSE)
            requirePeer(secure, address)
            connecting.set(false)
            connectTimer?.close()
            secure.soTimeout = remainingMillis().coerceAtLeast(1).toInt()
            secure.outputStream.write(requestBytes)
            secure.outputStream.flush()
            check()
            val parsed = StrictVerificationHttp.read(secure.inputStream, request.maxBodyBytes, ::check)
            check()
            // Use InputStream delegation instead of subclassing ByteArrayInputStream: its JDK
            // readAllBytes/readNBytes/transferTo shortcuts otherwise bypass guarded read methods.
            val body = object : InputStream() {
                private val bytes = ByteArrayInputStream(parsed.body)
                private val closed = AtomicBoolean(false)
                private fun guard() { check(); if (closed.get()) fail(VerificationError.CANCELLED) }
                override fun read(): Int { guard(); return bytes.read().also { guard() } }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    guard(); return bytes.read(buffer, offset, length).also { guard() }
                }
                override fun close() { closed.set(true) }
            }
            return VerificationHttpResponse(parsed.status, request.url, parsed.headers, body)
        }

        private fun Socket.soLingerDisabled() { setSoLinger(false, 0) }
        private fun phaseRemaining(end: Long): Int {
            check()
            val nanos = end - clock.nanoTime()
            if (nanos <= 0) fail(VerificationError.SERVICE_ERROR)
            return minOf(remainingMillis(), (nanos / 1_000_000L).coerceAtLeast(1)).coerceAtLeast(1).toInt()
        }
        private fun requirePeer(socket: Socket, expected: InetAddress) {
            if (!socket.isConnected || socket.port != 443 || socket.inetAddress != expected ||
                !VerificationUrlPolicy.isPublicAddress(socket.inetAddress)) fail(VerificationError.UNSAFE_URL)
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
                fail(VerificationError.CANCELLED)
            }
        }
        private fun remainingEpoch(): Long {
            val now = clock.epochMillis()
            return if (request.deadlineEpochMillis <= now) 0 else
                (request.deadlineEpochMillis - now).takeIf { it > 0 } ?: Long.MAX_VALUE
        }
        private fun remainingMillis(): Long {
            check()
            return minOf(remainingEpoch(), ((monotonicDeadline - clock.nanoTime()) / 1_000_000L).coerceAtLeast(1), 10_000L)
        }
        private fun check() {
            failure.get()?.let(::fail)
            if (remainingEpoch() <= 0 || clock.nanoTime() - monotonicDeadline >= 0) fail(VerificationError.DEADLINE_EXCEEDED)
        }
        private fun stop(code: VerificationError) {
            failure.compareAndSet(null, code)
            publish(Result.failure(VerificationFailure(failure.get()!!)))
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
            }, "esv-verification-close").apply { isDaemon = true; start() }
        }
        private fun finishIfReleased() {
            if (!workerDone.get() || !cleanupDone.get()) return
            absoluteTimer?.close()
            active.compareAndSet(this, null)
            workerResult?.let { publish(it) }
        }
        private fun publish(value: Result<VerificationHttpResponse>) {
            if (result.compareAndSet(null, Outcome(value))) {
                terminal.countDown()
            }
        }
    }

    private companion object {
        const val MAX_REQUEST = 8_192
        const val MAX_BODY = 32_768L
        val REQUEST_HEADERS = setOf("host", "connection", "user-agent", "accept", "accept-encoding", "content-type", "content-length")
        fun fail(code: VerificationError): Nothing = throw VerificationFailure(code)
    }
}

internal fun interface VerificationDnsResolver { fun resolve(host: String): Array<InetAddress> }
internal interface VerificationSockets {
    fun open(): Socket
    fun secure(raw: Socket, host: String, port: Int): SSLSocket
}
internal interface VerificationTransportClock { fun epochMillis(): Long; fun nanoTime(): Long }
internal fun interface VerificationTransportTimer { fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable }
private object SystemVerificationDns : VerificationDnsResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}
private object SystemVerificationClock : VerificationTransportClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}
private class SystemVerificationSockets : VerificationSockets {
    // An instance-owned context, with no client keys, uses platform trust, not a caller-replaced
    // HttpsURLConnection verifier/factory or SSLContext default. No system setting is changed.
    private val factory by lazy {
        SSLContext.getInstance("TLS").apply { init(emptyArray<KeyManager>(), null, null) }.socketFactory
    }
    override fun open(): Socket = Socket(Proxy.NO_PROXY)
    override fun secure(raw: Socket, host: String, port: Int): SSLSocket =
        factory.createSocket(raw, host, port, true) as SSLSocket
}
private object SystemVerificationTimer : VerificationTransportTimer {
    private val executor by lazy {
        ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "esv-verification-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
