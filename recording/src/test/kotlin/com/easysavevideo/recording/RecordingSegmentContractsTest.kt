package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingSegmentContractsTest {
    private val session = RecordingSessionId("recording", 1)
    private val geometry = CaptureDisplayGeometry(1080, 2400, 0, CaptureInsets(top = 96))
    private val scope = CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY
    private val region = RegionCaptureRecipe(RegionNormalizedRect.FULL, geometry, scope, 5)
    private val observation = CaptureGeometryObservation(geometry, scope,
        CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 6)
    private val descriptor = CaptureDescriptor(session.sessionId, session.generation, RecordingAudioChoice.NONE, regionCaptureRecipe = region)
    private val profile = RecordingProfile(width = 1080, height = 2400)
    private fun timing(times: LongArray = longArrayOf(0, 100_000, 200_000), end: Long = 300_000) =
        RecordingFrameTimeline(times, end)
    private fun segment(index: Int = 0, startUs: Long = index * 300_000L,
        timeline: RecordingFrameTimeline = timing()): RecordingClosedSegment {
        val video = RecordingTrackEvidence(timeline.frameCount.toLong(), timeline.firstFrameUs,
            timeline.lastFrameUs, timeline.endUsExclusive - timeline.lastFrameUs, 1, true)
        val expectation = RecordingArtifactExpectation(descriptor, profile, RecordingAudioOutcome.NO_AUDIO_REQUESTED,
            RecordingStopReason.USER_STOP, video, null, video.endPtsUs, 1024)
        return RecordingClosedSegment(RecordingSegmentId(session, index), RecordingOutputHandle("segment-$index"),
            expectation, timeline, observation, region, startUs)
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid contract") } catch (_: IllegalArgumentException) { }
    }

    @Test fun timingCopiesObservedPresentationOrderAndDoesNotExposeMutableArrays() {
        val times = longArrayOf(0, 100_000, 200_000)
        val timeline = timing(times)
        times[1] = 1
        assertEquals(100_000L, timeline.frameTimeUs(1))
        assertEquals(3, timeline.frameCount)
        assertEquals(200_000L, timeline.lastFrameUs)
        assertEquals(RecordingFrameTimingProvenance.ENCODED_PRESENTATION_TIMESTAMPS, timeline.provenance)
        assertEquals(timing(), timeline)
        assertEquals(timing().hashCode(), timeline.hashCode())
    }

    @Test fun timingRejectsEmptySingleDuplicateBackwardsAndUnobservedEnd() {
        invalid { timing(longArrayOf()) }
        invalid { timing(longArrayOf(0)) }
        invalid { timing(longArrayOf(0, 0)) }
        invalid { timing(longArrayOf(1, 100_000)) }
        invalid { timing(longArrayOf(0, 200_000, 100_000)) }
        invalid { timing(longArrayOf(0, 300_000)) }
        invalid { timing(longArrayOf(0, 100_000), Long.MAX_VALUE) }
        invalid { timing(LongArray(RecordingSegmentLimits.MAX_VIDEO_FRAMES + 1) { it.toLong() }) }
    }

    @Test fun cfrRequiresEveryObservedPhaseAndFinalEndpointRatherThanDeclaredOrAverageFps() {
        assertTrue(timing().matchesConstantFrameRate(10))
        assertFalse(timing().matchesConstantFrameRate(30))
        assertFalse(timing().matchesConstantFrameRate(0))
        assertFalse(timing().matchesConstantFrameRate(61))
        assertFalse(timing().matchesConstantFrameRate(10, 1001))
        val vfr = timing(longArrayOf(0, 90_000, 200_000))
        assertFalse(vfr.matchesConstantFrameRate(10))
        val endpointChanged = timing(end = 301_001)
        assertFalse(endpointChanged.matchesConstantFrameRate(10))
        assertTrue(timing(longArrayOf(0, 99_500, 200_500), 300_500).matchesConstantFrameRate(10))
    }

    @Test fun independentTimingChecksAllSamplesAndLastEndpoint() {
        val inspected = RecordingFrameTimeline(longArrayOf(0, 100_100, 200_100), 300_100,
            RecordingFrameTimingProvenance.INDEPENDENT_CLOSED_ARTIFACT)
        assertTrue(timing().matchesObserved(inspected))
        assertFalse(timing().matchesObserved(inspected, 0))
        assertFalse(timing().matchesObserved(timing(longArrayOf(0, 90_000, 200_000))))
        assertFalse(timing().matchesObserved(timing(end = 301_001)))
        assertFalse(timing().matchesObserved(timing(longArrayOf(0, 200_000))))
        assertFalse(timing().matchesObserved(inspected, -1))
    }

    @Test fun closedEnvelopeRequiresExactTrackTimingAndNativeClosedExpectation() {
        val closed = segment()
        assertTrue(closed.videoTiming.matchesTrack(closed.expectation.video))
        invalid { closed.copy(videoTiming = timing(longArrayOf(0, 100_001, 200_001))) }
        invalid { closed.copy(id = RecordingSegmentId(RecordingSessionId("other", 1), 0)) }
        invalid { closed.copy(expectation = closed.expectation.copy(resourcesReleased = false)) }
        invalid { closed.copy(expectation = closed.expectation.copy(finalized = false)) }
        invalid { closed.copy(expectation = closed.expectation.copy(resumeBoundaries = null)) }
        invalid { closed.copy(effectiveStartUs = -1) }
        invalid { closed.copy(effectiveStartUs = RecordingSegmentLimits.MAX_DURATION_US) }
        invalid { closed.copy(geometry = observation.copy(geometry = geometry.copy(rotationDegrees = 90))) }
        invalid { closed.copy(expectation = closed.expectation.copy(actualProfile = profile.copy(width = 720))) }
    }

    @Test fun oldInFileResumeBoundaryCannotMasqueradeAsIndependentClosedSegment() {
        val closed = segment()
        invalid {
            closed.copy(expectation = closed.expectation.copy(
                video = closed.expectation.video.copy(syncSampleCount = 2),
                resumeBoundaries = listOf(RecordingResumeBoundary(1, 100_000)),
            ))
        }
    }

    @Test fun fullDisplayWithoutRegionStillForbidsStretchingPresetOutput() {
        val original = segment()
        val full = original.copy(region = null, expectation = original.expectation.copy(
            descriptor = descriptor.copy(regionCaptureRecipe = null)))
        assertEquals(1080, full.expectation.actualProfile.width)
        invalid { full.copy(expectation = full.expectation.copy(actualProfile = RecordingProfile())) }
    }

    @Test fun sequencePreservesExactOrderAndExcludesAllPauseTime() {
        val input = mutableListOf(segment(), segment(1), segment(2))
        val sequence = RecordingSegmentSequence(input)
        input.clear()
        assertEquals(3, sequence.segments.size)
        assertEquals(2, sequence.actualResumeCount)
        assertEquals(0L, sequence.effectiveStartUs(0))
        assertEquals(300_000L, sequence.effectiveStartUs(1))
        assertEquals(900_000L, sequence.effectiveDurationUs)
        assertEquals(session, sequence.session)
        invalid { RecordingSegmentSequence(listOf(segment(), segment(1, 600_000))) }
        invalid { RecordingSegmentSequence(listOf(segment(1), segment())) }
        invalid { RecordingSegmentSequence(listOf(segment(), segment(2, 300_000))) }
        invalid { RecordingSegmentSequence(listOf(segment(), segment(1, 0))) }
        invalid { RecordingSegmentSequence(listOf(segment(), segment(1).copy(outputHandle = segment().outputHandle))) }
    }

    @Test fun sequenceRejectsMixedConfigurationSelectionSessionAndScope() {
        val first = segment()
        val second = segment(1)
        invalid { RecordingSegmentSequence(listOf(first, second.copy(
            expectation = second.expectation.copy(actualProfile = profile.copy(videoBitrate = 7_000_000))))) }
        invalid { RecordingSegmentSequence(listOf(first, second.copy(region = region.copy(selectedAtMonotonicUs = 4)))) }
        val olderScope = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY
        val olderRegion = region.copy(scopeEvidence = olderScope)
        val older = second.copy(region = olderRegion,
            expectation = second.expectation.copy(descriptor = descriptor.copy(regionCaptureRecipe = olderRegion)),
            geometry = observation.copy(scopeEvidence = olderScope,
                provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE))
        invalid { RecordingSegmentSequence(listOf(first, older)) }
        val otherDescriptor = descriptor.copy(sessionId = "different")
        val otherSession = second.copy(id = RecordingSegmentId(otherDescriptor.session, 1),
            expectation = second.expectation.copy(descriptor = otherDescriptor))
        invalid { RecordingSegmentSequence(listOf(first, otherSession)) }
    }

    @Test fun maximumIsInitialSegmentPlus32ActualResumes() {
        val maximum = RecordingSegmentSequence((0..32).map { segment(it) })
        assertEquals(33, maximum.segments.size)
        assertEquals(32, maximum.actualResumeCount)
        invalid { RecordingSegmentId(session, 33) }
        invalid { RecordingSegmentId(session, -1) }
        invalid { RecordingSegmentSequence(emptyList()) }
        invalid { RecordingSegmentSequence((0..32).map { segment(it) } + segment(32)) }
        val budget = RecordingSegmentResumeBudget()
        repeat(100) { assertTrue(budget.canStartResume) }
        assertEquals(0, budget.actualResumeCount)
        repeat(32) { assertEquals(it + 1, budget.resumedSegmentStarted()) }
        assertFalse(budget.canStartResume)
        try { budget.resumedSegmentStarted(); fail("Expected bounded resumes") }
        catch (failure: RecordingFailure) { assertEquals(RecordingError.SAMPLE_LIMIT, failure.code) }
        assertEquals(32, budget.actualResumeCount)
    }

    @Test fun wholeSequenceBudgetsCannotBeBypassedUsingSeparateFiles() {
        val first = segment().let { it.copy(expectation = it.expectation.copy(bytesWritten = RecordingSegmentLimits.MAX_TOTAL_BYTES)) }
        invalid { RecordingSegmentSequence(listOf(first, segment(1))) }
        val largeTimeline = timing(LongArray(54_001) { it * 1000L }, 54_001_000)
        val a = segment(timeline = largeTimeline)
        val b = segment(1, a.effectiveEndUs, largeTimeline)
        invalid { RecordingSegmentSequence(listOf(a, b)) }
    }

    @Test fun stopRetainsForVerificationAndCancelDiscardsOnlyAfterActualClose() {
        assertEquals(RecordingSegmentRetentionAction.RETAIN_FOR_VERIFICATION, RecordingSegmentRetentionPolicy.decide(
            RecordingSegmentTerminalIntent.STOP_AND_SAVE, RecordingSegmentNativeRelease.CONFIRMED_CLOSED, false, false))
        assertEquals(RecordingSegmentRetentionAction.RETAIN_VERIFIED_SEGMENTS, RecordingSegmentRetentionPolicy.decide(
            RecordingSegmentTerminalIntent.STOP_AND_SAVE, RecordingSegmentNativeRelease.CONFIRMED_CLOSED, false, true))
        assertEquals(RecordingSegmentRetentionAction.DISCARD_OWNED_SEGMENTS, RecordingSegmentRetentionPolicy.decide(
            RecordingSegmentTerminalIntent.CANCEL_AND_DISCARD, RecordingSegmentNativeRelease.CONFIRMED_CLOSED, false, true))
        for (intent in RecordingSegmentTerminalIntent.entries) {
            assertEquals(RecordingSegmentRetentionAction.WAIT_FOR_RELEASE,
                RecordingSegmentRetentionPolicy.decide(intent, RecordingSegmentNativeRelease.OWNED, false, false))
            assertEquals(RecordingSegmentRetentionAction.RECOVERY_REQUIRED,
                RecordingSegmentRetentionPolicy.decide(intent, RecordingSegmentNativeRelease.UNCONFIRMED, false, true))
            assertEquals(RecordingSegmentRetentionAction.RECOVERY_REQUIRED,
                RecordingSegmentRetentionPolicy.decide(intent, RecordingSegmentNativeRelease.CONFIRMED_CLOSED, true, true))
        }
    }
}
