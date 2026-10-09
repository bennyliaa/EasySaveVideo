package com.easysavevideo.app.distribution

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.entitlements.BuildEntitlements
import com.easysavevideo.app.entitlements.StoreOperationStatus
import com.easysavevideo.app.entitlements.UnconfiguredStoreAdapter
import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.EntitlementSourceState
import com.easysavevideo.core.model.OperationContext
import org.junit.Assert.*
import org.junit.Test

class DistributionConfigurationTest {
    @Test fun documentedBuildValuesAreExactAndUnique() {
        val expected = listOf("unconfigured", "google_play", "huawei", "xiaomi", "oppo", "vivo", "tencent_myapp")
        assertEquals(expected, DistributionChannel.entries.map { it.buildValue })
        DistributionChannel.entries.forEach { assertSame(it, DistributionChannel.fromBuildValue(it.buildValue)) }
    }

    @Test fun unfamiliarOrNormalizedValuesCannotSelectAChannel() {
        listOf("", "unknown", "GOOGLE_PLAY", "GooglePlay", " google_play", "google_play ", "google-play", "腾讯应用宝")
            .forEach { assertSame(DistributionChannel.UNCONFIGURED, DistributionChannel.fromBuildValue(it)) }
    }

    @Test fun everyIdentityKeepsPaymentUnconfiguredAndSnapshotUnavailable() {
        val operation = OperationContext("distribution-check", Long.MAX_VALUE, CancellationSource())
        DistributionChannel.entries.forEach { channel ->
            val configuration = DistributionConfiguration(channel)
            assertSame(channel, configuration.channel)
            val store = configuration.store
            assertTrue(store is UnconfiguredStoreAdapter)
            assertTrue(store.products.isEmpty())
            assertFalse(store.canPurchase)
            val snapshot = store.snapshot()
            assertTrue(snapshot.grants.isEmpty())
            assertEquals(0L, snapshot.revision)
            assertEquals(EntitlementSourceState.UNAVAILABLE, snapshot.sourceState)
            assertNull(snapshot.validatedAtEpochMillis)
            assertNull(snapshot.validUntilEpochMillis)
            assertEquals(StoreOperationStatus.UNCONFIGURED, store.purchase("unmapped-fixture", operation))
            assertEquals(StoreOperationStatus.UNCONFIGURED, store.restorePurchases(operation))
            assertSame(snapshot, store.refresh(operation))
            assertSame(snapshot, store.restore(operation))
        }
    }

    @Test fun actualBuildCompositionPreservesIdentityAndStoreAlias() {
        val configuration = BuildDistribution.configuration
        assertSame(DistributionChannel.fromBuildValue(BuildConfig.DISTRIBUTION_CHANNEL), configuration.channel)
        assertSame(configuration, BuildEntitlements.distribution)
        assertSame(configuration.store, BuildEntitlements.store)
        if (!BuildConfig.DEBUG) assertSame(BuildEntitlements.store, BuildEntitlements.source)
    }
}
