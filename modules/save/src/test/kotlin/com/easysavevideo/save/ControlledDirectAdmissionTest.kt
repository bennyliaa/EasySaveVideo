package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ControlledDirectAdmissionTest {
    @Test fun movBatchRequiresTwoExtentsForFirstAndFourForSecondBeforeNativeClaim() {
        var free = 199L; var nativeClaims = 0; var probes = 0
        val admission = SaveExecutionAdmission(2, 100, { probes++; free },
            onFirstDirect = { nativeClaims++; true }, movCapable = true)
        assertNull(admission.tryAcquireDirect()); assertEquals(0, nativeClaims)
        free = 200; val first = admission.tryAcquireDirect()!!
        assertEquals(1, nativeClaims); assertNull(admission.tryAcquireDirect())
        free = 399; assertNull(admission.tryAcquireDirect())
        free = 400; val second = admission.tryAcquireDirect()!!
        assertEquals(1, nativeClaims); assertEquals(5, probes)
        assertTrue(admission.release(first)); assertTrue(admission.release(second))
    }
    @Test fun movFirstSpaceProbeDoesNotHoldAdmissionMonitorAndVersionChangeRejectsIt() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var claims = 0
        val admission = SaveExecutionAdmission(2, 100, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 1000
        }, onFirstDirect = { claims++; true }, movCapable = true)
        val result = AtomicReference<SaveExecutionAdmission.Claim?>()
        val worker = thread { result.set(admission.tryAcquireDirect()) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(0, admission.snapshot().directCount)
            admission.updateMaxDirectTasks(1)
        } finally { release.countDown() }
        worker.join(5000); assertFalse(worker.isAlive); assertNull(result.get()); assertEquals(0, claims)
    }
    @Test fun enteredCellCannotBeCapturedForReservedPreparedForeignOrExitedClaims() {
        val admission = SaveExecutionAdmission(maxArtifactBytes = 100)
        val other = SaveExecutionAdmission(maxArtifactBytes = 100)
        val claim = admission.tryAcquireDirect()!!; val runtime = Any()
        assertNull(admission.captureEntered(claim, runtime))
        admission.prepare(claim, TaskMethod.DIRECT, "task", "operation") { true to runtime }
        assertNull(admission.captureEntered(claim, runtime))
        assertNull(other.captureEntered(claim, runtime))
        admission.enter(claim, "task", "operation", runtime) { true }
        val cell = admission.captureEntered(claim, runtime)!!
        cell.requireLive()
        assertNull(admission.captureEntered(claim, Any()))
        assertThrows(SaveFailure::class.java) { cell.assertAdmission(other) }
        admission.exit(claim, runtime)
        assertThrows(SaveFailure::class.java) { cell.requireLive() }
        assertNull(admission.captureEntered(claim, runtime))
        assertTrue(admission.release(claim))
        assertThrows(SaveFailure::class.java) { cell.requireLive() }
    }
    @Test fun checkedMovExtentArithmeticRefusesOverflowBeforeNativeClaim() {
        var claims = 0
        val admission = SaveExecutionAdmission(2, Long.MAX_VALUE, { Long.MAX_VALUE },
            onFirstDirect = { claims++; true }, movCapable = true)
        assertNull(admission.tryAcquireDirect()); assertEquals(0, claims)
    }
}
