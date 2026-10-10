package com.easysavevideo.verification.local

import com.easysavevideo.discovery.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import org.jsoup.Jsoup

/** Test-source-only development experiment. No transport, production registration or activation. */
internal class DevelopmentCommonsStaticFixturePolicy(private val offlineSelected: Boolean = false) : VerificationSourcePolicy {
    companion object {
        const val ORIGIN = "https://commons.wikimedia.org:443"
        const val MAX_HTML_BYTES = 2 * 1024 * 1024
        private val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    }
    // Even an explicitly selected OFFLINE experiment cannot authorize a live B request.
    override fun network(candidate: CandidateUrl) = EngineNetworkConstraints(
        emptySet(), requireTls = true, maxRedirects = 0, sourceAdmissionRequired = true)

    fun offlineSelection(): VerificationSourceSelection = requireNotNull(
        CompiledVerificationSources(if (offlineSelected) mapOf("commons-static-development" to setOf(ORIGIN)) else emptyMap())
            .configure(if (offlineSelected) setOf("commons-static-development") else emptySet(), offlineSelected))

    fun canonicalFilePage(value: String): Boolean = try {
        val uri = URI(value)
        if (value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' } ||
            uri.scheme != "https" || uri.rawAuthority !in setOf("commons.wikimedia.org", "commons.wikimedia.org:443") ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.startsWith("/wiki/File:")) false
        else {
            val encoded = uri.rawPath.removePrefix("/wiki/File:")
            val decoded = uri.path.removePrefix("/wiki/File:")
            decoded.isNotEmpty() && decoded.length <= 512 && decoded !in setOf(".", "..") &&
                decoded.none { it.isWhitespace() || it.isISOControl() || it in "/\\%?#:[]" } &&
                Normalizer.isNormalized(decoded, Normalizer.Form.NFC) &&
                decoded.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                    val n = byte.toInt() and 255
                    if (n.toChar() in unreserved) n.toChar().toString() else "%%%02X".format(n)
                } == encoded
        }
    } catch (_: Exception) { false }

    enum class Outcome { DISABLED, DENIED, RATE_LIMITED, SERVICE_STOP, CANCELLED, TIMEOUT, OVER_BUDGET, UNKNOWN, PAGE_EVIDENCE }
    class Result(val outcome: Outcome, val evidence: EngineEvidence = EngineEvidence.NONE) {
        val playbackVerified = false
        val savingAuthorized = false
        val resourceExtractionVerified = false
        override fun toString() = "CommonsFixtureResult(outcome=$outcome,evidence=$evidence)"
    }
    fun evaluateOffline(page: String, status: Int, decompressedHtml: ByteArray,
        cancelled: Boolean = false, elapsedMillis: Long = 0, requestsUsed: Int = 1): Result {
        fun result(outcome: Outcome) = Result(outcome)
        if (!offlineSelected) return result(Outcome.DISABLED)
        if (cancelled) return result(Outcome.CANCELLED)
        if (elapsedMillis >= 20_000) return result(Outcome.TIMEOUT)
        if (elapsedMillis < 0 || requestsUsed !in 1..12 || decompressedHtml.size > MAX_HTML_BYTES) return result(Outcome.OVER_BUDGET)
        if (!canonicalFilePage(page)) return result(Outcome.DENIED)
        if (status == 429) return result(Outcome.RATE_LIMITED)
        if (status == 503 || status in 500..599) return result(Outcome.SERVICE_STOP)
        if (status in setOf(401,403) || status in 300..399) return result(Outcome.DENIED)
        if (status != 200 || decompressedHtml.isEmpty()) return result(Outcome.UNKNOWN)
        val html = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decompressedHtml)).toString()
        } catch (_: Exception) { return result(Outcome.UNKNOWN) }
        if (listOf("captcha", "verify you are human", "cf-turnstile").any { html.contains(it, true) }) return result(Outcome.DENIED)
        val document = Jsoup.parse(html, page) // parses bytes only; never fetches any resource
        if (document.select("iframe,object,embed,audio").isNotEmpty()) return result(Outcome.UNKNOWN)
        val region = document.select("#file").singleOrNull() ?: return result(Outcome.UNKNOWN)
        val video = document.select("video").singleOrNull() ?: return result(Outcome.UNKNOWN)
        if (video !in region.select("video") || !video.hasAttr("controls") || video.hasAttr("src") ||
            video.hasAttr("hidden") || video.attr("aria-hidden").equals("true", true) ||
            (listOf(video) + video.parents()).any {
                it.hasAttr("hidden") || it.attr("aria-hidden").equals("true", true) ||
                    it.attr("style").lowercase().filterNot(Char::isWhitespace).let { s -> "display:none" in s || "visibility:hidden" in s }
            } || video.attr("width").toIntOrNull()?.let { it in 1..8192 } != true ||
            video.attr("height").toIntOrNull()?.let { it in 1..8192 } != true) return result(Outcome.UNKNOWN)
        val sources = video.select("source")
        if (sources.isEmpty() || sources.size > 4 || sources.any {
            !it.attr("type").matches(Regex("video/webm; codecs=\"(?:vp8|vp9|vp09|av01)(?:, (?:opus|vorbis))?\"")) ||
                !declaredMediaReference(it.attr("src"))
        }) return result(Outcome.UNKNOWN)
        // Feed the existing B static parser a finite structural projection. Media URLs are
        // discarded, not fetched/returned; this proves only offline HTML video-page evidence.
        val projected = "<main><video controls src='/offline-declaration-only.webm'></video></main>".toByteArray()
        return if (LocalHtmlEvidenceParser().parse(projected, page) != null)
            Result(Outcome.PAGE_EVIDENCE, EngineEvidence.HTML_VIDEO) else result(Outcome.UNKNOWN)
    }
    private fun declaredMediaReference(value: String): Boolean = try {
        val uri = URI(value)
        value.length in 1..4096 && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' } &&
            uri.scheme == "https" && uri.rawAuthority == "upload.wikimedia.org" && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.startsWith("/wikipedia/commons/") &&
            uri.rawPath.endsWith(".webm") && !uri.rawPath.contains('%') && uri.normalize() == uri
    } catch (_: Exception) { false }
}
