package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.recording.RecordingArtifactExpectation
import com.easysavevideo.recording.RecordingTrackEvidence
import com.easysavevideo.recording.RecordingResumeBoundary
import com.easysavevideo.recording.snapshotRecordingResumeBoundaries
import kotlin.math.abs

/** Independent closed-file scan and actual terminal decoder output, never recorder completion flags. */
class RecordingVerificationProof(
    val video: RecordingTrackEvidence,
    val audio: RecordingTrackEvidence?,
    val videoMimeType: String,
    val audioMimeType: String?,
    val audioSampleRate: Int?,
    val audioChannelCount: Int?,
    val terminalVideoOutputTimeUs: Long,
    val videoOutputEos: Boolean,
    decodedResumeBoundaries: List<RecordingResumeBoundary>? = null,
) {
    val decodedResumeBoundaries: List<RecordingResumeBoundary>? = snapshotRecordingResumeBoundaries(decodedResumeBoundaries, video)
    init {
        require(videoMimeType.length in 1..128 && (audioMimeType == null || audioMimeType.length in 1..128))
        require((audio == null) == (audioMimeType == null))
        require((audio == null) == (audioSampleRate == null) && (audio == null) == (audioChannelCount == null))
        require(audioSampleRate == null || audioSampleRate in 8_000..96_000)
        require(audioChannelCount == null || audioChannelCount in 1..2)
        require(terminalVideoOutputTimeUs >= 0)
    }
    fun copy(
        video: RecordingTrackEvidence = this.video,
        audio: RecordingTrackEvidence? = this.audio,
        videoMimeType: String = this.videoMimeType,
        audioMimeType: String? = this.audioMimeType,
        audioSampleRate: Int? = this.audioSampleRate,
        audioChannelCount: Int? = this.audioChannelCount,
        terminalVideoOutputTimeUs: Long = this.terminalVideoOutputTimeUs,
        videoOutputEos: Boolean = this.videoOutputEos,
        decodedResumeBoundaries: List<RecordingResumeBoundary>? = this.decodedResumeBoundaries,
    ) = RecordingVerificationProof(video, audio, videoMimeType, audioMimeType, audioSampleRate,
        audioChannelCount, terminalVideoOutputTimeUs, videoOutputEos, decodedResumeBoundaries)

    private fun values(): List<Any?> = listOf(video, audio, videoMimeType, audioMimeType, audioSampleRate,
        audioChannelCount, terminalVideoOutputTimeUs, videoOutputEos, decodedResumeBoundaries)
    override fun equals(other: Any?): Boolean = other is RecordingVerificationProof && values() == other.values()
    override fun hashCode(): Int = values().hashCode()
}

/** Recording uses its own bounded output clock tolerance; it never borrows HLS conservation flags. */
const val RECORDING_TIMESTAMP_TOLERANCE_US = 100L
private fun RecordingTrackEvidence.matches(actual: RecordingTrackEvidence): Boolean =
    sampleCount == actual.sampleCount && syncSampleCount == actual.syncSampleCount &&
        firstSampleIsKeyFrame == actual.firstSampleIsKeyFrame &&
        abs(firstPtsUs - actual.firstPtsUs) <= RECORDING_TIMESTAMP_TOLERANCE_US &&
        abs(lastPtsUs - actual.lastPtsUs) <= RECORDING_TIMESTAMP_TOLERANCE_US &&
        abs(lastDurationUs - actual.lastDurationUs) <= 1000

fun RecordingArtifactExpectation.matches(evidence: VerificationEvidence): Boolean {
    val proof = evidence.recordingProof ?: return false
    val expectedBoundaries = resumeBoundaries ?: return false
    val actualBoundaries = proof.decodedResumeBoundaries ?: return false
    if (expectedBoundaries.size != actualBoundaries.size || expectedBoundaries.indices.any { index ->
            expectedBoundaries[index].sampleIndex != actualBoundaries[index].sampleIndex ||
                abs(expectedBoundaries[index].ptsUs - actualBoundaries[index].ptsUs) > RECORDING_TIMESTAMP_TOLERANCE_US
        }) return false
    return matchesLegacyEvidence(evidence)
}

/** Only permits old journals to remain readable; it is never a publication gate. */
internal fun RecordingArtifactExpectation.matchesLegacyEvidence(evidence: VerificationEvidence): Boolean {
    val proof = evidence.recordingProof ?: return false
    val expectedAudio = audio
    return finalized && resourcesReleased && evidence.sizeBytes == bytesWritten &&
        evidence.streamConservation == null && evidence.streamTailProof == null &&
        evidence.videoTrackCount == videoTrackCount && evidence.audioTrackCount == audioTrackCount &&
        evidence.width == actualProfile.width && evidence.height == actualProfile.height &&
        evidence.durationMillis in 1..24L * 60 * 60 * 1000 &&
        abs(evidence.durationMillis * 1000 - effectiveDurationUs) <= 1000 &&
        proof.videoMimeType == "video/avc" && video.matches(proof.video) &&
        (if (expectedAudio == null) proof.audio == null else proof.audio?.let(expectedAudio::matches) == true &&
            proof.audioMimeType == "audio/mp4a-latm" && proof.audioSampleRate == actualProfile.audioSampleRate &&
            proof.audioChannelCount == actualProfile.audioChannels) &&
        proof.videoOutputEos && abs(proof.terminalVideoOutputTimeUs - video.lastPtsUs) <= RECORDING_TIMESTAMP_TOLERANCE_US
}

internal fun TransferResult.matchesMethod(request: SaveRequest): Boolean = when (request.method) {
    TaskMethod.DIRECT -> streamExpectation == null && recordingExpectation == null
    TaskMethod.STREAM -> streamExpectation != null && recordingExpectation == null
    TaskMethod.RECORD -> streamExpectation == null && recordingExpectation?.let {
        it.descriptor == request.captureDescriptor && it.bytesWritten == bytesOnDisk
    } == true
    else -> false
}
