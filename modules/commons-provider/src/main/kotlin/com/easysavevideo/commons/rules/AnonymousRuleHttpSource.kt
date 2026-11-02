package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsUrlPolicy
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Anonymous, single-exchange transport. All execution uses the existing provider runtime worker. */
class AnonymousRuleHttpSource private constructor(
    private val endpoint: RuleHttpEndpoint,
    private val clock: EpochClock,
    private val resolver: (String) -> List<InetAddress>,
    private val fixturePort: Int?,
    private val nanoTime: () -> Long,
) : RuleResponseSource {
    constructor(endpoint: RuleHttpEndpoint, clock: EpochClock = EpochClock(System::currentTimeMillis)) :
        this(endpoint, clock, Dns.SYSTEM::lookup, null, System::nanoTime)

    override fun create(request: RuleResponseRequest): RuleResponseCall {
        val binding = request.httpBinding ?: fail(DiscoveryError.SOURCE_CONFIGURATION)
        if (binding.endpoint !== endpoint || binding.format != endpoint.format || request.maximumBytes < 1)
            fail(DiscoveryError.SOURCE_CONFIGURATION)
        val logical = try { binding.url.toHttpUrl() } catch (_: Exception) { fail(DiscoveryError.SOURCE_CONFIGURATION) }
        if (!logical.isHttps || logical.host != endpoint.origin.host || logical.port != 443 ||
            logical.encodedPath != endpoint.path || logical.username.isNotEmpty() || logical.password.isNotEmpty() ||
            logical.fragment != null || logical.toString().length > RuleHttpEndpoint.MAX_URL_BYTES)
            fail(DiscoveryError.SOURCE_CONFIGURATION)
        val wire = if (fixturePort == null) logical else logical.newBuilder().scheme("http").host("127.0.0.1").port(fixturePort).build()
        return Exchange(request, wire)
    }

    private inner class Exchange(private val request: RuleResponseRequest, private val wireUrl: HttpUrl) : RuleResponseCall {
        private val started = AtomicBoolean(false)
        private val failure = AtomicReference<DiscoveryFailure?>()
        private val nativeCall = AtomicReference<Call?>()
        private val response = AtomicReference<Response?>()
        private val pool = ConnectionPool(0, 1, TimeUnit.MILLISECONDS)
        private val selectedAddress = AtomicReference<InetAddress?>()
        private val dnsStarted = AtomicBoolean(false)
        private val exchangeStarted = AtomicBoolean(false)
        private val cap = minOf(request.maximumBytes, MAX_BODY_BYTES)
        @Volatile private var endNanos = Long.MAX_VALUE

        override fun execute(): InputStream {
            if (!started.compareAndSet(false, true)) abort(DiscoveryError.SOURCE_CONFIGURATION)
            check()
            val now = clock.nowMillis()
            if (now < 0 || request.deadlineEpochMillis <= now) abort(DiscoveryError.DEADLINE_EXCEEDED)
            val remaining = minOf(15_000L, request.deadlineEpochMillis - now)
            endNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining)
            try {
                val dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        check()
                        if (hostname != wireUrl.host || !dnsStarted.compareAndSet(false, true))
                            throw UnknownHostException("ESV_ADDRESS_DENIED")
                        val answers = resolver(hostname).toList()
                        check()
                        if (answers.isEmpty() || answers.any { !allowedAddress(it) })
                            throw UnknownHostException("ESV_ADDRESS_DENIED")
                        selectedAddress.set(answers.first())
                        return listOf(answers.first())
                    }
                }
                // OkHttp bypasses DNS for numeric addresses. Only the internal owned fixture is numeric.
                if (fixturePort != null) selectedAddress.set(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                val builder = OkHttpClient.Builder().dns(dns).proxy(Proxy.NO_PROXY)
                    .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                    .cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .protocols(listOf(Protocol.HTTP_1_1)).connectionPool(pool)
                    .connectTimeout(minOf(5_000L, remaining), TimeUnit.MILLISECONDS)
                    .readTimeout(remaining, TimeUnit.MILLISECONDS).writeTimeout(remaining, TimeUnit.MILLISECONDS)
                    .callTimeout(remaining, TimeUnit.MILLISECONDS)
                    .addNetworkInterceptor { chain ->
                        check()
                        if (!exchangeStarted.compareAndSet(false, true)) abort(DiscoveryError.PROVIDER_FAILURE)
                        val connection = chain.connection() ?: abort(DiscoveryError.PROVIDER_FAILURE)
                        val peer = connection.socket().inetAddress
                        val selected = selectedAddress.get()
                        if (connection.route().address.url.host != wireUrl.host || connection.socket().port != wireUrl.port ||
                            selected == null || peer != selected || !allowedAddress(peer)) abort(DiscoveryError.SOURCE_CONFIGURATION)
                        val result = chain.proceed(chain.request())
                        try {
                            check()
                            validateHeaders(result)
                            result
                        } catch (problem: Exception) {
                            // Stop first: close must not discard an unbudgeted response or permit retry follow-ups.
                            nativeCall.get()?.cancel()
                            result.close()
                            throw problem
                        }
                    }
                if (fixturePort == null) installSystemTlsWithoutClientCredentials(builder)
                val call = builder.build().newCall(Request.Builder().url(wireUrl)
                    .header("Accept", endpoint.accept).header("Accept-Encoding", "identity")
                    .header("User-Agent", RuleHttpEndpoint.USER_AGENT).header("Connection", "close").get().build())
                nativeCall.set(call)
                check()
                val result = call.execute()
                response.set(result)
                check()
                val body = result.body ?: abort(DiscoveryError.INVALID_RESULT)
                val declared = declaredLength(result)
                return BoundedBody(body.byteStream(), declared)
            } catch (problem: DiscoveryFailure) {
                cleanup(cancelFirst = true)
                throw problem
            } catch (_: Exception) {
                cleanup(cancelFirst = true)
                check()
                abort(DiscoveryError.PROVIDER_FAILURE)
            }
        }

        private fun allowedAddress(value: InetAddress): Boolean = if (fixturePort == null)
            CommonsUrlPolicy.isPublicAddress(value) else value.address.contentEquals(byteArrayOf(127, 0, 0, 1))

        private fun validateHeaders(value: Response) {
            if (value.headers.size > MAX_HEADER_FIELDS) abort(DiscoveryError.INVALID_RESULT)
            var size = 2L
            for (i in 0 until value.headers.size) {
                check()
                // Parsed acceptance budget, not a claim about OkHttp's native preallocation/wire bytes.
                size += utf8Size(value.headers.name(i)) + utf8Size(value.headers.value(i)) + 4L
                if (size > MAX_HEADER_BYTES) abort(DiscoveryError.RESPONSE_TOO_LARGE)
            }
            if (value.code != 200) {
                val code = if (value.code == 429 || value.code == 503) DiscoveryError.RATE_LIMITED else DiscoveryError.PROVIDER_FAILURE
                abort(code)
            }
            // Fixed OkHttp's chunk-extension reader is not entity-cap bounded. This profile refuses it.
            if (value.headers.values("Transfer-Encoding").isNotEmpty() || value.headers.values("Trailer").isNotEmpty())
                abort(DiscoveryError.INVALID_RESULT)
            val types = value.headers.values("Content-Type")
            if (types.size != 1 || !validContentType(types.single())) abort(DiscoveryError.INVALID_RESULT)
            val encodings = value.headers.values("Content-Encoding")
            if (encodings.size > 1 || encodings.singleOrNull()?.let { !it.trim().equals("identity", true) } == true)
                abort(DiscoveryError.INVALID_RESULT)
            declaredLength(value)
        }

        private fun validContentType(value: String): Boolean {
            if (value.any { it.code !in 32..126 }) return false
            val pieces = value.split(';')
            if (pieces.size !in 1..2 || !pieces[0].trim().equals(endpoint.accept, true)) return false
            if (pieces.size == 1) return true
            val parameter = pieces[1].trim().split('=', limit = 2)
            return parameter.size == 2 && parameter[0].trim().equals("charset", true) &&
                parameter[1].trim().let { it.equals("utf-8", true) || it.equals("\"utf-8\"", true) }
        }

        private fun declaredLength(value: Response): Long? {
            val values = value.headers.values("Content-Length")
            if (values.isEmpty()) return null
            if (values.size != 1) abort(DiscoveryError.INVALID_RESULT)
            val raw = values.single()
            if (raw.length !in 1..19 || raw.any { it !in '0'..'9' }) abort(DiscoveryError.INVALID_RESULT)
            val parsed = raw.toLongOrNull() ?: abort(DiscoveryError.INVALID_RESULT)
            if (parsed > cap) abort(DiscoveryError.RESPONSE_TOO_LARGE)
            return parsed
        }

        private inner class BoundedBody(private val input: InputStream, private val declared: Long?) : InputStream() {
            private val closed = AtomicBoolean(false)
            private var count = 0L
            private var eof = false
            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (length == 0) return 0
                if (closed.get()) abort(DiscoveryError.CANCELLED)
                check()
                if (eof) return -1
                return try {
                    val requested = minOf(length.toLong(), cap - count + 1).toInt()
                    val read = input.read(buffer, offset, requested)
                    check()
                    if (read < 0) {
                        if (declared != null && count != declared) abort(DiscoveryError.INVALID_RESULT)
                        eof = true
                        -1
                    } else {
                        if (read == 0 || read > requested) abort(DiscoveryError.INVALID_RESULT)
                        if (count + read > cap) abort(DiscoveryError.RESPONSE_TOO_LARGE)
                        count += read
                        read
                    }
                } catch (problem: DiscoveryFailure) {
                    nativeCall.get()?.cancel()
                    throw problem
                } catch (_: IOException) {
                    nativeCall.get()?.cancel()
                    check()
                    abort(DiscoveryError.INVALID_RESULT)
                }
            }
            override fun close() {
                if (closed.compareAndSet(false, true)) cleanup(cancelFirst = !eof)
            }
        }

        override fun cancel() {
            failure.compareAndSet(null, DiscoveryFailure(DiscoveryError.CANCELLED))
            nativeCall.get()?.cancel()
        }
        private fun cleanup(cancelFirst: Boolean) {
            if (cancelFirst) nativeCall.get()?.cancel()
            try { response.getAndSet(null)?.close() } finally { pool.evictAll() }
        }
        private fun check() {
            failure.get()?.let { throw it }
            if (Thread.currentThread().isInterrupted) abort(DiscoveryError.CANCELLED)
            val now = clock.nowMillis()
            if (now < 0 || now >= request.deadlineEpochMillis || (endNanos != Long.MAX_VALUE && nanoTime() - endNanos >= 0))
                abort(DiscoveryError.DEADLINE_EXCEEDED)
        }
        private fun abort(code: DiscoveryError): Nothing {
            failure.compareAndSet(null, DiscoveryFailure(code))
            nativeCall.get()?.cancel()
            throw failure.get()!!
        }
        override fun toString(): String = "RuleHttpResponseCall([redacted])"
    }

    override fun toString(): String = "AnonymousRuleHttpSource([redacted])"
    companion object {
        internal const val MAX_BODY_BYTES = 65_536L
        private const val MAX_HEADER_BYTES = 16_384L
        private const val MAX_HEADER_FIELDS = 64

        /** Test-only assembly seam; never selected by rule bytes, public constructors or App settings. */
        internal fun ownedFixture(endpoint: RuleHttpEndpoint, port: Int, clock: EpochClock): AnonymousRuleHttpSource {
            require(port in 1..65535)
            return AnonymousRuleHttpSource(endpoint, clock, Dns.SYSTEM::lookup, port, System::nanoTime)
        }
        /** Exercises production DNS gates without changing OS resolution, trust or public configuration. */
        internal fun resolverFixture(endpoint: RuleHttpEndpoint, clock: EpochClock,
            resolver: (String) -> List<InetAddress>): AnonymousRuleHttpSource =
            AnonymousRuleHttpSource(endpoint, clock, resolver, null, System::nanoTime)

        private fun installSystemTlsWithoutClientCredentials(builder: OkHttpClient.Builder) {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val context = SSLContext.getInstance("TLS")
            context.init(emptyArray<KeyManager>(), arrayOf<TrustManager>(manager), null)
            builder.sslSocketFactory(context.socketFactory, manager)
        }
        private fun utf8Size(value: String): Long {
            var bytes = 0L; var index = 0
            while (index < value.length) {
                val c = value[index++]
                bytes += when {
                    c.code < 128 -> 1
                    c.code < 2048 -> 2
                    c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> { index++; 4 }
                    else -> 3
                }
            }
            return bytes
        }
        private fun fail(code: DiscoveryError): Nothing = throw DiscoveryFailure(code)
    }
}
