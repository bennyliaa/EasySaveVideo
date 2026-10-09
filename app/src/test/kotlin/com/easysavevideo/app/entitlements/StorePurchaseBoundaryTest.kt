package com.easysavevideo.app.entitlements

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.core.domain.EntitlementManager
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.BuildFeaturePolicy
import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.EntitlementSnapshot
import com.easysavevideo.core.model.EntitlementSourceState
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.Feature
import com.easysavevideo.core.model.FeatureRequest
import com.easysavevideo.core.model.GateStatus
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.PlatformCapabilities
import org.junit.Assert.*
import org.junit.Test

/** Ordinary deterministic host tests; no store, account, Android UI or purchase is exercised. */
class StorePurchaseBoundaryTest {
    private val now = 1_000L
    private val store = UnconfiguredStoreAdapter(EpochClock { now })

    private fun operation(deadline: Long = now + 1, cancelled: Boolean = false) = OperationContext(
        operationId = "store-operation",
        deadlineEpochMillis = deadline,
        cancelToken = CancellationSource().also { if (cancelled) it.cancel() },
    )

    private fun assertNoGrants(snapshot: EntitlementSnapshot) {
        assertEquals(0L, snapshot.revision)
        assertTrue(snapshot.grants.isEmpty())
        assertNull(snapshot.validatedAtEpochMillis)
        assertNull(snapshot.validUntilEpochMillis)
        assertEquals(EntitlementSourceState.UNAVAILABLE, snapshot.sourceState)
    }

    @Test fun defaultHasNoProductsAndCannotPurchase() {
        assertTrue(store.products.isEmpty())
        assertFalse(store.canPurchase)
        assertNoGrants(store.snapshot())
    }

    @Test fun purchaseAndRestoreReturnUnconfiguredForAnActiveOperation() {
        val operation = operation()
        assertEquals(StoreOperationStatus.UNCONFIGURED, store.purchase("unmapped-fixture", operation))
        assertEquals(StoreOperationStatus.UNCONFIGURED, store.restorePurchases(operation))
        assertNoGrants(store.snapshot())
    }

    @Test fun cancellationTakesPrecedenceOverUnconfiguredState() {
        val operation = operation(cancelled = true)
        assertEquals(StoreOperationStatus.CANCELLED, store.purchase("unmapped-fixture", operation))
        assertEquals(StoreOperationStatus.CANCELLED, store.restorePurchases(operation))
        assertNoGrants(store.snapshot())
    }

    @Test fun deadlineAtOrBeforeNowReturnsTimeout() {
        listOf(now, now - 1).forEach { deadline ->
            val operation = operation(deadline)
            assertEquals(StoreOperationStatus.TIMED_OUT, store.purchase("unmapped-fixture", operation))
            assertEquals(StoreOperationStatus.TIMED_OUT, store.restorePurchases(operation))
            assertNoGrants(store.snapshot())
        }
    }

    @Test fun cancellationHasTheSamePriorityAsOperationContextAtTheDeadline() {
        val operation = operation(deadline = now, cancelled = true)
        assertEquals(StoreOperationStatus.CANCELLED, store.purchase("unmapped-fixture", operation))
        assertEquals(StoreOperationStatus.CANCELLED, store.restorePurchases(operation))
    }

    @Test fun repeatedAppAndCoreOperationsPreserveUnavailableSnapshotAndRevision() {
        val initial = store.snapshot()
        val manager = EntitlementManager(store)
        listOf(operation(), operation(cancelled = true), operation(deadline = now)).forEach { operation ->
            repeat(3) {
                store.purchase("unmapped-fixture", operation)
                store.restorePurchases(operation)
                assertSame(initial, store.refresh(operation))
                assertSame(initial, store.restore(operation))
                assertSame(initial, store.snapshot())
                assertSame(initial, manager.snapshot())
                assertNoGrants(initial)
            }
        }
        assertTrue(store.products.isEmpty())
        assertFalse(store.canPurchase)
    }

    @Test fun unavailableStoreLeavesFreeGatesOpenAndPaidGatesDenied() {
        val gate = FeatureGate()
        // Contract-only platform capabilities; this does not enable unimplemented platform work.
        val platform = PlatformCapabilities(Feature.entries.toSet())
        val free = setOf(Feature.BROWSER, Feature.DIRECT_SAVE, Feature.MEDIA_LIBRARY, Feature.VAULT)
        Feature.entries.forEach { feature ->
            val decision = gate.evaluate(FeatureRequest(feature), store.snapshot(), platform, BuildFeaturePolicy(), now)
            assertEquals(feature.name, if (feature in free) GateStatus.ALLOWED else GateStatus.DENIED_ENTITLEMENT,
                decision.status)
        }
    }

    @Test fun buildAssemblyUsesTheUnconfiguredStoreAndReleaseUsesItAsSource() {
        assertTrue(BuildEntitlements.store is UnconfiguredStoreAdapter)
        assertTrue(BuildEntitlements.store.products.isEmpty())
        assertFalse(BuildEntitlements.store.canPurchase)
        assertNoGrants(BuildEntitlements.store.snapshot())
        if (!BuildConfig.DEBUG) {
            assertSame(BuildEntitlements.store, BuildEntitlements.source)
            assertNoGrants(BuildEntitlements.source.snapshot())
        }
    }
}
