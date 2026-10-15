package com.easysavevideo.save

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Fake input/PCM observations exercise the production helper; no test asserts decoder acceptance. */
class DirectAudioPcmAccountingTest {
    private val hash = "a".repeat(64)

    private data class Fixture(
        val clock: DirectMovClock,
        val audio: DirectTrackConservation,
        val channels: Int,
        val compressedBytes: Int,
    ) {
        fun sample(index: Long) = DirectNativeSample(
            DirectSampleIdentity(index, clock.presentationTimeUs(index), compressedBytes,
                audio.lastSample.sha256, 1), compressedBytes,
        )
        fun state(checkpoint: () -> Unit = {}) = DirectAudioPcmAccounting(audio, clock, channels, checkpoint)
        fun queued(inputEos: Boolean = true, checkpoint: () -> Unit = {}) = state(checkpoint).also { state ->
            queueAll(state)
            if (inputEos) state.inputEndOfStream()
        }
        fun queueAll(state: DirectAudioPcmAccounting) {
            for (index in 0 until clock.sampleCount) {
                val sample = sample(index)
                state.requireNextInput(sample)
                state.inputQueued(sample)
            }
        }
        val frames: Long get() = clock.sampleCount * 1_024
        val stride: Int get() = channels * 2
    }

    private fun fixture(count: Long = 2, rate: Int = 48_000, channels: Int = 2,
        compressedBytes: Int = 7): Fixture {
        val clock = DirectMovClock.audio(rate, count)
        val last = DirectSampleIdentity(count - 1, clock.presentationTimeUs(count - 1), compressedBytes, hash, 1)
        val audio = DirectTrackConservation(DirectSampleKind.AAC_LC, hash, count,
            count * compressedBytes, hash, hash, hash, 0, last, clock.endpoint)
        return Fixture(clock, audio, channels, compressedBytes)
    }

    private fun pcm(state: DirectAudioPcmAccounting, f: Fixture, frames: Int,
        startFrame: Long = 0, deltaUs: Long = 0, eos: Boolean = false,
        buffer: ByteBuffer? = ByteBuffer.allocate(frames * f.stride), offset: Int = 0): Boolean =
        state.output(buffer, offset, frames * f.stride,
            DirectMovClock.floorUs(startFrame, f.clock.timescale) + deltaUs,
            "audio/raw", f.clock.timescale, f.channels, true, false, eos)

    private fun empty(state: DirectAudioPcmAccounting, eos: Boolean = false,
        pts: Long = Long.MIN_VALUE, offset: Int = 0): Boolean =
        state.output(null, offset, 0, pts, "unused", -1, -1, false, false, eos)

    private fun config(state: DirectAudioPcmAccounting, size: Int = 4): Boolean =
        state.output(ByteBuffer.allocate(size), 0, size, Long.MAX_VALUE,
            "unused", -1, -1, false, true, false)

    private fun failure(code: SaveError = SaveError.INVALID_MEDIA, block: () -> Unit): SaveFailure =
        assertThrows(SaveFailure::class.java, block).also { assertEquals(code, it.code) }

    private fun unavailable(block: () -> Unit) = failure(SaveError.VERIFICATION_UNAVAILABLE, block)

    private fun assertResult(f: Fixture, result: DirectAudioPcmAccounting.Result, buffers: Long,
        firstTime: Long = 0, lastTime: Long = 0) {
        assertEquals(f.clock.sampleCount, result.queuedAccessUnits)
        assertEquals(buffers, result.outputBufferCount)
        assertEquals(f.frames, result.pcmFrames)
        assertEquals(f.frames * f.stride, result.pcmBytes)
        assertEquals(firstTime, result.firstOutputTimeUs)
        assertEquals(lastTime, result.lastOutputTimeUs)
        assertEquals(f.audio.endpoint, result.endpoint)
        assertTrue(result.outputEos)
    }

    @Test fun sixRateChannelCombinationsAndTwoOrThreeAusAcceptExactFakeObservations() {
        for (rate in DirectMovClock.AUDIO_SAMPLE_RATES) for (channels in 1..2) for (count in 2L..3L) {
            val f = fixture(count, rate, channels)
            val state = f.queued()
            for (index in 0 until count) assertTrue(pcm(state, f, 1_024, index * 1_024,
                eos = index == count - 1))
            assertResult(f, state.finish(), count, lastTime = f.clock.presentationTimeUs(count - 1))
        }
    }

