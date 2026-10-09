package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure
import org.junit.Assert.*
import org.junit.Test

class MeasuredAudioTailTest {
    @Test fun onlyWholePacketsEndingWithinActualVideoBoundaryAreWrittenInOrder() {
        val tail = MeasuredAudioTail<String>(100, 8)
        tail.offer("a", 0, 21_333, 10, 0, 0)
        tail.offer("b", 21_333, 21_333, 10, 0, 0)
        tail.offer("c", 42_666, 21_333, 10, 0, 0)
        assertNull(tail.pollReady(21_332))
        assertEquals("a", tail.pollReady(21_333))
        assertNull(tail.pollReady(33_333))
        assertEquals("b", tail.pollReady(60_000))
        assertNull(tail.pollReady(60_000))
        assertEquals(listOf("c"), tail.discard())
        assertEquals(0, tail.retainedSamples)
        assertEquals(0L, tail.retainedBytes)
    }

    @Test fun audioAndVideoHoldbackShareByteAndSampleBudgets() {
        val tail = MeasuredAudioTail<String>(100, 3)
        tail.offer("a", 0, 21_333, 20, 30, 1)
        expect(RecordingError.BUFFER_LIMIT) { tail.offer("b", 21_333, 21_333, 51, 30, 1) }
        tail.offer("b", 21_333, 21_333, 20, 30, 1)
        expect(RecordingError.BUFFER_LIMIT) { tail.offer("c", 42_666, 21_333, 1, 30, 1) }
        assertEquals(listOf("a", "b"), tail.discard())
    }

    @Test fun backwardsPacketIsRejectedWithoutLosingPriorTail() {
        val tail = MeasuredAudioTail<String>(100, 3)
        tail.offer("a", 21_333, 21_333, 20, 0, 0)
        expect(RecordingError.TIMESTAMP_INVALID) { tail.offer("b", 21_333, 21_333, 20, 0, 0) }
        assertEquals(listOf("a"), tail.discard())
    }

    private fun expect(error: RecordingError, body: () -> Unit) {
        try { body(); fail("Expected bounded failure") }
        catch (failure: RecordingFailure) { assertEquals(error, failure.code) }
    }
}
