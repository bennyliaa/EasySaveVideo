package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RecordingContractsTest {
    private val profile = RecordingProfile()
    private val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
    private val audio = RecordingTrackEvidence(47, 0, 981_333, 21_333)
    private fun descriptor(choice: RecordingAudioChoice = RecordingAudioChoice.NONE) = CaptureDescriptor("capture", 1, choice)
    private fun expectation(choice: RecordingAudioChoice = RecordingAudioChoice.NONE,
        outcome: RecordingAudioOutcome = if (choice == RecordingAudioChoice.NONE)
            RecordingAudioOutcome.NO_AUDIO_REQUESTED else RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED,
        audioTrack: RecordingTrackEvidence? = if (choice == RecordingAudioChoice.NONE) null else audio) =
        RecordingArtifactExpectation(descriptor(choice), profile, outcome, RecordingStopReason.USER_STOP,
            video, audioTrack, maxOf(video.endPtsUs, audioTrack?.endPtsUs ?: 0), 100_000)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid contract") } catch (_: IllegalArgumentException) { }
    }
    private fun failure(error: RecordingError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (failure: RecordingFailure) { assertEquals(error, failure.code) }
    }

    @Test fun m01_formatOrderingStartsExactlyOnceForBothOrders() {
        for (order in listOf(listOf(RecordingTrack.VIDEO, RecordingTrack.AUDIO),
            listOf(RecordingTrack.AUDIO, RecordingTrack.VIDEO))) {
            val gate = RecordingFormatGate(RecordingAudioChoice.INTERNAL, 0)
            assertFalse(gate.formatReady(order[0], 1))
            assertFalse(gate.started)
            assertTrue(gate.formatReady(order[1], 2))
            assertTrue(gate.started)
            failure(RecordingError.FORMAT_CHANGED) { gate.formatReady(order[1], 3) }
        }
    }

    @Test fun m01_videoOnlyNeedsExactlyOneFormat() {
        val gate = RecordingFormatGate(RecordingAudioChoice.NONE, 0)
        assertTrue(gate.formatReady(RecordingTrack.VIDEO, 1))
        failure(RecordingError.FORMAT_CHANGED) { gate.formatReady(RecordingTrack.AUDIO, 2) }
    }

    @Test fun m01_missingFormatIsBoundedEvenWithBufferedVideo() {
        val limits = RecordingLimits()
        val gate = RecordingFormatGate(RecordingAudioChoice.INTERNAL, 100, limits)
        gate.formatReady(RecordingTrack.VIDEO, 101)
        failure(RecordingError.FORMAT_TIMEOUT) { gate.checkDeadline(100 + limits.formatTimeoutUs) }
        assertFalse(gate.started)
        assertTrue(gate.failed)
    }

    @Test fun m02_zeroAndOversizedBuffersRejectedBeforeAllocation() {
        val budget = RecordingBufferBudget()
        failure(RecordingError.SAMPLE_LIMIT) { budget.reserve(0) }
        failure(RecordingError.SAMPLE_LIMIT) { budget.reserve(RecordingLimits().maxSampleBytes + 1) }
        assertEquals(0L, budget.pendingBytes())
        assertEquals(0, budget.pendingSamples())
    }

    @Test fun m02_duplicateOrBackwardsTrackEvidenceIsInvalid() {
        invalid { RecordingTrackEvidence(2, 1, 0, 1) }
        invalid { RecordingTrackEvidence(2, 1, 1, 1) }
        invalid { RecordingTrackEvidence(1, 1, 2, 1) }
        invalid { RecordingTrackEvidence(1, 1, 1, 0) }
        invalid { RecordingTrackEvidence(1, Long.MAX_VALUE, Long.MAX_VALUE, 1) }
        invalid { RecordingTrackEvidence(1, 0, 0, 1, 0, true) }
    }

    @Test fun m03_emptySingleFrameAndUltrashortCapturesCannotBeDurableExpectations() {
        invalid { RecordingTrackEvidence(0, 0, 0, 1) }
        invalid { expectation().copy(video = RecordingTrackEvidence(1, 0, 0, 1_000_000, 1, true)) }
        invalid {
            expectation().copy(video = RecordingTrackEvidence(2, 0, 100, 100, 1, true), effectiveDurationUs = 200)
        }
        invalid { RecordingLimits(minCaptureDurationUs = 1) }
    }

    @Test fun m03_firstAcceptedVideoMustBeKeyframeAtZero() {
        invalid { expectation().copy(video = video.copy(firstSampleIsKeyFrame = false)) }
        invalid {
            expectation().copy(video = video.copy(firstPtsUs = 1, lastPtsUs = 966_668), effectiveDurationUs = 1_000_001)
        }
    }

    @Test fun m04_unfinalizedOrUnreleasedProductCannotConstructExpectation() {
        invalid { expectation().copy(finalized = false) }
        invalid { expectation().copy(resourcesReleased = false) }
        invalid { expectation().copy(bytesWritten = 0) }
    }

    @Test fun m05_bufferCountAndBytesAreBoundedAndReleaseIsIdempotent() {
        val limits = RecordingLimits(maxSampleBytes = 4, maxPendingBytes = 6, maxPendingSamples = 2)
        val budget = RecordingBufferBudget(limits)
        val first = budget.reserve(4)
        failure(RecordingError.BUFFER_LIMIT) { budget.reserve(3) }
        val second = budget.reserve(2)
        assertEquals(6L, budget.pendingBytes())
        failure(RecordingError.BUFFER_LIMIT) { budget.reserve(1) }
        assertTrue(budget.release(first))
        assertFalse(budget.release(first))
        assertEquals(2L, budget.pendingBytes())
        val third = budget.reserve(1)
        assertEquals(2, budget.pendingSamples())
        assertTrue(budget.release(second))
        assertTrue(budget.release(third))
        assertEquals(0L, budget.pendingBytes())
    }

    @Test fun m05_foreignReservationsCannotReleaseAnotherBudget() {
        val first = RecordingBufferBudget()
        val second = RecordingBufferBudget()
        val a = first.reserve(10)
        val b = second.reserve(20)
        assertFalse(first.release(b))
        assertEquals(10L, first.pendingBytes())
        assertTrue(first.release(a))
        assertEquals(20L, second.pendingBytes())
    }

    @Test fun m05_encodedAndContainerBudgetsRemainSeparate() {
        val budget = RecordingByteBudget(RecordingLimits(maxFileBytes = 10, maxSampleBytes = 8))
        budget.admitSample(8)
        budget.admitSample(2)
        assertEquals(10L, budget.encodedBytes)
        failure(RecordingError.BYTE_LIMIT) { budget.admitSample(1) }
        budget.observeArtifactBytes(10)
        failure(RecordingError.BYTE_LIMIT) { budget.observeArtifactBytes(11) }
        assertEquals(10L, budget.observedArtifactBytes)
        failure(RecordingError.SAMPLE_INVALID) { budget.observeArtifactBytes(9) }
    }

    @Test fun m05_storageThresholdIsExplicitAndInclusive() {
        val budget = RecordingByteBudget(RecordingLimits(minFreeBytes = 100))
        budget.checkStorage(100)
        failure(RecordingError.STORAGE_LOW) { budget.checkStorage(99) }
        failure(RecordingError.STORAGE_LOW) { budget.checkStorage(-1) }
    }

    @Test fun m06_invalidProfileDimensionsAlignmentAndRatesRejected() {
        invalid { profile.copy(width = 0) }
        invalid { profile.copy(width = 1279) }
        invalid { profile.copy(width = 4096, height = 4096) }
        invalid { profile.copy(frameRate = 0) }
        invalid { profile.copy(frameRate = 61) }
        invalid { profile.copy(videoBitrate = 1) }
        invalid { profile.copy(audioSampleRate = 96_000) }
        invalid { profile.copy(audioChannels = 6) }
        assertEquals(720, profile.copy(width = 720, height = 1280).width)
    }

    @Test fun a01_noAudioChoiceForbidsClaimedAudioOrUnexpectedTrack() {
        val output = expectation()
        assertEquals(0, output.audioTrackCount)
        invalid { output.copy(audio = audio, effectiveDurationUs = audio.endPtsUs) }
        invalid { output.copy(audioOutcome = RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED) }
    }

    @Test fun a03_silentReadableAudioIsDifferentFromAbsentAudio() {
        val output = expectation(RecordingAudioChoice.INTERNAL, RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN)
        assertEquals(1, output.audioTrackCount)
        assertNotNull(output.audio)
        assertEquals(RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN, output.audioOutcome)
    }

    @Test fun a04_interruptedAudioStillRequiresActualAudioEvidence() {
        val output = expectation(RecordingAudioChoice.INTERNAL, RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED)
            .copy(stopReason = RecordingStopReason.AUDIO_LOST)
        assertNotNull(output.audio)
        invalid { output.copy(audio = null, effectiveDurationUs = video.endPtsUs) }
    }

    @Test fun a05_internalChoiceCannotHideMissingAudioByChangingOutcome() {
        invalid { expectation(RecordingAudioChoice.INTERNAL, audioTrack = null) }
        invalid { expectation(RecordingAudioChoice.INTERNAL, RecordingAudioOutcome.NO_AUDIO_REQUESTED) }
        invalid {
            expectation(RecordingAudioChoice.INTERNAL).copy(audio = audio.copy(syncSampleCount = 1, firstSampleIsKeyFrame = true))
        }
    }

    @Test fun outputDurationMustBeActualMaximumSampleEnd() {
        invalid { expectation().copy(effectiveDurationUs = 1_000_001) }
        invalid { expectation(RecordingAudioChoice.INTERNAL).copy(effectiveDurationUs = video.endPtsUs) }
        assertEquals(audio.endPtsUs, expectation(RecordingAudioChoice.INTERNAL).effectiveDurationUs)
    }

    @Test fun cancellationAndProcessInterruptionCannotProducePublishableProduct() {
        for (reason in listOf(RecordingStopReason.USER_CANCELLED, RecordingStopReason.CONSENT_DENIED,
            RecordingStopReason.AUDIO_PERMISSION_DENIED, RecordingStopReason.ENTITLEMENT_REVOKED,
            RecordingStopReason.PROCESS_INTERRUPTED)) invalid { expectation().copy(stopReason = reason) }
        assertEquals(RecordingStopReason.SYSTEM_STOPPED, expectation().copy(stopReason = RecordingStopReason.SYSTEM_STOPPED).stopReason)
    }

    @Test fun opaqueIdentifiersRejectPathsURLsAndInvalidGeneration() {
        for (id in listOf("", "../capture", "https://example.test", "capture/1", "a".repeat(129))) {
            invalid { RecordingOutputHandle(id) }
            invalid { CaptureDescriptor(id, 1, RecordingAudioChoice.NONE) }
        }
        invalid { CaptureDescriptor("capture", 0, RecordingAudioChoice.NONE) }
        invalid { RecordingSessionId("capture", -1) }
    }

    @Test fun resumeBoundariesAreStrictOrderedBoundedSampleIdentities() {
        val one = RecordingResumeBoundary(10, 400_000)
        val expected = expectation().copy(video = video.copy(syncSampleCount = 2), resumeBoundaries = listOf(one))
        assertEquals(listOf(one), expected.resumeBoundaries)
        invalid { RecordingResumeBoundary(0, 1) }
        invalid { RecordingResumeBoundary(1, 0) }
        invalid { expected.copy(resumeBoundaries = listOf(one.copy(sampleIndex = 30))) }
        invalid { expected.copy(resumeBoundaries = listOf(one.copy(ptsUs = video.lastPtsUs + 1))) }
        invalid { expected.copy(video = video) }
        invalid { expected.copy(video = video.copy(syncSampleCount = 3), resumeBoundaries = listOf(one, one)) }
        invalid { expected.copy(video = video.copy(syncSampleCount = 3),
            resumeBoundaries = listOf(one, RecordingResumeBoundary(11, 399_999))) }
        invalid { expected.copy(video = video.copy(syncSampleCount = 3),
            resumeBoundaries = listOf(one, RecordingResumeBoundary(9, 400_001))) }
        val fullVideo = RecordingTrackEvidence(34, 0, 990_000, 10_000, 34, true)
        val boundaries = (1..32).map { RecordingResumeBoundary(it.toLong(), it * 30_000L) }
        assertEquals(32, expectation().copy(video = fullVideo, resumeBoundaries = boundaries).resumeBoundaries!!.size)
        invalid { expectation().copy(video = fullVideo, resumeBoundaries = boundaries + RecordingResumeBoundary(33, 990_000)) }
        assertEquals(32, RecordingLimits().maxResumeBoundaries)
        invalid { RecordingLimits(maxResumeBoundaries = 0) }
        invalid { RecordingLimits(maxResumeBoundaries = 33) }
    }

    @Test fun callerMutationCannotChangeDurableResumeContractOrCopy() {
        val source = mutableListOf(RecordingResumeBoundary(10, 400_000))
        val expected = expectation().copy(video = video.copy(syncSampleCount = 2), resumeBoundaries = source)
        val copy = expected.copy()
        source.clear()
        assertEquals(1, expected.resumeBoundaries!!.size)
        assertEquals(expected, copy)
        assertThrows(Exception::class.java) { (expected.resumeBoundaries as MutableList<RecordingResumeBoundary>).clear() }
        assertEquals(1, copy.resumeBoundaries!!.size)
        assertNull(expected.copy(resumeBoundaries = null).resumeBoundaries)
        assertTrue(expectation().resumeBoundaries!!.isEmpty())
        val growing = object : AbstractList<RecordingResumeBoundary>() {
            private var reads = 0
            override val size: Int get() = if (reads++ == 0) 1 else 33
            override fun get(index: Int) = RecordingResumeBoundary(10, 400_000)
        }
        invalid { expected.copy(resumeBoundaries = growing) }
    }

    @Test fun c13_abandonedResourceRemainsOccupiedUntilActualOwnerRelease() {
        val first = RecordingSessionId("capture", 1)
        val second = RecordingSessionId("capture", 2)
        val fence = RecordingResourceFence()
        assertTrue(fence.tryAcquire(first))
        assertFalse(fence.tryAcquire(first))
        assertTrue(fence.acceptsResult(first))
        assertTrue(fence.abandon(first))
        assertTrue(fence.isOccupied())
        assertFalse(fence.acceptsResult(first))
        assertFalse(fence.tryAcquire(second))
        assertFalse(fence.confirmReleased(second))
        assertTrue(fence.confirmReleased(first))
        assertFalse(fence.tryAcquire(first))
        assertTrue(fence.tryAcquire(second))
        assertFalse(fence.confirmReleased(first))
        assertTrue(fence.acceptsResult(second))
    }

    @Test fun c01_concurrentResourceAdmissionHasExactlyOneOwner() {
        val fence = RecordingResourceFence()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(16)
        val wins = AtomicInteger()
        val workers = (1..16).map { generation -> Thread {
            start.await()
            if (fence.tryAcquire(RecordingSessionId("capture", generation.toLong()))) wins.incrementAndGet()
            finished.countDown()
        }.also { it.start() } }
        start.countDown()
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        workers.forEach { it.join(5000) }
        assertEquals(1, wins.get())
        assertTrue(fence.isOccupied())
    }

    @Test fun formatClockRegressionIsNotTreatedAsMoreWaitingTime() {
        val gate = RecordingFormatGate(RecordingAudioChoice.INTERNAL, 100)
        gate.checkDeadline(200)
        failure(RecordingError.CLOCK_INVALID) { gate.formatReady(RecordingTrack.VIDEO, 199) }
        assertFalse(gate.started)
    }
}
