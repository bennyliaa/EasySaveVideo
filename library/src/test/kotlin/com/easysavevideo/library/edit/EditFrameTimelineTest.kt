package com.easysavevideo.library.edit

import org.junit.Assert.*
import org.junit.Test

class EditFrameTimelineTest {
    @Test fun exactTimelineIsCopiedAndComparedByValueWithoutExposingItsArray() {
        val frames = longArrayOf(0, 33_333, 66_666, 100_000)
        val timeline = EditFrameTimeline(frames, 133_333)
        val equal = EditFrameTimeline(frames.copyOf(), 133_333)
        frames[1] = 9
        assertEquals(33_333L, timeline.frameTimeUs(1))
        assertEquals(equal, timeline); assertEquals(equal.hashCode(), timeline.hashCode())
        assertNotEquals(timeline, EditFrameTimeline(longArrayOf(0, 33_334, 66_667, 100_000), 133_333))
        assertNotEquals(timeline, EditFrameTimeline(longArrayOf(0, 33_333, 66_666, 100_000), 133_334))
    }

    @Test fun countExtentAndMonotonicityHaveHardBounds() {
        for (invalid in listOf(longArrayOf(), longArrayOf(-1, 100), longArrayOf(0, 0),
            longArrayOf(0, 200, 100), longArrayOf(0, 1000))) {
            assertThrows(EditFailure::class.java) { EditFrameTimeline(invalid, 1000) }
        }
        assertThrows(EditFailure::class.java) {
            EditFrameTimeline(LongArray(EditLimits.MAX_VIDEO_FRAMES + 1) { it.toLong() }, 1_000_000)
        }
        assertThrows(EditFailure::class.java) { EditFrameTimeline(longArrayOf(0), Long.MAX_VALUE) }
        val largest = EditFrameTimeline(LongArray(EditLimits.MAX_VIDEO_FRAMES) { it * 1_000_000L / 60 },
            EditLimits.MAX_SOURCE_DURATION_US)
        assertEquals(108_000, largest.frameCount); assertTrue(largest.matchesFrameRate(60))
    }

    @Test fun arbitraryTrimSnapsBothEdgesInwardToRealObservedFrames() {
        val timeline = EditFixtures.timeline(1_000_000, 30)
        val trim = timeline.snapInward(10_001, 288_888)
        assertEquals(33_333L, trim.startUs)
        assertEquals(266_666L, trim.endUsExclusive)
        assertEquals(1, trim.firstFrameIndex); assertEquals(7, trim.frameCount)
        assertEquals(trim, timeline.snapInward(33_333, 266_666))
    }

    @Test fun aOneMicrosecondBoundaryDifferenceUsesActualPtsRatherThanReconstruction() {
        val timeline = EditFrameTimeline(longArrayOf(0, 33_334, 66_667, 100_000, 133_334, 166_667), 200_000)
        assertTrue(timeline.matchesFrameRate(30))
        assertEquals(33_334L, timeline.snapInward(33_333, 133_335).startUs)
        assertEquals(100_000L, timeline.snapInward(0, 133_333).endUsExclusive)
        assertEquals(133_334L, timeline.snapInward(0, 133_334).endUsExclusive)
    }

    @Test fun finalObservedEndpointCanRetainTheLastCompleteFrame() {
        val timeline = EditFixtures.timeline(1_000_000, 30)
        val full = timeline.snapInward(0, 1_000_000)
        assertEquals(30, full.frameCount); assertEquals(1_000_000L, full.endUsExclusive)
        val clipped = timeline.snapInward(0, 999_999)
        assertEquals(29, clipped.frameCount); assertEquals(966_666L, clipped.endUsExclusive)
    }

    @Test fun noCompleteFramesOrOutOfExtentRangesFail() {
        val timeline = EditFixtures.timeline(1_000_000, 30)
        for ((start, end) in listOf(1L to 33_332L, -1L to 500_000L, 0L to 1_000_001L,
            900_000L to 800_000L, 999_999L to 1_000_000L)) {
            assertThrows(EditFailure::class.java) { timeline.snapInward(start, end) }
        }
    }

    @Test fun observedTimelineRejectsVfrEvenWhenMetadataClaimsConstantRate() {
        val frameTimes = LongArray(300) { it * 1_000_000L / 30 }
        frameTimes[120] += 2_001
        val timeline = EditFrameTimeline(frameTimes, 10_000_000)
        assertFalse(timeline.matchesFrameRate(30))
        val source = EditFixtures.source().copy(frameTimeline = timeline)
        assertEquals(EditError.UNSUPPORTED_PROFILE,
            assertThrows(EditFailure::class.java) { EditPlanner.plan(EditFixtures.request(), listOf(source)) }.code)
    }

