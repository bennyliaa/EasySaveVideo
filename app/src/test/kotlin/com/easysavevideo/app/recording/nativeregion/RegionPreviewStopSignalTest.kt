package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RegionPreviewStopSignalTest {
    private val session = RecordingSessionId("stop-preview", 1)
    private val source = CaptureDisplayGeometry(1080, 1920, 0)
    private val scope = CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY
    private val matrix = floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)
    private fun frame() = RegionPreviewSnapshot(session, 1, 1,
        CaptureGeometryObservation(source, scope, CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 110),
        540, 960, RegionPreviewTransform(matrix), 120_000, 120, 60_000_120)
    private fun gate() = RegionPreviewGate(session, 1, 100).also { assertTrue(it.markHostReady(110)) }
    private fun invalidate(gate: RegionPreviewGate, error: RecordingError) {
        gate.invalidate(if (error == RecordingError.FORMAT_CHANGED) RegionPreviewInvalidation.GEOMETRY_CHANGED
            else RegionPreviewInvalidation.CANCELLED)
    }

    @Test fun sourceStopBeforeQueuedOfferSynchronouslyRejectsPublicationAndConfirmation() {
        for (reason in listOf(RecordingStopReason.SYSTEM_STOPPED, RecordingStopReason.SIZE_CHANGED,
                RecordingStopReason.USER_CANCELLED)) {
            val gate = gate()
            RegionPreviewStopSignal().signal(true, reason) { invalidate(gate, it) }
            // Represents queued publication after the callback has returned, without Android claims.
            assertTrue(gate.isInvalidated)
            assertFalse(gate.publish(frame(), 121))
            assertFalse(gate.confirm(frame(), RegionNormalizedRect.FULL, 122))
            assertNull(gate.snapshot)
        }
    }

    @Test fun sourceStopAfterOfferBeforeQueuedUiReadRemovesSnapshotSynchronously() {
        val gate = gate()
        assertTrue(gate.publish(frame(), 120))
        RegionPreviewStopSignal().signal(true, RecordingStopReason.SIZE_CHANGED) { invalidate(gate, it) }
        assertNull(gate.snapshot)
        assertFalse(gate.confirm(frame(), RegionNormalizedRect.FULL, 121))
        assertEquals(RegionPreviewInvalidation.GEOMETRY_CHANGED, gate.invalidationReason)
    }

    @Test fun admittedRecordingDoesNotReusePendingPreviewRevocation() {
        val calls = AtomicInteger()
        RegionPreviewStopSignal().signal(false, RecordingStopReason.SYSTEM_STOPPED) { calls.incrementAndGet() }
        assertEquals(0, calls.get())
    }

    @Test fun repeatedConcurrentSignalsCoalesceAndReentrantObserverCannotRecurse() {
        val signal = RegionPreviewStopSignal()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..8).map { Thread {
            start.await()
            repeat(100) {
                signal.signal(true, RecordingStopReason.SYSTEM_STOPPED) {
                    calls.incrementAndGet()
                    signal.signal(true, RecordingStopReason.USER_CANCELLED) { calls.incrementAndGet() }
                }
            }
        }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }
        assertEquals(1, calls.get())
    }

    @Test fun geometryMapsToFormatChangeAndOtherStopSignalsToCancellation() {
        for (reason in RecordingStopReason.entries) {
            var observed: RecordingError? = null
            RegionPreviewStopSignal().signal(true, reason) { observed = it }
            assertEquals(if (reason == RecordingStopReason.SIZE_CHANGED) RecordingError.FORMAT_CHANGED
                else RecordingError.CANCELLED, observed)
        }
    }
}
