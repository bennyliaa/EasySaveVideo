package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EntitlementsTest {
    private val gate = FeatureGate()
    private val platform = PlatformCapabilities(Feature.entries.toSet())
    private fun evaluate(feature: Feature, snapshot: EntitlementSnapshot = EntitlementSnapshot(),
        caps: PlatformCapabilities = platform, policy: BuildFeaturePolicy = BuildFeaturePolicy(), now: Long = 100) =
        gate.evaluate(FeatureRequest(feature), snapshot, caps, policy, now)
    private fun granted(vararg ids: EntitlementId) = EntitlementSnapshot(1,
        ids.map { EntitlementGrant(it, GrantState.ACTIVE) }, 10, 200, EntitlementSourceState.VALIDATED)

    @Test fun freeFeaturesSurviveUnknownOfflineAndBrokenStore() {
        val broken = EntitlementManager(object : EntitlementSource {
            override fun snapshot(): EntitlementSnapshot = throw IllegalStateException("private raw store response")
        }).snapshot()
        assertEquals(EntitlementSourceState.UNAVAILABLE, broken.sourceState)
        listOf(Feature.BROWSER, Feature.DIRECT_SAVE, Feature.MEDIA_LIBRARY, Feature.VAULT).forEach { feature ->
            assertTrue(evaluate(feature).allowed)
            assertTrue(evaluate(feature, broken).allowed)
            assertTrue(evaluate(feature, EntitlementSnapshot(sourceState = EntitlementSourceState.OFFLINE_CACHE)).allowed)
        }
    }

    @Test fun partialAndAllGrantsAreFineGrained() {
        assertTrue(evaluate(Feature.STREAM_SAVE, granted(EntitlementId.STREAM_SAVE)).allowed)
        assertFalse(evaluate(Feature.RECORD_SAVE, granted(EntitlementId.STREAM_SAVE)).allowed)
        val all = granted(*EntitlementId.entries.toTypedArray())
        Feature.entries.forEach { assertTrue(it.name, evaluate(it, all).allowed) }
    }

    @Test fun toolsAloneCannotAuthorizeRecordingButCanAuthorizePureEditing() {
        val tools = granted(EntitlementId.SEGMENT_RECORDING, EntitlementId.REGION_RECORDING, EntitlementId.VIDEO_CROP)
        assertEquals(setOf(EntitlementId.RECORD_SAVE), evaluate(Feature.SEGMENT_RECORDING, tools).missingEntitlements)
        assertFalse(evaluate(Feature.REGION_RECORDING, tools).allowed)
        assertTrue(evaluate(Feature.VIDEO_CROP, tools).allowed)
    }

    @Test fun revokedExpiredAndUnknownStatesRejectPaidNewWork() {
        GrantState.entries.filterNot { it == GrantState.ACTIVE }.forEach { state ->
            val snapshot = granted(EntitlementId.STREAM_SAVE).copy(grants = listOf(EntitlementGrant(EntitlementId.STREAM_SAVE, state)))
            assertFalse(evaluate(Feature.STREAM_SAVE, snapshot).allowed)
        }
        val expiredGrant = granted(EntitlementId.STREAM_SAVE).copy(grants = listOf(EntitlementGrant(EntitlementId.STREAM_SAVE, GrantState.ACTIVE, 100)))
        assertFalse(evaluate(Feature.STREAM_SAVE, expiredGrant).allowed)
        assertFalse(evaluate(Feature.STREAM_SAVE, granted(EntitlementId.STREAM_SAVE), now = 200).allowed)
        assertFalse(evaluate(Feature.STREAM_SAVE, granted(EntitlementId.STREAM_SAVE).copy(sourceState = EntitlementSourceState.UNKNOWN)).allowed)
    }

    @Test fun offlineCacheRequiresExplicitUnexpiredValidityAndCannotTrustFutureValidation() {
        val offline = granted(EntitlementId.STREAM_SAVE).copy(sourceState = EntitlementSourceState.OFFLINE_CACHE)
        assertTrue(evaluate(Feature.STREAM_SAVE, offline).allowed)
        assertFalse(evaluate(Feature.STREAM_SAVE, offline.copy(validUntilEpochMillis = null)).allowed)
        assertFalse(evaluate(Feature.STREAM_SAVE, offline.copy(validatedAtEpochMillis = 150)).allowed)
    }

    @Test fun grantsCannotOverridePlatformRefusalOrBuildPolicy() {
        val all = granted(*EntitlementId.entries.toTypedArray())
        val unavailable = PlatformCapabilities(emptySet(), mapOf(Feature.BROWSER to CapabilityUnavailableReason.SYSTEM_UNAVAILABLE))
        assertEquals(GateStatus.UNAVAILABLE_PLATFORM, evaluate(Feature.BROWSER, all, unavailable).status)
        assertEquals(CapabilityUnavailableReason.SYSTEM_UNAVAILABLE, evaluate(Feature.BROWSER, all, unavailable).unavailableReason)
        assertEquals(GateStatus.BLOCKED_POLICY, evaluate(Feature.STREAM_SAVE, all, policy = BuildFeaturePolicy(setOf(Feature.STREAM_SAVE))).status)
    }


    @Test fun conflictingSameRevisionCannotRegrantRevokedRights() {
        var current = granted(EntitlementId.STREAM_SAVE).copy(grants = emptyList())
        val manager = EntitlementManager(object : EntitlementSource { override fun snapshot() = current })
        assertFalse(evaluate(Feature.STREAM_SAVE, manager.snapshot()).allowed)
        current = granted(EntitlementId.STREAM_SAVE)
        assertFalse(evaluate(Feature.STREAM_SAVE, manager.snapshot()).allowed)
        current = current.copy(revision = 2)
        assertTrue(evaluate(Feature.STREAM_SAVE, manager.snapshot()).allowed)
    }

    @Test fun staleRevisionCannotRegrantRevokedRights() {
        var current = granted(EntitlementId.STREAM_SAVE).copy(revision = 2, grants = emptyList())
        val manager = EntitlementManager(object : EntitlementSource { override fun snapshot() = current })
        assertFalse(evaluate(Feature.STREAM_SAVE, manager.snapshot()).allowed)
        current = granted(EntitlementId.STREAM_SAVE)
        assertFalse(evaluate(Feature.STREAM_SAVE, manager.snapshot()).allowed)
    }
}
