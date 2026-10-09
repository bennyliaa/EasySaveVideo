package com.easysavevideo.app.save

import com.easysavevideo.recording.RecordingResumeBoundary
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test

class RecordingVideoTailStateTest {
    private val samples = listOf(
        RecordingTailSample(1_000_000, 1, 100, "a".repeat(64)),
        RecordingTailSample(1_033_333, 0, 80, "b".repeat(64)),
        RecordingTailSample(1_066_667, 0, 90, "c".repeat(64)),
    )
    private val nextSync = RecordingTailSample(1_100_000, 1, 100, "d".repeat(64))
    private fun failure(code: SaveError = SaveError.INVALID_MEDIA, action: () -> Unit) {
        assertEquals(code, assertThrows(SaveFailure::class.java, action).code)
    }
    private fun state(next: RecordingTailSample? = null, checkpoint: () -> Unit = {}) =
        RecordingVideoTailState(RecordingSelectedGop(0, samples, next), checkpoint)
    private fun queued() = state().also { samples.forEach(it::input); it.inputEndOfStream() }

    @Test fun finalNonemptyOutputMayCarryActualEos() {
        val state = queued()
        samples.forEachIndexed { index, sample -> state.output(sample.timeUs, 16, false, index == samples.lastIndex) }
        assertTrue(state.complete); assertEquals(samples.last().timeUs, state.terminalOutputTimeUs)
        assertEquals(samples.size, state.decodedSamples); assertEquals(samples.size, state.queuedSamples)
        assertFalse(state.canQueueInput)
    }
    @Test fun nonFinalGopStopsAtExactNextSyncWithoutConsumingThatFrame() {
        val state = state(nextSync)
        samples.forEach(state::input)
        state.inputEndOfStream(nextSync.timeUs, nextSync.flags, nextSync.inputBytes.toLong())
        samples.forEach { state.output(it.timeUs, 16, false, false) }
        assertFalse(state.complete)
        state.output(0, 0, false, true)
        assertTrue(state.complete)
        assertEquals(samples.last().timeUs, state.terminalOutputTimeUs)
    }
    @Test fun wrongOrMissingNextSyncAndPrematureExtractorEofFailClosed() {
        val nonFinal = state(nextSync)
        samples.forEach(nonFinal::input)
        failure { nonFinal.inputEndOfStream() }
        for ((time, flags, bytes) in listOf(Triple(nextSync.timeUs + 1, 1, 100L),
            Triple(nextSync.timeUs, 0, 100L), Triple(nextSync.timeUs, 1, 99L))) {
            val wrong = state(nextSync); samples.forEach(wrong::input)
            failure { wrong.inputEndOfStream(time, flags, bytes) }
        }
        val final = state(); samples.forEach(final::input)
        failure { final.inputEndOfStream(nextSync.timeUs, 1, 100) }
        val truncated = state(nextSync); samples.dropLast(1).forEach(truncated::input)
        failure { truncated.inputEndOfStream(nextSync.timeUs, 1, 100) }
    }
    @Test fun emptyEosWaitsForEveryFrameAndEveryInput() {
        val state = queued()
        samples.forEach { state.output(it.timeUs, 16, false, false) }
        assertFalse(state.complete); assertEquals(-1L, state.terminalOutputTimeUs)
        state.output(0, 0, false, true)
        assertTrue(state.complete)
        val premature = state(); samples.forEach(premature::input)
        samples.forEach { premature.output(it.timeUs, 16, false, false) }
        failure { premature.output(0, 0, false, true) }
        val dropped = queued(); samples.dropLast(1).forEach { dropped.output(it.timeUs, 16, false, false) }
        failure { dropped.output(0, 0, false, true) }
    }
    @Test fun truncatedAddedDuplicateAndChangedInputsAreRejected() {
        val truncated = state(); samples.dropLast(1).forEach(truncated::input)
        failure { truncated.inputEndOfStream() }
        for (changed in listOf(samples[0].copy(sha256 = "e".repeat(64)), samples[0].copy(timeUs = 999_999),
            samples[0].copy(flags = 0), samples[0].copy(inputBytes = 99))) failure { state().input(changed) }
        val duplicate = state(); duplicate.input(samples[0]); failure { duplicate.input(samples[0]) }
        val added = state(); samples.forEach(added::input); failure { added.input(nextSync) }
    }
    @Test fun exactOutputPtsRejectsEvenOneMicrosecondDrift() {
        for (drift in listOf(-1000L, -1L, 1L, 1000L)) {
            val near = queued(); samples.dropLast(1).forEach { near.output(it.timeUs, 16, false, false) }
            failure { near.output(samples.last().timeUs + drift, 16, false, true) }
        }
    }
    @Test fun emptyAndConfigOnlyOutputNeverCountsAsAFrameOrProvesEos() {
        val state = queued()
        assertFalse(state.output(0, 0, false, false))
        assertFalse(state.output(samples.first().timeUs, 32, true, false))
        assertEquals(0, state.decodedSamples)
        for (allFrames in listOf(false, true)) {
            val config = queued()
            (if (allFrames) samples else samples.dropLast(1)).forEach { config.output(it.timeUs, 16, false, false) }
            failure { config.output(samples.last().timeUs, 16, true, true) }
        }
    }
    @Test fun missingIntermediateDoubledOutputAndOutputBeforeInputFail() {
        val missing = queued(); missing.output(samples[0].timeUs, 16, false, false)
        failure { missing.output(samples[2].timeUs, 16, false, true) }
        val duplicate = queued(); duplicate.output(samples[0].timeUs, 16, false, false)
        failure { duplicate.output(samples[0].timeUs, 16, false, false) }
        failure { state().output(samples[0].timeUs, 16, false, false) }
        failure { queued().output(samples[0].timeUs, -1, false, false) }
    }
    @Test fun duplicateInputEosAndPostCompletionOutputInvalidateEvidence() {
        failure { queued().inputEndOfStream() }
        val done = queued(); samples.forEach { done.output(it.timeUs, 16, false, false) }
        done.output(0, 0, false, true)
        failure { done.output(0, 0, false, true) }
        assertFalse(done.complete); assertEquals(-1L, done.terminalOutputTimeUs)
    }
    @Test fun selectedGopMustBeginWithOnlyOneSyncAndStayWithinFrameAndPayloadBounds() {
        for (items in listOf(samples.drop(1), samples.toMutableList().apply { this[1] = this[1].copy(flags = 1) })) {
            failure { RecordingVideoTailState(RecordingSelectedGop(0, items, null)) {} }
        }
        val frames = List(600) { samples[0].copy(timeUs = it.toLong(), flags = if (it == 0) 1 else 0) }
        RecordingVideoTailState(RecordingSelectedGop(0, frames, null)) {}
        failure(SaveError.UNSUPPORTED_MEDIA) {
            RecordingVideoTailState(RecordingSelectedGop(0, frames + frames.last().copy(timeUs = 600), null)) {}
        }
        failure(SaveError.UNSUPPORTED_MEDIA) {
            RecordingVideoTailState(RecordingSelectedGop(0, samples.map { it.copy(inputBytes = 4 * 1024 * 1024) }, null)) {}
        }
    }
    @Test fun cancellationAndDeadlinePoisonFutureEvidence() {
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED)) {
            var blocked = false
            val state = state { if (blocked) throw SaveFailure(code) }
            samples.forEach(state::input); state.inputEndOfStream(); blocked = true
            failure(code) { state.output(samples[0].timeUs, 16, false, false) }
            blocked = false
            failure { state.output(samples[0].timeUs, 16, false, false) }
            assertFalse(state.complete); assertFalse(state.canQueueInput)
        }
    }
    @Test fun noProgressBudgetOnlyExtendsForActualProgress() {
        val progress = RecordingDecodeProgress(100, 50)
        progress.check(149); progress.madeProgress(149); progress.check(198)
        failure(SaveError.DEADLINE_EXCEEDED) { progress.check(199) }
        failure(SaveError.DEADLINE_EXCEEDED) { progress.madeProgress(148) }
    }
}

