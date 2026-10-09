package com.easysavevideo.m10b.review

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.distribution.BuildDistribution
import com.easysavevideo.app.distribution.DistributionChannel
import com.easysavevideo.app.distribution.DistributionConfiguration
import com.easysavevideo.app.entitlements.BuildEntitlements
import com.easysavevideo.app.entitlements.UnconfiguredStoreAdapter
import com.easysavevideo.core.model.EntitlementSourceState
import org.junit.Assert.*
import org.junit.Test

/** Independent host checks against actual compiled app variants; no Android or store execution. */
class IndependentDistributionReviewTest {
    // The external matrix input is the oracle, not the implementation's parser or generated value.
    private fun expectedChannel(): DistributionChannel = when (System.getProperty("esv.expected.channel")) {
        "unconfigured" -> DistributionChannel.UNCONFIGURED
        "google_play" -> DistributionChannel.GOOGLE_PLAY
        "huawei" -> DistributionChannel.HUAWEI
        "xiaomi" -> DistributionChannel.XIAOMI
        "oppo" -> DistributionChannel.OPPO
        "vivo" -> DistributionChannel.VIVO
        "tencent_myapp" -> DistributionChannel.TENCENT_MYAPP
        else -> error("Missing or unsupported external channel expectation")
    }

    private fun expectedDebug(): Boolean = when (System.getProperty("esv.expected.variant")) {
        "debug" -> true
        "release" -> false
        else -> error("Missing or unsupported external variant expectation")
    }

    @Test fun externalMatrixInputMatchesGeneratedIdentityAndActualAssembly() {
        val expected = expectedChannel()
        assertEquals(System.getProperty("esv.expected.channel"), BuildConfig.DISTRIBUTION_CHANNEL)
        assertEquals(expectedDebug(), BuildConfig.DEBUG)
        val selected = BuildDistribution.configuration
        assertSame(expected, selected.channel)
        assertSame(selected, BuildEntitlements.distribution)
        assertSame(selected.store, BuildEntitlements.store)
        assertEquals(UnconfiguredStoreAdapter::class.java, BuildEntitlements.store.javaClass)
        assertFalse(BuildEntitlements.store.canPurchase)
        assertTrue(BuildEntitlements.store.products.isEmpty())
        if (!expectedDebug()) assertSame(BuildEntitlements.store, BuildEntitlements.source)
    }

    @Test fun constructingOtherProfilesCannotRebindTheProcessSelection() {
        val selected = BuildDistribution.configuration
        val source = BuildEntitlements.source
        val snapshot = selected.store.snapshot()
        val adapters = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )
        DistributionChannel.entries.forEach { channel ->
            val other = DistributionConfiguration(channel)
            assertSame(channel, other.channel)
            assertNotSame(selected.store, other.store)
            assertTrue(adapters.add(other.store))
            assertFalse(other.store.canPurchase)
            assertTrue(other.store.products.isEmpty())
            assertTrue(other.store.snapshot().grants.isEmpty())
            assertEquals(EntitlementSourceState.UNAVAILABLE, other.store.snapshot().sourceState)
            assertSame(selected, BuildDistribution.configuration)
            assertSame(selected.store, BuildEntitlements.store)
            assertSame(source, BuildEntitlements.source)
            assertSame(snapshot, selected.store.snapshot())
        }
    }

    @Test fun developmentSimulationExistsOnlyInTheExternallyExpectedDebugVariant() {
        val fakeClass = try {
            Class.forName(
                "com.easysavevideo.app.entitlements.FakeEntitlementSource",
                false,
                BuildEntitlements::class.java.classLoader,
            )
        } catch (_: ClassNotFoundException) {
            null
        }
        if (expectedDebug()) {
            assertNotNull(fakeClass)
            assertTrue(fakeClass!!.isInstance(BuildEntitlements.source))
            assertFalse(fakeClass.isInstance(BuildEntitlements.store))
            assertNotSame(BuildEntitlements.source, BuildEntitlements.store)
        } else {
            assertNull(fakeClass)
            assertSame(BuildEntitlements.source, BuildEntitlements.store)
            assertTrue(BuildEntitlements.source.snapshot().grants.isEmpty())
        }
    }
}
