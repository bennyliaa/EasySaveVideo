package com.easysavevideo.app.editor.verification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class EditVerificationNativeGateTest {
    @Test fun cancellationOfQueuedWorkNeedsActualAcknowledgment() {
        val gate = EditVerificationNativeGate(); val owner = Any()
        assertTrue(gate.acquire(owner)); gate.abandon(owner)
        assertTrue(gate.occupied); assertTrue(gate.recoveryRequired)
        assertFalse(gate.acquire(Any())); assertFalse(gate.start(owner))
        gate.finish(owner, true)
        assertFalse(gate.occupied)
    }
    @Test fun timeoutDoesNotReleaseBlockedNativeOwner() {
        val gate = EditVerificationNativeGate(); val owner = Any()
        val nativeRelease = CountDownLatch(1); val done = CountDownLatch(1)
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner))
        val thread = Thread { nativeRelease.await(); gate.finish(owner, true); done.countDown() }.also { it.start() }
        try {
            gate.abandon(owner)
            assertTrue(gate.occupied); assertTrue(gate.recoveryRequired)
            assertFalse(done.await(10, TimeUnit.MILLISECONDS)); assertFalse(gate.acquire(Any()))
        } finally { nativeRelease.countDown(); thread.join(1000) }
        assertFalse(gate.occupied)
    }
    @Test fun failedActualReleaseRemainsSticky() {
        val gate = EditVerificationNativeGate(); val owner = Any()
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner))
        gate.finish(owner, false); gate.abandon(owner)
        assertTrue(gate.occupied); assertTrue(gate.recoveryRequired); assertFalse(gate.acquire(Any()))
    }
    @Test fun releaseBeforeCallerCancellationCannotAbandonDifferentOwner() {
        val gate = EditVerificationNativeGate(); val first = Any(); val second = Any()
        assertTrue(gate.acquire(first)); assertTrue(gate.start(first)); gate.finish(first, true)
        assertTrue(gate.acquire(second)); gate.abandon(first)
        assertFalse(gate.recoveryRequired); assertTrue(gate.start(second)); gate.finish(second, true)
    }
    @Test fun executorRejectionIsDifferentFromFutureCancellation() {
        val gate = EditVerificationNativeGate(); val owner = Any()
        assertTrue(gate.acquire(owner)); gate.rejectedBeforeScheduling(owner)
        assertFalse(gate.occupied); assertFalse(gate.recoveryRequired)
    }
}
