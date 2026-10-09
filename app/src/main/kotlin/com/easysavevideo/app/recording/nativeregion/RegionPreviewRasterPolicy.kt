package com.easysavevideo.app.recording.nativeregion

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.sqrt

/** Full-frame preview scale only; these dimensions must never become encoder crop dimensions. */
internal object RegionPreviewRasterPolicy {
    const val MAX_EDGE = 1280
    const val MAX_PIXELS = 921_600
    data class Size(val width: Int, val height: Int) {
        init { require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && width.toLong() * height <= MAX_PIXELS) }
        val pixels: Int get() = width * height
    }
    fun size(sourceWidth: Int, sourceHeight: Int): Size {
        require(sourceWidth in 1..4096 && sourceHeight in 1..4096)
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight),
            sqrt(MAX_PIXELS.toDouble() / (sourceWidth.toLong() * sourceHeight)))
        return Size(maxOf(1, floor(sourceWidth * scale).toInt()), maxOf(1, floor(sourceHeight * scale).toInt()))
    }

    /** GLES RGBA bytes arrive bottom row first. Convert byte-wise, independent of CPU endianness. */
    fun topDownArgb(rgba: ByteBuffer, size: Size): IntArray {
        require(rgba.remaining() == size.pixels * 4)
        val offset = rgba.position()
        return IntArray(size.pixels) { index ->
            val x = index % size.width
            val topY = index / size.width
            val byte = offset + ((size.height - 1 - topY) * size.width + x) * 4
            val red = rgba.get(byte).toInt() and 255
            val green = rgba.get(byte + 1).toInt() and 255
            val blue = rgba.get(byte + 2).toInt() and 255
            val alpha = rgba.get(byte + 3).toInt() and 255
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
}
