package com.easysavevideo.app.recording

import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.recording.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Real queue/native-lease seam. These fixtures do not attest Android capture/window behavior. */
class PrivateRecordingNativeBindingTest {
    private class Fixture(audio: RecordingAudioChoice = RecordingAudioChoice.NONE) {
        val descriptor = CaptureDescriptor("private_recording", 1, audio)
        val owner = Any(); val lease = RecordingNativeLease(); val events = mutableListOf<String>()
        val source = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        val binding = PrivateRecordingNativeBinding(descriptor, owner,
            { events += "capture" }, { events += "drain:$it" })
        fun permit(caller: Any = owner, audio: PrivateCaptureAudio =
            if (descriptor.audioChoice == RecordingAudioChoice.NONE) PrivateCaptureAudio.SILENT else PrivateCaptureAudio.DEVICE_AUDIO,
            mode: PrivateCaptureMode = PrivateCaptureMode.BASIC) =
            PrivateCaptureStartPermit(PrivateCaptureDescriptor(audio, mode), caller)
        fun queue(): PrivateCaptureStartPermit {
            val permit = permit(); assertTrue(binding.arm(permit)); assertTrue(binding.registerQueued(lease)); return permit
        }
        fun runOwner(): PrivateCaptureStartPermit {
            val permit = queue(); assertTrue(lease.started()); binding.ownerStarted(); binding.requireStartAllowed(descriptor); return permit
        }
        fun release(confirmed: Boolean) { lease.released(confirmed); binding.ownerReleased(confirmed) }
    }
    private fun expectFailure(error: RecordingError? = null, action: () -> Unit) {
        try { action(); fail("unexpected capture") }
        catch (failure: RecordingFailure) { if (error != null) assertEquals(error, failure.code) }
    }
    @Test fun armingStoresOnlyAndRealQueueConsumesOnce() {
        val f = Fixture(); assertTrue(f.binding.arm(f.permit())); assertTrue(f.binding.isArmed)
        assertFalse(f.binding.arm(f.permit())); assertFalse(f.lease.occupied); assertTrue(f.events.isEmpty())
        expectFailure { f.binding.requireStartAllowed(f.descriptor) }
    }
    @Test fun descriptorAudioAndModeMismatchCannotArm() {
        val f = Fixture()
        assertFalse(f.binding.arm(f.permit(audio = PrivateCaptureAudio.DEVICE_AUDIO)))
        assertFalse(f.binding.arm(f.permit(mode = PrivateCaptureMode.REGION)))
        assertTrue(f.binding.arm(f.permit()))
    }
    @Test fun browserProfileExitBeforeQueueDoesNotRevokeIndependentCapture() {
        val f = Fixture(); assertTrue(f.binding.arm(f.permit())); var browserDrained = false
        f.source.revokeAndDrain { browserDrained = true }
        assertTrue(browserDrained); assertFalse(f.binding.isStopRequested)
        assertTrue(f.binding.registerQueued(f.lease)); assertTrue(f.lease.occupied)
        f.binding.queueRejected(); assertTrue(f.binding.isCaptureDrained)
    }
    @Test fun browserProfileExitDuringCaptureDoesNotWaitForProjection() {
        val f = Fixture(); f.runOwner(); var browserDrained = false
        f.source.revokeAndDrain { browserDrained = true }
        assertTrue(browserDrained); assertFalse(f.binding.isStopRequested)
        f.binding.requireStartAllowed(f.descriptor); assertFalse(f.binding.isCaptureDrained)
        f.binding.nativeCaptureAcquired(); assertEquals(listOf("capture"), f.events); f.release(true)
    }
    @Test fun neverQueuedProofCannotReviveArmedAuthority() {
        val f = Fixture(); assertTrue(f.binding.arm(f.permit()))
        assertTrue(f.binding.closeNeverQueued()); assertTrue(f.binding.isCaptureDrained)
        assertFalse(f.binding.registerQueued(f.lease)); assertFalse(f.binding.arm(f.permit()))
        assertFalse(f.lease.occupied); assertTrue(f.binding.closeNeverQueued()); assertEquals(listOf("drain:true"), f.events)
    }
    @Test fun wrongOwnerRejectedWithoutAcquiringLease() {
        val f = Fixture(); assertTrue(f.binding.arm(f.permit(caller = Any())))
        assertFalse(f.binding.registerQueued(f.lease)); assertFalse(f.lease.occupied)
        assertEquals(listOf("drain:true"), f.events)
    }
    @Test fun failedRegistrationDoesNotReleaseSomeOtherAcquiredNativeLease() {
        val f = Fixture(); assertTrue(f.lease.tryAcquire()); assertTrue(f.binding.arm(f.permit()))
        assertFalse(f.binding.registerQueued(f.lease)); assertTrue(f.binding.isCaptureDrained)
        assertTrue(f.lease.occupied) // This binding never acquired it; global owner remains quarantined.
        assertTrue(f.binding.isStopRequested); f.lease.rejectQueued()
    }
    @Test fun callbackFreeFailureKeepsPartialNativeAcquisitionOwned() {
        val f = Fixture(); val permit = f.permit()
        val result = permit.consumeDeferred(f.owner) { check(f.lease.tryAcquire()); error("partial registration") }
        assertEquals(PrivateCaptureStartPermit.Registration.FAILED, result)
        assertTrue(f.lease.occupied); assertFalse(permit.startedAllowed()); assertTrue(f.events.isEmpty())
        f.lease.rejectQueued()
    }
    @Test fun consumedPermitAndLeaseCannotRegisterTwice() {
        val f = Fixture(); val permit = f.queue()
        assertFalse(f.binding.registerQueued(f.lease)); assertFalse(permit.consumeAndRegister(f.owner) { fail("duplicate") })
        assertFalse(f.binding.closeNeverQueued()); assertTrue(f.lease.occupied)
        f.binding.queueRejected(); assertFalse(f.lease.occupied); assertEquals(listOf("drain:true"), f.events)
    }
    @Test fun revokedPermitBlocksEveryAcquisitionCheckpoint() {
        val f = Fixture(); val permit = f.runOwner(); permit.revoke()
        expectFailure(RecordingError.CAPTURE_EMPTY) { f.binding.requireStartAllowed(f.descriptor) }
        assertTrue(f.binding.isStopRequested); assertFalse(f.binding.discardRequested); f.release(true)
    }
    @Test fun exactDescriptorChecksSessionGenerationProfileAndAudio() {
        listOf<(CaptureDescriptor) -> CaptureDescriptor>(
            { it.copy(generation = 2) }, { it.copy(sessionId = "other") },
            { it.copy(audioChoice = RecordingAudioChoice.INTERNAL) },
            { it.copy(requestedProfile = it.requestedProfile.copy(frameRate = 24)) }
        ).forEach { change -> val f = Fixture(); f.runOwner(); expectFailure { f.binding.requireStartAllowed(change(f.descriptor)) }; f.release(true) }
    }
    @Test fun delayedPreparationThenExplicitStopCannotAcquireAudioOrDisplay() {
        RecordingAudioChoice.entries.forEach { audio ->
            val f = Fixture(audio); f.runOwner()
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); val acquired = AtomicInteger()
            val failed = AtomicReference<Throwable?>()
            val worker = thread { try {
                entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                f.binding.requireStartAllowed(f.descriptor); acquired.incrementAndGet()
            } catch (error: Throwable) { failed.set(error) } }
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); f.binding.requestStop() }
            finally { resume.countDown(); worker.join(5_000) }
            assertFalse(worker.isAlive); assertTrue(failed.get() is RecordingFailure); assertEquals(0, acquired.get()); f.release(true)
        }
    }
    @Test fun stopDuringPermitRegistrationCannotClaimPrematureDrain() {
        val f = Fixture(); val permit = f.permit(); assertTrue(f.binding.arm(permit))
        val started = CountDownLatch(1); val result = AtomicReference<Boolean?>(); lateinit var worker: Thread
        synchronized(permit) {
            worker = thread { started.countDown(); result.set(f.binding.registerQueued(f.lease)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!f.binding.hasUndrainedOwner && System.nanoTime() < until) Thread.yield()
            assertTrue(f.binding.hasUndrainedOwner)
            f.binding.requestStop(); assertFalse(f.binding.closeNeverQueued()); assertTrue(f.events.isEmpty())
        }
        worker.join(5_000); assertFalse(worker.isAlive); assertEquals(false, result.get())
        assertFalse(f.lease.occupied); assertEquals(listOf("drain:true"), f.events)
    }
    @Test fun nativeObservationIsOnceAndStaleEventsDoNotReviveOwner() {
        val f = Fixture(); f.runOwner(); assertFalse(f.binding.isNativeEventCurrent())
        f.binding.nativeCaptureAcquired(); f.binding.nativeCaptureAcquired()
        assertTrue(f.binding.isNativeEventCurrent()); assertEquals(listOf("capture"), f.events)
        f.binding.requestStop(); assertFalse(f.binding.isNativeEventCurrent()); f.release(true)
        f.binding.nativeCaptureAcquired(); assertEquals(listOf("capture", "drain:true"), f.events)
    }
    @Test fun uncertainCleanupCannotRunPositiveWaiterOrBecomeDrainedLater() {
        val f = Fixture(); f.runOwner(); var completed = 0; f.binding.whenDrained { completed++ }
        f.release(false); f.binding.ownerReleased(true); f.binding.whenDrained { completed++ }
        assertTrue(f.lease.occupied); assertTrue(f.lease.releaseUnconfirmed); assertFalse(f.binding.isCaptureDrained)
        assertFalse(f.binding.closeNeverQueued()); assertEquals(0, completed); assertEquals(listOf("drain:false"), f.events)
    }
    @Test fun trueCallbackBeforeActualLeaseReleaseCannotManufactureDrain() {
        val f = Fixture(); f.runOwner(); f.binding.ownerReleased(true)
        assertFalse(f.binding.isCaptureDrained); assertTrue(f.lease.occupied); assertEquals(listOf("drain:false"), f.events)
        f.lease.released(true); f.binding.ownerReleased(true); assertFalse(f.binding.isCaptureDrained)
    }
    @Test fun keepThenDiscardIsMonotonicWithoutFreeingNativeOwnership() {
        val f = Fixture(); f.runOwner(); f.binding.requestStop(); assertFalse(f.binding.discardRequested)
        f.binding.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT); f.binding.requestStop()
        assertTrue(f.binding.discardRequested); assertTrue(f.lease.occupied); assertFalse(f.binding.closeNeverQueued())
        expectFailure(RecordingError.CANCELLED) { f.binding.requireStartAllowed(f.descriptor) }; f.release(true)
    }
    @Test fun observersAndDrainWaitersRunOutsideBindingPermitAndLeaseLocks() {
        val f = Fixture(); val permit = f.runOwner(); var outside = false
        f.binding.whenDrained {
            val acquired = CountDownLatch(1)
            val reader = thread { synchronized(permit) { f.binding.isStopRequested; f.lease.occupied }; acquired.countDown() }
            outside = acquired.await(5, TimeUnit.SECONDS); reader.join(5_000)
        }
        f.release(true); assertTrue(outside)
    }
    @Test fun retainedPermitAndBindingHaveNoPrivateBrowserLeaseOrTargetFields() {
        val f = Fixture(); f.runOwner()
        assertFalse(f.binding.javaClass.declaredFields.any { it.type == PrivateWorkRegistration::class.java || it.type == PrivateCaptureTarget::class.java })
        fun field(name: String) = f.binding.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(f.binding)
        assertNotNull(field("permit")); f.release(true)
        listOf("permit", "nativeLease", "startedObserver", "drainObserver").forEach { assertNull(it, field(it)) }
        assertTrue((field("drainWaiters") as List<*>).isEmpty())
    }
    @Test fun failingWaiterCannotStrandOtherActualDrainObservers() {
        val f = Fixture(); f.runOwner(); var done = 0
        f.binding.whenDrained { error("observer") }; f.binding.whenDrained { done++ }; f.release(true); assertEquals(1, done)
    }
}
