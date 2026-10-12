package com.easysavevideo.app.browser.history

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class HistoryNavigationGateTest {
    private val gate = HistoryNavigationGate()
    private val view = Any()
    private val context = BrowserContextRef("tab", "session")
    private val address = "https://example.com/video?q=synthetic%20value#position"
    private val tab = BrowserTab(context, epoch = 1, address = PageAddress(address), loadState = BrowserLoadState.READY)
    private val permit = gate.begin(context, 1, view)
    private fun finish(p: HistoryNavigationPermit = permit, t: BrowserTab = tab, selected: BrowserContextRef = context,
        v: Any = view, actual: String? = address, admitted: String? = address,
        current: Boolean = true, accepted: Boolean = true, active: Boolean = true) =
        gate.complete(p, t, selected, v, actual, admitted, "A\nSafe\u202e title", current, accepted, active, 100)

    @Test fun trustedCompletionPreservesApprovedQueryFragmentAndSanitizesTitle() {
        val result = requireNotNull(finish()); assertEquals(address, result.address); assertEquals("A Safe title", result.title)
    }
    @Test fun repeatedFinishAndRepeatedRecordAreSingleUse() {
        val visit = requireNotNull(finish()); assertNull(finish()); assertTrue(gate.take(visit)); assertFalse(gate.take(visit))
    }
    @Test fun failedOrUnacceptedCompletionNeverRecords() {
        for (state in listOf(BrowserLoadState.LOADING, BrowserLoadState.FAILED, BrowserLoadState.CANCELLED,
            BrowserLoadState.RENDERER_UNAVAILABLE, BrowserLoadState.IDLE)) assertNull(finish(t = tab.copy(loadState = state)))
        assertNull(finish(accepted = false)); assertNotNull(finish())
    }
    @Test fun incognitoNeverProducesOrdinaryRecord() { assertNull(finish(t = tab.copy(incognito = true))) }
    @Test fun oldSessionEpochViewEntrySelectionAndBackgroundReject() {
        assertNull(finish(t = tab.copy(context = BrowserContextRef("tab", "new-session"))))
        assertNull(finish(t = tab.copy(epoch = 2))); assertNull(finish(v = Any()))
        assertNull(finish(current = false)); assertNull(finish(selected = BrowserContextRef("other-tab", "other-session")))
        assertNull(finish(active = false)); assertNotNull(finish())
    }
    @Test fun addressMustMatchTabViewAndNavigationAdmission() {
        assertNull(finish(actual = "https://example.com/old")); assertNull(finish(admitted = "https://example.com/old"))
        assertNull(finish(actual = null)); assertNull(finish(t = tab.copy(address = PageAddress("https://example.com/old"))))
    }
    @Test fun unsafeAddressesNeverBecomeVisits() {
        // The core address type rejects these before a tab can contain them.
        for (url in listOf("javascript:1", "file:///tmp/x", "intent://x", "https://user:pass@example.com/"))
            assertThrows(IllegalArgumentException::class.java) { PageAddress(url) }
        // These reach the stronger bookmark/history URL boundary with all identities and
        // addresses equal, so this actually exercises canonicalAddress rather than mismatch.
        for (url in listOf("https://example.com/\u202efoo", "https://example.com/\u200bfoo", "https://example.com/\ud800foo"))
            assertNull(gate.complete(permit, tab.copy(address = PageAddress(url)), context, view, url, url,
                "x", true, true, true, 100))
    }
    @Test fun clearedGenerationRejectsStartedBeforeClearAndUndeliveredCompletion() {
        val visit = requireNotNull(finish()); gate.invalidate(); assertFalse(gate.take(visit)); assertNull(finish())
        assertNotNull(finish(p = gate.begin(context, 1, view)))
    }
    @Test fun foreignGateCannotReplayAuthority() {
        val other = HistoryNavigationGate(); assertNull(finish(p = other.begin(context, 1, view)))
        assertFalse(other.take(requireNotNull(finish())))
    }
    @Test fun functionalDataToStringIsRedacted() {
        assertFalse(requireNotNull(finish()).toString().contains("example.com"))
        assertFalse(historyItem().toString().contains("example.com")); assertFalse(permit.toString().contains("session"))
    }
}
