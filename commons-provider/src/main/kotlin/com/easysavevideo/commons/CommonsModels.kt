package com.easysavevideo.commons

/** Metadata discovery only. These types never grant download, playback, execution, or save authority. */
object CommonsIdentity {
    const val SOURCE = "Wikimedia Commons"
    const val PROVIDER_ID = "wikimedia-commons"
    const val VERSION = "0.1.0"
    const val API = "https://commons.wikimedia.org/w/api.php"
    const val METADATA_TERMS = "https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia"
}

enum class CommonsError {
    DISABLED, CONTACT_REQUIRED, VERSION_REQUIRED, TRANSPORT_UNAVAILABLE, UNSUPPORTED_FILTER, INVALID_REQUEST, INVALID_CURSOR,
    INVALID_RESPONSE, UNSAFE_URL, REDIRECT_REJECTED, SERVICE_DENIED, SERVICE_ERROR,
    RATE_LIMITED, RESPONSE_TOO_LARGE, DEADLINE_EXCEEDED, CANCELLED, BUSY,
}
class CommonsFailure(val code: CommonsError, val retryAtEpochMillis: Long? = null,
    /** Only the compiled semantic parser sets this after a recognizable, valid JSON envelope. */
    internal val structureFailure: Boolean = false,
) : RuntimeException(code.name)

/** No default contact: supply an operator-owned, explicitly approved public contact for this service. */
class CommonsClientConfig(
    val enabled: Boolean = false,
    val applicationVersion: String? = null,
    val approvedPublicContact: String? = null,
) {
    init {
        require(applicationVersion == null || applicationVersion.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(approvedPublicContact == null || (approvedPublicContact.length in 1..256 &&
            approvedPublicContact.all { it.code in 33..126 && it !in "()\\" }))
    }
    internal fun userAgent(): String {
        if (!enabled) throw CommonsFailure(CommonsError.DISABLED)
        val contact = approvedPublicContact ?: throw CommonsFailure(CommonsError.CONTACT_REQUIRED)
        val version = applicationVersion ?: throw CommonsFailure(CommonsError.VERSION_REQUIRED)
        return "EasySaveVideo/$version ($contact)"
    }
    override fun toString(): String = "CommonsClientConfig(enabled=$enabled, contact=[redacted])"
}

data class CommonsLimits(
    val pageSize: Int = 5,
    val maxResponseBytes: Long = 2L * 1024 * 1024,
    val deadlineMillis: Long = 15_000,
    val cacheTtlMillis: Long = 5 * 60_000,
    val cacheEntries: Int = 16,
) {
    init {
        require(pageSize in 1..10) // extmetadata is expensive; deliberately small batches.
        require(maxResponseBytes in 1..2L * 1024 * 1024)
        require(deadlineMillis in 1..15_000)
        require(cacheTtlMillis in 0..5 * 60_000)
        require(cacheEntries in 0..16)
    }
}

class CommonsSearchRequest(val query: String, val language: String = "en", val cursor: String? = null,
    val safeSearchRequired: Boolean = true, val queryIdentity: String? = null) {
    init {
        require(query.length in 1..512 && query.isNotBlank())
        require(query.none { it.isISOControl() } && isWellFormedUnicode(query))
        require(language.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) && language.length <= 32)
        require(cursor == null || cursor.length <= 8192)
        require(queryIdentity == null || queryIdentity.matches(Regex("[a-f0-9]{64}")))
    }
    override fun toString(): String = "CommonsSearchRequest([redacted])"
}

/** extmetadata is extracted page/EXIF text, not a Structured Data on Commons / CC0 response. */
enum class CommonsMetadataKind { EXTRACTED_UNSTRUCTURED_TEXT }
enum class CommonsRightsReview { INCOMPLETE, REPORTED_REQUIRES_REVIEW, FLAGGED_REQUIRES_REVIEW }

data class CommonsFieldProvenance(
    val field: String,
    val apiReportedSource: String?,
    val kind: CommonsMetadataKind = CommonsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT,
    val htmlConvertedToText: Boolean,
    val truncated: Boolean,
) { override fun toString(): String = "CommonsFieldProvenance(field=$field, kind=$kind)" }

/**
 * A report of what the source says, not legal clearance. Multi-licensing fields may be incomplete or
 * unreliable. A short license name, or even a complete attribution, never authorizes downloading.
 */
data class CommonsRights(
    val sourcePageUrl: String,
    val sourcePageRevisionId: Long?,
    val retrievedAtEpochMillis: Long,
    val author: String?,
    val credit: String?,
    val customAttribution: String?,
    val licenseName: String?,
    val licenseUrl: String?,
    val usageTerms: String?,
    val copyrighted: Boolean?,
    val attributionRequired: Boolean?,
    val nonFree: Boolean?,
    val restrictions: List<String>,
    val deletionReason: String?,
    val review: CommonsRightsReview,
    val provenance: List<CommonsFieldProvenance>,
    val metadataTermsUrl: String = CommonsIdentity.METADATA_TERMS,
) {
    val hasDisplayAttribution: Boolean get() = author != null && licenseName != null && licenseUrl != null
    val downloadAuthorized: Boolean get() = false
    override fun toString(): String = "CommonsRights(review=$review, provenanceCount=${provenance.size})"
}

data class CommonsVideoMetadata(
    val pageId: Long,
    val fileTitle: String,
    val pageUrl: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val mime: String,
    val rights: CommonsRights,
    val sourceName: String = CommonsIdentity.SOURCE,
) { override fun toString(): String = "CommonsVideoMetadata([redacted])" }

data class CommonsMetadataPage(
    val results: List<CommonsVideoMetadata>,
    val nextCursor: String?,
    val cursorExpiresAtEpochMillis: Long?,
    val exhausted: Boolean,
    val responseBytes: Long,
    val fromCache: Boolean = false,
) { override fun toString(): String = "CommonsMetadataPage(count=${results.size}, fromCache=$fromCache)" }

/** Reject malformed UTF-16 before UTF-8 encoding can replace different inputs with the same '?'. */
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

/** No caller receives a mutable collection owned by the provider's cache. */
internal fun CommonsMetadataPage.detached(): CommonsMetadataPage = copy(results = results.map { video ->
    video.copy(rights = video.rights.copy(restrictions = video.rights.restrictions.toList(),
        provenance = video.rights.provenance.map { it.copy() }))
})
