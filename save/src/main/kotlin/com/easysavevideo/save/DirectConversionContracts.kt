package com.easysavevideo.save

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable shape only. None of these values issues storage, decoder or publication authority. */
object DirectConversionLimits {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_DURATION_US = 30L * 60 * 1_000_000
    const val MAX_DISPLAYED_FRAMES = 108_000L
    const val SOURCE_ROUNDING_US = 1_000L
    const val MAX_OPUS_BOUNDARY_US = 120_000L
    const val AAC_BOUNDARY_US = 42_667L
    /** Integer-microsecond endpoint rounding, never AAC boundary slack or an interior gap budget. */
    const val AUDIO_PCM_ROUNDING_US = 2L
    const val AUDIO_SAMPLE_RATE = 48_000
    const val MAX_PCM_FRAMES = MAX_DURATION_US * AUDIO_SAMPLE_RATE / 1_000_000
}
enum class DirectConversionProfile { WEBM_VP8_OPUS_TO_AVC_AAC_V1 }

/** Observed successfully rendered VP8 output lifetimes, never compressed VP8 sample counts. */
data class DirectConversionVideoExpectation(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val sourceDurationUs: Long,
    val displayedFrameCount: Long,
    val firstDisplayedTimeUs: Long,
    val lastDisplayedTimeUs: Long,
    /** Maximum absolute deviation observed across EVERY displayed frame from floor(index * 1000000 / frameRate), in the fixed source origin. */
    val maximumFrameDeviationUs: Long,
) {
    init {
        require(width in 2..1920 && height in 2..1920 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 1920L * 1080 && frameRate in 1..60)
        require(sourceDurationUs in 1..DirectConversionLimits.MAX_DURATION_US)
        require(displayedFrameCount in 1..DirectConversionLimits.MAX_DISPLAYED_FRAMES)
        require(firstDisplayedTimeUs in 0..DirectConversionLimits.SOURCE_ROUNDING_US)
        require(lastDisplayedTimeUs in firstDisplayedTimeUs..DirectConversionLimits.MAX_DURATION_US)
        require(maximumFrameDeviationUs in 0..DirectConversionLimits.SOURCE_ROUNDING_US)
        require(firstDisplayedTimeUs <= maximumFrameDeviationUs)
        require(conversionNear(lastDisplayedTimeUs, frameTimeUs(displayedFrameCount - 1), maximumFrameDeviationUs))
        require(conversionNear(sourceDurationUs, frameTimeUs(displayedFrameCount), durationToleranceUs))
    }
    val durationToleranceUs: Long get() = (1_000_000L + frameRate - 1) / frameRate + DirectConversionLimits.SOURCE_ROUNDING_US
    fun frameTimeUs(index: Long): Long {
        require(index in 0..DirectConversionLimits.MAX_DISPLAYED_FRAMES)
        return index * 1_000_000L / frameRate
    }
}

/** Bounded durable values. Source S keeps its origin; the enclosing policy supplies S/E semantics. */
data class DirectConversionAudioExpectation(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val sourcePcmFrameCount: Long,
    val encoderPcmFrameCount: Long,
    val channelCount: Int,
    val codecDelayUs: Long,
    val seekPrerollUs: Long,
    val discardPaddingUs: Long,
    val sampleRate: Int = DirectConversionLimits.AUDIO_SAMPLE_RATE,
) {
    init {
        require(sampleRate == DirectConversionLimits.AUDIO_SAMPLE_RATE && channelCount in 1..2)
        require(sourceStartUs in -DirectConversionLimits.SOURCE_ROUNDING_US..DirectConversionLimits.SOURCE_ROUNDING_US)
        require(sourceEndUs in 1..DirectConversionLimits.MAX_DURATION_US + DirectConversionLimits.SOURCE_ROUNDING_US)
        require(sourcePcmFrameCount in 1..DirectConversionLimits.MAX_PCM_FRAMES)
        require(encoderPcmFrameCount in 1..DirectConversionLimits.MAX_PCM_FRAMES)
        require(listOf(codecDelayUs, seekPrerollUs, discardPaddingUs).all { it in 0..DirectConversionLimits.MAX_OPUS_BOUNDARY_US })
        require(conversionNear(sourceEndUs - sourceStartUs, effectiveDurationUs, DirectConversionLimits.SOURCE_ROUNDING_US))
    }
    val effectiveDurationUs: Long get() = conversionPcmDurationUs(sourcePcmFrameCount)
    /** The actual encoder-accepted E interval starts at zero; it is independent of the source end. */
    val encoderDurationUs: Long get() = conversionPcmDurationUs(encoderPcmFrameCount)
}
data class DirectConversionExpectation(
    val video: DirectConversionVideoExpectation,
    val audio: DirectConversionAudioExpectation?,
)

