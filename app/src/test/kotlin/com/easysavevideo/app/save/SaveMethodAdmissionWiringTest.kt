package com.easysavevideo.app.save

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; native Android admission/lifecycle remains a device acceptance gate. */
class SaveMethodAdmissionWiringTest {
    private val runtime get()=File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
    @Test fun newTaskSwitchesAreSeparateFromActiveEntitlementChecks() {
        val s=runtime
        assertTrue(s.contains("fun canStartSave(method: TaskMethod): Boolean = saveSettingsOwner.isMethodEnabled(method) && canSave(method)"))
        assertFalse(s.substringAfter("fun canSave(method:").substringBefore("/** Reserves").contains("saveSettingsOwner"))
        assertTrue(s.contains("AndroidRecordSavePort(application, storage, { canSave(TaskMethod.RECORD) }"))
        for((start,end) in listOf("fun pauseRecording(" to "fun resumeRecording(", "fun resumeRecording(" to "fun stopRecording(", "fun stopRecording(" to "internal fun onRecordingScreenOff("))
            assertFalse(s.substringAfter(start).substringBefore(end).contains("canStartSave"))
    }
    @Test fun segmentSwitchIsCheckedBeforeConsentAndNativeStartNotDuringCapture() {
        val s=File("src/main/kotlin/com/easysavevideo/app/recording/AndroidSegmentRecordingRuntime.kt").readText()
        assertTrue(runtime.contains("newRecordingAllowed = { saveSettingsOwner.isMethodEnabled(TaskMethod.RECORD) }"))
        assertTrue(s.substringAfter("fun allowsConsent(").substringBefore("fun prepare(").contains("newRecordingAllowed()"))
        val capture=s.substringAfter("private fun captureAndProcess(job: Job)")
        assertTrue(capture.indexOf("if (!newRecordingAllowed())") < capture.indexOf("job.fence.startNative"))
        assertFalse(s.substringAfter("fun pause(").substringBefore("private fun").contains("newRecordingAllowed()"))
    }
    @Test fun recordingAttributionComesFromExplicitCurrentBrowserCapability() {
        val s=File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
        assertTrue(s.contains("browser.beginEvaluationPage() else null"))
        assertTrue(s.contains("evaluationPage?.let { saves.armRecordingEvaluation(admission, it) }"))
        assertTrue(s.contains("LaunchedEffect(showingRecording) { if (!showingRecording) evaluationRecordingPage = null }"))
        assertTrue(s.contains("else evaluation?.let(saves::abandonRecordingEvaluation)"))
        assertTrue(s.contains("val evaluationPage = evaluationRecordingPage"))
        assertTrue(s.contains("val startEntry = routeEntry"))
        assertEquals(2,Regex("controller.currentBackStackEntry === startEntry").findAll(s).count())
        assertTrue(s.contains("if (evaluationRecordingPage === evaluationPage) evaluationRecordingPage = null"))
        assertFalse(s.contains("candidate.canonicalPageUrl =="))
    }
}
