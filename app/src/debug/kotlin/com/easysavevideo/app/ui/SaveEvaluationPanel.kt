package com.easysavevideo.app.ui

import androidx.compose.runtime.*
import com.easysavevideo.app.save.DebugSaveEvaluationOwner
import com.easysavevideo.discovery.SaveEvaluationSessionReport
import com.easysavevideo.discovery.SaveEvaluationMode

import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*

/** Operator-only UI, compiled in debug source set. Preview is not a file export or device acceptance. */
@Composable internal fun SaveEvaluationPanel(owner: DebugSaveEvaluationOwner, fixture: Boolean, finish: () -> SaveEvaluationSessionReport?,
    exportState: com.easysavevideo.app.diagnostics.DiagnosticExportState,
    onExport: (SaveEvaluationSessionReport) -> Boolean, onCancelExport: () -> Unit) {
    DisposableEffect(owner) { onDispose { onCancelExport() } }
    var exportRefused by remember { mutableStateOf(false) }
    var report by remember(owner) { mutableStateOf(owner.snapshot()) }
    EsvText(stringResource(R.string.evaluation_notice))
    EsvButton(stringResource(R.string.evaluation_begin), onClick = { onCancelExport(); owner.begin(fixture, SaveEvaluationMode.JOINT); report = owner.snapshot(); exportRefused = false }, enabled = !owner.active())
    EsvButton(stringResource(R.string.evaluation_begin_record), onClick = { onCancelExport(); owner.begin(fixture, SaveEvaluationMode.INDEPENDENT_RECORD); report = owner.snapshot(); exportRefused = false }, enabled = !owner.active())
    EsvButton(stringResource(R.string.evaluation_preview), onClick = { report = owner.snapshot() })
    EsvButton(stringResource(R.string.evaluation_end), onClick = { onCancelExport(); report = finish(); exportRefused = false }, enabled = owner.active())
    report?.let { value ->
        EsvText("${value.evaluation.evidence} / ${value.mode} / ${value.state} / partial=${value.evaluation.overall.partial} / unfinished=${value.unfinishedMethodAttempts}")
        val rows = listOf("ALL" to value.evaluation.overall) + value.evaluation.byProvider.entries.take(32).mapIndexed { index, row -> "source-${index + 1} (${row.key.take(128)})" to row.value }
        fun percent(value: Double?) = value?.let { "${kotlin.math.round(it * 1000) / 10}%" } ?: "?"
        for ((label, c) in rows) EsvText("$label: N=${c.actualTestPages}; ①=${c.save1.succeeded}/${c.actualTestPages} (${percent(c.save1Share)}), ②=${c.save2.succeeded}/${c.actualTestPages} (${percent(c.save2Share)}); untested=${c.save1.notTested}/${c.save2.notTested}; blocked=${c.save1.blocked}/${c.save2.blocked}")
        for ((label, c) in rows) {
            val independent = value.mode == SaveEvaluationMode.INDEPENDENT_RECORD
            val recording = if (independent) c.independentRecord else c.save3
            val share = if (independent) c.independentRecordShare else c.jointRecordShare
            val coverage = if (independent) c.independentRecordTestCoverage else c.jointRecordTestCoverage
            EsvText("$label: ③ ${value.mode}=${recording.succeeded}/${c.actualTestPages} (${percent(share)}); tested=${recording.tested}; coverage=${percent(coverage)}; failed=${recording.failed}; blocked=${recording.blocked}; untested=${recording.notTested}; skipped①②=${c.save1.skipped}/${c.save2.skipped}")
            if (!independent) EsvText("$label: only③=${c.fallbackSave3.succeeded}/${c.actualTestPages} (${percent(c.fallbackShare)}); both-primary-failed=${c.fallbackEligiblePages}")
        }
        EsvText(stringResource(R.string.evaluation_record_scope))
        EsvText(stringResource(R.string.evaluation_unavailable))
        if (value.state == com.easysavevideo.discovery.SaveEvaluationSessionState.FINISHED) {
            EsvText(stringResource(R.string.evaluation_export_notice))
            EsvButton(stringResource(R.string.evaluation_export), onClick = { exportRefused = !onExport(value) }, enabled = !exportState.busy)
        }
        if (exportRefused) EsvText(stringResource(R.string.evaluation_export_refused))
        EsvText("JSON: ${exportState.phase}")
        if (exportState.busy) EsvButton(stringResource(R.string.diagnostics_cancel), onClick = onCancelExport)
        if (exportState.releaseUnconfirmed) EsvText(stringResource(R.string.diagnostics_release_unconfirmed))
    }
}
