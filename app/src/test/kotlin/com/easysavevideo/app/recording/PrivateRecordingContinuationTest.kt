package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class PrivateRecordingContinuationTest {
    private val owner = Any()
    private val activity = Any()
    private val service = Any()
    private fun gate() = PrivateRecordingContinuation(owner, activity, 1_000)
    @Test fun serviceReadyDoesNotBypassProtectedPauseConfirmation() {
        val g = gate(); assertTrue(g.suspendProtected(owner, activity))
        assertTrue(g.serviceParked(owner, service, 1))
        assertFalse(g.mayPrepareSurface(owner, activity, true, 2))
        assertTrue(g.confirmContinue(owner, activity, g.generation, true, 3))
        assertTrue(g.claimSubmission(owner, activity, service, true, 4))
        assertFalse(g.claimSubmission(owner, activity, service, true, 4))
    }
    @Test fun lateClickFromEarlierPauseCannotConfirmNewSuspension() {
        val g = gate(); g.suspendProtected(owner, activity); val old = g.generation
        g.serviceParked(owner, service, 1); g.suspendProtected(owner, activity)
        assertFalse(g.confirmContinue(owner, activity, old, true, 2))
        assertFalse(g.mayPrepareSurface(owner, activity, true, 3))
    }
    @Test fun resultBeforeResumeWaitsForActualForegroundTarget() {
        val g = gate(); g.suspendProtected(owner, activity); g.serviceParked(owner, service, 1)
        assertFalse(g.confirmContinue(owner, activity, g.generation, false, 2))
        assertFalse(g.claimSubmission(owner, activity, service, false, 2))
        assertTrue(g.confirmContinue(owner, activity, g.generation, true, 3))
        assertTrue(g.claimSubmission(owner, activity, service, true, 4))
    }
    @Test fun foreignOwnerActivityServiceAndExpiredGrantCannotStart() {
        val g = gate()
        assertFalse(g.serviceParked(Any(), service, 1))
        assertTrue(g.serviceParked(owner, service, 1))
        assertFalse(g.claimSubmission(Any(), activity, service, true, 2))
        assertFalse(g.claimSubmission(owner, Any(), service, true, 2))
        assertFalse(g.claimSubmission(owner, activity, Any(), true, 2))
        assertFalse(g.claimSubmission(owner, activity, service, true, 1_000))
    }
    @Test fun stopIsIrreversibleAndCannotBeReplacedByLateService() {
        val g = gate(); g.stop()
        assertFalse(g.serviceParked(owner, service, 1))
        assertFalse(g.suspendProtected(owner, activity))
        assertFalse(g.confirmContinue(owner, activity, g.generation, true, 2))
        assertFalse(g.claimSubmission(owner, activity, service, true, 3))
    }
    @Test fun duplicateServiceCannotReplaceOriginalExactService() {
        val g = gate(); assertTrue(g.serviceParked(owner, service, 1))
        assertFalse(g.serviceParked(owner, Any(), 2))
        assertTrue(g.claimSubmission(owner, activity, service, true, 3))
    }
}
