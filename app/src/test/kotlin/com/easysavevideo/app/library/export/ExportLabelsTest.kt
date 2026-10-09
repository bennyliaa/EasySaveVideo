package com.easysavevideo.app.library.export

import org.junit.Assert.*
import org.junit.Test

class ExportLabelsTest {
    @Test fun labelIsBoundedWithoutCuttingSurrogates() {
        assertEquals("123", safeExportLabel("123😀", 4))
        assertEquals("123😀", safeExportLabel("123😀", 5))
        assertEquals(80, safeExportLabel("a".repeat(1000), 80).length)
    }
    @Test fun controlsAndBidiCannotSpoofCleanupTarget() {
        assertEquals("Drive document", safeExportLabel("Drive\n\u202e document\u0000", 80))
        assertEquals("safe", safeExportLabel("\ud800safe\udfff", 80))
    }
}
