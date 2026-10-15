package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic value contracts only; no conversion, audible mapping or storage issuer is simulated. */
class DirectConversionContractsTest {
    private fun proof(audio: Boolean = true, rate: Int = 30): DirectConversionProof {
        val video = DirectConversionVideoExpectation(16, 16, rate, 1_000_000, rate.toLong(), 0,
            ((rate - 1) * 1_000_000L + rate / 2) / rate, 1)
        val sound = if (audio) DirectConversionAudioExpectation(0, 1_000_000, 48_000, 48_000, 2, 0, 0, 0) else null
        val expected = DirectConversionExpectation(video, sound)
        val fact = if (audio) true else null
        return DirectConversionProof("00000000-0000-4000-8000-000000000001", "task", "a".repeat(64), "artifact", "commit",
            4096, "b".repeat(64), sourceTransferFingerprint(transfer()), 2048, "c".repeat(64), expected,
            DirectConversionEncodingSettings.forExpectation(expected),
            DirectConversionCompletionFacts(true, true, true, true, true, true, fact, fact, fact, fact, fact, fact))
    }
    private fun transfer() = TransferResult("artifact", 4096, "video/webm", 4096)
    private fun verification(p: DirectConversionProof) = DirectConversionVerificationProof(p.normalizationId, p.taskId,
        p.sourceIdentity, p.artifactId, p.commitId, p.digest(), p.outputSizeBytes, p.outputSha256,
        p.expectation.video.displayedFrameCount, 0, p.expectation.video.lastDisplayedTimeUs, 1_000_000, 1,
        p.expectation.audio?.let { DirectConversionAudioVerification(0, 1_000_000, 48_000, 48_000, 2, true, true) }, true, true, true, policyRevision = p.policyRevision)
    private fun evidence(p: DirectConversionProof) = VerificationEvidence("artifact", 2048, p.outputSha256, true, 1,
        if (p.expectation.audio == null) 0 else 1, 1000, 16, 16, false, DecodePosition.entries.toSet(), true,
        directConversionProof = verification(p))

