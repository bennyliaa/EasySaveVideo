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
import com.easysavevideo.core.model.VaultAutoLockPolicy

/** Configuration only. Main owns exact callback admission and the parent owns scrolling. */
@Composable internal fun VaultAutoLockSettingsDetail(policy: VaultAutoLockPolicy, enabled: Boolean,
    available: Boolean, loadFailed: Boolean, onSelect: (VaultAutoLockPolicy) -> Unit) {
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.vault_auto_lock_title), style = v.sectionTextStyle)
    if (!available) EsvText(stringResource(R.string.ui_vault_disabled), style = v.captionTextStyle)
    if (loadFailed) EsvText(stringResource(R.string.vault_auto_lock_load_failed), style = v.captionTextStyle)
    else if (available && !enabled) EsvText(stringResource(R.string.vault_auto_lock_wait), style = v.captionTextStyle)
    Column(Modifier.selectableGroup()) {
        VaultAutoLockPolicy.entries.forEach { option ->
            val label = when (option) {
                VaultAutoLockPolicy.IMMEDIATE -> R.string.vault_auto_lock_immediate
                VaultAutoLockPolicy.AFTER_1_MINUTE -> R.string.vault_auto_lock_one_minute
                VaultAutoLockPolicy.AFTER_5_MINUTES -> R.string.vault_auto_lock_five_minutes
                VaultAutoLockPolicy.AFTER_15_MINUTES -> R.string.vault_auto_lock_fifteen_minutes
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = policy == option,
                enabled = enabled && available, role = Role.RadioButton, onClick = { onSelect(option) }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = policy == option, onClick = null, enabled = enabled && available)
                EsvText(stringResource(label), modifier = Modifier.weight(1f))
            }
        }
    }
    EsvText(stringResource(R.string.vault_auto_lock_scope), style = v.captionTextStyle)
}