/** Fixed compatibility policy, independent of paid Editor preferences. */
data class DirectConversionEncodingSettings(val videoBitrate: Int, val audioBitrate: Int?) {
    init {
        require(videoBitrate in 256_000..20_000_000)
        require(audioBitrate == null || audioBitrate in setOf(96_000, 192_000))
    }
    fun matches(expectation: DirectConversionExpectation): Boolean = this == forExpectation(expectation)
    companion object {
        fun forExpectation(expectation: DirectConversionExpectation): DirectConversionEncodingSettings {
            val video = expectation.video
            val rate = (video.width.toLong() * video.height * video.frameRate * 16 / 100).coerceIn(256_000, 20_000_000).toInt()
            return DirectConversionEncodingSettings(rate, expectation.audio?.let { if (it.channelCount == 1) 96_000 else 192_000 })
        }
    }
}

/** Separate terminal observations. A callback, cancellation return or Future is not native release. */
data class DirectConversionCompletionFacts(
    val rawExtentEof: Boolean,
    val videoDecoderInputEos: Boolean,
    val videoDecoderOutputEnded: Boolean,
    val videoPendingBufferAbsent: Boolean,
    val muxerFinalized: Boolean,
    val nativeResourcesReleased: Boolean,
    val audioDecoderInputEos: Boolean?,
    val audioDecoderOutputEnded: Boolean?,
    val sourceConsumerAcceptedEos: Boolean?,
    val sourceAudioContinuous: Boolean?,
    val audioEncoderInputEos: Boolean?,
    val audioEncoderOutputEnded: Boolean?,
) {
    init {
        require(rawExtentEof && videoDecoderInputEos && videoDecoderOutputEnded && videoPendingBufferAbsent && muxerFinalized && nativeResourcesReleased)
        val facts = listOf(audioDecoderInputEos, audioDecoderOutputEnded, sourceConsumerAcceptedEos,
            sourceAudioContinuous, audioEncoderInputEos, audioEncoderOutputEnded)
        require(facts.all { it == null } || facts.all { it == true })
    }
    val hasAudio: Boolean get() = sourceConsumerAcceptedEos != null
}

