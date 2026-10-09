package com.easysavevideo.library.edit

import org.junit.Assert.*
import org.junit.Test

class EditVerificationEvidenceTest {
    private val plan = EditFixtures.plan()
    private val output = EditFixtures.output()
    private val proof = EditFixtures.evidence(plan)
    private fun accepts(evidence: EditVerificationEvidence) = evidence.validFor(output, plan.expectation)

    @Test fun fullyConsistentSyntheticEvidencePassesContractOnly() {
        assertTrue(accepts(proof))
        val audioPlan = EditFixtures.plan(EditAudioProfile.Aac(48_000, 2))
        assertTrue(EditFixtures.evidence(audioPlan).validFor(output, audioPlan.expectation))
    }

    @Test fun proofBindsExactOutputOperationArtifactAndExpectedPlan() {
        for (bad in listOf(proof.copy(output = output.copy(operationId = "other")),
            proof.copy(output = output.copy(artifactId = "other")),
            proof.copy(expectation = plan.expectation.copy(width = 320)),
            proof.copy(verificationVersion = 0), proof.copy(verificationVersion = 2))) assertFalse(accepts(bad))
    }

    @Test fun bytesHashGeometryRotationAndTrackMimeCannotBeSubstituted() {
        val mutations = listOf(proof.copy(bytes = 0), proof.copy(bytes = Long.MAX_VALUE), proof.copy(sha256 = ""),
            proof.copy(sha256 = "A".repeat(64)), proof.copy(actualWidth = 360), proof.copy(actualHeight = 640),
            proof.copy(rotationDegrees = 90), proof.copy(videoMime = "video/hevc"),
            proof.copy(audioMime = "audio/mp4a-latm"), proof.copy(audioSamples = 1),
            proof.copy(audioChannels = 2), proof.copy(audioSampleRate = 48_000), proof.copy(audioDecodedFrames = 1))
        mutations.forEach { assertFalse(accepts(it)) }
    }

    @Test fun missingTailSamplesDecodeEosExtentOrNativeReleaseBlockPublication() {
        val mutations = listOf(proof.copy(videoSamples = 0), proof.copy(decodedVideoFrames = 59),
            proof.copy(videoSamples = Long.MAX_VALUE, decodedVideoFrames = Long.MAX_VALUE),
            proof.copy(videoOutputEos = false), proof.copy(containerExtentVerified = false),
            proof.copy(nativeReleaseConfirmed = false), proof.copy(lastVideoPtsUs = 1_000_000),
            proof.copy(firstVideoPtsUs = 100_000), proof.copy(firstVideoPtsUs = -1),
            proof.copy(lastVideoPtsUs = Long.MAX_VALUE), proof.copy(actualDurationUs = Long.MAX_VALUE),
            proof.copy(actualDurationUs = 0), proof.copy(actualDurationUs = 4_000_000))
        mutations.forEach { assertFalse(accepts(it)) }
    }

    @Test fun forgedFrameCountCannotUseCorrectEndTimestampToHideMissingMiddle() {
        assertFalse(accepts(proof.copy(videoSamples = 30, decodedVideoFrames = 30)))
        assertFalse(accepts(proof.copy(videoSamples = 90, decodedVideoFrames = 90)))
    }

    @Test fun aacRequiresMatchingLayoutDecodedPcmAndEos() {
        val audioPlan = EditFixtures.plan(EditAudioProfile.Aac(48_000, 2))
        val p = EditFixtures.evidence(audioPlan)
        val mutations = listOf(p.copy(audioOutputEos = false), p.copy(audioSampleRate = 44_100),
            p.copy(audioChannels = 1), p.copy(audioMime = "audio/opus"), p.copy(audioSamples = 0),
            p.copy(audioDecodedFrames = 0), p.copy(audioDecodedFrames = 48_000),
            p.copy(audioDecodedFrames = Long.MAX_VALUE), p.copy(audioSamples = Long.MAX_VALUE))
        mutations.forEach { assertFalse(it.validFor(output, audioPlan.expectation)) }
    }

    @Test fun aacPacketCountCannotHideMissingMiddleBehindCorrectPcmTotals() {
        val audioPlan = EditFixtures.plan(EditAudioProfile.Aac(48_000, 2))
        val p = EditFixtures.evidence(audioPlan)
        assertFalse(p.copy(audioSamples = 1).validFor(output, audioPlan.expectation))
        assertFalse(p.copy(audioSamples = 200).validFor(output, audioPlan.expectation))
    }

    @Test fun explicitToleranceAllowsBoundaryQuantizationButNotUnboundedDrift() {
        assertTrue(accepts(proof.copy(actualDurationUs = proof.actualDurationUs + plan.expectation.durationToleranceUs)))
        assertFalse(accepts(proof.copy(actualDurationUs = proof.actualDurationUs + plan.expectation.durationToleranceUs + 1)))
        val audioPlan = EditFixtures.plan(EditAudioProfile.Aac(48_000, 2))
        val p = EditFixtures.evidence(audioPlan)
        assertTrue(p.copy(audioDecodedFrames = p.audioDecodedFrames + 1024).validFor(output, audioPlan.expectation))
        assertFalse(p.copy(audioDecodedFrames = p.audioDecodedFrames + 10_240).validFor(output, audioPlan.expectation))
    }
}
