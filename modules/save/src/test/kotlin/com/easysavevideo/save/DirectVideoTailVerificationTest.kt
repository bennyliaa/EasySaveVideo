package com.easysavevideo.save

import org.junit.Assert.*
import org.junit.Test

/** Actual production helper tests; synthetic identities do not claim Android/native acceptance. */
class DirectVideoTailVerificationTest {
    private val hash = "a".repeat(64)
    private data class Fixture(val clock: DirectMovClock, val conservation: DirectRemuxConservation,
        val samples: List<DirectNativeSample>) {
        fun plan() = DirectVideoTailPlan(clock, conservation, samples)
        fun state(checkpoint: () -> Unit = {}) = DirectVideoTailState(plan(), checkpoint)
        fun queued(): DirectVideoTailState = state().also { state ->
            samples.forEach(state::input); state.inputEndOfStream()
        }
    }

    private fun fixture(count: Int = 3, syncIndex: Int = 0, rate: Int = 30,
        inputBytes: Int = 100, canonicalBytes: Int = 96): Fixture {
        val clock = DirectMovClock.video(rate, count.toLong())
        fun identity(index: Int) = DirectSampleIdentity(index.toLong(), clock.presentationTimeUs(index.toLong()),
            canonicalBytes, hash, if (index == 0 || index == syncIndex) 1 else 0)
        val track = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, hash, count.toLong(),
            canonicalBytes.toLong() * count, hash, hash, hash, 0, identity(count - 1), clock.endpoint)
        val contract = DirectRemuxConservation(1280, 720, rate, track, null, null, null, identity(syncIndex))
        return Fixture(clock, contract, (syncIndex until count).map { DirectNativeSample(identity(it), inputBytes) })
    }

    @Test fun realCanonicalAccumulatorFeedsProductionPlanAndActualFinalTimestampProof() {
        val clock = DirectMovClock.video(30, 3)
        val config = DirectMovCanonical.avcConfiguration(byteArrayOf(0x67, 0x42, 0, 0x1e, 0x80.toByte()),
            byteArrayOf(0x68, 0xce.toByte(), 0x06, 0xe2.toByte()))
        val accumulator = DirectMovSampleAccumulator(clock, config.sha256)
        val samples = listOf(0x65, 0x61, 0x61).mapIndexed { index, nal ->
            val bytes = byteArrayOf(0, 0, 0, 2, nal.toByte(), (0xaa + index).toByte())
            DirectNativeSample(accumulator.append(bytes, clock.presentationTimeUs(index.toLong()),
                if (index == 0) 1 else 0), bytes.size)
        }
        val observed = accumulator.finish()
        val conservation = DirectRemuxConservation(1280, 720, 30, observed.track, null, null, null,
            requireNotNull(observed.precedingVideoSync))
        val plan = DirectVideoTailPlan(clock, conservation, samples)
        val state = DirectVideoTailState(plan) {}
        samples.forEach { state.requireNextInput(it); state.input(it) }
        state.inputEndOfStream()
        samples.forEachIndexed { index, sample ->
            assertTrue(state.output(sample.identity.presentationTimeUs + 2, 1024, false, index == samples.lastIndex))
        }
        assertTrue(state.complete)
        val proof = state.finish()
        assertEquals(samples.last().identity.presentationTimeUs + 2, proof.terminalOutputTimeUs)
        assertTrue(proof.matches(conservation))
        assertEquals(samples.first().identity, proof.precedingSync)
        assertEquals(samples.last().identity, proof.lastSample)
    }

    @Test fun finalSyncMeansOneTailSampleEvenInSmallestTwoSampleTrack() {
        for (count in listOf(2, 3, 5)) {
            val f = fixture(count, count - 1)
            val plan = f.plan()
            assertEquals(1, plan.sampleCount)
            val state = f.queued()
            assertTrue(state.output(f.samples.single().identity.presentationTimeUs, 1, false, true))
            assertTrue(state.finish().matches(f.conservation))
        }
    }

    @Test fun precedingSyncInMiddleRequiresOnlyItsExactContiguousTail() {
        val f = fixture(7, 4)
        assertEquals(listOf(4L, 5L, 6L), f.plan().samples.map { it.identity.index })
        val state = f.queued()
        f.samples.forEach { state.output(it.identity.presentationTimeUs, 1, false, false) }
        state.output(Long.MIN_VALUE, 0, false, true)
        assertTrue(state.finish().matches(f.conservation))
        failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.drop(1)) }
        failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.reversed()) }
    }

    @Test fun planOwnsDefensiveImmutableSamplesAndChecksCountBeforeTraversal() {
        val f = fixture()
        val mutable = f.samples.toMutableList()
        val plan = DirectVideoTailPlan(f.clock, f.conservation, mutable)
        mutable.clear()
        assertEquals(f.samples, plan.samples)
        assertThrows(UnsupportedOperationException::class.java) { (plan.samples as MutableList).clear() }
        val oversized = object : AbstractList<DirectNativeSample>() {
            override val size = 108_001
            override fun get(index: Int): DirectNativeSample = error("Count must be checked first")
        }
        failure { DirectVideoTailPlan(f.clock, f.conservation, oversized) }
    }

    @Test fun planRejectsEveryIdentityFieldAndAnyLaterSync() {
        val f = fixture()
        val middle = f.samples[1]
        val bad = listOf(
            middle.copy(identity = middle.identity.copy(index = 0)),
            middle.copy(identity = middle.identity.copy(presentationTimeUs = middle.identity.presentationTimeUs + 1)),
            middle.copy(identity = middle.identity.copy(flags = 1)),
        )
        bad.forEach { sample -> failure { DirectVideoTailPlan(f.clock, f.conservation,
            f.samples.toMutableList().also { it[1] = sample }) } }
        for (position in listOf(0, 2)) {
            val changed = f.samples[position].copy(identity = f.samples[position].identity.copy(sha256 = "b".repeat(64)))
            failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.toMutableList().also { it[position] = changed }) }
        }
        failure { DirectVideoTailPlan(f.clock, f.conservation, emptyList()) }
        failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples + f.samples.last()) }
    }

    @Test fun clockCountRateExactEndpointAndCanonicalTotalAreBound() {
        val f = fixture()
        failure { DirectVideoTailPlan(DirectMovClock.video(24, 3), f.conservation, f.samples) }
        failure { DirectVideoTailPlan(DirectMovClock.video(30, 4), f.conservation, f.samples) }
        failure { DirectVideoTailPlan(DirectMovClock.audio(48_000, 3), f.conservation, f.samples) }
        val shiftedEndpoint = f.conservation.video.copy(endpoint = DirectTrackEndpoint(100_001, 100_001))
        failure { DirectVideoTailPlan(f.clock, f.conservation.copy(video = shiftedEndpoint), f.samples) }
        val changedTotal = f.conservation.video.copy(canonicalBytes = f.conservation.video.canonicalBytes + 1)
        failure { DirectVideoTailPlan(f.clock, f.conservation.copy(video = changedTotal), f.samples) }
        assertThrows(IllegalArgumentException::class.java) { DirectMovClock.video(1, 1_801) }
    }

    private fun withAudio(f: Fixture, count: Long): Fixture {
        val clock = DirectMovClock.audio(48_000, count)
        val last = DirectSampleIdentity(count - 1, clock.presentationTimeUs(count - 1), 10, hash, 1)
        val audio = DirectTrackConservation(DirectSampleKind.AAC_LC, hash, count, count * 10,
            hash, hash, hash, 0, last, clock.endpoint)
        return f.copy(conservation = f.conservation.copy(audio = audio, audioSampleRate = 48_000, audioChannelCount = 2))
    }

    @Test fun identicalVideoHasIdenticalTailWithNoShorterOrLongerAudioAndRetainsWholeConservation() {
        val silent = fixture()
        var expected: DirectVideoTailProof? = null
        for (f in listOf(silent, withAudio(silent, 2), withAudio(silent, 8))) {
            val plan = f.plan()
            assertSame(f.conservation, plan.conservation)
            assertEquals(silent.samples, plan.samples)
            assertEquals(silent.plan().totalInputBytes, plan.totalInputBytes)
            val state = f.queued()
            f.samples.forEach { state.output(it.identity.presentationTimeUs, 1, false, false) }
            state.output(-1, 0, false, true)
            val proof = state.finish()
            if (expected == null) expected = proof else assertEquals(expected, proof)
            assertTrue(proof.matches(f.conservation))
        }
    }

    @Test fun audioCannotRescueInvalidVideoManifestOrCausalDecode() {
        for (f in listOf(withAudio(fixture(), 2), withAudio(fixture(), 8))) {
            failure { DirectVideoTailPlan(DirectMovClock.video(24, 3), f.conservation, f.samples) }
            failure { DirectVideoTailPlan(DirectMovClock.video(30, 4), f.conservation, f.samples) }
            failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.reversed()) }
            failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.dropLast(1)) }
            for (index in f.samples.indices) {
                val sample = f.samples[index]
                for (identity in listOf(sample.identity.copy(presentationTimeUs = sample.identity.presentationTimeUs + 1),
                    sample.identity.copy(flags = 1 - sample.identity.flags))) {
                    failure { DirectVideoTailPlan(f.clock, f.conservation, f.samples.toMutableList().also {
                        it[index] = sample.copy(identity = identity)
                    }) }
                }
            }
            failure { f.state().output(0, 1, false, false) }
            failure { f.queued().output(0, 0, false, true) }
            failure { f.queued().finish() }
            failure { DirectVideoTailPlan(f.clock, f.conservation.copy(video = f.conservation.video.copy(
                endpoint = DirectTrackEndpoint(100_001, 100_001))), f.samples) }
        }
        failure(SaveError.VERIFICATION_UNAVAILABLE) {
            withAudio(fixture(17, inputBytes = DirectNormalizationLimits.MAX_SAMPLE_BYTES, canonicalBytes = 1), 2).plan()
        }
    }

    @Test fun maximumSampleAndTailBytesAreExactForNativeAndCanonicalLengths() {
        val max = DirectNormalizationLimits.MAX_SAMPLE_BYTES
        val identity = fixture().samples.first().identity
        DirectNativeSample(identity, max)
        for (size in listOf(-1, 0, max + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DirectNativeSample(identity, size) }
        }
        val exactNative = fixture(16, inputBytes = max, canonicalBytes = 1).plan()
        val exactCanonical = fixture(16, inputBytes = 1, canonicalBytes = max).plan()
        assertEquals(DirectVideoTailPlan.MAX_TAIL_BYTES, exactNative.totalInputBytes)
        assertEquals(DirectVideoTailPlan.MAX_TAIL_BYTES, exactCanonical.totalCanonicalBytes)
        failure(SaveError.VERIFICATION_UNAVAILABLE) { fixture(17, inputBytes = max, canonicalBytes = 1).plan() }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { fixture(17, inputBytes = 1, canonicalBytes = max).plan() }
    }

    @Test fun builderRejectsOverBudgetBeforeRetainingAndCannotRecover() {
        val f = fixture(17, inputBytes = DirectNormalizationLimits.MAX_SAMPLE_BYTES, canonicalBytes = 1)
        val builder = DirectVideoTailPlan.Builder(f.clock, f.conservation)
        f.samples.take(16).forEach(builder::append)
        failure(SaveError.VERIFICATION_UNAVAILABLE) { builder.append(f.samples.last()) }
        assertEquals(16, builder.sampleCount)
        assertEquals(DirectVideoTailPlan.MAX_TAIL_BYTES, builder.totalInputBytes)
        failure(SaveError.VERIFICATION_UNAVAILABLE) { builder.finish() }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { builder.append(f.samples.last().copy(inputBytes = 1)) }
    }

    @Test fun max108000ManifestIsAcceptedAndEarlierLargeGopsAreNotRetainedOrBudgeted() {
        val max = fixture(108_000, rate = 60, inputBytes = 1, canonicalBytes = 1).plan()
        assertEquals(108_000, max.sampleCount)
        assertEquals(1_800_000_000, max.clock.endpoint.minimumUs)
        val f = fixture(100, 99, canonicalBytes = DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        assertTrue(f.conservation.video.canonicalBytes > DirectVideoTailPlan.MAX_TAIL_BYTES)
        assertEquals(1, f.plan().sampleCount)
    }

    @Test fun builderCompletesOnlyExactManifestAndIsImmutableAfterFinish() {
        val f = fixture()
        val builder = DirectVideoTailPlan.Builder(f.clock, f.conservation)
        f.samples.forEach(builder::append)
        val plan = builder.finish()
        assertEquals(f.samples, plan.samples)
        failure { builder.append(f.samples.last()) }
        failure { builder.finish() }
        failure { DirectVideoTailPlan.Builder(f.clock, f.conservation).finish() }
    }

    @Test fun builderCancellationIsStickyAndPrecedesRetention() {
        val f = fixture()
        var cancel = false
        val stop = SaveFailure(SaveError.CANCELLED)
        val builder = DirectVideoTailPlan.Builder(f.clock, f.conservation) { if (cancel) throw stop }
        builder.append(f.samples.first())
        cancel = true
        assertSame(stop, assertThrows(SaveFailure::class.java) { builder.append(f.samples[1]) })
        cancel = false
        assertEquals(1, builder.sampleCount)
        assertSame(stop, assertThrows(SaveFailure::class.java) { builder.finish() })
    }

    @Test fun rereadInputMustMatchIndexTimeSyncCanonicalHashAndActualInputLength() {
        val f = fixture()
        val first = f.samples.first()
        val altered = listOf(first.copy(inputBytes = first.inputBytes - 1),
            first.copy(identity = first.identity.copy(index = 1)),
            first.copy(identity = first.identity.copy(presentationTimeUs = 1)),
            first.copy(identity = first.identity.copy(flags = 0)),
            first.copy(identity = first.identity.copy(canonicalBytes = first.identity.canonicalBytes - 1)),
            first.copy(identity = first.identity.copy(sha256 = "b".repeat(64))))
        for (sample in altered) {
            val state = f.state()
            failure { state.requireNextInput(sample) }
            assertEquals(0, state.queuedSamples)
            failure { state.input(first) }
            failure { state.finish() }
        }
    }

    @Test fun prequeueValidationCannotPretendInputWasSuccessfullyQueued() {
        val f = fixture()
        val state = f.state()
        state.requireNextInput(f.samples.first())
        assertEquals(0, state.queuedSamples)
        failure { state.output(0, 1, false, false) }
        assertFalse(state.complete)
    }

    @Test fun everyOutputRequiresItsOwnCorrespondingQueuedInput() {
        val f = fixture()
        val state = f.state()
        state.input(f.samples.first())
        assertTrue(state.output(0, 1, false, false))
        failure { state.output(f.samples[1].identity.presentationTimeUs, 1, false, false) }
        val valid = f.state()
        f.samples.forEach { valid.input(it); valid.output(it.identity.presentationTimeUs, 1, false, false) }
        valid.inputEndOfStream(); valid.output(-1, 0, false, true)
        assertTrue(valid.finish().matches(f.conservation))
    }

    @Test fun missingDuplicateReorderedExtraInputAndEarlyInputEosReject() {
        val f = fixture()
        failure { f.state().inputEndOfStream() }
        val missing = f.state()
        f.samples.dropLast(1).forEach(missing::input)
        failure { missing.inputEndOfStream() }
        val duplicate = f.state()
        duplicate.input(f.samples.first())
        failure { duplicate.input(f.samples.first()) }
        failure { f.state().input(f.samples[1]) }
        val extra = f.state()
        f.samples.forEach(extra::input)
        failure { extra.input(f.samples.last()) }
        failure { f.queued().inputEndOfStream() }
        failure { f.queued().input(f.samples.last()) }
    }

    @Test fun perFrameTwoMicrosecondsAllowedWithoutAccumulationAndActualFinalTimeRetained() {
        for (rate in DirectMovClock.VIDEO_FRAME_RATES) for (delta in listOf(-2L, 2L)) {
            val f = fixture(5, 2, rate)
            val state = f.queued()
            f.samples.forEach { state.output(it.identity.presentationTimeUs + delta, 1, false, false) }
            state.output(0, 0, false, true)
            val proof = state.finish()
            assertEquals(f.samples.last().identity.presentationTimeUs + delta, proof.terminalOutputTimeUs)
            assertTrue(proof.matches(f.conservation))
        }
        val f = fixture()
        val accumulated = f.queued()
        accumulated.output(0, 1, false, false)
        accumulated.output(f.samples[1].identity.presentationTimeUs + 2, 1, false, false)
        failure { accumulated.output(f.samples[2].identity.presentationTimeUs + 4, 1, false, true) }
    }

    @Test fun threeMicrosecondDriftAndOutOfRangeTimesCannotProduceEvidence() {
        val f = fixture()
        for (delta in listOf(-3L, 3L)) for (badIndex in 1..2) {
            val state = f.queued()
            f.samples.take(badIndex).forEach { state.output(it.identity.presentationTimeUs, 1, false, false) }
            failure { state.output(f.samples[badIndex].identity.presentationTimeUs + delta, 1, false, false) }
            failure { state.finish() }
        }
        for (pts in listOf(-1L, -2L, Long.MIN_VALUE, Long.MAX_VALUE, DirectNormalizationLimits.MAX_DURATION_US + 1))
            failure { f.queued().output(pts, 1, false, false) }
    }

    @Test fun droppedDuplicateReorderedExtraAndNonmonotonicOutputsFail() {
        val f = fixture()
        val dropped = f.queued()
        dropped.output(0, 1, false, false)
        failure { dropped.output(f.samples.last().identity.presentationTimeUs, 1, false, true) }
        for (pts in listOf(0L, 1L, -1L)) {
            val duplicate = f.queued()
            duplicate.output(0, 1, false, false)
            failure { duplicate.output(pts, 1, false, false) }
        }
        failure { f.queued().output(f.samples[1].identity.presentationTimeUs, 1, false, false) }
        val extra = f.queued()
        f.samples.forEach { extra.output(it.identity.presentationTimeUs, 1, false, false) }
        failure { extra.output(f.clock.endpoint.minimumUs, 1, false, true) }
    }

    @Test fun emptyAndConfigBuffersNeverCountAsFramesOrProgress() {
        val f = fixture()
        val state = f.queued()
        repeat(100) {
            assertFalse(state.output(Long.MIN_VALUE, 0, false, false))
            assertFalse(state.output(Long.MAX_VALUE, 10, true, false))
        }
        assertEquals(0, state.decodedSamples)
        assertFalse(state.complete)
        failure { state.output(f.samples.last().identity.presentationTimeUs, 0, false, true) }
        failure { f.queued().output(0, -1, false, false) }
        for (bytes in listOf(0, 1)) failure { f.queued().output(0, bytes, true, true) }
    }

    @Test fun attachedAndSeparateEmptyEosProduceSameActualTerminalEvidence() {
        val f = fixture()
        val attached = f.queued()
        f.samples.forEachIndexed { i, sample -> attached.output(sample.identity.presentationTimeUs + 1,
            1, false, i == f.samples.lastIndex) }
        val attachedProof = attached.finish()
        for (emptyPts in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            val separate = f.queued()
            f.samples.forEach { separate.output(it.identity.presentationTimeUs + 1, 1, false, false) }
            assertFalse(separate.complete)
            assertTrue(separate.output(emptyPts, 0, false, true))
            assertEquals(attachedProof, separate.finish())
        }
    }

    @Test fun inputEosIsNotCompletionAndOutputEosRequiresAllInputsAndAllOutputs() {
        val f = fixture()
        val onlyInput = f.queued()
        assertFalse(onlyInput.complete)
        failure { onlyInput.finish() }
        val noInputEos = f.state()
        f.samples.forEach(noInputEos::input)
        f.samples.dropLast(1).forEach { noInputEos.output(it.identity.presentationTimeUs, 1, false, false) }
        failure { noInputEos.output(f.samples.last().identity.presentationTimeUs, 1, false, true) }
        for (decoded in 0 until f.samples.size) {
            val early = f.queued()
            f.samples.take(decoded).forEach { early.output(it.identity.presentationTimeUs, 1, false, false) }
            failure { early.output(0, 0, false, true) }
        }
    }

    @Test fun postCompletionCallsAndDoubleFinishInvalidateTheState() {
        val f = fixture()
        fun completed() = f.queued().also { state ->
            f.samples.forEachIndexed { i, sample -> state.output(sample.identity.presentationTimeUs, 1,
                false, i == f.samples.lastIndex) }
        }
        val actions: List<(DirectVideoTailState) -> Unit> = listOf(
            { it.input(f.samples.first()) }, { it.requireNextInput(f.samples.first()) },
            { it.inputEndOfStream() }, { it.output(0, 0, false, true) },
            { it.output(0, 0, false, false) })
        for (action in actions) {
            val state = completed()
            assertTrue(state.complete)
            failure { action(state) }
            assertFalse(state.complete)
            failure { state.finish() }
        }
        val finished = completed()
        finished.finish()
        failure { finished.finish() }
        assertFalse(finished.complete)
    }

    @Test fun cancellationOnAnyTransitionOrFinishIsStickyAndPreservesOriginalStopReason() {
        val f = fixture()
        val actions: List<(DirectVideoTailState) -> Unit> = listOf(
            { it.requireNextInput(f.samples.first()) }, { it.input(f.samples.first()) },
            { it.inputEndOfStream() }, { it.output(0, 0, false, false) }, { it.finish() })
        for (action in actions) {
            var cancelled = true
            val stop = SaveFailure(SaveError.CANCELLED)
            val state = f.state { if (cancelled) throw stop }
            assertSame(stop, assertThrows(SaveFailure::class.java) { action(state) })
            cancelled = false
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.input(f.samples.first()) })
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.finish() })
            assertFalse(state.complete)
        }
        var stop = false
        val state = f.state { if (stop) throw SaveFailure(SaveError.DEADLINE_EXCEEDED) }
        f.samples.forEach(state::input); state.inputEndOfStream()
        f.samples.forEachIndexed { i, sample -> state.output(sample.identity.presentationTimeUs, 1,
            false, i == f.samples.lastIndex) }
        stop = true
        failure(SaveError.DEADLINE_EXCEEDED) { state.finish() }
        assertFalse(state.complete)
        stop = false
        failure(SaveError.DEADLINE_EXCEEDED) { state.finish() }
    }

    private fun failure(code: SaveError = SaveError.INVALID_MEDIA, block: () -> Unit) {
        val failure = assertThrows(SaveFailure::class.java, block)
        assertEquals(code, failure.code)
    }
}

