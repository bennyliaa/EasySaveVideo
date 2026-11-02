package com.easysavevideo.hls

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

/** Technical codes only. Neither messages nor causes retain manifest text, URLs or credentials. */
enum class HlsError {
    INVALID_UTF8, INVALID_PLAYLIST, INVALID_ATTRIBUTE, INVALID_NUMBER, INVALID_URI,
    INSECURE_URI, LIMIT_EXCEEDED, UNSUPPORTED_VERSION, UNSUPPORTED_TAG, UNSUPPORTED_RENDITION,
    UNSUPPORTED_CODEC_DECLARATION, UNSUPPORTED_CONTAINER, UNSUPPORTED_TIMELINE,
    PROTECTED_MEDIA, NOT_STATIC_VOD, MIXED_PLAYLIST_TYPES, PLAYLIST_LOOP, NESTED_MASTER,
    SELECTION_MISMATCH, IDENTITY_MISMATCH,
}
class HlsFailure(val code: HlsError) : RuntimeException(code.name)
internal fun fail(code: HlsError): Nothing = throw HlsFailure(code)
internal fun ensure(ok: Boolean, code: HlsError = HlsError.INVALID_PLAYLIST) { if (!ok) fail(code) }

/**
 * Composition-root choices, not Discovery's page budget. No implicit product limits.
 * A conservative candidate is 2 MiB/manifest, 8 MiB/task, 8 manifest parses, 16 KiB/line,
 * 100,000 lines, 8 KiB/URI, 32 attributes, 64 variants, 20,000 segments and 24 hours.
 * These are parsing limits only: transport MUST also enforce Save's original absolute deadline,
 * shared lifetime network-byte budget, free-space reservation and chunk cancellation, including
 * redirects, retry bodies and failed responses. A supplied byte array is already downloaded.
 */
data class HlsLimits(
    val maxManifestBytes: Int,
    val maxTotalManifestBytes: Long,
    val maxManifestCount: Int,
    val maxLineBytes: Int,
    val maxLines: Int,
    val maxUriBytes: Int,
    val maxAttributes: Int,
    val maxVariants: Int,
    val maxSegments: Int,
    val maxDurationUs: Long,
) {
    init {
        require(maxManifestBytes > 0 && maxTotalManifestBytes >= maxManifestBytes)
        require(maxManifestCount > 0 && maxLineBytes > 0 && maxLineBytes <= maxManifestBytes)
        require(maxLines > 0 && maxUriBytes > 0 && maxUriBytes <= maxLineBytes)
        require(maxAttributes > 0 && maxVariants > 0 && maxSegments > 0 && maxDurationUs > 0)
    }
}

/** Create once per save task and reuse for every manifest attempt, including malformed retries. */
class HlsPlanningBudget(val limits: HlsLimits) {
    private var count = 0
    private var bytes = 0L
    @Synchronized internal fun chargeManifest(size: Int) {
        ensure(size >= 0, HlsError.LIMIT_EXCEEDED)
        // A rejected call never refreshes this task's remaining allowance.
        ensure(count < limits.maxManifestCount, HlsError.LIMIT_EXCEEDED)
        count++
        val room = limits.maxTotalManifestBytes - bytes
        if (size.toLong() > room) {
            bytes = limits.maxTotalManifestBytes
            fail(HlsError.LIMIT_EXCEEDED)
        }
        bytes += size
        ensure(size <= limits.maxManifestBytes, HlsError.LIMIT_EXCEEDED)
    }
    @Synchronized fun usage(): HlsPlanningUsage = HlsPlanningUsage(count, bytes)
    override fun toString() = "HlsPlanningBudget([bounded])"
}
data class HlsPlanningUsage(val manifestsCharged: Int, val manifestBytesCharged: Long)

/**
 * Memory-only request input, never journal this object or [value]. There are no credential headers
 * in this module. A transport guard MUST check every requested/redirected peer, TLS and credential
 * origin independently. HTTPS syntax here does not establish a public/safe network destination.
 */
class HlsUri private constructor(val value: String, internal val parsed: URI) {
    val identity: String = digest("hls-uri-v1", value)
    override fun toString() = "HlsUri([redacted])"
    override fun equals(other: Any?) = other is HlsUri && value == other.value
    override fun hashCode() = value.hashCode()

