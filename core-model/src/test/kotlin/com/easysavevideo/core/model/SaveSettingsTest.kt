package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class SaveSettingsTest {
    @Test fun legacyDefaultDoesNotRestrictSaving() {
        val old = AppSettings()
        assertFalse(old.save.wifiOnly); assertEquals(1, old.save.maxConcurrentDirectTasks); assertEquals(1, old.schemaVersion)
        val updated = old.copy(save = SaveSettings(true))
        assertTrue(updated.save.wifiOnly)
        assertEquals(old.browser, updated.browser); assertEquals(old.discovery, updated.discovery)
        assertEquals(old.appearance, updated.appearance); assertEquals(old.editor, updated.editor)
    }
    @Test fun concurrencyOnlyAcceptsOneOrTwoAndCopiesPreserveWifi() {
        for (count in 1..2) {
            val settings = SaveSettings(true, count)
            assertTrue(settings.wifiOnly)
            assertEquals(count, settings.maxConcurrentDirectTasks)
            assertEquals(count, settings.copy(wifiOnly = false).maxConcurrentDirectTasks)
        }
        for (count in listOf(Int.MIN_VALUE, -1, 0, 3, 10, Int.MAX_VALUE)) {
            try { SaveSettings(maxConcurrentDirectTasks = count); fail("accepted invalid count") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