class DirectDecodeProgressTest {
    private fun deadline(block: () -> Unit) {
        assertEquals(SaveError.DEADLINE_EXCEEDED, assertThrows(SaveFailure::class.java, block).code)
    }

    @Test fun onlyRealProgressRenewsTheFiveSecondBoundAndNeverRevivesExpiredWork() {
        val progress = DirectDecodeProgress(100, 50)
        progress.check(149); progress.madeProgress(149); progress.check(198)
        deadline { progress.check(199) }
        deadline { progress.madeProgress(199) }
        deadline { DirectDecodeProgress(100, 50).madeProgress(150) }
        val default = DirectDecodeProgress(0)
        default.check(4_999_999_999)
        deadline { default.check(5_000_000_000) }
        assertThrows(IllegalArgumentException::class.java) { DirectDecodeProgress(0, 5_000_000_001) }
    }

    @Test fun emptyConfigAndFormatEventFloodCannotExtendTheAllowance() {
        val progress = DirectDecodeProgress(0, 100)
        for (time in 0L until 100) progress.check(time)
        deadline { progress.check(100) }
    }

    @Test fun originalAbsoluteDeadlineAndCancellationRemainAuthoritativeDespiteProgress() {
        var now = 0L
        val progress = DirectDecodeProgress(0, 100) { if (now >= 50) throw SaveFailure(SaveError.DEADLINE_EXCEEDED) }
        for (time in 1L..49) { now = time; progress.madeProgress(now) }
        now = 50
        deadline { progress.madeProgress(now) }
        now = 1
        deadline { progress.check(now) }
        var cancel = true
        val stop = SaveFailure(SaveError.CANCELLED)
        val cancelled = DirectDecodeProgress(0) { if (cancel) throw stop }
        assertSame(stop, assertThrows(SaveFailure::class.java) { cancelled.check(1) })
        cancel = false
        assertSame(stop, assertThrows(SaveFailure::class.java) { cancelled.madeProgress(2) })
    }

