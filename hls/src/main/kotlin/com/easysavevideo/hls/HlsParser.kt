package com.easysavevideo.hls

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Original, intentionally strict RFC 8216 subset parser; no third-party parser code or runtime.
 * Only static, full-segment VOD is accepted. A parsed media playlist is a plan candidate, NOT proof
 * that bytes are MPEG-TS, AVC Baseline/no-B, optional multiplexed AAC-LC or a continuous timeline.
 * Unknown EXT tags and unsupported attributes fail closed, stricter than a general HLS player.
 * Rendition groups/unsupported branches reject the entire master in this initial profile.
 * Reference: https://www.rfc-editor.org/rfc/rfc8216.html (sections 4 and 7).
 */
class HlsParser {
    fun parse(bytes: ByteArray, location: HlsPlaylistLocation, budget: HlsPlanningBudget): HlsPlaylist {
        budget.chargeManifest(bytes.size)
        val limits = budget.limits
        ensure(location.requestedUri.value.toByteArray(Charsets.UTF_8).size <= limits.maxUriBytes, HlsError.LIMIT_EXCEEDED)
        ensure(location.responseUri.value.toByteArray(Charsets.UTF_8).size <= limits.maxUriBytes, HlsError.LIMIT_EXCEEDED)
        // Decode and hash the same bounded snapshot even if a caller later reuses its buffer.
        val snapshot = bytes.copyOf()
        val lines = decodeLines(snapshot, limits)
        ensure(lines.firstOrNull() == "#EXTM3U")
        val contentIdentity = digestBytes(snapshot)
        var version = 1
        var versionSeen = false
        var independent = false
        var master = false
        var media = false
        var pendingVariant: HlsVariantMetadata? = null
        var pendingDuration: Long? = null
        var target: Long? = null
        var sequence = 0L
        var sequenceSeen = false
        var playlistTypeSeen = false
        var ended = false
        var fractionalDuration = false
        var total = 0L
        val variants = ArrayList<HlsVariant>()
        val segments = ArrayList<HlsMediaSegment>()
        fun markMaster() { ensure(!media, HlsError.MIXED_PLAYLIST_TYPES); master = true }
        fun markMedia() { ensure(!master, HlsError.MIXED_PLAYLIST_TYPES); media = true }
        for (line in lines.drop(1)) {
            if (line.isEmpty() || (line.startsWith('#') && !line.startsWith("#EXT"))) continue
            if (!line.startsWith('#')) {
                ensure(!ended)
                val uri = resolve(location.responseUri, line, limits.maxUriBytes)
                if (pendingVariant != null) {
                    ensure(variants.size < limits.maxVariants, HlsError.LIMIT_EXCEEDED)
                    ensure(uri != location.requestedUri && uri != location.responseUri, HlsError.PLAYLIST_LOOP)
                    val index = variants.size
                    variants.add(HlsVariant(
                        digest("hls-variant-v1", contentIdentity, location.responseUri.identity, index.toString(), uri.identity),
                        index, uri, pendingVariant,
                    ))
                    pendingVariant = null
                } else {
                    markMedia()
                    val duration = pendingDuration ?: fail(HlsError.INVALID_PLAYLIST)
                    ensure(segments.size < limits.maxSegments, HlsError.LIMIT_EXCEEDED)
                    ensure(sequence <= Long.MAX_VALUE - segments.size, HlsError.INVALID_NUMBER)
                    ensure(duration <= limits.maxDurationUs - total, HlsError.LIMIT_EXCEEDED)
                    val segmentSequence = sequence + segments.size
                    // Identical URLs at different sequences remain distinct work; never deduplicate.
                    val identity = digest("hls-segment-v1", location.responseUri.identity,
                        segmentSequence.toString(), uri.identity, duration.toString())
                    segments.add(HlsMediaSegment(segments.size, segmentSequence, duration, total, uri, identity))
                    total += duration
                    pendingDuration = null
                }
                continue
            }
            // STREAM-INF's next non-comment line must be its URI, not another tag.
            ensure(pendingVariant == null)
            val separator = line.indexOf(':')
            val tag = if (separator < 0) line else line.substring(0, separator)
            val value = if (separator < 0) null else line.substring(separator + 1)
            fun requiredValue(): String = value?.takeIf { it.isNotEmpty() } ?: fail(HlsError.INVALID_PLAYLIST)
            fun noValue() { ensure(value == null) }
            when (tag) {
                "#EXTM3U" -> fail(HlsError.INVALID_PLAYLIST)
                "#EXT-X-VERSION" -> {
                    ensure(!versionSeen && !ended)
                    val n = positiveLong(requiredValue())
                    ensure(n in 1..7, HlsError.UNSUPPORTED_VERSION)
                    version = n.toInt(); versionSeen = true
                }
                "#EXT-X-INDEPENDENT-SEGMENTS" -> {
                    noValue(); ensure(!independent && !ended); independent = true
                }
                "#EXT-X-STREAM-INF" -> {
                    markMaster(); ensure(!ended)
                    pendingVariant = parseVariant(requiredValue(), limits)
                }
                "#EXT-X-TARGETDURATION" -> {
                    markMedia(); ensure(target == null && segments.isEmpty() && pendingDuration == null && !ended)
                    target = positiveLong(requiredValue())
                }
                "#EXT-X-MEDIA-SEQUENCE" -> {
                    markMedia(); ensure(!sequenceSeen && segments.isEmpty() && pendingDuration == null && !ended)
                    sequence = nonnegativeLong(requiredValue()); sequenceSeen = true
                }
                "#EXT-X-PLAYLIST-TYPE" -> {
                    markMedia(); ensure(!playlistTypeSeen && segments.isEmpty() && pendingDuration == null && !ended)
                    ensure(requiredValue() == "VOD", HlsError.NOT_STATIC_VOD); playlistTypeSeen = true
                }
                "#EXTINF" -> {
                    markMedia(); ensure(pendingDuration == null && !ended)
                    val text = requiredValue()
                    val comma = text.indexOf(',')
                    ensure(comma > 0)
                    val durationText = text.substring(0, comma)
                    if (durationText.contains('.')) fractionalDuration = true
                    pendingDuration = decimalScaled(durationText, 6)
                    ensure(pendingDuration > 0, HlsError.INVALID_NUMBER)
                    // The optional title is deliberately discarded, not persisted or logged.
                }
                "#EXT-X-ENDLIST" -> {
                    markMedia(); noValue(); ensure(!ended && pendingDuration == null); ended = true
                }
                "#EXT-X-KEY" -> {
                    markMedia(); ensure(!ended)
                    val attrs = attributes(requiredValue(), limits)
                    val method = attrs["METHOD"] ?: fail(HlsError.INVALID_ATTRIBUTE)
                    ensure(!method.quoted, HlsError.INVALID_ATTRIBUTE)
                    ensure(method.text == "NONE", HlsError.PROTECTED_MEDIA)
                    // METHOD=NONE may not smuggle URI, KEYFORMAT or IV into a supported plan.
                    ensure(attrs.size == 1, HlsError.INVALID_ATTRIBUTE)
                }
                "#EXT-X-SESSION-KEY" -> fail(HlsError.PROTECTED_MEDIA)
                "#EXT-X-MEDIA" -> fail(HlsError.UNSUPPORTED_RENDITION)
                "#EXT-X-MAP", "#EXT-X-BYTERANGE" -> fail(HlsError.UNSUPPORTED_CONTAINER)
                "#EXT-X-DISCONTINUITY", "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-GAP" ->
                    fail(HlsError.UNSUPPORTED_TIMELINE)
                else -> fail(HlsError.UNSUPPORTED_TAG)
            }
        }
        ensure(pendingVariant == null && pendingDuration == null)
        if (master) {
            ensure(variants.isNotEmpty())
            // RFC 8216: one CLOSED-CAPTIONS=NONE declaration requires it on every variant.
            ensure(!variants.any { it.metadata.declaresNoClosedCaptions } ||
                variants.all { it.metadata.declaresNoClosedCaptions })
            return HlsMasterPlaylist(location, contentIdentity, bytes.size, version, independent, variants)
        }
        ensure(media && segments.isNotEmpty())
        ensure(ended, HlsError.NOT_STATIC_VOD)
        val targetSeconds = target ?: fail(HlsError.INVALID_PLAYLIST)
        ensure(!fractionalDuration || version >= 3, HlsError.UNSUPPORTED_VERSION)
        for (segment in segments) {
            // RFC target duration compares to duration rounded to nearest integer, not raw decimal.
            val rounded = segment.durationUs / 1_000_000 + if (segment.durationUs % 1_000_000 >= 500_000) 1 else 0
            ensure(rounded <= targetSeconds)
        }
        return HlsMediaPlaylist(location, contentIdentity, bytes.size, version, independent,
            targetSeconds, sequence, total, segments)
    }

