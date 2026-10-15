package com.easysavevideo.app.save

import com.easysavevideo.app.save.normalization.MovProbeInput
import com.easysavevideo.app.save.normalization.MovProfile
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.save.*
import java.nio.ByteBuffer

/** Native facts, not requested codec settings or source-table substitutes. */
internal data class DirectNativeAudioFormat(
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long,
    val initializationData: List<ByteArray>,
    val hasExtraInitializationData: Boolean,
    val aacProfile: Int? = null,
    val profile: Int? = null,
    val isAdts: Int? = null,
    val encoderDelay: Int? = null,
    val encoderPadding: Int? = null,
    val hasProtection: Boolean = false,
)

/** Optional audio remains finite even before the independently owned raw scan. */
internal fun requireDirectFiniteAudioContract(conservation: DirectRemuxConservation) {
    val audio = conservation.audio
    if (audio == null) {
        if (conservation.audioSampleRate != null || conservation.audioChannelCount != null)
            throw SaveFailure(SaveError.INVALID_MEDIA)
        return
    }
    val rate = conservation.audioSampleRate ?: throw SaveFailure(SaveError.INVALID_MEDIA)
    val channels = conservation.audioChannelCount ?: throw SaveFailure(SaveError.INVALID_MEDIA)
    if (channels !in 1..2 || audio.kind != DirectSampleKind.AAC_LC)
        throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
    val clock = directRawScan { DirectMovClock.audio(rate, audio.sampleCount) }
    if (audio.firstPresentationTimeUs != 0L || audio.endpoint != clock.endpoint ||
        audio.lastSample.index != clock.sampleCount - 1 || audio.lastSample.flags != 1 ||
        audio.lastSample.presentationTimeUs != clock.presentationTimeUs(clock.sampleCount - 1))
        throw SaveFailure(SaveError.INVALID_MEDIA)
}

internal fun directAudioInitializationData(buffers: List<ByteBuffer>): List<ByteArray> {
    if (buffers.size != 1 || buffers.single().remaining() != 2)
        throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    return listOf(ByteArray(2).also { buffers.single().duplicate().get(it) })
}

internal fun inspectDirectAudioFormat(actual: DirectNativeAudioFormat, profile: MovProfile,
    checkpoint: () -> Unit): DirectMovAacConfiguration {
    checkpoint()
    if (actual.hasProtection) throw SaveFailure(SaveError.PROTECTED_MEDIA)
    val raw = profile.audio ?: throw SaveFailure(SaveError.INVALID_MEDIA)
    if (actual.mime != "audio/mp4a-latm" || actual.sampleRate != raw.configuration.sampleRate ||
        actual.channelCount != raw.configuration.channelCount ||
        actual.aacProfile?.let { it != 2 } == true || actual.profile?.let { it != 2 } == true ||
        actual.isAdts?.let { it != 0 } == true || actual.encoderDelay?.let { it != 0 } == true ||
        actual.encoderPadding?.let { it != 0 } == true || actual.durationUs <= 0 ||
        !raw.clock.endpoint.contains(actual.durationUs, 1_000)) throw SaveFailure(SaveError.INVALID_MEDIA)
    if (actual.hasExtraInitializationData) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    val observed = directNativeRepresentation {
        DirectMovCanonical.aacConfigurationFromInitializationData(actual.initializationData, checkpoint)
    }
    if (observed.sampleRate != actual.sampleRate || observed.channelCount != actual.channelCount ||
        observed.sha256 != raw.configuration.sha256 || !observed.asc.contentEquals(raw.configuration.asc))
        throw SaveFailure(SaveError.INVALID_MEDIA)
    return observed
}

