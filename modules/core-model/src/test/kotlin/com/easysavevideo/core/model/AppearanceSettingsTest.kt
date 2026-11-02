package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsTest {
    @Test fun oldOrInvalidPreferencesRetainSystemDefaults() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("999"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("unsupported"))
        assertEquals(20, AppSettings().discovery.pageSize)
        assertEquals(AppearanceSettings(), AppSettings().appearance)
    }
    @Test fun manualThemeDoesNotFollowSystemChanges() {
        assertFalse(ThemeMode.LIGHT.useDark(true))
        assertTrue(ThemeMode.DARK.useDark(false))
        assertFalse(ThemeMode.SYSTEM.useDark(false))
        assertTrue(ThemeMode.SYSTEM.useDark(true))
    }
    @Test fun persistedStableValuesRoundTripWithoutOrdinal() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }
}
