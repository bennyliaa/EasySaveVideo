package com.easysavevideo.app.browser.bookmarks

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BookmarkPolicyTest {
    private val context = BrowserContextRef("tab", "session")
    private val tab = BrowserTab(context, epoch = 2, address = PageAddress("https://example.com/"), loadState = BrowserLoadState.READY)
    private fun capture(t: BrowserTab? = tab, actual: String? = "https://example.com/", admitted: String? = actual,
        title: String? = "A page", selection: Long = 4, navigation: Long = 5, active: Boolean = true) =
        BookmarkPolicy.capture(t, actual, admitted, title, selection, navigation, active)
    @Test fun canonicalSchemeHostPortAndEmptyPath() { assertEquals("https://example.com/", BookmarkPolicy.canonicalAddress("HTTPS://EXAMPLE.COM:443")) }
    @Test fun httpDefaultPortAndNondefaultPreserved() {
        assertEquals("http://example.com/", BookmarkPolicy.canonicalAddress("http://EXAMPLE.com:80"))
        assertEquals("https://example.com:8443/", BookmarkPolicy.canonicalAddress("https://example.com:8443"))
    }
    @Test fun pathQueryFragmentAndEscapesPreserved() { assertEquals("https://example.com/a/../%2f?Q=2&Q=1#part", BookmarkPolicy.canonicalAddress("https://EXAMPLE.com/a/../%2f?Q=2&Q=1#part")) }
    @Test fun emptyQueryAndFragmentRemainDistinct() { assertEquals("https://example.com/?#", BookmarkPolicy.canonicalAddress("https://example.com?#")) }
    @Test fun ipv6CanonicalPort() { assertEquals("https://[2001:db8::1]/", BookmarkPolicy.canonicalAddress("HTTPS://[2001:DB8::1]:443")) }
    @Test fun rejectUnsafeSchemesAndCredentials() { listOf("javascript:alert(1)", "file:///a", "content://host/a", "https://u:p@example.com/", "https://u%40p@example.com/", "data:text/plain,a").forEach { assertNull(it, BookmarkPolicy.canonicalAddress(it)) } }
    @Test fun rejectLiteralControlsBidiAndWhitespace() { listOf(" https://example.com/", "https://example.com/ ", "https://example.com/\n", "https://example.com/\u202e", "https://example.com/\u0000", "https://example.com/\\a", "https://example.com/a b").forEach { assertNull(BookmarkPolicy.canonicalAddress(it)) } }
    @Test fun rejectMalformedPortsHostAndSurrogates() { listOf("https:///a", "https://example.com:0/", "https://example.com:65536/", "https://example.com/%xx", "https://example.com/\uD800", "https://[fe80::1%25eth0]/").forEach { assertNull(it, BookmarkPolicy.canonicalAddress(it)) } }
    @Test fun byteLimitNotOnlyUtf16Limit() { assertNull(BookmarkPolicy.canonicalAddress("https://example.com/" + "中".repeat(3000))) }
    @Test fun maximumAsciiAddressRoundtrips() { val s = "https://example.com/" + "a".repeat(8192 - 20); assertEquals(s, BookmarkPolicy.canonicalAddress(s)) }
    @Test fun canonicalIsIdempotent() { listOf("http://EXAMPLE.com:80", "https://example.com/a?x#h", "https://[::1]:443").forEach { val c = BookmarkPolicy.canonicalAddress(it)!!; assertEquals(c, BookmarkPolicy.canonicalAddress(c)) } }
    @Test fun localeCannotChangeCanonicalHost() { val before = Locale.getDefault(); try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("https://i.example/", BookmarkPolicy.canonicalAddress("HTTPS://I.EXAMPLE/")) } finally { Locale.setDefault(before) } }
    @Test fun safeTitleStripsControlsAndBidiAndCollapsesSpaces() { assertEquals("Hello world page", BookmarkPolicy.safeTitle(" \nHello\u202e\tworld\u0000 page\r ", "https://example.com/")) }
    @Test fun blankTitleUsesHost() { assertEquals("example.com", BookmarkPolicy.safeTitle("\u200b\n", "https://example.com/a")) }
    @Test fun titleHtmlIsOnlyPlainText() { assertEquals("<script>alert(1)</script>", BookmarkPolicy.safeTitle("<script>alert(1)</script>", "https://example.com/")) }
    @Test fun titleTruncationPreservesWholeCodepoints() { val title = BookmarkPolicy.safeTitle("😀".repeat(200), "https://example.com/"); assertEquals(160, title.codePointCount(0, title.length)); assertEquals(320, title.length) }
    @Test fun isolatedSurrogateNeverSurvivesTitle() { assertEquals("ab cd", BookmarkPolicy.safeTitle("ab\uD800cd", "https://example.com/")) }
    @Test fun longFallbackHostRemainsWithinLimit() { val host = listOf("a".repeat(60), "b".repeat(60), "c".repeat(60), "com").joinToString("."); val title = BookmarkPolicy.safeTitle(null, "https://$host/"); assertEquals(160, title.length); assertEquals(title, BookmarkPolicy.safeTitle(title, "https://$host/")) }
    @Test fun captureNormalReadyOnly() { assertNotNull(capture()); BrowserLoadState.entries.filter { it != BrowserLoadState.READY }.forEach { assertNull(capture(tab.copy(loadState = it))) } }
    @Test fun captureNoIncognitoOrAbsentPage() { assertNull(capture(tab.copy(incognito = true))); assertNull(capture(t = null)); assertNull(capture(actual = null)); assertNull(capture(tab.copy(epoch = 0))) }
    @Test fun captureRejectsUnadmittedUrlAndInactive() { assertNull(capture(admitted = "https://other.example/")); assertNull(capture(active = false)); assertNull(capture(actual = "javascript:1")) }
    @Test fun captureBindsBothGenerationsContextAndTitle() { val p = capture()!!; assertNotEquals(p, capture(selection = 6)); assertNotEquals(p, capture(navigation = 6)); assertNotEquals(p, capture(tab.copy(epoch = 3))); assertNotEquals(p, capture(tab.copy(context = BrowserContextRef("tab", "new")))); assertNotEquals(p, capture(title = "new title")) }
    @Test fun captureCanonicalizesAndSanitizesBeforePersistence() { val p = capture(actual = "HTTPS://EXAMPLE.COM:443", title = "a\n b")!!; assertEquals("https://example.com/", p.address); assertEquals("a b", p.title) }
    @Test fun acceptUsesCurrentSafeTitleWithoutConfusingItWithNavigationAuthority() { val old = capture()!!; val current = old.copy(title = "Updated title"); assertEquals(current, BookmarkPolicy.accept(old, current)) }
    @Test fun acceptRejectsAnyIdentityAddressOrGenerationChange() { val old = capture()!!; listOf(old.copy(context = BrowserContextRef("tab", "new")), old.copy(epoch = 3), old.copy(selectionRevision = 9), old.copy(navigationRevision = 9), old.copy(address = "https://example.com/changed")).forEach { assertNull(BookmarkPolicy.accept(old, it)) }; assertNull(BookmarkPolicy.accept(null, old)); assertNull(BookmarkPolicy.accept(old, null)) }
    @Test fun functionalDataToStringIsRedacted() { val token = capture()!!; val item = BrowserBookmark("00000000-0000-0000-0000-000000000001", token.address, token.title); listOf(token.toString(), item.toString(), BookmarkState(listOf(item)).toString()).forEach { assertFalse(it.contains(token.address)); assertFalse(it.contains(token.title)) } }

}