    private fun parseVariant(text: String, limits: HlsLimits): HlsVariantMetadata {
        val attrs = attributes(text, limits)
        for ((name, _) in attrs) {
            if (name in setOf("AUDIO", "VIDEO", "SUBTITLES")) fail(HlsError.UNSUPPORTED_RENDITION)
            ensure(name in setOf("BANDWIDTH", "AVERAGE-BANDWIDTH", "CODECS", "RESOLUTION", "FRAME-RATE", "CLOSED-CAPTIONS", "HDCP-LEVEL"), HlsError.UNSUPPORTED_TAG)
        }
        fun unquoted(name: String): String? = attrs[name]?.let { ensure(!it.quoted, HlsError.INVALID_ATTRIBUTE); it.text }
        ensure(unquoted("CLOSED-CAPTIONS")?.let { it == "NONE" } != false, HlsError.UNSUPPORTED_RENDITION)
        ensure(unquoted("HDCP-LEVEL")?.let { it == "NONE" } != false, HlsError.PROTECTED_MEDIA)
        val bandwidth = positiveLong(unquoted("BANDWIDTH") ?: fail(HlsError.INVALID_ATTRIBUTE))
        val average = unquoted("AVERAGE-BANDWIDTH")?.let(::positiveLong)
        val resolution = unquoted("RESOLUTION")?.let {
            ensure(it.matches(Regex("[0-9]+x[0-9]+")), HlsError.INVALID_ATTRIBUTE)
            val pair = it.split('x').map(::positiveLong)
            ensure(pair.all { n -> n <= Int.MAX_VALUE }, HlsError.INVALID_NUMBER)
            pair[0].toInt() to pair[1].toInt()
        }
        val frameRate = unquoted("FRAME-RATE")?.let { decimalScaled(it, 3).also { n -> ensure(n > 0, HlsError.INVALID_NUMBER) } }
        val codecs = attrs["CODECS"]?.let {
            ensure(it.quoted, HlsError.INVALID_ATTRIBUTE)
            val names = it.text.split(',')
            ensure(names.size in 1..2 && names.distinct().size == names.size, HlsError.UNSUPPORTED_CODEC_DECLARATION)
            val avc = names.singleOrNull { name -> name.matches(Regex("avc1\\.[0-9A-Fa-f]{6}")) }
                ?: fail(HlsError.UNSUPPORTED_CODEC_DECLARATION)
            ensure(names.all { name -> name == avc || name == "mp4a.40.2" }, HlsError.UNSUPPORTED_CODEC_DECLARATION)
            val profileLevel = avc.substring(5).toInt(16)
            ensure((profileLevel ushr 16) == 0x42, HlsError.UNSUPPORTED_CODEC_DECLARATION)
            HlsCodecDeclaration(profileLevel, names.contains("mp4a.40.2"))
        }
        return HlsVariantMetadata(bandwidth, average, resolution?.first, resolution?.second, frameRate, codecs, attrs.containsKey("CLOSED-CAPTIONS"))
    }

