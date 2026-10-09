package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test

class MediaTaskActivityHandoffTest {
    @Test fun workerReturnBeforeMainInstallationCannotExposeIdleDuringSegmentRecovery() {
        val tracker = MediaTaskActivityTracker()
        lateinit var owner: MediaTaskActivityHandoff
        var segmentInstalled = false
        var segmentRecovering = true
        fun publish() = tracker.update(MediaTaskActivitySignals(initializing = owner.active,
            segmentRecording = segmentInstalled && segmentRecovering))
        owner = MediaTaskActivityHandoff(::publish)
        val mainQueue = ArrayDeque<() -> Unit>()
        publish()
        assertTrue(owner.enqueue({ mainQueue.addLast(it); true }) { segmentInstalled = true; publish() })
        // The worker's finally and pending-command decrement happen before main installs the runtime.
        owner.preparationFailed()
        val observed = mutableListOf<Boolean>()
        tracker.addListener { observed += it.active }
        publish()
        assertTrue(owner.active)
        assertEquals(listOf(true), observed)
        mainQueue.removeFirst().invoke()
        assertFalse(owner.active)
        assertTrue(tracker.snapshot.recordingActive)
        assertTrue(observed.all { it })
        segmentRecovering = false; publish()
        assertFalse(tracker.snapshot.active)
    }

    @Test fun prepareFailureReleasesOwnerWithoutUsingReadinessAsAnActivitySignal() {
        var changes = 0
        val owner = MediaTaskActivityHandoff { changes++ }
        owner.preparationFailed(); owner.preparationFailed()
        assertFalse(owner.active)
        assertEquals(1, changes)
    }

    @Test fun mainQueueRejectionReleasesOwnerAndNeverInstalls() {
        val owner = MediaTaskActivityHandoff { }
        assertFalse(owner.enqueue({ false }) { error("Rejected install") })
        assertFalse(owner.active)
    }

    @Test fun throwingDispatcherAndInstallationBothReleaseOwner() {
        val rejected = MediaTaskActivityHandoff { }
        try { rejected.enqueue({ throw IllegalStateException() }) { } } catch (_: IllegalStateException) { }
        assertFalse(rejected.active)
        val installing = MediaTaskActivityHandoff { }
        val queue = ArrayDeque<() -> Unit>()
        installing.enqueue({ queue.addLast(it); true }) { throw IllegalStateException() }
        try { queue.removeFirst().invoke() } catch (_: IllegalStateException) { }
        assertFalse(installing.active)
    }
}
