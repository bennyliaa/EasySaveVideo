package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.theme.*

/** Local counts and scoped controls only. Never displays a query, URL, cursor or profile identifier. */
@Composable internal fun DiscoveryPrivacySettingsDetail(controller: AndroidDiscoveryController,
    generation: Long, canAct: () -> Boolean) {
    val v = LocalEsvVisuals.current
    var controls by remember(controller, generation) { mutableStateOf(DiscoveryPrivacyState()) }
    val actions = remember(controller, generation) {
        DiscoveryPrivacyActions(canAct, {
            val state = controller.state
            DiscoveryPrivacyAvailability(state.ready, state.storageUnavailable, state.controlTarget,
                state.status?.providers?.map { it.providerId }?.toSet().orEmpty(), controller.localControlState.busy)
        }, controller::confirmLocalControl, controller::refreshHistoryStatistics) { controls = it }
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    val state = controller.state
    val operationState = controller.localControlState
    val active = canAct()
    LaunchedEffect(actions, state.controlTarget, state.storageUnavailable, operationState.busy, active) { actions.reconcile() }
    val enabled = active && state.ready && !state.storageUnavailable && state.controlTarget != null && !operationState.busy && controls.prompt == null
    EsvText(stringResource(R.string.discovery_privacy_title), style = v.sectionTextStyle)
    EsvText(stringResource(R.string.discovery_privacy_scope), style = v.captionTextStyle)
    if (state.demo) EsvText(stringResource(R.string.discovery_privacy_demo), style = v.captionTextStyle, color = v.colors.error)
    if (!state.ready || state.controlTarget == null) EsvText(stringResource(R.string.discovery_privacy_unavailable), style = v.captionTextStyle)
    if (state.storageUnavailable || state.historyStatisticsUnavailable) EsvText(stringResource(R.string.discovery_privacy_read_failed), style = v.captionTextStyle, color = v.colors.error)
    state.historyStatistics?.takeIf { it.target == state.controlTarget }?.let { statistics ->
        for ((label, counts) in listOf(R.string.discovery_privacy_current to statistics.currentProfile,
            R.string.discovery_privacy_all to statistics.allProfiles)) {
            EsvText(stringResource(R.string.discovery_privacy_counts, stringResource(label), counts.profileCount,
                counts.seenIdentityCount, counts.seenAliasCount, counts.progressCount), style = v.captionTextStyle)
        }
    }
    EsvButton(stringResource(R.string.discovery_privacy_refresh), onClick = actions::refresh, enabled = enabled)
    DiscoveryPrivacyOperationStatus(controller, canAct)
    if (controls.stale) {
        EsvText(stringResource(R.string.discovery_privacy_stale), style = v.captionTextStyle, color = v.colors.error)
        EsvButton(stringResource(R.string.discovery_privacy_dismiss), onClick = actions::dismissStale, enabled = active)
    }
    EsvButton(stringResource(R.string.discovery_privacy_clear_current),
        onClick = { actions.begin(DiscoveryLocalAction.CLEAR_CURRENT_HISTORY) }, enabled = enabled)
    EsvButton(stringResource(R.string.discovery_privacy_clear_all),
        onClick = { actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY) }, enabled = enabled)
    state.status?.providers?.forEach { provider ->
        EsvText(stringResource(R.string.discovery_privacy_provider, provider.providerId,
            stringResource(if (provider.hasContinuation) R.string.discovery_privacy_yes else R.string.discovery_privacy_no)), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.discovery_privacy_reset_provider, provider.providerId),
            onClick = { actions.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, provider.providerId) }, enabled = enabled)
    }
    controls.prompt?.let { prompt ->
        Dialog(onDismissRequest = { actions.cancel(prompt) }) {
            BoxWithConstraints {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(600.dp))
                    .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(discoveryPrivacyControlTitle(prompt.request), style = v.titleTextStyle)
                    EsvText(discoveryPrivacyControlConsequence(prompt.request), style = v.captionTextStyle)
                    if (state.demo) EsvText(stringResource(R.string.discovery_privacy_demo), color = v.colors.error)
                    if (!actions.canConfirm(prompt)) EsvText(stringResource(R.string.discovery_privacy_stale), color = v.colors.error)
                    EsvButton(stringResource(R.string.discovery_privacy_cancel), onClick = { actions.cancel(prompt) }, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(R.string.discovery_privacy_confirm), onClick = { actions.confirm(prompt) },
                        enabled = actions.canConfirm(prompt), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable internal fun DiscoveryPrivacyOperationStatus(controller: AndroidDiscoveryController, canAct: () -> Boolean) {
    val v = LocalEsvVisuals.current
    if (controller.localControlState.busy) EsvText(stringResource(R.string.discovery_privacy_working), style = v.captionTextStyle)
    controller.localControlState.lastOutcome?.let { receipt ->
        val resultText = when (receipt.outcome) {
            DiscoveryControlOutcome.COMMITTED -> when (receipt.operation.request.action) {
                DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> R.string.discovery_privacy_current_done
                DiscoveryLocalAction.CLEAR_ALL_HISTORY -> R.string.discovery_privacy_all_done
                DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> R.string.discovery_privacy_progress_done
            }
            DiscoveryControlOutcome.FAILED -> R.string.discovery_privacy_failed
            DiscoveryControlOutcome.STALE -> R.string.discovery_privacy_stale
        }
        EsvText(stringResource(resultText), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.discovery_privacy_dismiss), onClick = {
            if (canAct()) controller.dismissLocalControlResult(receipt)
        }, enabled = canAct())
    }
}

@Composable internal fun discoveryPrivacyControlTitle(request: DiscoveryControlRequest): String = when (request.action) {
    DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> stringResource(R.string.discovery_privacy_clear_current)
    DiscoveryLocalAction.CLEAR_ALL_HISTORY -> stringResource(R.string.discovery_privacy_clear_all)
    DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> stringResource(R.string.discovery_privacy_reset_provider, requireNotNull(request.providerId))
}
@Composable internal fun discoveryPrivacyControlConsequence(request: DiscoveryControlRequest): String = when (request.action) {
    DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> stringResource(R.string.discovery_privacy_current_confirm)
    DiscoveryLocalAction.CLEAR_ALL_HISTORY -> stringResource(R.string.discovery_privacy_all_confirm)
    DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> stringResource(R.string.discovery_privacy_progress_confirm, requireNotNull(request.providerId))
}
