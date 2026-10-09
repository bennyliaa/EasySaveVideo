package com.easysavevideo.app.library.share

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShareProxyAdmissionTest {
    @Test fun reservationOwnedButFrameworkOpenNeverHoldsCallbackLock() {
        val lock = Any()
        val callback = CountDownLatch(1)
        val result = ShareProxyAdmission.open(lock, reserve = {
            assertTrue(Thread.holdsLock(lock)); "owned"
        }, open = { owned ->
            assertFalse(Thread.holdsLock(lock))
            val release = Thread { synchronized(lock) { callback.countDown() } }
            release.start()
            assertTrue("An older callback can release while framework open is waiting", callback.await(2, TimeUnit.SECONDS))
            release.join(2000)
            owned
        })
        assertEquals("owned", result)
    }
    @Test fun reservationFailureNeverCallsFramework() {
        var opens = 0
        try { ShareProxyAdmission.open(Any(), { throw IllegalStateException("NO_CAPACITY") }, { _: Nothing -> opens++; 0 }); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, opens)
    }
}
