package com.easysavevideo.app.recording

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class SegmentRecordingFenceTest {
    private val session = RecordingSessionId("session", 1)
    @Test fun stopSaveKeepsProcessingEligibleOnlyAfterActualClose() {
        val fence = SegmentRecordingFence(session)
        assertTrue(fence.startNative(session))
        assertTrue(fence.stopAndSave(session, RecordingStopReason.USER_STOP))
        assertFalse(fence.beginProcessing(session))
        fence.nativeReleased(session, true)
        assertTrue(fence.beginProcessing(session)); assertTrue(fence.acceptsResult(session))
        assertTrue(fence.finish(session)); assertFalse(fence.acceptsResult(session))
    }
    @Test fun queuedDiscardRequiresWorkerAcknowledgementAndNeverPublishes() {
        val fence = SegmentRecordingFence(session)
        assertTrue(fence.discard(session)); assertFalse(fence.isCaptureClosed)
        assertFalse(fence.startNative(session)); assertFalse(fence.beginProcessing(session))
        fence.nativeReleased(session, true)
        assertTrue(fence.isCaptureClosed); assertFalse(fence.beginProcessing(session))
    }
    @Test fun timeoutDoesNotReleaseAndLateClosureNeverRestoresResultAuthority() {
        val fence = SegmentRecordingFence(session)
        assertTrue(fence.startNative(session)); assertTrue(fence.revoke(session))
        assertFalse(fence.isCaptureClosed); assertFalse(fence.acceptsResult(session))
        fence.nativeReleased(session, true)
        assertTrue(fence.isCaptureClosed); assertFalse(fence.beginProcessing(session))
    }
    @Test fun nativeUncertaintyRevokesEvenIfLaterDuplicateClaimsClose() {
        val fence = SegmentRecordingFence(session)
        fence.startNative(session); fence.nativeReleased(session, false)
        assertFalse(fence.beginProcessing(session))
        fence.nativeReleased(session, true); assertFalse(fence.beginProcessing(session))
    }
    @Test fun cancelWinsOverSaveAndConcurrentLatePublication() {
        val fence = SegmentRecordingFence(session)
        fence.startNative(session); fence.stopAndSave(session, RecordingStopReason.USER_STOP)
        fence.nativeReleased(session, true); assertTrue(fence.beginProcessing(session))
        assertTrue(fence.discard(session)); assertFalse(fence.acceptsResult(session))
        assertFalse(fence.stopAndSave(session, RecordingStopReason.SYSTEM_STOPPED))
    }
    @Test fun foreignGenerationCommandsAndCallbacksAreFenced() {
        val fence = SegmentRecordingFence(session); val foreign = session.copy(generation = 2)
        assertFalse(fence.startNative(foreign)); assertFalse(fence.discard(foreign))
        assertFalse(fence.revoke(foreign)); assertFalse(fence.stopAndSave(foreign, RecordingStopReason.USER_STOP))
        fence.nativeReleased(foreign, true); assertFalse(fence.isCaptureClosed)
        assertTrue(fence.startNative(session)); assertFalse(fence.startNative(session))
    }
    @Test fun lockRotationSystemStopAllRetainEligibleClosedPrefix() {
        for (reason in listOf(RecordingStopReason.SIZE_CHANGED, RecordingStopReason.SYSTEM_STOPPED, RecordingStopReason.RESUME_LIMIT)) {
            val fence = SegmentRecordingFence(session); fence.startNative(session)
            assertTrue(fence.stopAndSave(session, reason)); assertEquals(reason, fence.stopReason)
            fence.nativeReleased(session, true); assertTrue(fence.beginProcessing(session))
        }
    }
    @Test fun restoredSessionGetsNoNativeOrProcessingAuthorityWithoutExplicitAction() {
        val fence = SegmentRecordingFence(session)
        assertFalse(fence.acceptsResult(session)); assertFalse(fence.beginProcessing(session))
        assertFalse(fence.stopAndSave(session, RecordingStopReason.USER_CANCELLED))
    }
}
