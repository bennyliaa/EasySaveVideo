package com.easysavevideo.app.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*

@Composable
internal fun DiagnosticSettings(state: DiagnosticExportState, onExport: () -> Unit, onCancel: () -> Unit) {
    DisposableEffect(Unit) { onDispose { onCancel() } }
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.diagnostics_description), style = v.captionTextStyle)
    EsvText(stringResource(R.string.diagnostics_provider_warning), style = v.captionTextStyle)
    EsvText(stringResource(when (state.phase) {
        DiagnosticExportPhase.IDLE -> R.string.diagnostics_idle
        DiagnosticExportPhase.PREPARING -> R.string.diagnostics_preparing
        DiagnosticExportPhase.CHOOSING -> R.string.diagnostics_choosing
        DiagnosticExportPhase.WRITING -> R.string.diagnostics_writing
        DiagnosticExportPhase.CANCELLING -> R.string.diagnostics_cancelling
        DiagnosticExportPhase.EMPTY -> R.string.diagnostics_empty
        DiagnosticExportPhase.SUCCEEDED -> R.string.diagnostics_succeeded
        DiagnosticExportPhase.FAILED -> R.string.diagnostics_failed
        DiagnosticExportPhase.CANCELLED -> R.string.diagnostics_cancelled
        DiagnosticExportPhase.TIMED_OUT -> R.string.diagnostics_timed_out
        DiagnosticExportPhase.BLOCKED -> R.string.diagnostics_blocked
    }))
    if (state.releaseUnconfirmed) EsvText(stringResource(R.string.diagnostics_release_unconfirmed), style = v.captionTextStyle)
    if (!state.busy) EsvButton(stringResource(R.string.diagnostics_export), onExport)
    else if (state.phase in setOf(DiagnosticExportPhase.PREPARING, DiagnosticExportPhase.CHOOSING, DiagnosticExportPhase.WRITING)) EsvButton(stringResource(R.string.diagnostics_cancel), onCancel)
}