    @Test fun conversionKeepsRawTransferAndCannotValidateAsLegacyOutput() {
        val p = proof(); val raw = transfer(); val output = ArtifactOutputDescriptor.ConvertedDirect(raw, p)
        assertSame(raw, output.sourceTransfer); assertEquals(2048L, output.sizeBytes)
        assertTrue(p.matchesTransfer(raw)); assertTrue(verification(p).matches(p))
        assertTrue(evidence(p).isValidFor(StagingArtifact("artifact", "commit"), output, false))
        assertFalse(evidence(p).isValidFor(StagingArtifact("artifact", "commit"), raw, false))
        assertFalse(evidence(p).copy(directConversionProof = null).isValidFor(StagingArtifact("artifact", "commit"), output, false))
        assertFalse(p.matchesTransfer(raw.copy(responseMimeType = "video/mp4")))
        assertFalse(p.matchesTransfer(raw.copy(bytesOnDisk = 2048, expectedTotalBytes = 2048)))
    }
    @Test fun exactIdentityDigestAndEveryCompletionFactMatter() {
        val p = proof(); val checked = verification(p)
        assertFalse(checked.matches(p.copy(rawSha256 = "d".repeat(64))))
        assertFalse(checked.copy(outputSha256 = "d".repeat(64)).matches(p))
        assertFalse(checked.copy(sourceIdentity = "d".repeat(64)).matches(p))
        assertFalse(checked.copy(conversionProofSha256 = "d".repeat(64)).matches(p))
        assertFalse(checked.copy(containerComplete = false).matches(p))
        assertFalse(checked.copy(videoOutputEos = false).matches(p))
        assertFalse(checked.copy(nativeResourcesReleased = false).matches(p))
        assertThrows(IllegalArgumentException::class.java) { p.copy(completion = p.completion.copy(nativeResourcesReleased = false)) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(completion = p.completion.copy(sourceConsumerAcceptedEos = false)) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(completion = p.completion.copy(audioEncoderOutputEnded = null)) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(schemaVersion = 2) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(engineRevision = 2) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(policyRevision = 3) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(sourceIdentity = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(normalizationId = "1-1-1-1-1") }
    }
    @Test fun actualVisibleFramesAndEveryFrameCfrAggregateAreBounded() {
        val p = proof(); val v = p.expectation.video
        assertThrows(IllegalArgumentException::class.java) { v.copy(displayedFrameCount = 31) }
        assertThrows(IllegalArgumentException::class.java) { v.copy(maximumFrameDeviationUs = 1001) }
        assertThrows(IllegalArgumentException::class.java) { v.copy(lastDisplayedTimeUs = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { v.copy(width = 1920, height = 1920) }
        assertThrows(IllegalArgumentException::class.java) { v.copy(frameRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { verification(p).copy(maximumFrameDeviationUs = 1001) }
        assertFalse(verification(p).copy(decodedVideoFrameCount = 29).matches(p))
        assertFalse(verification(p).copy(lastVideoTimeUs = v.lastDisplayedTimeUs - 1001).matches(p))
        assertTrue(verification(p).copy(lastVideoTimeUs = v.lastDisplayedTimeUs - 1000, maximumFrameDeviationUs = 1000).matches(p))
    }
    @Test fun sourceAndOutputCfrDeviationUseTheSameFloorClockAsTheObserver() {
        val p = proof(); val video = p.expectation.video
        assertEquals(966_666L, video.frameTimeUs(29))
        val exact = video.copy(lastDisplayedTimeUs = 966_666, maximumFrameDeviationUs = 0)
        assertEquals(0L, exact.maximumFrameDeviationUs)
        assertThrows(IllegalArgumentException::class.java) {
            video.copy(lastDisplayedTimeUs = 966_667, maximumFrameDeviationUs = 0)
        }
        assertEquals(1L, video.copy(lastDisplayedTimeUs = 966_667, maximumFrameDeviationUs = 1).maximumFrameDeviationUs)
        val checked = verification(p)
        assertTrue(checked.copy(lastVideoTimeUs = 966_666, maximumFrameDeviationUs = 0).matches(p))
        assertFalse(checked.copy(lastVideoTimeUs = 966_667, maximumFrameDeviationUs = 0).matches(p))
        assertTrue(checked.copy(lastVideoTimeUs = 966_667, maximumFrameDeviationUs = 1).matches(p))
    }
    @Test fun historicalAudioKeepsOriginalCouplingAtTheEnclosingPolicy() {
        val p = proof(); val source = p.expectation.audio!!
        assertThrows(IllegalArgumentException::class.java) { source.copy(sourceStartUs = 1001) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(codecDelayUs = 120_001) }
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(expectation = p.expectation.copy(audio = source.copy(sourcePcmFrameCount = 47_999)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(expectation = p.expectation.copy(audio = source.copy(encoderPcmFrameCount = 48_001)))
        }
        for ((end, frames) in listOf(800_000L to 38_400L, 1_200_000L to 57_600L)) {
            val sound = source.copy(sourceEndUs = end, sourcePcmFrameCount = frames, encoderPcmFrameCount = frames)
            val expected = DirectConversionExpectation(p.expectation.video, sound)
            assertThrows(IllegalArgumentException::class.java) { p.copy(expectation = expected) }
        }
        // Policy 1's original 1 ms source rounding must remain readable, without becoming current.
        val historical = p.copy(expectation = p.expectation.copy(audio = source.copy(sourceStartUs = 1000)))
        assertFalse(historical.isCurrentPolicy())
        assertTrue(verification(historical).matches(historical))
        assertThrows(IllegalArgumentException::class.java) { historical.copy(policyRevision = 2) }
    }
    @Test fun audioPolicyTwoPreservesShortSourceAndIndependentlyChecksEncoderE() {
        val old = proof(); val sound = old.expectation.audio!!.copy(sourceEndUs = 600_000,
            sourcePcmFrameCount = 28_800, encoderPcmFrameCount = 48_000)
        val p = old.copy(expectation = old.expectation.copy(audio = sound), policyRevision = 2)
        assertTrue(p.isCurrentPolicy())
        assertEquals(600_000L, sound.effectiveDurationUs)
        assertEquals(1_000_000L, sound.encoderDurationUs)
        assertTrue(verification(p).matches(p))
        val checked = verification(p)
        val checkedAudio = requireNotNull(checked.audio)
        val sourceSizedOutput = checkedAudio.copy(outputEndUs = 600_000, effectiveDecodedPcmFrameCount = 28_800)
        assertFalse(checked.copy(audio = sourceSizedOutput).matches(p))
        assertFalse(checkedAudio.matches(sound, 1))
        assertFalse(checkedAudio.matches(sound, 3))
        val wrongE = p.copy(expectation = p.expectation.copy(audio = sound.copy(encoderPcmFrameCount = 60_000)))
        assertFalse(verification(wrongE).matches(wrongE))
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(expectation = p.expectation.copy(audio = sound.copy(encoderPcmFrameCount = 28_799)))
        }
        for (frames in listOf(0L, DirectConversionLimits.MAX_PCM_FRAMES + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { sound.copy(sourcePcmFrameCount = frames) }
            assertThrows(IllegalArgumentException::class.java) { sound.copy(encoderPcmFrameCount = frames) }
        }
        assertThrows(IllegalArgumentException::class.java) { p.copy(policyRevision = 1) }
    }
    @Test fun audioPolicyTwoUsesSampleClockRoundingRatherThanHistoricalMillisecondSlack() {
        val old = proof(); val sound = old.expectation.audio!!.copy(sourceEndUs = 600_000,
            sourcePcmFrameCount = 28_800, encoderPcmFrameCount = 48_000)
        fun current(audio: DirectConversionAudioExpectation) =
            old.copy(expectation = old.expectation.copy(audio = audio), policyRevision = 2)
        for (delta in listOf(-2L, 0L, 2L)) assertTrue(current(sound.copy(sourceEndUs = 600_000 + delta)).isCurrentPolicy())
        for (delta in listOf(-3L, 3L, 1000L)) assertThrows(IllegalArgumentException::class.java) {
            current(sound.copy(sourceEndUs = 600_000 + delta))
        }
        val nonIntegral = current(sound.copy(sourcePcmFrameCount = 28_801, sourceEndUs = 600_021))
        assertEquals(600_021L, nonIntegral.expectation.audio!!.effectiveDurationUs)
        assertTrue(verification(nonIntegral).matches(nonIntegral))
    }
    @Test fun revisionPairsAreMandatoryAndSilentRemainsPolicyOne() {
        val historical = proof(); val current = historical.copy(policyRevision = 2); val silent = proof(false)
        assertFalse(historical.isCurrentPolicy()); assertTrue(current.isCurrentPolicy()); assertTrue(silent.isCurrentPolicy())
        assertTrue(verification(historical).matches(historical)); assertTrue(verification(current).matches(current))
        assertFalse(verification(current).copy(policyRevision = 1).matches(current))
        assertFalse(verification(historical).copy(policyRevision = 2).matches(historical))
        for (revision in listOf(0, 3, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { current.copy(policyRevision = revision) }
            assertThrows(IllegalArgumentException::class.java) { verification(current).copy(policyRevision = revision) }
        }
        assertThrows(IllegalArgumentException::class.java) { silent.copy(policyRevision = 2) }
        assertThrows(IllegalArgumentException::class.java) { verification(silent).copy(policyRevision = 2) }
        assertThrows(IllegalArgumentException::class.java) { verification(current).copy(audio = null) }
    }
    @Test fun historicalPolicyOneDigestDomainAndBytesRemainStable() {
        assertEquals("a287f4ea67e7df5457cf270f8f29683185edd1c193b32e006b9d3a3fe1e60297", proof().digest())
        assertEquals("2353b7d7e34d448a1fcf16feaaf334ec690a53f7e1eb96eafd07df6927086960", proof(false).digest())
        assertNotEquals(proof().digest(), proof().copy(policyRevision = 2).digest())
    }
    @Test fun audioBoundaryExactly42667DoesNotStackVideoOrOpusAndNeverPermitsInteriorGaps() {
        val p = proof(rate = 1); val a = verification(p).audio!!; val source = p.expectation.audio!!
        assertTrue(a.copy(outputStartUs = 42_667, effectiveDecodedPcmFrameCount = 45_952).matches(source))
        assertFalse(a.copy(outputStartUs = 42_668, effectiveDecodedPcmFrameCount = 45_952).matches(source))
        assertTrue(a.copy(outputEndUs = 1_042_667, effectiveDecodedPcmFrameCount = 50_048).matches(source))
        assertFalse(a.copy(outputEndUs = 1_042_668, effectiveDecodedPcmFrameCount = 50_048).matches(source))
        assertTrue(a.copy(effectiveDecodedPcmFrameCount = 50_048, outputEndUs = 1_042_667).matches(source))
        assertFalse(a.copy(effectiveDecodedPcmFrameCount = 50_049, outputEndUs = 1_042_688).matches(source))
        assertFalse(a.copy(outputEndUs = 1_162_667).matches(source.copy(codecDelayUs = 120_000)))
        assertFalse(a.copy(outputEndUs = 900_000).matches(source))
        assertFalse(a.copy(outputStartUs = 42_667).matches(source))
        assertFalse(a.copy(outputEndUs = 1_042_667).matches(source))
        assertFalse(a.copy(effectiveDecodedPcmFrameCount = 50_048).matches(source))
        assertTrue(a.copy(outputEndUs = 1_000_002).matches(source))
        assertFalse(a.copy(outputEndUs = 1_000_003).matches(source))
        assertFalse(a.copy(continuous = false).matches(source))
        assertFalse(a.copy(outputEos = false).matches(source))
    }
    @Test fun audioPolicyTwoBoundaryChecksUseEAndNeverSourceOrOpusPadding() {
        val old = proof(); val source = old.expectation.audio!!.copy(sourceEndUs = 600_000,
            sourcePcmFrameCount = 28_800, encoderPcmFrameCount = 48_000, discardPaddingUs = 120_000)
        val p = old.copy(expectation = old.expectation.copy(audio = source), policyRevision = 2)
        val a = verification(p).audio!!
        assertTrue(a.copy(outputStartUs = 42_667, effectiveDecodedPcmFrameCount = 45_952).matches(source, 2))
        assertFalse(a.copy(outputStartUs = 42_668, effectiveDecodedPcmFrameCount = 45_952).matches(source, 2))
        assertTrue(a.copy(outputEndUs = 1_042_667, effectiveDecodedPcmFrameCount = 50_048).matches(source, 2))
        assertFalse(a.copy(outputEndUs = 1_042_668, effectiveDecodedPcmFrameCount = 50_048).matches(source, 2))
        assertTrue(a.copy(outputEndUs = 1_000_002).matches(source, 2))
        assertFalse(a.copy(outputEndUs = 1_000_003).matches(source, 2))
        assertFalse(a.copy(outputEndUs = 880_000, effectiveDecodedPcmFrameCount = 42_240).matches(source, 2))
        assertFalse(a.copy(outputEndUs = 1_042_667).matches(source, 2))
        assertFalse(a.copy(effectiveDecodedPcmFrameCount = 50_048).matches(source, 2))
        assertFalse(a.copy(continuous = false).matches(source, 2))
        assertFalse(a.copy(outputEos = false).matches(source, 2))
    }
    @Test fun silentAndAudibleKindsAreNeverInterchangeable() {
        val p = proof(); val silent = proof(false)
        assertFalse(verification(p).copy(audio = null).matches(p))
        assertFalse(verification(silent).copy(audio = verification(p).audio).matches(silent))
        assertFalse(evidence(silent).isValidFor(StagingArtifact("artifact", "commit"),
            ArtifactOutputDescriptor.ConvertedDirect(transfer(), silent), true))
        assertThrows(IllegalArgumentException::class.java) { p.copy(settings = p.settings.copy(audioBitrate = 96_000)) }
        assertThrows(IllegalArgumentException::class.java) { silent.copy(settings = silent.settings.copy(audioBitrate = 192_000)) }
    }
    @Test fun legacyVerifierDefaultRejectsConvertedBeforeCallingTransferAdapter() {
        var calls = 0
        val adapter = ArtifactVerifier { _, _, _ -> calls++; evidence(proof()) }
        val error = assertThrows(SaveFailure::class.java) { adapter.verify(StagingArtifact("artifact", "commit"),
            ArtifactOutputDescriptor.ConvertedDirect(transfer(), proof()), OperationContext("op", Long.MAX_VALUE, CancellationSource())) }
        assertEquals(SaveError.METHOD_UNSUPPORTED, error.code); assertEquals(0, calls)
    }
}