/** Exact raw transport bytes only. Trusted native/Media3 implementations interpret AAC syntax. */
internal fun readDirectNativeAacSample(source: DirectNativeSampleSource, track: Int, index: Long,
    input: MovProbeInput, profile: MovProfile, buffer: ByteBuffer, checkpoint: () -> Unit): DirectReadSample {
    checkpoint()
    val audio = profile.audio ?: throw SaveFailure(SaveError.INVALID_MEDIA)
    if (index !in 0 until audio.clock.sampleCount || source.trackIndex != track ||
        source.timeUs != audio.clock.presentationTimeUs(index)) throw SaveFailure(SaveError.INVALID_MEDIA)
    val flags = directAndroidSync(source.flags)
    if (flags != 1) throw SaveFailure(SaveError.INVALID_MEDIA)
    val time = source.timeUs
    val size = source.size
    val rawReference = audio.samples[index.toInt()]
    if (size !in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES.toLong() || size != rawReference.size.toLong() ||
        rawReference.index.toLong() != index || rawReference.presentationTimeUs != time || !rawReference.sync)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    if (size > buffer.capacity()) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    buffer.clear()
    val count = source.read(buffer)
    if (count.toLong() != size) throw SaveFailure(SaveError.INVALID_MEDIA)
    buffer.position(0); buffer.limit(count)
    val bytes = ByteArray(count).also { buffer.duplicate().get(it) }
    val raw = directRawScan { MovProfileProbe.readAudioSample(input, profile, index.toInt(), checkpoint) }
    if (!bytes.contentEquals(raw)) throw SaveFailure(SaveError.INVALID_MEDIA)
    val identity = DirectSampleIdentity(index, time, count, DirectMovCanonical.sampleSha256(bytes, checkpoint), flags)
    checkpoint()
    return DirectReadSample(identity, count, bytes)
}

/** One serial owner: a failed native queue cannot create any PCM frame credit. */
internal fun queueDirectAudioInput(sample: DirectNativeSample, accounting: DirectAudioPcmAccounting,
    queue: () -> Unit) {
    accounting.requireNextInput(sample)
    directNativeCall { queue() }
    accounting.inputQueued(sample)
}

internal data class DirectDecodedAudioFormat(val mime: String, val sampleRate: Int,
    val channelCount: Int, val signedPcm16: Boolean)

/** Android's absent PCM-encoding key means signed PCM16; a key read failure is never absence. */
internal fun directSignedPcm16(present: Boolean, readInteger: () -> Int): Boolean =
    !present || directNativeCall { readInteger() == 2 }

internal fun requireDirectPcmFormat(actual: DirectDecodedAudioFormat, sampleRate: Int, channelCount: Int) {
    if (actual.mime != "audio/raw" || actual.sampleRate != sampleRate ||
        actual.channelCount != channelCount || !actual.signedPcm16)
        throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
}

internal data class DirectAudioOutputFlags(val codecConfig: Boolean, val endOfStream: Boolean)

internal fun directAudioOutputFlags(flags: Int): DirectAudioOutputFlags {
    // MediaCodec key-frame=1, codec-config=2, EOS=4. No partial-frame or unknown flags.
    if (flags and 7.inv() != 0 || flags and 6 == 6) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    return DirectAudioOutputFlags(flags and 2 != 0, flags and 4 != 0)
}

/** Enter this before any checkpoint/format/buffer access for each dequeued native output index. */
internal fun <T> withDirectReturnedOutputBuffer(release: () -> Unit, action: () -> T): T {
    var primary: Throwable? = null
    try { return action() }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        // A failed return still belongs to the codec, whose enclosing release is always attempted.
        finishDirectCleanup(primary, {}, releases = emptyList(), after = release)
    }
}

/** Ephemeral native observation. PCM totals are aggregate, never individual-AU/bit exhaustion proof. */
internal data class DirectInspectedAudio(val conservation: DirectTrackConservation,
    val configuration: DirectMovAacConfiguration, val pcm: DirectAudioPcmAccounting.Result)