    private class Attribute(val text: String, val quoted: Boolean) {
        override fun toString() = "Attribute([redacted])"
    }
    private fun attributes(text: String, limits: HlsLimits): Map<String, Attribute> {
        val result = LinkedHashMap<String, Attribute>()
        var i = 0
        while (i < text.length) {
            ensure(result.size < limits.maxAttributes, HlsError.LIMIT_EXCEEDED)
            val start = i
            while (i < text.length && (text[i] in 'A'..'Z' || text[i] in '0'..'9' || text[i] == '-')) i++
            ensure(i > start && i < text.length && text[i] == '=', HlsError.INVALID_ATTRIBUTE)
            val name = text.substring(start, i++)
            ensure(!result.containsKey(name) && i < text.length, HlsError.INVALID_ATTRIBUTE)
            val quoted = text[i] == '"'
            val valueStart: Int
            val value: String
            if (quoted) {
                i++; valueStart = i
                while (i < text.length && text[i] != '"') i++
                ensure(i < text.length, HlsError.INVALID_ATTRIBUTE)
                value = text.substring(valueStart, i++)
            } else {
                valueStart = i
                while (i < text.length && text[i] != ',') {
                    ensure(!text[i].isWhitespace() && text[i] != '"' && text[i] != '=', HlsError.INVALID_ATTRIBUTE)
                    i++
                }
                ensure(i > valueStart, HlsError.INVALID_ATTRIBUTE)
                value = text.substring(valueStart, i)
            }
            result[name] = Attribute(value, quoted)
            if (i < text.length) {
                ensure(text[i] == ',' && i + 1 < text.length, HlsError.INVALID_ATTRIBUTE)
                i++
            }
        }
        ensure(result.isNotEmpty(), HlsError.INVALID_ATTRIBUTE)
        return result
    }
}

private fun nonnegativeLong(text: String): Long {
    ensure(text.isNotEmpty() && text.length <= 20 && text.all { it in '0'..'9' }, HlsError.INVALID_NUMBER)
    return text.toLongOrNull() ?: fail(HlsError.INVALID_NUMBER)
}
private fun positiveLong(text: String): Long = nonnegativeLong(text).also { ensure(it > 0, HlsError.INVALID_NUMBER) }
private fun decimalScaled(text: String, places: Int): Long {
    ensure(text.length in 1..40 && text.matches(Regex("[0-9]+(?:\\.[0-9]+)?")), HlsError.INVALID_NUMBER)
    return try {
        BigDecimal(text).movePointRight(places).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: ArithmeticException) { fail(HlsError.INVALID_NUMBER) }
}

