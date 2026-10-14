package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Production cleanup policy with injected release actions; not actual Android native release. */
class DirectNormalizedOwnershipCleanupTest {
    @Test fun ordinaryStopFailureDoesNotPoisonAfterConfirmedReleases() {
        val events = mutableListOf<String>(); var poisoned = false
        val failure = assertThrows(SaveFailure::class.java) {
            finishDirectCleanup(null, { poisoned = true }, stop = { events += "stop"; throw IOException() },
                releases = listOf({ events += "codec" }, { events += "extractor" }), after = { events += "lease" })
        }
        assertEquals(SaveError.VERIFICATION_UNAVAILABLE, failure.code)
        assertEquals(listOf("stop", "codec", "extractor", "lease"), events)
        assertFalse(poisoned)
    }

    @Test fun everyReleaseIsAttemptedAndNativeUncertaintyIsLatchedBeforeLeaseClose() {
        val events = mutableListOf<String>(); var poisoned = false
        val failure = assertThrows(SaveFailure::class.java) {
            finishDirectCleanup(null, { poisoned = true; events += "latch" }, releases = listOf(
                { events += "codec"; throw IOException() },
                { events += "extractor"; throw IllegalStateException() },
                { events += "retriever" }), after = { assertTrue(poisoned); events += "lease" })
        }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
        assertEquals(listOf("codec", "latch", "extractor", "latch", "retriever", "lease"), events)
    }

    @Test fun stopReasonsRemainAuthoritativeWhileIndependentReleaseFenceStaysSet() {
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.RETRY_EXHAUSTED)) {
            val primary = SaveFailure(code); var poisoned = false; var closed = false
            // The enclosing catch already owns/rethrows primary; cleanup must not replace it.
            finishDirectCleanup(primary, { poisoned = true }, releases = listOf({ throw IOException() }),
                after = { closed = true })
            assertTrue(poisoned); assertTrue(closed); assertEquals(code, primary.code)
        }
    }

    @Test fun nativeReleaseUncertaintyOverridesOrdinaryPrimaryFailure() {
        val failure = assertThrows(SaveFailure::class.java) {
            finishDirectCleanup(SaveFailure(SaveError.INVALID_MEDIA), {},
                releases = listOf({ throw IOException() }))
        }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
    }

    @Test fun successfulCleanupDoesNotReplaceExistingOrdinaryFailure() {
        val events = mutableListOf<String>()
        finishDirectCleanup(SaveFailure(SaveError.INVALID_MEDIA), { fail("No uncertainty") },
            stop = { throw IOException() }, releases = listOf({ events += "release" }),
            after = { events += "lease" })
        assertEquals(listOf("release", "lease"), events)
    }

    @Test fun evenBrokenLatchCannotSkipIndependentReleaseOrLeaseClose() {
        var second = false; var after = false
        val failure = assertThrows(SaveFailure::class.java) {
            finishDirectCleanup(null, { throw IllegalStateException() }, releases = listOf(
                { throw IOException() }, { second = true }), after = { after = true })
        }
        assertTrue(second); assertTrue(after)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
    }

    @Test fun blockedNativeReleaseDoesNotCloseLeaseOrClaimCompletionUntilActualReturn() {
        val entered = CountDownLatch(1); val allowReturn = CountDownLatch(1)
        val done = CountDownLatch(1); val leaseClosed = AtomicBoolean()
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                finishDirectCleanup(null, { fail("Confirmed release must not poison") }, releases = listOf({
                    entered.countDown(); check(allowReturn.await(5, TimeUnit.SECONDS))
                }), after = { leaseClosed.set(true) })
            } catch (failure: Throwable) { error.set(failure) }
            finally { done.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(leaseClosed.get()); assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        } finally { allowReturn.countDown(); worker.join(2_000) }
        assertFalse(worker.isAlive); assertNull(error.get()); assertTrue(leaseClosed.get())
        assertEquals(0L, done.count)
    }

    @Test fun successfulDescriptorCloseCannotClearPreviouslyLatchedNativeUncertainty() {
        var retained = false; var descriptorClosed = false
        val failure = assertThrows(SaveFailure::class.java) {
            finishDirectCleanup(null, { retained = true }, releases = listOf({ throw IOException() }), after = {
                descriptorClosed = true
                assertTrue(retained) // The owner releases FD, but its mutation fence remains retained.
            })
        }
        assertTrue(descriptorClosed); assertTrue(retained)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
    }
}
