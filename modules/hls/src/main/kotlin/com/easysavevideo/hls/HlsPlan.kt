package com.easysavevideo.hls

/**
 * Initial media profile, still requiring TS/Android verification before remux/commit:
 * 188-byte MPEG-TS, one program, one stable AVC Baseline/no-B video track, zero or one stable
 * multiplexed AAC-LC audio track, complete segments and one continuous media timeline.
 * No manifest declaration, extension, MIME type or this enum establishes any of those facts.
 */
enum class HlsMediaProfile { TS_STATIC_VOD_AVC_BASELINE_AAC_LC }
enum class HlsMediaValidation { REQUIRED_NOT_PERFORMED }

/** Persistable technical metadata only. No URI, title, cookie, header, lease or manifest body. */
data class HlsSafeSegment(
    val index: Int,
    val sequence: Long,
    val durationUs: Long,
    val startUs: Long,
    val uriIdentity: String,
    val segmentIdentity: String,
)
class HlsDurablePlan internal constructor(
    val identity: String,
    val selectionIdentity: String,
    val masterContentIdentity: String?,
    val mediaContentIdentity: String,
    val requestedUriIdentity: String,
    val responseUriIdentity: String,
    val manifestByteLength: Int,
    val targetDurationSeconds: Long,
    val mediaSequence: Long,
    val totalDurationUs: Long,
    segments: List<HlsSafeSegment>,
) {
    val profile = HlsMediaProfile.TS_STATIC_VOD_AVC_BASELINE_AAC_LC
    val mediaValidation = HlsMediaValidation.REQUIRED_NOT_PERFORMED
    val segments: List<HlsSafeSegment> = immutable(segments)
    /** Re-fetch and re-plan after restart. A hash is identity metadata, not a downloadable source. */
    fun requireSameIdentity(refetched: HlsPlan) {
        ensure(identity == refetched.identity, HlsError.IDENTITY_MISMATCH)
    }
    override fun toString() = "HlsDurablePlan(segments=${segments.size}, profile=$profile)"
}

/** Memory-only exact request plan; never serialize it. [durable] is the sole persistence view. */
class HlsPlan internal constructor(val selection: HlsSelection, val media: HlsMediaPlaylist) {
    val profile = HlsMediaProfile.TS_STATIC_VOD_AVC_BASELINE_AAC_LC
    val mediaValidation = HlsMediaValidation.REQUIRED_NOT_PERFORMED
    val identity: String = digest("hls-plan-v1", profile.name, selection.identity,
        selection.masterContentIdentity.orEmpty(), media.contentIdentity,
        media.location.requestedUri.identity, media.location.responseUri.identity)
    val segments: List<HlsMediaSegment> get() = media.segments
    val durable = HlsDurablePlan(identity, selection.identity, selection.masterContentIdentity,
        media.contentIdentity, media.location.requestedUri.identity, media.location.responseUri.identity,
        media.manifestByteLength, media.targetDurationSeconds, media.mediaSequence, media.totalDurationUs,
        media.segments.map { HlsSafeSegment(it.index, it.sequence, it.durationUs, it.startUs, it.uri.identity, it.identity) })
    override fun toString() = "HlsPlan(segments=${segments.size}, validation=$mediaValidation, uri=[redacted])"
}

object HlsPlanBuilder {
    /** No recursive master fetch and no adaptive fallback, even when another variant could work. */
    fun build(selection: HlsSelection, playlist: HlsPlaylist): HlsPlan {
        ensure(selection.playlistUri == playlist.location.requestedUri, HlsError.SELECTION_MISMATCH)
        val media = playlist as? HlsMediaPlaylist ?: fail(HlsError.NESTED_MASTER)
        return HlsPlan(selection, media)
    }
}
