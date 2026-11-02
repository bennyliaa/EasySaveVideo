package com.easysavevideo.commons

import java.net.URI
import java.math.BigDecimal
import java.math.RoundingMode

internal class CommonsResponseParser(private val pageSize: Int) {
    fun parse(bytes: ByteArray, key: String, now: Long, cursorExpires: Long, check: () -> Unit): CommonsMetadataPage {
        val root = StrictJson.read(bytes, check).objectValue()
        if ("error" in root || "errors" in root) {
            val code = (root["error"] as? Json.Obj)?.fields?.get("code")?.stringOrNull()
            throw CommonsFailure(if (code in setOf("maxlag", "ratelimited")) CommonsError.RATE_LIMITED else CommonsError.SERVICE_ERROR)
        }
        // Unknown/deprecated parameters can silently remove constraints; fail instead of degrading.
        if ("warnings" in root) invalidResponse()
        // A challenge page, invalid JSON or a service-error envelope is not evidence against a
        // parameter package. Only recognizable API JSON with incompatible semantic structure is
        // eligible for the local observation counter; this does not prove defective/malicious code.
        val recognizable = "query" in root || "batchcomplete" in root || "continue" in root
        return try { parseRoot(root, bytes.size, now, key, cursorExpires, check) }
        catch (failure: CommonsFailure) {
            if (recognizable && failure.code == CommonsError.INVALID_RESPONSE)
                throw CommonsFailure(failure.code, structureFailure = true)
            throw failure
        }
    }
    private fun parseRoot(root: Map<String, Json>, byteCount: Int, now: Long, key: String,
        cursorExpires: Long, check: () -> Unit): CommonsMetadataPage {
        if ("batchcomplete" in root && root["batchcomplete"] != Json.Bool(true)) invalidResponse()
        val query = root["query"]?.objectValue()
        val pages = if (query != null) (query["pages"] as? Json.Arr)?.values ?: invalidResponse() else emptyList()
        if (pages.size > pageSize) invalidResponse()
        if (query == null && "batchcomplete" !in root && "continue" !in root) invalidResponse()
        val results = mutableListOf<Pair<Long, CommonsVideoMetadata>>()
        val seen = mutableSetOf<Long>()
        for (pageValue in pages) {
            check(); val page = pageValue.objectValue()
            if ("missing" in page || "invalid" in page) continue
            val pageId = page["pageid"]?.longOrNull()?.takeIf { it > 0 } ?: invalidResponse()
            if (!seen.add(pageId)) invalidResponse()
            if (page["ns"]?.longOrNull() != 6L) continue
            val title = page["title"]?.stringOrNull()?.takeIf { it.startsWith("File:") && it.length in 6..1024 } ?: continue
            val infos = (page["videoinfo"] as? Json.Arr)?.values ?: continue
            if (infos.size > 1) invalidResponse()
            val info = infos.singleOrNull()?.objectValue() ?: continue
            if ("filehidden" in info || "suppressed" in info) continue
            val mime = info["mime"]?.stringOrNull()?.lowercase() ?: continue
            if (!mime.startsWith("video/") || !mime.matches(Regex("video/[a-z0-9.+-]{1,64}"))) continue
            if (info["mediatype"]?.stringOrNull() != "VIDEO") continue
            val pageUrl = (info["descriptionurl"]?.stringOrNull() ?: page["fullurl"]?.stringOrNull())
                ?.let(CommonsUrlPolicy::page) ?: continue
            if (URI(pageUrl).path.removePrefix("/wiki/").replace('_', ' ') != title.replace('_', ' ')) continue
            val rights = rights(info["extmetadata"], pageUrl, page["lastrevid"]?.longOrNull()?.takeIf { it > 0 }, now)
            val thumbnail = info["thumburl"]?.stringOrNull()?.let(CommonsUrlPolicy::thumbnail)
            // videoinfo.duration is seconds, confirmed in the recorded Commons API response.
            // Missing, malformed, negative or overflow values remain unknown, never zero.
            val durationMs = duration(info["duration"])
            val plainTitle = CommonsText.plain(title, 1024).text ?: continue
            val video = CommonsVideoMetadata(pageId, plainTitle, pageUrl, thumbnail, durationMs,
                dimension(info["width"]), dimension(info["height"]), mime, rights)
            results += (page["index"]?.longOrNull() ?: Long.MAX_VALUE) to video
        }
        val continuation = root["continue"]?.let(CommonsCursor::fields)
        val cursor = continuation?.let { CommonsCursor.encode(it, key, cursorExpires) }
        return CommonsMetadataPage(results.sortedBy { it.first }.map { it.second }, cursor,
            if (cursor == null) null else cursorExpires, continuation == null, byteCount.toLong())
    }
    private fun duration(value: Json?): Long? = try {
        val seconds = (value as? Json.Num)?.raw?.let(::BigDecimal)
        seconds?.takeIf { it.signum() > 0 && it <= BigDecimal("31536000") }
            ?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()?.takeIf { it > 0 }
    } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
    private fun dimension(value: Json?): Int? = value?.longOrNull()?.takeIf { it in 1..100_000 }?.toInt()
    private fun rights(value: Json?, pageUrl: String, revision: Long?, now: Long): CommonsRights {
        val metadata = value?.objectValue() ?: emptyMap()
        val provenance = mutableListOf<CommonsFieldProvenance>()
        fun text(name: String): String? {
            val entry = metadata[name]?.objectValue() ?: return null
            val raw = entry["value"] ?: return null
            val original = when (raw) {
                is Json.Str -> raw.value
                is Json.Bool -> raw.value.toString()
                is Json.Arr -> raw.values.map { it.stringOrNull() ?: invalidResponse() }.joinToString("; ")
                else -> return null
            }
            val plain = CommonsText.plain(original)
            val source = entry["source"]?.stringOrNull()?.let { CommonsText.plain(it, 128).text }
            provenance += CommonsFieldProvenance(name, source, htmlConvertedToText = plain.changed, truncated = plain.truncated)
            return plain.text
        }
        val author = text("Artist"); val credit = text("Credit"); val attribution = text("Attribution")
        val licenseName = text("LicenseShortName"); val licenseUrl = text("LicenseUrl")?.let(CommonsUrlPolicy::license)
        val usage = text("UsageTerms"); val copyrighted = booleanish(text("Copyrighted"))
        val attributionRequired = booleanish(text("AttributionRequired")); val nonFree = booleanish(text("NonFree"))
        val restrictions = text("Restrictions")?.split(';', '|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (restrictions.size > 32) invalidResponse()
        val deletion = text("DeletionReason")
        val review = when {
            nonFree == true || restrictions.isNotEmpty() || deletion != null -> CommonsRightsReview.FLAGGED_REQUIRES_REVIEW
            author == null || licenseName == null || licenseUrl == null || provenance.any { it.truncated } -> CommonsRightsReview.INCOMPLETE
            else -> CommonsRightsReview.REPORTED_REQUIRES_REVIEW
        }
        return CommonsRights(pageUrl, revision, now, author, credit, attribution, licenseName, licenseUrl, usage,
            copyrighted, attributionRequired, nonFree, restrictions, deletion, review, provenance.toList())
    }
    private fun booleanish(value: String?): Boolean? = when (value?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
