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
import com.easysavevideo.core.model.BrowserSettings

/** Shared by the old browser panel and central settings; parent owns scrolling and persistence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserSettingsDetail(settings: BrowserSettings, editable: Boolean,
    onEdit: (SettingsEdit.BrowserEdit) -> Unit) {
    val v=LocalEsvVisuals.current
    val enabledNow by rememberUpdatedState(editable)
    val editNow by rememberUpdatedState(onEdit)
    val actions=remember { BrowserSettingsActions({enabledNow}) { editNow(it) } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    if (!editable) EsvText(stringResource(R.string.settings_browser_waiting), style=v.captionTextStyle)
    BrowserPreferenceSwitch(stringResource(R.string.settings_browser_javascript),settings.javaScriptEnabled,editable) {
        actions.edit(SettingsEdit.BrowserJavaScript(it))
    }
    EsvText(stringResource(R.string.flow_browserscreen_34),style=v.captionTextStyle)
    BrowserPreferenceSwitch(stringResource(R.string.browser_copy_third_party_cookies),settings.acceptThirdPartyCookies,editable) {
        actions.edit(SettingsEdit.BrowserThirdPartyCookies(it))
    }
    EsvText(stringResource(R.string.flow_browserscreen_35),style=v.captionTextStyle)
    BrowserPreferenceSwitch(stringResource(R.string.browser_copy_restore_tabs),settings.restoreTabs,editable) {
        actions.edit(SettingsEdit.BrowserRestoreTabs(it))
    }
    EsvText(stringResource(R.string.flow_browserscreen_36),style=v.captionTextStyle)
    EsvText(stringResource(R.string.browser_copy_active_limit,settings.maxActiveWebViews))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
        EsvButton(stringResource(R.string.flow_browserscreen_38),onClick={actions.edit(SettingsEdit.BrowserActiveViews(-1))},
            enabled=editable && settings.maxActiveWebViews>1)
        EsvButton(stringResource(R.string.flow_browserscreen_39),onClick={actions.edit(SettingsEdit.BrowserActiveViews(1))},
            enabled=editable && settings.maxActiveWebViews<4)
    }
    EsvText(stringResource(R.string.flow_browserscreen_40),style=v.captionTextStyle)
    EsvText(stringResource(R.string.flow_browserscreen_43),style=v.captionTextStyle)
}
@Composable private fun BrowserPreferenceSwitch(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=value,enabled=enabled,role=Role.Switch,
        onValueChange=onChange),verticalAlignment=Alignment.CenterVertically) {
        EsvText(label,modifier=Modifier.weight(1f))
        Switch(checked=value,onCheckedChange=null,enabled=enabled)
    }
}
