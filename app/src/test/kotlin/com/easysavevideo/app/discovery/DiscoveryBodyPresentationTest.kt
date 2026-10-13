package com.easysavevideo.app.discovery

import com.easysavevideo.app.R
import com.easysavevideo.discovery.*
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class DiscoveryBodyPresentationTest {
    private fun status(state: DiscoveryState, error: DiscoveryError? = null) =
        DiscoveryStatus(state, 0, 0, 0, 0, emptyMap(), lastError = error)
    private fun body(
        state: DiscoveryState? = null, error: DiscoveryError? = null,
        results: Boolean = false, candidates: Boolean = false, selectedCandidates: Boolean = false,
        ready: Boolean = true, unavailable: Boolean = false, searching: Boolean = false, pageWaiting: Boolean = false,
    ) = discoveryBodyState(results, candidates, selectedCandidates, ready, unavailable, searching, pageWaiting,
        state?.let { status(it, error) })

    @Test fun allDomainStatesHaveDistinctHonestPresentations() {
        val expected = mapOf(
            DiscoveryState.NO_SOURCES to DiscoveryBodyState.NO_SOURCES,
            DiscoveryState.SUSPENDED to DiscoveryBodyState.SUSPENDED,
            DiscoveryState.IDLE to DiscoveryBodyState.IDLE,
            DiscoveryState.FILLING to DiscoveryBodyState.FILLING,
            DiscoveryState.BUFFERED to DiscoveryBodyState.BUFFERED,
            DiscoveryState.BACKOFF to DiscoveryBodyState.BACKOFF,
            DiscoveryState.EXHAUSTED to DiscoveryBodyState.EXHAUSTED,
            DiscoveryState.CAPACITY_REACHED to DiscoveryBodyState.CAPACITY_REACHED,
            DiscoveryState.UNSUPPORTED_FILTER to DiscoveryBodyState.UNSUPPORTED_FILTER,
            DiscoveryState.FAILED to DiscoveryBodyState.FAILED,
        )
        assertEquals(DiscoveryState.entries.toSet(), expected.keys)
        expected.forEach { (state, presentation) -> assertEquals(state.name, presentation, body(state)) }
    }

    @Test fun existingResultsOutrankEveryFeedbackFlagUnlessUserSelectedCandidates() {
        for (state in DiscoveryState.entries) for (candidates in listOf(false, true)) {
            assertEquals(DiscoveryBodyState.CONTENT, body(state, results = true, candidates = candidates,
                ready = false, unavailable = true, searching = true, pageWaiting = true))
        }
    }

    @Test fun candidatePriorityExactlyMatchesExistingBranch() {
        for (results in listOf(false, true)) for (candidates in listOf(false, true)) for (selected in listOf(false, true)) {
            val expected = if (candidates && (selected || !results)) DiscoveryBodyState.PENDING_CANDIDATES
                else if (results) DiscoveryBodyState.CONTENT else DiscoveryBodyState.STORAGE_UNAVAILABLE
            assertEquals(expected, body(results = results, candidates = candidates, selectedCandidates = selected,
                ready = false, unavailable = true, searching = true))
        }
    }

    @Test fun storageFailureOutranksRestorationAndAllPendingStatus() {
        for (state in DiscoveryState.entries) assertEquals(DiscoveryBodyState.STORAGE_UNAVAILABLE,
            body(state, ready = false, unavailable = true, searching = true, pageWaiting = true))
    }

    @Test fun restorationOutranksSearchAndStaleDomainStatus() {
        for (state in DiscoveryState.entries) assertEquals(DiscoveryBodyState.RESTORING,
            body(state, ready = false, searching = true, pageWaiting = true))
        assertEquals(DiscoveryBodyState.RESTORING, body(ready = false))
    }

    @Test fun acceptedFilterSubmissionIsNotOldQueryEmptyResult() {
        for (state in DiscoveryState.entries) assertEquals(DiscoveryBodyState.SEARCHING,
            body(state, searching = true, pageWaiting = true))
    }

    @Test fun waitingPageOnlySpecializesIdleOrUnknownAndDoesNotHideErrors() {
        assertEquals(DiscoveryBodyState.WAITING_FOR_PAGE, body(pageWaiting = true))
        assertEquals(DiscoveryBodyState.WAITING_FOR_PAGE, body(DiscoveryState.IDLE, pageWaiting = true))
        for (state in DiscoveryState.entries.filter { it != DiscoveryState.IDLE })
            assertEquals(body(state), body(state, pageWaiting = true))
        assertEquals(DiscoveryBodyState.IDLE, body())
    }

    @Test fun unsupportedReasonsAreScopedToActualUnsupportedState() {
        assertEquals(DiscoveryBodyState.INVALID_QUERY, body(DiscoveryState.UNSUPPORTED_FILTER, DiscoveryError.INVALID_QUERY))
        assertEquals(DiscoveryBodyState.SOURCE_CONFIGURATION, body(DiscoveryState.UNSUPPORTED_FILTER, DiscoveryError.SOURCE_CONFIGURATION))
        for (error in DiscoveryError.entries) {
            assertEquals(DiscoveryBodyState.BACKOFF, body(DiscoveryState.BACKOFF, error))
            assertEquals(DiscoveryBodyState.FAILED, body(DiscoveryState.FAILED, error))
        }
    }

    @Test fun allEmptyBodyKindsHaveResourceTextAndContentDoesNot() {
        for (state in DiscoveryBodyState.entries) {
            val text = discoveryBodyText(state)
            if (state in setOf(DiscoveryBodyState.CONTENT, DiscoveryBodyState.PENDING_CANDIDATES)) assertNull(text)
            else { assertNotNull(state.name, text); assertTrue(text!!.title != 0); assertTrue(text.detail != 0) }
        }
        assertEquals(R.string.flow_discovery_no_results, discoveryBodyText(DiscoveryBodyState.EXHAUSTED)!!.title)
        for (state in listOf(DiscoveryBodyState.RESTORING, DiscoveryBodyState.FILLING, DiscoveryBodyState.BACKOFF,
            DiscoveryBodyState.FAILED, DiscoveryBodyState.SEARCHING, DiscoveryBodyState.WAITING_FOR_PAGE))
            assertNotEquals(R.string.flow_discovery_no_results, discoveryBodyText(state)!!.title)
    }

    @Test fun aFinishedRoundDoesNotAssertAllProvidersAreExhausted() {
        for (providerState in ProviderDiscoveryState.entries) {
            val snapshot = status(DiscoveryState.EXHAUSTED).copy(providers = listOf(
                ProviderDiscoveryProgress("fixture", providerState, false, null, null, 0, null),
            ))
            val kind = discoveryBodyState(false, false, false, true, false, false, false, snapshot)
            assertEquals(DiscoveryBodyState.EXHAUSTED, kind)
            assertEquals(R.string.discovery_body_round_finished, discoveryBodyText(kind)!!.detail)
            assertNotEquals(R.string.discovery_pages_exhausted, discoveryBodyText(kind)!!.detail)
        }
    }

    private fun source(path: String): String = String(Files.readAllBytes(Paths.get("src", path)), Charsets.UTF_8)

    @Test fun actualScreenUsesOneMappingWithoutReplacingDomainControls() {
        val source = source("main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt")
        assertEquals(1, Regex("val bodyState = discoveryBodyState\\(").findAll(source).count())
        assertTrue(source.contains("if (bodyState == DiscoveryBodyState.PENDING_CANDIDATES)"))
        assertTrue(source.contains("else if (bodyState != DiscoveryBodyState.CONTENT)"))
        assertTrue(source.contains("discoveryBodyText(bodyState)"))
        assertTrue(source.contains("onClick = controller::retry"))
        assertTrue(source.contains("controller.cancelNextPage(state.controlTarget)"))
        val projection = source("main/kotlin/com/easysavevideo/app/discovery/DiscoveryBodyPresentation.kt")
        for (forbidden in listOf("AndroidDiscoveryController", "CoroutineScope", "LaunchedEffect", "navigate(", "retry(", "start("))
            assertFalse(forbidden, projection.contains(forbidden))
    }

    @Test fun newCopyIsPairedAndNoFormatArgumentsOrUntranslatedBlankStrings() {
        fun entries(folder: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Paths.get("src/main/res", folder, "discovery_body.xml").toFile())
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).associate { index -> val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent }
        }
        val en = entries("values");val zh = entries("values-zh-rCN")
        assertEquals(11, en.size);assertEquals(en.keys, zh.keys)
        (en.values + zh.values).forEach { assertTrue(it.isNotBlank());assertFalse(it.contains('%')) }
    }
}