class RecordingSampleScanTest {
    private fun sample(index: Int, key: Boolean = false, bytes: Int = 100) =
        RecordingTailSample(index * 1_000L, if (key) 1 else 0, bytes, "a".repeat(64))
    private fun timing(items: List<RecordingTailSample>) = RecordingTrackTiming(items.size.toLong(), 1_000_000,
        items.last().timeUs - items.first().timeUs + 1000, 1000)
    private fun scan(items: List<RecordingTailSample>, boundaries: List<RecordingResumeBoundary>? = emptyList(),
        checkpoint: () -> Unit = {}): RecordingSampleScan {
        val timing = timing(items)
        return RecordingSampleScan(true, items.size.toLong(), timing.spanUs / 2, boundaries, checkpoint).also { scan ->
            items.forEach(scan::sample); scan.finish(timing)
        }
    }
    private fun failure(code: SaveError = SaveError.INVALID_MEDIA, action: () -> Unit) {
        assertEquals(code, assertThrows(SaveFailure::class.java, action).code)
    }

    @Test fun firstMiddleFinalAreWholeDeduplicatedGopsWithExactNextSyncIdentity() {
        val items = List(10) { sample(it, it % 2 == 0) }
        val gops = scan(items).selectedGops()
        assertEquals(listOf(0L, 4L, 8L), gops.map { it.startSampleIndex })
        assertEquals(listOf(items.take(2), items.subList(4, 6), items.takeLast(2)), gops.map { it.samples })
        assertEquals(listOf(items[2], items[6], null), gops.map { it.nextSync })
        assertEquals(emptyList<RecordingResumeBoundary>(), scan(items).observedResumeBoundaries())
    }
    @Test fun temporalMiddleUsesSttsTimeRatherThanSampleIndex() {
        val times = listOf(0L, 1000L, 2000L, 3000L, 4000L, 5000L, 9000L, 10_000L)
        val items = times.mapIndexed { index, time -> sample(index, index % 2 == 0).copy(timeUs = time) }
        assertEquals(listOf(0L, 4L, 6L), scan(items).selectedGops().map { it.startSampleIndex })
    }
    @Test fun middleExactlyAtSyncBelongsToNewGopAndOneGopMayCoverEveryPosition() {
        val items = List(9) { sample(it, it % 2 == 0) }
        assertEquals(listOf(0L, 4L, 8L), scan(items).selectedGops().map { it.startSampleIndex })
        val single = List(6) { sample(it, it == 0) }
        assertEquals(listOf(RecordingSelectedGop(0, single, null)), scan(single).selectedGops())
    }
    @Test fun allEffectiveResumeGopsAreIncludedAndRepeatedSelectionRolesAreDeduplicated() {
        val items = List(12) { sample(it, it % 2 == 0) }
        val boundaries = listOf(2, 4, 6, 10).map { RecordingResumeBoundary(it.toLong(), items[it].timeUs) }
        val result = scan(items, boundaries)
        assertEquals(listOf(0L, 2L, 4L, 6L, 10L), result.selectedGops().map { it.startSampleIndex })
        assertEquals(boundaries, result.observedResumeBoundaries())
        assertEquals(5, result.selectedGops().size)
    }
    @Test fun boundaryIndicesAreExactAndPtsAreObservedWithinMuxRoundingTolerance() {
        val items = List(6) { sample(it, it % 2 == 0) }
        for (difference in listOf(-100L, 100L)) {
            val result = scan(items, listOf(RecordingResumeBoundary(2, items[2].timeUs + difference)))
            assertEquals(listOf(RecordingResumeBoundary(2, items[2].timeUs)), result.observedResumeBoundaries())
        }
        for (wrong in listOf(RecordingResumeBoundary(1, items[1].timeUs),
            RecordingResumeBoundary(2, items[2].timeUs + 101), RecordingResumeBoundary(2, items[4].timeUs))) {
            failure { scan(items, listOf(wrong)) }
        }
    }
    @Test fun missingLegacyContractAndDuplicateOrOutOfRangeBoundariesFailClosed() {
        val items = List(6) { sample(it, it % 2 == 0) }
        failure(SaveError.RECOVERY_REQUIRED) { scan(items, null) }
        val a = RecordingResumeBoundary(2, 2000); val b = RecordingResumeBoundary(4, 4000)
        for (wrong in listOf(listOf(a, a), listOf(b, a), listOf(RecordingResumeBoundary(6, 6000))))
            failure { scan(items, wrong) }
        val truncated = RecordingSampleScan(true, 6, 2500, listOf(b))
        items.take(3).forEach(truncated::sample)
        failure { truncated.finish(timing(items)) }
    }
    @Test fun thirtyTwoResumesPermitThirtyFiveDistinctGopsAndThirtyThreeAreRejected() {
        val items = List(74) { sample(it, true) }
        val boundaries = (1..32).map { RecordingResumeBoundary(it.toLong(), items[it].timeUs) }
        val result = scan(items, boundaries)
        assertEquals(35, result.selectedGops().size)
        assertEquals(listOf(0L) + (1L..32L) + listOf(36L, 73L), result.selectedGops().map { it.startSampleIndex })
        assertEquals(boundaries, result.observedResumeBoundaries())
        failure(SaveError.UNSUPPORTED_MEDIA) { scan(items, boundaries + RecordingResumeBoundary(33, 33_000)) }
    }
    @Test fun sixHundredFrameAndEightMiBGopBoundariesAreInclusive() {
        assertEquals(600, scan(List(600) { sample(it, it == 0) }).selectedGops().single().samples.size)
        failure(SaveError.UNSUPPORTED_MEDIA) { scan(List(601) { sample(it, it == 0) }) }
        assertEquals(2, scan(List(2) { sample(it, it == 0, 4 * 1024 * 1024) }).selectedGops().single().samples.size)
        failure(SaveError.UNSUPPORTED_MEDIA) { scan(List(3) { sample(it, it == 0, 4 * 1024 * 1024) }) }
    }
    @Test fun cumulativeSelectedPayloadIsBoundedAndDeduplicated() {
        val items = List(10) { sample(it, it % 2 == 0, 4 * 1024 * 1024) }
        val four = scan(items, listOf(RecordingResumeBoundary(2, 2000)))
        assertEquals(32L * 1024 * 1024, four.selectedGops().sumOf { gop -> gop.samples.sumOf { it.inputBytes.toLong() } })
        failure(SaveError.UNSUPPORTED_MEDIA) {
            scan(items, listOf(RecordingResumeBoundary(2, 2000), RecordingResumeBoundary(6, 6000)))
        }
        val dedup = scan(items, listOf(RecordingResumeBoundary(4, 4000), RecordingResumeBoundary(8, 8000)))
        assertEquals(3, dedup.selectedGops().size)
    }
    @Test fun completeTrackStillCountsEveryNonselectedSampleAndDuration() {
        val items = List(10) { sample(it, it % 2 == 0) }
        val state = RecordingSampleScan(true, 10, 4500)
        items.forEach(state::sample)
        val evidence = state.finish(timing(items))
        assertEquals(10L, evidence.sampleCount); assertEquals(5L, evidence.syncSampleCount)
        assertEquals(0L, evidence.firstPtsUs); assertEquals(9000L, evidence.lastPtsUs)
        assertEquals(1000L, evidence.lastDurationUs); assertTrue(evidence.firstSampleIsKeyFrame)
    }
    @Test fun audioSyncFlagsAreNotVideoKeyframesAndLeadingEditIsNotDuration() {
        val state = RecordingSampleScan(false, 2)
        state.sample(sample(0, true).copy(timeUs = 500_000)); state.sample(sample(1, true).copy(timeUs = 521_333))
        val evidence = state.finish(RecordingTrackTiming(2, 1_000_000, 42_666, 21_333))
        assertEquals(0L, evidence.syncSampleCount); assertFalse(evidence.firstSampleIsKeyFrame)
        assertEquals(21_333L, evidence.lastDurationUs); assertEquals(542_666L, evidence.endPtsUs)
        failure { state.selectedGops() }
    }
    @Test fun missingAddedDuplicateOutOfOrderAndUnsyncedFirstSamplesFail() {
        val truncated = RecordingSampleScan(true, 2, 500)
        truncated.sample(sample(0, true)); failure { truncated.finish(RecordingTrackTiming(2, 1_000_000, 2000, 1000)) }
        val added = RecordingSampleScan(true, 1); added.sample(sample(0, true)); failure { added.sample(sample(1)) }
        for (time in listOf(0L, -1L)) {
            val invalid = RecordingSampleScan(true, 2)
            invalid.sample(sample(0, true)); failure { invalid.sample(sample(1).copy(timeUs = time)) }
        }
        failure { RecordingSampleScan(true, 1).sample(sample(0)) }
    }
    @Test fun wrongDurationMiddleTargetAndUnknownFlagsFailClosed() {
        for (timing in listOf(RecordingTrackTiming(2, 1_000_000, 500, 200), RecordingTrackTiming(3, 1_000_000, 300, 100))) {
            val invalid = RecordingSampleScan(true, 2)
            invalid.sample(sample(0, true)); invalid.sample(sample(1))
            failure { invalid.finish(timing) }
        }
        val wrongMiddle = RecordingSampleScan(true, 2, 501)
        wrongMiddle.sample(sample(0, true)); wrongMiddle.sample(sample(1))
        failure { wrongMiddle.finish(RecordingTrackTiming(2, 1_000_000, 2000, 1000)) }
        failure { RecordingSampleScan(true, 1).sample(sample(0, true).copy(flags = 2)) }
    }
    @Test fun cancellationAndDeadlineDuringScanOrFinishPermanentlyInvalidateEvidence() {
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED)) {
            var blocked = false
            val state = RecordingSampleScan(true, 2, 500, checkpoint = { if (blocked) throw SaveFailure(code) })
            state.sample(sample(0, true)); blocked = true
            failure(code) { state.sample(sample(1)) }
            blocked = false
            failure { state.sample(sample(1)) }; failure { state.selectedGops() }
            failure(code) { scan(List(2) { sample(it, it == 0) }, checkpoint = { throw SaveFailure(code) }) }
        }
    }
}

