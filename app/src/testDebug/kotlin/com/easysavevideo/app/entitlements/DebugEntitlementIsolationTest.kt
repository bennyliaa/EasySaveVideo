package com.easysavevideo.app.entitlements

import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Debug-only test source; never requires or imports Fake into Release test/main compilation. */
class DebugEntitlementIsolationTest {
    private val platform=PlatformCapabilities(setOf(Feature.RECORD_SAVE,Feature.STREAM_SAVE,Feature.VIDEO_CROP))
    private fun decision(source:FakeEntitlementSource,feature:Feature=Feature.RECORD_SAVE,
        capabilities:PlatformCapabilities=platform,policy:BuildFeaturePolicy=BuildFeaturePolicy()) =
        FeatureGate().evaluate(FeatureRequest(feature),source.snapshot(),capabilities,policy,System.currentTimeMillis())

    @Test fun initialUnknownAndFreePresetsCannotOpenRecordingGate() {
        val source=FakeEntitlementSource()
        assertEquals(EntitlementSourceState.UNKNOWN,source.snapshot().sourceState)
        assertEquals(GateStatus.DENIED_ENTITLEMENT,decision(source).status)
        source.select("免费");assertEquals(GateStatus.DENIED_ENTITLEMENT,decision(source).status)
    }
    @Test fun deliberateInternalSavePresetCanAuthorizeEntitlementButNotPlatformOrPolicy() {
        val source=FakeEntitlementSource();source.select("部分权益")
        assertEquals(GateStatus.ALLOWED,decision(source).status)
        assertEquals(GateStatus.UNAVAILABLE_PLATFORM,decision(source,capabilities=PlatformCapabilities(emptySet())).status)
        assertEquals(GateStatus.BLOCKED_POLICY,decision(source,policy=BuildFeaturePolicy(blocked=setOf(Feature.RECORD_SAVE))).status)
        assertEquals(GateStatus.DENIED_ENTITLEMENT,decision(source,feature=Feature.VIDEO_CROP).status)
    }
    @Test fun revokedExpiredAndUnknownStillRefuseRealFeatureRequests() {
        val source=FakeEntitlementSource()
        for(preset in listOf("已撤销","已过期","来源未知")) {
            source.select(preset);assertEquals(GateStatus.DENIED_ENTITLEMENT,decision(source).status)
        }
    }
}
