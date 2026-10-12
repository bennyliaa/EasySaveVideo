package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.AndroidDiscoveryController
import com.easysavevideo.app.discovery.BuildDiscoveryPower
import com.easysavevideo.app.discovery.DiscoveryPowerPresentationPolicy
import com.easysavevideo.app.discovery.DiscoveryUiState
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

/** Shares SkinKit visuals. A Release build has no simulated inputs or controls. */
@Composable
fun DiscoveryPowerControls(state: DiscoveryUiState, controller: AndroidDiscoveryController) {
    val v = LocalEsvVisuals.current
    EsvText(stringResource(R.string.finite_power_heading), style = v.captionTextStyle)
    DiscoveryPowerPresentationPolicy.lines(state.status?.powerPolicy, state.status?.powerPolicyUpdatePending == true,
        state.powerScenarioId != null, state.osPower).forEach { EsvText(it, style = v.captionTextStyle) }
    EsvButton(stringResource(R.string.finite_power_refresh), onClick = controller::refreshSystemSignals,
        enabled = state.ready && !state.storageUnavailable && state.osPower.state == com.easysavevideo.app.discovery.OsObservationState.OBSERVING)
    if (BuildDiscoveryPower.scenarios.isNotEmpty()) {
        EsvText(stringResource(R.string.finite_power_debug), style = v.captionTextStyle, color = v.colors.error)
        BuildDiscoveryPower.scenarios.forEach { scenario ->
            EsvButton((if ((state.powerScenarioId ?: "disconnected") == scenario.id) "✓ " else "") + stringResource(powerScenarioLabelResource(scenario.id)),
                onClick = { controller.selectPowerScenario(scenario.id) }, enabled = state.ready && !state.storageUnavailable)
        }
    }
}
internal fun powerScenarioLabelResource(id:String):Int=when(id) {
    "disconnected" -> R.string.finite_power_disconnected
    "normal" -> R.string.finite_power_normal
    "conserving" -> R.string.finite_power_conserving
    "unknown" -> R.string.finite_power_unknown
    "paused" -> R.string.finite_power_paused
    else -> R.string.finite_power_unavailable
}