    @Test fun backwardsClockRejectsButShortSignedNanoTimeWrapIsValid() {
        val backwards = DirectDecodeProgress(100, 50)
        backwards.check(120)
        deadline { backwards.check(119) }
        val wrapped = DirectDecodeProgress(Long.MAX_VALUE - 10, 50)
        wrapped.check(Long.MIN_VALUE + 10)
        wrapped.madeProgress(Long.MIN_VALUE + 20)
        wrapped.check(Long.MIN_VALUE + 69)
        deadline { wrapped.check(Long.MIN_VALUE + 70) }
    }
}

class DirectVideoFormatStateTest {
    private val format = DirectDecodedVideoFormat("video/raw", 1280, 720, 0, 1279, 0, 719,
        DirectDecodedVideoRepresentation.YUV420_SEMIPLANAR)
    private fun unavailable(block: () -> Unit) {
        assertEquals(SaveError.VERIFICATION_UNAVAILABLE, assertThrows(SaveFailure::class.java, block).code)
    }

    @Test fun initialStableByteBufferFormatAndPaddedCropHaveExactDisplayGeometry() {
        for (representation in DirectDecodedVideoRepresentation.entries) {
            val state = DirectVideoFormatState(1280, 720)
            val padded = format.copy(height = 736, representation = representation)
            state.accept(padded, true); state.accept(padded, false); state.accept(padded, false)
        }
    }

