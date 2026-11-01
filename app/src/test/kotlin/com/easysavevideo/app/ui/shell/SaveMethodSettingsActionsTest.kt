package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SaveMethodSettingsActionsTest {
    private val methods = listOf(TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD)

    @Test fun allMethodIntentsPreserveEveryOtherCurrentPreference() {
        val current = AppSettings(browser = BrowserSettings(false, 4, false, true),
            discovery = DiscoverySettings(4, 75, RepeatPolicy.HIDE_WATCHED, DiscoveryMode.AGGRESSIVE),
            appearance = AppearanceSettings(ThemeMode.DARK, AppLanguage.CHINESE),
            editor = EditorSettings(EditorCompressionMode.SAVE_SPACE), save = SaveSettings(true, 2))
        for (method in methods) assertEquals(current.copy(save = current.save.withMethodEnabled(method, false)),
            SettingsEdit.SaveMethodEnabled(method, false).applyTo(current))
        for (method in listOf(TaskMethod.BROWSER, TaskMethod.EDIT))
            assertThrows(IllegalArgumentException::class.java) { SettingsEdit.SaveMethodEnabled(method, false) }
    }

    @Test fun unrelatedEditsPreserveAllThreeMethodChoices() {
        val settings = SaveSettings(true, 2, false, true, false)
        val current = AppSettings(save = settings)
        val edits = listOf(SettingsEdit.Theme(ThemeMode.DARK), SettingsEdit.Language(AppLanguage.ENGLISH),
            SettingsEdit.EditorCompression(EditorCompressionMode.SAVE_SPACE), SettingsEdit.BrowserJavaScript(false),
            SettingsEdit.BrowserThirdPartyCookies(true), SettingsEdit.BrowserRestoreTabs(false), SettingsEdit.BrowserActiveViews(1),
            SettingsEdit.DiscoveryColumns(1), SettingsEdit.DiscoveryPageSize(1), SettingsEdit.DiscoveryPagePresetStep(10),
            SettingsEdit.DiscoveryRepeat(RepeatPolicy.HIDE_WATCHED), SettingsEdit.DiscoveryIntensity(DiscoveryMode.AGGRESSIVE),
            SettingsEdit.DiscoveryDefaults)
        for (edit in edits) assertEquals(settings, edit.applyTo(current).save)
        assertEquals(settings.copy(wifiOnly = false), SettingsEdit.WifiOnly(false).applyTo(current).save)
        assertEquals(settings.copy(maxConcurrentDirectTasks = 1), SettingsEdit.DirectConcurrency(1).applyTo(current).save)
    }

    @Test fun everyUnloadedBackgroundDeadAndWrongRouteStateRejects() {
        for (method in methods) for (ready in listOf(false, true)) for (foreground in listOf(false, true))
            for (alive in listOf(false, true)) for (route in EsvDestination.entries) {
                val result = applySaveMethodSettingsEdit(AppSettings(), SettingsEdit.SaveMethodEnabled(method, false),
                    ready, foreground, alive, route)
                assertEquals(ready && foreground && alive && route == EsvDestination.SETTINGS, result != null)
            }
    }

    @Test fun eachClickUsesLatestStateAndRetiredCallbacksCannotRevive() {
        var enabled = false
        var current = AppSettings()
        val actions = SaveMethodSettingsActions({ enabled }) { current = it.applyTo(current) }
        actions.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.DIRECT, false)); assertEquals(AppSettings(), current)
        enabled = true
        current = current.copy(save = SaveSettings(true, 2))
        actions.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.DIRECT, false))
        actions.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.STREAM, false))
        val accepted = current
        assertEquals(SaveSettings(true, 2, false, false, true), accepted.save)
        enabled = false
        actions.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.RECORD, false)); assertEquals(accepted, current)
        actions.close(); actions.close(); enabled = true
        actions.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.DIRECT, true)); assertEquals(accepted, current)
        val replacement = SaveMethodSettingsActions({ enabled }) { current = it.applyTo(current) }
        replacement.edit(SettingsEdit.SaveMethodEnabled(TaskMethod.RECORD, false))
        assertEquals(SaveSettings(true, 2, false, false, false), current.save)
    }
}
