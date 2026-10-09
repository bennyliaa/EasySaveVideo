package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.PixelCrop
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class RegionTexturePolicyTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    @Test fun fullImageIdentityAndAxisFlipsAreAccepted() {
        assertTrue(RegionTexturePolicy.isUnscaledFullImage(identity()))
        for (x in listOf(false, true)) for (y in listOf(false, true)) {
            val matrix = identity()
            if (x) { matrix[0] = -1f; matrix[12] = 1f }
            if (y) { matrix[5] = -1f; matrix[13] = 1f }
            assertTrue(RegionTexturePolicy.isUnscaledFullImage(matrix))
        }
    }

    @Test fun producerCropScaleRotationPerspectiveAndNonFiniteFailClosed() {
        val bad = listOf(
            identity().apply { this[0] = .999f },
            identity().apply { this[13] = .001f },
            identity().apply { this[0] = 0f; this[1] = 1f; this[4] = -1f; this[5] = 0f },
            identity().apply { this[3] = .5f },
            identity().apply { this[10] = 0f },
            identity().apply { this[7] = Float.NaN },
            identity().apply { this[15] = Float.POSITIVE_INFINITY },
            FloatArray(15),
        )
        bad.forEach { assertFalse(RegionTexturePolicy.isUnscaledFullImage(it)) }
    }

    @Test fun topLeftPixelRectangleIsConvertedWithoutInsetRemovalOrScaling() {
        assertArrayEquals(floatArrayOf(.1f, .6f, .5f, .9f),
            RegionTexturePolicy.textureEdges(PixelCrop(100, 200, 500, 800), 1000, 2000), .000001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f),
            RegionTexturePolicy.textureEdges(PixelCrop(0, 0, 1000, 2000), 1000, 2000), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropOutsideFrozenSourceIsRejected() {
        RegionTexturePolicy.textureEdges(PixelCrop(0, 0, 1200, 2000), 1000, 2000)
    }

    @Test fun syntheticColoredGridSelectsExactlyApprovedPixelsForEveryAcceptedAxisFlip() {
        val width = 12
        val height = 20
        val crop = PixelCrop(2, 4, 10, 16)
        val expected = (crop.top until crop.bottom).flatMap { y ->
            (crop.left until crop.right).map { x -> y * 100 + x }
        }
        for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
            val rendered = renderSyntheticGrid(crop, width, height, crop.width, crop.height, flipX, flipY)
            assertEquals(expected, rendered)
            assertEquals(crop.width * crop.height, rendered.toSet().size)
            assertFalse(rendered.contains(0)) // Content outside the selection never enters output.
        }
    }

    @Test fun merelySizingVirtualDisplayOrEncoderWouldScaleFullFrameAndFailsSelectedGridOracle() {
        val crop = PixelCrop(2, 4, 10, 16)
        val selected = renderSyntheticGrid(crop, 12, 20, crop.width, crop.height, false, true)
        val wronglyScaledFullDisplay = renderSyntheticGrid(PixelCrop(0, 0, 12, 20),
            12, 20, crop.width, crop.height, false, true)
        assertNotEquals(selected, wronglyScaledFullDisplay)
        assertEquals(402, selected.first())
        assertNotEquals(402, wronglyScaledFullDisplay.first())
    }

    /** CPU oracle for shader interpolation at fragment centers, then GL_NEAREST OES sampling.
     * This verifies the submitted geometry, not EGL/driver execution (device gate NOT_RUN). */
    private fun renderSyntheticGrid(crop: PixelCrop, width: Int, height: Int, outputWidth: Int,
        outputHeight: Int, flipX: Boolean, flipY: Boolean): List<Int> {
        val edges = RegionTexturePolicy.textureEdges(crop, width, height)
        val matrix = identity().apply {
            if (flipX) { this[0] = -1f; this[12] = 1f }
            if (flipY) { this[5] = -1f; this[13] = 1f }
        }
        assertTrue(RegionTexturePolicy.isUnscaledFullImage(matrix))
        // Producer backing pixels account for the exact full-image axis flip reported by OES.
        val backing = Array(height) { textureY -> IntArray(width) { textureX ->
            val x = if (flipX) width - 1 - textureX else textureX
            val y = if (flipY) textureY else height - 1 - textureY
            y * 100 + x
        } }
        return (0 until outputHeight).flatMap { y -> (0 until outputWidth).map { x ->
            val u = edges[0] + (edges[2] - edges[0]) * ((x + .5f) / outputWidth)
            val v = edges[1] + (edges[3] - edges[1]) * ((outputHeight - y - .5f) / outputHeight)
            val sourceU = matrix[0] * u + matrix[12]
            val sourceV = matrix[5] * v + matrix[13]
            val sourceX = floor(sourceU * width).toInt()
            val sourceY = floor(sourceV * height).toInt()
            backing[sourceY][sourceX]
        } }
    }
}