/** A distinct transcode meaning. No remux conservation, noEncoding or compressed tail identity. */
data class DirectConversionProof(
    val normalizationId: String,
    val taskId: String,
    val sourceIdentity: String,
    val artifactId: String,
    val commitId: String,
    val rawSizeBytes: Long,
    val rawSha256: String,
    val sourceTransferSha256: String,
    val outputSizeBytes: Long,
    val outputSha256: String,
    val expectation: DirectConversionExpectation,
    val settings: DirectConversionEncodingSettings,
    val completion: DirectConversionCompletionFacts,
    val schemaVersion: Int = 1,
    val profile: DirectConversionProfile = DirectConversionProfile.WEBM_VP8_OPUS_TO_AVC_AAC_V1,
    val engineRevision: Int = 1,
    val policyRevision: Int = 1,
) {
    init {
        requireConversionIdentity(normalizationId, taskId, sourceIdentity, artifactId, commitId)
        requireDirectHash(rawSha256); requireDirectHash(sourceTransferSha256); requireDirectHash(outputSha256)
        require(rawSizeBytes in 1..DirectConversionLimits.MAX_BYTES && outputSizeBytes in 1..DirectConversionLimits.MAX_BYTES)
        require(schemaVersion == 1 && engineRevision == 1)
        // Recognize historical audio1 for durable reads; only audio2 is eligible for new owned work.
        require(policyRevision == 1 || (policyRevision == 2 && expectation.audio != null))
        expectation.audio?.let { audio ->
            if (policyRevision == 1) {
                // These are the original nested constraints, unchanged for historical bytes/digests.
                require(audio.encoderPcmFrameCount == audio.sourcePcmFrameCount)
                require(conversionNear(audio.sourceEndUs, expectation.video.sourceDurationUs, DirectConversionLimits.SOURCE_ROUNDING_US))
                require(conversionNear(audio.effectiveDurationUs, expectation.video.sourceDurationUs, DirectConversionLimits.SOURCE_ROUNDING_US))
            } else {
                require(audio.sourcePcmFrameCount <= audio.encoderPcmFrameCount)
                require(conversionNear(audio.sourceEndUs - audio.sourceStartUs, audio.effectiveDurationUs,
                    DirectConversionLimits.AUDIO_PCM_ROUNDING_US))
            }
        }
        require(settings.matches(expectation) && completion.hasAudio == (expectation.audio != null))
    }
    /** Active eligibility only; these values never establish native mapping or issue owned completion. */
    fun isCurrentPolicy(): Boolean = if (expectation.audio == null) policyRevision == 1 else policyRevision == 2
    fun matchesSource(taskId: String, sourceIdentity: String, artifact: StagingArtifact, transfer: TransferResult): Boolean =
        this.taskId == taskId && this.sourceIdentity == sourceIdentity && artifactId == artifact.artifactId &&
            commitId == artifact.commitId && matchesTransfer(transfer)
    fun matchesTransfer(transfer: TransferResult): Boolean = transfer.artifactId == artifactId &&
        transfer.streamExpectation == null && transfer.recordingExpectation == null && transfer.bytesOnDisk == rawSizeBytes &&
        (transfer.expectedTotalBytes == null || transfer.expectedTotalBytes == rawSizeBytes) &&
        sourceTransferFingerprint(transfer) == sourceTransferSha256
    fun digest(): String = conversionDigest { o ->
        o.writeUTF("ESV-DIRECT-CONVERSION-PROOF-1"); o.writeInt(schemaVersion); o.writeUTF(profile.name)
        o.writeUTF(normalizationId); o.writeUTF(taskId); o.writeUTF(sourceIdentity); o.writeUTF(artifactId); o.writeUTF(commitId)
        o.writeLong(rawSizeBytes); o.writeUTF(rawSha256); o.writeUTF(sourceTransferSha256)
        o.writeLong(outputSizeBytes); o.writeUTF(outputSha256); o.writeInt(engineRevision); o.writeInt(policyRevision)
        with(expectation.video) {
            o.writeInt(width); o.writeInt(height); o.writeInt(frameRate); o.writeLong(sourceDurationUs)
            o.writeLong(displayedFrameCount); o.writeLong(firstDisplayedTimeUs); o.writeLong(lastDisplayedTimeUs); o.writeLong(maximumFrameDeviationUs)
        }
        o.writeBoolean(expectation.audio != null)
        expectation.audio?.let { a ->
            o.writeLong(a.sourceStartUs); o.writeLong(a.sourceEndUs); o.writeLong(a.sourcePcmFrameCount); o.writeLong(a.encoderPcmFrameCount)
            o.writeInt(a.channelCount); o.writeLong(a.codecDelayUs); o.writeLong(a.seekPrerollUs); o.writeLong(a.discardPaddingUs); o.writeInt(a.sampleRate)
        }
        o.writeInt(settings.videoBitrate); o.writeBoolean(settings.audioBitrate != null); settings.audioBitrate?.let(o::writeInt)
        with(completion) {
            o.writeBoolean(rawExtentEof); o.writeBoolean(videoDecoderInputEos); o.writeBoolean(videoDecoderOutputEnded)
            o.writeBoolean(videoPendingBufferAbsent); o.writeBoolean(muxerFinalized); o.writeBoolean(nativeResourcesReleased)
            o.writeBoolean(hasAudio)
            if (hasAudio) {
                o.writeBoolean(audioDecoderInputEos!!); o.writeBoolean(audioDecoderOutputEnded!!); o.writeBoolean(sourceConsumerAcceptedEos!!)
                o.writeBoolean(sourceAudioContinuous!!); o.writeBoolean(audioEncoderInputEos!!); o.writeBoolean(audioEncoderOutputEnded!!)
            }
        }
    }
}

