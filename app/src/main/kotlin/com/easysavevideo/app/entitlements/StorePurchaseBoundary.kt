package com.easysavevideo.app.entitlements

import com.easysavevideo.core.model.EntitlementSnapshot
import com.easysavevideo.core.model.EntitlementSource
import com.easysavevideo.core.model.EntitlementSourceState
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import com.easysavevideo.core.model.StoreAdapter

/**
 * Operation-local app status only. No status is an entitlement or a proof of purchase.
 * VERIFICATION_REQUIRED means an unverified store completion; a future adapter must verify and
 * map it before publishing a separate revisioned Core snapshot. It cannot authorize work itself.
 * Fixed values intentionally cannot carry account data, receipts, tokens or raw store errors.
 */
enum class StoreOperationStatus {
    UNCONFIGURED,
    UNAVAILABLE,
    CANCELLED,
    PENDING,
    FAILED,
    TIMED_OUT,
    VERIFICATION_REQUIRED,
}

/**
 * App-side purchase and restore port. Product IDs and store-specific work stay on this side;
 * Core's existing StoreAdapter continues to expose entitlement snapshots only.
 * Implementations must expose an immutable product set and respect the supplied operation.
 * An operation status must never be treated as an entitlement grant.
 */
interface StorePurchaseBoundary : StoreAdapter, EntitlementSource {
    val products: Set<String>
    val canPurchase: Boolean
    fun purchase(productId: String, operation: OperationContext): StoreOperationStatus
    fun restorePurchases(operation: OperationContext): StoreOperationStatus
}

/**
 * Production default until a real, separately reviewed store integration is configured.
 * No SDK, account, persistence, network request, product mapping or grant mutation is present.
 */
class UnconfiguredStoreAdapter(
    private val clock: EpochClock = EpochClock { System.currentTimeMillis() },
) : StorePurchaseBoundary {
    override val products: Set<String> = emptySet()
    override val canPurchase: Boolean = false

    // A stable revision describes the same unchanged unavailable source across every operation.
    private val unavailable = EntitlementSnapshot(sourceState = EntitlementSourceState.UNAVAILABLE)

    override fun snapshot(): EntitlementSnapshot = unavailable
    override fun refresh(operation: OperationContext): EntitlementSnapshot = unavailable
    override fun restore(operation: OperationContext): EntitlementSnapshot = unavailable

    override fun purchase(productId: String, operation: OperationContext): StoreOperationStatus =
        unconfiguredOrStopped(operation)

    override fun restorePurchases(operation: OperationContext): StoreOperationStatus =
        unconfiguredOrStopped(operation)

    private fun unconfiguredOrStopped(operation: OperationContext): StoreOperationStatus =
        when (operation.stopReason(clock.nowMillis())) {
            OperationStopReason.CANCELLED -> StoreOperationStatus.CANCELLED
            OperationStopReason.DEADLINE_EXCEEDED -> StoreOperationStatus.TIMED_OUT
            OperationStopReason.RETRY_EXHAUSTED -> StoreOperationStatus.FAILED
            null -> StoreOperationStatus.UNCONFIGURED
        }
}
