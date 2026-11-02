package com.easysavevideo.core.model

enum class EntitlementId {
    DIRECT_SAVE, VAULT, PROVIDER_PLUS, STREAM_SAVE, RECORD_SAVE, SEGMENT_RECORDING,
    REGION_RECORDING, VIDEO_CROP, VIDEO_MERGE, VIDEO_COMPRESS,
}
enum class GrantState { ACTIVE, EXPIRED, REVOKED, UNKNOWN }
enum class EntitlementSourceState { VALIDATED, OFFLINE_CACHE, UNAVAILABLE, UNKNOWN }
data class EntitlementGrant(
    val entitlementId: EntitlementId,
    val state: GrantState,
    val expiresAtEpochMillis: Long? = null,
    val originRef: String? = null,
) { init { require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0) } }
/**
 * Immutable value snapshot. Copy caller-owned collections before validating or retaining them.
 * A read-only Kotlin List alone is insufficient: callers can otherwise mutate a revision in place.
 * The explicit value/copy/component API preserves the former data-class calling convention.
 */
class EntitlementSnapshot(
    val revision: Long = 0,
    grants: List<EntitlementGrant> = emptyList(),
    val validatedAtEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val sourceState: EntitlementSourceState = EntitlementSourceState.UNKNOWN,
) {
    val grants: List<EntitlementGrant> = java.util.Collections.unmodifiableList(ArrayList(grants))

    init {
        require(revision >= 0)
        require(this.grants.map { it.entitlementId }.distinct().size == this.grants.size)
        require(validatedAtEpochMillis == null || validatedAtEpochMillis >= 0)
        require(validUntilEpochMillis == null || validatedAtEpochMillis != null && validUntilEpochMillis >= validatedAtEpochMillis)
    }

    fun copy(
        revision: Long = this.revision,
        grants: List<EntitlementGrant> = this.grants,
        validatedAtEpochMillis: Long? = this.validatedAtEpochMillis,
        validUntilEpochMillis: Long? = this.validUntilEpochMillis,
        sourceState: EntitlementSourceState = this.sourceState,
    ) = EntitlementSnapshot(revision, grants, validatedAtEpochMillis, validUntilEpochMillis, sourceState)

    operator fun component1() = revision
    operator fun component2() = grants
    operator fun component3() = validatedAtEpochMillis
    operator fun component4() = validUntilEpochMillis
    operator fun component5() = sourceState

    override fun equals(other: Any?): Boolean = this === other || other is EntitlementSnapshot &&
        revision == other.revision && grants == other.grants &&
        validatedAtEpochMillis == other.validatedAtEpochMillis &&
        validUntilEpochMillis == other.validUntilEpochMillis && sourceState == other.sourceState

    override fun hashCode(): Int {
        var result = revision.hashCode()
        result = 31 * result + grants.hashCode()
        result = 31 * result + (validatedAtEpochMillis?.hashCode() ?: 0)
        result = 31 * result + (validUntilEpochMillis?.hashCode() ?: 0)
        return 31 * result + sourceState.hashCode()
    }

    override fun toString(): String = "EntitlementSnapshot(revision=$revision, grants=$grants, " +
        "validatedAtEpochMillis=$validatedAtEpochMillis, validUntilEpochMillis=$validUntilEpochMillis, " +
        "sourceState=$sourceState)"
}
enum class Feature {
    BROWSER, DIRECT_SAVE, MEDIA_LIBRARY, VAULT, PROVIDER_PLUS, STREAM_SAVE, RECORD_SAVE,
    SEGMENT_RECORDING, REGION_RECORDING, VIDEO_CROP, VIDEO_MERGE, VIDEO_COMPRESS,
}
data class FeatureRequest(val feature: Feature)
enum class CapabilityUnavailableReason { NOT_IMPLEMENTED, SYSTEM_UNAVAILABLE, PERMISSION_REQUIRED, PROTECTED_CONTENT, UNSUPPORTED }
data class PlatformCapabilities(
    val available: Set<Feature>,
    val unavailable: Map<Feature, CapabilityUnavailableReason> = emptyMap(),
) {
    init { require(available.intersect(unavailable.keys).isEmpty()) }
    fun unavailableReason(feature: Feature): CapabilityUnavailableReason? =
        if (feature in available) null else unavailable[feature] ?: CapabilityUnavailableReason.NOT_IMPLEMENTED
}
data class BuildFeaturePolicy(val blocked: Set<Feature> = emptySet())
enum class GateStatus { ALLOWED, DENIED_ENTITLEMENT, UNAVAILABLE_PLATFORM, BLOCKED_POLICY }
data class GateDecision(
    val status: GateStatus,
    val missingEntitlements: Set<EntitlementId> = emptySet(),
    val unavailableReason: CapabilityUnavailableReason? = null,
) { val allowed: Boolean get() = status == GateStatus.ALLOWED }

/** The adapter converts verified store results and SKU mappings before crossing this boundary. */
interface StoreAdapter {
    fun refresh(operation: OperationContext): EntitlementSnapshot
    fun restore(operation: OperationContext): EntitlementSnapshot
}
interface EntitlementSource { fun snapshot(): EntitlementSnapshot }
