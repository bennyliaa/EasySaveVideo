package com.easysavevideo.app.save

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.app.recording.*
import com.easysavevideo.recording.*
import com.easysavevideo.save.SaveError
import org.junit.Assert.*
import org.junit.Test

class SaveUiPolicyTest {
    @Test fun originRequiresHttpsExactHostAndEffectivePort() {
        assertTrue(AndroidSaveRuntime.sameOrigin("https://EXAMPLE.invalid/watch", "https://example.invalid:443/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("http://example.invalid/watch", "https://example.invalid/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("https://example.invalid/watch", "https://cdn.example.invalid/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("https://example.invalid/watch", "https://example.invalid:444/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("https://example.invalid.evil.test/watch", "https://example.invalid/media.mp4"))
    }
    @Test fun absentMalformedOrCredentialedPageCannotSupplyCookies() {
        assertFalse(AndroidSaveRuntime.sameOrigin(null, "https://example.invalid/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("not a url", "https://example.invalid/media.mp4"))
        assertFalse(AndroidSaveRuntime.sameOrigin("https://user:password@example.invalid/watch", "https://example.invalid/media.mp4"))
    }
    @Test fun streamAacDecoderCannotSilentlyExpandRateOrChannels() {
        assertTrue(StreamAudioFormatPolicy.compatible(48000, 2, 48000, 2))
        assertTrue(StreamAudioFormatPolicy.compatible(22050, 1, 22050, 1))
        assertFalse(StreamAudioFormatPolicy.compatible(24000, 2, 48000, 2))
        assertFalse(StreamAudioFormatPolicy.compatible(48000, 1, 48000, 2))
        assertFalse(StreamAudioFormatPolicy.compatible(48000, 2, -1, 2))
        assertFalse(StreamAudioFormatPolicy.compatible(48000, 8, 48000, 8))
    }
    @Test fun selectionFencesLateCallbacksAndExpiry() {
        var current = true
        val selection = PageSaveSelection(BrowserContextRef("tab", "session"), 4, "https://example.invalid/watch",
            emptyList(), System.currentTimeMillis() + 60_000) { current }
        assertTrue(selection.isCurrent())
        current = false
        assertFalse(selection.isCurrent())
        val expired = PageSaveSelection(BrowserContextRef("tab", "session"), 4, "https://example.invalid/watch",
            emptyList(), 1) { true }
        assertFalse(expired.isCurrent())
        assertFalse(selection.toString().contains("example.invalid"))
    }

    @Test fun everyMethodHasItsOwnLabelAndRecordingNeverPretendsToDownload() {
        assertEquals(TaskMethod.entries.size, TaskMethod.entries.map(::saveMethodLabel).toSet().size)
        assertEquals("③ 录制", saveMethodLabel(TaskMethod.RECORD))
        assertEquals("录制会话处理中", saveExecutionLabel(TaskMethod.RECORD))
        assertFalse(saveArtifactProgressLabel(TaskMethod.RECORD, "1 MiB").contains("已传输"))
        assertTrue(saveArtifactProgressLabel(TaskMethod.RECORD, "1 MiB").contains("尚未保存成功"))
        assertTrue(saveArtifactProgressLabel(TaskMethod.DIRECT, "1 MiB").contains("已传输"))
    }
    @Test fun captureFinishedIsNotPublishedAndEveryCaptureStateHasALabel() {
        RecordingState.entries.forEach { assertTrue(recordingStateLabel(it).isNotBlank()) }
        assertTrue(recordingStateLabel(RecordingState.PREPARING).contains("尚未确认首帧"))
        assertTrue(recordingStateLabel(RecordingState.CAPTURE_FINISHED).contains("尚未确认保存"))
        assertTrue(recordingStateLabel(RecordingState.FINALIZING).contains("释放资源"))
        assertTrue(recordingStateLabel(RecordingState.RESUMING).contains("关键帧"))
    }
    @Test fun safetyControlsDependOnSessionStateNotEntitlement() {
        listOf(RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING)
            .forEach { assertTrue(recordingCanStop(it)); assertTrue(recordingHasActiveSession(it)) }
        listOf(RecordingState.IDLE, RecordingState.CAPTURE_FINISHED, RecordingState.CANCELLED, RecordingState.FAILED)
            .forEach { assertFalse(recordingCanStop(it)); assertFalse(recordingHasActiveSession(it)) }
        assertTrue(recordingHasActiveSession(RecordingState.FINALIZING))
        assertFalse(recordingCanStop(RecordingState.FINALIZING))
    }
    @Test fun audioLabelsNeverPromiseAudibilityFromPermissionOrPcm() {
        RecordingAudioStatus.entries.forEach { assertTrue(recordingAudioStatusLabel(it).isNotBlank()) }
        RecordingAudioOutcome.entries.forEach { assertTrue(recordingAudioOutcomeLabel(it).isNotBlank()) }
        RecordingStopReason.entries.forEach { assertTrue(recordingStopReasonLabel(it).isNotBlank()) }
        RecordingError.entries.forEach { assertTrue(recordingErrorLabel(it).isNotBlank()) }
        assertTrue(recordingAudioStatusLabel(RecordingAudioStatus.PCM_OBSERVED).contains("不保证"))
        assertTrue(recordingAudioOutcomeLabel(RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN).contains("未知"))
        assertTrue(detailedFailureChinese(SaveError.AUDIO_PERMISSION_DENIED).contains("明确选择"))
        assertFalse(detailedFailureChinese(SaveError.UNSUPPORTED_MEDIA).contains("录制和转码不受支持"))
    }
    @Test fun effectiveTimeUsesProvidedMicrosecondsRatherThanWallClock() {
        assertEquals("00:00:00", recordingDurationLabel(-1))
        assertEquals("00:00:50", recordingDurationLabel(50_999_999))
        assertEquals("02:03:04", recordingDurationLabel((2 * 3600L + 3 * 60 + 4) * 1_000_000))
    }
    @Test fun cancelledOrMissingConsentOwnerCannotConsumeALateResult() {
        val pending = PendingRecordingConsent("session-one", RecordingConsentStage.PROJECTION)
        assertTrue(canAcceptRecordingConsent(pending, RecordingConsentStage.PROJECTION))
        assertFalse(canAcceptRecordingConsent(pending, RecordingConsentStage.AUDIO))
        assertFalse(canAcceptRecordingConsent(pending.copy(cancelled = true), RecordingConsentStage.PROJECTION))
        assertFalse(canAcceptRecordingConsent(null, RecordingConsentStage.PROJECTION))
        val awaitingAudio = pending.copy(stage = RecordingConsentStage.AUDIO)
        assertFalse(canAcceptRecordingConsent(awaitingAudio, RecordingConsentStage.PROJECTION))
        assertTrue(canAcceptRecordingConsent(awaitingAudio, RecordingConsentStage.AUDIO))
    }

    @Test fun ephemeralConsentUsesOnlyItsOwnNonAndroidxPlatformCodes() {
        assertTrue(RECORDING_PROJECTION_REQUEST_CODE in 1..65_535)
        assertTrue(RECORDING_AUDIO_REQUEST_CODE in 1..65_535)
        assertNotEquals(RECORDING_PROJECTION_REQUEST_CODE, RECORDING_AUDIO_REQUEST_CODE)
        assertTrue(isRecordingResultCode(RECORDING_PROJECTION_REQUEST_CODE))
        assertTrue(isRecordingResultCode(RECORDING_AUDIO_REQUEST_CODE))
        assertFalse(isRecordingResultCode(65_536))
        assertFalse(isRecordingResultCode(1))
        // Correlation has no Bundle, Intent, token, Activity, or platform-resource field.
        assertEquals(setOf("requestId", "stage", "cancelled", "defaultDisplayOnly"), PendingRecordingConsent::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }
    private fun detailedFailureChinese(error: SaveError): String {
        val id = saveRuntimeFailureNoticeResource(saveRuntimeFailureNotice(error).code)
        val resourceName = com.easysavevideo.app.R.string::class.java.fields.single { it.getInt(null) == id }.name
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/values-zh-rCN/save_runtime_notice.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == resourceName }.textContent
    }

}
