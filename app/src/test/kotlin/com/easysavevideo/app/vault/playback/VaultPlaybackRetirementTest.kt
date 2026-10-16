package com.easysavevideo.app.vault.playback

import com.easysavevideo.app.vault.VaultPlaybackDrain
import org.junit.Assert.*
import org.junit.Test

/** PURE_ACTUAL_SOURCE: production ledger, fixed identities; no Android/native release assertion. */
class VaultPlaybackRetirementTest {
    private fun nativeReturned(ledger: VaultPlaybackRetirement) {
        ledger.revoke()
        ledger.detachReturned()
        ledger.playerReleaseReturned()
        ledger.playbackBarrierReturned()
    }
    private fun finish(ledger: VaultPlaybackRetirement) { ledger.readOwnerRetired(); ledger.loopersJoined() }

    @Test fun releaseReturnAndAnalyticsEquivalentAreNotRendererProof() {
        val ledger = VaultPlaybackRetirement(); val renderer = Any()
        ledger.addRenderer(renderer); ledger.rendererEnabled(renderer)
        nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.resourcesReturned())
        assertEquals(VaultPlaybackDrain.RETIRING, ledger.observe())
        ledger.rendererDisabled(renderer); ledger.rendererReset(renderer); ledger.rendererReleased(renderer)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun neverEnabledRendererNeedsReleaseButNoInventedDisable() {
        val ledger = VaultPlaybackRetirement(); val renderer = Any()
        ledger.addRenderer(renderer); nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.resourcesReturned())
        ledger.rendererReleased(renderer)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun previouslyEnabledRendererCannotUseDefaultReleaseOnly() {
        val ledger = VaultPlaybackRetirement(); val renderer = Any()
        ledger.addRenderer(renderer); ledger.rendererEnabled(renderer)
        ledger.rendererReleased(renderer); nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.resourcesReturned())
        ledger.rendererDisabled(renderer)
        assertFalse(ledger.resourcesReturned())
        ledger.rendererReset(renderer)
        assertTrue(ledger.resourcesReturned())
    }
    @Test fun reenableCreatesFreshDisableAndResetRequirements() {
        val ledger = VaultPlaybackRetirement(); val renderer = Any()
        ledger.addRenderer(renderer); ledger.rendererEnabled(renderer)
        ledger.rendererDisabled(renderer); ledger.rendererReset(renderer)
        ledger.rendererEnabled(renderer); ledger.rendererReleased(renderer)
        nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.resourcesReturned())
        ledger.rendererDisabled(renderer); ledger.rendererReset(renderer)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun actualRendererFailureStaysStickyAfterEveryOtherReturn() {
        val ledger = VaultPlaybackRetirement(); val renderer = Any()
        ledger.addRenderer(renderer); ledger.rendererEnabled(renderer); ledger.failed()
        ledger.rendererDisabled(renderer); ledger.rendererReset(renderer); ledger.rendererReleased(renderer)
        nativeReturned(ledger); finish(ledger)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun playbackOrRevokedReadErrorDoesNotForgeCleanupFailure() {
        val ledger = VaultPlaybackRetirement(); ledger.playbackError()
        assertTrue(ledger.hadPlaybackError())
        nativeReturned(ledger); finish(ledger)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun synchronousReleaseTimeoutCanConvergeOnlyOnLateRealEvidence() {
        val ledger = VaultPlaybackRetirement(); ledger.revoke(); ledger.releaseTimeout()
        ledger.playerReleaseReturned(); ledger.detachReturned(); finish(ledger)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
        ledger.playbackBarrierReturned()
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun detachTimeoutDoesNotBecomeSuccessfulFieldClear() {
        val ledger = VaultPlaybackRetirement(); ledger.detachFailure()
        nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.detachConfirmed())
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun loaderCancelShutdownAndExtractorQueuedAreNotTermination() {
        val ledger = VaultPlaybackRetirement(); val executor = Any()
        ledger.addLoader(executor); nativeReturned(ledger); finish(ledger)
        assertFalse(ledger.resourcesReturned())
        ledger.deadline()
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
        ledger.loaderTerminated(executor, normally = true)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun loaderTaskFailureCannotBeSuccessfulExecutorDeath() {
        val ledger = VaultPlaybackRetirement(); val executor = Any()
        ledger.addLoader(executor); nativeReturned(ledger); finish(ledger)
        ledger.loaderTerminated(executor, normally = false)
        ledger.loaderTerminated(executor, normally = true)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun audioFinallyCallbackWhileInitializedCannotPass() {
        val ledger = VaultPlaybackRetirement(); val output = Any(); val track = Any()
        ledger.addAudio(output, track, initialized = true)
        nativeReturned(ledger); finish(ledger)
        ledger.audioReleased(output, track, uninitialized = false)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
        ledger.audioReleased(output, track, uninitialized = true)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun differentTrackCannotSatisfyExactOutputCallback() {
        val ledger = VaultPlaybackRetirement(); val output = Any(); val track = Any()
        ledger.addAudio(output, track, initialized = true)
        nativeReturned(ledger); finish(ledger)
        ledger.audioReleased(output, Any(), uninitialized = true)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun terminalTrackWithoutInitialInitializedStateCannotPass() {
        val ledger = VaultPlaybackRetirement(); val output = Any(); val track = Any()
        ledger.addAudio(output, track, initialized = false)
        ledger.audioReleased(output, track, uninitialized = true)
        nativeReturned(ledger); finish(ledger)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun everyIndependentAudioOutputRequiresSameObjectTerminalEvent() {
        val ledger = VaultPlaybackRetirement(); val a = Any(); val at = Any(); val b = Any(); val bt = Any()
        ledger.addAudio(a, at, true); ledger.addAudio(b, bt, true)
        nativeReturned(ledger); finish(ledger)
        ledger.audioReleased(a, at, true)
        assertFalse(ledger.resourcesReturned())
        ledger.audioReleased(b, bt, true)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun duplicateTrackAcrossTwoOutputIdentitiesFailsClosed() {
        val ledger = VaultPlaybackRetirement(); val a = Any(); val b = Any(); val track = Any()
        ledger.addAudio(a, track, true); ledger.addAudio(b, track, true)
        ledger.audioReleased(a, track, true); ledger.audioReleased(b, track, true)
        nativeReturned(ledger); finish(ledger)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun acquisitionIsRegisteredBeforeFactoryAndLateAdoptedBySameLedger() {
        val ledger = VaultPlaybackRetirement(); val acquiring = ledger.acquire()
        assertEquals(1, ledger.pendingAcquisitions())
        nativeReturned(ledger); finish(ledger); ledger.deadline()
        assertFalse(ledger.resourcesReturned())
        val exactOutput = Any(); val exactTrack = Any()
        ledger.addAudio(exactOutput, exactTrack, true)
        ledger.adopted(acquiring)
        assertEquals(0, ledger.pendingAcquisitions())
        assertFalse(ledger.resourcesReturned())
        ledger.audioReleased(exactOutput, exactTrack, true)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun factoryThrowWithoutNoAcquisitionProofNeverClearsObligation() {
        val ledger = VaultPlaybackRetirement(); ledger.acquire(); ledger.failed()
        nativeReturned(ledger); finish(ledger)
        assertEquals(1, ledger.pendingAcquisitions())
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, ledger.observe())
    }
    @Test fun readerFileReceiptPrecedesLooperQuitAndJoinReceipt() {
        val ledger = VaultPlaybackRetirement(); nativeReturned(ledger)
        assertTrue(ledger.resourcesReturned())
        assertFalse(ledger.mayJoinLoopers())
        assertEquals(VaultPlaybackDrain.RETIRING, ledger.observe())
        ledger.readOwnerRetired()
        assertTrue(ledger.mayJoinLoopers())
        assertEquals(VaultPlaybackDrain.RETIRING, ledger.observe())
        ledger.loopersJoined()
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun providerReturnIsSeparateFromAudioOutputReturn() {
        val ledger = VaultPlaybackRetirement(); val provider = Any(); val output = Any(); val track = Any()
        ledger.addOwned(provider); ledger.addAudio(output, track, true)
        nativeReturned(ledger); finish(ledger); ledger.audioReleased(output, track, true)
        assertFalse(ledger.resourcesReturned())
        ledger.ownedReleased(provider)
        assertEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
    }
    @Test fun revocationClosesLoaderAndAudioFactoryAdmission() {
        val ledger = VaultPlaybackRetirement()
        assertTrue(ledger.canAcquireAudio()); assertTrue(ledger.canAcquireLoader())
        ledger.revoke()
        assertFalse(ledger.canAcquireAudio()); assertFalse(ledger.canAcquireLoader())
    }
    @Test fun eachNativeResourceIndependentlyBlocksRelease() {
        repeat(5) { missing ->
            val ledger = VaultPlaybackRetirement(); val renderer = Any(); val output = Any(); val track = Any(); val loader = Any(); val provider = Any()
            ledger.addRenderer(renderer); ledger.rendererEnabled(renderer)
            ledger.addAudio(output, track, true); ledger.addLoader(loader); ledger.addOwned(provider)
            ledger.revoke(); ledger.playerReleaseReturned(); ledger.playbackBarrierReturned(); finish(ledger)
            if (missing != 0) { ledger.rendererDisabled(renderer); ledger.rendererReset(renderer); ledger.rendererReleased(renderer) }
            if (missing != 1) ledger.audioReleased(output, track, true)
            if (missing != 2) ledger.loaderTerminated(loader, true)
            if (missing != 3) ledger.detachReturned()
            if (missing != 4) ledger.ownedReleased(provider)
            assertFalse("missing=$missing", ledger.resourcesReturned())
            assertNotEquals(VaultPlaybackDrain.RELEASED, ledger.observe())
        }
    }
}
