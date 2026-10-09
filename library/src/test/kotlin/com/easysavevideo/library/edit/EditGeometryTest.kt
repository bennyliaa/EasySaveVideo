package com.easysavevideo.library.edit

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EditGeometryTest {
    @Test fun normalizedCoordinatesRejectNanInfinityReversalAndOutOfBounds() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(EditFailure::class.java) { NormalizedCrop(bad, 0.0, 1.0, 1.0) }
            assertThrows(EditFailure::class.java) { NormalizedCrop(0.0, bad, 1.0, 1.0) }
            assertThrows(EditFailure::class.java) { NormalizedCrop(0.0, 0.0, bad, 1.0) }
            assertThrows(EditFailure::class.java) { NormalizedCrop(0.0, 0.0, 1.0, bad) }
        }
        assertThrows(EditFailure::class.java) { NormalizedCrop(0.5, 0.0, 0.5, 1.0) }
        assertThrows(EditFailure::class.java) { NormalizedCrop(0.0, 0.8, 1.0, 0.2) }
    }

    @Test fun letterboxedLandscapeSelectionUsesActualImageBounds() {
        val content = EditGeometry.viewportContent(1000.0, 1000.0, 1920, 1080)
        assertEquals(ViewportRect(0.0, 218.75, 1000.0, 781.25), content)
        assertEquals(NormalizedCrop.FULL, EditGeometry.selectionToNormalized(
            ViewportRect(0.0, 0.0, 1000.0, 1000.0), 1000.0, 1000.0, 1920, 1080))
        assertEquals(NormalizedCrop(0.25, 0.0, 0.75, 0.5), EditGeometry.selectionToNormalized(
            ViewportRect(250.0, 0.0, 750.0, 500.0), 1000.0, 1000.0, 1920, 1080))
    }

    @Test fun pillarboxedPortraitSelectionUsesActualImageBounds() {
        assertEquals(ViewportRect(218.75, 0.0, 781.25, 1000.0),
            EditGeometry.viewportContent(1000.0, 1000.0, 1080, 1920))
        assertEquals(NormalizedCrop(0.0, 0.25, 0.5, 0.75), EditGeometry.selectionToNormalized(
            ViewportRect(0.0, 250.0, 500.0, 750.0), 1000.0, 1000.0, 1080, 1920))
    }

    @Test fun selectionsWhollyInBarsFailInsteadOfCroppingTheWrongPixels() {
        for (selection in listOf(ViewportRect(0.0, 0.0, 1000.0, 200.0),
            ViewportRect(0.0, 800.0, 1000.0, 1000.0))) {
            assertThrows(EditFailure::class.java) {
                EditGeometry.selectionToNormalized(selection, 1000.0, 1000.0, 1920, 1080)
            }
        }
    }

    @Test fun allFourRotationTransformsMapAsymmetricCropToExpectedSourceArea() {
        val source = PixelCrop(2, 2, 12, 6)
        val expected = mapOf(0 to source, 90 to PixelCrop(4, 2, 8, 12),
            180 to PixelCrop(4, 4, 14, 8), 270 to PixelCrop(2, 4, 6, 14))
        for ((rotation, display) in expected) {
            assertEquals(display, EditGeometry.toDisplay(source, 16, 10, rotation))
            assertEquals(source, EditGeometry.toSource(display, 16, 10, rotation))
        }
    }

    @Test fun displayOrientationSwapsDimensionsOnlyForQuarterTurns() {
        for (rotation in listOf(0, 180)) assertEquals(EditDimensions(1920, 1080),
            EditGeometry.displayDimensions(1920, 1080, rotation))
        for (rotation in listOf(90, 270)) assertEquals(EditDimensions(1080, 1920),
            EditGeometry.displayDimensions(1920, 1080, rotation))
    }

    @Test fun pixelRoundingIsEvenAndStrictlyInward() {
        assertEquals(PixelCrop(212, 250, 1670, 982), EditGeometry.displayCrop(
            NormalizedCrop(0.11, 0.23, 0.87, 0.91), 1920, 1080, 0))
        assertEquals(PixelCrop(0, 0, 1080, 1920), EditGeometry.displayCrop(NormalizedCrop.FULL, 1920, 1080, 90))
        assertThrows(EditFailure::class.java) {
            EditGeometry.displayCrop(NormalizedCrop(0.0, 0.0, 0.0001, 0.0001), 1920, 1080, 0)
        }
    }

    @Test fun transformsRejectUnsupportedRotationsOddGeometryAndOutsideSelections() {
        for (rotation in listOf(-90, 1, 45, 360)) assertThrows(EditFailure::class.java) {
            EditGeometry.displayDimensions(1920, 1080, rotation)
        }
        for ((w, h) in listOf(1921 to 1080, 1920 to 1081, 3840 to 2160, 0 to 1080)) {
            assertThrows(EditFailure::class.java) { EditGeometry.displayDimensions(w, h, 0) }
        }
        assertThrows(EditFailure::class.java) { EditGeometry.toSource(PixelCrop(0, 0, 1082, 1920), 1920, 1080, 90) }
        assertThrows(EditFailure::class.java) { EditGeometry.toDisplay(PixelCrop(0, 0, 1922, 1080), 1920, 1080, 0) }
    }

    @Test fun deterministicPropertySamplesPreserveInwardContainmentAndRotationRoundTrip() {
        val random = Random(0xE5A)
        repeat(500) {
            val left = random.nextDouble(0.0, 0.4)
            val top = random.nextDouble(0.0, 0.4)
            val crop = NormalizedCrop(left, top, random.nextDouble(0.6, 1.0), random.nextDouble(0.6, 1.0))
            for (rotation in listOf(0, 90, 180, 270)) {
                val dimensions = EditGeometry.displayDimensions(1920, 1080, rotation)
                val display = EditGeometry.displayCrop(crop, 1920, 1080, rotation)
                assertTrue(display.left >= crop.left * dimensions.width)
                assertTrue(display.top >= crop.top * dimensions.height)
                assertTrue(display.right <= crop.right * dimensions.width)
                assertTrue(display.bottom <= crop.bottom * dimensions.height)
                assertTrue(display.left - crop.left * dimensions.width < 2)
                assertTrue(crop.right * dimensions.width - display.right < 2)
                val source = EditGeometry.toSource(display, 1920, 1080, rotation)
                assertEquals(display, EditGeometry.toDisplay(source, 1920, 1080, rotation))
            }
        }
    }

    @Test fun viewportRoundTripRetainsNormalizedSelectionAcrossAspectRatios() {
        val crop = NormalizedCrop(0.1, 0.2, 0.8, 0.9)
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 800 to 800)) {
            val screen = EditGeometry.normalizedToViewport(crop, 720.0, 1280.0, w, h)
            val again = EditGeometry.selectionToNormalized(screen, 720.0, 1280.0, w, h)
            assertEquals(crop.left, again.left, 1e-12); assertEquals(crop.top, again.top, 1e-12)
            assertEquals(crop.right, again.right, 1e-12); assertEquals(crop.bottom, again.bottom, 1e-12)
        }
    }
}
