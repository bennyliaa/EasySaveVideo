package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingPreviewHostRequestTest {
    private val first = RecordingPreviewHostRequest("session", 1, RecordingPreviewHostStage.BEFORE_CONSENT)
    @Test fun staleGenerationCannotSubstituteNewDraw() {
        val second = first.copy(generation = 2, stage = RecordingPreviewHostStage.BEFORE_SNAPSHOT)
        assertFalse(recordingPreviewHostCallbackCurrent(second, first, "session"))
        assertTrue(recordingPreviewHostCallbackCurrent(second, second, "session"))
    }
    @Test fun sameGeometryDoesNotJoinAnotherSession() {
        assertFalse(recordingPreviewHostCallbackCurrent(first, first, "other"))
        assertFalse(recordingPreviewHostCallbackCurrent(null, first, "session"))
    }
    @Test fun readinessStagesCannotBeReused() {
        RecordingPreviewHostStage.entries.filter { it != first.stage }.forEach {
            assertFalse(recordingPreviewHostCallbackCurrent(first.copy(stage = it), first, "session"))
        }
    }
    @Test fun onePendingAndOneSuccessfulCallback() {
        val fence = RecordingPreviewDrawFence()
        assertTrue(fence.reserve(true)); assertFalse(fence.reserve(true))
        assertTrue(fence.arrive(true, true)); assertFalse(fence.arrive(true, true))
        assertFalse(fence.reserve(true))
    }
    @Test fun noFocusCannotReserveAndLostFocusCanRetry() {
        val fence = RecordingPreviewDrawFence()
        assertFalse(fence.reserve(false)); assertTrue(fence.reserve(true))
        assertFalse(fence.arrive(true, false)); assertTrue(fence.reserve(true))
        assertTrue(fence.arrive(true, true))
    }
    @Test fun detachDoesNotDeliver() {
        val fence = RecordingPreviewDrawFence()
        assertTrue(fence.reserve(true)); assertFalse(fence.arrive(false, true))
    }
    @Test fun disposalAndBackgroundRejectQueuedCallbacks() {
        val fence = RecordingPreviewDrawFence()
        assertTrue(fence.reserve(true)); fence.close()
        assertFalse(fence.arrive(true, true)); assertFalse(fence.reserve(true)); assertTrue(fence.isRetired())
    }
    @Test fun onlyKnownSecureSelectorTransitionMayOwnFocus() {
        val required = setOf(com.easysavevideo.recording.RegionPreviewPhase.WAITING_FOR_FRAME,
            com.easysavevideo.recording.RegionPreviewPhase.READY_TO_ENCODE,
            com.easysavevideo.recording.RegionPreviewPhase.ADMITTED)
        com.easysavevideo.recording.RegionPreviewPhase.entries.forEach {
            assertEquals(it in required, recordingPreviewRequiresHostFocus(it))
        }
        assertFalse(recordingPreviewRequiresHostFocus(null))
    }
    @Test fun callbackWithoutSubmittedDrawHasNoAuthority() {
        assertFalse(RecordingPreviewDrawFence().arrive(true, true))
    }
}
