package com.easysavevideo.app.ui

import androidx.compose.runtime.*
import com.easysavevideo.app.save.DebugSaveEvaluationOwner
import com.easysavevideo.discovery.SaveEvaluationSessionReport
/** Release has no evaluation controls or activation path. */
@Composable internal fun SaveEvaluationPanel(owner: DebugSaveEvaluationOwner, fixture: Boolean, finish: () -> SaveEvaluationSessionReport?,
    exportState: com.easysavevideo.app.diagnostics.DiagnosticExportState,
    onExport: (SaveEvaluationSessionReport) -> Boolean, onCancelExport: () -> Unit) { }
