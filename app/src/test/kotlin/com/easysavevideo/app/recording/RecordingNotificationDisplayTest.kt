package com.easysavevideo.app.recording

import com.easysavevideo.app.ui.shell.esvLocaleOverrideTag
import com.easysavevideo.app.ui.shell.SettingsEdit
import com.easysavevideo.core.model.AppLanguage
import com.easysavevideo.core.model.AppSettings
import org.junit.Assert.*
import org.junit.Test

class RecordingNotificationDisplayTest {
    private val initial = RecordingNotificationStatus.INITIAL_PREPARING
    @Test fun unpromotedDisplayHasNoStatusToRefreshOrUpdate() {
        val display = RecordingNotificationDisplay()
        assertNull(display.current("session"))
        RecordingNotificationStatus.entries.forEach { assertNull(display.update("session", it)) }
    }
    @Test fun successfulPromotionExposesOnlyOriginalSessionAndInitialStatus() {
        val display = RecordingNotificationDisplay()
        assertTrue(display.promoted("session", initial))
        assertEquals(RecordingNotificationSnapshot("session", initial), display.current("session"))
        assertNull(display.current("other"))
    }
    @Test fun invalidOrRepeatedPromotionCannotReplaceAnExistingOwner() {
        for (invalid in listOf("", "a b", "é", "x".repeat(129), "../", "line\n")) {
            val display = RecordingNotificationDisplay()
            assertFalse(display.promoted(invalid, initial));assertNull(display.current(invalid))
        }
        val display = RecordingNotificationDisplay()
        assertTrue(display.promoted("valid_1.-", initial))
        assertFalse(display.promoted("other", RecordingNotificationStatus.RECORDING))
        assertFalse(display.promoted("valid_1.-", RecordingNotificationStatus.PAUSED))
        assertEquals(initial, display.current("valid_1.-")?.status)
    }
    @Test fun refreshReadsLatestTypedStatusWithoutRevertingOrFlatteningIt() {
        val display = RecordingNotificationDisplay();display.promoted("session", initial)
        for (status in RecordingNotificationStatus.entries) {
            val update = display.update("session", status)
            repeat(4) { assertSame(update, display.current("session"));assertEquals(status, display.current("session")?.status) }
        }
    }
    @Test fun lateOldIdCannotOverwriteCurrentStatus() {
        val display = RecordingNotificationDisplay();display.promoted("new", initial)
        display.update("new", RecordingNotificationStatus.PAUSED)
        assertNull(display.update("old", RecordingNotificationStatus.RECOVERY_REQUIRED))
        assertNull(display.current("old"));assertEquals(RecordingNotificationStatus.PAUSED, display.current("new")?.status)
    }
    @Test fun finishRetiresStatusAndEveryLateUpdateRefreshOrPromotion() {
        val display = RecordingNotificationDisplay();display.promoted("session", initial);display.retire()
        repeat(3) {
            assertNull(display.current("session"))
            RecordingNotificationStatus.entries.forEach { assertNull(display.update("session", it)) }
            assertFalse(display.promoted("session", initial));assertFalse(display.promoted("later", initial))
            display.retire()
        }
    }
    @Test fun destructionBeforePromotionCannotBeUndoneByALateCallback() {
        val display = RecordingNotificationDisplay();display.retire()
        assertFalse(display.promoted("session", initial));assertNull(display.update("session", initial))
    }
    @Test fun failedExternalRenderingLeavesLatestStatusButDoesNotReviveRetiredState() {
        val display = RecordingNotificationDisplay();display.promoted("session", initial)
        val snapshot = requireNotNull(display.update("session", RecordingNotificationStatus.PAUSED))
        try { renderFailure(snapshot);fail() } catch (_: IllegalStateException) { }
        assertSame(snapshot, display.current("session"))
        display.retire()
        assertNull(display.current("session"));assertNull(display.update("session", initial))
    }
    private fun renderFailure(snapshot: RecordingNotificationSnapshot): Nothing {
        assertEquals(RecordingNotificationStatus.PAUSED, snapshot.status)
        throw IllegalStateException("Synthetic render failure")
    }
    @Test fun separateServiceDisplayInstancesCannotRefreshAnotherSession() {
        val old = RecordingNotificationDisplay();val fresh = RecordingNotificationDisplay()
        old.promoted("old", initial);old.retire();fresh.promoted("new", initial)
        assertNull(old.current("new"));assertNull(fresh.current("old"))
        assertFalse(old.promoted("new", initial));assertEquals(initial, fresh.current("new")?.status)
    }
    @Test fun languageMirrorIsIdempotentAndDoesNotOwnOrFlattenNotificationStatus() {
        val language = RecordingNotificationLanguage();assertEquals(AppLanguage.SYSTEM, language.current)
        assertFalse(language.update(AppLanguage.SYSTEM))
        val display = RecordingNotificationDisplay();display.promoted("session", initial)
        val paused = display.update("session", RecordingNotificationStatus.PAUSED)
        for (next in listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH, AppLanguage.SYSTEM)) {
            assertTrue(language.update(next));assertEquals(next, language.current);assertFalse(language.update(next))
            assertSame(paused, display.current("session"))
        }
        assertEquals("RecordingNotificationSnapshot([redacted])", paused.toString())
    }
    @Test fun localeOverrideUsesOnlyLegacyExplicitChoiceAndPlatformOtherwise() {
        for (api in listOf(31, 32, 33, 34, 36)) for (language in AppLanguage.entries) {
            val expected = if (api < 33 && language != AppLanguage.SYSTEM) language.tag else null
            assertEquals("API $api / $language", expected, esvLocaleOverrideTag(api, language))
        }
    }
    @Test fun finalOwnerValueAfterEarlyEditIsMirroredWithoutAnActivityDefaultReset() {
        val mirror = RecordingNotificationLanguage()
        val loaded = SettingsEdit.Language(AppLanguage.CHINESE).applyTo(AppSettings())
        val edited = SettingsEdit.Language(AppLanguage.ENGLISH).applyTo(AppSettings())
        mirror.update(edited.appearance.language)
        // Mirrors the existing MainActivity settingsChanged precedence, rather than loading preferences here.
        val settingsChanged = true
        val effective = if (settingsChanged) edited else loaded
        assertFalse(mirror.update(effective.appearance.language));assertEquals(AppLanguage.ENGLISH, mirror.current)
        // A new Activity's temporary default is not an owner-delivery event.
        val temporaryActivityDefault = AppSettings()
        assertEquals(AppLanguage.SYSTEM, temporaryActivityDefault.appearance.language)
        assertEquals(AppLanguage.ENGLISH, mirror.current)
        assertTrue(mirror.update(loaded.appearance.language));assertEquals(AppLanguage.CHINESE, mirror.current)
    }
}
