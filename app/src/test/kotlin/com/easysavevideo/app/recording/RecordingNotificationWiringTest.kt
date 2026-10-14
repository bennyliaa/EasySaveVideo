package com.easysavevideo.app.recording

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Real-source wiring contracts, not Android Service/NotificationManager execution. */
class RecordingNotificationWiringTest {
    private fun source(path: String) = String(Files.readAllBytes(Paths.get("src/main/kotlin/com/easysavevideo/app", path)), Charsets.UTF_8)
    private val service get() = source("recording/RecordingForegroundService.kt")
    private val runtime get() = source("save/AndroidSaveRuntime.kt")
    private val segment get() = source("recording/AndroidSegmentRecordingRuntime.kt")
    @Test fun allThreeStatusProducersAreTypedAndKeepTheirExistingMainThreadDispatch() {
        assertEquals(2, Regex("\\.showStatus\\(").findAll(runtime).count())
        assertEquals(1, Regex("\\.showStatus\\(").findAll(segment).count())
        val native = runtime.substringAfter("recorder = AndroidRecordSavePort").substringBefore("coordinator = SaveCoordinator")
        assertTrue(native.indexOf("main.post {") < native.indexOf("val label = recordingNotificationStatus(next.state)"))
        assertTrue(native.contains("recordingService?.showStatus(id, label)"))
        val recovery = runtime.substringAfter("if (recorder.nativeOccupied)").substringBefore("else finishRecordingOwnerIfReleased")
        assertTrue(recovery.contains("service.showStatus(id, RecordingNotificationStatus.RECOVERY_REQUIRED)"))
        assertTrue(segment.contains("main.post { onProgress(job, progress) }"))
        assertTrue(segment.contains("recordingSegmentNotificationStatus(phase)?.let { job.service?.showStatus(job.descriptor.sessionId, it) }"))
        assertFalse(segment.contains("recordingSegmentsPhaseLabel(phase)"))
    }
    @Test fun segmentProducerStillEmitsOnlyFiveCapturePhasesAndDoesNotAnnouncePublication() {
        val producer = segment.substringAfter("private fun onProgress(").substringBefore("private fun updateRows(")
        assertTrue(producer.contains("if (active !== job || job.fence.isRevoked) return"))
        assertTrue(producer.contains("if (job.fence.stopReason != null) RecordingSegmentsPhase.STOPPING"))
        val mapping = producer.substringAfter("val phase =").substringBefore("val rows =")
        val emitted = Regex("RecordingSegmentsPhase\\.([A-Z_]+)").findAll(mapping).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("PREPARING", "RECORDING", "PAUSED", "RESUMING", "STOPPING"), emitted)
        assertTrue(producer.contains("if (phase == RecordingSegmentsPhase.STOPPING) armFinalizationDeadline(job)"))
        assertFalse(producer.contains("SUCCEEDED"));assertFalse(producer.contains("startForeground"))
    }
    @Test fun displayPromotionIsAfterForegroundSuccessAndBeforeExistingReadyCallback() {
        val start = service.substringAfter("override fun onStartCommand(").substringBefore("internal fun showStatus(")
        val promote = start.indexOf("startForeground(NOTIFICATION_ID")
        val display = start.indexOf("notificationDisplay.promoted(id, RecordingNotificationStatus.INITIAL_PREPARING)")
        val ready = start.indexOf("runtime.onRecordingServiceReady(this, id)")
        assertTrue(promote >= 0 && promote < display && display < ready)
        assertTrue(start.contains("!runtime.hasPendingRecording(id)"))
        assertTrue(start.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"))
        assertEquals(1, Regex("startForeground\\(").findAll(service).count())
        assertEquals(1, Regex("startForegroundService\\(").findAll(service).count())
        assertTrue(start.contains("runtime.recordingStartRejected(id)\n            finishSession(id)"))
    }
    @Test fun stopActionProtocolAndScreenOffSafetyRemainSessionBound() {
        for (required in listOf("if (requestId == id) runtime.stopRecording(id)", "return START_NOT_STICKY",
            "stop.identifier = id", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT",
            "Intent.ACTION_SCREEN_OFF", "runtime.safetyStopRecording(it, RecordingStopReason.SYSTEM_STOPPED)",
            "RECEIVER_NOT_EXPORTED", "private const val CHANNEL = \"esv_recording\"", "private const val NOTIFICATION_ID = 4301",
            "private const val ACTION_START = \"com.easysavevideo.app.recording.START\"",
            "private const val ACTION_STOP = \"com.easysavevideo.app.recording.STOP\"", "private const val REQUEST_ID = \"request_id\""))
            assertTrue(required, service.contains(required))
        assertTrue(service.contains("setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)"))
    }
    @Test fun finishAndDestroyRetireBeforeCallbacksAndLateStatusCannotReviveDisplay() {
        val finish = service.substringAfter("fun finishSession(").substringBefore("override fun onDestroy()")
        assertTrue(finish.contains("if (requestId != id || finishing) return"))
        assertTrue(finish.indexOf("finishing = true") < finish.indexOf("notificationDisplay.retire()"))
        assertTrue(finish.indexOf("notificationDisplay.retire()") < finish.indexOf("stopForeground(STOP_FOREGROUND_REMOVE)"))
        val destroy = service.substringAfter("override fun onDestroy()").substringBefore("private fun notification(")
        assertTrue(destroy.indexOf("notificationDisplay.retire()") < destroy.indexOf("runtime::onRecordingServiceLost"))
        assertTrue(destroy.contains("runtime.onRecordingServiceDestroyed(this, it)"))
        val status = service.substringAfter("internal fun showStatus(").substringBefore("/** Repaint")
        assertTrue(status.contains("status: RecordingNotificationStatus"))
        assertTrue(status.contains("if (requestId != id || finishing) return"))
        assertTrue(status.contains("notificationDisplay.update(id, status) ?: return"))
    }
    @Test fun languageRefreshOnlyRepaintsCurrentPromotedSessionAndExistingChannel() {
        val refresh = service.substringAfter("internal fun refreshNotificationText()").substringBefore("override fun onConfigurationChanged(")
        for (required in listOf("val id = requestId ?: return", "if (finishing) return", "notificationDisplay.current(id) ?: return",
            "manager.getNotificationChannel(CHANNEL)", "channel.name = name", "manager.createNotificationChannel(channel)",
            "manager.notify(NOTIFICATION_ID, notification(id, snapshot.status, localized))", "catch (_: RuntimeException)"))
            assertTrue(required, refresh.contains(required))
        for (forbidden in listOf("startForeground", "stopForeground", "stopSelf", "finishSession", "runtime.stop", "runtime.onRecording",
            "MediaProjection", "ACTION_START", "NotificationChannel(CHANNEL,", "setImportance", "setSound", "enableVibration", "deleteNotificationChannel", "INITIAL_PREPARING"))
            assertFalse(forbidden, refresh.contains(forbidden))
    }
    @Test fun notificationContentIsResolvedAtRenderTimeIncludingConfigurationUpdates() {
        assertTrue(service.contains("super.onConfigurationChanged(newConfig)\n        refreshNotificationText()"))
        assertTrue(service.contains("esvLocalizedContext(this, resources.configuration,\n        runtime.recordingNotificationLanguage)"))
        val render = service.substringAfter("private fun notification(").substringBefore("companion object")
        for (required in listOf("status: RecordingNotificationStatus", "localized.getString(R.string.recording_notification_title)",
            "localized.getString(recordingNotificationStatusResource(status))", "localized.getString(R.string.recording_notification_stop)"))
            assertTrue(required, render.contains(required))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(service))
        assertFalse(service.contains("status: String"))
    }
    @Test fun localeHelperIsSharedStatelessAndDoesNotCreateAnotherPreferenceOwner() {
        val helper = source("ui/shell/EsvLocaleContext.kt")
        val compose = source("ui/shell/EsvLocaleHost.kt")
        assertTrue(helper.contains("apiLevel >= 33 || language == AppLanguage.SYSTEM"))
        assertTrue(helper.contains("esvLocaleOverrideTag(apiLevel, language) ?: return base"))
        assertTrue(helper.contains("base.createConfigurationContext(Configuration(configuration).apply"))
        assertTrue(compose.contains("esvLocalizedContext(base, configuration, language)"))
        assertTrue(compose.contains("Build.VERSION.SDK_INT >= 33 || language == AppLanguage.SYSTEM"))
        for (forbidden in listOf("getSharedPreferences", "Locale.setDefault", "applicationLocales =", "setApplicationLocales")) {
            assertFalse(forbidden, helper.contains(forbidden));assertFalse(forbidden, service.contains(forbidden))
        }
        assertFalse(helper.contains("startForeground"))
    }
    @Test fun languageMirrorUsesOnlyAcceptedEditAndFinalMergedStartupValue() {
        val main = source("MainActivity.kt")
        assertEquals(2, Regex("saves\\.updateRecordingNotificationLanguage\\(").findAll(main).count())
        assertTrue(main.contains("settingsChanged = true; settings = value\n                            if (edit is SettingsEdit.Language) saves.updateRecordingNotificationLanguage(settings.appearance.language)"))
        val loaded = main.substringAfter("persistence.start { loaded, checkpoint, failed ->").substringBefore("@Suppress(\"DEPRECATION\")")
        assertTrue(loaded.contains("if (!settingsChanged) { settings = loaded }"))
        assertTrue(loaded.indexOf("mergeLoadedBrowserSettings(settings, loaded, persistence.settingsLoaded)") < loaded.indexOf("browser.applySettings(settings.browser)"))
        assertTrue(loaded.indexOf("browser.applySettings(settings.browser)") < loaded.indexOf("saves.updateRecordingNotificationLanguage"))
        assertTrue(loaded.indexOf("settings.copy(save = saves.initializeSaveSettings") < loaded.indexOf("saves.updateRecordingNotificationLanguage"))
        assertTrue(loaded.contains("saves.updateRecordingNotificationLanguage(settings.appearance.language)\n            settingsReady = true"))
        val mirror = runtime.substringAfter("internal fun updateRecordingNotificationLanguage(").substringBefore("var recordingSnapshot")
        assertTrue(mirror.contains("requireMain()"));assertTrue(mirror.contains("if (notificationLanguage.update(language)) recordingService?.refreshNotificationText()"))
        for (forbidden in listOf("prepareRecording", "submit(", "command(", "start(", "saveSettings", "LocaleManager")) assertFalse(forbidden, mirror.contains(forbidden))
        assertTrue(runtime.contains("private var recordingService: RecordingForegroundService? = null\n        set(value) { field = value; publishMediaTaskActivity() }"))
    }
    @Test fun contractInputsIncludeTheRealProducerRendererAndLocaleSources() {
        val build = String(Files.readAllBytes(Paths.get("build.gradle.kts")), Charsets.UTF_8)
        for (name in listOf("RecordingNotificationStatus.kt", "RecordingNotificationDisplay.kt", "RecordingForegroundService.kt",
            "AndroidSegmentRecordingRuntime.kt", "AndroidSaveRuntime.kt", "MainActivity.kt", "EsvLocaleContext.kt", "EsvLocaleHost.kt", "recording_notification.xml"))
            assertTrue(name, build.contains(name))
        assertFalse(service.contains("getStringExtra(\"status"))
    }
}
