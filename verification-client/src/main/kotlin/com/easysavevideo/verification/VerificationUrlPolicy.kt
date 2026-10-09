package com.easysavevideo.verification

import java.net.InetAddress
import java.net.URI
import java.util.Locale

object VerificationUrlPolicy {
    /** Deliberately support only /v1/verify; no implicit path-prefix discovery or redirects. */
    fun isEndpoint(uri: URI): Boolean = safeBase(uri) && uri.rawPath == "/v1/verify" && uri.rawQuery == null

    fun requireApi(endpoint: VerificationEndpoint, uri: URI) {
        if (!isEndpoint(uri) || uri.toString() != endpoint.url)
            throw VerificationFailure(VerificationError.UNSAFE_URL)
    }

    /** A policy check only. The client never resolves or retrieves a candidate or thumbnail. */
    fun candidate(value: String): String? = try {
        if (value.toByteArray(Charsets.UTF_8).size !in 1..4096) null
        else value.takeIf { safeBase(URI(it), allowHttp = true) }
    } catch (_: Exception) { null }

    fun thumbnail(value: String): String? = candidate(value)?.takeIf { URI(it).scheme == "https" }

    private fun safeBase(uri: URI, allowHttp: Boolean = false): Boolean = uri.toString().length <= 16_384 &&
        isWellFormedUnicode(uri.toString()) && (uri.scheme == "https" || allowHttp && uri.scheme == "http") && !uri.isOpaque &&
        uri.userInfo == null && (uri.port == -1 || uri.scheme == "https" && uri.port == 443 || allowHttp && uri.scheme == "http" && uri.port == 80) && uri.fragment == null &&
        publicDnsName(uri.host) && uri.rawAuthority == uri.host + (if (uri.port == -1) "" else ":${uri.port}") &&
        uri.toString().none { it.isISOControl() || it == '\\' } &&
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
