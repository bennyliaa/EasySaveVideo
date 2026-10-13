package com.easysavevideo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.easysavevideo.app.theme.EsvComposeVisuals

/** The actual host selects the role; window geometry and filter state remain outside this mapper. */
internal enum class DiscoveryFilterPanelPresentation { INLINE, FLOATING }

internal data class DiscoveryFilterPanelVisuals(val background: Color, val corner: Dp)

/** Uses the existing detached SkinKit frame without adding a provider or changing control visuals. */
internal fun discoveryFilterPanelVisuals(v: EsvComposeVisuals, presentation: DiscoveryFilterPanelPresentation) = when (presentation) {
    DiscoveryFilterPanelPresentation.INLINE -> DiscoveryFilterPanelVisuals(v.colors.surface, v.cardCorner)
    DiscoveryFilterPanelPresentation.FLOATING -> DiscoveryFilterPanelVisuals(v.colors.elevated, v.sheetCorner)
}