class RecordingVideoFormatStateTest {
    private val format = RecordingDecodedVideoFormat("video/raw", 1280, 720, 0, 1279, 0, 719, 21)
    @Test fun actualRawFormatAndPaddedCroppedOutputAreAccepted() {
        val state = RecordingVideoFormatState(1280, 720)
        state.accept(format, true); state.accept(format, false)
        RecordingVideoFormatState(1280, 720).accept(format.copy(height = 736), true)
    }
    @Test fun independentGopsRetainOneOutputSignatureButMayAnnounceItAgain() {
        val state = RecordingVideoFormatState(1280, 720)
        state.beginGop(); state.accept(format, true); state.accept(format, false)
        state.beginGop(); state.accept(format, true); state.accept(format, false)
        state.beginGop()
        assertThrows(SaveFailure::class.java) { state.accept(format.copy(color = 19), true) }
    }
    @Test fun changedResolutionFormatAndLateEventAreRejected() {
        for (wrong in listOf(format.copy(mime = "video/avc"), format.copy(cropRight = 1280),
            format.copy(width = 1279), format.copy(cropTop = -1))) {
            assertThrows(SaveFailure::class.java) { RecordingVideoFormatState(1280, 720).accept(wrong, true) }
        }
        val state = RecordingVideoFormatState(1280, 720)
        state.accept(format, true)
        assertThrows(SaveFailure::class.java) { state.accept(format.copy(color = 19), false) }
        val repeated = RecordingVideoFormatState(1280, 720)
        repeated.accept(format, true)
        assertThrows(SaveFailure::class.java) { repeated.accept(format, true) }
    }
}

