package com.easysavevideo.library.edit

import kotlin.math.abs

/**
 * Issued only by the independent staging verifier, after full bounded container/sample traversal,
 * decode of every admitted video/AAC sample, output EOS, exact byte-extent/hash checks, and actual
 * release of all native and input handles. User metadata and encoder completion are not evidence.
 * This DTO transports adapter evidence; construction alone cannot authenticate native behavior.
 */
data class EditVerificationEvidence(
    val output: EditOutputRef,
    val expectation: EditOutputExpectation,
    val bytes: Long,
    val sha256: String,
    val actualDurationUs: Long,
    val actualWidth: Int,
    val actualHeight: Int,
    val rotationDegrees: Int,
    val videoMime: String,
    val audioMime: String?,
    val audioSampleRate: Int?,
    val audioChannels: Int?,
    val firstVideoPtsUs: Long,
    val lastVideoPtsUs: Long,
    val videoSamples: Long,
    val audioSamples: Long,
    val decodedVideoFrames: Long,
    val audioDecodedFrames: Long,
    val videoOutputEos: Boolean,
    val audioOutputEos: Boolean,
    val nativeReleaseConfirmed: Boolean,
    val containerExtentVerified: Boolean,
    val verificationVersion: Int = 1,
) {
    /** A second fail-closed publication check; it does not replace the verifier's complete scan. */
    fun validFor(expectedOutput: EditOutputRef, expected: EditOutputExpectation): Boolean {
        if (verificationVersion != 1 || output != expectedOutput || expectation != expected || bytes !in 1..expected.maxBytes ||
            !sha256.matches(Regex("[a-f0-9]{64}")) || actualDurationUs <= 0 ||
            actualDurationUs > EditLimits.MAX_OUTPUT_DURATION_US + 9_000_000 ||
            actualWidth != expected.width || actualHeight != expected.height || rotationDegrees != 0 ||
            videoMime != "video/avc" || !nativeReleaseConfirmed || !containerExtentVerified || !videoOutputEos ||
            videoSamples != expected.videoFrameCount ||
            decodedVideoFrames != videoSamples || firstVideoPtsUs < 0 || lastVideoPtsUs < firstVideoPtsUs ||
            lastVideoPtsUs > EditLimits.MAX_OUTPUT_DURATION_US + 9_000_000) return false
        val frameUs = (1_000_000L + expected.frameRate - 1) / expected.frameRate
        val videoTolerance = expected.durationToleranceUs
        if (abs(actualDurationUs - expected.durationUs) > videoTolerance + expected.audioPrimingToleranceUs ||
            firstVideoPtsUs > videoTolerance ||
            abs(lastVideoPtsUs + frameUs - expected.durationUs) > videoTolerance + 1_000 ||
            abs(videoSamples * 1_000_000L / expected.frameRate - expected.durationUs) > videoTolerance + 1_000) return false
        return when (val audio = expected.audio) {
            EditAudioProfile.Silent -> audioMime == null && audioSampleRate == null && audioChannels == null &&
                audioSamples == 0L && audioDecodedFrames == 0L
            is EditAudioProfile.Aac -> {
                val maxAudioSamples = (EditLimits.MAX_OUTPUT_DURATION_US / 1_000_000 + 9) * 48_000 / 1024 + 1
                val maxDecodedFrames = (EditLimits.MAX_OUTPUT_DURATION_US / 1_000_000 + 9) * 48_000
                audioMime == "audio/mp4a-latm" && audioSampleRate == audio.sampleRate && audioChannels == audio.channels &&
                    audioOutputEos && audioSamples in 1..maxAudioSamples && audioDecodedFrames in 1..maxDecodedFrames &&
                    abs(audioSamples * 1_024_000_000L / audio.sampleRate - expected.durationUs) <=
                    videoTolerance + expected.audioPrimingToleranceUs + 1_000 &&
                    abs(audioDecodedFrames * 1_000_000L / audio.sampleRate - expected.durationUs) <=
                    videoTolerance + expected.audioPrimingToleranceUs + 1_000
            }
        }
    }
    override fun toString() = "EditVerificationEvidence([redacted])"
}
