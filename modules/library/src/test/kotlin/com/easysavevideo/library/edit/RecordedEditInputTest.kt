package com.easysavevideo.library.edit

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.RecordedSegmentReadRef
import org.junit.Assert.*
import org.junit.Test

class RecordedEditInputTest {
    private fun recorded(index: Int, session: String = "session") = EditSegment(RecordedSegmentReadRef(session, "segment-$index", 1, index), 0, 1_000_000)
    @Test fun initialPlus32ResumesAreAllowedOnlyForPrivateOrderedSession() {
        val request = EditRequest("operation", List(33) { recorded(it) })
        assertTrue(request.recordedInputs); assertEquals(33, request.maxSegments)
        assertEquals(33, request.segments.size)
    }
    @Test fun ordinaryLimitRemainsEight() {
        rejects { EditRequest("operation", List(9) { EditSegment(MediaReadRef("ordinary", 1, 1), 0, 1_000_000) }) }
    }
    @Test fun mixedSourcesSessionsGapsDuplicatesAndReorderReject() {
        rejects { EditRequest("operation", listOf(recorded(0), EditSegment(MediaReadRef("ordinary", 1, 1), 0, 1_000_000))) }
        rejects { EditRequest("operation", listOf(recorded(0), recorded(1, "other"))) }
        rejects { EditRequest("operation", listOf(recorded(0), EditSegment(RecordedSegmentReadRef("session", "segment-1", 2, 1), 0, 1_000_000))) }
        rejects { EditRequest("operation", listOf(recorded(0), recorded(2))) }
        rejects { EditRequest("operation", listOf(recorded(0), recorded(0))) }
        rejects { EditRequest("operation", listOf(recorded(1), recorded(0))) }
    }
    @Test fun generationAndSessionRemainInFingerprint() {
        val first = EditRequest("operation", listOf(recorded(0)))
        val otherSession = EditRequest("operation", listOf(recorded(0, "other")))
        val otherGeneration = EditRequest("operation", listOf(EditSegment(RecordedSegmentReadRef("session", "segment-0", 2, 0), 0, 1_000_000)))
        assertNotEquals(first.fingerprint(), otherSession.fingerprint()); assertNotEquals(first.fingerprint(), otherGeneration.fingerprint())
    }
    @Test fun completeThirtyThreeSegmentCfrAacPlanHasExactObservedTimeline() {
        val request = EditRequest("operation", List(33) { recorded(it) })
        val audio = EditAudioProfile.Aac(48000, 2)
        val metadata = request.segments.map { segment -> EditFixtures.source(audio = audio).copy(
            ref = segment.source, durationUs = 1_000_000,
            frameTimeline = EditFrameTimeline(LongArray(30) { it * 1_000_000L / 30 }, 1_000_000)) }
        val plan = EditPlanner.plan(request, metadata)
        assertEquals(33, plan.segments.size); assertEquals(33, plan.expectation.recordedSegmentCount)
        assertEquals(33_000_000L, plan.expectation.durationUs); assertEquals(990L, plan.expectation.videoFrameCount)
        assertEquals(30, plan.expectation.frameRate); assertEquals(audio, plan.expectation.audio)
        assertEquals(33L * ((2_048_000_000L + 47999) / 48000), plan.expectation.audioPrimingToleranceUs)
        plan.segments.forEachIndexed { index, segment ->
            assertEquals(index * 1_000_000L, segment.outputStartUs)
            assertEquals((index + 1) * 1_000_000L, segment.outputEndUsExclusive)
            assertEquals(30, segment.frameCount); assertEquals(0L, segment.executionStartUs)
            assertEquals(1_000_000L, segment.executionEndUsExclusive)
            assertTrue(segment.metadata.frameTimeline!!.matchesFrameRate(30))
        }
    }
    @Test fun observedVfrAmongThirtyThreeInputsNeverNormalizesSilently() {
        val request = EditRequest("operation", List(33) { recorded(it) })
        val sources = request.segments.map { EditFixtures.source().copy(ref = it.source, durationUs = 1_000_000,
            frameTimeline = EditFrameTimeline(LongArray(30) { frame -> frame * 1_000_000L / 30 }, 1_000_000)) }.toMutableList()
        sources[15] = sources[15].copy(frameTimeline = EditFrameTimeline(LongArray(30) { frame -> frame * 1_000_000L / 30 + if (frame == 10) 2000 else 0 }, 1_000_000))
        rejects { EditPlanner.plan(request, sources) }
    }
    @Test fun snapshotIsTheOnlyValidatedAndRetainedList() {
        var reads = 0
        val changing = object : AbstractList<EditSegment>() {
            override val size = 2
            override fun get(index: Int): EditSegment {
                reads++
                return if (reads <= 2) recorded(index) else recorded(index, "changed")
            }
        }
        val request = EditRequest("operation", changing)
        assertEquals(2, reads)
        assertEquals(listOf(recorded(0), recorded(1)), request.segments)
        val mutable = mutableListOf(recorded(0), recorded(1))
        val stable = EditRequest("operation", mutable); mutable.clear()
        assertEquals(2, stable.segments.size)
    }
    @Test fun maliciousListCannotChangeTypeAfterValidationBeforeCopy() {
        var reads = 0
        val changing = object : AbstractList<EditSegment>() {
            override val size = 2
            override fun get(index: Int): EditSegment = if (++reads == 1) recorded(0)
                else EditSegment(MediaReadRef("ordinary", 1, 1), 0, 1_000_000)
        }
        rejects { EditRequest("operation", changing) }
    }
    @Test fun recordedAudioBudgetIsExplicitAndCannotBroadenOrdinaryAllowance() {
        val audio = EditAudioProfile.Aac(32000, 2)
        rejects { EditOutputExpectation(33_000_000, 320, 240, 30, audio, 1_100_022, 2_112_000) }
        assertEquals(33, EditOutputExpectation(33_000_000, 320, 240, 30, audio, 1_100_022, 2_112_000,
            recordedSegmentCount = 33).recordedSegmentCount)
        rejects { EditOutputExpectation(33_000_000, 320, 240, 30, audio, 1_100_022, 2_112_001, recordedSegmentCount = 33) }
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }
}
