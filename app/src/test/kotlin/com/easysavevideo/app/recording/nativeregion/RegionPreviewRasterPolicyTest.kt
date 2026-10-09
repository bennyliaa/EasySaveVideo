package com.easysavevideo.app.recording.nativeregion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class RegionPreviewRasterPolicyTest {
    @Test fun rgbaChannelsAndBottomUpRowsBecomeTopDownArgbIndependentOfMachineOrder() {
        // GL bottom row: blue, white; GL top row: red with half alpha, green.
        val bytes = byteArrayOf(0, 0, -1, -1, -1, -1, -1, -1, -1, 0, 0, -128, 0, -1, 0, -1)
        val expected = intArrayOf(0x80ff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt())
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val buffer = ByteBuffer.allocate(22).order(order).apply {
                put(byteArrayOf(7, 8, 9)); put(bytes); put(byteArrayOf(10, 11, 12)); position(3); limit(19)
            }
            assertArrayEquals(expected, RegionPreviewRasterPolicy.topDownArgb(buffer, RegionPreviewRasterPolicy.Size(2, 2)))
            assertEquals(3, buffer.position())
        }
    }

    @Test fun boundedPreviewDimensionsRetainWholeSourceAspectWithinOnePixel() {
        assertEquals(RegionPreviewRasterPolicy.Size(1280, 720), RegionPreviewRasterPolicy.size(3840, 2160))
        assertEquals(RegionPreviewRasterPolicy.Size(576, 1280), RegionPreviewRasterPolicy.size(1080, 2400))
        assertEquals(RegionPreviewRasterPolicy.Size(960, 960), RegionPreviewRasterPolicy.size(4096, 4096))
        assertEquals(RegionPreviewRasterPolicy.Size(128, 129), RegionPreviewRasterPolicy.size(128, 129))
        for (width in 128..4096 step 31) for (height in 128..4096 step 37) {
            val size = RegionPreviewRasterPolicy.size(width, height)
            assertTrue(size.width <= width && size.height <= height)
            assertTrue(maxOf(size.width, size.height) <= 1280)
            assertTrue(size.pixels <= 921_600)
            assertTrue(abs(size.width.toLong() * height - size.height.toLong() * width) <= maxOf(width, height))
        }
    }

    @Test fun fullSourceDownsampleAndRowConversionPreserveCornerIdentityWithoutCrop() {
        val sourceWidth = 2400
        val sourceHeight = 1080
        val size = RegionPreviewRasterPolicy.size(sourceWidth, sourceHeight)
        val buffer = ByteBuffer.allocate(size.pixels * 4)
        // Synthetic shader nearest-sampling oracle. No real GPU behavior is claimed by this test.
        for (glY in 0 until size.height) for (x in 0 until size.width) {
            val sourceX = ((x + .5) * sourceWidth / size.width).toInt()
            val sourceY = (((size.height - 1 - glY) + .5) * sourceHeight / size.height).toInt()
            buffer.put((if (sourceX < sourceWidth / 2) 17 else 231).toByte())
            buffer.put((if (sourceY < sourceHeight / 2) 33 else 207).toByte())
            buffer.put(91); buffer.put(-1)
        }
        buffer.flip()
        val output = RegionPreviewRasterPolicy.topDownArgb(buffer, size)
        assertEquals(0xff11215b.toInt(), output.first())
        assertEquals(0xffe7215b.toInt(), output[size.width - 1])
        assertEquals(0xff11cf5b.toInt(), output[(size.height - 1) * size.width])
        assertEquals(0xffe7cf5b.toInt(), output.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteReadbackIsRejectedRatherThanShowingPartialImage() {
        RegionPreviewRasterPolicy.topDownArgb(ByteBuffer.allocate(15), RegionPreviewRasterPolicy.Size(2, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedRasterCannotAllocateThroughPolicy() { RegionPreviewRasterPolicy.Size(1280, 1280) }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSourceCannotBeScaledIntoAcceptance() { RegionPreviewRasterPolicy.size(Int.MAX_VALUE, 1080) }
}
