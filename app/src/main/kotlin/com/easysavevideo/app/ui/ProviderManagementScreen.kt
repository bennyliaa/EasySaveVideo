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
    val view = ProviderManagementPresentationPolicy.present(state, configuration, commonsEnabled)
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
                    EsvText("Provider 管理", style = v.titleTextStyle, modifier = Modifier.weight(1f))
                    EsvButton("返回", onClick = onBack)
                }
                EsvText("Wikimedia Commons · 唯一内置来源", style = v.bodyTextStyle)
                EsvText(view.summary, style = v.titleTextStyle)
                EsvText("前台每 5 秒只刷新本地快照；无远程检查。显示值是上次读取状态，执行恢复时会重新校验。", style = v.captionTextStyle, color = v.colors.muted)
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
                EsvText(ProviderManagementPresentationPolicy.LIFECYCLE_NOTICE, style = v.captionTextStyle, color = v.colors.muted)
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
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
                .padding(v.spacingLarge).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText(if (action == ProviderManagementOperation.REJECT_CANDIDATE) "拒绝候选 v${snapshot?.pendingVersion}？" else "执行当前获准的本地回滚？",
                    style = v.titleTextStyle)
                EsvText(if (action == ProviderManagementOperation.REJECT_CANDIDATE)
                    "将结束此候选试用并记录本地显式拒绝。只尝试仓库允许的上一状态，仍可能保持停用；不会重新安装已隔离版本。"
                    else "只回到仓库当前获准的历史目标，并消耗这个回滚位置。有效授权、过期和隔离检查仍然生效，结果仍可能停用。")
                EsvText(ProviderManagementPresentationPolicy.RECOVERY_NOTICE, style = v.captionTextStyle)
                EsvButton("确认本地操作", enabled = allowed, onClick = {
                    confirmation = null
                    if (allowed) {
                        if (action == ProviderManagementOperation.REJECT_CANDIDATE) onRejectCandidate() else onRollback()
                    }
                }, modifier = Modifier.fillMaxWidth())
                EsvButton("取消", onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConfigurationSection(view: ProviderManagementPresentation, configuration: ProviderManagementConfiguration,
    enabled: Boolean, canToggle: Boolean, onEnable: (Boolean) -> Unit) {
    ProviderSection("构建与本地配置", view.configuration) {
        if (configuration.contactConfigured) EsvButton("${if (enabled) "关闭" else "开启"}本地 Commons 来源",
            onClick = { onEnable(!enabled) }, enabled = canToggle, modifier = Modifier.fillMaxWidth())
        EsvText("本地开关沿用发现页设置，不能覆盖签名安全策略、隔离或缺失的构建配置。", style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun VersionSection(view: ProviderManagementPresentation, state: ProviderManagementState,
    onRefresh: () -> Unit, onConfirm: (ProviderManagementOperation) -> Unit) {
    ProviderSection("版本与恢复", view.versions) {
        EsvButton("刷新本地状态", onClick = onRefresh, enabled = state.active && !state.busy, modifier = Modifier.fillMaxWidth())
        if (view.canRejectCandidate) EsvButton("拒绝候选 / 尝试安全恢复",
            onClick = { onConfirm(ProviderManagementOperation.REJECT_CANDIDATE) }, modifier = Modifier.fillMaxWidth())
        if (view.canRollback) EsvButton("回滚到当前获准的历史目标",
            onClick = { onConfirm(ProviderManagementOperation.ROLLBACK) }, modifier = Modifier.fillMaxWidth())
        if (state.snapshot != null && !view.canRejectCandidate && !view.canRollback)
            EsvText("当前没有可执行的候选恢复或回滚操作。", style = LocalEsvVisuals.current.captionTextStyle)
        EsvText(ProviderManagementPresentationPolicy.RECOVERY_NOTICE, style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun HealthSection(view: ProviderManagementPresentation) {
    ProviderSection("本地试用观察", view.health) {
        EsvText(ProviderManagementPresentationPolicy.SELF_TEST_NOTICE, style = LocalEsvVisuals.current.captionTextStyle)
        EsvText(ProviderManagementPresentationPolicy.LIMIT_NOTICE, style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun QuarantineSection(view: ProviderManagementPresentation, loaded: Boolean) {
    ProviderSection("本地隔离记录", view.quarantine.ifEmpty {
        listOf(if (loaded) "没有本地隔离记录；不代表任何网站已通过兼容验证。" else "尚未读取本地隔离记录。")
    }) {
        EsvText(ProviderManagementPresentationPolicy.QUARANTINE_NOTICE, style = LocalEsvVisuals.current.captionTextStyle)
    }
}

@Composable
private fun ProviderSection(title: String, lines: List<String>, content: @Composable ColumnScope.() -> Unit = {}) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
        .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(title, style = v.titleTextStyle)
        lines.forEach { EsvText(it, style = v.bodyTextStyle) }
        content()
    }
}
