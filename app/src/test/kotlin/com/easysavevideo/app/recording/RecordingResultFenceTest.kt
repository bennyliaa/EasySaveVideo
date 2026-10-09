package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingResultFenceTest {
    @Test fun releaseBeforeAbandonStillClearsControllerOccupancy() {
        val lease = RecordingNativeLease(); lease.tryAcquire(); lease.started()
        var occupied = true
        val fence = RecordingResultFence(lease, { occupied = true }, { occupied = false })
        lease.released(true); fence.ownerFinished()
        assertTrue(occupied)
        fence.abandon {}
        assertFalse(occupied); assertTrue(fence.isAbandoned)
        assertTrue(lease.tryAcquire())
    }
    @Test fun abandonBeforeReleaseRetainsFenceUntilOwnerActuallyReturns() {
        val lease = RecordingNativeLease(); lease.tryAcquire(); lease.started()
        var occupied = true
        val fence = RecordingResultFence(lease, { occupied = true }, { occupied = false })
        fence.abandon {}
        assertTrue(occupied); assertFalse(lease.tryAcquire())
        lease.released(true); fence.ownerFinished()
        assertFalse(occupied); assertTrue(fence.isAbandoned)
    }
    @Test fun failedNativeReleaseNeverRemovesOccupancy() {
        val lease = RecordingNativeLease(); lease.tryAcquire(); lease.started()
        var released = false
        val fence = RecordingResultFence(lease, {}, { released = true })
        lease.released(false); fence.abandon {}; fence.ownerFinished()
        assertFalse(released); assertTrue(lease.occupied); assertFalse(lease.tryAcquire())
    }
}
