package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class VaultAutoLockSettingsTest {
    @Test fun stableKeysAndDurationsAreExactlyTheFourApprovedChoices() {
        assertEquals(listOf("immediate", "after_1_minute", "after_5_minutes", "after_15_minutes"),
            VaultAutoLockPolicy.entries.map { it.key })
        assertEquals(listOf(0L, 60_000L, 300_000L, 900_000L), VaultAutoLockPolicy.entries.map { it.durationMillis })
        for (policy in VaultAutoLockPolicy.entries) assertEquals(policy, VaultAutoLockPolicy.fromKey(policy.key))
    }
    @Test fun missingEmptyUnknownAndLegacyNamesDefaultToImmediate() {
        for (key in listOf(null, "", "unknown", "AFTER_5_MINUTES", "2", " after_1_minute"))
            assertEquals(VaultAutoLockPolicy.IMMEDIATE, VaultAutoLockPolicy.fromKey(key))
        assertEquals(VaultAutoLockPolicy.IMMEDIATE, AppSettings().vaultAutoLockPolicy)
        assertEquals(1, AppSettings().schemaVersion)
    }
    @Test fun additivePolicyCopyPreservesEveryOtherSettingsField() {
        val original = AppSettings(browser = BrowserSettings(false, 4, false, true),
            discovery = DiscoverySettings(6, 99, RepeatPolicy.SHOW_ALL, DiscoveryMode.AGGRESSIVE),
            appearance = AppearanceSettings(ThemeMode.DARK, AppLanguage.CHINESE),
            editor = EditorSettings(EditorCompressionMode.SAVE_SPACE), save = SaveSettings(true, 2))
        for (policy in VaultAutoLockPolicy.entries) {
            val selected = original.copy(vaultAutoLockPolicy = policy)
            assertEquals(original, selected.copy(vaultAutoLockPolicy = VaultAutoLockPolicy.IMMEDIATE))
        }
    }
}
