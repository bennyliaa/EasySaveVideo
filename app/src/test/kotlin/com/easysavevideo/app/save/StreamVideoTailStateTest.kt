package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test

class StreamVideoTailStateTest {
    private val samples = listOf(
        StreamTailSample(1_000_000, 1, 100, 96, "a".repeat(64)),
        StreamTailSample(1_033_333, 0, 80, 76, "b".repeat(64)),
        StreamTailSample(1_066_667, 0, 90, 86, "c".repeat(64)),
    )
    private fun failure(code: SaveError = SaveError.INVALID_MEDIA, block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (e: SaveFailure) { assertEquals(code, e.code) }
    }
    private fun queued(): StreamVideoTailState = StreamVideoTailState(samples) {}.also { state ->
        samples.forEach(state::input); state.inputEndOfStream()
    }
    @Test fun finalNonemptyOutputMayCarryEos() {
        val state = queued()
        samples.forEachIndexed { i, sample -> state.output(sample.timeUs, 10, false, i == samples.lastIndex) }
        assertTrue(state.complete); assertEquals(3, state.decodedSamples)
    }
    @Test fun emptyEosOnlyCompletesAfterEveryOutput() {
        val state = queued()
        samples.forEach { state.output(it.timeUs, 10, false, false) }
        assertFalse(state.complete)
        state.output(0, 0, false, true)
        assertTrue(state.complete)
    }
    @Test fun missingLastAccessUnitAndEarlyEosFail() {
        val state = StreamVideoTailState(samples) {}
        samples.dropLast(1).forEach(state::input)
        failure { state.inputEndOfStream() }
        val early = StreamVideoTailState(samples) {}
        samples.forEach(early::input)
        samples.forEach { early.output(it.timeUs, 10, false, false) }
        failure { early.output(0, 0, false, true) }
    }
    @Test fun corruptedPayloadMetadataOrWrongSeekIdentityFails() {
        for (bad in listOf(samples[0].copy(sha256 = "d".repeat(64)), samples[0].copy(timeUs = 999_999),
            samples[0].copy(inputBytes = 99), samples[0].copy(canonicalBytes = 95), samples[0].copy(flags = 0))) {
            failure { StreamVideoTailState(samples) {}.input(bad) }
        }
    }
    @Test fun nearEndOutputAndConfigOnlyTailAreNotTerminalProof() {
        val near = queued()
        samples.dropLast(1).forEach { near.output(it.timeUs, 10, false, false) }
        failure { near.output(samples.last().timeUs - 10_000, 10, false, true) }
        val config = queued()
        samples.dropLast(1).forEach { config.output(it.timeUs, 10, false, false) }
        failure { config.output(samples.last().timeUs, 10, true, true) }
    }
    @Test fun lostIntermediateGopOutputIsRejectedEvenIfFinalFrameAppears() {
        val state = queued()
        state.output(samples.first().timeUs, 10, false, false)
        failure { state.output(samples.last().timeUs, 10, false, true) }
    }
    @Test fun duplicateInputOutputAndRepeatedEosFail() {
        val duplicateInput = StreamVideoTailState(samples) {}
        duplicateInput.input(samples.first())
        failure { duplicateInput.input(samples.first()) }
        val duplicateOutput = queued()
        duplicateOutput.output(samples.first().timeUs, 10, false, false)
        failure { duplicateOutput.output(samples.first().timeUs, 10, false, false) }
        failure { queued().inputEndOfStream() }
        val completed = queued()
        samples.forEach { completed.output(it.timeUs, 10, false, false) }
        completed.output(0, 0, false, true)
        failure { completed.output(0, 0, false, true) }
        assertFalse(completed.complete)
    }
    @Test fun cancellationPoisonsEvidenceEvenIfCheckpointLaterRecovers() {
        var cancelled = false
        val state = StreamVideoTailState(samples) { if (cancelled) throw SaveFailure(SaveError.CANCELLED) }
        samples.forEach(state::input); state.inputEndOfStream()
        cancelled = true
        failure(SaveError.CANCELLED) { state.output(samples.first().timeUs, 10, false, false) }
        cancelled = false
        failure { state.output(samples.first().timeUs, 10, false, false) }
        assertFalse(state.complete)
    }
    @Test fun outputCannotPrecedeItsQueuedInputOrArriveAfterTerminalProof() {
        failure { StreamVideoTailState(samples) {}.output(samples.first().timeUs, 10, false, false) }
        val state = queued()
        samples.forEach { state.output(it.timeUs, 10, false, false) }
        state.output(0, 0, false, true)
        failure { state.input(samples.last()) }
    }
    @Test fun noProgressFailsAndOnlyRealProgressResetsBudget() {
        val progress = StreamDecodeProgress(100, 50)
        progress.check(149)
        progress.madeProgress(149)
        progress.check(198)
        failure(SaveError.DEADLINE_EXCEEDED) { progress.check(199) }
    }
}

class StreamVideoFormatStateTest {
    private val format = StreamDecodedVideoFormat("video/raw", 1280, 720, 0, 1279, 0, 719, 21)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected unsupported format") }
        catch (e: SaveFailure) { assertEquals(SaveError.UNSUPPORTED_MEDIA, e.code) }
    }
    @Test fun initialOutputFormatAndPaddedCropAreSupported() {
        val state = StreamVideoFormatState(1280, 720)
        state.accept(format, true); state.accept(format, false); state.accept(format, false)
        val padded = format.copy(height = 736)
        StreamVideoFormatState(1280, 720).also { it.accept(padded, true); it.accept(padded, false) }
    }
    @Test fun wrongDimensionsMimeCropOrLaterConfigurationAreRejected() {
        for (bad in listOf(format.copy(mime = "video/avc"), format.copy(cropRight = 1280),
            format.copy(width = 1279), format.copy(cropBottom = 718), format.copy(cropTop = -1),
            format.copy(width = 8192, height = 8192))) {
            rejected { StreamVideoFormatState(1280, 720).accept(bad, true) }
        }
        val state = StreamVideoFormatState(1280, 720)
        state.accept(format, true)
        rejected { state.accept(format.copy(color = 19), false) }
    }
    @Test fun repeatedFormatEventOrEventAfterOutputCannotBecomeProof() {
        val repeated = StreamVideoFormatState(1280, 720)
        repeated.accept(format, true)
        rejected { repeated.accept(format, true) }
        val late = StreamVideoFormatState(1280, 720)
        late.accept(format, false)
        rejected { late.accept(format, true) }
    }
}
