package com.easysavevideo.app.storage

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SaveConcurrencyPersistenceTest {
    @Test fun validPersistedCountsAreReadUnchanged() {
        for (count in 1..2) assertEquals(count, readDirectConcurrencyPreference { count })
    }
    @Test fun missingAndInvalidCountsUseOneRatherThanClampingUpToTwo() {
        assertEquals(1, readDirectConcurrencyPreference { 1 }) // SharedPreferences missing-key default.
        for (count in listOf(Int.MIN_VALUE, -1, 0, 3, 100, Int.MAX_VALUE))
            assertEquals(1, readDirectConcurrencyPreference { count })
    }
    @Test fun wrongTypesFallBackLocallyWithoutDiscardingOtherLoadedSettings() {
        for (stored: Any in listOf("2", 2L, 2.0, true, setOf("2"))) {
            val count = readDirectConcurrencyPreference { stored as Int }
            val loaded = AppSettings(browser = BrowserSettings(false, 4, false, true),
                save = SaveSettings(true, count), appearance = AppearanceSettings(ThemeMode.DARK, AppLanguage.ENGLISH))
            assertEquals(1, loaded.save.maxConcurrentDirectTasks)
            assertTrue(loaded.save.wifiOnly)
            assertFalse(loaded.browser.restoreTabs)
            assertEquals(AppLanguage.ENGLISH, loaded.appearance.language)
        }
    }
    @Test fun genuineStoreFailureIsNotConvertedIntoSuccessfulLoading() {
        try { readDirectConcurrencyPreference { throw IllegalStateException("test store unavailable") }; fail("hid read failure") }
        catch (_: IllegalStateException) { }
    }
}
