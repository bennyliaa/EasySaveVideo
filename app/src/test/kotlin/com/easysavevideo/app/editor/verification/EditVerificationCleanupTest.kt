package com.easysavevideo.app.editor.verification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class EditVerificationCleanupTest {
    @Test fun ordinaryCancellationWaitsForRealCloseWithoutPermanentRecovery() {
        val gate = EditVerificationNativeGate(); val owner = Any(); val ack = EditVerificationCleanup()
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner)); gate.abandon(owner)
        val worker = Thread { gate.finish(owner, true); ack.finished(true) }.also { it.start() }
        try {
            assertTrue(ack.awaitConfirmed(1000)); assertFalse(gate.occupied); assertFalse(gate.recoveryRequired)
        } finally { worker.join(1000) }
    }
    @Test fun missingCleanupAcknowledgementKeepsTheOwnerDraining() {
        val gate = EditVerificationNativeGate(); val owner = Any(); val ack = EditVerificationCleanup()
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner)); gate.abandon(owner)
        assertFalse(ack.awaitConfirmed(10))
        assertTrue(gate.occupied); assertTrue(gate.recoveryRequired)
        gate.finish(owner, true); ack.finished(true)
        assertTrue(ack.awaitConfirmed(10)); assertFalse(gate.occupied)
    }
    @Test fun failedCloseAcknowledgementNeverMeansConfirmed() {
        val gate = EditVerificationNativeGate(); val owner = Any(); val ack = EditVerificationCleanup()
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner)); gate.abandon(owner)
        gate.finish(owner, false); ack.finished(false)
        assertFalse(ack.awaitConfirmed(1000)); assertTrue(gate.recoveryRequired)
    }
    @Test fun interruptedCallerStillWaitsForCleanupAndRestoresInterrupt() {
        val ack = EditVerificationCleanup()
        val entered = CountDownLatch(1); val returned = CountDownLatch(1)
        val result = AtomicBoolean(); val interrupted = AtomicBoolean()
        val caller = Thread {
            Thread.currentThread().interrupt()
            entered.countDown()
            result.set(ack.awaitConfirmed(1000))
            interrupted.set(Thread.currentThread().isInterrupted)
            returned.countDown()
        }.also { it.start() }
        try {
            assertTrue(entered.await(1000, TimeUnit.MILLISECONDS))
            assertFalse(returned.await(10, TimeUnit.MILLISECONDS))
            ack.finished(true)
            assertTrue(returned.await(1000, TimeUnit.MILLISECONDS))
            assertTrue(result.get()); assertTrue(interrupted.get())
        } finally { caller.join(1000) }
    }
    @Test fun queuedCancellationAcknowledgedByWorkerIsAlsoOrdinaryClosure() {
        val gate = EditVerificationNativeGate(); val owner = Any(); val ack = EditVerificationCleanup()
        assertTrue(gate.acquire(owner)); gate.abandon(owner)
        assertFalse(gate.start(owner)); gate.finish(owner, true); ack.finished(true)
        assertTrue(ack.awaitConfirmed(1000)); assertFalse(gate.recoveryRequired)
    }
}
