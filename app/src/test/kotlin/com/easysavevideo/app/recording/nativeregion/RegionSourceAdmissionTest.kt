package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RegionSourceAdmissionTest {
    private val display = CaptureDisplayGeometry(1080, 1920, 0, CaptureInsets(0, 48, 0, 96))
    private fun recipe(api: Int) = RegionCaptureRecipe(RegionNormalizedRect(.1, .1, .9, .9), display,
        if (api <= 33) CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY else CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY)
    private fun transform() = floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)

    @Test fun api31ActualDisplayAndLatchedSurfaceDoNotWaitForApi34Callback() = admitsOldPlatform(31)
    @Test fun api32ActualDisplayAndLatchedSurfaceDoNotWaitForApi34Callback() = admitsOldPlatform(32)
    @Test fun api33ActualDisplayAndLatchedSurfaceDoNotWaitForApi34Callback() = admitsOldPlatform(33)

    private fun admitsOldPlatform(api: Int) {
        assertTrue(RegionSourceAdmission.readyForFrame(recipe(api), api, display, null))
        assertTrue(RegionSourceAdmission.canEncode(recipe(api), api, display, null, transform()))
        assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, null, null, transform()))
        assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, display, null, null))
        assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, display, null, transform().apply { this[0] = .9f }))
    }

    @Test fun api34AndLaterWaitForActualMatchingCallbackBeforeAdmittingAnyFrame() {
        for (api in 34..36) {
            assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, display, null, transform()))
            assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, display, 900 to 1600, transform()))
            assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, null, 1080 to 1920, transform()))
            assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, display, 1080 to 1920, null))
            assertTrue(RegionSourceAdmission.canEncode(recipe(api), api, display, 1080 to 1920, transform()))
        }
    }

    @Test fun rotationSizeInsetsVersionOrPlatformBranchMismatchRejectsStaleSelection() {
        val changed = listOf(display.copy(width = 1082), display.copy(rotationDegrees = 90),
            display.copy(insets = CaptureInsets(0, 50, 0, 96)), display.copy(transformVersion = 2))
        for (api in listOf(31, 32, 33, 34, 35, 36)) for (geometry in changed) {
            assertFalse(RegionSourceAdmission.canEncode(recipe(api), api, geometry, 1080 to 1920, transform()))
        }
        assertFalse(RegionSourceAdmission.canEncode(recipe(31), 34, display, 1080 to 1920, transform()))
        assertFalse(RegionSourceAdmission.canEncode(recipe(34), 33, display, null, transform()))
        assertFalse(RegionSourceAdmission.canEncode(recipe(31), 30, display, null, transform()))
    }

    @Test fun dynamicSourceFramesAdvanceWithoutReplacingFrozenPreviewOrItsSelectionIdentity() {
        val session = RecordingSessionId("dynamic-preview", 2)
        for (api in listOf(31, 34, 36)) {
            val selected = recipe(api)
            val provenance = if (api >= 34) CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK
                else CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE
            val first = CaptureGeometryObservation(display, selected.scopeEvidence, provenance, 100)
            val snapshot = RegionPreviewSnapshot(session, 7, 1, first, 540, 960,
                RegionPreviewTransform(transform()), 100_000, 100, 60_000_100)
            val cadence = RegionFrameAdmission(30)
            for (frame in 1L..120L) {
                val timestampNs = 100_000 + frame * 33_333_334
                val actual = first.copy(observedAtMonotonicUs = timestampNs / 1000)
                assertTrue(RegionSourceAdmission.canEncode(selected, api, display,
                    if (api >= 34) 1080 to 1920 else null, transform()))
                assertTrue(snapshot.matchesSource(session, 7, actual, transform()))
                assertTrue(cadence.admit(timestampNs))
                assertEquals(1L, snapshot.frameSequence)
                assertEquals(100_000L, snapshot.sourceFrameTimestampNs)
                assertEquals(first, snapshot.observation)
            }
            // Snapshot lifetime governs pending UI selection, not an already-admitted recording.
            assertTrue(snapshot.matchesSource(session, 7,
                first.copy(observedAtMonotonicUs = snapshot.expiresAtMonotonicUs + 1), transform()))
        }
    }

    @Test fun anotherSupportedFullImageFlipStillInvalidatesTheExactFrozenSourceBinding() {
        val session = RecordingSessionId("dynamic-preview", 2)
        val selected = recipe(34)
        val actual = CaptureGeometryObservation(display, selected.scopeEvidence,
            CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 100)
        val snapshot = RegionPreviewSnapshot(session, 7, 1, actual, 540, 960,
            RegionPreviewTransform(transform()), 100_000, 100, 60_000_100)
        val changed = transform().apply { this[0] = -1f; this[12] = 1f }
        assertTrue(RegionSourceAdmission.canEncode(selected, 34, display, 1080 to 1920, changed))
        assertFalse(snapshot.matchesSource(session, 7, actual.copy(observedAtMonotonicUs = 200), changed))
        assertFalse(snapshot.matchesSource(session.copy(generation = 3), 7, actual, transform()))
        assertFalse(snapshot.matchesSource(session, 8, actual, transform()))
    }

}
