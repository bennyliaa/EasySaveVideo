package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class SaveSettingsTest {
    @Test fun legacyDefaultDoesNotRestrictSaving() {
        val old = AppSettings()
        assertFalse(old.save.wifiOnly); assertEquals(1, old.schemaVersion)
        val updated = old.copy(save = SaveSettings(true))
        assertTrue(updated.save.wifiOnly)
        assertEquals(old.browser, updated.browser); assertEquals(old.discovery, updated.discovery)
        assertEquals(old.appearance, updated.appearance); assertEquals(old.editor, updated.editor)
    }
}
