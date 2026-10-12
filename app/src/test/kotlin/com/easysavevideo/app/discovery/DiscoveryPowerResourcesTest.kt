package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.discovery.*
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

internal fun powerResources(folder: String): Map<String, String> {
    val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val nodes = factory.newDocumentBuilder().parse(File(root, "$folder/discovery_power.xml")).getElementsByTagName("string")
    return (0 until nodes.length).associate { i ->
        nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent
    }
}
internal fun powerResourceText(folder: String): DiscoveryPowerText {
    val strings = powerResources(folder)
    return DiscoveryPowerText { key, args ->
        String.format(if (folder == "values") Locale.US else Locale.SIMPLIFIED_CHINESE,
            strings.getValue("discovery_power_${key.name.lowercase(Locale.ROOT)}"), *args)
    }
}

class DiscoveryPowerResourcesTest {
    private val fresh = DiscoveryOsPowerSnapshot(state = OsObservationState.OBSERVING,
        battery = OsSignalAge.FRESH, saver = OsSignalAge.FRESH, thermal = OsSignalAge.FRESH, network = OsSignalAge.FRESH)
    private val healthy = DiscoveryPowerSignals(true, BatterySignal.NORMAL, ThermalSignal.NORMAL, NetworkSignal.UNMETERED)
    private fun policy(signals: DiscoveryPowerSignals) = DiscoveryPowerPolicy.evaluate(signals, DiscoveryMode.AGGRESSIVE)

    @Test fun pairedKeysAndInterpolationContractsCoverEveryPresentationKey() {
        val en = powerResources("values"); val zh = powerResources("values-zh-rCN")
        assertEquals(DiscoveryPowerCopy.entries.map { "discovery_power_${it.name.lowercase(Locale.ROOT)}" }.toSet(), en.keys)
        assertEquals(en.keys, zh.keys)
        val placeholders = Regex("%[0-9]+\\$[sd]")
        en.keys.forEach { key ->
            assertEquals(placeholders.findAll(en.getValue(key)).map { it.value }.toList(), placeholders.findAll(zh.getValue(key)).map { it.value }.toList())
            assertFalse(en.getValue(key).any { it in '\u4e00'..'\u9fff' })
            assertFalse(en.getValue(key).contains('\uFFFD')); assertFalse(zh.getValue(key).contains('\uFFFD'))
        }
    }

    @Test fun languageSwitchReformatsSameSnapshotWithoutChangingBudgetOrSignalOrigin() {
        val status = policy(healthy)
        val before = status.copy()
        val en = DiscoveryPowerPresentationPolicy.lines(status, false, false, fresh, powerResourceText("values"))
        val zh = DiscoveryPowerPresentationPolicy.lines(status, false, false, fresh, powerResourceText("values-zh-rCN"))
        assertTrue(en.any { it.startsWith("System observations allow") })
        assertTrue(zh.any { it.startsWith("系统观察允许") })
        assertEquals(en, DiscoveryPowerPresentationPolicy.lines(status, false, false, fresh, powerResourceText("values")))
        assertEquals(before, status)
        for (folder in listOf("values", "values-zh-rCN")) {
            val budget = powerResourceText(folder).get(DiscoveryPowerCopy.BUDGET, 7, 19, 31, 67)
            listOf("7", "19", "31", "67").forEach { assertTrue(budget.contains(it)) }
            assertFalse(budget.contains('%'))
        }
    }

    @Test fun everyObservationAndPolicyReasonHasALocalizedBranch() {
        val seen = mutableSetOf<DiscoveryPowerCopy>()
        val text = DiscoveryPowerText { key, _ -> seen += key; key.name }
        val policies = listOf(policy(DiscoveryPowerSignals()), policy(healthy), policy(healthy.copy(battery = BatterySignal.LOW)), policy(healthy.copy(network = NetworkSignal.UNKNOWN)))
        val snapshots = listOf(null, fresh) + OsObservationState.entries.map { DiscoveryOsPowerSnapshot(state = it) }
        for (status in policies) for (os in snapshots) for (simulated in listOf(false, true)) {
            DiscoveryPowerPresentationPolicy.lines(status, true, simulated, os, text)
        }
        assertEquals(DiscoveryPowerCopy.entries.toSet(), seen)
    }

    @Test fun eachUnknownOrExpiredObservationUsesConservativeFallbackInBothLanguages() {
        for (age in listOf(OsSignalAge.UNKNOWN, OsSignalAge.EXPIRED)) {
            for (snapshot in listOf(fresh.copy(battery = age), fresh.copy(saver = age), fresh.copy(thermal = age), fresh.copy(network = age))) {
                for (folder in listOf("values", "values-zh-rCN")) {
                    val text = powerResourceText(folder)
                    val lines = DiscoveryPowerPresentationPolicy.lines(null, false, false, snapshot, text)
                    assertTrue(lines.contains(text.get(DiscoveryPowerCopy.UNKNOWN)))
                    assertFalse(lines.contains(text.get(DiscoveryPowerCopy.FRESH)))
                }
            }
        }
    }

    @Test fun allSignalReasonsRetainPolicyResultsAndZeroBudgetForPaused() {
        for (battery in BatterySignal.entries) for (thermal in ThermalSignal.entries) for (network in NetworkSignal.entries) {
            val status = policy(DiscoveryPowerSignals(true, battery, thermal, network))
            for (folder in listOf("values", "values-zh-rCN")) {
                val text = powerResourceText(folder)
                val lines = DiscoveryPowerPresentationPolicy.lines(status, false, true, fresh, text)
                assertTrue(lines.contains(text.get(DiscoveryPowerCopy.BUDGET, status.concurrency, status.maxRequestsPerRound, status.lowWatermark, status.highWatermark)))
                val key = when (status.state) {
                    DiscoveryPowerPolicyState.PAUSED -> DiscoveryPowerCopy.PAUSED
                    DiscoveryPowerPolicyState.CONSERVING -> DiscoveryPowerCopy.CONSERVING
                    DiscoveryPowerPolicyState.UNRESTRICTED -> DiscoveryPowerCopy.UNRESTRICTED
                    DiscoveryPowerPolicyState.NOT_CONNECTED -> error("enabled signals")
                }
                assertTrue(lines.contains(text.get(key, text.get(DiscoveryPowerCopy.SIMULATED))))
                if (status.state == DiscoveryPowerPolicyState.PAUSED) assertEquals(0, status.concurrency)
            }
        }
    }
}
