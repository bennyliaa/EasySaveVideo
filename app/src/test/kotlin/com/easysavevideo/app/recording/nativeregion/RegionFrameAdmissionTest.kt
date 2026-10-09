package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingFrameTimeline
import org.junit.Assert.*
import org.junit.Test

class RegionFrameAdmissionTest {
    @Test fun roundedSixtyHzSourceProducesMeasuredThirtyFpsPositiveWithoutRetime() {
        val gate = RegionFrameAdmission(30)
        val source = (0..120).map { 1_000_000_000L + it * 1_000_000_000L / 60 }
        val accepted = source.filter(gate::admit)
        assertEquals((0..120 step 2).map(source::get), accepted)
        val base = accepted.first()
        val pts = accepted.map { (it - base) / 1000 }
        val timing = RecordingFrameTimeline(pts.dropLast(1).toLongArray(), pts.last())
        assertTrue(timing.matchesConstantFrameRate(30))
    }

    @Test fun oneNanosecondShortPairsAreNotDroppedIntoFiftyMillisecondGaps() {
        val gate = RegionFrameAdmission(30)
        assertTrue(gate.admit(1_000_000_000L))
        assertFalse(gate.admit(1_016_666_666L))
        assertTrue(gate.admit(1_033_333_332L))
        assertFalse(gate.admit(1_049_999_999L))
        assertTrue(gate.admit(1_066_666_664L))
    }

    @Test fun slowerOrVariableSourceRetainsRealTimingAndCannotClaimRequestedCfr() {
        val gate = RegionFrameAdmission(30)
        val actual = (0..60).map { 1_000_000_000L + it * 1_000_000_000L / 29 }.filter(gate::admit)
        val pts = actual.map { (it - actual.first()) / 1000 }
        assertFalse(RecordingFrameTimeline(pts.dropLast(1).toLongArray(), pts.last()).matchesConstantFrameRate(30))
    }

    @Test fun highRefreshInputIsBoundedAndPauseDoesNotCauseCatchupBurst() {
        val gate = RegionFrameAdmission(30)
        val accepted = (0..240).count { gate.admit(1_000_000_000L + it * 1_000_000_000L / 240) }
        assertEquals(31, accepted)
        assertTrue(gate.admit(10_000_000_000L))
        assertFalse(gate.admit(10_004_000_000L))
        assertTrue(gate.admit(10_033_333_333L))
    }
}
