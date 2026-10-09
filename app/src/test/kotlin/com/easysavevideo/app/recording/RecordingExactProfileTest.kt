package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingExactProfileTest {
    @Test fun alignmentNeverRoundsOrFitsSelectedDimensions() {
        assertTrue(RecordingProfileGeometry.exact(1080, 1920, 2, 2))
        assertFalse(RecordingProfileGeometry.exact(1080, 1920, 16, 16))
        assertTrue(RecordingProfileGeometry.exact(1088, 1920, 16, 16))
        assertFalse(RecordingProfileGeometry.exact(1079, 1920, 2, 2))
    }
    @Test fun invalidOrOverBudgetOutputFailsClosed() {
        assertFalse(RecordingProfileGeometry.exact(0, 720, 2, 2))
        assertFalse(RecordingProfileGeometry.exact(8192, 720, 2, 2))
        assertFalse(RecordingProfileGeometry.exact(4096, 4096, 2, 2))
        assertFalse(RecordingProfileGeometry.exact(1280, 720, 0, 2))
        assertFalse(RecordingProfileGeometry.exact(1280, 720, 2, 2048))
    }
}
