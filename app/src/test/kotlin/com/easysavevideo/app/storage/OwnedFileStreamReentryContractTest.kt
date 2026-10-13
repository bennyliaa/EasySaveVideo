package com.easysavevideo.app.storage

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Deterministic models of callbacks which return before the outer native descriptor closes. */
class OwnedFileStreamReentryContractTest {
    private enum class Stage { CHANNEL, STREAM }
    private class Unconfirmed : RuntimeException()
    private class Owner {
        val lock = Any()
        val descriptorClosed = AtomicBoolean(false)
        val owners = AtomicInteger(1)
        val releases = AtomicInteger()
        val unconfirmed = AtomicInteger()
        val unconfirmedThread = AtomicReference<Thread?>()
        val release = OwnedFileStreamRelease(lock, {
            assertTrue("Ownership changes must be serialized", Thread.holdsLock(lock))
            assertTrue("Accounting cannot replace actual descriptor evidence", descriptorClosed.get())
            assertEquals(0, owners.decrementAndGet())
            releases.incrementAndGet()
            Unit
        }, {
            assertFalse("Failure reporting must not hold the ownership lock", Thread.holdsLock(lock))
            unconfirmedThread.set(Thread.currentThread())
            unconfirmed.incrementAndGet()
            throw Unconfirmed()
        })
        fun stillOwned() {
            assertFalse(descriptorClosed.get())
            assertEquals(1, owners.get())
            assertEquals(0, releases.get())
        }
        fun releasedOnce() {
            assertTrue(descriptorClosed.get())
            assertEquals(0, owners.get())
            assertEquals(1, releases.get())
        }
    }

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue(label, latch.await(5, TimeUnit.SECONDS))
    }

    private fun worker(name: String, done: CountDownLatch, failure: AtomicReference<Throwable?>, action: () -> Unit): Thread =
        Thread({
            try { action() }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }, name).apply { isDaemon = true; start() }

    private fun assertNoFailure(failure: AtomicReference<Throwable?>) {
        failure.get()?.let { throw AssertionError("Close contract worker failed", it) }
    }

    private fun expectUnconfirmed(action: () -> Unit) {
        try { action(); fail("Only an outer returned close with an open descriptor must fail") }
        catch (_: Unconfirmed) { }
    }

    @Test fun nestedOpenDescriptorCallbacksReturnBeforeOuterClosesAndReleasesExactlyOnce() {
        for (stage in Stage.entries) {
            val owner = Owner()
            var callbacksReturned = 0
            fun callback() {
                assertFalse(Thread.holdsLock(owner.lock))
                // Both nested frames return with an open descriptor, as in Windows closeAll.
                owner.release.close({}, {
                    owner.release.close({}, {}, { owner.descriptorClosed.get() })
                    owner.stillOwned()
                    assertEquals(0, owner.unconfirmed.get())
                    callbacksReturned++
                }, { owner.descriptorClosed.get() })
                owner.stillOwned()
                assertEquals(0, owner.unconfirmed.get())
                callbacksReturned++
            }
            owner.release.close({
                assertFalse(Thread.holdsLock(owner.lock))
                if (stage == Stage.CHANNEL) callback()
            }, {
                assertFalse(Thread.holdsLock(owner.lock))
                if (stage == Stage.STREAM) callback()
                assertEquals(2, callbacksReturned)
                owner.stillOwned()
                owner.descriptorClosed.set(true)
            }, { owner.descriptorClosed.get() })
            owner.releasedOnce()
            repeat(3) { owner.release.close({}, {}, { owner.descriptorClosed.get() }) }
            owner.releasedOnce()
            assertEquals(0, owner.unconfirmed.get())
        }
    }

    @Test fun stillOpenOuterReturnReportsUnconfirmedOnceAfterNestedCallbacksReturn() {
        for (stage in Stage.entries) {
            val owner = Owner()
            var nestedReturned = false
            fun callback() {
                owner.release.close({}, {}, { false })
                owner.stillOwned()
                assertEquals(0, owner.unconfirmed.get())
                nestedReturned = true
            }
            expectUnconfirmed {
                owner.release.close({ if (stage == Stage.CHANNEL) callback() }, {
                    if (stage == Stage.STREAM) callback()
                    assertTrue(nestedReturned)
                    assertEquals(0, owner.unconfirmed.get())
                }, { false })
            }
            assertTrue(nestedReturned)
            assertEquals(1, owner.unconfirmed.get())
            owner.stillOwned()
        }
    }

    @Test fun nestedPlatformExceptionsRestoreDepthBeforeSameThreadRetry() {
        for (stage in Stage.entries) {
            val owner = Owner()
            val original = IOException("synthetic-reentrant-close-failure")
            try {
                owner.release.close({
                    owner.release.close({ if (stage == Stage.CHANNEL) throw original }, {
                        if (stage == Stage.STREAM) throw original
                    }, { false })
                }, { fail("The outer stream close must not run after its channel throws") }, { false })
                fail("Expected the original platform exception")
            } catch (actual: IOException) { assertSame(original, actual) }
            owner.stillOwned()
            assertEquals(0, owner.unconfirmed.get())
            // A leaked thread depth would suppress this later top-level failure.
            expectUnconfirmed { owner.release.close({}, {}, { false }) }
            owner.stillOwned()
            assertEquals(1, owner.unconfirmed.get())
            owner.release.close({}, { owner.descriptorClosed.set(true) }, { owner.descriptorClosed.get() })
            owner.releasedOnce()
        }
    }

    @Test fun descriptorProbeExceptionRestoresDepthAndKeepsOwnershipForRetry() {
        val owner = Owner()
        val original = IllegalStateException("synthetic-descriptor-probe-failure")
        try {
            owner.release.close({}, {}, { throw original })
            fail("The descriptor probe failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(original, actual) }
        owner.stillOwned()
        expectUnconfirmed { owner.release.close({}, {}, { false }) }
        assertEquals(1, owner.unconfirmed.get())
        owner.release.close({}, { owner.descriptorClosed.set(true) }, { owner.descriptorClosed.get() })
        owner.releasedOnce()
    }

    @Test fun anotherThreadsTopLevelCloseIsNotMistakenForReentry() {
        val owner = Owner()
        val firstEntered = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        worker("owner-reentry-first", firstDone, failure) {
            owner.release.close({
                firstEntered.countDown()
                await(finishFirst, "The first close must be released after the second close is checked")
            }, { owner.descriptorClosed.set(true) }, { owner.descriptorClosed.get() })
        }
        try {
            await(firstEntered, "The first thread must be inside its platform close")
            val second = worker("owner-reentry-second", secondDone, failure) {
                expectUnconfirmed { owner.release.close({}, {}, { false }) }
            }
            await(secondDone, "A second thread's independent close must not wait for the first platform close")
            assertNoFailure(failure)
            assertEquals(1, owner.unconfirmed.get())
            assertSame(second, owner.unconfirmedThread.get())
            owner.stillOwned()
        } finally {
            finishFirst.countDown()
            await(firstDone, "The first close must finish after descriptor closure is allowed")
        }
        assertNoFailure(failure)
        owner.releasedOnce()
        assertEquals(1, owner.unconfirmed.get())
    }

    @Test fun platformChannelAndStreamCloseLeaveOwnershipLockAvailableToOtherThreads() {
        for (stage in Stage.entries) {
            val owner = Owner()
            val platformEntered = CountDownLatch(1)
            val contenderAcquired = CountDownLatch(1)
            val contenderDone = CountDownLatch(1)
            val closerDone = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            worker("owner-lock-contender-$stage", contenderDone, failure) {
                await(platformEntered, "The platform close must start")
                synchronized(owner.lock) {
                    owner.stillOwned()
                    contenderAcquired.countDown()
                }
            }
            fun platformClose() {
                assertFalse("Platform close must run outside the ownership lock", Thread.holdsLock(owner.lock))
                platformEntered.countDown()
                await(contenderAcquired, "Another thread must acquire ownership lock before platform close returns")
            }
            worker("owner-platform-close-$stage", closerDone, failure) {
                owner.release.close({ if (stage == Stage.CHANNEL) platformClose() }, {
                    if (stage == Stage.STREAM) platformClose()
                    owner.descriptorClosed.set(true)
                }, { owner.descriptorClosed.get() })
            }
            try {
                await(closerDone, "Platform close must not deadlock against the ownership lock")
                await(contenderDone, "The ownership-lock contender must finish")
                assertNoFailure(failure)
                owner.releasedOnce()
                assertEquals(0, owner.unconfirmed.get())
            } finally {
                // Unblock either worker on an assertion failure; no test leaves a permanent wait.
                platformEntered.countDown()
                contenderAcquired.countDown()
            }
        }
    }
}
