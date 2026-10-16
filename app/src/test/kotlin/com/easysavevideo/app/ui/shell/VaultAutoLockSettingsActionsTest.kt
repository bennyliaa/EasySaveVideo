package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.ui.CapturedPrivateActions
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual disposable admission helper; source contracts bind these predicates to Main's owners. */
class VaultAutoLockSettingsActionsTest {
    @Test fun ordinaryAndPrivateCallbacksRequireAllCurrentOwnerConditions() {
        for (privateMode in listOf(false, true)) for (blocked in -1..10) {
            val conditions = BooleanArray(11) { true }
            if (blocked >= 0) conditions[blocked] = false
            val actions = CapturedPrivateActions(privateMode, { conditions[10] }) {
                conditions.take(10).all { it }
            }
            var calls = 0; actions.run { calls++ }
            assertEquals(if (blocked == -1 || !privateMode && blocked == 10) 1 else 0, calls)
        }
    }
    @Test fun categoryNoticeRouteRemovalCannotReviveDisposedCallback() {
        for (removal in listOf("category", "notices", "route")) {
            var owner = Any(); val captured = owner; var calls = 0
            val old = CapturedPrivateActions(false, { true }) { owner === captured }
            old.run { calls++ }; old.close(); owner = Any(); owner = captured
            old.run { calls++ }; assertEquals(removal, 1, calls)
            val replacement = CapturedPrivateActions(false, { true }) { owner === captured }
            replacement.run { calls++ }; assertEquals(2, calls)
        }
    }
    @Test fun replacedBindingRouteRootOrEpochCannotLendAuthorityToOldCallback() {
        for (replacement in 0..3) {
            val identities = Array(4) { Any() }; val captured = identities.copyOf()
            val old = CapturedPrivateActions(false, { true }) { identities.indices.all { identities[it] === captured[it] } }
            identities[replacement] = Any(); var calls = 0; old.run { calls++ }; assertEquals(0, calls)
        }
    }
    @Test fun existingSettingsIntentsPreserveAcceptedPolicy() {
        val current = AppSettings(vaultAutoLockPolicy = VaultAutoLockPolicy.AFTER_15_MINUTES)
        val edits = listOf(SettingsEdit.Theme(ThemeMode.DARK), SettingsEdit.Language(AppLanguage.ENGLISH),
            SettingsEdit.EditorCompression(EditorCompressionMode.SAVE_SPACE), SettingsEdit.BrowserJavaScript(false),
            SettingsEdit.BrowserThirdPartyCookies(true), SettingsEdit.BrowserRestoreTabs(false), SettingsEdit.BrowserActiveViews(1),
            SettingsEdit.DiscoveryColumns(1), SettingsEdit.DiscoveryPageSize(1), SettingsEdit.DiscoveryPagePresetStep(10),
            SettingsEdit.DiscoveryRepeat(RepeatPolicy.HIDE_WATCHED), SettingsEdit.DiscoveryIntensity(DiscoveryMode.AGGRESSIVE),
            SettingsEdit.DiscoveryDefaults, SettingsEdit.WifiOnly(true), SettingsEdit.DirectConcurrency(2))
        for (edit in edits) assertEquals(current.vaultAutoLockPolicy, edit.applyTo(current).vaultAutoLockPolicy)
    }
    @Test fun dispatchMergesOnlyEnumIntoLatestSettings() {
        var settings = AppSettings()
        val callback: (VaultAutoLockPolicy) -> Unit = { settings = settings.copy(vaultAutoLockPolicy = it) }
        settings = settings.copy(save = SaveSettings(true, 2), appearance = AppearanceSettings(ThemeMode.DARK))
        val before = settings; callback(VaultAutoLockPolicy.AFTER_5_MINUTES)
        assertEquals(before.copy(vaultAutoLockPolicy = VaultAutoLockPolicy.AFTER_5_MINUTES), settings)
    }
}
