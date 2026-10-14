package com.easysavevideo.app.ui.shell

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.entitlements.*
import com.easysavevideo.app.theme.*
import com.easysavevideo.core.model.*
import kotlinx.coroutines.delay

internal fun plannedModuleTitle(module: PlannedEntitlementModule): Int = when (module) {
    PlannedEntitlementModule.PROVIDER_PLUS -> R.string.entitlements_provider_plus
    PlannedEntitlementModule.SAVE_PRO -> R.string.entitlements_save_pro
    PlannedEntitlementModule.VIDEO_TOOLS_PRO -> R.string.entitlements_video_tools_pro
}
internal fun explainedFeatureTitle(feature: Feature): Int = when (feature) {
    Feature.PROVIDER_PLUS -> R.string.entitlements_feature_sources
    Feature.STREAM_SAVE -> R.string.entitlements_feature_stream
    Feature.RECORD_SAVE -> R.string.entitlements_feature_record
    Feature.SEGMENT_RECORDING -> R.string.entitlements_feature_segments
    Feature.REGION_RECORDING -> R.string.entitlements_feature_region
    Feature.VIDEO_CROP -> R.string.entitlements_feature_crop
    Feature.VIDEO_MERGE -> R.string.entitlements_feature_merge
    Feature.VIDEO_COMPRESS -> R.string.entitlements_feature_compress
    else -> error("Not a paid feature explanation")
}
internal fun explainedEntitlementStatus(status: FeatureEntitlementExplanation): Int = when (status) {
    FeatureEntitlementExplanation.SATISFIED -> R.string.entitlements_status_satisfied
    FeatureEntitlementExplanation.NOT_SATISFIED -> R.string.entitlements_status_not_satisfied
    FeatureEntitlementExplanation.UNKNOWN -> R.string.entitlements_status_unknown
}

/** No purchasing, restoration, grant changes, account details or externally fetched prices. */
@Composable internal fun EntitlementSettingsDetail(generation: Long, simulated: Boolean,
    canRead: () -> Boolean, readSnapshot: () -> EntitlementSnapshot) {
    val v = LocalEsvVisuals.current
    var refresh by remember(generation) { mutableIntStateOf(0) }
    var observed by remember(generation) { mutableStateOf<EntitlementExplanation?>(null) }
    val active = canRead()
    LaunchedEffect(generation, active, refresh) {
        while (canRead()) {
            val next = readEntitlementExplanation(readSnapshot, System.currentTimeMillis(), simulated)
            if (!canRead()) break
            observed = next
            val wait = entitlementExplanationDelay(next.nextTimeBoundaryMillis, System.currentTimeMillis()) ?: break
            delay(wait)
        }
    }
    EsvText(stringResource(R.string.entitlements_readonly_scope), style = v.captionTextStyle)
    if (simulated) EsvText(stringResource(R.string.entitlements_debug_notice), style = v.captionTextStyle, color = v.colors.error)
    EsvText(stringResource(R.string.entitlements_store_unconfigured), style = v.captionTextStyle)
    EsvText(stringResource(R.string.entitlements_free_title), style = v.sectionTextStyle)
    EsvText(stringResource(R.string.entitlements_free_available))
    EsvText(stringResource(R.string.entitlements_free_vault), style = v.captionTextStyle)
    EsvText(stringResource(R.string.entitlements_planned_scope), style = v.captionTextStyle)
    val observation = observed.takeIf { active }
    if (observation == null) EsvText(stringResource(R.string.entitlements_reading), style = v.captionTextStyle)
    PlannedEntitlementModule.entries.forEach { module ->
        EsvText(stringResource(plannedModuleTitle(module)), style = v.sectionTextStyle)
        module.features.forEach { feature ->
            val status = observation?.features?.firstOrNull { it.feature == feature }?.entitlement ?: FeatureEntitlementExplanation.UNKNOWN
            EsvText(stringResource(R.string.entitlements_feature_status,
                stringResource(explainedFeatureTitle(feature)), stringResource(explainedEntitlementStatus(status))))
        }
        if (module == PlannedEntitlementModule.VIDEO_TOOLS_PRO)
            EsvText(stringResource(R.string.entitlements_recording_dependency), style = v.captionTextStyle)
    }
    EsvText(stringResource(R.string.entitlements_operation_scope), style = v.captionTextStyle)
    EsvButton(stringResource(R.string.entitlements_read_again), onClick = {
        if (canRead()) refresh++
    }, enabled = active)
}
