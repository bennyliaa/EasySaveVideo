package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library lifecycle work is local and never depends on paid or legacy Vault grants. */
class FreeLibraryVaultGateTest {
    private val gate = FeatureGate()
    private val free = listOf(Feature.MEDIA_LIBRARY, Feature.VAULT)
    private val platform = PlatformCapabilities(free.toSet())

    @Test fun everyStoreAndGrantStateLeavesLibraryAndVaultFree() {
        EntitlementSourceState.entries.forEach { source ->
            GrantState.entries.forEach { state ->
                val snapshot = EntitlementSnapshot(5, EntitlementId.entries.map { EntitlementGrant(it, state, 1) },
                    1, 2, source)
                free.forEach { feature ->
                    val decision = gate.evaluate(FeatureRequest(feature), snapshot, platform, BuildFeaturePolicy(), 10_000)
                    assertTrue("$feature $source $state", decision.allowed)
                    assertEquals(emptySet<EntitlementId>(), decision.missingEntitlements)
                }
            }
        }
    }

    @Test fun emptyOfflineAndBrokenStoreDoNotNeedAccountOrPayment() {
        val failed = EntitlementManager(object : EntitlementSource {
            override fun snapshot(): EntitlementSnapshot = throw IllegalStateException("synthetic store failure")
        }).snapshot()
        listOf(EntitlementSnapshot(), failed, EntitlementSnapshot(sourceState = EntitlementSourceState.OFFLINE_CACHE))
            .forEach { snapshot -> free.forEach { feature ->
                assertTrue(gate.evaluate(FeatureRequest(feature), snapshot, platform, BuildFeaturePolicy(), Long.MAX_VALUE).allowed)
            } }
    }
}
