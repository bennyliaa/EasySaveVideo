package com.easysavevideo.app.browser
import org.junit.Assert.*
import org.junit.Test
class BrowserPolicyTest {
    @Test fun acceptsOnlyPublicWebSchemes() {
        listOf("javascript:alert(1)","file:///tmp/a", "content://x/a", "intent://x", "https://user:pass@example.com", "https://example.com\\@bad.test", "https://x:0/a").forEach {
            assertNull(it, BrowserInputPolicy.address(it))
        }
        assertEquals("https://example.com", BrowserInputPolicy.address("example.com", true)?.value)
        assertNotNull(BrowserInputPolicy.address("http://127.0.0.1:8080/fixture"))
    }
    @Test fun originNormalizesPortsButNeverIncludesPath() {
        assertEquals("https://example.com", BrowserInputPolicy.origin("https://EXAMPLE.com:443/private?token=a"))
        assertEquals("http://example.com:8080", BrowserInputPolicy.origin("http://example.com:8080/a"))
    }
    @Test fun mimeValuesAreBoundedAndSanitized() {
        assertArrayEquals(arrayOf("image/*", "video/mp4"), BrowserInputPolicy.safeAcceptTypes(arrayOf("image/*, video/mp4", "../bad", "", "image/*")))
    }
    @Test fun previousPageCannotFinishNewNavigationBeforeStart() {
        val admission = NavigationAdmission("https://b.test")
        assertFalse(admission.onStarted("https://a.test"))
        assertFalse(admission.accepts("https://a.test"))
        assertFalse(admission.accepts("https://b.test"))
        assertTrue(admission.onStarted("https://b.test"))
        assertTrue(admission.accepts("https://b.test"))
        assertFalse(admission.accepts("https://a.test"))
    }
    @Test fun redirectNeedsExplicitAdmissionAndStopIsTerminal() {
        val admission = NavigationAdmission("https://a.test")
        assertFalse(admission.onStarted("https://b.test"))
        admission.allowRedirect("https://b.test")
        assertTrue(admission.onStarted("https://b.test"))
        admission.cancel()
        admission.allowRedirect("https://c.test")
        assertFalse(admission.onStarted("https://c.test"))
        assertFalse(admission.accepts("https://b.test"))
    }
    @Test fun historyAllowsFirstStartThenRejectsUnannouncedPage() {
        val admission = NavigationAdmission(null)
        assertTrue(admission.onStarted("https://a.test"))
        assertFalse(admission.onStarted("https://b.test"))
    }
}
