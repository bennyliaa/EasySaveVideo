package com.easysavevideo.app.discovery

import com.easysavevideo.app.R
import com.easysavevideo.discovery.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class DiscoveryBodyRecoveryTest {
    private fun state(kind: DiscoveryState, error: DiscoveryError? = null) = DiscoveryUiState(ready = true,
        status = DiscoveryStatus(kind, 0, 0, 0, 0, emptyMap(), lastError = error), commonsConfigured = false)
    @Test fun missingOrMisconfiguredSourcesOfferOnlyExistingSourceManagement() {
        assertEquals(DiscoveryBodyRecovery.MANAGE_SOURCES, discoveryBodyRecovery(DiscoveryBodyState.NO_SOURCES))
        assertEquals(DiscoveryBodyRecovery.MANAGE_SOURCES, discoveryBodyRecovery(DiscoveryBodyState.SOURCE_CONFIGURATION))
    }
    @Test fun invalidOrUnsupportedFiltersOfferReviewWithoutAnAutomaticSubmission() {
        assertEquals(DiscoveryBodyRecovery.EDIT_FILTERS, discoveryBodyRecovery(DiscoveryBodyState.INVALID_QUERY))
        assertEquals(DiscoveryBodyRecovery.EDIT_FILTERS, discoveryBodyRecovery(DiscoveryBodyState.UNSUPPORTED_FILTER))
    }
    @Test fun finishedRoundOffersOptionalReviewWithoutCallingItAFilterError() {
        assertEquals(DiscoveryBodyRecovery.EDIT_FILTERS, discoveryBodyRecovery(DiscoveryBodyState.EXHAUSTED))
        assertEquals(R.string.discovery_recovery_filters, discoveryBodyRecoveryLabel(DiscoveryBodyRecovery.EDIT_FILTERS))
    }
    @Test fun waitingBusyContentAndUnrelatedFailuresDoNotGainSpeculativeRecoveryCommands() {
        val supported = setOf(DiscoveryBodyState.NO_SOURCES, DiscoveryBodyState.SOURCE_CONFIGURATION,
            DiscoveryBodyState.INVALID_QUERY, DiscoveryBodyState.UNSUPPORTED_FILTER, DiscoveryBodyState.EXHAUSTED)
        for (kind in DiscoveryBodyState.entries.filterNot { it in supported }) assertNull(kind.name, discoveryBodyRecovery(kind))
    }
    @Test fun correctStateInvokesExactlyItsExistingDestination() {
        for ((snapshot, action) in listOf(state(DiscoveryState.NO_SOURCES) to DiscoveryBodyRecovery.MANAGE_SOURCES,
            state(DiscoveryState.UNSUPPORTED_FILTER, DiscoveryError.SOURCE_CONFIGURATION) to DiscoveryBodyRecovery.MANAGE_SOURCES,
            state(DiscoveryState.UNSUPPORTED_FILTER, DiscoveryError.INVALID_QUERY) to DiscoveryBodyRecovery.EDIT_FILTERS,
            state(DiscoveryState.UNSUPPORTED_FILTER) to DiscoveryBodyRecovery.EDIT_FILTERS,
            state(DiscoveryState.EXHAUSTED) to DiscoveryBodyRecovery.EDIT_FILTERS)) {
            val calls = mutableListOf<String>()
            assertTrue(dispatchDiscoveryBodyRecovery(action, snapshot, snapshot, 1, 1, true,
                { calls += "sources" }, { calls += "filters" }))
            assertEquals(listOf(if (action == DiscoveryBodyRecovery.MANAGE_SOURCES) "sources" else "filters"), calls)
        }
    }
    @Test fun mismatchedActionCannotRedirectToAnotherRecoveryDestination() {
        val snapshot = state(DiscoveryState.NO_SOURCES)
        assertFalse(dispatchDiscoveryBodyRecovery(DiscoveryBodyRecovery.EDIT_FILTERS, snapshot, snapshot, 1, 1, true,
            { fail("source navigation") }, { fail("filter navigation") }))
    }
    @Test fun staleSameValueStateAndLifecycleCannotOpenAnyRecoveryUi() {
        val snapshot = state(DiscoveryState.NO_SOURCES)
        for ((current, life) in listOf(snapshot.copy() to 1L, snapshot to 2L)) {
            assertFalse(dispatchDiscoveryBodyRecovery(DiscoveryBodyRecovery.MANAGE_SOURCES, snapshot, current, 1, life, true,
                { fail("source navigation") }, { fail("filter navigation") }))
        }
    }
    @Test fun backgroundModalSearchPendingAndOtherRoutesAreRejectedByCurrentInteractionGate() {
        val snapshot = state(DiscoveryState.NO_SOURCES)
        assertFalse(dispatchDiscoveryBodyRecovery(DiscoveryBodyRecovery.MANAGE_SOURCES, snapshot, snapshot, 1, 1, false,
            { fail("source navigation") }, { fail("filter navigation") }))
    }
    @Test fun notReadyAndUnavailableDoNotExposeRecoveryThroughStaleStatus() {
        for (snapshot in listOf(state(DiscoveryState.NO_SOURCES).copy(ready = false),
            state(DiscoveryState.NO_SOURCES).copy(storageUnavailable = true))) {
            assertFalse(dispatchDiscoveryBodyRecovery(DiscoveryBodyRecovery.MANAGE_SOURCES, snapshot, snapshot, 1, 1, true,
                { fail("source navigation") }, { fail("filter navigation") }))
        }
    }
    @Test fun bilingualRecoveryCopyIsPairedAndDoesNotClaimAnErrorOrAutoRetry() {
        fun resources(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/discovery_recovery.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(setOf("discovery_recovery_sources", "discovery_recovery_filters", "discovery_open_unavailable"), en.keys)
        assertEquals(en.keys, zh.keys)
        assertEquals("Review query and filters", en.getValue("discovery_recovery_filters"))
        assertEquals("查看关键词和筛选", zh.getValue("discovery_recovery_filters"))
        assertTrue((en.values + zh.values).all { it.isNotBlank() })
    }
}
