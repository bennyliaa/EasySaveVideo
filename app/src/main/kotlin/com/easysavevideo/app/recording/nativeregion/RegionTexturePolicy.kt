package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.PixelCrop
import kotlin.math.abs

/** Pure host-tested boundary for the accepted, unscaled OES transform subset. */
internal object RegionTexturePolicy {
    // SurfaceTexture may add a producer crop/scale. Reject it rather than quietly stretching the
    // selected pixels. Full-image axis flips are handled by its matrix; rotations are not guessed.
    fun isUnscaledFullImage(matrix: FloatArray): Boolean {
        if (matrix.size != 16 || matrix.any { !it.isFinite() }) return false
        val expectedZero = intArrayOf(1, 2, 3, 4, 6, 7, 8, 9, 11, 14)
        if (expectedZero.any { abs(matrix[it]) > 0.000001f }) return false
        if (abs(matrix[10] - 1f) > 0.000001f || abs(matrix[15] - 1f) > 0.000001f) return false
        return listOf(0 to 12, 5 to 13).all { (scale, offset) ->
            (abs(matrix[scale] - 1f) <= 0.000001f && abs(matrix[offset]) <= 0.000001f) ||
                (abs(matrix[scale] + 1f) <= 0.000001f && abs(matrix[offset] - 1f) <= 0.000001f)
        }
    }

    /** Bottom-left GL coordinates before the producer transform, expressed at crop edges. */
    fun textureEdges(crop: PixelCrop, sourceWidth: Int, sourceHeight: Int): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0 && crop.right <= sourceWidth && crop.bottom <= sourceHeight)
        return floatArrayOf(crop.left.toFloat() / sourceWidth, 1f - crop.bottom.toFloat() / sourceHeight,
            crop.right.toFloat() / sourceWidth, 1f - crop.top.toFloat() / sourceHeight)
    }
}
