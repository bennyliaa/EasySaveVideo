package com.easysavevideo.app.ui

import kotlin.math.floor

internal data class DiscoveryResponsiveLayout(val columns: Int, val stackSearch: Boolean)

/** Layout geometry only: never rewrites the persisted column preference or business state.
 * 160dp is the compact card/input width budget; text scales, never shrinks to fit.
 * Gaps are supplied by the existing SkinKit snapshot rather than a second theme. */
internal fun discoveryResponsiveLayout(widthDp: Float, fontScale: Float, requestedColumns: Int,
    gapDp: Float): DiscoveryResponsiveLayout {
    val preference = requestedColumns.coerceIn(1, 6)
    if (!widthDp.isFinite() || widthDp <= 0 || !fontScale.isFinite() || fontScale <= 0 ||
        !gapDp.isFinite() || gapDp < 0) return DiscoveryResponsiveLayout(1, true)
    val scale = fontScale.coerceAtLeast(1f)
    val cardWidth = 160.0 * scale
    val fits = floor((widthDp.toDouble() + gapDp) / (cardWidth + gapDp)).coerceIn(1.0, 6.0).toInt()
    return DiscoveryResponsiveLayout(minOf(preference, fits), widthDp < 260.0 * scale + gapDp)
}