/**
 * Independently decoded AAC presentation after the actual priming/padding/edit-list mapping.
 * effectiveDecodedPcmFrameCount excludes only samples that this verified mapping excludes;
 * it is not the raw decoder byte count or an arbitrary subtraction. Interior continuity uses
 * sample-clock rounding only, never the AAC head/tail boundary allowance.
 */
data class DirectConversionAudioVerification(
    val outputStartUs: Long,
    val outputEndUs: Long,
    val effectiveDecodedPcmFrameCount: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val outputEos: Boolean,
    val continuous: Boolean,
) {
    init {
        require(sampleRate == DirectConversionLimits.AUDIO_SAMPLE_RATE && channelCount in 1..2)
        require(outputStartUs in -(DirectConversionLimits.AAC_BOUNDARY_US + DirectConversionLimits.SOURCE_ROUNDING_US)..DirectConversionLimits.MAX_DURATION_US)
        require(outputEndUs in 1..DirectConversionLimits.MAX_DURATION_US + DirectConversionLimits.AAC_BOUNDARY_US + DirectConversionLimits.SOURCE_ROUNDING_US)
        require(outputEndUs > outputStartUs && effectiveDecodedPcmFrameCount in 1..DirectConversionLimits.MAX_PCM_FRAMES + 2048)
    }
    /** Policy 1 retains its historical source target; policy 2 checks actual encoder E. */
    fun matches(source: DirectConversionAudioExpectation, policyRevision: Int = 1): Boolean {
        val startUs: Long
        val endUs: Long
        val durationUs: Long
        when (policyRevision) {
            1 -> {
                if (source.encoderPcmFrameCount != source.sourcePcmFrameCount) return false
                startUs = source.sourceStartUs
                endUs = source.sourceEndUs
                durationUs = source.effectiveDurationUs
            }
            2 -> {
                if (source.sourcePcmFrameCount > source.encoderPcmFrameCount ||
                    !conversionNear(source.sourceEndUs - source.sourceStartUs, source.effectiveDurationUs,
                        DirectConversionLimits.AUDIO_PCM_ROUNDING_US)) return false
                startUs = 0
                endUs = source.encoderDurationUs
                durationUs = source.encoderDurationUs
            }
            else -> return false
        }
        return outputEos && continuous && sampleRate == source.sampleRate && channelCount == source.channelCount &&
            conversionNear(outputEndUs - outputStartUs, conversionPcmDurationUs(effectiveDecodedPcmFrameCount),
                DirectConversionLimits.AUDIO_PCM_ROUNDING_US) &&
            conversionNear(outputStartUs, startUs, DirectConversionLimits.AAC_BOUNDARY_US) &&
            conversionNear(outputEndUs, endUs, DirectConversionLimits.AAC_BOUNDARY_US) &&
            conversionNear(conversionPcmDurationUs(effectiveDecodedPcmFrameCount), durationUs, DirectConversionLimits.AAC_BOUNDARY_US)
    }
}

