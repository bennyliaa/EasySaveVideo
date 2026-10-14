package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.core.model.*

internal fun discoveryRepeatLabel(policy: RepeatPolicy): Int = when (policy) {
    RepeatPolicy.PREFER_NEW -> R.string.discovery_copy_74
    RepeatPolicy.HIDE_WATCHED -> R.string.discovery_copy_75
    RepeatPolicy.HIDE_SHOWN -> R.string.discovery_copy_76
    RepeatPolicy.SHOW_ALL -> R.string.discovery_copy_77
}
internal fun discoveryIntensityLabel(mode: DiscoveryMode): Int = when (mode) {
    DiscoveryMode.POWER_SAVING -> R.string.discovery_copy_19
    DiscoveryMode.BALANCED -> R.string.discovery_copy_20
    DiscoveryMode.AGGRESSIVE -> R.string.discovery_copy_21
}

/** The existing AppSettings owner remains authoritative; this page stores no preference draft. */
@Composable internal fun DiscoverySettingsDetail(settings: DiscoverySettings, enabled: Boolean, onEdit: (SettingsEdit) -> Unit) {
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.settings_discovery_scope), style = v.captionTextStyle)
    if (!enabled) EsvText(stringResource(R.string.settings_discovery_wait), style = v.captionTextStyle)
    EsvText(stringResource(R.string.settings_discovery_columns, settings.columns))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvButton(stringResource(R.string.settings_discovery_columns_less), { onEdit(SettingsEdit.DiscoveryColumns(-1)) }, enabled = enabled && settings.columns > 1)
        EsvButton(stringResource(R.string.settings_discovery_columns_more), { onEdit(SettingsEdit.DiscoveryColumns(1)) }, enabled = enabled && settings.columns < 6)
    }
    EsvText(stringResource(R.string.settings_discovery_pagesize, settings.pageSize))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvButton(stringResource(R.string.settings_discovery_pagesize_less), { onEdit(SettingsEdit.DiscoveryPageSize(-1)) }, enabled = enabled && settings.pageSize > 1)
        EsvButton(stringResource(R.string.settings_discovery_pagesize_more), { onEdit(SettingsEdit.DiscoveryPageSize(1)) }, enabled = enabled && settings.pageSize < 100)
    }
    EsvText(stringResource(R.string.settings_discovery_repeat), style = v.sectionTextStyle)
    Column(Modifier.selectableGroup()) {
        RepeatPolicy.entries.forEach { policy ->
            DiscoveryPreferenceOption(stringResource(discoveryRepeatLabel(policy)), settings.repeatPolicy == policy, enabled) {
                onEdit(SettingsEdit.DiscoveryRepeat(policy))
            }
        }
    }
    EsvText(stringResource(R.string.settings_discovery_intensity), style = v.sectionTextStyle)
    Column(Modifier.selectableGroup()) {
        DiscoveryMode.entries.forEach { mode ->
            DiscoveryPreferenceOption(stringResource(discoveryIntensityLabel(mode)), settings.mode == mode, enabled) {
                onEdit(SettingsEdit.DiscoveryIntensity(mode))
            }
        }
    }
    EsvText(stringResource(R.string.settings_discovery_intensity_scope), style = v.captionTextStyle)
    EsvButton(stringResource(R.string.settings_discovery_defaults), { onEdit(SettingsEdit.DiscoveryDefaults) },
        enabled = enabled && settings != DiscoverySettings())
    EsvText(stringResource(R.string.settings_discovery_defaults_scope), style = v.captionTextStyle)
}

@Composable private fun DiscoveryPreferenceOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        EsvText(label, modifier = Modifier.weight(1f))
    }
}
