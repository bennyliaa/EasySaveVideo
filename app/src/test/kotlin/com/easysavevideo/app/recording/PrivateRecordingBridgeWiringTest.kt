package com.easysavevideo.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only. No Activity, native capture, framework lifecycle or device acceptance. */
class PrivateRecordingBridgeWiringTest {
    private val bridge get() = File("src/main/kotlin/com/easysavevideo/app/recording/PrivateRecordingBridge.kt").readText()
    private val runtime get() = File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
    private val policy get() = File("src/main/kotlin/com/easysavevideo/app/browser/privacy/PrivateCaptureExceptionPolicy.kt").readText()
    private fun before(text: String, first: String, second: String) {
        val a = text.indexOf(first); val b = text.indexOf(second)
        assertTrue(first, a >= 0); assertTrue(second, b >= 0); assertTrue("$first precedes $second", a < b)
    }
    @Test fun nativeObserversAreApplicationOwnedAndNoPrivateSourceLeaseIsRegistered() {
        val create = runtime.substringAfter("internal fun createPrivateRecordingBinding").substringBefore("private val continuousPoll")
        assertTrue(create.contains("PrivateRecordingNativeBinding(descriptor, identity"))
        assertTrue(create.contains("onNativeStarted")); assertTrue(create.contains("onActualDrain"))
        listOf("activity", "browser", "value.target", "value.attempt", "policy", "surface").forEach { assertFalse(it, create.contains(it)) }
        assertTrue(bridge.contains("runtime.createPrivateRecordingBinding(descriptor, value.identity)"))
        listOf("privateCaptureLease", "RECORD_STOP_FINALIZE", "onNativeStarted", "onActualDrain").forEach { assertFalse(it, bridge.contains(it)) }
    }
    @Test fun acceptedSubmissionDropsSetupChainsBeforeDelayedNativeNotification() {
        val handoff = bridge.substringAfter("private fun continueAfterConsent").substringBefore("fun protectForLifecycle")
        before(handoff, "runtime.startPrivateRecording", "policy.retireAfterSubmission(attempt)")
        listOf("value.attempt = null", "value.continuation = null", "value.service = null", "value.binding = null", "this.policy = null; owner = null").forEach { assertTrue(it, handoff.contains(it)) }
        val retire = policy.substringAfter("fun retireAfterSubmission").substringBefore("/** Revocation")
        listOf("cancelTimer()", "current = null", "evidence = null", "surface = null", "targetCurrent = null", "surfaceCurrent = null", "scheduler = null", "onOwnerThread = null", "changed = null").forEach { assertTrue(it, retire.contains(it)) }
        assertFalse(retire.contains("stopPort.stop")); assertFalse(policy.contains("registrationFailed")); assertFalse(policy.contains("notifyRegistrationFailure"))
    }
    @Test fun runtimeWatchdogRemainsAfterActivityPolicyRetires() {
        val body = runtime.substringAfter("private fun acceptContinuousSubmission").substringBefore("private var recordingGeneration")
        assertTrue(body.contains("owner.submitted = true")); assertTrue(body.contains("!owner.nativeStarted"))
        assertTrue(body.contains("continuousRecordingOwner === owner")); assertTrue(body.contains("RecordingStopReason.DEADLINE_EXCEEDED"))
        assertFalse(body.contains("activity.")); assertFalse(body.contains("value.target")); assertFalse(body.contains("policy."))
    }
    @Test fun pausedOrUnfocusedSetupCannotContinueOrOpenInitialSurface() {
        val foreground = bridge.substringAfter("private fun foregroundTarget").substringBefore("private var policy")
        assertTrue(foreground.contains("Lifecycle.State.RESUMED")); assertTrue(foreground.contains("activity.hasWindowFocus()"))
        val continuation = bridge.substringAfter("private fun continueAfterConsent").substringBefore("fun protectForLifecycle")
        assertTrue(continuation.contains("confirmContinue(value.identity, activity, generation, foregroundTarget(value)"))
        before(continuation, "!foregroundTarget(value)", "policy.openForSurface")
        before(continuation, "PrivateRecordingStage.ARMING", "surface.requestEvidence")
    }
    @Test fun ordinaryAndPrivateSubmissionsIssueOneRuntimeAuthorization() {
        val ready = runtime.substringAfter("fun onRecordingServiceReady").substringBefore("internal fun recordingSourceObligationsDrained")
        assertEquals(2, Regex("acceptContinuousSubmission\\(descriptor\\)").findAll(ready).count())
        assertTrue(runtime.contains("ContinuousRecordingOwner(descriptor, binding?.ownerIdentity ?: Any())"))
        assertTrue(runtime.contains("fun requiresDefaultDisplayRecording(id: String): Boolean = segmentRecording?.owns(id) == true"))
    }
    @Test fun lateDiscardSignalsImmediatelyBeforeDurableCommandAndRepeatsAcrossRegistrationGap() {
        val cancel = runtime.substringAfter("fun cancel(taskId:").substringBefore("fun resume(taskId:")
        before(cancel, "coordinator.snapshot(taskId)?.state?.isTerminal", "requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)")
        before(cancel, "coordinator.signalUserCancel(taskId)", "taskCommands.tryCancel(taskId)")
        before(cancel, "coordinator.signalUserCancel(taskId)", "command(mediaWork = true")
        assertTrue(cancel.contains("coordinator.cancel(taskId)"))
        val submit = runtime.substringAfter("private fun submitRecordingOwner").substringBefore("private fun finishRecordingOwnerIfReleased")
        before(submit, "if (continuous.discardRequested)", "coordinator.submit(")
        val afterRegistration = submit.substringAfter("coordinator.submit(")
        before(afterRegistration, "if (continuous.discardRequested)", "SaveWorkerHandoff().launch")
        assertTrue(afterRegistration.contains("coordinator.signalUserCancel(id)")); assertTrue(afterRegistration.contains("coordinator.cancel(id)"))
        assertTrue(runtime.contains("if (owner.discardRequested) cancel(owner.descriptor.sessionId)"))
    }
    @Test fun captureReleaseReadbackNeverPretendsWindowOrOutputCompletion() {
        val body = runtime.substringAfter("internal fun recordingSourceObligationsDrained()").substringBefore("internal val currentSelectionPresentationIdentity")
        listOf("recorder.nativeOccupied", "recorder.isDraining", "recorder.requiresProcessRecovery()", "it.nativeOccupied", "it.requiresRecovery").forEach { assertTrue(it, body.contains(it)) }
        assertTrue(body.contains("return binding.isCaptureDrained")); assertFalse(body.contains("executionBusy()"))
        assertTrue(body.contains("pendingRecording == null && currentRecordingId == null && recordingService == null"))
        assertTrue(bridge.contains("return runtime.recordingSourceObligationsDrained()"))
    }
    @Test fun finishingControlsKeepExactAuthorizationAndDoNotRetargetNewSession() {
        val ui = bridge.substringAfter("private fun publishContinuous").substringBefore("private fun stop(value:")
        listOf("ContinuousRecordingPhase.VERIFYING", "ContinuousRecordingPhase.COMMITTING", "ContinuousRecordingPhase.SUCCEEDED", "snapshot.canDiscard").forEach { assertTrue(it, ui.contains(it)) }
        assertTrue(ui.contains("runtime.continuousRecordingSnapshot?.authorization === authorization"))
        assertTrue(ui.contains("runtime.stopRecording(authorization.sessionId)")); assertTrue(ui.contains("runtime.cancel(authorization.sessionId)"))
        assertFalse(ui.contains("runtime.currentRecordingId"))
    }
    @Test fun activityCloseRemovesObservationAndOnlyCancelsNeverSubmittedSetup() {
        val close = bridge.substringAfter("override fun close()")
        assertTrue(close.contains("owner?.let")); assertTrue(close.contains("observation.close()")); assertTrue(close.contains("surface.detachContinuous()"))
        assertFalse(close.contains("runtime.stopRecording")); assertFalse(close.contains("runtime.cancel("))
        val transition = bridge.substringAfter("fun protectForPresentationTransition()").substringBefore("internal val captureSourceDrained")
        assertTrue(transition.contains("owner?.let")); assertFalse(transition.contains("runtime.stopRecording"))
    }
    @Test fun stopAndDiscardHaveDistinctOutputSemantics() {
        val stop = runtime.substringAfter("internal fun stopPrivateRecording").substringBefore("private fun submitRecordingOwner")
        assertTrue(stop.contains("DISCARD_UNPUBLISHED_OUTPUT) cancel(id)"))
        assertTrue(stop.contains("else if (!binding.isCaptureDrained) recorder.stopAndSave(id)"))
        assertTrue(stop.contains("sourceReleasedWithoutQueue && !privateRecordingSubmitted"))
    }
}
