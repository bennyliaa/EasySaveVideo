package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
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
    EsvText("补充预算与系统信号", style = v.captionTextStyle)
    DiscoveryPowerPresentationPolicy.lines(state.status?.powerPolicy, state.status?.powerPolicyUpdatePending == true,
        state.powerScenarioId != null, state.osPower).forEach { EsvText(it, style = v.captionTextStyle) }
    EsvButton("刷新系统信号", onClick = controller::refreshSystemSignals,
        enabled = state.ready && !state.storageUnavailable && state.osPower.state == com.easysavevideo.app.discovery.OsObservationState.OBSERVING)
    if (BuildDiscoveryPower.scenarios.isNotEmpty()) {
        EsvText("Debug 注入测试：以下选择不是设备读数，不会保存到下次启动。改变后需主动查找、翻页或补充。", style = v.captionTextStyle, color = v.colors.error)
        BuildDiscoveryPower.scenarios.forEach { scenario ->
            EsvButton((if ((state.powerScenarioId ?: "disconnected") == scenario.id) "✓ " else "") + scenario.label,
                onClick = { controller.selectPowerScenario(scenario.id) }, enabled = state.ready && !state.storageUnavailable)
        }
    }
}
