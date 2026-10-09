package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingProfileGeometryTest {
    @Test fun portraitDownscalePreservesExactRatioAndAlignment() {
        val result = requireNotNull(RecordingProfileGeometry.fit(1080, 2400, 1920, 16, 16))
        assertEquals(864 to 1920, result)
        assertEquals(1080L * result.second, 2400L * result.first)
    }

    @Test fun landscapeAndTabletAreNotStretchedOrUpscaled() {
        listOf(1920 to 1080, 2560 to 1600, 1440 to 3120, 1280 to 720).forEach { (width, height) ->
            val result = requireNotNull(RecordingProfileGeometry.fit(width, height, 1280, 16, 16))
            assertTrue(maxOf(result.first, result.second) <= 1280)
            assertTrue(result.first <= width && result.second <= height)
            assertEquals(0, result.first % 16); assertEquals(0, result.second % 16)
            assertEquals(width.toLong() * result.second, height.toLong() * result.first)
        }
    }

    @Test fun unrepresentableRatioFailsInsteadOfRoundingIndependently() {
        assertNull(RecordingProfileGeometry.fit(1081, 2400, 1920, 16, 16))
        assertNull(RecordingProfileGeometry.fit(120, 240, 1920, 16, 16))
    }
}
