package com.easysavevideo.app.recording

import com.easysavevideo.app.browser.privacy.PrivateCaptureDisposition
import com.easysavevideo.recording.*
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingAudioChoice
import org.junit.Assert.*
import org.junit.Test

class ContinuousRecordingOwnerTest {
    private fun owner() = ContinuousRecordingOwner(CaptureDescriptor("basic", 1, RecordingAudioChoice.NONE), Any())
    @Test fun reservedOwnerCannotExposePixelsUntilAcceptedSubmission() {
        val owner = owner(); assertFalse(owner.authorizes(owner.authorization))
        owner.submitted = true; assertTrue(owner.authorizes(owner.authorization)); assertFalse(owner.nativeStarted)
    }
    @Test fun equalFieldsCannotForgeAnAuthorization() {
        val owner = owner(); owner.submitted = true; val auth = owner.authorization
        val forged = ContinuousRecordingAuthorization(auth.sessionId, auth.generation, auth.ownerIdentity, auth.descriptor)
        assertFalse(owner.authorizes(forged)); assertTrue(owner.authorizes(auth))
    }
    @Test fun ordinaryAndPrivateBasicShareSameOwnerAndDoNotStoreInitialSource() {
        val owner = owner(); owner.submitted = true
        assertTrue(owner.authorizes(owner.authorization))
        owner.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
            it.isAccessible = true; assertFalse(it.name, it.get(owner) is Function<*>)
            assertFalse(it.name.contains("target", true) || it.name.contains("profile", true) || it.name.contains("activity", true))
        }
    }
    @Test fun stopRevokesPixelsButKeepsExactControlIdentityAndNativeObligation() {
        val owner = owner(); owner.submitted = true; val auth = owner.authorization
        assertTrue(owner.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT))
        assertFalse(owner.authorizes(auth)); assertSame(auth, owner.authorization)
        assertFalse(owner.nativeDrained); assertNull(owner.terminal); assertFalse(owner.discardRequested)
    }
    @Test fun submittedBeforeCoordinatorRegistrationDiscardCannotBeDowngraded() {
        val owner = owner(); owner.submitted = true
        owner.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
        owner.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        assertTrue(owner.discardRequested); assertFalse(owner.authorizes(owner.authorization))
    }
    @Test fun nativeDrainDoesNotMeanOutputTerminalOrDisableLateDiscard() {
        val owner = owner(); owner.submitted = true; owner.nativeDrained = true
        assertNull(owner.terminal); assertTrue(owner.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT))
        assertTrue(owner.discardRequested)
    }
    @Test fun succeededPublicationWinnerRejectsDelayedDiscard() {
        val owner = owner(); owner.submitted = true; owner.nativeDrained = true; owner.terminal = ContinuousRecordingPhase.SUCCEEDED
        assertFalse(owner.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)); assertFalse(owner.discardRequested)
        assertEquals(ContinuousRecordingPhase.SUCCEEDED, owner.terminal)
    }
    @Test fun uncertaintyNeverAuthorizesPixelsOrClaimsDrain() {
        val owner = owner(); owner.submitted = true; owner.releaseUnconfirmed = true
        assertFalse(owner.authorizes(owner.authorization)); assertFalse(owner.nativeDrained)
    }
    @Test fun nativeSystemAndBudgetStopsRevokePixelsBeforeActualReleaseWithoutCancellingOutput() {
        for (reason in listOf(RecordingStopReason.SYSTEM_STOPPED, RecordingStopReason.SESSION_LIMIT,
                RecordingStopReason.BYTE_LIMIT, RecordingStopReason.STORAGE_LOW)) {
            val owner = owner(); owner.submitted = true; owner.nativeStarted = true
            val native = RecordingSnapshot(RecordingRequest("basic", "basic", owner.descriptor),
                RecordingState.STOPPING, 2, RecordingAudioStatus.NOT_REQUESTED, reason, null,
                resourceOccupied = true, publicationAllowed = false, outcome = null)
            owner.observeNativeSnapshot(native)
            assertFalse(owner.authorizes(owner.authorization)); assertFalse(owner.nativeDrained)
            assertFalse(owner.discardRequested); assertNull(owner.terminal)
            val ui = owner.snapshot()
            assertEquals(ContinuousRecordingPhase.STOPPING, ui.phase); assertTrue(ui.canDiscard)
            assertFalse(ui.nativeDrained); assertSame(owner.authorization, ui.authorization)
        }
    }
    @Test fun uncertainNativeReleaseRemainsBlockedWhenOutputTaskFails() {
        val owner = owner(); owner.submitted = true; owner.releaseUnconfirmed = true
        val ui = owner.snapshot(ContinuousRecordingPhase.FAILED)
        assertEquals(ContinuousRecordingPhase.FAILED, owner.terminal)
        assertEquals(ContinuousRecordingPhase.BLOCKED, ui.phase); assertFalse(ui.nativeDrained)
        assertFalse(ui.canDiscard); assertFalse(owner.authorizes(ui.authorization))
    }
    @Test fun staleNativeStopCannotStopReplacementDescriptor() {
        val owner = owner(); owner.submitted = true
        val stale = RecordingSnapshot(RecordingRequest("basic", "basic", owner.descriptor.copy(generation = 2)),
            RecordingState.FINALIZING, 9, RecordingAudioStatus.NOT_REQUESTED, RecordingStopReason.SYSTEM_STOPPED,
            null, true, false, null)
        owner.observeNativeSnapshot(stale)
        assertTrue(owner.authorizes(owner.authorization)); assertFalse(owner.stopRequested)
    }


    @Test fun stoppedAndUncertainIndependentOwnerPermitSecureCompatibilityWithoutPixelOrDrainAuthority() {
        for (uncertain in listOf(false, true)) {
            val owner = owner(); owner.submitted = true
            owner.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            owner.releaseUnconfirmed = uncertain
            val snapshot = owner.snapshot()
            assertTrue(owner.isIndependentSubmitted(snapshot))
            assertFalse(owner.authorizes(snapshot.authorization)); assertFalse(owner.nativeDrained)
            val foreign = owner(); foreign.submitted = true
            assertFalse(foreign.isIndependentSubmitted(snapshot))
            assertFalse(owner.isIndependentSubmitted(null))
        }
    }
    @Test fun unsubmittedOwnerCannotUseSnapshotFieldsToBypassSecureEntrySetupGate() {
        val owner = owner()
        assertFalse(owner.isIndependentSubmitted(owner.snapshot()))
    }

    @Test fun screenOffDoesNotStopExactSubmittedBasicButCannotBypassAnotherOrUnsubmittedSession() {
        val owner = owner()
        assertFalse(owner.ignoresScreenOff("basic"))
        owner.submitted = true
        assertTrue(owner.ignoresScreenOff("basic"))
        assertFalse(owner.ignoresScreenOff("foreign"))
        owner.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
        assertTrue(owner.ignoresScreenOff("basic"))
        assertFalse(owner.authorizes(owner.authorization))
    }
}
