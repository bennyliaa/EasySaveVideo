package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPowerPolicyTest {
    private val healthy = DiscoveryPowerSignals(true, BatterySignal.NORMAL, ThermalSignal.NORMAL, NetworkSignal.UNMETERED)

    @Test fun defaultDisconnectedIsNotEnabledUnknownAndDoesNotFabricateHealthyInputs() {
        val disconnected = DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(), DiscoveryMode.AGGRESSIVE)
        assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, disconnected.state)
        assertEquals(BatterySignal.UNKNOWN, disconnected.signals.battery)
        assertEquals(ThermalSignal.UNKNOWN, disconnected.signals.thermal)
        assertEquals(NetworkSignal.UNKNOWN, disconnected.signals.network)
        assertEquals(3, disconnected.concurrency)
        assertEquals(6, disconnected.maxRequestsPerRound)
        assertEquals(40, disconnected.lowWatermark)
        assertEquals(100, disconnected.highWatermark)
        assertTrue(disconnected.allowsRequests)
        val unknown = DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(enabled = true), DiscoveryMode.AGGRESSIVE)
        assertEquals(DiscoveryPowerPolicyState.PAUSED, unknown.state)
        assertFalse(unknown.allowsRequests)
        assertEquals(0, unknown.concurrency)
        assertEquals(0, unknown.maxRequestsPerRound)
    }

    @Test fun enabledUnknownAndHardLimitsEachIndependentlyPause() {
        val cases = listOf(
            healthy.copy(battery = BatterySignal.UNKNOWN), healthy.copy(battery = BatterySignal.CRITICAL),
            healthy.copy(thermal = ThermalSignal.UNKNOWN), healthy.copy(thermal = ThermalSignal.SEVERE),
            healthy.copy(network = NetworkSignal.UNKNOWN), healthy.copy(network = NetworkSignal.OFFLINE))
        cases.forEach { signals ->
            val policy = DiscoveryPowerPolicy.evaluate(signals, DiscoveryMode.BALANCED)
            assertEquals(DiscoveryPowerPolicyState.PAUSED, policy.state)
            assertFalse(policy.allowsRequests)
            assertEquals(0, policy.concurrency)
            assertEquals(0, policy.maxRequestsPerRound)
            assertEquals(0, policy.lowWatermark)
            assertEquals(0, policy.highWatermark)
        }
    }

    @Test fun softLimitsIndependentlyConserveWithoutEscalatingUserMode() {
        val cases = listOf(healthy.copy(battery = BatterySignal.LOW), healthy.copy(thermal = ThermalSignal.WARM),
            healthy.copy(network = NetworkSignal.METERED))
        cases.forEach { signals -> DiscoveryMode.entries.forEach { mode ->
            val policy = DiscoveryPowerPolicy.evaluate(signals, mode)
            assertEquals(DiscoveryPowerPolicyState.CONSERVING, policy.state)
            assertEquals(mode, policy.requestedMode)
            assertEquals(DiscoveryMode.POWER_SAVING, policy.effectiveMode)
            assertTrue(policy.allowsRequests)
            assertEquals(1, policy.concurrency)
            assertEquals(3, policy.maxRequestsPerRound)
            assertEquals(20, policy.lowWatermark)
            assertEquals(40, policy.highWatermark)
        } }
    }

    @Test fun everyCombinationOnlyTightensAllAdmittedBounds() {
        for (enabled in listOf(false, true)) for (battery in BatterySignal.entries)
            for (thermal in ThermalSignal.entries) for (network in NetworkSignal.entries)
                for (mode in DiscoveryMode.entries) for (requests in 1..6) {
                    val limits = DiscoveryLimits(pageSize = 2, poolCapacity = 9, maxRequestsPerRound = requests)
                    val p = DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(enabled, battery, thermal, network), mode, limits)
                    assertTrue(p.concurrency in 0..limits.concurrency(mode))
                    assertTrue(p.maxRequestsPerRound in 0..limits.maxRequestsPerRound)
                    assertTrue(p.lowWatermark in 0..limits.lowWatermark)
                    assertTrue(p.highWatermark in p.lowWatermark..limits.highWatermark)
                    assertTrue(limits.concurrency(p.effectiveMode) <= limits.concurrency(mode))
                    if (!enabled) assertEquals(DiscoveryPowerPolicyState.NOT_CONNECTED, p.state)
                }
    }

    @Test fun healthySnapshotRetainsSelectedModeAndStricterCallerLimits() {
        for (mode in DiscoveryMode.entries) {
            val limits = DiscoveryLimits(pageSize = 1, poolCapacity = 5, maxRequestsPerRound = 1)
            val normal = DiscoveryPowerPolicy.evaluate(healthy, mode, limits)
            assertEquals(DiscoveryPowerPolicyState.UNRESTRICTED, normal.state)
            assertEquals(mode, normal.effectiveMode)
            assertEquals(limits.concurrency(mode), normal.concurrency)
            assertEquals(1, normal.maxRequestsPerRound)
            val low = DiscoveryPowerPolicy.evaluate(healthy.copy(battery = BatterySignal.LOW), mode, limits)
            assertEquals(1, low.maxRequestsPerRound)
            assertEquals(1, low.lowWatermark)
            assertEquals(2, low.highWatermark)
        }
    }

    @Test fun schedulerIntersectsExistingForegroundVisibilityAndTaskPriorityGates() {
        for (foreground in listOf(false, true)) for (visible in listOf(false, true))
            for (save in listOf(false, true)) for (record in listOf(false, true)) {
                val s = DiscoveryScheduler()
                s.mode(DiscoveryMode.AGGRESSIVE)
                s.powerSignals(healthy.copy(network = NetworkSignal.METERED))
                s.visibility(foreground, visible); s.priority(save, record)
                assertTrue(s.beginRound(0))
                assertEquals(foreground && visible && !save && !record, s.canLaunch("p", 0))
                s.powerSignals(healthy.copy(network = NetworkSignal.UNKNOWN))
                assertFalse(s.canLaunch("p", 0))
            }
    }

    @Test fun schedulerConservingWatermarksRoundAndConcurrencyHaveExactBoundaries() {
        val s = DiscoveryScheduler(); s.visibility(true, true); s.mode(DiscoveryMode.AGGRESSIVE)
        s.powerSignals(healthy.copy(battery = BatterySignal.LOW))
        assertFalse(s.beginRound(20)); assertTrue(s.beginRound(19))
        assertTrue(s.reserve("a", 19)); assertFalse(s.reserve("b", 19))
        s.release("a"); assertFalse(s.reserve("a", 40)); assertTrue(s.reserve("a", 39))
        s.release("a"); assertTrue(s.reserve("a", 39)); s.release("a")
        assertFalse(s.reserve("a", 0)); assertEquals(3, s.requestsThisRound)
        s.suspend(); assertFalse(s.roundActive); assertEquals(3, s.requestsThisRound)
    }
}
