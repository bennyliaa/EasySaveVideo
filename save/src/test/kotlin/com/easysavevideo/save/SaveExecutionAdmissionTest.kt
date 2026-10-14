package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SaveExecutionAdmissionTest {
    @Test fun defaultOneHasNoQueueAndExclusiveUsesTheSameFence() {
        var probes = 0
        val admission = SaveExecutionAdmission(maxArtifactBytes = 100, availableBytes = { probes++; 1000 })
        val first = admission.tryAcquireDirect()!!
        assertNull(admission.tryAcquireDirect()); assertNull(admission.tryAcquireExclusive())
        assertEquals(0, probes); assertEquals(1, admission.snapshot().totalCount)
        assertTrue(admission.release(first)); assertFalse(admission.release(first))
        val exclusive = admission.tryAcquireExclusive()!!
        assertFalse(admission.isDirect(exclusive)); assertTrue(admission.snapshot().exclusive)
        assertNull(admission.tryAcquireDirect()); assertNull(admission.tryAcquireExclusive())
        assertFalse(admission.release(first)); assertTrue(admission.owns(exclusive))
        assertTrue(admission.release(exclusive)); assertFalse(admission.snapshot().busy)
    }

    @Test fun downgradeRetainsBothClaimsAndEveryLaterSecondAdmissionRechecksSpace() {
        var probes = 0
        val admission = SaveExecutionAdmission(2, 100, { probes++; 200 })
        val a = admission.tryAcquireDirect()!!; val b = admission.tryAcquireDirect()!!
        assertNull(admission.tryAcquireDirect()); assertEquals(1, probes)
        admission.updateMaxDirectTasks(1)
        assertTrue(admission.owns(a)); assertTrue(admission.owns(b)); assertNull(admission.tryAcquireDirect())
        assertTrue(admission.release(a)); assertNull(admission.tryAcquireDirect())
        admission.updateMaxDirectTasks(2)
        val c = admission.tryAcquireDirect()!!
        assertEquals(2, probes); assertFalse(admission.release(a)); assertTrue(admission.owns(c))
        assertTrue(admission.release(b)); assertTrue(admission.release(c))
    }

    @Test fun secondSlotRejectsMissingNegativeInsufficientOverflowAndThrowingStorage() {
        val probes = listOf<() -> Long?>({ null }, { -1 }, { 199 }, { throw IllegalStateException() })
        for (probe in probes) {
            val admission = SaveExecutionAdmission(2, 100, probe)
            assertNotNull(admission.tryAcquireDirect()); assertNull(admission.tryAcquireDirect())
            assertEquals(1, admission.snapshot().directCount)
        }
        val overflow = SaveExecutionAdmission(2, Long.MAX_VALUE, { Long.MAX_VALUE })
        assertNotNull(overflow.tryAcquireDirect()); assertNull(overflow.tryAcquireDirect())
    }

    @Test fun competingSecondSnapshotsAdmitOnlyOneAndDoNotHoldAdmissionDuringDiskProbe() {
        val probes = CountDownLatch(2); val release = CountDownLatch(1)
        val admission = SaveExecutionAdmission(2, 100, { probes.countDown(); check(release.await(5, TimeUnit.SECONDS)); 200 })
        val a = admission.tryAcquireDirect()!!
        val b = AtomicReference<SaveExecutionAdmission.Claim?>(); val c = AtomicReference<SaveExecutionAdmission.Claim?>()
        val t1 = thread { b.set(admission.tryAcquireDirect()) }; val t2 = thread { c.set(admission.tryAcquireDirect()) }
        try {
            assertTrue(probes.await(5, TimeUnit.SECONDS))
            // This completes while both storage calls remain blocked.
            assertEquals(1, admission.snapshot().directCount)
            release.countDown(); t1.join(5000); t2.join(5000)
            assertFalse(t1.isAlive); assertFalse(t2.isAlive)
            assertEquals(1, listOf(b.get(), c.get()).count { it != null })
            assertEquals(2, admission.snapshot().directCount)
        } finally { release.countDown(); admission.release(a) }
    }

    @Test fun versionChangeRejectsStaleSpaceSnapshotEvenWhenCountReturnsToOne() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val admission = SaveExecutionAdmission(2, 100, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 200 })
        val a = admission.tryAcquireDirect()!!
        val result = AtomicReference<SaveExecutionAdmission.Claim?>()
        val worker = thread { result.set(admission.tryAcquireDirect()) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(admission.release(a))
            val replacement = admission.tryAcquireDirect()!!
            release.countDown(); worker.join(5000)
            assertNull(result.get()); assertTrue(admission.owns(replacement))
        } finally { release.countDown() }
    }

    @Test fun exactRuntimeBindingIsOneShotAndReleaseCannotStealAnExecutingClaim() {
        val admission = SaveExecutionAdmission(maxArtifactBytes = 100)
        val claim = admission.tryAcquireDirect()!!; val runtime = Any()
        assertEquals(true, admission.prepare(claim, TaskMethod.DIRECT, "task", "operation") { true to runtime })
        assertNull(admission.prepare(claim, TaskMethod.DIRECT, "other", "operation") { true to Any() })
        assertNull(admission.enter(claim, "task", "operation", Any()) { true })
        assertEquals(true, admission.enter(claim, "task", "operation", runtime) { true })
        assertFalse(admission.release(claim)); assertNull(admission.enter(claim, "task", "operation", runtime) { true })
        admission.exit(claim, Any()); assertFalse(admission.release(claim))
        admission.exit(claim, runtime); assertTrue(admission.release(claim))
        assertNull(admission.enter(claim, "task", "operation", runtime) { true })
    }

    @Test fun nativeBatchLivesThroughLastClaimAndTemporaryDrainingUntilActualRelease() {
        val firstCalls = AtomicInteger(); val lastCalls = AtomicInteger(); var poison = false
        val admission = SaveExecutionAdmission(2, 100, { 200 }, { poison },
            { firstCalls.incrementAndGet(); true }, { lastCalls.incrementAndGet() })
        val a = admission.tryAcquireDirect()!!; val b = admission.tryAcquireDirect()!!
        assertEquals(1, firstCalls.get()); assertTrue(admission.release(a)); assertEquals(0, lastCalls.get())
        poison = true
        assertFalse(admission.release(b)); assertTrue(admission.owns(b)); assertEquals(0, lastCalls.get())
        assertTrue(admission.snapshot().recoveryRequired)
        poison = false // The actual native-release gate has now confirmed release.
        assertTrue(admission.release(b)); assertEquals(1, lastCalls.get())
        assertFalse(admission.snapshot().recoveryRequired); assertFalse(admission.snapshot().busy)
        assertNotNull(admission.tryAcquireExclusive())
    }

    @Test fun lastConfirmedReleaseRunsBatchHookOnceAndForeignClaimsCannotRelease() {
        var closed = 0
        val admission = SaveExecutionAdmission(2, 100, { 200 }, onLastDirectReleased = { closed++ })
        val a = admission.tryAcquireDirect()!!; val b = admission.tryAcquireDirect()!!
        val foreign = SaveExecutionAdmission(maxArtifactBytes = 100).tryAcquireDirect()!!
        assertFalse(admission.release(foreign)); assertTrue(admission.release(a)); assertEquals(0, closed)
        assertTrue(admission.release(b)); assertFalse(admission.release(b)); assertEquals(1, closed)
    }
    @Test fun blockedPrepareAndEnterNeverHoldAdmissionMonitorAndProtectTheirExactClaim() {
        for (entering in listOf(false, true)) {
            val entered = CountDownLatch(1); val releaseAction = CountDownLatch(1)
            val admission = SaveExecutionAdmission(2, 100, { 200 })
            val claim = admission.tryAcquireDirect()!!; val runtime = Any()
            val failure = AtomicReference<Throwable?>()
            if (entering) admission.prepare(claim, TaskMethod.DIRECT, "task", "operation") { true to runtime }
            val worker = thread {
                try {
                    if (entering) admission.enter(claim, "task", "operation", runtime) {
                        entered.countDown(); check(releaseAction.await(5, TimeUnit.SECONDS)); true
                    } else admission.prepare(claim, TaskMethod.DIRECT, "task", "operation") {
                        entered.countDown(); check(releaseAction.await(5, TimeUnit.SECONDS)); true to runtime
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(1, admission.snapshot().directCount)
                assertFalse(admission.release(claim))
                assertNull(admission.tryAcquireExclusive())
                val independent = admission.tryAcquireDirect()!!
                assertTrue(admission.release(independent))
                assertNull(admission.prepare(claim, TaskMethod.DIRECT, "other", "other") { true to Any() })
            } finally { releaseAction.countDown() }
            worker.join(5000); assertFalse(worker.isAlive); failure.get()?.let { throw AssertionError(it) }
            if (entering) admission.exit(claim, runtime)
            assertTrue(admission.release(claim))
        }
    }

    @Test fun failedPreparationOrEntryReleasesOnlyItsTransientMarker() {
        val admission = SaveExecutionAdmission(maxArtifactBytes = 100)
        val claim = admission.tryAcquireDirect()!!; val runtime = Any()
        assertThrows(IllegalStateException::class.java) {
            admission.prepare<Boolean>(claim, TaskMethod.DIRECT, "task", "operation") { throw IllegalStateException() }
        }
        assertTrue(admission.owns(claim))
        assertEquals(true, admission.prepare(claim, TaskMethod.DIRECT, "task", "operation") { true to runtime })
        assertThrows(IllegalStateException::class.java) {
            admission.enter<Boolean>(claim, "task", "operation", runtime) { throw IllegalStateException() }
        }
        assertTrue(admission.owns(claim)); assertTrue(admission.release(claim))
    }

    @Test fun explicitProcessPoisonRemainsStickyAfterEveryTemporaryGateClears() {
        var draining = false
        val admission = SaveExecutionAdmission(maxArtifactBytes = 100, resourcesUnreleased = { draining })
        val claim = admission.tryAcquireDirect()!!
        draining = true; admission.markRecoveryRequired(); draining = false
        assertTrue(admission.recoveryRequired); assertTrue(admission.snapshot().recoveryRequired)
        assertFalse(admission.release(claim)); assertTrue(admission.owns(claim))
        assertNull(admission.tryAcquireExclusive()); assertNull(admission.tryAcquireDirect())
    }

}
