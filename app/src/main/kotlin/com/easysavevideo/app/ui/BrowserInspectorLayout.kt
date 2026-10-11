package com.easysavevideo.app.ui

internal data class BrowserInspectorLayout(val sidePane: Boolean, val modal: Boolean)

/** Placement only. No detection, selection ownership, navigation or save effects. */
internal fun browserInspectorLayout(pane: ResponsivePaneLayout, hasCandidates: Boolean,
    streamSelection: Boolean, otherPanelOpen: Boolean): BrowserInspectorLayout {
    val side = pane.split && hasCandidates && !streamSelection && !otherPanelOpen
    return BrowserInspectorLayout(side, !side && (hasCandidates || streamSelection))
}

internal fun browserInspectEnabled(pageReady: Boolean, scanning: Boolean,
    hasCandidates: Boolean, streamSelection: Boolean): Boolean =
    pageReady && !scanning && !hasCandidates && !streamSelection
