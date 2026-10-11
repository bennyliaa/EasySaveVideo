package com.easysavevideo.app.ui

internal data class ResponsivePaneLayout(val split: Boolean, val sideWidthDp: Float,
    val headerMaxHeightDp: Float, val footerMaxHeightDp: Float)

/** Remaining content geometry, not another window or theme authority. */
internal fun responsivePaneLayout(widthDp: Float, heightDp: Float, fontScale: Float,
    gapDp: Float): ResponsivePaneLayout {
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0 || heightDp <= 0 ||
        !fontScale.isFinite() || fontScale <= 0 || !gapDp.isFinite() || gapDp < 0)
        return ResponsivePaneLayout(false, 0f, 0f, 0f)
    val scale = fontScale.coerceAtLeast(1f)
    val split = widthDp >= maxOf(840.0, 720.0 * scale + gapDp) && heightDp >= 480.0 * scale
    val usableHeight = (heightDp - 2 * gapDp).coerceAtLeast(0f)
    return ResponsivePaneLayout(split, if (split) (320.0 * scale).toFloat() else 0f,
        minOf(usableHeight * .45f, 240f * scale), minOf(usableHeight * .25f, 96f * scale))
}