    @Test fun wrongTrackKindClockKindRateAndChannelCountReject() {
        val f = fixture()
        failure { DirectAudioPcmAccounting(f.audio.copy(kind = DirectSampleKind.AVC_BASELINE), f.clock, 2) }
        failure { DirectAudioPcmAccounting(f.audio, DirectMovClock.video(30, 2), 2) }
        failure { DirectAudioPcmAccounting(f.audio, DirectMovClock.audio(32_000, 2), 2) }
        for (channels in listOf(Int.MIN_VALUE, -1, 0, 3, Int.MAX_VALUE))
            failure { DirectAudioPcmAccounting(f.audio, f.clock, channels) }
        for (rate in listOf(0, 8_000, 44_101, 96_000, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { DirectMovClock.audio(rate, 2) }
    }

    @Test fun clockCountFinalPtsAndEndpointMustMatchExactly() {
        val f = fixture()
        failure { DirectAudioPcmAccounting(f.audio, DirectMovClock.audio(48_000, 3), 2) }
        val changedLast = f.audio.copy(lastSample = f.audio.lastSample.copy(
            presentationTimeUs = f.audio.lastSample.presentationTimeUs + 1))
        failure { DirectAudioPcmAccounting(changedLast, f.clock, 2) }
        val changedEndpoint = f.audio.copy(endpoint = DirectTrackEndpoint(
            f.audio.endpoint.minimumUs + 1, f.audio.endpoint.maximumUs + 1))
        failure { DirectAudioPcmAccounting(changedEndpoint, f.clock, 2) }
        // These malformed expectations are rejected even before reaching the helper.
        assertThrows(IllegalArgumentException::class.java) { f.audio.copy(firstPresentationTimeUs = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            f.audio.copy(lastSample = f.audio.lastSample.copy(index = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.audio.copy(lastSample = f.audio.lastSample.copy(flags = 0))
        }
    }

    @Test fun rateSpecificDurationCapsApplyBelowTheBroaderNinetyThousandEnvelope() {
        val caps = mapOf(32_000 to 56_250L, 44_100 to 77_519L, 48_000 to 84_375L)
        for ((rate, cap) in caps) for (channels in 1..2) {
            val f = fixture(cap, rate, channels, compressedBytes = 1)
            f.state() // Arithmetic only: no full-track list, PCM allocation or iteration.
            assertTrue(cap + 1 < DirectNormalizationLimits.MAX_AUDIO_SAMPLES)
            assertThrows(IllegalArgumentException::class.java) { DirectMovClock.audio(rate, cap + 1) }
            val mismatchedCount = f.audio.copy(sampleCount = cap + 1, canonicalBytes = cap + 1,
                lastSample = f.audio.lastSample.copy(index = cap))
            failure { DirectAudioPcmAccounting(mismatchedCount, f.clock, channels) }
        }
    }

    @Test fun everyInputRequiresExactIndexPtsSyncAndUnchangedRawByteLength() {
        val f = fixture(3)
        val first = f.sample(0)
        val changed = listOf(
            first.copy(identity = first.identity.copy(index = 1)),
            first.copy(identity = first.identity.copy(presentationTimeUs = 1)),
            first.copy(identity = first.identity.copy(flags = 0)),
            first.copy(identity = first.identity.copy(canonicalBytes = 6)),
            first.copy(inputBytes = 6),
        )
        for (sample in changed) for (prequeue in listOf(true, false)) {
            val state = f.state()
            val original = failure {
                if (prequeue) state.requireNextInput(sample) else state.inputQueued(sample)
            }
            assertSame(original, failure { state.inputQueued(first) })
            assertSame(original, failure { state.finish() })
        }
        for (bytes in listOf(-1, 0, DirectNormalizationLimits.MAX_SAMPLE_BYTES + 1, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { first.copy(inputBytes = bytes) }
    }

    @Test fun finalIdentityHashAndSizeMustMatchTheSuppliedConservation() {
        val f = fixture()
        val last = f.sample(1)
        val changed = listOf(
            last.copy(identity = last.identity.copy(sha256 = "b".repeat(64))),
            last.copy(identity = last.identity.copy(canonicalBytes = 6), inputBytes = 6),
        )
        for (sample in changed) {
            val state = f.state()
            state.inputQueued(f.sample(0))
            failure { state.requireNextInput(sample) }
            failure { state.inputQueued(last) }
            failure { state.finish() }
        }
    }

    @Test fun middleIdentityConservationRemainsAnExplicitCallerPrecondition() {
        val f = fixture(3)
        val state = f.state()
        state.inputQueued(f.sample(0))
        // This fake middle hash is not compared with a second manifest or rehashed by the helper.
        // Passing here deliberately does not claim that the compressed payload digest was proved.
        state.inputQueued(f.sample(1).let { it.copy(identity = it.identity.copy(sha256 = "b".repeat(64))) })
        state.inputQueued(f.sample(2))
        state.inputEndOfStream()
        pcm(state, f, 3_072, eos = true)
        assertResult(f, state.finish(), 1)
    }

    @Test fun duplicateSkippedExtraAndPostEosInputsFailSticky() {
        val f = fixture(3)
        val duplicate = f.state()
        duplicate.inputQueued(f.sample(0))
        failure { duplicate.inputQueued(f.sample(0)) }
        failure { duplicate.inputQueued(f.sample(1)) }
        val skipped = f.state()
        failure { skipped.inputQueued(f.sample(1)) }
        failure { skipped.inputQueued(f.sample(0)) }
        val extra = f.queued(inputEos = false)
        failure { extra.inputQueued(f.sample(2)) }
        for (prequeue in listOf(true, false)) {
            val ended = f.queued()
            failure { if (prequeue) ended.requireNextInput(f.sample(0)) else ended.inputQueued(f.sample(0)) }
            failure { ended.finish() }
        }
    }

    @Test fun compressedByteTotalsAreCheckedAtQueueAndExactlyAtInputEos() {
        val f = fixture()
        val oversized = f.state()
        val huge = f.sample(0).let { it.copy(identity = it.identity.copy(canonicalBytes = 15), inputBytes = 15) }
        failure { oversized.inputQueued(huge) }
        val short = f.state()
        val first = f.sample(0).let { it.copy(identity = it.identity.copy(canonicalBytes = 6), inputBytes = 6) }
        short.inputQueued(first); short.inputQueued(f.sample(1))
        failure { short.inputEndOfStream() }
        failure { short.finish() }
        val changedTotal = f.audio.copy(canonicalBytes = f.audio.canonicalBytes + 1)
        val mismatch = DirectAudioPcmAccounting(changedTotal, f.clock, f.channels)
        f.queueAll(mismatch)
        failure { mismatch.inputEndOfStream() }
    }

    @Test fun compressedByteCapUsesLongArithmeticAndCannotBeExceeded() {
        val max = DirectNormalizationLimits.MAX_SAMPLE_BYTES
        val f = fixture(513, compressedBytes = 1)
        // Keep the expected final identity small; 512 prior maximal samples already exhaust 2 GiB.
        val bounded = f.audio.copy(canonicalBytes = DirectNormalizationLimits.MAX_BYTES)
        val state = DirectAudioPcmAccounting(bounded, f.clock, f.channels)
        for (index in 0L until 512) state.inputQueued(f.sample(index).let {
            it.copy(identity = it.identity.copy(canonicalBytes = max), inputBytes = max)
        })
        failure { state.inputQueued(f.sample(512)) }
        failure { state.inputEndOfStream() }
    }

    @Test fun earlyMissingAndDuplicateInputEosCannotSucceed() {
        val f = fixture()
        failure { f.state().inputEndOfStream() }
        val partial = f.state()
        partial.inputQueued(f.sample(0))
        failure { partial.inputEndOfStream() }
        val repeated = f.queued()
        failure { repeated.inputEndOfStream() }
        failure { repeated.finish() }
    }

    @Test fun repeatedPrequeueValidationNeverAdvancesTheSequenceOrGrantsCredit() {
        val f = fixture()
        val state = f.state()
        repeat(3) { state.requireNextInput(f.sample(0)) }
        state.inputQueued(f.sample(0))
        state.requireNextInput(f.sample(1)); state.inputQueued(f.sample(1))
        state.inputEndOfStream(); pcm(state, f, 2_048, eos = true)
        assertEquals(2, state.finish().queuedAccessUnits)
        val noCredit = f.state()
        noCredit.requireNextInput(f.sample(0))
        val original = unavailable { pcm(noCredit, f, 1) }
        assertSame(original, unavailable { noCredit.inputQueued(f.sample(0)) })
        assertSame(original, unavailable { noCredit.finish() })
    }

    @Test fun simulatedFailedNativeQueueCannotGrantCreditOrProduceAResult() {
        val f = fixture()
        val state = f.state()
        state.requireNextInput(f.sample(0))
        val nativeQueueSucceeded = false
        if (nativeQueueSucceeded) state.inputQueued(f.sample(0))
        // The real adapter must abort here. Fake output proves prequeue validation gave no credit.
        val original = unavailable { pcm(state, f, 1) }
        assertSame(original, unavailable { state.inputQueued(f.sample(0)) })
        assertSame(original, unavailable { state.finish() })
    }

    @Test fun heapDirectReadOnlyDuplicateAndSlicedBuffersPreserveCallerCursorAndMark() {
        val f = fixture()
        val size = (f.frames * f.stride).toInt()
        val buffers = listOf(
            ByteBuffer.allocate(size + 32),
            ByteBuffer.allocateDirect(size + 32),
            ByteBuffer.allocate(size + 32).asReadOnlyBuffer(),
            ByteBuffer.allocate(size + 32).duplicate(),
            ByteBuffer.allocate(size + 48).apply { position(16) }.slice(),
        )
        for (buffer in buffers) {
            buffer.limit(size + 24); buffer.position(20); buffer.mark()
            val state = f.queued()
            // Absolute offset precedes position; a relative-to-position interpretation would fail.
            assertTrue(pcm(state, f, 2_048, eos = true, buffer = buffer, offset = 8))
            assertEquals(20, buffer.position()); assertEquals(size + 24, buffer.limit())
            buffer.reset(); assertEquals(20, buffer.position())
            assertResult(f, state.finish(), 1)
        }
    }

    @Test fun silenceAndNonzeroPcmBothCountWithMonoAndStereoStride() {
        for (channels in 1..2) for (fill in listOf(0.toByte(), 0x7f.toByte())) {
            val f = fixture(channels = channels)
            val state = f.queued()
            val bytes = ByteArray((f.frames * f.stride).toInt()) { fill }
            pcm(state, f, 2_048, eos = true, buffer = ByteBuffer.wrap(bytes))
            assertResult(f, state.finish(), 1)
        }
    }

    @Test fun nullOrNoAccessibleMediaExtentRejectsWithoutResult() {
        val f = fixture()
        for (buffer in listOf(null, ByteBuffer.allocate(0), ByteBuffer.allocate(8).apply { limit(0) })) {
            val state = f.queued()
            unavailable { pcm(state, f, 1, buffer = buffer) }
            unavailable { state.finish() }
        }
    }

    @Test fun invalidNegativeOverflowingAndCrossLimitExtentsPreserveCallerCursor() {
        val f = fixture()
        val buffer = ByteBuffer.allocate(32).apply { limit(16); position(7); mark() }
        val bad = listOf(-1 to 4, 0 to -1, Int.MIN_VALUE to 4, Int.MAX_VALUE to 4,
            Int.MAX_VALUE to Int.MAX_VALUE, 13 to 4, 32 to 4,
            0 to DirectNormalizationLimits.MAX_SAMPLE_BYTES + 1)
        for ((offset, size) in bad) {
            val state = f.queued()
            unavailable { state.output(buffer, offset, size, 0, "audio/raw", 48_000, 2, true, false, false) }
            assertEquals(7, buffer.position()); assertEquals(16, buffer.limit())
            buffer.reset(); assertEquals(7, buffer.position())
            unavailable { state.finish() }
        }
    }

    @Test fun sizeMustBeWholePcmFramesInEitherChannelLayout() {
        for (channels in 1..2) for (size in 1 until channels * 2) {
            val f = fixture(channels = channels)
            val state = f.queued()
            unavailable { state.output(ByteBuffer.allocate(size), 0, size, 0,
                "audio/raw", 48_000, channels, true, false, false) }
            unavailable { state.finish() }
        }
    }

    @Test fun everyMediaBufferRequiresActualRawPcm16RateAndChannelFormat() {
        val f = fixture()
        data class Format(val mime: String = "audio/raw", val rate: Int = 48_000,
            val channels: Int = 2, val signed16: Boolean = true)
        val bad = listOf(Format(mime = "audio/mp4a-latm"), Format(mime = ""),
            Format(rate = 44_100), Format(rate = 0), Format(channels = 1), Format(channels = 0),
            Format(signed16 = false)) // False covers float/unsupported/unreadable native mapping.
        for (format in bad) for (late in listOf(false, true)) {
            val state = f.queued()
            if (late) pcm(state, f, 1_024)
            val pts = if (late) f.clock.presentationTimeUs(1) else 0
            unavailable { state.output(ByteBuffer.allocate(4_096), 0, 4_096, pts,
                format.mime, format.rate, format.channels, format.signed16, false, false) }
            unavailable { pcm(state, f, 1_024, if (late) 1_024 else 0, eos = true) }
            unavailable { state.finish() }
        }
        // Missing-key -> PCM16 is a future native-adapter mapping test, not manufactured here.
    }

    @Test fun arbitrarySplitCoalescedAndUnequalBlocksUseAggregateFramesNotBufferCount() {
        val f = fixture()
        val partitions = listOf(listOf(1_024, 1_024), listOf(512, 1_536), listOf(2_048),
            listOf(1, 3, 17, 491, 1_111, 425))
        for (partition in partitions) {
            assertEquals(2_048, partition.sum())
            val state = f.queued()
            var frames = 0L
            for ((index, count) in partition.withIndex()) {
                assertTrue(pcm(state, f, count, frames, eos = index == partition.lastIndex))
                frames += count
            }
            val lastStart = f.frames - partition.last()
            assertResult(f, state.finish(), partition.size.toLong(),
                lastTime = DirectMovClock.floorUs(lastStart, f.clock.timescale))
        }
        // These groupings do not identify an AU's individual decoded frame contribution.
    }

    @Test fun fortyFourPointOneKhzAcrossThe441AuPhaseUsesAbsoluteCumulativeFrames() {
        val f = fixture(443, 44_100, 1)
        val state = f.queued()
        val pattern = intArrayOf(1, 17, 511, 1_537, 73, 997)
        var frames = 0L
        var bufferCount = 0L
        var accumulatedRoundedDeltas = 0L
        var diverged = false
        var lastStart = 0L
        while (frames < f.frames) {
            val count = minOf(pattern[(bufferCount % pattern.size).toInt()].toLong(), f.frames - frames).toInt()
            val absolute = DirectMovClock.floorUs(frames, 44_100)
            if (absolute - accumulatedRoundedDeltas > DirectNormalizationLimits.MAX_ROUNDING_US) diverged = true
            lastStart = absolute
            assertTrue(pcm(state, f, count, frames))
            accumulatedRoundedDeltas += DirectMovClock.floorUs(count.toLong(), 44_100)
            frames += count
            bufferCount++
        }
        assertTrue(diverged)
        assertTrue(frames > 441L * 1_024)
        assertTrue(empty(state, eos = true))
        assertResult(f, state.finish(), bufferCount, lastTime = lastStart)
    }

    @Test fun repeatedRoundedDeltaClockFailsRatherThanAccumulatingAnAllowance() {
        val f = fixture(3, 44_100)
        val state = f.queued()
        // A 17-frame block is 385.487... us. Repeated floor deltas drift beyond 2 us.
        var frames = 0L
        var accumulated = 0L
        var rejected = false
        repeat(20) {
            if (!rejected) {
                val exact = DirectMovClock.floorUs(frames, 44_100)
                if (exact - accumulated > 2) {
                    unavailable { state.output(ByteBuffer.allocate(68), 0, 68, accumulated,
                        "audio/raw", 44_100, 2, true, false, false) }
                    rejected = true
                } else {
                    assertTrue(state.output(ByteBuffer.allocate(68), 0, 68, accumulated,
                        "audio/raw", 44_100, 2, true, false, false))
                    frames += 17
                    accumulated += DirectMovClock.floorUs(17, 44_100)
                }
            }
        }
        assertTrue(rejected)
        unavailable { state.finish() }
    }

    @Test fun zeroOriginAndIndependentPlusOrMinusTwoMicrosecondsAreAllowed() {
        for (rate in DirectMovClock.AUDIO_SAMPLE_RATES) for (first in listOf(0L, 1L, 2L))
            for (second in listOf(-2L, 0L, 2L)) {
                val f = fixture(rate = rate)
                val state = f.queued()
                pcm(state, f, 1_024, deltaUs = first)
                pcm(state, f, 1_024, 1_024, second, eos = true)
                assertResult(f, state.finish(), 2, firstTime = first,
                    lastTime = f.clock.presentationTimeUs(1) + second)
            }
    }

    @Test fun threeMicrosecondsNegativeOriginAndExtremePtsRejectWithoutOverflow() {
        val f = fixture()
        for (pts in listOf(Long.MIN_VALUE, -3L, -1L, 3L, Long.MAX_VALUE)) {
            val state = f.queued()
            unavailable { state.output(ByteBuffer.allocate(4), 0, 4, pts,
                "audio/raw", 48_000, 2, true, false, false) }
            unavailable { state.finish() }
        }
        for (delta in listOf(-3L, 3L)) {
            val state = f.queued()
            pcm(state, f, 1_024)
            unavailable { pcm(state, f, 1_024, 1_024, delta, eos = true) }
            unavailable { state.finish() }
        }
    }

    @Test fun missingDuplicateBackdatedJumpedAndSlowlyDriftingPtsReject() {
        val f = fixture(4)
        for (secondPts in listOf(0L, 1L, -1L, f.clock.presentationTimeUs(2), f.clock.endpoint.minimumUs)) {
            val state = f.queued()
            pcm(state, f, 1_024)
            unavailable { state.output(ByteBuffer.allocate(4_096), 0, 4_096, secondPts,
                "audio/raw", 48_000, 2, true, false, false) }
        }
        val missing = f.queued()
        unavailable { pcm(missing, f, 1_024, 1_024) }
        val drift = f.queued()
        pcm(drift, f, 1_024, deltaUs = 1)
        pcm(drift, f, 1_024, 1_024, deltaUs = 2)
        unavailable { pcm(drift, f, 1_024, 2_048, deltaUs = 3) }
    }

    @Test fun oneFrameShortOrExtraCannotReachOutputEos() {
        val f = fixture()
        val short = f.queued()
        pcm(short, f, 2_047)
        unavailable { empty(short, eos = true) }
        unavailable { short.finish() }
        val extra = f.queued()
        unavailable { pcm(extra, f, 2_049, eos = true) }
        unavailable { extra.finish() }
        val appended = f.queued()
        pcm(appended, f, 2_048)
        unavailable { pcm(appended, f, 1, 2_048) }
    }

    @Test fun mediaMayCompleteBeforeInputEosThenSeparateOutputEosCompletes() {
        val f = fixture()
        val state = f.queued(inputEos = false)
        assertTrue(pcm(state, f, 2_048))
        state.inputEndOfStream()
        assertTrue(empty(state, eos = true, pts = Long.MAX_VALUE))
        assertResult(f, state.finish(), 1)
    }

    @Test fun outputEosAndFinishRequirePriorInputEosEvenAfterAllPcmArrived() {
        val f = fixture()
        val outputEos = f.queued(inputEos = false)
        unavailable { pcm(outputEos, f, 2_048, eos = true) }
        unavailable { outputEos.inputEndOfStream() }
        unavailable { outputEos.finish() }
        val finish = f.queued(inputEos = false)
        pcm(finish, f, 2_048)
        unavailable { finish.finish() }
        unavailable { finish.inputEndOfStream() }
    }

    @Test fun oneQueuedAuGrantsExactly1024CumulativeFramesAndNothingMore() {
        val f = fixture()
        val valid = f.state()
        valid.inputQueued(f.sample(0))
        pcm(valid, f, 512)
        pcm(valid, f, 512, 512)
        valid.inputQueued(f.sample(1)); valid.inputEndOfStream()
        pcm(valid, f, 1_024, 1_024, eos = true)
        assertResult(f, valid.finish(), 3, lastTime = f.clock.presentationTimeUs(1))
        for (frames in listOf(1_025, 2_048)) {
            val state = f.state()
            state.inputQueued(f.sample(0))
            val original = unavailable { pcm(state, f, frames) }
            assertSame(original, unavailable { state.inputQueued(f.sample(1)) })
            assertSame(original, unavailable { state.inputEndOfStream() })
            assertSame(original, unavailable { state.finish() })
        }
        val cumulative = f.state()
        cumulative.inputQueued(f.sample(0))
        pcm(cumulative, f, 1_024)
        unavailable { pcm(cumulative, f, 1, 1_024) }
    }

    @Test fun delayedOutputAndSplit512ThenQueueThen1536AreLegal() {
        val f = fixture()
        val split = f.state()
        split.inputQueued(f.sample(0))
        pcm(split, f, 512)
        split.inputQueued(f.sample(1)); split.inputEndOfStream()
        pcm(split, f, 1_536, 512, eos = true)
        assertResult(f, split.finish(), 2, lastTime = DirectMovClock.floorUs(512, 48_000))
        val delayed = f.state()
        delayed.inputQueued(f.sample(0)); delayed.inputQueued(f.sample(1)); delayed.inputEndOfStream()
        assertFalse(empty(delayed)); assertFalse(config(delayed))
        pcm(delayed, f, 2_048, eos = true)
        assertResult(f, delayed.finish(), 1)
    }

    @Test fun successfulQueueCreditIsRecordedBeforeSynchronousOutputConsumption() {
        val f = fixture()
        val state = f.state()
        for (index in 0L..1L) {
            val sample = f.sample(index)
            state.requireNextInput(sample)
            val nativeQueueSucceeded = true // Fake adapter event, not an actual native queue.
            if (nativeQueueSucceeded) {
                state.inputQueued(sample)
                assertTrue(pcm(state, f, 1_024, index * 1_024))
            }
        }
        state.inputEndOfStream(); empty(state, eos = true)
        assertResult(f, state.finish(), 2, lastTime = f.clock.presentationTimeUs(1))
    }

    @Test fun coalescedOutputRequiresTwoSuccessfulQueuesNotTwoPrequeueChecks() {
        val f = fixture()
        val invalid = f.state()
        invalid.inputQueued(f.sample(0)); invalid.requireNextInput(f.sample(1))
        unavailable { pcm(invalid, f, 2_048) }
        unavailable { invalid.inputQueued(f.sample(1)) }
        val valid = f.queued()
        pcm(valid, f, 2_048, eos = true)
        assertResult(f, valid.finish(), 1)
    }

    @Test fun noInputNoRealPcmAndEmptyOrConfigFloodsCannotCreateEvidence() {
        val f = fixture()
        unavailable { pcm(f.state(), f, 1) }
        val state = f.queued()
        repeat(32) { assertFalse(empty(state)); assertFalse(config(state)) }
        unavailable { empty(state, eos = true) }
        unavailable { state.finish() }
        unavailable { f.state().finish() }
        unavailable { f.queued().finish() }
    }

    @Test fun configOnlyIsBoundedAndDoesNotTraverseBytesOrUsePcmFormat() {
        val f = fixture()
        var calls = 0
        val state = f.state { calls++ }
        val before = calls
        val buffer = ByteBuffer.allocate(32 * 1_024).asReadOnlyBuffer().apply { position(7) }
        assertFalse(state.output(buffer, 0, buffer.limit(), Long.MAX_VALUE,
            "not-pcm", -1, -1, false, true, false))
        assertEquals(2, calls - before) // Entry and commit only, no PCM-read checkpoints.
        assertEquals(7, buffer.position())
        for (size in listOf(0, 4)) {
            unavailable { f.queued().output(ByteBuffer.allocate(size), 0, size, 0,
                "audio/raw", 48_000, 2, true, true, true) }
        }
        unavailable { f.queued().output(null, 0, 4, 0, "unused", 0, 0, false, true, false) }
        unavailable { f.queued().output(ByteBuffer.allocate(8).apply { limit(4) }, 0, 8, 0,
            "unused", 0, 0, false, true, false) }
        unavailable { f.queued().output(null, 0, DirectNormalizationLimits.MAX_SAMPLE_BYTES + 1, 0,
            "unused", 0, 0, false, true, false) }
    }

    @Test fun attachedAndSeparateEmptyEosReturnTheSameMeasuredEndpoint() {
        val f = fixture(rate = 44_100)
        val attached = f.queued()
        pcm(attached, f, 2_048, deltaUs = 2, eos = true)
        val result = attached.finish()
        for (pts in listOf(Long.MIN_VALUE, -1L, 0L, 999L, Long.MAX_VALUE)) {
            val separate = f.queued()
            pcm(separate, f, 2_048, deltaUs = 2)
            assertTrue(empty(separate, eos = true, pts = pts, offset = Int.MAX_VALUE))
            assertEquals(result, separate.finish())
        }
        assertEquals(f.clock.endpoint, result.endpoint)
        assertEquals(2, result.lastOutputTimeUs) // Last buffer start is not the duration.
    }

    @Test fun emptyOutputStillRejectsNegativeOffsetOrSizeAndKnownInvalidExtent() {
        val f = fixture()
        unavailable { empty(f.queued(), eos = true, offset = -1) }
        unavailable { f.queued().output(null, 0, -1, 0, "unused", 0, 0, false, false, false) }
        unavailable { f.queued().output(ByteBuffer.allocate(1), 2, 0, 0,
            "unused", 0, 0, false, false, false) }
    }

    @Test fun earlyAndDuplicateOutputEosOrMissingOutputEosCannotFinish() {
        val f = fixture()
        for (count in listOf(0, 1_024)) {
            val state = f.queued()
            if (count > 0) pcm(state, f, count)
            unavailable { empty(state, eos = true) }
            unavailable { state.finish() }
        }
        val missing = f.queued()
        pcm(missing, f, 2_048)
        unavailable { missing.finish() }
        val duplicate = f.queued()
        pcm(duplicate, f, 2_048, eos = true)
        failure { empty(duplicate, eos = true) }
        failure { duplicate.finish() }
    }

    @Test fun everyPostEosEventAndSecondFinishInvalidatesFurtherSuccess() {
        val f = fixture()
        val events: List<(DirectAudioPcmAccounting) -> Unit> = listOf(
            { it.requireNextInput(f.sample(0)) }, { it.inputQueued(f.sample(0)) },
            { it.inputEndOfStream() }, { pcm(it, f, 1) }, { empty(it) }, { config(it) },
        )
        for (event in events) {
            val state = f.queued()
            pcm(state, f, 2_048, eos = true)
            val original = failure { event(state) }
            assertSame(original, failure { state.finish() })
        }
        val finished = f.queued()
        pcm(finished, f, 2_048, eos = true)
        finished.finish()
        val original = failure { finished.finish() }
        assertSame(original, failure { finished.finish() })
    }

    @Test fun checkpointsRunBeforeAndAfterConstructorValidation() {
        val f = fixture()
        for (stopAt in 1..2) {
            var calls = 0
            val stop = SaveFailure(SaveError.CANCELLED)
            assertSame(stop, assertThrows(SaveFailure::class.java) {
                f.state { if (++calls == stopAt) throw stop }
            })
            assertEquals(stopAt, calls)
        }
    }

    @Test fun cancellationBeforeEveryEventPreservesTheOriginalFailure() {
        val f = fixture()
        val events: List<(DirectAudioPcmAccounting) -> Unit> = listOf(
            { it.requireNextInput(f.sample(0)) }, { it.inputQueued(f.sample(0)) },
            { it.inputEndOfStream() }, { empty(it) }, { config(it) }, { it.finish() },
        )
        for (event in events) {
            var cancel = false
            val stop = SaveFailure(SaveError.CANCELLED)
            val state = f.state { if (cancel) throw stop }
            cancel = true
            assertSame(stop, assertThrows(SaveFailure::class.java) { event(state) })
            cancel = false
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.inputQueued(f.sample(0)) })
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.finish() })
        }
    }

    @Test fun preAcceptanceCheckpointsAbortInputAndEosTransitions() {
        val f = fixture()
        for (action in 0..2) {
            var armed = false
            var calls = 0
            val stop = SaveFailure(SaveError.DEADLINE_EXCEEDED)
            val state = f.state { if (armed && ++calls == 2) throw stop }
            if (action == 2) f.queueAll(state)
            armed = true
            assertSame(stop, assertThrows(SaveFailure::class.java) {
                when (action) {
                    0 -> state.requireNextInput(f.sample(0))
                    1 -> state.inputQueued(f.sample(0))
                    else -> state.inputEndOfStream()
                }
            })
            armed = false
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.finish() })
        }
    }

    @Test fun midBufferCancellationCannotReturnPartiallyCountedSuccessAndLeavesCursorUntouched() {
        val f = fixture(8)
        var armed = false
        var readCalls = 0
        val stop = SaveFailure(SaveError.CANCELLED)
        val state = f.queued(checkpoint = { if (armed && ++readCalls == 3) throw stop })
        val buffer = ByteBuffer.allocate(32_784).apply { position(9); limit(32_780); mark() }
        armed = true
        // Entry, first chunk, then cancellation before the second chunk of the 32 KiB extent.
        assertSame(stop, assertThrows(SaveFailure::class.java) {
            pcm(state, f, 8_192, eos = true, buffer = buffer, offset = 4)
        })
        assertEquals(3, readCalls)
        assertEquals(9, buffer.position()); assertEquals(32_780, buffer.limit())
        buffer.reset(); assertEquals(9, buffer.position())
        armed = false
        assertSame(stop, assertThrows(SaveFailure::class.java) { pcm(state, f, 8_192, eos = true) })
        assertSame(stop, assertThrows(SaveFailure::class.java) { state.finish() })
    }

    @Test fun finalOutputCommitCheckpointAndFinishCheckpointAreSticky() {
        val f = fixture()
        for (phase in listOf("output", "finish-entry", "finish-commit")) {
            var armed = false
            var calls = 0
            val stopAt = when (phase) { "output" -> 3; "finish-entry" -> 1; else -> 2 }
            val stop = SaveFailure(SaveError.DEADLINE_EXCEEDED)
            val state = f.queued(checkpoint = { if (armed && ++calls == stopAt) throw stop })
            if (phase != "output") pcm(state, f, 2_048, eos = true)
            armed = true
            assertSame(stop, assertThrows(SaveFailure::class.java) {
                if (phase == "output") pcm(state, f, 2_048, eos = true) else state.finish()
            })
            armed = false
            assertSame(stop, assertThrows(SaveFailure::class.java) { state.finish() })
        }
    }

    @Test fun arbitraryCheckpointExceptionsArePropagatedUnchangedRatherThanTranslated() {
        val f = fixture()
        for (stop in listOf(SaveFailure(SaveError.RETRY_EXHAUSTED), IllegalStateException("test-stop"))) {
            var armed = false
            val state = f.state { if (armed) throw stop }
            armed = true
            assertSame(stop, assertThrows(RuntimeException::class.java) { state.inputQueued(f.sample(0)) })
            armed = false
            assertSame(stop, assertThrows(RuntimeException::class.java) { state.finish() })
        }
    }

    @Test fun existingProgressGuardUsesTrueOnlyAndConfigEmptyFormatDequeueDoNotExtendIt() {
        val f = fixture()
        val state = f.queued()
        val progress = DirectDecodeProgress(0)
        assertTrue(pcm(state, f, 1_024))
        progress.madeProgress(1_000_000_000)
        for (time in 2L..5L) {
            assertFalse(config(state)); progress.check(time * 1_000_000_000)
            assertFalse(empty(state)); progress.check(time * 1_000_000_000)
            progress.check(time * 1_000_000_000) // Fake format/dequeue events have no helper media event.
        }
        failure(SaveError.DEADLINE_EXCEEDED) { progress.check(6_000_000_000) }
        failure(SaveError.DEADLINE_EXCEEDED) { progress.madeProgress(6_000_000_001) }
    }

    @Test fun realPcmAndOutputEosCanRenewProgressWithinTheOriginalDeadline() {
        val f = fixture()
        var now = 0L
        val absoluteDeadline = 8_000_000_000L
        val progress = DirectDecodeProgress(0) {
            if (now >= absoluteDeadline) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
        }
        val state = f.queued(checkpoint = { progress.check(now) })
        now = 4_000_000_000
        if (pcm(state, f, 2_048)) progress.madeProgress(now)
        now = 7_000_000_000
        if (empty(state, eos = true)) progress.madeProgress(now)
        assertResult(f, state.finish(), 1)
        now = absoluteDeadline
        failure(SaveError.DEADLINE_EXCEEDED) { progress.madeProgress(now) }
    }

    @Test fun fourMiBOutputBoundaryIsReadableWithBoundedChecksAndNoWholeTrackAllocation() {
        val f = fixture(1_024)
        var armed = false
        var checkpoints = 0
        val state = f.queued(checkpoint = { if (armed) checkpoints++ })
        val size = DirectNormalizationLimits.MAX_SAMPLE_BYTES
        val buffer = ByteBuffer.allocate(size).asReadOnlyBuffer().apply { position(13) }
        armed = true
        assertTrue(pcm(state, f, size / f.stride, eos = true, buffer = buffer))
        assertEquals(2 + size / (8 * 1_024), checkpoints)
        assertEquals(13, buffer.position()); assertEquals(size, buffer.limit())
        assertResult(f, state.finish(), 1)
        val oversized = f.queued()
        unavailable { oversized.output(null, 0, size + f.stride, 0,
            "audio/raw", 48_000, 2, true, false, false) }
        unavailable { oversized.finish() }
    }

    @Test fun checkedPcmTotalsUseExistingLongArithmeticWithoutMaximalPcmAllocation() {
        val count = 84_375L
        val frames = DirectMovClock.checkedMultiply(count, 1_024)
        val bytes = DirectMovClock.checkedMultiply(frames, 4)
        assertEquals(86_400_000L, frames)
        assertEquals(345_600_000L, bytes)
        fixture(count, channels = 2, compressedBytes = 1).state()
        assertThrows(IllegalArgumentException::class.java) { DirectMovClock.checkedMultiply(Long.MAX_VALUE, 2) }
        assertThrows(IllegalArgumentException::class.java) { DirectMovClock.checkedAdd(Long.MAX_VALUE, 1) }
    }
}