class RecordingMp4TimingProbeTest {
    private fun atom(type: String, payload: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.io.DataOutputStream(out).apply { writeInt(payload.size + 8); writeBytes(type); write(payload) }
    }.toByteArray()
    private fun integers(vararg values: Int) = java.io.ByteArrayOutputStream().also { out ->
        java.io.DataOutputStream(out).apply { values.forEach(::writeInt) }
    }.toByteArray()
    private fun track(kind: String, scale: Int, runs: List<Pair<Int, Int>>, declared: Int = runs.sumOf { it.first * it.second }): ByteArray {
        val hdlr = atom("hdlr", integers(0, 0) + kind.toByteArray(Charsets.ISO_8859_1))
        val mdhd = atom("mdhd", integers(0, 0, 0, scale, declared))
        val stts = atom("stts", integers(0, runs.size) + runs.fold(byteArrayOf()) { bytes, run -> bytes + integers(run.first, run.second) })
        return atom("trak", atom("mdia", hdlr + mdhd + atom("minf", atom("stbl", stts))))
    }
    private fun probe(bytes: ByteArray, checkpoint: () -> Unit = {}): Map<String, RecordingTrackTiming> {
        val file = java.nio.file.Files.createTempFile("esv-record-timing", ".mp4").toFile()
        return try { file.writeBytes(bytes); java.io.RandomAccessFile(file, "r").use { RecordingMp4TimingProbe.read(it, checkpoint) } }
        finally { file.delete() }
    }
    @Test fun distinctTrackTimescalesAndFinalDurationsAreReadFromStts() {
        val timing = probe(atom("ftyp", byteArrayOf()) + atom("moov",
            track("vide", 90_000, listOf(2 to 3000, 1 to 3001)) + track("soun", 48_000, listOf(5 to 1024))))
        assertEquals(3L, timing.getValue("vide").sampleCount)
        assertEquals(66_666L, timing.getValue("vide").spanUs)
        assertEquals(33_344L, timing.getValue("vide").lastDurationUs)
        assertEquals(21_333L, timing.getValue("soun").lastDurationUs)
    }
    @Test fun duplicateTrackMissingVideoBadRunsAndMetadataMismatchReject() {
        val video = track("vide", 90_000, listOf(3 to 3000))
        for (invalid in listOf(atom("moov", video + video), atom("moov", track("soun", 48_000, listOf(2 to 1024))),
            atom("moov", track("vide", 90_000, listOf(3 to 3000), 8000)),
            atom("moov", track("vide", 90_000, listOf(0 to 3000))),
            atom("moov", track("vide", 90_000, listOf(3 to 0))),
            atom("moov", track("vide", 0, listOf(3 to 3000))))) {
            assertThrows(SaveFailure::class.java) { probe(invalid) }
        }
    }
    @Test fun truncationOversizedBoxAndCancellationFailClosed() {
        val valid = atom("moov", track("vide", 90_000, listOf(3 to 3000)))
        assertThrows(SaveFailure::class.java) { probe(valid.copyOf(valid.size - 1)) }
        assertThrows(SaveFailure::class.java) { probe(integers(Int.MAX_VALUE) + "moov".toByteArray()) }
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) {
            probe(valid) { throw SaveFailure(SaveError.CANCELLED) }
        }.code)
    }
}
