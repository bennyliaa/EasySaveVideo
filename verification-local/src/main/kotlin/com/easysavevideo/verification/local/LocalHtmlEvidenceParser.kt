package com.easysavevideo.verification.local

import com.easysavevideo.discovery.RawVideoResult
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.jsoup.Jsoup

/** B's static parsing component. No network, scripts, downloaded rules or matching memory. */
class LocalHtmlEvidenceParser {
    data class PageEvidence(val raw: RawVideoResult, val sourceCandidate: String) {
        override fun toString()="PageEvidence([redacted])"
    }
    fun parse(bytes: ByteArray, pageUrl: String): PageEvidence? {
        if (bytes.size !in 1..262144) return null
        val base = safeUri(pageUrl) ?: return null
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return null }
        val document=Jsoup.parse(text,pageUrl)
        // Two compiled structural checks, not an interpreted rule language.
        val mains=document.select("main")
        val videos=document.select("video")
        if (mains.size != 1 || videos.size != 1 || document.select("iframe,object,embed").isNotEmpty()) return null
        val video=mains.single().select("video").singleOrNull() ?: return null
        // This first admitted fixture strategy requires an explicit primary marker.
        // An unrelated article's sole video must not be promoted to primary evidence.
        if (video.attr("data-esv-primary") != "true") return null
        if (video.hasAttr("hidden") || video.attr("aria-hidden").equals("true",true)) return null
        val declarations=(listOf(video.attr("src"))+video.select("source").map {it.attr("src")})
            .filter {it.isNotBlank()}.distinct()
        if (declarations.size != 1) return null
        val source=try {base.resolve(declarations.single()).toString()} catch (_: Exception) {return null}
        if (safeUri(source) == null) return null
        val title=mains.single().select("h1").singleOrNull()?.text()?.takeIf {
            it.isNotBlank() && it.length<=512 && it.none {c -> c.isISOControl()}
        }
        return PageEvidence(RawVideoResult(pageUrl,true,title=title),source)
    }
    private fun safeUri(text:String):URI? = try {
        URI(text).takeIf {it.scheme in setOf("https","http") && !it.host.isNullOrBlank() &&
            it.rawUserInfo==null && it.rawFragment==null && text.length<=4096 &&
            text.none {c -> c.isISOControl() || c=='\\'}}
    } catch (_:Exception) {null}
}
