package com.easysavevideo.save

/** Contract-only bounds. No source parser, muxer or codec capability is implied by these values. */
object DirectNormalizationLimits {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_DURATION_US = 30L * 60 * 1_000_000
    const val MAX_VIDEO_SAMPLES = 108_000L
    const val MAX_AUDIO_SAMPLES = 90_000L
    const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
    const val MAX_CONFIGURATION_BYTES = 64 * 1024
    // Initial representation bound only, NOT a measured Media3/pinned-mux guarantee. The production
    // adapter must establish compatible clocks/profile or use a separately reviewed policy revision.
    const val MAX_ROUNDING_US = 2L
}

enum class DirectSampleKind { AVC_BASELINE, AAC_LC }
/** Shared representation only. A future adapter must prove its source/output clock conversion. */
enum class DirectTimingRepresentation { NORMALIZED_MICROSECONDS_V1 }

/** Immutable compact identity; payload bytes and untrusted source metadata never enter a journal. */
data class DirectSampleIdentity(
    val index: Long,
    val presentationTimeUs: Long,
    val canonicalBytes: Int,
    val sha256: String,
    val flags: Int,
) {
    init {
        require(index in 0 until DirectNormalizationLimits.MAX_VIDEO_SAMPLES)
        require(presentationTimeUs in 0 until DirectNormalizationLimits.MAX_DURATION_US)
        require(canonicalBytes in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        requireDirectHash(sha256); require(flags in 0..1)
    }
}

/** An explicitly proved final sample endpoint, including the bounded shared rounding interval. */
data class DirectTrackEndpoint(val minimumUs: Long, val maximumUs: Long) {
    init {
        require(minimumUs in 1..DirectNormalizationLimits.MAX_DURATION_US)
        require(maximumUs in minimumUs..DirectNormalizationLimits.MAX_DURATION_US)
        require(maximumUs - minimumUs <= DirectNormalizationLimits.MAX_ROUNDING_US)
    }
    fun contains(valueUs: Long, toleranceUs: Long = 0): Boolean =
        toleranceUs in 0..1_000 && valueUs in 0..DirectNormalizationLimits.MAX_DURATION_US &&
            valueUs >= minimumUs - toleranceUs && valueUs - toleranceUs <= maximumUs
}

/**
 * Versioned digests bind ordered codec configuration, every canonical compressed sample, sample
 * flags and normalized presentation timing independently. AVC canonical payload is an ordered NAL
 * sequence with four-byte lengths. Configuration is a versioned length-delimited SPS/PPS or ASC
 * tuple. No algorithm is provided here: production adapters remain unavailable until the shared
 * bounded scanners and pinned clock/framing tests implement this representation.
 */
data class DirectTrackConservation(
    val kind: DirectSampleKind,
    val codecConfigurationSha256: String,
    val sampleCount: Long,
    val canonicalBytes: Long,
    val payloadSha256: String,
    val sampleFlagsSha256: String,
    val timingSha256: String,
    val firstPresentationTimeUs: Long,
    val lastSample: DirectSampleIdentity,
    val endpoint: DirectTrackEndpoint,
    val representationVersion: Int = 1,
    val timingRepresentation: DirectTimingRepresentation = DirectTimingRepresentation.NORMALIZED_MICROSECONDS_V1,
) {
    init {
        require(representationVersion == 1)
        requireDirectHash(codecConfigurationSha256); requireDirectHash(payloadSha256)
        requireDirectHash(sampleFlagsSha256); requireDirectHash(timingSha256)
        val cap = if (kind == DirectSampleKind.AVC_BASELINE) DirectNormalizationLimits.MAX_VIDEO_SAMPLES
            else DirectNormalizationLimits.MAX_AUDIO_SAMPLES
        require(sampleCount in 2..cap && canonicalBytes in sampleCount..DirectNormalizationLimits.MAX_BYTES)
        require(firstPresentationTimeUs == 0L)
        require(lastSample.index == sampleCount - 1 && lastSample.presentationTimeUs > firstPresentationTimeUs)
        require(canonicalBytes >= lastSample.canonicalBytes.toLong() + sampleCount - 1)
        require(endpoint.minimumUs > lastSample.presentationTimeUs)
        require(endpoint.maximumUs - lastSample.presentationTimeUs <= 1_000_002)
        require(kind != DirectSampleKind.AAC_LC || lastSample.flags == 1)
    }
}

/** V1 deliberately admits zero origin offset only, identity geometry and a fixed codec family. */
data class DirectRemuxConservation(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val video: DirectTrackConservation,
    val audio: DirectTrackConservation?,
    val audioSampleRate: Int?,
    val audioChannelCount: Int?,
    val precedingVideoSync: DirectSampleIdentity,
    val sourceOriginOffsetUs: Long = 0,
    val representationVersion: Int = 1,
) {
    init {
        require(representationVersion == 1 && sourceOriginOffsetUs == 0L)
        require(width in 2..1920 && height in 2..1920 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 1920L * 1080 && frameRate in 1..60)
        require(video.kind == DirectSampleKind.AVC_BASELINE)
        require(audio == null || audio.kind == DirectSampleKind.AAC_LC)
        require(if (audio == null) audioSampleRate == null && audioChannelCount == null
            else audioSampleRate in setOf(32_000, 44_100, 48_000) && audioChannelCount in 1..2)
        require(precedingVideoSync.flags == 1 && precedingVideoSync.index <= video.lastSample.index)
        require(precedingVideoSync.presentationTimeUs <= video.lastSample.presentationTimeUs)
        require((precedingVideoSync.index == video.lastSample.index) == (precedingVideoSync == video.lastSample))
        require((precedingVideoSync.index == 0L) == (precedingVideoSync.presentationTimeUs == 0L))
    }
    val endpoint: DirectTrackEndpoint get() = DirectTrackEndpoint(
        maxOf(video.endpoint.minimumUs, audio?.endpoint?.minimumUs ?: 0),
        maxOf(video.endpoint.maximumUs, audio?.endpoint?.maximumUs ?: 0),
    )
}

/** Actual decoder tail observation, separate from successful mux finalization or scanner metadata. */
data class DirectVideoTailProof(
    val precedingSync: DirectSampleIdentity,
    val lastSample: DirectSampleIdentity,
    val terminalOutputTimeUs: Long,
    val outputEos: Boolean,
) {
    init { require(terminalOutputTimeUs in 0..DirectNormalizationLimits.MAX_DURATION_US) }
    fun matches(expected: DirectRemuxConservation): Boolean = outputEos &&
        precedingSync == expected.precedingVideoSync && lastSample == expected.video.lastSample &&
        kotlin.math.abs(terminalOutputTimeUs - expected.video.lastSample.presentationTimeUs) <= DirectNormalizationLimits.MAX_ROUNDING_US
}

internal fun requireDirectHash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
