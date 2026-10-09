package com.easysavevideo.app.editor.verification

import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditOutputExpectation
import org.junit.Assert.*
import org.junit.Test

class EditVerificationPolicyTest {
    private fun expected(audio: EditAudioProfile = EditAudioProfile.Silent) =
        EditOutputExpectation(120_000, 16, 16, 25, audio, 40_000, if (audio is EditAudioProfile.Aac) 43_000 else 0)
    private fun timing() = EditMp4Track("vide", intArrayOf(4, 4, 4), 12, 1_000_000, 120_000, 40_000, 16, 16, 0, 66)
    private fun videoScan(): EditScannedTrack = EditSampleLedger(true, expected(), timing()).run {
        sample(0, 4, 1); sample(40_000, 4, 0); sample(80_000, 4, 0); finish()
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected edit verification rejection") } catch (_: EditFailure) { }
    }

    @Test fun fullSampleWalkUsesExactFinalSampleDuration() {
        val actual = videoScan()
        assertEquals(120_000L, actual.endUs)
        assertEquals(80_000L, actual.lastPtsUs)
        assertEquals(3, actual.samples.size)
        assertEquals(12L, actual.bytes)
    }
    @Test fun expectedFrameCountComesFromActualSnappedSourceTimeline() {
        reject { EditSampleLedger(true, expected().copy(videoFrameCount = 4), timing()) }
    }
    @Test fun inwardSnappedNonFrameTrimProducesRebasedCfr() {
        // Requested source [15ms, 175ms), actual source-frame boundaries [40ms,160ms).
        // Source pixels/time selection is the planner/engine responsibility; verifier gets 3 frames.
        val snapped = expected().copy(durationUs = 120_000, videoFrameCount = 3)
        val ledger = EditSampleLedger(true, snapped, timing())
        ledger.sample(0, 4, 1); ledger.sample(40_000, 4, 0); ledger.sample(80_000, 4, 0)
        assertEquals(120_000L, ledger.finish().endUs)
    }
    @Test fun snappedMergeJoinsDoNotPermitAnArbitraryCadenceReset() {
        // Two three-frame snapped pieces form six contiguous output frames, regardless of
        // the original requested (non-frame-aligned) boundaries or source ordering.
        val joined = expected().copy(durationUs = 240_000, videoFrameCount = 6)
        val timing = EditMp4Track("vide", IntArray(6) { 4 }, 24, 1_000_000, 240_000, 40_000, 16, 16, 0, 66)
        val ledger = EditSampleLedger(true, joined, timing)
        repeat(6) { ledger.sample(it * 40_000L, 4, if (it == 0 || it == 3) 1 else 0) }
        assertEquals(240_000L, ledger.finish().endUs)
        val wrongJoin = EditSampleLedger(true, joined, timing)
        repeat(3) { wrongJoin.sample(it * 40_000L, 4, if (it == 0) 1 else 0) }
        reject { wrongJoin.sample(140_000, 4, 1) }
    }
    @Test fun omittedTailCannotUseMetadataAsCoverage() {
        val ledger = EditSampleLedger(true, expected(), timing())
        ledger.sample(0, 4, 1); ledger.sample(40_000, 4, 0)
        reject { ledger.finish() }
    }
    @Test fun finalFrameCannotHaveAnInventedNearZeroDisplayDuration() {
        val ledger = EditSampleLedger(true, expected(), timing().copy(durationTicks = 80_001, lastDurationTicks = 1))
        ledger.sample(0, 4, 1); ledger.sample(40_000, 4, 0); ledger.sample(80_000, 4, 0)
        reject { ledger.finish() }
    }
    @Test fun changedSampleSizeFailsEvenIfTotalCouldMatch() {
        reject { EditSampleLedger(true, expected(), timing()).sample(0, 3, 1) }
    }
    @Test fun encryptedPartialAndUnknownFlagsFailClosed() {
        for (flag in listOf(2, 4, 8, 3, -1)) reject { EditSampleLedger(true, expected(), timing()).sample(0, 4, flag) }
    }
    @Test fun firstVideoAccessUnitMustBeSync() {
        reject { EditSampleLedger(true, expected(), timing()).sample(0, 4, 0) }
    }
    @Test fun duplicateReorderedAndVariableFrameTimesFail() {
        for (time in listOf(0L, -40_000L, 80_000L, 50_000L)) {
            val ledger = EditSampleLedger(true, expected(), timing())
            ledger.sample(0, 4, 1)
            reject { ledger.sample(time, 4, 0) }
        }
    }
    @Test fun boundedClockRoundingIsAccepted() {
        val ledger = EditSampleLedger(true, expected(), timing())
        ledger.sample(0, 4, 1); ledger.sample(40_001, 4, 0); ledger.sample(80_000, 4, 0)
        assertEquals(120_000L, ledger.finish().endUs)
    }
    @Test fun individuallySmallFrameDriftCannotAccumulateAcrossTheOutput() {
        val ledger = EditSampleLedger(true, expected(), timing())
        ledger.sample(0, 4, 1); ledger.sample(40_600, 4, 0)
        reject { ledger.sample(81_200, 4, 0) }
    }
    @Test fun noSampleMayBeAddedAfterFinishOrRejection() {
        val ledger = EditSampleLedger(true, expected(), timing())
        reject { ledger.sample(0, 4, 0) }
        reject { ledger.sample(0, 4, 1) }
        val finished = EditSampleLedger(true, expected(), timing())
        finished.sample(0, 4, 1); finished.sample(40_000, 4, 0); finished.sample(80_000, 4, 0); finished.finish()
        reject { finished.finish() }
    }
    @Test fun completeVideoDecodeRequiresEveryActualSampleAndEos() {
        val scan = videoScan(); val ledger = EditVideoDecodeLedger(scan)
        scan.samples.forEach { ledger.input(it) }; ledger.inputEnd()
        assertFalse(ledger.outputEos)
        scan.samples.forEach { ledger.output(it.ptsUs, 1024, false, false) }
        assertEquals(0L, ledger.decodedFrames)
        ledger.output(0, 0, false, true)
        assertTrue(ledger.outputEos); assertEquals(3L, ledger.decodedFrames)
    }
    @Test fun codecConfigDoesNotCountAsDecodedVideo() {
        val scan = videoScan(); val ledger = EditVideoDecodeLedger(scan)
        scan.samples.forEach { ledger.input(it) }; ledger.inputEnd()
        ledger.output(0, 100, true, false)
        ledger.output(40_000, 100, true, false)
        reject { ledger.output(80_000, 100, false, true) }
    }
    @Test fun prematureVideoEosIsRejected() {
        val scan = videoScan(); val ledger = EditVideoDecodeLedger(scan)
        scan.samples.forEach { ledger.input(it) }; ledger.inputEnd()
        ledger.output(0, 1, false, false); ledger.output(40_000, 1, false, false)
        reject { ledger.output(0, 0, false, true) }
    }
    @Test fun nearbySeekFrameCannotStandInForExactTail() {
        val scan = videoScan(); val ledger = EditVideoDecodeLedger(scan)
        scan.samples.forEach { ledger.input(it) }; ledger.inputEnd()
        ledger.output(0, 1, false, false); ledger.output(40_000, 1, false, false)
        reject { ledger.output(79_999, 1, false, true) }
    }
    @Test fun decodeCannotInventOrDuplicateFrames() {
        val scan = videoScan(); val ledger = EditVideoDecodeLedger(scan)
        scan.samples.forEach { ledger.input(it) }; ledger.inputEnd()
        ledger.output(0, 1, false, false)
        reject { ledger.output(0, 1, false, false) }
    }
    @Test fun inputMustMatchPreviouslyScannedIdentity() {
        val scan = videoScan()
        reject { EditVideoDecodeLedger(scan).input(scan.samples[0].copy(bytes = 5)) }
        reject { EditVideoDecodeLedger(scan).inputEnd() }
    }

