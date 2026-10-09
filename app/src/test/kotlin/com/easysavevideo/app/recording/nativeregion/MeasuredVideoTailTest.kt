package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure
import org.junit.Assert.*
import org.junit.Test

class MeasuredVideoTailTest {
    @Test fun actualFollowingPtsDeterminesEachDurationAndLastSampleIsOnlyEndpoint() {
        val tail = MeasuredVideoTail<String>(100)
        assertNull(tail.advance("sync", 0, 10, 0))
        assertEquals(MeasuredVideoTail.Completed("sync", 33_367), tail.advance("second", 33_367, 10, 0))
        assertEquals(MeasuredVideoTail.Completed("second", 46_633), tail.advance("endpoint", 80_000, 10, 0))
        assertEquals("endpoint", tail.discardEndpoint())
        assertEquals(0, tail.retainedBytes)
        assertNull(tail.discardEndpoint())
    }

    @Test fun firstPtsMustBeLocalZeroAndBackwardsOrUnboundedGapsFail() {
        expect(RecordingError.TIMESTAMP_INVALID) { MeasuredVideoTail<String>(100).advance("x", 1, 10, 0) }
        for (next in listOf(0L, -1L, 1_000_001L)) {
            val tail = MeasuredVideoTail<String>(100)
            tail.advance("first", 0, 10, 0)
            expect(RecordingError.TIMESTAMP_INVALID) { tail.advance("next", next, 10, 0) }
            assertEquals("first", tail.discardEndpoint())
        }
    }

    @Test fun bothCompressedSamplesAndOtherPendingBytesShareOneBudget() {
        val tail = MeasuredVideoTail<String>(100)
        tail.advance("first", 0, 40, 0)
        expect(RecordingError.BUFFER_LIMIT) { tail.advance("second", 33_333, 40, 21) }
        assertEquals("first", tail.discardEndpoint())
        assertNull(tail.advance("new-segment", 0, 10, 0))
    }

    private fun expect(error: RecordingError, body: () -> Unit) {
        try { body(); fail("Expected bounded failure") }
        catch (failure: RecordingFailure) { assertEquals(error, failure.code) }
    }
}
