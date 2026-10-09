package com.easysavevideo.app.save

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeVerificationLeaseTest {
    @Test fun cancellationBeforeNativeStartRevokesStartWithoutOwnershipLeak() {
        val owned = AtomicInteger()
        val lease = NativeVerificationLease({ owned.incrementAndGet() }, { owned.decrementAndGet() })
        lease.abandon(); lease.abandon()
        assertFalse(lease.start()); assertEquals(0, owned.get())
    }
    @Test fun abandonmentKeepsOwnershipUntilBlockedNativeReleaseActuallyReturns() {
        val owned = AtomicInteger()
        val enteredRelease = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lease = NativeVerificationLease({ owned.incrementAndGet() }, { owned.decrementAndGet() })
        assertTrue(lease.start())
        val nativeThread = Thread {
            enteredRelease.countDown()
            allowRelease.await()
            lease.released()
            finished.countDown()
        }
        nativeThread.start()
        try {
            assertTrue(enteredRelease.await(1, TimeUnit.SECONDS))
            lease.abandon(); lease.abandon()
            assertEquals(1, owned.get())
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
            assertEquals(1, owned.get())
        } finally { allowRelease.countDown(); nativeThread.join(1000) }
        assertEquals(0, owned.get())
        lease.abandon(); assertEquals(0, owned.get())
    }
    @Test fun releaseBeforeCancellationDoesNotCreateFalseDrainingState() {
        val owned = AtomicInteger()
        val lease = NativeVerificationLease({ owned.incrementAndGet() }, { owned.decrementAndGet() })
        assertTrue(lease.start()); lease.released(); lease.abandon()
        assertEquals(0, owned.get())
    }
}
