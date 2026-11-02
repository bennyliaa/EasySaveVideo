package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RegionCaptureContractsTest {
    private val geometry = CaptureDisplayGeometry(1080, 2400, 0, CaptureInsets(top = 96, bottom = 120))
    private val scope = CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY
    private fun recipe(bounds: RegionNormalizedRect = RegionNormalizedRect.FULL) =
        RegionCaptureRecipe(bounds, geometry, scope, selectedAtMonotonicUs = 100)
    private fun observation(source: CaptureDisplayGeometry = geometry) = CaptureGeometryObservation(
        source, scope, CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 101,
    )
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid contract") } catch (_: IllegalArgumentException) { }
    }

    @Test fun normalizedBoundsRejectNonFiniteReversedAndOutOfDisplayValues() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            invalid { RegionNormalizedRect(value, 0.0, 1.0, 1.0) }
            invalid { RegionNormalizedRect(0.0, value, 1.0, 1.0) }
            invalid { RegionNormalizedRect(0.0, 0.0, value, 1.0) }
            invalid { RegionNormalizedRect(0.0, 0.0, 1.0, value) }
        }
        invalid { RegionNormalizedRect(0.5, 0.0, 0.5, 1.0) }
        invalid { RegionNormalizedRect(0.0, 0.9, 1.0, 0.8) }
    }

    @Test fun fullDisplayIncludesSystemBarsRatherThanInsetContent() {
        val result = recipe()
        assertEquals(PixelCrop(0, 0, 1080, 2400), result.pixelCrop)
        assertEquals(CaptureInsets(top = 96, bottom = 120), result.source.insets)
        assertEquals(RegionCaptureDataRoute.SELECTED_PIXELS_BEFORE_ENCODING, result.dataRoute)
    }

    @Test fun oddDisplayExtentCropsLastPixelInwardRatherThanStretching() {
        val result = RegionCaptureRecipe(RegionNormalizedRect.FULL,
            geometry.copy(width = 1081, height = 2401), scope)
        assertEquals(PixelCrop(0, 0, 1080, 2400), result.pixelCrop)
        assertEquals(1080, result.encodedWidth)
        assertEquals(2400, result.encodedHeight)
    }

    @Test fun fractionalEdgesAllSnapInwardAndNeverExpandSelection() {
        val result = recipe(RegionNormalizedRect(0.101, 0.103, 0.899, 0.901))
        assertEquals(PixelCrop(110, 248, 970, 2162), result.pixelCrop)
        val random = Random(61)
        repeat(1000) {
            val bounds = RegionNormalizedRect(random.nextDouble() * 0.3, random.nextDouble() * 0.3,
                0.7 + random.nextDouble() * 0.3, 0.7 + random.nextDouble() * 0.3)
            val crop = recipe(bounds).pixelCrop
            assertTrue(crop.left >= bounds.left * geometry.width)
            assertTrue(crop.top >= bounds.top * geometry.height)
            assertTrue(crop.right <= bounds.right * geometry.width)
            assertTrue(crop.bottom <= bounds.bottom * geometry.height)
            assertTrue(crop.left - bounds.left * geometry.width < 2.0)
            assertTrue(crop.top - bounds.top * geometry.height < 2.0)
            assertTrue(bounds.right * geometry.width - crop.right < 2.0)
            assertTrue(bounds.bottom * geometry.height - crop.bottom < 2.0)
        }
    }

    @Test fun dimensionsAlreadyHaveDisplayOrientationAndAreNotRotatedTwice() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val result = RegionCaptureRecipe(RegionNormalizedRect(0.25, 0.25, 0.75, 0.75),
                CaptureDisplayGeometry(2400, 1080, rotation), scope)
            assertEquals(PixelCrop(600, 270, 1800, 810), result.pixelCrop)
            assertEquals(1200, result.encodedWidth)
            assertEquals(540, result.encodedHeight)
            assertEquals(0, result.encodedRotationDegrees)
        }
    }

    @Test fun requestedProfileCannotStretchOrScaleCropToPresetAspectRatio() {
        val selected = recipe(RegionNormalizedRect(0.25, 0.25, 0.75, 0.75))
        val actual = RegionCapturePolicy.outputProfile(selected, RecordingProfile(width = 1280, height = 720))
        assertEquals(540, actual.width)
        assertEquals(1200, actual.height)
        assertTrue(RegionCapturePolicy.profileMatches(selected, actual))
        assertFalse(RegionCapturePolicy.profileMatches(selected, actual.copy(width = 1280, height = 720)))
    }

    @Test fun boundedDimensionsInsetsVersionAndMinimumCropFailClosed() {
        invalid { geometry.copy(width = 127) }
        invalid { geometry.copy(height = 4097) }
        invalid { geometry.copy(width = 4096, height = 4096) }
        invalid { geometry.copy(rotationDegrees = 45) }
        invalid { CaptureInsets(left = -1) }
        invalid { geometry.copy(insets = CaptureInsets(left = 600, right = 480)) }
        invalid { geometry.copy(insets = CaptureInsets(top = 2400)) }
        invalid { geometry.copy(transformVersion = 0) }
        invalid { RegionCaptureRecipe(RegionNormalizedRect.FULL, geometry.copy(transformVersion = 2), scope) }
        invalid { recipe(RegionNormalizedRect(0.0, 0.0, 0.1, 0.1)) }
        invalid { recipe(RegionNormalizedRect(0.0001, 0.0, 0.12, 1.0)) }
        invalid { recipe().copy(selectedAtMonotonicUs = -1) }
        invalid { observation().copy(observedAtMonotonicUs = -1) }
        invalid { PixelCrop(1, 0, 200, 200) }
        invalid { PixelCrop(0, 0, 4098, 200) }
    }

    @Test fun observationMustMatchEveryFrozenGeometryAndScopeDimension() {
        val expected = recipe()
        assertTrue(RegionCapturePolicy.matches(expected, observation()))
        for (changed in listOf(
            geometry.copy(width = 1082), geometry.copy(height = 2402), geometry.copy(rotationDegrees = 90),
            geometry.copy(insets = CaptureInsets(top = 97, bottom = 120)), geometry.copy(transformVersion = 2),
        )) assertFalse(RegionCapturePolicy.matches(expected, observation(changed)))
        assertFalse(RegionCapturePolicy.matches(expected,
            observation().copy(scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY)))
        assertFalse(RegionCapturePolicy.matches(expected,
            observation().copy(provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE)))
        assertFalse(RegionCapturePolicy.matches(expected, observation().copy(observedAtMonotonicUs = 99)))
        try {
            RegionCapturePolicy.confirm(expected, observation(geometry.copy(rotationDegrees = 180)))
            fail("Expected safe-stop")
        } catch (failure: RecordingFailure) { assertEquals(RecordingError.FORMAT_CHANGED, failure.code) }
    }

    @Test fun olderApisRequireActualDefaultDisplaySurfaceProofAndNewerApisRequireContentProof() {
        val older = recipe().copy(scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY)
        val olderProof = observation().copy(scopeEvidence = older.scopeEvidence,
            provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE)
        assertTrue(RegionCapturePolicy.matches(older, olderProof))
        assertFalse(RegionCapturePolicy.matches(older, olderProof.copy(
            provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK)))
        val confirmed = RegionCapturePolicy.confirm(recipe(), observation())
        assertEquals(recipe(), confirmed.recipe)
        assertEquals(observation(), confirmed.observation)
        assertEquals(recipe().pixelCrop, confirmed.pixelCrop)
    }
}
