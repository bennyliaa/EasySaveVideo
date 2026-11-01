package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.AndroidDiscoveryController
import com.easysavevideo.app.discovery.CommonsStageDiagnosticsPresentation
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText

/** Debug source-set entry only. Every action reads or updates bounded local diagnostics, never a search. */
@Composable internal fun CommonsStageDiagnosticsPanel(controller: AndroidDiscoveryController) {
    if (!BuildConfig.DEBUG) return
    val preview = controller.commonsStageDiagnosticsPreview
    val currentRevision by rememberUpdatedState(preview.revision)
    DisposableEffect(controller) {
        onDispose { controller.stopCommonsStageDiagnostics(currentRevision) }
    }
    val state = controller.state
    val lifecycle = controller.cardNavigationLifecycle
    val snapshot = preview.diagnostics ?: return
    EsvText(stringResource(R.string.commons_stage_title))
    EsvText(stringResource(R.string.commons_stage_notice))
    EsvText(stringResource(when {
        preview.armed -> R.string.commons_stage_armed
        snapshot.sessionOpen -> R.string.commons_stage_collecting
        snapshot.sessionAlias != null -> R.string.commons_stage_sealed
        else -> R.string.commons_stage_idle
    }))
    EsvButton(stringResource(R.string.commons_stage_start), onClick = {
        controller.startCommonsStageDiagnostics(preview.revision, state.controlTarget, lifecycle)
    }, enabled = !preview.active && state.ready && !state.storageUnavailable &&
        state.controlTarget != null && !controller.filterControlsBusy)
    EsvButton(stringResource(R.string.commons_stage_stop), onClick = {
        controller.stopCommonsStageDiagnostics(preview.revision)
    }, enabled = preview.active)
    EsvButton(stringResource(R.string.commons_stage_preview), onClick = {
        controller.previewCommonsStageDiagnostics(preview.revision)
    })
    val rows = CommonsStageDiagnosticsPresentation.rows(snapshot)
    EsvText(stringResource(R.string.commons_stage_counts, snapshot.samples.size, snapshot.capacity, snapshot.activeStages.size, rows.size,
        CommonsStageDiagnosticsPresentation.truncated(snapshot).toString(),
        CommonsStageDiagnosticsPresentation.partial(snapshot).toString(), snapshot.pendingOperations))
    EsvText(stringResource(R.string.commons_stage_dropped, snapshot.droppedOperations, snapshot.droppedSamples,
        snapshot.ignoredEvents, snapshot.clockAnomalies))
    for (stage in CommonsStageDiagnosticsPresentation.unobservedStages(snapshot)) {
        EsvText(stringResource(R.string.commons_stage_not_observed, stage.name))
    }
    for (sample in rows) {
        val elapsed = sample.elapsedNanos?.let { stringResource(R.string.commons_stage_elapsed, it) }
            ?: stringResource(R.string.commons_stage_not_measured)
        EsvText(stringResource(R.string.commons_stage_row, sample.sessionAlias, sample.operationAlias, sample.attempt,
            sample.stage.name, sample.outcome.name, elapsed, sample.timing.name, sample.failure?.name ?: "NONE",
            sample.httpStatus?.toString() ?: "NOT_OBSERVED"))
    }
    EsvText(stringResource(R.string.commons_stage_scope))
}
