package com.easysavevideo.app.discovery

import com.easysavevideo.searxng.SearxngResultFormat
import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationPolicyTest {
    private val endpoint = "https://search.example.org/search"
    private fun accepted(value: SearxngConfigurationEdit) = (value as SearxngConfigurationEdit.Accepted).config
    private fun populated(enabled: Boolean = true, format: SearxngResultFormat = SearxngResultFormat.JSON_VIDEOS) =
        accepted(SearxngConfigurationPolicy.add(SearxngConfigurationPolicy.empty(), endpoint, enabled, true, format))

    @Test fun defaultsHaveNoPhantomRowsAndAdditionStartsDisabled() {
        val empty = SearxngConfigurationPolicy.empty()
        assertTrue(empty.slots.isEmpty()); assertTrue(empty.enabledEndpoints.isEmpty())
        assertEquals(0L, empty.revision); assertEquals(1L, empty.nextInstanceOrdinal)
        val added = accepted(SearxngConfigurationPolicy.add(empty, endpoint, acknowledged = true))
        assertEquals("searxng-1", added.slots.single().id); assertFalse(added.slots.single().enabled)
        assertEquals(SearxngResultFormat.JSON_VIDEOS, added.slots.single().format)
    }
    @Test fun additionEvenWhenDisabledAndReenableRequireAcknowledgement() {
        val empty = SearxngConfigurationPolicy.empty()
        for (enabled in listOf(true, false)) assertEquals(
            SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.add(empty, endpoint, enabled, false))
        val disabled = populated(false)
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, false))
        assertEquals(1, accepted(SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, true)).enabledEndpoints.size)
    }
    @Test fun disableAndRemoveDoNotNeedFreshAcknowledgementAndDeletionReallyRemovesRow() {
        val enabled = populated()
        val disabled = accepted(SearxngConfigurationPolicy.replace(enabled, "searxng-1", endpoint, false, false))
        assertTrue(disabled.enabledEndpoints.isEmpty()); assertEquals(endpoint, disabled.slots.first().searchUrl)
        val removed = accepted(SearxngConfigurationPolicy.remove(enabled, "searxng-1"))
        assertTrue(removed.slots.isEmpty()); assertEquals(2L, removed.nextInstanceOrdinal)
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INVALID_ENDPOINT),
            SearxngConfigurationPolicy.replace(enabled, "searxng-1", "", false, false))
    }
    @Test fun endpointChangeRequiresFreshAcknowledgementAndPreservesOtherEntriesAndIdentity() {
        val enabled = populated()
        val second = accepted(SearxngConfigurationPolicy.add(enabled, "https://other.example.org/search", true, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(second, "searxng-1", "https://different.example.org/search", false, false))
        val changed = accepted(SearxngConfigurationPolicy.replace(second, "searxng-1", "https://different.example.org/search", false, true))
        assertEquals(second.slots[1], changed.slots[1]); assertEquals("searxng-1", changed.slots.first().id)
        assertEquals(second.nextInstanceOrdinal, changed.nextInstanceOrdinal)
    }
    @Test fun everyEffectiveMutationAndResetChangesPersistentGeneration() {
        val empty = SearxngConfigurationPolicy.empty()
        val added = populated()
        val disabled = accepted(SearxngConfigurationPolicy.replace(added, "searxng-1", endpoint, false, false))
        val enabledAgain = accepted(SearxngConfigurationPolicy.replace(disabled, "searxng-1", endpoint, true, true))
        val reset = accepted(SearxngConfigurationPolicy.reset(enabledAgain))
        val resetAgain = accepted(SearxngConfigurationPolicy.reset(reset))
        assertEquals(6, listOf(empty, added, disabled, enabledAgain, reset, resetAgain).map { it.generation }.toSet().size)
        assertEquals(empty.generation, SearxngConfigurationPolicy.empty().generation)
        assertEquals(SearxngConfigurationEdit.Unchanged,
            SearxngConfigurationPolicy.replace(added, "searxng-1", endpoint, true, false))
        assertEquals(2L, resetAgain.nextInstanceOrdinal)
        assertEquals("searxng-2", accepted(SearxngConfigurationPolicy.add(resetAgain, endpoint, acknowledged = true)).slots.single().id)
    }
    @Test fun invalidEndpointsAndBlankEntriesAreRejectedWithoutAnyFallback() {
        val invalid = listOf("http://search.example.org/search", "https://user:secret@search.example.org/search",
            "https://search.example.org/search?q=private", "https://search.example.org/search#fragment",
            "https://search.example.org:444/search", "https://127.0.0.1/search", "https://[::1]/search",
            "https://localhost/search", "https://host.local/search", "https://search.example.org/other", "", " ", "x".repeat(2049))
        for (url in invalid) assertEquals(url, SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INVALID_ENDPOINT),
            SearxngConfigurationPolicy.add(SearxngConfigurationPolicy.empty(), url, true, true))
    }
    @Test fun unknownEntriesAndRevisionOrIdentityExhaustionFailClosed() {
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.UNKNOWN_SLOT),
            SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-4", endpoint, true, true))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.UNKNOWN_SLOT),
            SearxngConfigurationPolicy.remove(SearxngConfigurationPolicy.empty(), "searxng-1"))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.REVISION_EXHAUSTED),
            SearxngConfigurationPolicy.reset(SearxngConfigurationPolicy.empty(Long.MAX_VALUE)))
        val exhausted = SearxngSourceConfiguration(7, emptyList(), Long.MAX_VALUE)
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ID_EXHAUSTED),
            SearxngConfigurationPolicy.add(exhausted, endpoint, acknowledged = true))
        val last = SearxngSourceConfiguration(7, emptyList(), Long.MAX_VALUE - 1)
        val lastAdded = accepted(SearxngConfigurationPolicy.add(last, endpoint, acknowledged = true))
        assertEquals("searxng-${Long.MAX_VALUE - 1}", lastAdded.slots.single().id)
        assertEquals(Long.MAX_VALUE, lastAdded.nextInstanceOrdinal)
    }
    @Test fun countIsBoundedAndDeletedIdentifiersAreNeverReusedAcrossResetOrRefill() {
        var config = SearxngConfigurationPolicy.empty()
        repeat(SearxngConfigurationPolicy.MAX_INSTANCES) { index ->
            config = accepted(SearxngConfigurationPolicy.add(config, "https://s$index.example.org/search", acknowledged = true))
        }
        assertEquals(16, config.slots.size)
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INSTANCE_LIMIT),
            SearxngConfigurationPolicy.add(config, "https://next.example.org/search", acknowledged = true))
        val removed = accepted(SearxngConfigurationPolicy.remove(config, "searxng-8"))
        val added = accepted(SearxngConfigurationPolicy.add(removed, "https://next.example.org/search", acknowledged = true))
        assertEquals("searxng-17", added.slots.last().id)
        assertEquals(config.slots.filterNot { it.id == "searxng-8" }, added.slots.dropLast(1))
        val reset = accepted(SearxngConfigurationPolicy.reset(added))
        assertEquals("searxng-18", accepted(SearxngConfigurationPolicy.add(reset, endpoint, acknowledged = true)).slots.single().id)
    }
    @Test fun invalidDirectStatesAndNoncanonicalIdsFailClosed() {
        for (id in listOf("searxng-0", "searxng-01", "searxng--1", "searxng-9223372036854775808", "x", "SEARXNG-1"))
            try { SearxngSourceSlot(id); fail(id) } catch (_: IllegalArgumentException) { }
        val slot = SearxngSourceSlot("searxng-1", endpoint)
        for (entries in listOf(listOf(slot, slot), listOf(SearxngSourceSlot("searxng-1"))))
            try { SearxngSourceConfiguration(1, entries, 2); fail() } catch (_: IllegalArgumentException) { }
        for (next in listOf(-1L, 0L, 1L))
            try { SearxngSourceConfiguration(1, listOf(slot), next); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun mutableInputIsDetachedAndDiagnosticsAreRedacted() {
        val input = populated().slots.toMutableList()
        val config = SearxngSourceConfiguration(5, input, 2)
        input.clear(); assertEquals(1, config.slots.size)
        assertFalse(config.toString().contains("example")); assertFalse(config.slots.first().toString().contains("example"))
        assertEquals("search.example.org", config.slots.first().hostname)
        try { (config.slots as MutableList<SearxngSourceSlot>).clear(); fail() } catch (_: UnsupportedOperationException) { }
    }
    @Test fun htmlIsExplicitAndRequiresFreshAcknowledgementForEveryFormatChange() {
        val json = populated()
        assertEquals(SearxngResultFormat.JSON_VIDEOS, json.enabledEndpoints.single().format)
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(json, "searxng-1", endpoint, true, false, SearxngResultFormat.HTML_WEB))
        val html = accepted(SearxngConfigurationPolicy.replace(json, "searxng-1", endpoint, true, true, SearxngResultFormat.HTML_WEB))
        assertEquals(SearxngResultFormat.HTML_WEB, html.enabledEndpoints.single().format)
        assertNotEquals(json.generation, html.generation)
        assertEquals(SearxngConfigurationEdit.Unchanged,
            SearxngConfigurationPolicy.replace(html, "searxng-1", endpoint, true, false))
        assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED),
            SearxngConfigurationPolicy.replace(html, "searxng-1", endpoint, true, false, SearxngResultFormat.JSON_VIDEOS))
    }
    @Test fun formatSelectionCannotEnableAnInstanceAndDisableKeepsItsMode() {
        val html = populated(false, SearxngResultFormat.HTML_WEB)
        assertTrue(html.enabledEndpoints.isEmpty())
        val enabled = accepted(SearxngConfigurationPolicy.replace(html, "searxng-1", endpoint, true, true))
        val disabled = accepted(SearxngConfigurationPolicy.replace(enabled, "searxng-1", endpoint, false, false))
        assertEquals(SearxngResultFormat.HTML_WEB, disabled.slots.first().format)
    }
    @Test fun duplicateOriginIsRejectedAcrossModesHostCaseDisabledInstancesAndBasePaths() {
        for (firstFormat in SearxngResultFormat.entries) for (secondFormat in SearxngResultFormat.entries)
            for (enabled in listOf(false, true)) {
                val first = populated(enabled, firstFormat)
                for (url in listOf("https://SEARCH.EXAMPLE.ORG/search", "https://search.example.org/other/search"))
                    assertEquals(SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.DUPLICATE_ORIGIN),
                        SearxngConfigurationPolicy.add(first, url, !enabled, true, secondFormat))
            }
        try {
            SearxngSourceConfiguration(1, listOf(SearxngSourceSlot("searxng-1", endpoint),
                SearxngSourceSlot("searxng-2", endpoint, format = SearxngResultFormat.HTML_WEB)), 3)
            fail("A directly constructed duplicate service must also fail closed")
        } catch (_: IllegalArgumentException) { }
    }
}
