package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.app.vault.VaultRuntimeAvailability
import com.easysavevideo.core.model.*
import com.easysavevideo.app.theme.*
import com.easysavevideo.app.licenses.ThirdPartyNotices
import com.easysavevideo.app.ui.responsivePaneLayout

@Composable
fun EsvSettingsScreen(settings:AppSettings,onChange:(SettingsEdit)->Unit,onSources:()->Unit,onBack:()->Unit, diagnostics: @Composable () -> Unit = {}, discoveryEditable: Boolean = true, persistenceWarning: Boolean = false, loadWarning: Boolean = false, privacy: @Composable () -> Unit = {}, entitlements: @Composable () -> Unit = {}, saveDestination: @Composable () -> Unit = {}, browserEditable: Boolean = false, saveConcurrencyEditable: Boolean = false, onVault: (() -> Unit)? = null) {
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var notices by rememberSaveable { mutableStateOf(false) }
    val noticesListState = rememberLazyListState()
    val v=LocalEsvVisuals.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Reuse the existing remaining-content geometry policy; no settings-specific thresholds.
        val pane=responsivePaneLayout(maxWidth.value,maxHeight.value,LocalDensity.current.fontScale,v.spacingMedium.value)
        val presentation=settingsPresentation(categoryKey,pane.split)
        val back=settingsBackAction(notices,presentation)
        fun goBack() { when(back) {
            SettingsBackAction.CLOSE_NOTICES -> notices=false
            SettingsBackAction.CATEGORIES -> categoryKey=null
            SettingsBackAction.EXIT -> onBack()
        } }
        BackHandler(enabled=back!=SettingsBackAction.EXIT){goBack()}
        if(notices) ThirdPartyNotices(onClose={notices=false}, listState=noticesListState)
        else if(pane.split) {
            Column(Modifier.fillMaxSize().padding(v.spacingMedium),verticalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
                SettingsHeading(onBack={goBack()}, persistenceWarning=persistenceWarning,loadWarning=loadWarning)
                Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(v.spacingMedium)) {
                    Column(Modifier.width(pane.sideWidthDp.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        SettingsCategories(presentation.active){categoryKey=it.key}
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
                        SettingsDetail(requireNotNull(presentation.active),settings,onChange,onSources,diagnostics,discoveryEditable,browserEditable,saveConcurrencyEditable,privacy,entitlements,saveDestination,onVault){notices=true}
                    }
                }
            }
        } else {
            // One bounded scroll owns the whole compact page, including its heading at low height.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(v.spacingMedium),
                verticalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
                SettingsHeading(onBack={goBack()}, persistenceWarning=persistenceWarning,loadWarning=loadWarning)
                if(presentation.categories)SettingsCategories(null){categoryKey=it.key}
                if(presentation.detail)SettingsDetail(requireNotNull(presentation.active),settings,onChange,onSources,diagnostics,discoveryEditable,browserEditable,saveConcurrencyEditable,privacy,entitlements,saveDestination,onVault){notices=true}
            }
        }
    }
}
@Composable private fun SettingsHeading(onBack:()->Unit,persistenceWarning:Boolean,loadWarning:Boolean) {
    val v=LocalEsvVisuals.current
    EsvText(stringResource(R.string.ui_settings),style=v.titleTextStyle)
    TextButton(onClick=onBack){EsvText(stringResource(R.string.ui_back))}
    if(loadWarning) EsvText(stringResource(R.string.settings_load_warning),style=v.captionTextStyle,color=v.colors.error)
    if(persistenceWarning) EsvText(stringResource(R.string.settings_persistence_warning),style=v.captionTextStyle,color=v.colors.error)
}
@Composable private fun categoryLabel(category:SettingsCategory):String=stringResource(when(category) {
    SettingsCategory.APPEARANCE -> R.string.ui_appearance
    SettingsCategory.LANGUAGE -> R.string.ui_language
    SettingsCategory.SOURCES -> R.string.ui_sources
    SettingsCategory.BROWSER -> R.string.settings_browser_title
    SettingsCategory.DISCOVERY -> R.string.ui_settings_discovery
    SettingsCategory.SAVE -> R.string.ui_settings_save
    SettingsCategory.EDITOR -> R.string.ui_settings_editor
    SettingsCategory.PRIVACY -> R.string.settings_privacy_title
    SettingsCategory.ENTITLEMENTS -> R.string.entitlements_title
    SettingsCategory.DIAGNOSTICS -> R.string.diagnostics_title
    SettingsCategory.ABOUT -> R.string.ui_settings_about
})
@Composable private fun SettingsCategories(active:SettingsCategory?,onSelect:(SettingsCategory)->Unit) {
    val v=LocalEsvVisuals.current
    Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
        SettingsCategory.entries.forEach { category ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=active==category,role=Role.Tab,
                onClick={onSelect(category)}).padding(v.spacingSmall)) {
                EsvText(categoryLabel(category),color=if(active==category)v.colors.accent else v.colors.text)
            }
        }
    }
}
@Composable private fun SettingsDetail(category:SettingsCategory,settings:AppSettings,onChange:(SettingsEdit)->Unit,
    onSources:()->Unit,diagnostics: @Composable () -> Unit,discoveryEditable:Boolean,browserEditable:Boolean,saveConcurrencyEditable:Boolean,privacy: @Composable () -> Unit,entitlements: @Composable () -> Unit,saveDestination: @Composable () -> Unit,onVault:(()->Unit)?,onNotices:()->Unit) {
    val v=LocalEsvVisuals.current
    EsvText(categoryLabel(category),style=v.sectionTextStyle)
    when(category) {
        SettingsCategory.APPEARANCE -> {
            EsvText(stringResource(R.string.ui_theme))
            Column(Modifier.selectableGroup()) { ThemeMode.entries.forEach { mode ->
                val label=when(mode){ThemeMode.SYSTEM->R.string.ui_system;ThemeMode.LIGHT->R.string.ui_light;ThemeMode.DARK->R.string.ui_dark}
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=settings.appearance.themeMode==mode,
                    role=Role.RadioButton,onClick={onChange(SettingsEdit.Theme(mode))}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected=settings.appearance.themeMode==mode,onClick=null);EsvText(stringResource(label))
                }
            } }
        }
        SettingsCategory.LANGUAGE -> Column(Modifier.selectableGroup()) { AppLanguage.entries.forEach { language ->
            val label=when(language){AppLanguage.SYSTEM->R.string.ui_system;AppLanguage.CHINESE->R.string.ui_chinese;AppLanguage.ENGLISH->R.string.ui_english}
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=settings.appearance.language==language,
                role=Role.RadioButton,onClick={onChange(SettingsEdit.Language(language))}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected=settings.appearance.language==language,onClick=null);EsvText(stringResource(label))
            }
        } }
        SettingsCategory.SOURCES -> {
            EsvText(stringResource(R.string.ui_settings_sources_description),style=v.captionTextStyle)
            EsvButton(stringResource(R.string.ui_sources),onSources)
        }
        SettingsCategory.BROWSER -> BrowserSettingsDetail(settings.browser,browserEditable,onChange)
        SettingsCategory.DISCOVERY -> DiscoverySettingsDetail(settings.discovery,discoveryEditable,onChange)
        SettingsCategory.SAVE -> {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=settings.save.wifiOnly,role=Role.Switch,
                onValueChange={onChange(SettingsEdit.WifiOnly(it))}), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                EsvText(stringResource(R.string.ui_save_wifi_only),modifier=Modifier.weight(1f))
                Switch(checked=settings.save.wifiOnly,onCheckedChange=null)
            }
            EsvText(stringResource(R.string.ui_save_wifi_description),style=v.captionTextStyle)
            SaveConcurrencySettingsControl(settings.save.maxConcurrentDirectTasks,saveConcurrencyEditable,onChange)
            saveDestination()
        }
        SettingsCategory.EDITOR -> {
            EsvText(stringResource(R.string.ui_editor_default_compression))
            EsvText(stringResource(R.string.ui_editor_default_compression_description),style=v.captionTextStyle)
            Column(Modifier.selectableGroup()) { EditorCompressionMode.entries.forEach { mode ->
                val label=when(mode) {
                    EditorCompressionMode.ORIGINAL_QUALITY -> R.string.ui_editor_original_quality
                    EditorCompressionMode.SAVE_SPACE -> R.string.ui_editor_save_space
                }
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=settings.editor.defaultCompression==mode,
                    role=Role.RadioButton,onClick={onChange(SettingsEdit.EditorCompression(mode))}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected=settings.editor.defaultCompression==mode,onClick=null);EsvText(stringResource(label))
                }
            } }
        }
        SettingsCategory.PRIVACY -> privacy()
        SettingsCategory.ENTITLEMENTS -> entitlements()
        SettingsCategory.DIAGNOSTICS -> diagnostics()
        SettingsCategory.ABOUT -> {
            EsvButton(stringResource(R.string.ui_licenses),onNotices)
            VaultRuntimeAvailability(onVault)
            EsvText(stringResource(R.string.ui_store_unavailable),style=v.captionTextStyle)
        }
    }
}

@Composable private fun SaveConcurrencySettingsControl(count: Int, editable: Boolean,
    onEdit: (SettingsEdit.DirectConcurrency) -> Unit) {
    val v=LocalEsvVisuals.current
    val enabledNow by rememberUpdatedState(editable)
    val editNow by rememberUpdatedState(onEdit)
    val actions=remember { SaveConcurrencySettingsActions({enabledNow}) { editNow(it) } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    EsvText(stringResource(R.string.save_concurrency_title),style=v.sectionTextStyle)
    if (!editable) EsvText(stringResource(R.string.save_concurrency_waiting),style=v.captionTextStyle)
    Column(Modifier.selectableGroup()) {
        for (option in 1..2) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=count==option,
                enabled=editable,role=Role.RadioButton,onClick={actions.edit(SettingsEdit.DirectConcurrency(option))}),
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected=count==option,onClick=null,enabled=editable)
                EsvText(stringResource(if(option==1)R.string.save_concurrency_one else R.string.save_concurrency_two))
            }
        }
    }
    EsvText(stringResource(R.string.save_concurrency_scope),style=v.captionTextStyle)
    EsvText(stringResource(R.string.save_concurrency_lowering),style=v.captionTextStyle)
}
