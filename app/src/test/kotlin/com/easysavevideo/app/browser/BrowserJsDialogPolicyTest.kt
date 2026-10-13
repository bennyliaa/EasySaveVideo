package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserJsDialogPolicyTest {
    @Test fun sourceContainsOnlyCanonicalOrigin() {
        assertEquals("https://example.com", BrowserJsDialogPolicy.origin("https://EXAMPLE.com:443/path?secret=yes#x", "https://example.com/a"))
        assertEquals("http://localhost:8080", BrowserJsDialogPolicy.origin("http://localhost:8080/page", "http://localhost:8080/other"))
    }
    @Test fun crossOriginAndOpaqueFrameSourcesAreRejected() {
        listOf("https://other.test", "http://example.com", "https://example.com:444", "about:blank", "data:text/html,x", "https://user:secret@example.com").forEach {
            assertNull(it, BrowserJsDialogPolicy.origin(it, "https://example.com"))
        }
        assertNull(BrowserJsDialogPolicy.origin("https://example.com", null))
    }
    @Test fun sourceLengthIsBounded() {
        assertNull(BrowserJsDialogPolicy.origin("https://${"a".repeat(301)}.test", "https://${"a".repeat(301)}.test"))
    }
    @Test fun messageIsPlainTextWithBidiAndControlsNeutralized() {
        assertEquals("<b>hello</b>\n\t\ufffd\ufffd\ufffd", BrowserJsDialogPolicy.message("<b>hello</b>\n\t\u0000\u202e\u2066"))
    }
    @Test fun messageIsBoundedWithoutSplittingSurrogatePair() {
        assertEquals(4097, BrowserJsDialogPolicy.message("a".repeat(5000)).length)
        val value = BrowserJsDialogPolicy.message("a".repeat(4095) + "\ud83d\ude00" + "tail")
        assertEquals("a".repeat(4095) + "…", value)
    }
    @Test fun inputBoundsDoNotTransformSubmittedText() {
        assertTrue(BrowserJsDialogPolicy.validInput("")); assertTrue(BrowserJsDialogPolicy.validInput("a".repeat(1024)))
        assertFalse(BrowserJsDialogPolicy.validInput("a".repeat(1025)))
    }
}
