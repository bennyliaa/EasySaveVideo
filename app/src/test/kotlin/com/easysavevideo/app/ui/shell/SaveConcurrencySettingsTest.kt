package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SaveConcurrencySettingsTest {
    @Test fun concurrencyIntentPreservesEveryOtherFieldAndCurrentWifi() {
        val current = AppSettings(browser = BrowserSettings(false, 4, false, true),
            discovery = DiscoverySettings(4, 75, RepeatPolicy.HIDE_WATCHED, DiscoveryMode.AGGRESSIVE),
            appearance = AppearanceSettings(ThemeMode.DARK, AppLanguage.CHINESE),
            editor = EditorSettings(EditorCompressionMode.SAVE_SPACE), save = SaveSettings(true, 1))
        assertEquals(current.copy(save = SaveSettings(true, 2)), SettingsEdit.DirectConcurrency(2).applyTo(current))
    }
    @Test fun unrelatedIntentsPreserveBothSaveFields() {
        val current = AppSettings(save = SaveSettings(true, 2))
        val edits = listOf(SettingsEdit.Theme(ThemeMode.DARK), SettingsEdit.Language(AppLanguage.ENGLISH),
            SettingsEdit.EditorCompression(EditorCompressionMode.SAVE_SPACE), SettingsEdit.BrowserJavaScript(false),
            SettingsEdit.BrowserThirdPartyCookies(true), SettingsEdit.BrowserRestoreTabs(false), SettingsEdit.BrowserActiveViews(1),
            SettingsEdit.DiscoveryColumns(1), SettingsEdit.DiscoveryPageSize(1), SettingsEdit.DiscoveryPagePresetStep(10),
            SettingsEdit.DiscoveryRepeat(RepeatPolicy.HIDE_WATCHED), SettingsEdit.DiscoveryIntensity(DiscoveryMode.AGGRESSIVE),
            SettingsEdit.DiscoveryDefaults)
        for (edit in edits) assertEquals(current.save, edit.applyTo(current).save)
        assertEquals(SaveSettings(false, 2), SettingsEdit.WifiOnly(false).applyTo(current).save)
    }
    @Test fun invalidCountsAreNotUiIntents() {
        for (count in listOf(Int.MIN_VALUE, 0, 3, Int.MAX_VALUE)) {
            try { SettingsEdit.DirectConcurrency(count); fail("accepted invalid count") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun everyUnloadedBackgroundDeadOrWrongRouteStateRejects() {
        for (ready in listOf(false, true)) for (foreground in listOf(false, true))
            for (alive in listOf(false, true)) for (route in EsvDestination.entries) {
                val result = applyDirectConcurrencySettingsEdit(AppSettings(), SettingsEdit.DirectConcurrency(2),
                    ready, foreground, alive, route)
                assertEquals(ready && foreground && alive && route == EsvDestination.SETTINGS, result != null)
            }
    }
    @Test fun retiredCallbackCannotEditAfterReturningToSameRoute() {
        var enabled = true
        val received = mutableListOf<SettingsEdit.DirectConcurrency>()
        val first = SaveConcurrencySettingsActions({ enabled }) { received += it }
        first.edit(SettingsEdit.DirectConcurrency(2))
        first.close(); enabled = false; enabled = true
        first.edit(SettingsEdit.DirectConcurrency(1))
        assertEquals(listOf(SettingsEdit.DirectConcurrency(2)), received)
        val replacement = SaveConcurrencySettingsActions({ enabled }) { received += it }
        replacement.edit(SettingsEdit.DirectConcurrency(1))
        assertEquals(2, received.size)
    }
    @Test fun eachClickChecksLiveAvailabilityAndDispatchesOnlyAnIntent() {
        var enabled = false
        var current = AppSettings()
        val actions = SaveConcurrencySettingsActions({ enabled }) { current = it.applyTo(current) }
        actions.edit(SettingsEdit.DirectConcurrency(2)); assertEquals(1, current.save.maxConcurrentDirectTasks)
        current = current.copy(save = SaveSettings(true, 1)); enabled = true
        actions.edit(SettingsEdit.DirectConcurrency(2)); assertEquals(SaveSettings(true, 2), current.save)
        enabled = false
        actions.edit(SettingsEdit.DirectConcurrency(1)); assertEquals(SaveSettings(true, 2), current.save)
        actions.close(); actions.close(); enabled = true
        actions.edit(SettingsEdit.DirectConcurrency(1)); assertEquals(SaveSettings(true, 2), current.save)
    }
}
