package com.easysavevideo.searxng

import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * create() does no I/O. execute() performs one anonymous GET only to request.endpoint.
 * The app adapter validates every DNS address and pins a public peer, verifies TLS hostnames,
 * rejects redirects and ambient auth/cookies/proxies, and never caches or decompresses.
 * Wire/body bytes are bounded; connect <= 5s and absolute deadline <= 15s cover DNS through body.
 * cancel() is prompt, thread-safe, non-blocking, idempotent; physical admission stays held until
 * the worker and all resource cleanup have really exited. No live network stream escapes it.
 */
fun interface SearxngTransport { fun create(request: SearxngHttpRequest): SearxngHttpCall }
interface SearxngHttpCall {
    fun execute(): SearxngHttpResponse
    fun cancel()
}
class SearxngHttpRequest(
    val endpoint: SearxngEndpoint,
    val url: URI,
    val headers: Map<String, String>,
    val deadlineEpochMillis: Long,
    val connectTimeoutMillis: Int,
    val maxBodyBytes: Long,
) {
    init { SearxngUrlPolicy.requireApi(endpoint, url) }
    override fun toString(): String = "SearxngHttpRequest([redacted])"
}
class SearxngHttpResponse(
    val status: Int,
    val url: URI,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : AutoCloseable {
    override fun close() { body.close() }
    override fun toString(): String = "SearxngHttpResponse(status=$status)"
}

object SearxngUrlPolicy {
    /** Deliberately support only /search; no implicit path-prefix discovery or redirects. */
    fun isEndpoint(uri: URI): Boolean = safeBase(uri) && uri.rawPath == "/search" && uri.rawQuery == null

    fun requireApi(endpoint: SearxngEndpoint, uri: URI) {
        if (!safeBase(uri) || uri.rawAuthority != endpoint.uri.rawAuthority ||
            uri.rawPath != endpoint.uri.rawPath || uri.rawQuery.isNullOrEmpty())
            throw SearxngFailure(SearxngError.UNSAFE_URL)
    }

    /** Display/validation candidates only. This function never resolves or fetches a result URL. */
    fun candidate(value: String): String? = try {
        if (value.length !in 1..4096) null else URI(value).takeIf { safeBase(it, allowHttp = true) }
            ?.toASCIIString()?.takeIf { it.length <= 4096 }
    } catch (_: Exception) { null }

    private fun safeBase(uri: URI, allowHttp: Boolean = false): Boolean = uri.toString().length <= 16_384 &&
        isWellFormedUnicode(uri.toString()) && (uri.scheme == "https" || allowHttp && uri.scheme == "http") && !uri.isOpaque &&
        uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
        publicDnsName(uri.host) && uri.toString().none { it.isISOControl() || it == '\\' } &&
        uri.rawPath != null && uri.normalize().rawPath == uri.rawPath &&
        uri.path.split('/').none { it == "." || it == ".." || '\\' in it } &&
        !Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|5c)").containsMatchIn(uri.toASCIIString())

    private fun publicDnsName(host: String?): Boolean {
        if (host == null || host.length !in 4..253 || host.endsWith('.')) return false
        val labels = host.lowercase(Locale.ROOT).split('.')
        if (labels.size < 2 || labels.any {
                it.length !in 1..63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            }) return false
        if (!labels.last().matches(Regex("[a-z]{2,63}|xn--[a-z0-9-]{2,59}"))) return false
        return labels.last() !in setOf("localhost", "local", "localdomain", "internal", "lan", "home", "onion", "invalid", "test", "example", "alt", "arpa") &&
            labels.none { it == "localhost" }
    }

    /** Reject the entire DNS answer if any address is not public; never re-resolve after this. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0) || (b[0] == 192 && b[1] == 2) ||
                (b[0] == 192 && b[1] == 88 && b[2] == 99) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
            16 -> b[0] and 0xe0 == 0x20 && !(b[0] == 0x20 && b[1] == 0x01 &&
                (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x3f && b[1] == 0xff && b[2] and 0xf0 == 0)
            else -> false
        }
    }
}
