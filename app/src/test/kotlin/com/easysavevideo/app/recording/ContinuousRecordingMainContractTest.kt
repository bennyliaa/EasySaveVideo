package com.easysavevideo.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Actual composition wiring contract; not Android lifecycle or pixel execution. */
class ContinuousRecordingMainContractTest {
    private val main get() = File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
    @Test fun exactRuntimePixelAuthorizationIsSeparateFromTaskControlAndNativeDrain() {
        assertTrue(main.contains("isContinuousAuthorized = saves::isContinuousRecordingAuthorized"))
        assertTrue(main.contains("continuousCaptureAuthorized = { saves.continuousRecordingAuthorization != null }"))
        assertTrue(main.contains("independentRecordingOwner = { saves.hasIndependentSubmittedRecordingOwner }"))
        assertTrue(main.contains("captureExceptionInactive = { !privateRecordingSurface.ownsCapturePresentation }"))
        assertTrue(main.contains("privateRecording.captureSourceDrained && saves.recordingSourceObligationsDrained()"))
    }
    @Test fun replayedRuntimeListenerAttachesOnlyCurrentAuthorizationAndIsRemovedOnDestroy() {
        val listener = main.substringAfter("continuousRecordingSubscription = saves.addContinuousRecordingListener").substringBefore("appComposeView.setContent")
        assertTrue(listener.contains("val authorization = saves.continuousRecordingAuthorization"))
        assertTrue(listener.contains("if (authorization != null) privateRecordingSurface.attachContinuous(authorization)"))
        assertTrue(listener.contains("else privateRecordingSurface.detachContinuous()"))
        assertFalse(listener.contains("compositionApplied("))
        val destroy = main.substringAfter("override fun onDestroy()")
        val remove = destroy.indexOf("continuousRecordingSubscription?.close()")
        val bridge = destroy.indexOf("privateRecording.close()")
        assertTrue(remove >= 0); assertTrue(bridge >= 0); assertTrue(remove < bridge)
        assertFalse(destroy.contains("stopPrivateRecording("))
    }
    @Test fun controlsAreOutsideTheBrowserRouteAndNavigationDoesNotStopCapture() {
        assertEquals(1, Regex(Regex.escape("RecordingSessionControls(recordingUi)")).findAll(main).count())
        assertFalse(main.contains("privateRecording = recordingUi"))
        val controls = main.indexOf("RecordingSessionControls(recordingUi)")
        val child = main.indexOf("if (!browserChild) SideEffect")
        assertTrue(child >= 0); assertTrue(controls > child)
        val transition = main.substringAfter("private fun protectPrivateTransition()").substringBefore("private fun protectBeforePresentationMove")
        assertTrue(transition.contains("protectForPresentationTransition()"))
        assertFalse(transition.contains("protectAndStop()"))
    }
    @Test fun ordinaryStartedCaptureMayEnterPrivateUsingLiveRuntimeAuthority() {
        val entry = main.substringAfter("override fun canEnterPrivateMode()").substringBefore("override fun protectForPresentationTransition")
        assertTrue(entry.contains("!recordingConsent.requestInFlight"))
        assertTrue(entry.contains("saves.hasIndependentSubmittedRecordingOwner || captureSourcesDrained()"))
        assertTrue(entry.contains("!privateRecording.hasSetupOwner"))
        assertTrue(main.contains("PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE"))
    }
}
