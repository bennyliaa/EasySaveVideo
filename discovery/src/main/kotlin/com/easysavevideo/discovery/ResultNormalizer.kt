package com.easysavevideo.discovery

import com.easysavevideo.core.model.PageAddress
import java.net.URI
import java.util.Locale

/** Conservative identity: retain query parameters, path casing, escapes and fragments. */
object ResultNormalizer {
    fun canonicalUrl(value: String): String? { return try {
        if (!isWellFormedUnicode(value)) return null
        PageAddress(value)
        val uri = URI(value)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.rawUserInfo != null || host.isBlank()) return null
        val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        "$scheme://$host${if (port == -1) "" else ":$port"}$path" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    } catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null } }

    fun normalize(raw: RawVideoResult, providerId: String, filters: SearchFilters = SearchFilters()): VideoSearchResult? {
        if (!raw.videoConfirmed) return null
        if (listOfNotNull(raw.title, raw.sourceName, raw.sourceNamespace, raw.sourceVideoId, raw.description).any { !isWellFormedUnicode(it) }) return null
        try { RightsCodec.validate(raw.rights) } catch (_: IllegalArgumentException) { return null }
        val url = canonicalUrl(raw.pageUrl) ?: return null
        if (raw.title?.length?.let { it > 512 } == true || raw.sourceName?.length?.let { it > 128 } == true ||
            raw.description?.length?.let { it > 4096 } == true || raw.sourceNamespace?.length?.let { it > 128 } == true ||
            raw.sourceVideoId?.length?.let { it > 512 } == true || raw.durationMs?.let { it < 0 } == true ||
            raw.publishedAtEpochMillis?.let { it < 0 } == true) return null
        val namespace = raw.sourceNamespace?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        val videoId = raw.sourceVideoId?.trim()?.takeIf { it.isNotEmpty() }
        if (filters.sourceNamespaces.isNotEmpty() && namespace !in filters.sourceNamespaces.map { it.lowercase(Locale.ROOT) }) return null
        val duration = raw.durationMs?.takeIf { it > 0 }
        if (duration == null && !filters.includeUnknownDuration) return null
        if (duration != null && (filters.minimumDurationMs?.let { duration < it } == true || filters.maximumDurationMs?.let { duration > it } == true)) return null
        if (clean(raw.sourceName) == null && URI(url).host.length > 128) return null
        val urlIdentity = digest(framed("url-v1", url))
        val identity = if (namespace != null && videoId != null) digest(framed("source-v1", namespace, videoId)) else urlIdentity
        return VideoSearchResult(identity, url, clean(raw.title), clean(raw.sourceName) ?: URI(url).host,
            namespace, videoId, raw.thumbnailUrl?.let(::canonicalUrl), duration, clean(raw.description),
            raw.publishedAtEpochMillis, setOf(providerId), setOf(identity, urlIdentity), rights = raw.rights.detached())
    }

    private fun clean(value: String?): String? = value?.filter { it.code >= 32 || it == '\n' || it == '\t' }?.trim()?.takeIf { it.isNotEmpty() }
}
