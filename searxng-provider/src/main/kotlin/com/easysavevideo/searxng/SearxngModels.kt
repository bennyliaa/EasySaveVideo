package com.easysavevideo.searxng

import java.net.URI
import java.security.MessageDigest

/** One user-configured anonymous metadata endpoint; never an authority to fetch result links. */
enum class SearxngResultFormat { JSON_VIDEOS, HTML_WEB }

class SearxngEndpoint(val id: String, val searchUrl: String, val format: SearxngResultFormat = SearxngResultFormat.JSON_VIDEOS) {
    val uri: URI = try { URI(searchUrl) } catch (_: Exception) { throw IllegalArgumentException("Invalid endpoint") }
    init {
        require(id.length in 9..27 && id.startsWith("searxng-") &&
            id.substring(8).matches(Regex("[1-9][0-9]{0,18}")) &&
            id.substring(8).toLongOrNull() != null)
        require(SearxngUrlPolicy.isEndpoint(uri)) { "Invalid endpoint" }
    }
    /** Exact URL changes invalidate persisted progress, even when a slot ID is reused. */
    val version: String = if (format == SearxngResultFormat.JSON_VIDEOS) "1-" + sha256(searchUrl)
        else "2-html-" + sha256(searchUrl)
    override fun toString(): String = "SearxngEndpoint(id=$id, endpoint=[redacted])"
}

object SearxngIdentity {
    const val USER_AGENT = "EasySaveVideo/1.0 (SearXNG metadata)"
}

enum class SearxngError {
    UNSUPPORTED_FILTER, INVALID_REQUEST, INVALID_CURSOR, INVALID_RESPONSE, UNSAFE_URL,
    REDIRECT_REJECTED, SERVICE_DENIED, SERVICE_ERROR, RATE_LIMITED, RESPONSE_TOO_LARGE,
    TOO_MANY_CANDIDATES, DEADLINE_EXCEEDED, CANCELLED, BUSY,
}
class SearxngFailure(val code: SearxngError, val retryAtEpochMillis: Long? = null) : RuntimeException(code.name)

data class SearxngLimits(
    val maxCandidates: Int = 100,
    val maxPages: Int = 20,
    val maxResponseBytes: Long = 2L * 1024 * 1024,
    val deadlineMillis: Long = 15_000,
) {
    init {
        require(maxCandidates in 1..100)
        require(maxPages in 1..100)
        require(maxResponseBytes in 1..2L * 1024 * 1024)
        require(deadlineMillis in 1..15_000)
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun isWellFormedUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

internal fun plus(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
