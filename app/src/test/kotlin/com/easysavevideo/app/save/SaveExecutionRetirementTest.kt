package com.easysavevideo.app.save

import com.easysavevideo.save.SaveExecutionAdmission
import org.junit.Assert.*
import org.junit.Test

class SaveExecutionRetirementTest {
    private class Fixture {
        var draining=false;var releasedBatches=0;var retired=0
        val admission=SaveExecutionAdmission(2,100,{1000},{draining},onLastDirectReleased={releasedBatches++})
        val retirement=SaveExecutionRetirement(admission::owns,{draining},admission::release){retired++}
    }
    @Test fun completionOfAStillLeavesBClaimed() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;val b=f.admission.tryAcquireDirect()!!
        f.retirement.request(a);assertEquals(1,f.admission.snapshot().directCount);assertTrue(f.admission.owns(b));assertEquals(0,f.releasedBatches)
        f.retirement.request(b);assertEquals(0,f.admission.snapshot().directCount);assertEquals(1,f.releasedBatches)
    }
    @Test fun futureCompletionWhileNativeDrainsDoesNotReleaseBatch() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.draining=true
        f.retirement.request(a);repeat(3){f.retirement.retry()}
        assertTrue(f.admission.owns(a));assertEquals(0,f.retired);assertEquals(0,f.releasedBatches)
    }
    @Test fun actualNativeReleaseAllowsPreviouslyCompletedClaimToRetire() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.draining=true;f.retirement.request(a)
        f.draining=false;f.retirement.retry();assertFalse(f.admission.owns(a));assertEquals(1,f.retired);assertEquals(1,f.releasedBatches)
    }
    @Test fun permanentPoisonKeepsCompletedClaims() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.admission.markRecoveryRequired()
        f.retirement.request(a);f.retirement.retry();assertTrue(f.admission.owns(a));assertEquals(0,f.releasedBatches)
    }
    @Test fun duplicateRetirementCannotReleaseLaterOwner() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.retirement.request(a)
        val b=f.admission.tryAcquireDirect()!!;f.retirement.request(a);f.retirement.retry()
        assertTrue(f.admission.owns(b));assertEquals(1,f.retired);assertEquals(1,f.releasedBatches)
    }
    @Test fun cancelledWaitingVerifierLeavesOtherClaimUntilItFinishes() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;val b=f.admission.tryAcquireDirect()!!
        f.retirement.request(b);assertTrue(f.admission.owns(a));assertEquals(0,f.releasedBatches)
        f.retirement.request(a);assertEquals(1,f.releasedBatches)
    }
    @Test fun unconfirmedReleaseBlocksNewClaimUntilActualConfirmation() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.draining=true;f.retirement.request(a)
        assertNull(f.admission.tryAcquireDirect());f.draining=false;f.retirement.retry()
        assertNotNull(f.admission.tryAcquireExclusive())
    }
    @Test fun retirementNeverStartsAnotherTask() {
        val f=Fixture();val a=f.admission.tryAcquireDirect()!!;f.retirement.request(a);f.retirement.retry()
        assertEquals(0,f.admission.snapshot().totalCount)
    }
    @Test fun lateRetirementCallbackCannotClearFreshExclusiveOwner() {
        val admission=SaveExecutionAdmission(maxArtifactBytes=100)
        val old=admission.tryAcquireExclusive()!!
        val owner=java.util.concurrent.atomic.AtomicReference<SaveExecutionAdmission.Claim?>(old)
        var fresh:SaveExecutionAdmission.Claim?=null
        val retirement=SaveExecutionRetirement(admission::owns,{false}, { claim ->
            val released=admission.release(claim)
            if(released) { fresh=admission.tryAcquireExclusive()!!;owner.set(fresh) }
            released
        }, { claim -> owner.compareAndSet(claim,null);Unit })
        retirement.request(old)
        assertSame(fresh,owner.get());assertTrue(admission.owns(fresh!!))
    }

}
