package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/** Store failure cannot propagate into the free Browser or mutate the user's existing media. */
class EntitlementManager(private val source: EntitlementSource) {
    private var last = EntitlementSnapshot()
    private var observed = false
    @Synchronized fun snapshot(): EntitlementSnapshot {
        val next = try { source.snapshot() } catch (_: Exception) {
            return last.copy(grants = emptyList(), sourceState = EntitlementSourceState.UNAVAILABLE)
        }
        if (!observed || next.revision > last.revision) {
            last = next
            observed = true
        }
        return last
    }
}

class FeatureGate {
    fun evaluate(
        request: FeatureRequest,
        snapshot: EntitlementSnapshot,
        platform: PlatformCapabilities,
        policy: BuildFeaturePolicy,
        nowMillis: Long,
    ): GateDecision {
        if (request.feature in policy.blocked) return GateDecision(GateStatus.BLOCKED_POLICY)
        platform.unavailableReason(request.feature)?.let {
            return GateDecision(GateStatus.UNAVAILABLE_PLATFORM, unavailableReason = it)
        }
        val required = requiredEntitlements(request.feature)
        if (required.isEmpty()) return GateDecision(GateStatus.ALLOWED)
        val sourceTrusted = snapshot.sourceState == EntitlementSourceState.VALIDATED ||
            snapshot.sourceState == EntitlementSourceState.OFFLINE_CACHE && snapshot.validUntilEpochMillis != null
        val cacheValid = snapshot.validatedAtEpochMillis?.let { it <= nowMillis } == true &&
            (snapshot.validUntilEpochMillis?.let { nowMillis < it } ?: true)
        val granted = if (sourceTrusted && cacheValid) snapshot.grants.filter {
            it.state == GrantState.ACTIVE && (it.expiresAtEpochMillis?.let { expiry -> nowMillis < expiry } ?: true)
        }.map { it.entitlementId }.toSet() else emptySet()
        val missing = required - granted
        return if (missing.isEmpty()) GateDecision(GateStatus.ALLOWED)
            else GateDecision(GateStatus.DENIED_ENTITLEMENT, missingEntitlements = missing)
    }

    fun requiredEntitlements(feature: Feature): Set<EntitlementId> = when (feature) {
        Feature.BROWSER, Feature.DIRECT_SAVE, Feature.MEDIA_LIBRARY, Feature.VAULT -> emptySet()
        Feature.PROVIDER_PLUS -> setOf(EntitlementId.PROVIDER_PLUS)
        Feature.STREAM_SAVE -> setOf(EntitlementId.STREAM_SAVE)
        Feature.RECORD_SAVE -> setOf(EntitlementId.RECORD_SAVE)
        Feature.SEGMENT_RECORDING -> setOf(EntitlementId.RECORD_SAVE, EntitlementId.SEGMENT_RECORDING)
        Feature.REGION_RECORDING -> setOf(EntitlementId.RECORD_SAVE, EntitlementId.REGION_RECORDING)
        Feature.VIDEO_CROP -> setOf(EntitlementId.VIDEO_CROP)
        Feature.VIDEO_MERGE -> setOf(EntitlementId.VIDEO_MERGE)
        Feature.VIDEO_COMPRESS -> setOf(EntitlementId.VIDEO_COMPRESS)
    }
}