    private fun audioScan(): EditScannedTrack {
        val samples = (0 until 6).map { EditEncodedSample(it * 1024_000_000L / 48_000, 8, 1) }
        return EditScannedTrack(samples, 48, 0, samples.last().ptsUs, 128_000)
    }
    private fun audioLedger(): EditAudioDecodeLedger = EditAudioDecodeLedger(expected(EditAudioProfile.Aac(48_000, 2)), audioScan()).also { ledger ->
        audioScan().samples.forEach(ledger::input); ledger.inputEnd()
    }
    @Test fun aacPrimingBudgetAndFullPcmCoverageCanPass() {
        val ledger = audioLedger()
        audioScan().samples.forEach { ledger.output(it.ptsUs, 1024 * 4, false, false) }
        ledger.output(0, 0, false, true)
        assertTrue(ledger.outputEos); assertEquals(6144L, ledger.decodedFrames)
    }
    @Test fun aacInteriorGapCannotBeExcusedAsPriming() {
        val ledger = audioLedger()
        ledger.output(0, 4096, false, false)
        reject { ledger.output(42_666, 4096, false, false) }
    }
    @Test fun aacDuplicatePcmAndFractionalFramesFail() {
        val duplicate = audioLedger(); duplicate.output(0, 4096, false, false)
        reject { duplicate.output(0, 4096, false, false) }
        reject { audioLedger().output(0, 4095, false, false) }
    }
    @Test fun emptyOrTruncatedAacDecodeIsNotEvidence() {
        reject { audioLedger().output(0, 0, false, true) }
        val truncated = audioLedger(); truncated.output(0, 4096, false, false)
        reject { truncated.output(0, 0, false, true) }
    }
    @Test fun aacLcConfigurationRequiresExactRateAndChannels() {
        EditAacConfigPolicy.requireLc(byteArrayOf(0x11, 0x90.toByte()), 48_000, 2)
        reject { EditAacConfigPolicy.requireLc(byteArrayOf(0x11, 0x90.toByte()), 44_100, 2) }
        reject { EditAacConfigPolicy.requireLc(byteArrayOf(0x11, 0x90.toByte()), 48_000, 1) }
        reject { EditAacConfigPolicy.requireLc(byteArrayOf(0x11, 0x91.toByte()), 48_000, 2) }
        reject { EditAacConfigPolicy.requireLc(byteArrayOf(0x29, 0x90.toByte()), 48_000, 2) }
        reject { EditAacConfigPolicy.requireLc(byteArrayOf(0x11, 0x90.toByte(), 0), 48_000, 2) }
    }
    @Test fun noProgressBudgetCannotBeRenewedByPollingOrReversedClock() {
        val progress = EditDecodeProgress(10)
        progress.check(100); progress.progressed(200)
        reject { progress.check(200 + EditVerificationBounds.NO_PROGRESS_NANOS) }
        reject { EditDecodeProgress(100).check(99) }
    }
}