/** Never fills an observed audio field by copying the source/normalizer's expected conservation. */
internal fun assembleDirectConservation(profile: MovProfile, video: DirectInspectedSamples,
    audio: DirectInspectedAudio?): DirectRemuxConservation {
    if ((profile.audio == null) != (audio == null)) throw SaveFailure(SaveError.INVALID_MEDIA)
    audio?.let(::requireDirectAudioObservation)
    val observed = DirectRemuxConservation(profile.width, profile.height, profile.frameRate,
        video.video.track, audio?.conservation, audio?.configuration?.sampleRate, audio?.configuration?.channelCount,
        video.video.precedingVideoSync ?: throw SaveFailure(SaveError.INVALID_MEDIA))
    if (observed != profile.conservation) throw SaveFailure(SaveError.INVALID_MEDIA)
    return observed
}

internal data class DirectNativeTrackBindings(val video: Int, val audio: Int?)

/** Track order is not authority: bind the exact declared set using the actual native MIME values. */
internal fun bindDirectNativeTracks(mimes: List<String>, hasAudio: Boolean): DirectNativeTrackBindings {
    if (mimes.size != (if (hasAudio) 2 else 1)) throw SaveFailure(SaveError.INVALID_MEDIA)
    val video = mimes.indices.filter { mimes[it] == "video/avc" }
    val audio = mimes.indices.filter { mimes[it] == "audio/mp4a-latm" }
    if (video.size != 1 || audio.size != (if (hasAudio) 1 else 0) || video.size + audio.size != mimes.size)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    return DirectNativeTrackBindings(video.single(), audio.singleOrNull())
}

/** Value assembly only; callers must already have released native resources and revalidated the FD.
 * Host callers can exercise this mapping but cannot create owned completion/publication authority.
 */
internal fun assembleDirectVerificationEvidence(artifact: StagingArtifact,
    output: ArtifactOutputDescriptor.NormalizedDirect, sizeBytes: Long, sha256: String,
    conservation: DirectRemuxConservation, tail: DirectVideoTailProof, positions: Set<DecodePosition>,
    audio: DirectInspectedAudio?): VerificationEvidence {
    if (sizeBytes != output.sizeBytes || sha256 != output.sha256) throw SaveFailure(SaveError.RESOURCE_CHANGED)
    if ((audio == null) != (conservation.audio == null) || audio?.conservation != conservation.audio)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    audio?.let {
        requireDirectAudioObservation(it)
        if (it.configuration.sha256 != conservation.audio?.codecConfigurationSha256 ||
            it.configuration.sampleRate != conservation.audioSampleRate ||
            it.configuration.channelCount != conservation.audioChannelCount) throw SaveFailure(SaveError.INVALID_MEDIA)
    }
    val proof = output.normalizationProof
    if (!proof.isCurrentPolicy()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    val verification = DirectNormalizationVerificationProof(proof.normalizationId, proof.taskId,
        proof.sourceIdentity, artifact.artifactId, artifact.commitId, proof.digest(), conservation, tail)
    if (!verification.matches(proof)) throw SaveFailure(SaveError.INVALID_MEDIA)
    return VerificationEvidence(
        artifactId = artifact.artifactId,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        containerIsMp4 = true,
        videoTrackCount = 1,
        audioTrackCount = if (audio == null) 0 else 1,
        durationMillis = conservation.endpoint.minimumUs / 1000,
        width = conservation.width,
        height = conservation.height,
        encrypted = false,
        decodedPositions = positions,
        audioTrackReadable = audio != null,
        directNormalizationProof = verification,
    )
}

private fun requireDirectAudioObservation(audio: DirectInspectedAudio) {
    val config = audio.configuration
    val pcm = audio.pcm
    if (!pcm.outputEos || pcm.queuedAccessUnits != audio.conservation.sampleCount ||
        pcm.pcmFrames != audio.conservation.sampleCount * 1_024 ||
        pcm.pcmBytes != pcm.pcmFrames * config.channelCount * 2 || pcm.firstOutputTimeUs !in 0..DirectNormalizationLimits.MAX_ROUNDING_US ||
        pcm.outputBufferCount <= 0 || pcm.endpoint != audio.conservation.endpoint)
        throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
}
