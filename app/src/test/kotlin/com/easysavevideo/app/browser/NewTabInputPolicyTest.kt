package com.easysavevideo.app.browser

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class NewTabInputPolicyTest {
    private fun interpret(value: String, mode: NewTabInputMode = NewTabInputMode.AUTO) = NewTabInputPolicy.interpret(value, mode)
    @Test fun explicitWebAddressNeverBecomesAutomaticProviderQuery() {
        listOf("https://example.test/watch?q=a", "http://127.0.0.1:8080/fixture").forEach {
            assertEquals(NewTabInput.Address(it), interpret(it))
        }
    }
    @Test fun bareDomainAndLocalhostUseExistingBrowserAdmission() {
        listOf("example.test/path", "localhost", "127.0.0.1").forEach {
            assertEquals(NewTabInput.Address("https://$it"), interpret(it))
        }
    }
    @Test fun ordinaryAndPunctuatedKeywordsRemainQueries() {
        listOf("cats", "猫咪视频", "Dr. Who", "cats and dogs", "猫🐈").forEach {
            assertEquals(NewTabInput.Query(it), interpret(it))
        }
    }
    @Test fun userCanCorrectDomainLikeKeywords() {
        assertTrue(interpret("example.test") is NewTabInput.Address)
        assertEquals(NewTabInput.Query("example.test"), interpret("example.test", NewTabInputMode.VIDEO))
    }
    @Test fun userCanCorrectSingleLabelAddress() {
        assertTrue(interpret("intranet") is NewTabInput.Query)
        assertEquals(NewTabInput.Address("https://intranet"), interpret("intranet", NewTabInputMode.ADDRESS))
    }
    @Test fun explicitQueryModeDoesNotOpenAddress() {
        assertEquals(NewTabInput.Query("https://example.test/fixture"), interpret("https://example.test/fixture", NewTabInputMode.VIDEO))
    }
    @Test fun unsafeAndMalformedAddressNeverFallBackToSearch() {
        listOf("javascript:alert(1)", "file:///tmp/a", "content://fixture/a", "intent://fixture",
            "https://user:pass@example.test", "https://example.test\\@other.test", "https://example.test:0",
            "https://", "foo@example.test", "[broken").forEach {
            assertEquals(it, NewTabInput.Invalid, interpret(it))
            assertEquals(it, NewTabInput.Invalid, interpret(it, NewTabInputMode.ADDRESS))
        }
    }
    @Test fun addressModeRejectsWhitespaceRatherThanExecutingQuery() {
        assertEquals(NewTabInput.Invalid, interpret("cats and dogs", NewTabInputMode.ADDRESS))
    }
    @Test fun blankAndControlCharactersAreRejectedInAllModes() {
        listOf("", "   ", "cats\n", "\tcats", "cat\u0000s", "cats\u007f").forEach { input ->
            NewTabInputMode.entries.forEach { assertEquals(NewTabInput.Invalid, interpret(input, it)) }
        }
    }
    @Test fun ordinaryPaddingIsTrimmed() {
        assertEquals(NewTabInput.Query("cats"), interpret("  cats  "))
        assertEquals(NewTabInput.Address("https://example.test"), interpret("  example.test  "))
    }
    @Test fun queryLengthAndSurrogatesUseExistingDiscoveryPolicy() {
        assertTrue(interpret("猫".repeat(512)) is NewTabInput.Query)
        assertEquals(NewTabInput.Invalid, interpret("猫".repeat(513)))
        assertTrue(interpret("🐈".repeat(256)) is NewTabInput.Query)
        listOf("\uD800", "\uDC00", "a\uD800b").forEach { assertEquals(NewTabInput.Invalid, interpret(it)) }
    }
    @Test fun addressLengthIsBoundedAndOversizedInputNeverTruncatedIntoNavigation() {
        val prefix = "https://example.test/"
        assertTrue(interpret(prefix + "a".repeat(8192 - prefix.length)) is NewTabInput.Address)
        assertEquals(NewTabInput.Invalid, interpret(prefix + "a".repeat(8193 - prefix.length)))
    }
    @Test fun resultStringRepresentationsRedactBusinessData() {
        assertEquals("Address([redacted])", NewTabInput.Address("https://example.test/secret").toString())
        assertEquals("Query([redacted])", NewTabInput.Query("private keywords").toString())
    }
    @Test fun retiredNavigationClosedSwitchedAndBackgroundTabsCannotSubmit() {
        val blank = BrowserTab(BrowserContextRef("tab", "session"))
        assertTrue(NewTabInputPolicy.canSubmit(blank, blank, true))
        assertFalse(NewTabInputPolicy.canSubmit(null, null, true))
        assertFalse(NewTabInputPolicy.canSubmit(blank, null, true))
        assertFalse(NewTabInputPolicy.canSubmit(blank, blank, false))
        assertFalse(NewTabInputPolicy.canSubmit(blank, blank.copy(context = BrowserContextRef("other", "session")), true))
        assertFalse(NewTabInputPolicy.canSubmit(blank, blank.copy(context = BrowserContextRef("tab", "replacement")), true))
        assertFalse(NewTabInputPolicy.canSubmit(blank, blank.copy(epoch = 1), true))
        assertFalse(NewTabInputPolicy.canSubmit(blank, blank.copy(address = PageAddress("https://example.test")), true))
        val loaded = blank.copy(address = PageAddress("https://example.test"))
        assertFalse(NewTabInputPolicy.canSubmit(loaded, loaded, true))
    }
    @Test fun acceptedBlankTabActionCannotReplayEvenAfterReturningToSameTab() {
        val tab = BrowserTab(BrowserContextRef("tab", "session"))
        val gate = NewTabSubmissionGate()
        assertTrue(gate.claim(tab, tab, true))
        assertFalse(gate.claim(tab, tab, true))
        assertFalse(gate.claim(tab, tab, false))
        assertFalse(gate.claim(tab, tab, true))
        assertTrue(NewTabSubmissionGate().claim(tab, tab, true))
    }
    @Test fun rejectedBackgroundOrStaleAttemptDoesNotConsumeTheActiveBlankTab() {
        val tab = BrowserTab(BrowserContextRef("tab", "session"))
        val gate = NewTabSubmissionGate()
        assertFalse(gate.claim(tab, tab, false))
        assertFalse(gate.claim(tab, tab.copy(epoch = 1), true))
        assertTrue(gate.claim(tab, tab, true))
    }
    @Test fun disposalRetiresEvenAnUnclaimedVisit() {
        val tab = BrowserTab(BrowserContextRef("tab", "session"))
        val gate = NewTabSubmissionGate()
        gate.retire()
        assertFalse(gate.claim(tab, tab, true))
    }
}
