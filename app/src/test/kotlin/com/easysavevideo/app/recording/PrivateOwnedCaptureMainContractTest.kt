package com.easysavevideo.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only; not Activity/window/native or device execution. */
class PrivateOwnedCaptureMainContractTest {
    private val main get() = File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
    @Test fun actualPortUsesExactBridgeReadbackAndRouteWithoutApprovingCandidate() {
        val creation = main.substringAfter("privateRecordingSurface = OwnedPrivateRecordingPresentationPort(").substringBefore("privateRecording = PrivateRecordingBridge")
        assertTrue(creation.contains("routeIdentity = { privateRouteIdentity }"))
        assertTrue(creation.contains("privateRecording.readCaptureWindowOwner(target, presentationId, identity) else null"))
        assertTrue(creation.contains("privateRecording.invalidateCaptureWindowOwner(identity)"))
        assertFalse(creation.contains("LOCK_WEBPAGE_INPUT_CANDIDATE"))
        assertFalse(main.contains("privateRecordingSurface = PendingPrivateRecordingPresentationPort"))
    }
    @Test fun realChildDispatchHasExactRouteAndExclusiveCaptureOwnership() {
        val outer = main.substringAfter("appComposeView.setContent {").substringBefore("EsvAppShell(")
        assertFalse(outer.contains("compositionApplied("))
        val child = main.substringAfter("val childApplied = {").substringBefore("val browserChild")
        assertTrue(child.contains("if (routeOwnsPresentation())"))
        assertTrue(child.contains("if (privateRecordingSurface.ownsCapturePresentation)"))
        assertTrue(child.contains("privateRecordingSurface.compositionApplied(recordingUi)"))
        assertTrue(child.contains("else privatePresentationOwner.compositionApplied(presentation, childApplyTicket)"))
        assertTrue(child.contains("routeOwnsPresentation() && !privateRecordingSurface.ownsCapturePresentation"))
    }
    @Test fun EveryNormalRetryRemainsOutsideOwnedCaptureAndNoLifecycleAckIsInvented() {
        val retries = main.lines().filter { it.contains("privatePresentationOwner.retryPresentation()") }
        assertEquals(4, retries.size)
        retries.forEach { assertTrue(it, it.contains("!privateRecordingSurface.ownsCapturePresentation")) }
        val lifecycle = main.substringAfter("override fun onWindowFocusChanged")
        assertFalse(lifecycle.contains("compositionApplied("))
        assertTrue(main.contains("captureExceptionInactive = { !privateRecordingSurface.ownsCapturePresentation }"))
    }
    @Test fun platformGatesRemainWhileContinuousProductAndLegacyRiskAreAuthorized() {
        val gates = main.substringAfter("privateWindowGuard.setPendingGates(").substringBefore("privateRecordingSurface =")
        listOf("RETAINED_INPUT_CONNECTION", "NATIVE_PRESENTATIONS", "ANDROID_DEVICE_VALIDATION").forEach { assertTrue(gates.contains(it)) }
        val port = File("src/main/kotlin/com/easysavevideo/app/recording/OwnedPrivateRecordingPresentationPort.kt").readText()
        assertTrue(port.contains("PrivateCaptureInteractionPolicy.CONTINUOUS_USER_AUTHORIZED"))
        assertTrue(port.contains("Build.VERSION.SDK_INT >= 31"))
    }
}
