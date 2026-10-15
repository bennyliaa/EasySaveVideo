package com.easysavevideo.app.media.verification

import com.easysavevideo.app.editor.verification.EditMp4Track
import com.easysavevideo.app.editor.verification.EditSampleLedger
import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditOutputExpectation
import org.junit.Assert.*
import org.junit.Test

/** Pure measured-clock/decode-ledger tests, never a substitute for Android media acceptance. */
class ConvertedFixedClockMeasurementTest {
    private fun samples(vararg times: Long) = times.mapIndexed { index, time -> AvcAacEncodedSample(time, 4, if (index == 0) 1 else 0) }
    private fun scan() = AvcAacScannedTrack(samples(0, 40_000, 80_000), 12, 0, 80_000, 120_000)

    @Test fun everyTimestampIncludingMiddleContributesToFixedZeroOriginMaximum() {
        var checkpoints = 0
        val actual = maximumFixedVideoDeviationUs(samples(800, 41_600, 80_800), 25) { checkpoints++ }
        assertEquals(1_600L, actual)
        assertEquals(4, checkpoints)
        // Looking only at these endpoints would have incorrectly accepted <= 1,000 us.
        assertEquals(800L, maximumFixedVideoDeviationUs(samples(800, 40_800, 80_800), 25) {})
    }

    @Test fun sourceOffsetAndRelativeDriftRemainAcceptedByExistingEditorPolicy() {
        val expected = EditOutputExpectation(120_000, 16, 16, 25, EditAudioProfile.Silent, 40_000, 0)
        val timing = EditMp4Track("vide", intArrayOf(4, 4, 4), 12, 1_000_000, 120_000, 40_000, 16, 16, 0, 66)
        val editor = EditSampleLedger(true, expected, timing)
        editor.sample(800, 4, 1); editor.sample(41_600, 4, 0); editor.sample(80_800, 4, 0)
        val scan = editor.finish()
        assertEquals(120_800L, scan.endUs)
        assertEquals(1_600L, maximumFixedVideoDeviationUs(scan.samples, 25) {})
    }

    @Test fun integerFloorIsMeasuredWithoutRoundingCopyingOrClamping() {
        val samples = (0..10).map { AvcAacEncodedSample(it * 1_000_000L / 30, 4, if (it == 0) 1 else 0) }
        assertEquals(0L, maximumFixedVideoDeviationUs(samples, 30) {})
        assertEquals(12_345L, maximumFixedVideoDeviationUs(samples.mapIndexed { i, sample ->
            if (i == 4) sample.copy(ptsUs = sample.ptsUs + 12_345) else sample
        }, 30) {})
        assertEquals(Long.MAX_VALUE, maximumFixedVideoDeviationUs(listOf(AvcAacEncodedSample(Long.MAX_VALUE, 4, 1)), 1) {})
    }

    @Test fun measurementCheckpointsPreserveCallerFailureAndRejectUnboundedInputs() {
        val selected = IllegalStateException("SYNTHETIC_STOP")
        assertSame(selected, assertThrows(IllegalStateException::class.java) {
            maximumFixedVideoDeviationUs(samples(0, 40_000), 25) { throw selected }
        })
        for ((values, fps) in listOf(emptyList<AvcAacEncodedSample>() to 25,
            samples(0) to 0, samples(-1) to 25)) {
            assertThrows(AvcAacVerificationFailure::class.java) { maximumFixedVideoDeviationUs(values, fps) {} }
        }
    }

    @Test fun allScannedSamplesMustDecodeExactlyOnceAndActualEosMustArrive() {
        val track = scan()
        val ledger = AvcAacVideoDecodeLedger(track)
        track.samples.forEach(ledger::input); ledger.inputEnd()
        track.samples.forEach { ledger.output(it.ptsUs, 1, config = false, eos = false) }
        assertFalse(ledger.outputEos); assertEquals(0L, ledger.decodedFrames)
        ledger.output(0, 0, config = false, eos = true)
        assertTrue(ledger.outputEos); assertEquals(3L, ledger.decodedFrames)
        for (bad in listOf(0L, 40_001L, 80_000L)) {
            val invalid = AvcAacVideoDecodeLedger(track)
            track.samples.forEach(invalid::input); invalid.inputEnd()
            invalid.output(0, 1, config = false, eos = false)
            assertThrows(AvcAacVerificationFailure::class.java) { invalid.output(bad, 1, config = false, eos = false) }
        }
        val missingTail = AvcAacVideoDecodeLedger(track)
        track.samples.forEach(missingTail::input); missingTail.inputEnd()
        missingTail.output(0, 1, config = false, eos = false)
        assertThrows(AvcAacVerificationFailure::class.java) { missingTail.output(0, 0, config = false, eos = true) }
    }

    @Test fun productionObservationOnlyReturnsMeasurementAfterFullVideoDecode() {
        val text = java.io.File("src/main/kotlin/com/easysavevideo/app/media/verification/AndroidAvcAacOutputInspection.kt").readText()
        assertTrue(text.indexOf("val videoDecoded = decode(") < text.indexOf("maximumFixedVideoDeviationUs(videoScan.samples"))
        assertFalse(text.contains("maximumFrameDeviationUs"))
        val adapter = java.io.File("src/main/kotlin/com/easysavevideo/app/save/DirectConvertedAndroidVerifier.kt").readText()
        assertTrue(adapter.contains("observed.maximumFixedVideoDeviationUs"))
        assertFalse(adapter.contains("MovProfileProbe")); assertFalse(adapter.contains("stagingFile"))
        assertFalse(adapter.contains("ThreadPoolExecutor")); assertFalse(adapter.contains("RandomAccessFile("))
    }
}
