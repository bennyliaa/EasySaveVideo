package com.easysavevideo.app.media.verification

/** Internal facts supplied by a validated caller. This is not a storage/publication capability. */
internal sealed class AvcAacAudioProfile {
    data object Silent : AvcAacAudioProfile()
    data class Aac(val sampleRate: Int, val channels: Int) : AvcAacAudioProfile()
}

/**
 * Exact inspection inputs, independent of Editor/Save model types. A null independentAudio retains
 * Editor's existing duration/priming policy. Direct uses a separately observed presented interval;
 * its encoder expectation is consulted only after the independent observation is complete.
 */
internal data class AvcAacOutputExpectation(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val audio: AvcAacAudioProfile,
    val durationToleranceUs: Long,
    val audioPrimingToleranceUs: Long,
    val maxBytes: Long,
    val videoFrameCount: Long,
    val independentAudio: AvcAacIndependentAudioExpectation? = null,
)

/** Comparison target only; neither the container window nor the decoded trim uses these frames. */
internal data class AvcAacIndependentAudioExpectation(val encoderFrames: Long) {
    init { require(encoderFrames in 1..86_400_000L) }
    val durationUs: Long get() = encoderFrames * 1_000_000L / 48_000
    companion object { const val BOUNDARY_US = 42_667L }
}
