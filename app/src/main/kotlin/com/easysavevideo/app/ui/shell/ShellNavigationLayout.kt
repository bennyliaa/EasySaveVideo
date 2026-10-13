package com.easysavevideo.app.ui.shell

/** Shell geometry only. Official destinations, navigation and business state remain unchanged. */
internal data class ShellNavigationLayout(
    val rail: Boolean,
    val railWidthDp: Float,
    val headerMaxHeightDp: Float,
    val bottomMaxHeightDp: Float,
)

/**
 * Text keeps the actual system scale and wraps inside standard Material3 items.
 * A rail can grow with text, but cannot consume the content width unchecked.
 * Finite scroll viewports retain all controls in short windows without clipping
 * their text or measuring a vertical scroller with infinite height.
 */
internal fun shellNavigationLayout(widthDp: Float, heightDp: Float, fontScale: Float,
    wideWindow: Boolean, lowWindow: Boolean, horizontalInsetsDp: Float = 0f): ShellNavigationLayout {
    val width = widthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
    val height = heightDp.takeIf { it.isFinite() && it > 0f } ?: 480f
    val scale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(1f) ?: 1f
    val insets = horizontalInsetsDp.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val availableWidth = (width - insets).coerceAtLeast(0f)
    // Admitted Material3 1.3.2 uses three 8dp gaps between the four items.
    val minimumFourItemWidth = 4 * 48f + 3 * 8f
    val rail = wideWindow || lowWindow || availableWidth < minimumFourItemWidth
    val railWidth = (80f * scale).coerceAtMost(maxOf(80f, availableWidth * .4f)).coerceAtMost(availableWidth)
    return ShellNavigationLayout(rail, railWidth,
        (height * .25f).coerceAtLeast(48f).coerceAtMost(height),
        (height * .4f).coerceAtLeast(48f).coerceAtMost(height))
}
