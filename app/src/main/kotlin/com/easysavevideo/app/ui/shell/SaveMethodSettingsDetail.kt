package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.core.model.TaskMethod

/** Three independent choices. The existing settings page owns scrolling and persistence. */
@Composable
internal fun SaveMethodSettingsDetail(settings: SaveSettings, editable: Boolean,
    onEdit: (SettingsEdit.SaveMethodEnabled) -> Unit) {
    val v=LocalEsvVisuals.current
    val enabledNow by rememberUpdatedState(editable)
    val editNow by rememberUpdatedState(onEdit)
    val actions=remember { SaveMethodSettingsActions({enabledNow}) { editNow(it) } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    EsvText(stringResource(R.string.save_methods_title),style=v.sectionTextStyle)
    if (!editable) EsvText(stringResource(R.string.save_methods_waiting),style=v.captionTextStyle)
    for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD)) {
        val label = when (method) {
            TaskMethod.DIRECT -> R.string.save_methods_direct
            TaskMethod.STREAM -> R.string.save_methods_stream
            else -> R.string.save_methods_record
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(
            value=settings.isMethodEnabled(method),enabled=editable,role=Role.Switch,
            onValueChange={actions.edit(SettingsEdit.SaveMethodEnabled(method,it))}),
            verticalAlignment=Alignment.CenterVertically) {
            EsvText(stringResource(label),modifier=Modifier.weight(1f))
            Switch(checked=settings.isMethodEnabled(method),onCheckedChange=null,enabled=editable)
        }
    }
    EsvText(stringResource(R.string.save_methods_new_tasks),style=v.captionTextStyle)
    EsvText(stringResource(R.string.save_methods_safety),style=v.captionTextStyle)
    if (!settings.directEnabled && !settings.streamEnabled && !settings.recordEnabled)
        EsvText(stringResource(R.string.save_methods_all_off),style=v.captionTextStyle)
}
