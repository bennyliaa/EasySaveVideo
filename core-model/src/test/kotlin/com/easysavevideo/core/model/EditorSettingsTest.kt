package com.easysavevideo.core.model

import org.junit.Assert.*
import org.junit.Test

class EditorSettingsTest {
    @Test fun missingLegacyAndUnknownKeysKeepOriginalQuality() {
        listOf(null, "", "unknown", "SAVE_SPACE", "1").forEach {
            assertEquals(EditorCompressionMode.ORIGINAL_QUALITY, EditorCompressionMode.fromKey(it))
        }
        assertEquals(EditorCompressionMode.ORIGINAL_QUALITY, AppSettings().editor.defaultCompression)
        assertEquals(1, AppSettings().schemaVersion)
        assertEquals(BrowserSettings(), AppSettings().browser)
        assertEquals(DiscoverySettings(), AppSettings().discovery)
    }
    @Test fun stablePreferenceKeysRoundTrip() {
        assertEquals(listOf("original_quality", "save_space"), EditorCompressionMode.entries.map { it.key })
        EditorCompressionMode.entries.forEach { assertEquals(it, EditorCompressionMode.fromKey(it.key)) }
    }
}