    @Test fun contradictoryCropMimeAndUnboundedCodedDimensionsReject() {
        for (actual in listOf(format.copy(mime = "video/avc"), format.copy(width = 0),
            format.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE), format.copy(width = 8193),
            format.copy(width = 8192, height = 8192), format.copy(cropLeft = -1),
            format.copy(cropRight = 1280), format.copy(cropBottom = 718), format.copy(cropTop = 1),
            format.copy(cropLeft = Int.MIN_VALUE, cropRight = Int.MAX_VALUE))) {
            unavailable { DirectVideoFormatState(1280, 720).accept(actual, true) }
        }
    }

    @Test fun lateChangedAndRepeatedFormatsFailStickyEvenIfOriginalReturns() {
        for (change in listOf(format.copy(height = 736),
            format.copy(representation = DirectDecodedVideoRepresentation.YUV420_PLANAR))) {
            val state = DirectVideoFormatState(1280, 720)
            state.accept(format, true)
            unavailable { state.accept(change, false) }
            unavailable { state.accept(format, false) }
        }
        val repeated = DirectVideoFormatState(1280, 720)
        repeated.accept(format, true)
        unavailable { repeated.accept(format, true) }
        val late = DirectVideoFormatState(1280, 720)
        late.accept(format, false)
        unavailable { late.accept(format, true) }
    }

    @Test fun formatCancellationCannotBeUndoneByLaterValidEvents() {
        var cancel = true
        val stop = SaveFailure(SaveError.CANCELLED)
        val state = DirectVideoFormatState(1280, 720) { if (cancel) throw stop }
        assertSame(stop, assertThrows(SaveFailure::class.java) { state.accept(format, true) })
        cancel = false
        assertSame(stop, assertThrows(SaveFailure::class.java) { state.accept(format, false) })
    }
}
