package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.CommonsStage
import com.easysavevideo.discovery.CommonsStageDiagnosticsSnapshot

/** A second fixed UI cap; absence refers only to the retained trace, never to network success/failure. */
internal object CommonsStageDiagnosticsPresentation {
    const val MAX_PREVIEW_ROWS = 32
    fun rows(snapshot: CommonsStageDiagnosticsSnapshot) = (snapshot.samples + snapshot.activeStages).takeLast(MAX_PREVIEW_ROWS)
    fun unobservedStages(snapshot: CommonsStageDiagnosticsSnapshot): List<CommonsStage> {
        val observed = (snapshot.samples + snapshot.activeStages).mapTo(mutableSetOf()) { it.stage }
        return CommonsStage.entries.filterNot { it in observed }
    }
    fun truncated(snapshot: CommonsStageDiagnosticsSnapshot): Boolean =
        snapshot.samples.size + snapshot.activeStages.size > MAX_PREVIEW_ROWS || snapshot.droppedSamples > 0 || snapshot.droppedOperations > 0
    fun partial(snapshot: CommonsStageDiagnosticsSnapshot): Boolean =
        snapshot.incomplete || snapshot.sessionOpen || snapshot.sessionAlias == null
}
