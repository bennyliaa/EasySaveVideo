package com.easysavevideo.app.library.destination

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.library.destination.*

@Composable
fun OrdinaryDestinationSettings(runtime: AndroidOrdinaryDestinationRuntime, actionScopeKey: Long = 0,
    canAct: () -> Boolean = { true }, showNotice: Boolean = true) {
    val v = LocalEsvVisuals.current
    val actions = remember(runtime, actionScopeKey) {
        DestinationSettingsActions(canAct, {
            DestinationSettingsAvailability(runtime.ready, runtime.savingPreference, runtime.choosingFolder,
                runtime.asking, runtime.admitting, runtime.hasRememberedFolder)
        }, runtime::updatePreference, runtime::requestFolder, runtime::cancelFolderSelection,
            runtime::forgetFolder, runtime::clearNotice)
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    val active = canAct()
    val availability = DestinationSettingsAvailability(runtime.ready, runtime.savingPreference, runtime.choosingFolder,
        runtime.asking, runtime.admitting, runtime.hasRememberedFolder)
    if (!runtime.ready) EsvText(stringResource(R.string.settings_destination_loading), style = v.captionTextStyle)
    if (runtime.savingPreference) EsvText(stringResource(R.string.settings_destination_saving), style = v.captionTextStyle)
    EsvText(stringResource(R.string.ordinary_destination_title), style = v.titleTextStyle)
    EsvText(stringResource(R.string.ordinary_destination_description), style = v.captionTextStyle)
    OrdinaryDestinationPreference.entries.forEach { value ->
        val label = stringResource(ordinaryDestinationLabelResource(value))
        EsvButton(if (runtime.preference == value) stringResource(R.string.ordinary_destination_selected, label) else label,
            onClick = { actions.select(value) }, enabled = active && availability.mayEdit &&
                (value != OrdinaryDestinationPreference.FOLDER_COPY || runtime.hasRememberedFolder || availability.mayChooseFolder),
            modifier = Modifier.fillMaxWidth().semantics { selected = runtime.preference == value })
    }
    EsvText(stringResource(R.string.ordinary_destination_folder_notice), style = v.captionTextStyle)
    EsvButton(stringResource(if (runtime.hasRememberedFolder) R.string.ordinary_destination_reselect_folder else R.string.ordinary_destination_select_folder), onClick = actions::choose,
        enabled = active && availability.mayChooseFolder)
    if (runtime.hasRememberedFolder) EsvButton(stringResource(R.string.ordinary_destination_forget_folder), onClick = actions::forget,
        enabled = active && availability.mayEdit)
    if (runtime.choosingFolder) EsvButton(stringResource(R.string.ordinary_destination_cancel_folder), onClick = actions::cancel, enabled = active)
    EsvText(stringResource(R.string.ordinary_destination_change_notice), style = v.captionTextStyle, color = v.colors.muted)
    if (showNotice) OrdinaryDestinationNotice(runtime, actions::dismiss)
}

@Composable
fun OrdinaryDestinationNotice(runtime: AndroidOrdinaryDestinationRuntime, onDismiss: () -> Unit = runtime::clearNotice) {
    val v = LocalEsvVisuals.current
    runtime.notice?.let {
        EsvText(stringResource(ordinaryDestinationNoticeResource(it)), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.ordinary_destination_close_notice), onClick = onDismiss)
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
                EsvText(stringResource(ordinaryDestinationNoticeResource(notice)))
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
        val display = ordinaryCopyDisplay(status)
        EsvText(stringResource(ordinaryCopyStateResource(display.state),
            stringResource(ordinaryCopyKindResource(display.kind))), style = v.captionTextStyle)
        if (ordinaryCopyCanCancel(status)) EsvButton(stringResource(R.string.ordinary_destination_cancel_copy), onClick = { runtime.cancelCopy(status.creationId) })
    }
    EsvText(stringResource(R.string.ordinary_destination_history_notice), style = v.captionTextStyle, color = v.colors.muted)
    EsvButton(stringResource(R.string.ordinary_destination_reconcile), onClick = runtime::reconcile)
}
