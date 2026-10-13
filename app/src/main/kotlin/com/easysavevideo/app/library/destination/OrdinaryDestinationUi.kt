package com.easysavevideo.app.library.destination

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.library.destination.*

@Composable
fun OrdinaryDestinationSettings(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.ordinary_destination_title), style = v.titleTextStyle)
    EsvText(stringResource(R.string.ordinary_destination_description), style = v.captionTextStyle)
    OrdinaryDestinationPreference.entries.forEach { value ->
        val label = stringResource(ordinaryDestinationLabelResource(value))
        EsvButton(if (runtime.preference == value) stringResource(R.string.ordinary_destination_selected, label) else label,
            onClick = { runtime.updatePreference(value) }, enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder,
            modifier = Modifier.fillMaxWidth())
    }
    EsvText(stringResource(R.string.ordinary_destination_folder_notice), style = v.captionTextStyle)
    EsvButton(stringResource(if (runtime.hasRememberedFolder) R.string.ordinary_destination_reselect_folder else R.string.ordinary_destination_select_folder), onClick = runtime::requestFolder,
        enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder)
    if (runtime.hasRememberedFolder) EsvButton(stringResource(R.string.ordinary_destination_forget_folder), onClick = runtime::forgetFolder,
        enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder)
    if (runtime.choosingFolder) EsvButton(stringResource(R.string.ordinary_destination_cancel_folder), onClick = runtime::cancelFolderSelection)
    EsvText(stringResource(R.string.ordinary_destination_change_notice), style = v.captionTextStyle, color = v.colors.muted)
    OrdinaryDestinationNotice(runtime)
}

@Composable
fun OrdinaryDestinationNotice(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    // Runtime notices remain source text until the typed-message migration.
    runtime.notice?.let {
        EsvText(it, style = v.captionTextStyle)
        EsvButton(stringResource(R.string.ordinary_destination_close_notice), onClick = runtime::clearNotice)
    }
    if (runtime.admitting) EsvText(stringResource(R.string.ordinary_destination_admitting), style = v.captionTextStyle)
}

@Composable
fun OrdinaryDestinationDialog(runtime: AndroidOrdinaryDestinationRuntime) {
    if (!runtime.asking) {
        val notice = runtime.notice ?: return
        val v = LocalEsvVisuals.current
        Dialog(onDismissRequest = runtime::clearNotice) {
            Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText(stringResource(R.string.ordinary_destination_title), style = v.titleTextStyle)
                EsvText(notice)
                EsvButton(stringResource(R.string.ordinary_destination_acknowledge), onClick = runtime::clearNotice)
            }
        }
        return
    }
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = runtime::cancelSelection) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(stringResource(R.string.ordinary_destination_prompt_title), style = v.titleTextStyle)
            EsvText(stringResource(R.string.ordinary_destination_prompt_description))
            EsvButton(stringResource(R.string.ordinary_destination_app_library), onClick = { runtime.choose(OrdinaryDestinationChoice.APP_LIBRARY) }, modifier = Modifier.fillMaxWidth())
            EsvButton(stringResource(R.string.ordinary_destination_album_copy), onClick = { runtime.choose(OrdinaryDestinationChoice.ALBUM_COPY) }, modifier = Modifier.fillMaxWidth())
            EsvButton(stringResource(R.string.ordinary_destination_remembered_folder_copy), onClick = { runtime.choose(OrdinaryDestinationChoice.FOLDER_COPY) },
                enabled = runtime.hasRememberedFolder, modifier = Modifier.fillMaxWidth())
            EsvText(stringResource(R.string.ordinary_destination_prompt_folder_notice), style = v.captionTextStyle)
            EsvButton(stringResource(R.string.ordinary_destination_cancel_creation), onClick = runtime::cancelSelection, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun OrdinaryDestinationHistory(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.ordinary_destination_history_title), style = v.titleTextStyle)
    OrdinaryDestinationNotice(runtime)
    runtime.statuses.filter { it.internalSaved }.take(8).forEach { status ->
        EsvText(stringResource(R.string.ordinary_destination_task_summary, status.creationId.take(8)), style = v.captionTextStyle)
        // Copy status remains source text until the typed-message migration.
        EsvText(ordinaryCopyStatus(status), style = v.captionTextStyle)
        if (ordinaryCopyCanCancel(status)) EsvButton(stringResource(R.string.ordinary_destination_cancel_copy), onClick = { runtime.cancelCopy(status.creationId) })
    }
    EsvText(stringResource(R.string.ordinary_destination_history_notice), style = v.captionTextStyle, color = v.colors.muted)
    EsvButton(stringResource(R.string.ordinary_destination_reconcile), onClick = runtime::reconcile)
}
