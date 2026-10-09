package com.easysavevideo.app.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecordingNativeLeaseTest {
    @Test fun queuedCancellationStaysFencedUntilWorkerAcknowledgesIt() {
        val lease = RecordingNativeLease()
        assertTrue(lease.tryAcquire()); assertFalse(lease.tryAcquire())
        lease.abandon(); assertTrue(lease.occupied); assertFalse(lease.started())
        assertFalse(lease.tryAcquire()); lease.released(true)
        assertTrue(lease.tryAcquire()); assertTrue(lease.started()); assertTrue(lease.started())
        lease.released(true); assertFalse(lease.occupied)
    }

    @Test fun executorRejectionBeforeSchedulingCanFreeQueuedAdmission() {
        val lease = RecordingNativeLease()
        assertTrue(lease.tryAcquire()); lease.rejectQueued()
        assertFalse(lease.occupied); assertFalse(lease.started())
        assertTrue(lease.tryAcquire())
    }

    @Test fun timeoutCannotFreeBlockedNativeOwner() {
        val lease = RecordingNativeLease()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        assertTrue(lease.tryAcquire()); assertTrue(lease.started())
        val thread = Thread { entered.countDown(); unblock.await(); lease.released(true) }
        thread.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            lease.abandon(); lease.abandon()
            assertTrue(lease.occupied); assertTrue(lease.releaseUnconfirmed)
            assertFalse(lease.tryAcquire())
        } finally { unblock.countDown(); thread.join(1000) }
        assertFalse(lease.occupied); assertFalse(lease.releaseUnconfirmed)
    }

    @Test fun throwingNativeReleaseRequiresProcessRecovery() {
        val lease = RecordingNativeLease()
        assertTrue(lease.tryAcquire()); assertTrue(lease.started())
        lease.keepAlive(Any()); lease.released(false); lease.abandon()
        assertTrue(lease.occupied); assertTrue(lease.releaseUnconfirmed)
        assertFalse(lease.tryAcquire()); assertFalse(lease.started())
    }
}
