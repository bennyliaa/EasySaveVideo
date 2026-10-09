package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPowerPresentationTest {
    private fun policy(signals: DiscoveryPowerSignals = DiscoveryPowerSignals()) = DiscoveryPowerPolicy.evaluate(signals, DiscoveryMode.AGGRESSIVE)
    @Test fun disconnectedIsNotRepresentedAsHealthyMonitoring() {
        val text = DiscoveryPowerPresentationPolicy.lines(policy(), false, false).joinToString()
        assertTrue(text.contains("未接入 Android")); assertTrue(text.contains("未测量实际耗电"))
        assertTrue(text.contains("系统信号未接入")); assertTrue(text.contains("3"))
    }
    @Test fun unknownEnabledIsPausedAndNeverReportedAsZeroBattery() {
        val text = DiscoveryPowerPresentationPolicy.lines(policy(DiscoveryPowerSignals(enabled = true)), false, true).joinToString()
        assertTrue(text.contains("模拟信号未知")); assertTrue(text.contains("并发最多 0")); assertFalse(text.contains("电量 0"))
    }
    @Test fun pendingStateExplainsImmediateFence() {
        assertTrue(DiscoveryPowerPresentationPolicy.lines(policy(), true, false).any { it.contains("尚未完成") })
    }
    @Test fun absentSnapshotStillHasHonestUnconnectedExplanation() {
        assertEquals(1, DiscoveryPowerPresentationPolicy.lines(null, false, false).size)
    }
    @Test fun debugScenarioValuesAreExplicitAndUnknownIdsRefused() {
        assertEquals(5, BuildDiscoveryPower.scenarios.size)
        assertNull(BuildDiscoveryPower.selection("unadmitted"))
        assertFalse(BuildDiscoveryPower.selection("disconnected")!!.signals.enabled)
        assertEquals(DiscoveryPowerPolicyState.PAUSED, policy(BuildDiscoveryPower.selection("unknown")!!.signals).state)
        assertEquals(DiscoveryPowerPolicyState.PAUSED, policy(BuildDiscoveryPower.selection("paused")!!.signals).state)
        assertEquals(DiscoveryPowerPolicyState.CONSERVING, policy(BuildDiscoveryPower.selection("conserving")!!.signals).state)
        assertEquals(DiscoveryPowerPolicyState.UNRESTRICTED, policy(BuildDiscoveryPower.selection("normal")!!.signals).state)
    }
    @Test fun injectedSelectionDiagnosticIsRedacted() {
        assertEquals("DiscoveryPowerSelection([redacted])", BuildDiscoveryPower.selection("paused").toString())
    }
}