/** Full independent output decode observations, only usable through an owned completion registry. */
data class DirectConversionVerificationProof(
    val normalizationId: String,
    val taskId: String,
    val sourceIdentity: String,
    val artifactId: String,
    val commitId: String,
    val conversionProofSha256: String,
    val outputSizeBytes: Long,
    val outputSha256: String,
    val decodedVideoFrameCount: Long,
    val firstVideoTimeUs: Long,
    val lastVideoTimeUs: Long,
    val videoDurationUs: Long,
    val maximumFrameDeviationUs: Long,
    val audio: DirectConversionAudioVerification?,
    val containerComplete: Boolean,
    val videoOutputEos: Boolean,
    val nativeResourcesReleased: Boolean,
    val schemaVersion: Int = 1,
    val policyRevision: Int = 1,
) {
    init {
        requireConversionIdentity(normalizationId, taskId, sourceIdentity, artifactId, commitId)
        requireDirectHash(conversionProofSha256); requireDirectHash(outputSha256)
        require(schemaVersion == 1 && outputSizeBytes in 1..DirectConversionLimits.MAX_BYTES)
        require(policyRevision == 1 || (policyRevision == 2 && audio != null))
        require(decodedVideoFrameCount in 1..DirectConversionLimits.MAX_DISPLAYED_FRAMES)
        require(maximumFrameDeviationUs in 0..DirectConversionLimits.SOURCE_ROUNDING_US)
        require(firstVideoTimeUs in 0..DirectConversionLimits.SOURCE_ROUNDING_US)
        require(lastVideoTimeUs in firstVideoTimeUs..DirectConversionLimits.MAX_DURATION_US)
        require(videoDurationUs in 1..DirectConversionLimits.MAX_DURATION_US)
    }
    fun matches(proof: DirectConversionProof): Boolean {
        val video = proof.expectation.video
        return policyRevision == proof.policyRevision &&
            normalizationId == proof.normalizationId && taskId == proof.taskId && sourceIdentity == proof.sourceIdentity &&
            artifactId == proof.artifactId && commitId == proof.commitId && conversionProofSha256 == proof.digest() &&
            outputSizeBytes == proof.outputSizeBytes && outputSha256 == proof.outputSha256 &&
            containerComplete && videoOutputEos && nativeResourcesReleased && decodedVideoFrameCount == video.displayedFrameCount &&
            firstVideoTimeUs <= maximumFrameDeviationUs &&
            conversionNear(lastVideoTimeUs, video.frameTimeUs(decodedVideoFrameCount - 1), maximumFrameDeviationUs) &&
            conversionNear(firstVideoTimeUs, video.firstDisplayedTimeUs, DirectConversionLimits.SOURCE_ROUNDING_US) &&
            conversionNear(lastVideoTimeUs, video.lastDisplayedTimeUs, DirectConversionLimits.SOURCE_ROUNDING_US) &&
            conversionNear(videoDurationUs, video.sourceDurationUs, video.durationToleranceUs) &&
            ((proof.expectation.audio == null && audio == null) ||
                (proof.expectation.audio != null && audio?.matches(proof.expectation.audio, policyRevision) == true))
    }
}

fun SaveRecord.hasDirectOutputProof(): Boolean = directNormalization != null || directConversion != null
/** Also protects orphan technical verification from entering generic storage paths. */
fun SaveRecord.hasOwnedDirectOutputEvidence(): Boolean = hasDirectOutputProof() ||
    verification?.directNormalizationProof != null || verification?.directConversionProof != null

private fun requireConversionIdentity(id: String, task: String, source: String, artifact: String, commit: String) {
    require(id.length == 36 && UUID.fromString(id).toString() == id)
    requireSaveId(task); requireSaveId(artifact); requireSaveId(commit); requireDirectHash(source)
}
private fun conversionPcmDurationUs(frames: Long): Long = (frames * 1_000_000 + DirectConversionLimits.AUDIO_SAMPLE_RATE / 2) / DirectConversionLimits.AUDIO_SAMPLE_RATE
private fun conversionNear(a: Long, b: Long, tolerance: Long): Boolean = a - b in -tolerance..tolerance
private fun conversionDigest(write: (DataOutputStream) -> Unit): String {
    val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use(write) }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
