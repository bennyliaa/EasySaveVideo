package com.easysavevideo.app.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual production sequencing helper; simulated resize callbacks are not Android pixel evidence. */
class BasicCaptureGeometryTest {
    private fun ready(width: Int = 1080, height: Int = 2400, density: Int = 420) =
        BasicCaptureGeometry().also { it.bindOwner(); it.displayCreated(width, height, density) }

    @Test fun callbackBeforeDisplayCreationIsRetainedAndAppliedOnce() {
        val geometry = BasicCaptureGeometry()
        assertTrue(geometry.observe(2400, 1080, 420))
        geometry.bindOwner()
        assertEquals(BasicCaptureResizeResult.NOT_READY, geometry.applyLatest({ true }) { fail() })
        geometry.displayCreated(1080, 2400, 420)
        val actual = mutableListOf<BasicCaptureSize>()
        assertEquals(BasicCaptureResizeResult.RESIZED, geometry.applyLatest({ true }, actual::add))
        assertEquals(listOf(BasicCaptureSize(2400, 1080, 420)), actual)
        assertEquals(BasicCaptureResizeResult.UNCHANGED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun noCallbackLeavesTheInitialCanvasAndNeverInventsSelectedSourceDimensions() {
        val geometry = ready()
        assertTrue(geometry.observeDensity(480))
        assertEquals(BasicCaptureResizeResult.UNCHANGED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun latestSizeWinsWithoutAQueueEvenAfterManyChanges() {
        val geometry = ready()
        repeat(100_000) { assertTrue(geometry.observe(1 + it % 8192, 3000, 420)) }
        assertTrue(geometry.observe(2560, 1600, 480))
        val actual = mutableListOf<BasicCaptureSize>()
        geometry.applyLatest({ true }, actual::add)
        assertEquals(listOf(BasicCaptureSize(2560, 1600, 480)), actual)
    }

    @Test fun repeatedAndReturnedToAppliedSizesDoNotCallNativeResize() {
        val geometry = ready()
        repeat(5) { assertTrue(geometry.observe(1080, 2400, 420)) }
        assertEquals(BasicCaptureResizeResult.UNCHANGED, geometry.applyLatest({ true }) { fail() })
        geometry.observe(2400, 1080, 420)
        geometry.observe(1080, 2400, 420)
        assertEquals(BasicCaptureResizeResult.UNCHANGED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun portraitToLandscapeAndLargerSourceUseGlobalBoundsNotInitialPerAxisBounds() {
        val geometry = ready()
        val actual = mutableListOf<BasicCaptureSize>()
        for ((width, height) in listOf(2400 to 1080, 8192 to 8192, 1 to 1)) {
            assertTrue(geometry.observe(width, height, 420))
            assertEquals(BasicCaptureResizeResult.RESIZED, geometry.applyLatest({ true }, actual::add))
        }
        assertEquals(listOf(2400 to 1080, 8192 to 8192, 1 to 1), actual.map { it.width to it.height })
    }

    @Test fun invalidGeometryDoesNotReplaceTheLastValidObservation() {
        val geometry = ready()
        geometry.observe(2400, 1080, 420)
        for ((width, height, density) in listOf(
            Triple(0, 1080, 420), Triple(-1, 1080, 420), Triple(8193, 1080, 420),
            Triple(2400, 0, 420), Triple(2400, -1, 420), Triple(2400, 8193, 420),
            Triple(2400, 1080, 0), Triple(2400, 1080, -1), Triple(Int.MAX_VALUE, Int.MAX_VALUE, 420),
        )) assertFalse(geometry.observe(width, height, density))
        val actual = mutableListOf<BasicCaptureSize>()
        geometry.applyLatest({ true }, actual::add)
        assertEquals(listOf(BasicCaptureSize(2400, 1080, 420)), actual)
    }

    @Test fun api34DensityObservationPreservesSelectedAppSize() {
        val geometry = ready()
        geometry.observe(700, 900, 420)
        assertTrue(geometry.observeDensity(480))
        val actual = mutableListOf<BasicCaptureSize>()
        geometry.applyLatest({ true }, actual::add)
        assertEquals(listOf(BasicCaptureSize(700, 900, 480)), actual)
        assertTrue(geometry.observeDensity(480))
        assertEquals(BasicCaptureResizeResult.UNCHANGED, geometry.applyLatest({ true }) { fail() })
        assertFalse(geometry.observeDensity(0))
    }

    @Test fun newObservationDuringNativeResizeIsNotLost() {
        val geometry = ready()
        geometry.observe(2400, 1080, 420)
        val actual = mutableListOf<BasicCaptureSize>()
        geometry.applyLatest({ true }) {
            actual += it
            geometry.observe(1600, 2560, 480)
        }
        geometry.applyLatest({ true }, actual::add)
        assertEquals(listOf(BasicCaptureSize(2400, 1080, 420), BasicCaptureSize(1600, 2560, 480)), actual)
    }

    @Test fun nativeAllocationFailurePropagatesExactlyAndCannotBeRetried() {
        val geometry = ready()
        geometry.observe(8192, 8192, 420)
        val failure = IllegalStateException("allocation")
        try {
            geometry.applyLatest({ true }) { throw failure }
            fail()
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        geometry.observe(2400, 1080, 420)
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun stopBeforeDisplayCreationDiscardsPendingGeometry() {
        val geometry = BasicCaptureGeometry()
        geometry.observe(2400, 1080, 420)
        geometry.retire()
        geometry.bindOwner()
        geometry.displayCreated(1080, 2400, 420)
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun stopBetweenOwnerChecksWinsBeforeNativeAdmission() {
        val geometry = ready()
        geometry.observe(2400, 1080, 420)
        var checks = 0
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({
            if (++checks == 2) geometry.retire()
            true
        }) { fail() })
        assertEquals(2, checks)
    }

    @Test fun lostExactOwnerOrLeaseRetiresWithoutNativeWork() {
        val geometry = ready()
        geometry.observe(2400, 1080, 420)
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ false }) { fail() })
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun observerThreadPublishesButCannotApplyOrRebindNativeOwner() {
        val geometry = ready()
        val error = AtomicReference<Throwable?>()
        val observer = Thread {
            try {
                assertTrue(geometry.observe(2400, 1080, 420))
                try { geometry.applyLatest({ true }) { fail() }; fail() }
                catch (_: IllegalStateException) { }
                try { geometry.bindOwner(); fail() }
                catch (_: IllegalStateException) { }
                try { geometry.displayCreated(2400, 1080, 420); fail() }
                catch (_: IllegalStateException) { }
            } catch (failure: Throwable) { error.set(failure) }
        }
        observer.start(); observer.join(5_000)
        assertFalse(observer.isAlive)
        error.get()?.let { throw it }
        val owner = Thread.currentThread()
        assertEquals(BasicCaptureResizeResult.RESIZED, geometry.applyLatest({ true }) {
            assertSame(owner, Thread.currentThread())
        })
    }

    @Test fun retiredCallbacksIncludingMalformedOnesCannotReopenOrFailTheStoppedSession() {
        val geometry = ready()
        geometry.retire()
        assertTrue(geometry.observe(2400, 1080, 420))
        assertTrue(geometry.observe(0, 0, 0))
        assertTrue(geometry.observeDensity(0))
        assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ true }) { fail() })
    }

    @Test fun freshSessionDoesNotInheritOldSessionsObservationOrRetirement() {
        val old = ready()
        val fresh = ready()
        old.observe(2400, 1080, 420)
        old.retire()
        old.observe(100, 100, 420)
        fresh.observe(1600, 2560, 480)
        val actual = mutableListOf<BasicCaptureSize>()
        fresh.applyLatest({ true }, actual::add)
        assertEquals(listOf(BasicCaptureSize(1600, 2560, 480)), actual)
    }

    @Test fun stopDoesNotWaitForAdmittedBlockedResizeAndOwnerCannotResizeAgain() {
        val geometry = BasicCaptureGeometry()
        val entered = CountDownLatch(1)
        val returnFromDriver = CountDownLatch(1)
        val ownerFinished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                geometry.bindOwner()
                geometry.displayCreated(1080, 2400, 420)
                geometry.observe(2400, 1080, 420)
                assertEquals(BasicCaptureResizeResult.RESIZED, geometry.applyLatest({ true }) {
                    entered.countDown()
                    check(returnFromDriver.await(5, TimeUnit.SECONDS))
                })
                assertEquals(BasicCaptureResizeResult.RETIRED, geometry.applyLatest({ true }) { fail() })
            } catch (failure: Throwable) { error.set(failure) }
            finally { ownerFinished.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            geometry.retire() // Must return while the owner/native operation is still blocked.
            assertEquals(1L, ownerFinished.count)
            geometry.observe(1600, 2560, 480)
        } finally { returnFromDriver.countDown() }
        assertTrue(ownerFinished.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        error.get()?.let { throw it }
    }
}