    @Test fun nonAlignedOrderedMergePreservesRequestAndProducesReEditableGlobalTimeline() {
        val source = EditFixtures.source()
        val segments = listOf(EditSegment(source.ref, 10_001, 1_234_567),
            EditSegment(source.ref, 2_100_001, 3_100_001), EditSegment(source.ref, 4_000_001, 5_200_001))
        val request = EditRequest("operation", segments)
        val originalFingerprint = request.fingerprint()
        val plan = EditPlanner.plan(request, listOf(source))
        assertEquals(originalFingerprint, plan.request.fingerprint())
        assertEquals(segments, plan.request.segments)
        assertTrue(plan.expectation.durationUs < segments.sumOf { it.durationUs })
        assertEquals(plan.segments.sumOf { it.durationUs }, plan.expectation.durationUs)
        assertEquals(plan.segments.sumOf { it.frameCount.toLong() }, plan.expectation.videoFrameCount)
        val merged = LongArray(plan.expectation.videoFrameCount.toInt())
        var outputFrame = 0
        for (segment in plan.segments) {
            assertTrue(segment.executionStartUs >= segment.segment.startUs)
            assertTrue(segment.executionEndUsExclusive <= segment.segment.endUsExclusive)
            val input = checkNotNull(segment.metadata.frameTimeline)
            val trim = input.snapInward(segment.segment.startUs, segment.segment.endUsExclusive)
            repeat(trim.frameCount) { index ->
                merged[outputFrame++] = segment.outputStartUs + input.frameTimeUs(trim.firstFrameIndex + index) - trim.startUs
            }
        }
        val editedTimeline = EditFrameTimeline(merged, plan.expectation.durationUs)
        assertTrue(editedTimeline.matchesFrameRate(plan.expectation.frameRate))
        val editedSource = source.copy(durationUs = plan.expectation.durationUs, frameTimeline = editedTimeline)
        val reEdit = EditPlanner.plan(EditRequest("reedit", listOf(EditSegment(source.ref, 1_000, 1_800_000))), listOf(editedSource))
        assertTrue(reEdit.expectation.videoFrameCount >= 3)
        assertTrue(reEdit.retainsOriginals)
    }

    @Test fun accumulatedJoinRoundingIsRejectedBeforeItCreatesAnUneditableOutput() {
        val timeline = EditFrameTimeline(LongArray(300) { it * 1_000_000L / 30 }, 10_000_900)
        val source = EditFixtures.source().copy(durationUs = timeline.endUsExclusive, frameTimeline = timeline)
        assertTrue(timeline.matchesFrameRate(30))
        val segment = EditSegment(source.ref, 0, timeline.endUsExclusive)
        EditPlanner.plan(EditRequest("single", listOf(segment)), listOf(source))
        assertEquals(EditError.INCOMPATIBLE_SEGMENTS, assertThrows(EditFailure::class.java) {
            EditPlanner.plan(EditRequest("merge", listOf(segment, segment)), listOf(source))
        }.code)
    }

    @Test fun duplicatedMuxerTailCannotExceedGlobalClockBudgetDespiteValidInputEndpoint() {
        val pts = LongArray(300) { it * 1_000_000L / 30 }
        pts[298] -= 400
        pts[299] += 400
        val timeline = EditFrameTimeline(pts, 10_000_000)
        assertTrue(timeline.matchesFrameRate(30))
        val source = EditFixtures.source().copy(frameTimeline = timeline)
        val full = EditRequest("operation", listOf(EditSegment(source.ref, 0, 10_000_000)))
        assertEquals(EditError.INCOMPATIBLE_SEGMENTS,
            assertThrows(EditFailure::class.java) { EditPlanner.plan(full, listOf(source)) }.code)
    }

    @Test fun atLeastThreeSelectedFramesAreRequiredForVerifiedMuxerTail() {
        val source = EditFixtures.source().copy(frameRate = 1,
            frameTimeline = EditFixtures.timeline(10_000_000, 1))
        assertThrows(EditFailure::class.java) {
            EditPlanner.plan(EditRequest("operation", listOf(EditSegment(source.ref, 0, 2_000_000))), listOf(source))
        }
        val accepted = EditPlanner.plan(EditRequest("operation", listOf(EditSegment(source.ref, 0, 3_000_000))), listOf(source))
        assertEquals(3, accepted.segments.single().frameCount)
    }
}
