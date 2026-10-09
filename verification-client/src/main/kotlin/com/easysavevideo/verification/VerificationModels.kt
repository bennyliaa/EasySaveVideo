package com.easysavevideo.verification

import com.easysavevideo.discovery.RawVideoResult
import java.net.URI

/** There is deliberately no default endpoint, service hosting or automatic discovery. */
class VerificationEndpoint(val url: String) {
    val uri: URI = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid endpoint") }
    init { require(VerificationUrlPolicy.isEndpoint(uri)) { "Invalid endpoint" } }
    override fun toString(): String = "VerificationEndpoint([redacted])"
}

object VerificationIdentity {
    const val USER_AGENT = "EasySaveVideo/1.0 (page verification)"
}

enum class VerificationError {
    INVALID_REQUEST, INVALID_RESPONSE, UNSAFE_URL, REDIRECT_REJECTED, SERVICE_DENIED,
    SERVICE_ERROR, RESPONSE_TOO_LARGE, DEADLINE_EXCEEDED, CANCELLED, BUSY, STALE_CONFIGURATION,
}
class VerificationFailure(val code: VerificationError) : RuntimeException(code.name)

enum class VerificationOutcome {
    PAGE_EVIDENCE, INSUFFICIENT, UNSUPPORTED, REJECTED, TIMEOUT, CANCELLED, BUSY, UNAVAILABLE,
}
enum class VerificationEvidence { HTML_VIDEO, DYNAMIC_VIDEO_XHR, NONE }

/** Only the validated response supplies metadata. This is never a playback or rights assertion. */
class VerificationResult internal constructor(
    val requestId: String,
    val generation: String,
    val candidateUrl: String,
    val outcome: VerificationOutcome,
    val pageUrl: String,
    val evidence: VerificationEvidence,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
) {
    val playbackVerified: Boolean get() = false
    val downloadable: Boolean get() = false
    fun toRaw(): RawVideoResult? = if (outcome == VerificationOutcome.PAGE_EVIDENCE &&
        evidence in setOf(VerificationEvidence.HTML_VIDEO, VerificationEvidence.DYNAMIC_VIDEO_XHR))
        RawVideoResult(pageUrl = pageUrl, videoConfirmed = true, title = title,
            thumbnailUrl = thumbnailUrl, durationMs = durationMs) else null
    override fun toString(): String = "VerificationResult(outcome=$outcome, evidence=$evidence, metadata=[redacted])"
}

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
