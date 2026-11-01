package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class SaveMethodSettingsTest {
    private val methods = listOf(TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD)

    @Test fun legacyConstructorsEnableAllThreeMethodsWithoutChangingSchema() {
        for (settings in listOf(SaveSettings(), SaveSettings(true), SaveSettings(false, 2)))
            for (method in methods) assertTrue(settings.isMethodEnabled(method))
        assertEquals(1, AppSettings().schemaVersion)
    }

    @Test fun allEightCombinationsAreIndependentAndPreserveExistingFields() {
        for (direct in listOf(false, true)) for (stream in listOf(false, true)) for (record in listOf(false, true)) {
            val original = SaveSettings(true, 2, direct, stream, record)
            assertEquals(direct, original.isMethodEnabled(TaskMethod.DIRECT))
            assertEquals(stream, original.isMethodEnabled(TaskMethod.STREAM))
            assertEquals(record, original.isMethodEnabled(TaskMethod.RECORD))
            for (method in methods) {
                val changed = original.withMethodEnabled(method, !original.isMethodEnabled(method))
                assertEquals(!original.isMethodEnabled(method), changed.isMethodEnabled(method))
                for (other in methods - method) assertEquals(original.isMethodEnabled(other), changed.isMethodEnabled(other))
                assertTrue(changed.wifiOnly); assertEquals(2, changed.maxConcurrentDirectTasks)
                assertEquals(original, changed.withMethodEnabled(method, original.isMethodEnabled(method)))
            }
        }
    }

    @Test fun allOffPreservesBrowsingEditingAndUnrelatedSettings() {
        val original = AppSettings(save = SaveSettings(true, 2, false, false, false))
        assertTrue(original.save.isMethodEnabled(TaskMethod.BROWSER))
        assertTrue(original.save.isMethodEnabled(TaskMethod.EDIT))
        for (method in listOf(TaskMethod.BROWSER, TaskMethod.EDIT))
            assertThrows(IllegalArgumentException::class.java) { original.save.withMethodEnabled(method, false) }
        assertEquals(SaveSettings(false, 2, false, false, false), original.save.copy(wifiOnly = false))
        assertEquals(SaveSettings(true, 1, false, false, false), original.save.copy(maxConcurrentDirectTasks = 1))
        assertEquals(BrowserSettings(), original.browser); assertEquals(DiscoverySettings(), original.discovery)
    }
}
