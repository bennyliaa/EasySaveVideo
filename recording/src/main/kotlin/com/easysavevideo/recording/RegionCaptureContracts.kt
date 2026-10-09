package com.easysavevideo.recording

import kotlin.math.ceil
import kotlin.math.floor

/** M6-B crops one whole system-authorized capture frame before encoding, without scaling. */
object RegionCaptureLimits {
    const val TRANSFORM_VERSION = 1
    const val MIN_ENCODED_DIMENSION = 128
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 8_847_360L
}

/** Coordinates refer to the ENTIRE frozen display geometry, including system bars. */
data class RegionNormalizedRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
    companion object { val FULL = RegionNormalizedRect(0.0, 0.0, 1.0, 1.0) }
}

/** Insets are frozen reference metadata. They are never removed from normalized coordinates. */
data class CaptureInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    init { require(listOf(left, top, right, bottom).all { it in 0..RegionCaptureLimits.MAX_DIMENSION }) }
}

/**
 * Width and height are already in current displayed orientation, from actual default-display
 * metrics, not the activity window, encoder profile, or requested virtual-display dimensions.
 * Rotation records the platform display rotation; do not swap dimensions a second time.
 */
data class CaptureDisplayGeometry(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val insets: CaptureInsets = CaptureInsets(),
    val transformVersion: Int = RegionCaptureLimits.TRANSFORM_VERSION,
) {
    init {
        require(width in RegionCaptureLimits.MIN_ENCODED_DIMENSION..RegionCaptureLimits.MAX_DIMENSION)
        require(height in RegionCaptureLimits.MIN_ENCODED_DIMENSION..RegionCaptureLimits.MAX_DIMENSION)
        require(width.toLong() * height <= RegionCaptureLimits.MAX_PIXELS)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(insets.left.toLong() + insets.right < width && insets.top.toLong() + insets.bottom < height)
        require(transformVersion > 0)
    }
}

/**
 * Records the known legacy default-display route or the API34+ explicit default-display REQUEST.
 * A request is not OS proof of selected content identity. The system can let the user change scope;
 * neither this enum nor matching dimensions proves that a particular display/app was selected.
 */
enum class CaptureScopeEvidence {
    API31_TO_33_DEFAULT_DISPLAY,
    API34_PLUS_EXPLICIT_DEFAULT_DISPLAY,
}

/** An actual observation, never a requested VD/SurfaceTexture size or an encoder setting. */
enum class CaptureGeometryProvenance {
    DEFAULT_DISPLAY_METRICS_AND_SURFACE,
    DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK,
}

/**
 * Adapter geometry evidence after consent, tied to the capture monotonic clock. A content-size
 * callback proves dimensions only, not app/display identity. The recipe applies its schematic
 * normalized selection to the whole actual system-authorized frame only when geometry matches.
 */
data class CaptureGeometryObservation(
    val geometry: CaptureDisplayGeometry,
    val scopeEvidence: CaptureScopeEvidence,
    val provenance: CaptureGeometryProvenance,
    val observedAtMonotonicUs: Long,
) {
    init { require(observedAtMonotonicUs >= 0) }
}

/** Half-open absolute display-pixel boundaries, inward aligned on every edge. */
data class PixelCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
        require(listOf(left, top, right, bottom).all { it % 2 == 0 })
        require(right <= RegionCaptureLimits.MAX_DIMENSION && bottom <= RegionCaptureLimits.MAX_DIMENSION)
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class RegionCaptureDataRoute { SELECTED_PIXELS_BEFORE_ENCODING }

/**
 * Frozen user intention only, never proof of a projection grant or actual geometry. The OS may
 * supply a full authorized frame transiently; only pixelCrop is admitted to the encoder. No full-screen
 * file fallback is represented by this contract. A geometry change requires a fresh selection.
 * The recipe does not bind any earlier screen content or app identity to a later OS selection.
 */
data class RegionCaptureRecipe(
    val bounds: RegionNormalizedRect,
    val source: CaptureDisplayGeometry,
    val scopeEvidence: CaptureScopeEvidence,
    val selectedAtMonotonicUs: Long = 0,
) {
    val pixelCrop: PixelCrop = PixelCrop(
        (ceil(bounds.left * source.width / 2) * 2).toInt(),
        (ceil(bounds.top * source.height / 2) * 2).toInt(),
        (floor(bounds.right * source.width / 2) * 2).toInt(),
        (floor(bounds.bottom * source.height / 2) * 2).toInt(),
    )
    init {
        require(selectedAtMonotonicUs >= 0)
        require(source.transformVersion == RegionCaptureLimits.TRANSFORM_VERSION)
        require(pixelCrop.width >= RegionCaptureLimits.MIN_ENCODED_DIMENSION)
        require(pixelCrop.height >= RegionCaptureLimits.MIN_ENCODED_DIMENSION)
    }
    val encodedWidth: Int get() = pixelCrop.width
    val encodedHeight: Int get() = pixelCrop.height
    val encodedRotationDegrees: Int get() = 0
    val dataRoute: RegionCaptureDataRoute get() = RegionCaptureDataRoute.SELECTED_PIXELS_BEFORE_ENCODING
}

/** Only the strict policy issues this geometry binding. This is not a reusable consent grant. */
class ConfirmedRegionCapture internal constructor(
    val recipe: RegionCaptureRecipe,
    val observation: CaptureGeometryObservation,
) {
    val pixelCrop: PixelCrop get() = recipe.pixelCrop
    val encodedRotationDegrees: Int get() = 0
}

object RegionCapturePolicy {
    fun matches(recipe: RegionCaptureRecipe, observation: CaptureGeometryObservation): Boolean =
        recipe.source == observation.geometry && recipe.scopeEvidence == observation.scopeEvidence &&
            observation.observedAtMonotonicUs >= recipe.selectedAtMonotonicUs &&
            when (observation.scopeEvidence) {
                CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY ->
                    observation.provenance == CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE
                CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY ->
                    observation.provenance == CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK
            }

    /** Mismatch must stop before another frame; it may not reinterpret or resize the selection. */
    fun confirm(recipe: RegionCaptureRecipe, observation: CaptureGeometryObservation): ConfirmedRegionCapture {
        if (!matches(recipe, observation)) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
        return ConfirmedRegionCapture(recipe, observation)
    }

    /** Profile fps is an encoder request, not evidence of actual frame timing. */
    fun outputProfile(recipe: RegionCaptureRecipe, requested: RecordingProfile): RecordingProfile =
        requested.copy(width = recipe.encodedWidth, height = recipe.encodedHeight)

    fun profileMatches(recipe: RegionCaptureRecipe, actual: RecordingProfile): Boolean =
        actual.width == recipe.encodedWidth && actual.height == recipe.encodedHeight
}
