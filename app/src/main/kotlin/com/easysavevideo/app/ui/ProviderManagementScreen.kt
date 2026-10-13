package com.easysavevideo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.easysavevideo.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.theme.*
import kotlinx.coroutines.delay

/** Real local repository state. All colors, type, controls, icons and spacing use SkinKit snapshots. */
@Composable
fun ProviderManagementScreen(
    state: ProviderManagementState,
    configuration: ProviderManagementConfiguration,
    commonsEnabled: Boolean,
    canToggleCommons: Boolean,
    onEnableCommons: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAutoRefresh: () -> Unit,
    onRejectCandidate: () -> Unit,
    onRollback: () -> Unit,
    onBack: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    val resources = LocalContext.current.resources
    val text = ProviderCopyText { key, args -> resources.getString(key.resource, *args) }
    val view = ProviderManagementPresentationPolicy.present(state, configuration, commonsEnabled, text)
    var confirmation by remember { mutableStateOf<ProviderManagementOperation?>(null) }
    val latestRefresh by rememberUpdatedState(onAutoRefresh)
    LaunchedEffect(state.active, confirmation) {
        if (!state.active || confirmation != null) return@LaunchedEffect
        while (true) { delay(5_000); latestRefresh() }
    }
    val snapshot = state.snapshot
    LaunchedEffect(state.active, snapshot?.generation, snapshot?.pendingVersion, snapshot?.canRollback, snapshot?.canRejectCandidate) {
        confirmation = null
    }
    BackHandler { if (confirmation != null) confirmation = null else onBack() }
    EsvBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxSize().padding(v.spacingMedium).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvThemeIcon(null, Modifier.size(v.iconSize))
                    EsvText(stringResource(R.string.provider_dialog_0), style = v.titleTextStyle, modifier = Modifier.weight(1f))
                    EsvButton(stringResource(R.string.provider_dialog_1), onClick = onBack)
                }
                EsvText(stringResource(R.string.provider_dialog_2), style = v.bodyTextStyle)
                EsvText(view.summary, style = v.titleTextStyle)
                EsvText(stringResource(R.string.provider_dialog_3), style = v.captionTextStyle, color = v.colors.muted)
                view.notice?.let { EsvText(it, color = v.colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                        ConfigurationSection(view, configuration, commonsEnabled, canToggleCommons && state.active,
                            onEnableCommons)
                        VersionSection(view, state, onRefresh, onConfirm = { confirmation = it })
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                        HealthSection(view)
                        QuarantineSection(view, state.snapshot != null)
                    }
                } else {
                    ConfigurationSection(view, configuration, commonsEnabled, canToggleCommons && state.active,
                        onEnableCommons)
                    VersionSection(view, state, onRefresh, onConfirm = { confirmation = it })
                    HealthSection(view)
                    QuarantineSection(view, state.snapshot != null)
                }
                EsvText(stringResource(R.string.provider_copy_4), style = v.captionTextStyle, color = v.colors.muted)
            }
        }
    }
    confirmation?.let { action ->
        val allowed = state.active && when (action) {
            ProviderManagementOperation.REJECT_CANDIDATE -> view.canRejectCandidate
            ProviderManagementOperation.ROLLBACK -> view.canRollback
            ProviderManagementOperation.INSPECT -> false
        }
        Dialog(onDismissRequest = { confirmation = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(v.colors.elevated, RoundedCornerShape(v.sheetCorner))
                .padding(v.spacingLarge).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText(if (action == ProviderManagementOperation.REJECT_CANDIDATE) stringResource(R.string.provider_dialog_4, snapshot?.pendingVersion?.toString() ?: stringResource(R.string.discovery_copy_94)) else stringResource(R.string.provider_dialog_5),
                    style = v.titleTextStyle)
                EsvText(if (action == ProviderManagementOperation.REJECT_CANDIDATE)
                    stringResource(R.string.provider_dialog_6)
                    else stringResource(R.string.provider_dialog_7))
                EsvText(stringResource(R.string.provider_copy_3), style = v.captionTextStyle)
                EsvButton(stringResource(R.string.provider_dialog_8), enabled = allowed, onClick = {
                    confirmation = null
                    if (allowed) {
                        if (action == ProviderManagementOperation.REJECT_CANDIDATE) onRejectCandidate() else onRollback()
                    }
                }, modifier = Modifier.fillMaxWidth())
                EsvButton(stringResource(R.string.provider_dialog_9), onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConfigurationSection(view: ProviderManagementPresentation, configuration: ProviderManagementConfiguration,
    enabled: Boolean, canToggle: Boolean, onEnable: (Boolean) -> Unit) {
    ProviderSection(stringResource(R.string.provider_dialog_10), view.configuration) {
        if (configuration.contactConfigured) EsvButton(stringResource(R.string.provider_dialog_13, if (enabled) stringResource(R.string.provider_dialog_11) else stringResource(R.string.provider_dialog_12)),
            onClick = { onEnable(!enabled) }, enabled = canToggle, modifier = Modifier.fillMaxWidth())
        EsvText(stringResource(R.string.provider_dialog_14), style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun VersionSection(view: ProviderManagementPresentation, state: ProviderManagementState,
    onRefresh: () -> Unit, onConfirm: (ProviderManagementOperation) -> Unit) {
    ProviderSection(stringResource(R.string.provider_dialog_15), view.versions) {
        EsvButton(stringResource(R.string.provider_dialog_16), onClick = onRefresh, enabled = state.active && !state.busy, modifier = Modifier.fillMaxWidth())
        if (view.canRejectCandidate) EsvButton(stringResource(R.string.provider_dialog_17),
            onClick = { onConfirm(ProviderManagementOperation.REJECT_CANDIDATE) }, modifier = Modifier.fillMaxWidth())
        if (view.canRollback) EsvButton(stringResource(R.string.provider_dialog_18),
            onClick = { onConfirm(ProviderManagementOperation.ROLLBACK) }, modifier = Modifier.fillMaxWidth())
        if (state.snapshot != null && !view.canRejectCandidate && !view.canRollback)
            EsvText(stringResource(R.string.provider_dialog_19), style = LocalEsvVisuals.current.captionTextStyle)
        EsvText(stringResource(R.string.provider_copy_3), style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun HealthSection(view: ProviderManagementPresentation) {
    ProviderSection(stringResource(R.string.provider_dialog_20), view.health) {
        EsvText(stringResource(R.string.provider_copy_0), style = LocalEsvVisuals.current.captionTextStyle)
        EsvText(stringResource(R.string.provider_copy_2), style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun QuarantineSection(view: ProviderManagementPresentation, loaded: Boolean) {
    ProviderSection(stringResource(R.string.provider_dialog_21), view.quarantine.ifEmpty {
        listOf(if (loaded) stringResource(R.string.provider_dialog_22) else stringResource(R.string.provider_dialog_23))
    }) {
        EsvText(stringResource(R.string.provider_copy_1), style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun ProviderSection(title: String, lines: List<String>, content: @Composable ColumnScope.() -> Unit = {}) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.cardCorner))
        .background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(title, style = v.sectionTextStyle)
        lines.forEach { EsvText(it, style = v.bodyTextStyle) }
        content()
    }
}
