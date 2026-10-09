package com.easysavevideo.app.discovery

import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationPolicyTest {
    private val endpoint = "https://search.example.org/search"
    private fun accepted(value: SearxngConfigurationEdit) = (value as SearxngConfigurationEdit.Accepted).config

    @Test fun defaultsHaveExactlyThreeDisabledSlotsAndNoEndpoint() {
        val empty = SearxngConfigurationPolicy.empty()
        assertEquals(listOf("searxng-1", "searxng-2", "searxng-3"), empty.slots.map { it.id })
        assertTrue(empty.slots.all { !it.enabled && it.searchUrl == null })
        assertTrue(empty.enabledEndpoints.isEmpty())
        assertEquals(0L, empty.revision)
    }
    @Test fun additionEvenWhenDisabledAndReenableRequireAcknowledgement() {
        val empty = SearxngConfigurationPolicy.empty()
        for (enabled in listOf(true, false)) assertEquals(
            SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(empty, "searxng-1", endpoint, enabled, false))
        val disabled = accepted(SearxngConfigurationPolicy.replace(empty, "searxng-1", endpoint, false, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, false))
        assertEquals(1, accepted(SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, true)).enabledEndpoints.size)
    }
    @Test fun disableAndRemoveDoNotNeedFreshAcknowledgement() {
        val enabled = accepted(SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1", endpoint, true, true))
        val disabled = accepted(SearxngConfigurationPolicy.replace(enabled, "searxng-1", endpoint, false, false))
        assertTrue(disabled.enabledEndpoints.isEmpty()); assertEquals(endpoint, disabled.slots.first().searchUrl)
        val removed = accepted(SearxngConfigurationPolicy.replace(enabled, "searxng-1", "", false, false))
        assertNull(removed.slots.first().searchUrl)
    }
    @Test fun anyEndpointChangeRequiresNewAcknowledgementAndPreservesOtherSlots() {
        val enabled = accepted(SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1", endpoint, true, true))
        val second = accepted(SearxngConfigurationPolicy.replace(enabled, "searxng-2", "https://other.example.org/search", true, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(second, "searxng-1", "https://different.example.org/search", false, false))
        val changed = accepted(SearxngConfigurationPolicy.replace(second, "searxng-1", "https://different.example.org/search", false, true))
        assertEquals(second.slots[1], changed.slots[1]); assertEquals(second.slots[2], changed.slots[2])
    }
    @Test fun everyEffectiveMutationAndResetChangesPersistentGeneration() {
        val empty = SearxngConfigurationPolicy.empty()
        val added = accepted(SearxngConfigurationPolicy.replace(empty, "searxng-1", endpoint, true, true))
        val disabled = accepted(SearxngConfigurationPolicy.replace(added, "searxng-1", endpoint, false, false))
        val enabledAgain = accepted(SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, true))
        val reset = accepted(SearxngConfigurationPolicy.reset(enabledAgain))
        val resetAgain = accepted(SearxngConfigurationPolicy.reset(reset))
        assertEquals(6, listOf(empty, added, disabled, enabledAgain, reset, resetAgain).map { it.generation }.toSet().size)
        assertEquals(empty.generation, SearxngConfigurationPolicy.empty().generation)
        assertEquals(SearxngConfigurationEdit.Unchanged,
            SearxngConfigurationPolicy.replace(added, "searxng-1", endpoint, true, false))
    }
    @Test fun invalidEndpointsAndBlankEnabledSlotAreRejectedWithoutAnyFallback() {
        val invalid = listOf("http://search.example.org/search", "https://user:secret@search.example.org/search",
            "https://search.example.org/search?q=private", "https://search.example.org/search#fragment",
            "https://search.example.org:444/search", "https://127.0.0.1/search", "https://[::1]/search",
            "https://localhost/search", "https://host.local/search", "https://search.example.org/other", " ", "x".repeat(2049))
        for (url in invalid) assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INVALID_ENDPOINT),
            SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1", url, true, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INVALID_ENDPOINT),
            SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1", "", true, true))
    }
    @Test fun unknownOrDuplicateSlotsAndVersionOverflowFailClosed() {
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.UNKNOWN_SLOT),
            SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-4", endpoint, true, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.REVISION_EXHAUSTED),
            SearxngConfigurationPolicy.reset(SearxngConfigurationPolicy.empty(Long.MAX_VALUE)))
        try { SearxngSourceConfiguration(1, List(3) { SearxngSourceSlot("searxng-1") }); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun mutableInputIsDetachedAndDiagnosticsAreRedacted() {
        val input = SearxngConfigurationPolicy.empty().slots.toMutableList()
        val config = SearxngSourceConfiguration(5, input)
        input.clear(); assertEquals(3, config.slots.size)
        val populated = accepted(SearxngConfigurationPolicy.replace(config, "searxng-1", endpoint, true, true))
        assertFalse(populated.toString().contains("example")); assertFalse(populated.slots.first().toString().contains("example"))
        assertEquals("search.example.org", populated.slots.first().hostname)
    }
}
