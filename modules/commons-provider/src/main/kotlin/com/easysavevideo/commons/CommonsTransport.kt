package com.easysavevideo.commons

import java.io.InputStream
import java.net.URI
import java.net.InetAddress

/**
 * Pure platform port; the app supplies its independently tested strict socket adapter. Implementations must:
 * - create() does not connect; execute() performs one anonymous GET, with only supplied headers;
 * - ignore global CookieHandler, Authenticator, browser cookies, stored accounts and ambient proxies;
 * - use system TLS trust + hostname verification, DNS-check and pin a public address for this request;
 * - never follow redirects, retry, decompress, cache, log, or acquire credentials internally;
 * - cap response headers at 32 KiB / 100 fields, and wire bytes at maxBodyBytes;
 * - enforce connect <= 5s and the absolute deadline, including DNS, TLS, headers and body;
 * - cancel() is thread-safe, prompt/non-blocking, idempotent, and closes the in-flight socket/read.
 *
 * The integrating owner must verify this contract with the actual platform adapter before enabling.
 * There is no safe default HttpURLConnection adapter because global cookies/auth and DNS pinning
 * would otherwise escape this contract. A raw URL is not permission to fetch it.
 */
fun interface CommonsTransport { fun create(request: CommonsHttpRequest): CommonsHttpCall }
interface CommonsHttpCall {
    fun execute(): CommonsHttpResponse
    fun cancel()
}
class CommonsHttpRequest(
    val url: URI,
    val headers: Map<String, String>,
    val deadlineEpochMillis: Long,
    val connectTimeoutMillis: Int,
    val maxBodyBytes: Long,
    val stageTrace: com.easysavevideo.discovery.CommonsStageTrace? = null,
) {
    init { CommonsUrlPolicy.requireApi(url) }
    override fun toString(): String = "CommonsHttpRequest([redacted])"
}
class CommonsHttpResponse(
    val status: Int,
    val url: URI,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : AutoCloseable {
    override fun close() { body.close() }
    override fun toString(): String = "CommonsHttpResponse(status=$status)"
}

/** Separate policies for network API requests and non-fetched display links. All redirects rejected. */
object CommonsUrlPolicy {
    fun requireApi(uri: URI) {
        if (!baseSafe(uri) || uri.host.lowercase() != "commons.wikimedia.org" ||
            uri.rawPath != "/w/api.php" || uri.rawQuery == null) throw CommonsFailure(CommonsError.UNSAFE_URL)
    }
    fun page(value: String): String? = parse(value)?.takeIf {
        it.host.lowercase() == "commons.wikimedia.org" && it.query == null &&
            it.path.startsWith("/wiki/File:") && it.path.length > "/wiki/File:".length
    }?.toASCIIString()
    fun thumbnail(value: String): String? = parse(value)?.takeIf {
        it.host.lowercase() in setOf("upload.wikimedia.org", "thumb.wikimedia.org") &&
            thumbnailQuerySafe(it.rawQuery) && it.path.startsWith("/wikipedia/commons/thumb/") &&
            it.path.lowercase().let { p -> listOf(".jpg", ".jpeg", ".png", ".webp").any(p::endsWith) }
    }?.toASCIIString()
    /** License URLs are display-only and never passed to the transport. */
    fun license(value: String): String? = parse(value)?.takeIf {
        it.rawQuery == null && when (it.host.lowercase()) {
            "creativecommons.org", "www.creativecommons.org" ->
                it.path.startsWith("/licenses/") || it.path.startsWith("/publicdomain/")
            "www.gnu.org", "gnu.org" -> it.path.startsWith("/licenses/")
            "commons.wikimedia.org" -> it.path.startsWith("/wiki/Help:") || it.path.startsWith("/wiki/Commons:")
            else -> false
        }
    }?.toASCIIString()
    /** The real adapter must check every DNS result and use the verified address, not re-resolve. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0) || (b[0] == 192 && b[1] == 2) || (b[0] == 192 && b[1] == 88 && b[2] == 99) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
            // Only global-unicast 2000::/3, excluding documentation, Teredo and 6to4 ranges.
            16 -> b[0] and 0xe0 == 0x20 && !(b[0] == 0x20 && b[1] == 0x01 &&
                (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x3f && b[1] == 0xff && b[2] and 0xf0 == 0)
            else -> false
        }
    }
    private fun thumbnailQuerySafe(query: String?): Boolean {
        if (query == null) return true
        // Current official API thumbnail URLs contain these two fixed attribution parameters.
        val parts = query.split('&')
        return parts.size == 2 && parts.toSet() == setOf(
            "utm_source=commons.wikimedia.org", "utm_campaign=imageinfo")
    }
    private fun parse(value: String): URI? = try {
        if (value.length !in 1..4096) null else URI(value).takeIf(::baseSafe)
    } catch (_: Exception) { null }
    private fun baseSafe(uri: URI): Boolean = isWellFormedUnicode(uri.toString()) && uri.scheme == "https" && uri.host != null &&
        uri.userInfo == null && uri.port == -1 && uri.fragment == null && !uri.isOpaque &&
        uri.rawPath != null && !uri.rawPath.contains('\\') && uri.normalize().rawPath == uri.rawPath &&
        uri.path.split('/').none { it == "." || it == ".." || it.contains('\\') } && !uri.path.contains('%') &&
        !Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|2f|5c)").containsMatchIn(uri.rawPath) &&
        uri.toString().none { it.isISOControl() }
}
