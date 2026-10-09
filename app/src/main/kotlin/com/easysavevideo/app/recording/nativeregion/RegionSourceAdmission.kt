package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.CaptureDisplayGeometry
import com.easysavevideo.recording.CaptureScopeEvidence
import com.easysavevideo.recording.RegionCaptureRecipe

/**
 * Old-platform default-display contract and API34 callback admission are intentionally distinct.
 * No requested VirtualDisplay/SurfaceTexture dimensions count as an observed source. API31–33
 * use actual default-display metrics plus the latched unscaled surface transform. API34+ also
 * require actual captured-content callback dimensions; equal dimensions do not identify content.
 */
internal object RegionSourceAdmission {
    fun readyForFrame(recipe: RegionCaptureRecipe, apiLevel: Int, actualDisplay: CaptureDisplayGeometry?,
        capturedContentSize: Pair<Int, Int>?): Boolean {
        if (apiLevel < 31 || actualDisplay != recipe.source) return false
        return if (apiLevel <= 33) {
            recipe.scopeEvidence == CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY
        } else {
            recipe.scopeEvidence == CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY &&
                capturedContentSize == (recipe.source.width to recipe.source.height)
        }
    }

    fun canEncode(recipe: RegionCaptureRecipe, apiLevel: Int, actualDisplay: CaptureDisplayGeometry?,
        capturedContentSize: Pair<Int, Int>?, latchedTransform: FloatArray?): Boolean =
        readyForFrame(recipe, apiLevel, actualDisplay, capturedContentSize) &&
            latchedTransform != null && RegionTexturePolicy.isUnscaledFullImage(latchedTransform)
}