private fun decodeLines(bytes: ByteArray, limits: HlsLimits): List<String> {
    ensure(bytes.isNotEmpty())
    var start = 0
    var lineCount = 0
    var i = 0
    while (i < bytes.size) {
        if (bytes[i] == 13.toByte()) ensure(i + 1 < bytes.size && bytes[i + 1] == 10.toByte())
        if (bytes[i] == 10.toByte()) {
            val length = i - start - if (i > start && bytes[i - 1] == 13.toByte()) 1 else 0
            ensure(length <= limits.maxLineBytes, HlsError.LIMIT_EXCEEDED)
            ensure(++lineCount <= limits.maxLines, HlsError.LIMIT_EXCEEDED)
            start = i + 1
        }
        i++
    }
    if (start < bytes.size) {
        ensure(bytes.size - start <= limits.maxLineBytes, HlsError.LIMIT_EXCEEDED)
        ensure(++lineCount <= limits.maxLines, HlsError.LIMIT_EXCEEDED)
    }
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { fail(HlsError.INVALID_UTF8) }
    ensure(!text.contains('\uFEFF'))
    ensure(text.none { (it.code in 0..31 && it != '\r' && it != '\n') || it.code in 127..159 })
    ensure(Normalizer.isNormalized(text, Normalizer.Form.NFC))
    return text.split('\n').map { it.removeSuffix("\r") }
}

/**
 * Original RFC 3986 section 5.2 resolution using raw parsed components. java.net.URI supplies
 * syntax validation but its RFC 2396 resolver collapses empty path segments and treats a query-only
 * reference differently. Neither behavior is safe for signed resource identities.
 */
private fun resolve(base: HlsUri, reference: String, maxBytes: Int): HlsUri {
    ensure(reference.toByteArray(Charsets.UTF_8).size in 1..maxBytes, HlsError.LIMIT_EXCEEDED)
    ensure(reference.none { it.isWhitespace() || it.code < 32 || it.code in 127..159 }, HlsError.INVALID_URI)
    val ref = try { URI(reference) } catch (_: Exception) { fail(HlsError.INVALID_URI) }
    ensure(ref.rawFragment == null && !ref.isOpaque, HlsError.INVALID_URI)
    // JDK parsing maps an explicitly empty authority to null; never inherit the base host for it.
    ensure(!reference.startsWith("//") || ref.rawAuthority != null, HlsError.INVALID_URI)
    val ownLocation = ref.isAbsolute || ref.rawAuthority != null
    val scheme = ref.scheme ?: base.parsed.scheme
    val authority = if (ownLocation) ref.rawAuthority else base.parsed.rawAuthority
    val refPath = ref.rawPath.orEmpty()
    val basePath = base.parsed.rawPath.orEmpty()
    val path = when {
        ownLocation -> removeDotSegments(refPath)
        refPath.isEmpty() -> basePath
        refPath.startsWith('/') -> removeDotSegments(refPath)
        else -> {
            val directory = if (basePath.isEmpty()) "/" else basePath.substring(0, basePath.lastIndexOf('/') + 1)
            removeDotSegments(directory + refPath)
        }
    }
    val query = if (!ownLocation && refPath.isEmpty()) ref.rawQuery ?: base.parsed.rawQuery else ref.rawQuery
    // Recompose raw syntax. Component-taking URI constructors would double-encode '%' escapes.
    val resolved = buildString {
        append(scheme); append(':')
        if (authority != null) { append("//"); append(authority) }
        append(path)
        if (query != null) { append('?'); append(query) }
    }
    return HlsUri.parse(resolved, maxBytes)
}

/** Only literal dot segments have navigation semantics; empty and percent-encoded segments survive. */
private fun removeDotSegments(path: String): String {
    if (path.isEmpty()) return path
    // HTTPS authority paths and all paths merged with a validated HTTPS base are absolute.
    ensure(path.startsWith('/'), HlsError.INVALID_URI)
    val input = path.substring(1).split('/')
    val output = ArrayList<String>()
    input.forEachIndexed { index, segment ->
        when (segment) {
            "." -> if (index == input.lastIndex) output.add("")
            ".." -> {
                if (output.isNotEmpty()) output.removeAt(output.lastIndex)
                if (index == input.lastIndex) output.add("")
            }
            else -> output.add(segment)
        }
    }
    return "/" + output.joinToString("/")
}
