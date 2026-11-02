package com.easysavevideo.library.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailNativeResourceGateTest {
    @Test fun thumbnailAndPreviewCannotOwnNativeResourcesTogether() {
        val gate = ThumbnailNativeResourceGate(); val thumbnail = Any(); val preview = Any()
        assertTrue(gate.claim(thumbnail)); assertFalse(gate.claim(preview))
        gate.released(thumbnail)
        assertTrue(gate.claim(preview)); assertFalse(gate.claim(thumbnail))
    }

    @Test fun staleReleaseCannotFreeAnotherOwnersResources() {
        val gate = ThumbnailNativeResourceGate(); val previous = Any(); val current = Any()
        assertTrue(gate.claim(previous)); gate.released(previous); assertTrue(gate.claim(current))
        gate.released(previous)
        assertTrue(gate.isOccupied); assertFalse(gate.claim(Any()))
    }

    @Test fun timeoutRetainsOwnerAndFencesFreshAdmissionEvenAfterLateActualRelease() {
        val gate = ThumbnailNativeResourceGate(); val stalled = Any()
        assertTrue(gate.claim(stalled)); gate.unconfirmed()
        assertTrue(gate.isOccupied); assertTrue(gate.requiresRestart); assertFalse(gate.claim(Any()))
        gate.released(stalled)
        assertFalse(gate.isOccupied); assertTrue(gate.requiresRestart); assertFalse(gate.claim(Any()))
    }

    @Test fun racingClaimsAdmitExactlyOneOwner() {
        val gate = ThumbnailNativeResourceGate()
        val start = java.util.concurrent.CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map { Thread { start.await(); if (gate.claim(Any())) wins.incrementAndGet() } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach(Thread::join)
        assertEquals(1, wins.get())
    }
}
