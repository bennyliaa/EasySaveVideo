package com.easysavevideo.library.edit

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Unit coordinates in the rotated, visible video, not the containing preview view. */
data class NormalizedCrop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        if (listOf(left, top, right, bottom).any { !it.isFinite() || it < 0.0 || it > 1.0 } ||
            left >= right || top >= bottom) throw EditFailure(EditError.INVALID_CROP)
    }
    companion object { val FULL = NormalizedCrop(0.0, 0.0, 1.0, 1.0) }
}

/** Half-open pixel boundaries; all edges are even for AVC 4:2:0 chroma alignment. */
data class PixelCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        if (left < 0 || top < 0 || right <= left || bottom <= top ||
            listOf(left, top, right, bottom).any { it % 2 != 0 }) throw EditFailure(EditError.INVALID_CROP)
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class ViewportRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        if (listOf(left, top, right, bottom).any { !it.isFinite() } || left >= right || top >= bottom) {
            throw EditFailure(EditError.INVALID_CROP)
        }
    }
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

data class EditDimensions(val width: Int, val height: Int)

object EditGeometry {
    fun displayDimensions(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): EditDimensions {
        requireDimensions(sourceWidth, sourceHeight, rotationDegrees)
        return if (rotationDegrees == 90 || rotationDegrees == 270) EditDimensions(sourceHeight, sourceWidth)
        else EditDimensions(sourceWidth, sourceHeight)
    }

    /** Fit-center content rectangle. Insets must first be removed from the supplied viewport. */
    fun viewportContent(
        viewportWidth: Double,
        viewportHeight: Double,
        displayWidth: Int,
        displayHeight: Int,
    ): ViewportRect {
        if (!viewportWidth.isFinite() || !viewportHeight.isFinite() || viewportWidth <= 0 || viewportHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0) throw EditFailure(EditError.INVALID_CROP)
        val scale = min(viewportWidth / displayWidth, viewportHeight / displayHeight)
        // Multiplication may overshoot the limiting viewport edge by one floating-point ULP.
        // Clamp that edge exactly: a tiny negative origin could otherwise trim two pixels when
        // a full-viewport selection is later rounded inward to even pixel boundaries.
        val width = min(viewportWidth, displayWidth * scale)
        val height = min(viewportHeight, displayHeight * scale)
        val left = (viewportWidth - width) / 2
        val top = (viewportHeight - height) / 2
        return ViewportRect(left, top, left + width, top + height)
    }

    /**
     * Intersect user selection with the displayed content, excluding letterbox/pillarbox bars.
     * A selection wholly within bars is invalid. Do not silently interpret bars as image pixels.
     */
    fun selectionToNormalized(
        selection: ViewportRect,
        viewportWidth: Double,
        viewportHeight: Double,
        displayWidth: Int,
        displayHeight: Int,
    ): NormalizedCrop {
        val content = viewportContent(viewportWidth, viewportHeight, displayWidth, displayHeight)
        val left = max(selection.left, content.left)
        val top = max(selection.top, content.top)
        val right = min(selection.right, content.right)
        val bottom = min(selection.bottom, content.bottom)
        if (left >= right || top >= bottom) throw EditFailure(EditError.INVALID_CROP)
        return NormalizedCrop(
            ((left - content.left) / content.width).coerceIn(0.0, 1.0),
            ((top - content.top) / content.height).coerceIn(0.0, 1.0),
            ((right - content.left) / content.width).coerceIn(0.0, 1.0),
            ((bottom - content.top) / content.height).coerceIn(0.0, 1.0),
        )
    }

    fun normalizedToViewport(
        crop: NormalizedCrop,
        viewportWidth: Double,
        viewportHeight: Double,
        displayWidth: Int,
        displayHeight: Int,
    ): ViewportRect {
        val content = viewportContent(viewportWidth, viewportHeight, displayWidth, displayHeight)
        return ViewportRect(
            content.left + crop.left * content.width,
            content.top + crop.top * content.height,
            content.left + crop.right * content.width,
            content.top + crop.bottom * content.height,
        )
    }

    /** Inward rounding never expands beyond the user's selection; sub-two-pixel crops fail. */
    fun displayCrop(crop: NormalizedCrop, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): PixelCrop {
        val size = displayDimensions(sourceWidth, sourceHeight, rotationDegrees)
        return PixelCrop(
            inwardStart(crop.left * size.width), inwardStart(crop.top * size.height),
            inwardEnd(crop.right * size.width), inwardEnd(crop.bottom * size.height),
        )
    }

    /** Inverse of Android's clockwise display rotation, using boundaries rather than pixel centers. */
    fun toSource(display: PixelCrop, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): PixelCrop {
        val size = displayDimensions(sourceWidth, sourceHeight, rotationDegrees)
        if (display.right > size.width || display.bottom > size.height) throw EditFailure(EditError.INVALID_CROP)
        return when (rotationDegrees) {
            0 -> display
            90 -> PixelCrop(display.top, sourceHeight - display.right, display.bottom, sourceHeight - display.left)
            180 -> PixelCrop(sourceWidth - display.right, sourceHeight - display.bottom,
                sourceWidth - display.left, sourceHeight - display.top)
            270 -> PixelCrop(sourceWidth - display.bottom, display.left, sourceWidth - display.top, display.right)
            else -> throw EditFailure(EditError.UNSUPPORTED_PROFILE)
        }
    }

    fun toDisplay(source: PixelCrop, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): PixelCrop {
        requireDimensions(sourceWidth, sourceHeight, rotationDegrees)
        if (source.right > sourceWidth || source.bottom > sourceHeight) throw EditFailure(EditError.INVALID_CROP)
        return when (rotationDegrees) {
            0 -> source
            90 -> PixelCrop(sourceHeight - source.bottom, source.left, sourceHeight - source.top, source.right)
            180 -> PixelCrop(sourceWidth - source.right, sourceHeight - source.bottom,
                sourceWidth - source.left, sourceHeight - source.top)
            270 -> PixelCrop(source.top, sourceWidth - source.right, source.bottom, sourceWidth - source.left)
            else -> throw EditFailure(EditError.UNSUPPORTED_PROFILE)
        }
    }

    private fun inwardStart(value: Double): Int = (ceil(value / 2) * 2).toInt()
    private fun inwardEnd(value: Double): Int = (floor(value / 2) * 2).toInt()
    private fun requireDimensions(width: Int, height: Int, rotation: Int) {
        if (width !in 2..EditLimits.MAX_DIMENSION || height !in 2..EditLimits.MAX_DIMENSION ||
            width % 2 != 0 || height % 2 != 0 || width.toLong() * height > EditLimits.MAX_PIXELS ||
            rotation !in setOf(0, 90, 180, 270)) throw EditFailure(EditError.UNSUPPORTED_PROFILE)
    }
}
