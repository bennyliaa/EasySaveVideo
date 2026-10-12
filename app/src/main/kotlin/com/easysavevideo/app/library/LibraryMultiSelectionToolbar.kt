package com.easysavevideo.app.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryMultiSelectionToolbar(count: Int, interactive: Boolean, single: Boolean, exportAvailable: Boolean,
    onPage: () -> Unit, onClear: () -> Unit, onCancel: () -> Unit, onDetails: () -> Unit,
    onRename: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier) {
    val v = LocalEsvVisuals.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.library_multi_count, count), style = v.titleTextStyle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvButton(stringResource(R.string.library_multi_page), onPage, enabled = interactive)
            EsvButton(stringResource(R.string.library_multi_clear), onClear, enabled = count > 0)
            EsvButton(stringResource(R.string.library_multi_cancel), onCancel)
            EsvButton(stringResource(R.string.library_multi_details), onDetails, enabled = single)
            EsvButton(stringResource(R.string.library_multi_rename), onRename, enabled = single)
            EsvButton(stringResource(R.string.library_multi_move), onMove, enabled = interactive && count > 0)
            EsvButton(stringResource(R.string.library_multi_export), onExport, enabled = single && exportAvailable)
        }
        EsvText(stringResource(R.string.library_multi_single_only), style = v.captionTextStyle, color = v.colors.muted)
        if (!exportAvailable) EsvText(stringResource(R.string.flow_export_unavailable), style = v.captionTextStyle, color = v.colors.muted)
    }
}
