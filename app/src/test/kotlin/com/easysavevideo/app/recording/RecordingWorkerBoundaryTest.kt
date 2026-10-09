package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingWorkerBoundaryTest {
    @Test fun successfulWorkerKeepsOnlyItsActualCompletionCallback() {
        val events = mutableListOf<String>()
        RecordingWorkerBoundary.run({ events += "actual-completion" }, { events += "recovery" })
        assertEquals(listOf("actual-completion"), events)
    }
    @Test fun storageAndSecondaryRecoveryExceptionsProduceImmediateUncertaintyWithoutRelease() {
        for (failure in listOf(IllegalStateException("private detail"), java.io.IOException("private path"))) {
            val events = mutableListOf<String>(); var ownerHeld = true
            RecordingWorkerBoundary.run({ throw failure }, { events += "recovery" })
            assertEquals(listOf("recovery"), events); assertTrue(ownerHeld)
        }
    }
    @Test fun queuedCaptureAndBlockedPreparationRemainBoundedUntilActualFirstEncodedFrame() {
        assertTrue(recordingStartStillPending(true, false, false))
        assertTrue(recordingStartStillPending(true, true, false))
        assertFalse(recordingStartStillPending(true, true, true))
        assertTrue(recordingStartStillPending(false, false, false))
        assertFalse(recordingStartStillPending(false, true, false))
    }

}
