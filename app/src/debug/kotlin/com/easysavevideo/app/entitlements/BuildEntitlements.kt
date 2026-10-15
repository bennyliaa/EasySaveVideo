package com.easysavevideo.app.entitlements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import com.easysavevideo.app.theme.*
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import com.easysavevideo.app.distribution.BuildDistribution

/** Entire implementation and controls are compiled only into the debug source set. */
class FakeEntitlementSource : EntitlementSource, StoreAdapter {
    private val mapping = ProductMapping(mapOf(
        "development.save" to setOf(EntitlementId.STREAM_SAVE, EntitlementId.RECORD_SAVE),
        "development.all" to EntitlementId.entries.toSet(),
    ))
    @Volatile private var current = EntitlementSnapshot(sourceState = EntitlementSourceState.UNKNOWN)
    fun select(preset: String) {
        val now = System.currentTimeMillis()
        val grants = when (preset) {
            "部分权益" -> mapping.entitlementsFor("development.save")
            "全部权益", "已撤销", "已过期" -> mapping.entitlementsFor("development.all")
            else -> emptySet()
        }
        val state = when (preset) { "已撤销" -> GrantState.REVOKED; "已过期" -> GrantState.EXPIRED; else -> GrantState.ACTIVE }
        current = EntitlementSnapshot(current.revision + 1, grants.map { EntitlementGrant(it, state) }, now,
            sourceState = if (preset == "来源未知") EntitlementSourceState.UNKNOWN else EntitlementSourceState.VALIDATED)
    }
    override fun snapshot() = current
    override fun refresh(operation: OperationContext): EntitlementSnapshot = if (operation.stopReason(System.currentTimeMillis()) == null) current else EntitlementSnapshot(sourceState = EntitlementSourceState.UNAVAILABLE)
    override fun restore(operation: OperationContext) = refresh(operation)
}
object BuildEntitlements {
    val distribution = BuildDistribution.configuration
    val store: StorePurchaseBoundary = distribution.store
    val source = FakeEntitlementSource()
}
@Composable fun DevelopmentEntitlementPanel(canAct: () -> Boolean = { true }) {
    val visuals = LocalEsvVisuals.current
    var selected by remember { mutableStateOf("来源未知") }
    val snapshot = remember(selected) { BuildEntitlements.source.snapshot() }
    Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
        EsvText("开发权益测试", style = visuals.titleTextStyle)
        EsvText("仅 Debug 有此面板。模拟权益不启动保存、录制或支付。", style = visuals.captionTextStyle)
        listOf("免费", "部分权益", "全部权益", "已撤销", "已过期", "来源未知").forEach { preset ->
            EsvButton(if (selected == preset) "✓ $preset" else preset, onClick = { if (canAct()) { BuildEntitlements.source.select(preset); selected = preset } })
        }
        val gate = FeatureGate()
        val actual = PlatformCapabilities(setOf(Feature.BROWSER, Feature.DIRECT_SAVE, Feature.STREAM_SAVE, Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING))
        EsvText("本阶段实际能力：" + Feature.entries.joinToString("；") { feature ->
            "$feature=${gate.evaluate(FeatureRequest(feature), snapshot, actual, BuildFeaturePolicy(), System.currentTimeMillis()).status}"
        }, style = visuals.captionTextStyle)
        EsvText("契约测试平台（仅测试权益组合）：" + listOf(Feature.STREAM_SAVE, Feature.RECORD_SAVE, Feature.REGION_RECORDING, Feature.VIDEO_CROP).joinToString("；") { feature ->
            "$feature=${gate.evaluate(FeatureRequest(feature), snapshot, PlatformCapabilities(Feature.entries.toSet()), BuildFeaturePolicy(), System.currentTimeMillis()).status}"
        }, style = visuals.captionTextStyle)
    }
}
