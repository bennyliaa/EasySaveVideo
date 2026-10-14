package com.easysavevideo.app.entitlements

import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*

/** Planned product labels, not store SKUs and not a purchase or ownership record. */
internal enum class PlannedEntitlementModule(val features: List<Feature>) {
    PROVIDER_PLUS(listOf(Feature.PROVIDER_PLUS)),
    SAVE_PRO(listOf(Feature.STREAM_SAVE, Feature.RECORD_SAVE)),
    VIDEO_TOOLS_PRO(listOf(Feature.SEGMENT_RECORDING, Feature.REGION_RECORDING,
        Feature.VIDEO_CROP, Feature.VIDEO_MERGE, Feature.VIDEO_COMPRESS)),
}
internal enum class FeatureEntitlementExplanation { SATISFIED, NOT_SATISFIED, UNKNOWN }
internal data class ExplainedFeature(val feature: Feature, val entitlement: FeatureEntitlementExplanation)
internal data class EntitlementExplanation(
    val features: List<ExplainedFeature>,
    val simulated: Boolean,
    val sourceState: EntitlementSourceState,
    val nextTimeBoundaryMillis: Long?,
)

/** Projection only. Actual operations must still use their own full platform/policy FeatureGate. */
internal fun explainEntitlements(snapshot: EntitlementSnapshot, nowMillis: Long, simulated: Boolean): EntitlementExplanation {
    val gate = FeatureGate()
    val currentEvidence = nowMillis >= 0 &&
        (snapshot.sourceState == EntitlementSourceState.VALIDATED ||
            snapshot.sourceState == EntitlementSourceState.OFFLINE_CACHE && snapshot.validUntilEpochMillis != null) &&
        snapshot.validatedAtEpochMillis?.let { it <= nowMillis } == true &&
        (snapshot.validUntilEpochMillis?.let { nowMillis < it } ?: true)
    val rows = PlannedEntitlementModule.entries.flatMap { it.features }.map { feature ->
        val decision = gate.evaluate(FeatureRequest(feature), snapshot, PlatformCapabilities(setOf(feature)), BuildFeaturePolicy(), nowMillis)
        val status = when {
            !currentEvidence -> FeatureEntitlementExplanation.UNKNOWN
            decision.allowed -> FeatureEntitlementExplanation.SATISFIED
            snapshot.grants.any { it.entitlementId in decision.missingEntitlements && it.state == GrantState.UNKNOWN } -> FeatureEntitlementExplanation.UNKNOWN
            else -> FeatureEntitlementExplanation.NOT_SATISFIED
        }
        ExplainedFeature(feature, status)
    }
    val boundary = if (snapshot.sourceState == EntitlementSourceState.VALIDATED || snapshot.sourceState == EntitlementSourceState.OFFLINE_CACHE) {
        (listOfNotNull(snapshot.validatedAtEpochMillis, snapshot.validUntilEpochMillis) +
            snapshot.grants.mapNotNull { it.expiresAtEpochMillis }).filter { it > nowMillis }.minOrNull()
    } else null
    return EntitlementExplanation(rows, simulated, snapshot.sourceState, boundary)
}

/** Reads the existing local snapshot only; never calls Store.refresh/restore/purchase. */
internal fun readEntitlementExplanation(readSnapshot: () -> EntitlementSnapshot,
    nowMillis: Long, simulated: Boolean): EntitlementExplanation {
    val snapshot = try { readSnapshot() }
        catch (_: Exception) { EntitlementSnapshot(sourceState = EntitlementSourceState.UNAVAILABLE) }
        catch (_: LinkageError) { EntitlementSnapshot(sourceState = EntitlementSourceState.UNAVAILABLE) }
    return explainEntitlements(snapshot, nowMillis, simulated)
}

/** Recheck only a future evidence time boundary, bounded to notice wall-clock adjustments. */
internal fun entitlementExplanationDelay(boundary: Long?, nowMillis: Long): Long? = boundary?.let {
    if (it <= nowMillis) 1L else (it - nowMillis).let { remaining ->
        if (remaining <= 0) 60_000L else remaining.coerceAtMost(60_000L)
    }
}
