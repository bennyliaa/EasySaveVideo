package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.core.model.*
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.app.licenses.ThirdPartyNotices

@Composable
fun EsvSettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit,
    onSources: () -> Unit, onBack: () -> Unit) {
    var notices by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = notices) { notices = false }
    if (notices) { ThirdPartyNotices(onClose = { notices = false }); return }
    val visuals = LocalEsvVisuals.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(visuals.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
        Text(stringResource(R.string.ui_settings), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onBack) { Text(stringResource(R.string.ui_back)) }
        Text(stringResource(R.string.ui_appearance), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_theme))
        Column(Modifier.selectableGroup()) { ThemeMode.entries.forEach { mode ->
            val label = when(mode) { ThemeMode.SYSTEM -> R.string.ui_system
                ThemeMode.LIGHT -> R.string.ui_light; ThemeMode.DARK -> R.string.ui_dark }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = settings.appearance.themeMode == mode, role = Role.RadioButton,
                onClick = { onChange(settings.copy(appearance = settings.appearance.copy(themeMode = mode))) }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = settings.appearance.themeMode == mode, onClick = null)
                Text(stringResource(label))
            }
        } }
        Text(stringResource(R.string.ui_language))
        Column(Modifier.selectableGroup()) { AppLanguage.entries.forEach { language ->
            val label = when(language) { AppLanguage.SYSTEM -> R.string.ui_system
                AppLanguage.CHINESE -> R.string.ui_chinese; AppLanguage.ENGLISH -> R.string.ui_english }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = settings.appearance.language == language, role = Role.RadioButton,
                onClick = { onChange(settings.copy(appearance = settings.appearance.copy(language = language))) }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = settings.appearance.language == language, onClick = null)
                Text(stringResource(label))
            }
        } }
        TextButton(onClick = onSources) { Text(stringResource(R.string.ui_sources)) }
        TextButton(onClick = { notices = true }) { Text(stringResource(R.string.ui_licenses)) }
        Text(stringResource(R.string.ui_vault_disabled))
        Text(stringResource(R.string.ui_store_unavailable))
    }
}
