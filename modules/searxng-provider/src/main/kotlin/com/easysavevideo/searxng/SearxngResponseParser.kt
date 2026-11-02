package com.easysavevideo.searxng

import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import java.net.URI

/** The category is an engine hint, not video evidence. Every returned item stays unverified. */
internal object SearxngResponseParser {
    fun parse(bytes: ByteArray, maxCandidates: Int, check: () -> Unit): List<CandidateUrl> {
        val root = StrictJson.read(bytes, check).objectValue()
        val rows = (root["results"] as? Json.Arr)?.values ?: invalidResponse()
        if (rows.size > maxCandidates) throw SearxngFailure(SearxngError.TOO_MANY_CANDIDATES)
        val found = linkedMapOf<String, CandidateUrl>()
        for (row in rows) {
            check()
            val fields = row.objectValue()
            val rawUrl = fields["url"]?.stringOrNull() ?: continue
            val url = SearxngUrlPolicy.candidate(rawUrl) ?: continue
            val title = text(fields["title"], 512, check)
            val description = text(fields["content"], 2048, check)
            found.putIfAbsent(url, CandidateUrl(url, sourceReported = SourceReportedCandidateMetadata(
                title = title, sourceName = URI(url).host, description = description)))
        }
        check()
        return found.values.toList()
    }

    /** Plain text only. Drop markup and controls; never render/fetch supplied HTML or images. */
    private fun text(value: Json?, maximum: Int, check: () -> Unit): String? {
        val input = when (value) {
            null, Json.Nil -> return null
            is Json.Str -> value.value
            else -> invalidResponse()
        }
        val out = StringBuilder()
        var tag = false
        var whitespace = false
        for ((index, char) in input.withIndex()) {
            if (index % 256 == 0) check()
            when {
                char == '<' -> { tag = true; whitespace = true }
                char == '>' && tag -> tag = false
                tag -> Unit
                char.isWhitespace() || char.isISOControl() -> whitespace = true
                else -> {
                    if (whitespace && out.isNotEmpty()) out.append(' ')
                    whitespace = false
                    out.append(char)
                }
            }
            if (out.length >= maximum) break
        }
        return out.toString().take(maximum).dropLastWhile { it.isHighSurrogate() }.trim().takeIf { it.isNotEmpty() }
    }
}
