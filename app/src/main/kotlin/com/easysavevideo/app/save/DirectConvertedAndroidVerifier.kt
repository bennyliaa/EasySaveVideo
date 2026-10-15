package com.easysavevideo.app.save

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import java.io.IOException

/** Same native lane and adopted owner as MOV; borrows the existing read and never opens a path. */
internal class DirectConvertedAndroidVerifier(
    private val owner: DirectNormalizedOutputOwner,
    private val markReleaseUnconfirmed: () -> Unit,
    private val inspectionFactory: (BorrowedAvcAacRead) -> BorrowedAvcAacInspection = { BorrowedAvcAacInspection(it) },
) {
    fun inspect(artifact: StagingArtifact, output: ArtifactOutputDescriptor.ConvertedDirect,
        context: OperationContext, checkpoint: () -> Unit): VerificationEvidence {
        var lease: DirectNormalizedOutputLease? = null
        var inspection: BorrowedAvcAacInspection? = null
        var bound = false
        var nativeConfirmed = false
        var primary: SaveFailure? = null
        var evidence: VerificationEvidence? = null
        fun uncertainty() {
            try { markReleaseUnconfirmed() } catch (_: Throwable) { /* Owner retains independently. */ }
        }
        try {
            checkpoint()
            DirectConvertedPreflight.check(artifact, output, context)
            if (owner.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            // This method is called only after the existing native lease starts on its real worker.
            val acquired = owner.acquire(artifact, output, context).also { lease = it }
            DirectConvertedPreflight.checkLease(acquired, artifact, output)
            val input = acquired.readOnlyInput
            val descriptor = convertedLocalIo(checkpoint) { input.fd }
            val channel = convertedLocalIo(checkpoint) { input.channel }
            val extent = convertedLocalIo(checkpoint) { input.length() }
            if (extent != output.sizeBytes) throw SaveFailure(SaveError.RESOURCE_CHANGED)
            val read = BorrowedAvcAacRead(descriptor, channel, extent)
            val before = convertedLocalIo(checkpoint) { read.sha256(checkpoint) }
            if (before != output.sha256) throw SaveFailure(SaveError.RESOURCE_CHANGED)
            DirectConvertedPreflight.checkLease(acquired, artifact, output)
            val video = output.conversionProof.expectation.video
            val audio = output.conversionProof.expectation.audio
            val expected = AvcAacOutputExpectation(video.sourceDurationUs, video.width, video.height,
                video.frameRate, audio?.let { AvcAacAudioProfile.Aac(it.sampleRate, it.channelCount) }
                    ?: AvcAacAudioProfile.Silent,
                video.durationToleranceUs, if (audio == null) 0 else DirectConversionLimits.AAC_BOUNDARY_US,
                extent, video.displayedFrameCount,
                audio?.let { AvcAacIndependentAudioExpectation(it.encoderPcmFrameCount) })
            val actual = inspectionFactory(read).also { inspection = it }
            acquired.bindNativeInspection(actual)
            bound = true
            val result = actual.inspect(expected, checkpoint)
            // Release is read from the actual bound owner, never from copied result or producer facts.
            if (!actual.nativeReleaseConfirmed) {
                acquired.retainOnNativeReleaseUncertainty(actual)
                uncertainty()
                result.failure?.let { throw it }
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
            acquired.confirmNativeRelease(actual)
            nativeConfirmed = true
            val observed = result.observationsOrThrow()
            checkpoint()
            // Independent full scan + exact one-to-one decode/EOS precedes same-original-FD final hash.
            if (convertedLocalIo(checkpoint) { input.fd !== descriptor || input.channel !== channel ||
                input.length() != extent || read.sha256(checkpoint) != before } || acquired.readOnlyInput !== input)
                throw SaveFailure(SaveError.RESOURCE_CHANGED)
            DirectConvertedPreflight.checkLease(acquired, artifact, output)
            checkpoint()
            evidence = assembleConvertedVerificationEvidence(artifact, output, before, observed)
        } catch (failure: Throwable) {
            primary = mapConvertedVerificationFailure(failure)
        } finally {
            val acquired = lease
            val actual = inspection
            if (acquired != null) {
                var mayClose = true
                if (bound && actual != null) {
                    // Also handles a stop between binding and inspect. releaseNative is sticky and
                    // exact-once internally; a failed release is never retried by this call.
                    if (!actual.nativeReleaseConfirmed) actual.releaseNative()
                    if (!actual.nativeReleaseConfirmed) {
                        mayClose = false
                        acquired.retainOnNativeReleaseUncertainty(actual)
                        uncertainty()
                        if (primary == null) primary = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    } else if (!nativeConfirmed) {
                        try { acquired.confirmNativeRelease(actual); nativeConfirmed = true }
                        catch (failure: Throwable) {
                            mayClose = false
                            acquired.retainOnNativeReleaseUncertainty(actual)
                            uncertainty()
                            if (primary == null) primary = mapConvertedVerificationFailure(failure)
                        }
                    }
                }
                if (mayClose) {
                    try {
                        acquired.close()
                        if (!acquired.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    } catch (_: Throwable) {
                        uncertainty()
                        // Cleanup uncertainty is independent. Never replace a previously selected
                        // cancellation, media, storage, or other Save failure with derivative cleanup.
                        if (primary == null) primary = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    }
                }
            } else if (owner.requiresProcessRecovery()) uncertainty()
        }
        primary?.let { throw it }
        checkpoint() // A stop after positive close still forbids usable completion.
        if (owner.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        return evidence ?: throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    }
}

/** Explicit boundary before Future.get; Editor's mapping is intentionally separate and unchanged. */
internal fun mapConvertedVerificationFailure(failure: Throwable): SaveFailure {
    if (failure is SaveFailure) return failure
    // A thrown primary may have crossed native finally blocks already. Do not select a later stop.
    if (failure is IOException || failure is SecurityException) return SaveFailure(SaveError.STORAGE_FAILURE)
    return SaveFailure(when ((failure as? AvcAacVerificationFailure)?.code) {
        AvcAacVerificationError.VERIFICATION_FAILED -> SaveError.INVALID_MEDIA
        AvcAacVerificationError.UNSUPPORTED_PROFILE -> SaveError.UNSUPPORTED_MEDIA
        AvcAacVerificationError.TIMEOUT -> SaveError.DEADLINE_EXCEEDED
        AvcAacVerificationError.RELEASE_UNCONFIRMED -> SaveError.RESOURCE_RELEASE_UNCONFIRMED
        null -> SaveError.VERIFICATION_UNAVAILABLE
    })
}

/** Stop selection only at actual local-read boundaries, before this adapter begins cleanup. */
internal inline fun <T> convertedLocalIo(checkpoint: () -> Unit, action: () -> T): T = try { action() }
catch (failure: IOException) { checkpoint(); throw SaveFailure(SaveError.STORAGE_FAILURE) }
catch (failure: SecurityException) { checkpoint(); throw SaveFailure(SaveError.STORAGE_FAILURE) }

internal fun assembleConvertedVerificationEvidence(artifact: StagingArtifact,
    output: ArtifactOutputDescriptor.ConvertedDirect, hash: String,
    observed: AvcAacOutputObservations): VerificationEvidence {
    val proof = output.conversionProof
    val video = proof.expectation.video
    if (!proof.isCurrentPolicy() || observed.width != video.width || observed.height != video.height || observed.rotationDegrees != 0 ||
        observed.videoSamples != video.displayedFrameCount || observed.decodedVideoFrames != observed.videoSamples ||
        observed.maximumFixedVideoDeviationUs !in 0..DirectConversionLimits.SOURCE_ROUNDING_US ||
        observed.firstVideoPtsUs !in 0..DirectConversionLimits.SOURCE_ROUNDING_US ||
        observed.lastVideoPtsUs !in observed.firstVideoPtsUs..DirectConversionLimits.MAX_DURATION_US ||
        observed.durationUs !in 1..DirectConversionLimits.MAX_DURATION_US)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    val audioExpected = proof.expectation.audio
    val audioVerified = if (audioExpected == null) {
        if (observed.audioSamples != 0L || observed.decodedAudioFrames != 0L || observed.presentedAudio != null)
            throw SaveFailure(SaveError.INVALID_MEDIA)
        null
    } else {
        // A raw decoder frame count and successful cleanup do not establish effective AAC mapping.
        val audio = observed.presentedAudio ?: throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        if (observed.audioSamples <= 0 || observed.decodedAudioFrames < audio.frames ||
            audio.sampleRate != audioExpected.sampleRate || audio.channels != audioExpected.channelCount)
            throw SaveFailure(SaveError.INVALID_MEDIA)
        try { requireIndependentAacMatches(AvcAacIndependentAudioExpectation(audioExpected.encoderPcmFrameCount), audio) }
        catch (failure: AvcAacVerificationFailure) { throw mapConvertedVerificationFailure(failure) }
        DirectConversionAudioVerification(audio.startUs, audio.endUs, audio.frames, audio.sampleRate,
            audio.channels, audio.outputEos, audio.continuous)
    }
    // These facts come from successful full inspection (complete container walk, one-to-one exact
    // decode and real output EOS) plus actual native release checked by the owning adapter above.
    val verified = DirectConversionVerificationProof(proof.normalizationId, proof.taskId, proof.sourceIdentity,
        artifact.artifactId, artifact.commitId, proof.digest(), output.sizeBytes, hash,
        observed.decodedVideoFrames, observed.firstVideoPtsUs, observed.lastVideoPtsUs, observed.durationUs,
        observed.maximumFixedVideoDeviationUs, audioVerified, containerComplete = true, videoOutputEos = true,
        nativeResourcesReleased = true, policyRevision = proof.policyRevision)
    val evidence = VerificationEvidence(artifact.artifactId, output.sizeBytes, hash, true, 1, if (audioVerified == null) 0 else 1,
        maxOf(1L, observed.durationUs / 1000), observed.width, observed.height, false,
        DecodePosition.entries.toSet(), audioVerified != null, directConversionProof = verified)
    if (!evidence.isValidFor(artifact, output, expectedAudio = audioExpected != null)) throw SaveFailure(SaveError.INVALID_MEDIA)
    return evidence
}