    companion object {
        fun parse(value: String, maxBytes: Int): HlsUri {
            ensure(maxBytes > 0 && value.length in 1..maxBytes, HlsError.LIMIT_EXCEEDED)
            // External request/response strings must not hash malformed UTF-16 as replacement bytes.
            val byteLength = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            } catch (_: CharacterCodingException) { fail(HlsError.INVALID_URI) }
            ensure(byteLength <= maxBytes, HlsError.LIMIT_EXCEEDED)
            ensure(value.none { it.isWhitespace() || it.code < 32 || it.code in 127..159 }, HlsError.INVALID_URI)
            val uri = try { URI(value) } catch (_: Exception) { fail(HlsError.INVALID_URI) }
            ensure(uri.isAbsolute && !uri.isOpaque && !uri.host.isNullOrEmpty(), HlsError.INVALID_URI)
            ensure(uri.scheme.equals("https", ignoreCase = true), HlsError.INSECURE_URI)
            ensure(uri.rawUserInfo == null && uri.rawFragment == null, HlsError.INVALID_URI)
            ensure(uri.port == -1 || uri.port in 1..65535, HlsError.INVALID_URI)
            return HlsUri(value, uri)
        }
    }
}

/** Both locations must come from the guarded transport; [responseUri] is the final response base. */
class HlsPlaylistLocation(val requestedUri: HlsUri, val responseUri: HlsUri) {
    override fun toString() = "HlsPlaylistLocation([redacted])"
}

/** Values here are declarations only, never proof of the downloaded elementary streams. */
data class HlsCodecDeclaration(val avcProfileLevel: Int, val declaresAacLc: Boolean)
data class HlsVariantMetadata(
    val bandwidth: Long,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val frameRateMilli: Long?,
    val codecs: HlsCodecDeclaration?,
    val declaresNoClosedCaptions: Boolean,
)
class HlsVariant internal constructor(
    val identity: String,
    val index: Int,
    val playlistUri: HlsUri,
    val metadata: HlsVariantMetadata,
) { override fun toString() = "HlsVariant(index=$index, uri=[redacted])" }

sealed class HlsPlaylist protected constructor(
    val location: HlsPlaylistLocation,
    val contentIdentity: String,
    val manifestByteLength: Int,
    val version: Int,
    val declaresIndependentSegments: Boolean,
) {
    override fun toString() = "HlsPlaylist([redacted])"
}
class HlsMasterPlaylist internal constructor(
    location: HlsPlaylistLocation,
    contentIdentity: String,
    manifestByteLength: Int,
    version: Int,
    independent: Boolean,
    variants: List<HlsVariant>,
) : HlsPlaylist(location, contentIdentity, manifestByteLength, version, independent) {
    val variants: List<HlsVariant> = immutable(variants)
    /** The caller must supply the user's exact variant identity. There is no automatic selection. */
    fun selectVariant(identity: String): HlsSelection {
        val variant = variants.singleOrNull { it.identity == identity } ?: fail(HlsError.SELECTION_MISMATCH)
        return HlsSelection(variant.identity, variant.playlistUri, contentIdentity, variant.metadata)
    }
    override fun toString() = "HlsMasterPlaylist(variants=${variants.size}, uri=[redacted])"
}
class HlsMediaSegment internal constructor(
    val index: Int,
    val sequence: Long,
    val durationUs: Long,
    val startUs: Long,
    val uri: HlsUri,
    val identity: String,
) { override fun toString() = "HlsMediaSegment(index=$index, sequence=$sequence, uri=[redacted])" }
class HlsMediaPlaylist internal constructor(
    location: HlsPlaylistLocation,
    contentIdentity: String,
    manifestByteLength: Int,
    version: Int,
    independent: Boolean,
    val targetDurationSeconds: Long,
    val mediaSequence: Long,
    val totalDurationUs: Long,
    segments: List<HlsMediaSegment>,
) : HlsPlaylist(location, contentIdentity, manifestByteLength, version, independent) {
    val segments: List<HlsMediaSegment> = immutable(segments)
    override fun toString() = "HlsMediaPlaylist(segments=${segments.size}, uri=[redacted])"
}

/** An immutable selection survives planning/retries without switching variants or adding audio. */
class HlsSelection internal constructor(
    val identity: String,
    val playlistUri: HlsUri,
    val masterContentIdentity: String?,
    val variantMetadata: HlsVariantMetadata?,
) {
    override fun toString() = "HlsSelection([redacted])"
    companion object {
        /** For a media-playlist resource directly and explicitly selected by the user. */
        fun directMedia(playlistUri: HlsUri): HlsSelection =
            HlsSelection(digest("hls-direct-selection-v1", playlistUri.identity), playlistUri, null, null)
    }
}

internal fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun digest(vararg parts: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        md.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        md.update(bytes)
    }
    return hex(md.digest())
}
internal fun digestBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
