package com.easysavevideo.searxng

import com.easysavevideo.discovery.CandidateMetadataLevel
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SearxngProtocolTest {
    @org.junit.Test fun malformedUnicodeEscapesRejectSignedOrNonHexDigits() {
        for (escape in listOf("+041", "-001", " 041", "0x41")) {
            val body = "{\"results\":[{\"url\":\"https://video.example.org/watch\",\"title\":\"\\u" + escape + "\"}]}"
            try { SearxngResponseParser.parse(body.toByteArray(), 100) {}; org.junit.Assert.fail(escape) }
            catch (expected: SearxngFailure) { org.junit.Assert.assertEquals(SearxngError.INVALID_RESPONSE, expected.code) }
        }
    }

    @Test fun endpointsHaveStableCanonicalInstanceIdsAndExactUrlVersions() {
        val first = endpoint()
        assertEquals(first.version, endpoint().version)
        assertNotEquals(first.version, endpoint("https://other.example.org/search").version)
        assertEquals("/search", first.uri.path)
        assertEquals("searxng-1", first.id)
        for (id in listOf("searxng-1", "searxng-2", "searxng-3", "searxng-4", "searxng-1000", "searxng-${Long.MAX_VALUE}"))
            assertEquals(id, endpoint(id = id).id)
        for (id in listOf("searxng-0", "searxng-01", "searxng-", "searxng-+1", "searxng--1", "searxng-١",
            "searxng-1 ", "searxng-1\n", "searxng- 1", "searxng-1.0", "searxng-1e1", "search", "SEARXNG-1",
            "searxng-9223372036854775808", "searxng-99999999999999999999")) invalidEndpoint { endpoint(id = id) }
    }
    @Test fun endpointRejectsCredentialsPortsPrivateNamesAndAmbiguousPaths() {
        for (url in listOf("http://search.example.org/search", "https://u:p@search.example.org/search",
            "https://search.example.org:443/search", "https://search.example.org/search?q=x",
            "https://search.example.org/search#fragment", "https://search.example.org/", "https://search.example.org/prefix/",
            "https://search.example.org/a/../search", "https://search.example.org/%73earch", "https://localhost/search",
            "https://localhost.example.org/search", "https://foo.local/search", "https://foo.internal/search",
            "https://foo.onion/search", "https://foo.example/search", "https://127.0.0.1/search", "https://[::1]/search", "https://2130706433/search",
            "https://0x7f.0.0.1/search", "https://search.example.org./search", "https://search.example.org/search\n"))
            invalidEndpoint { endpoint(url) }
    }
    @Test fun explicitDeploymentPrefixesArePreservedWithoutPathDiscoveryOrNormalization() {
        for (path in listOf("/search", "/searxng/search", "/tenant-1/meta_search/v1.2/~private/search")) {
            val configured = endpoint("https://search.example.org$path")
            assertEquals(path, configured.uri.rawPath)
            assertEquals("https://search.example.org$path", configured.searchUrl)
            assertTrue(SearxngUrlPolicy.isEndpoint(configured.uri))
        }
        assertNotEquals(endpoint().version, endpoint("https://search.example.org/searxng/search").version)
        assertNotEquals(endpoint("https://search.example.org/searxng/search").version,
            endpoint("https://search.example.org/Searxng/search").version)
    }
    @Test fun encodedReservedOrAmbiguousDeploymentPathsAreNotEndpoints() {
        for (path in listOf("/prefix", "/prefix/search/", "/prefix/Search", "//search", "/prefix//search",
            "/./search", "/../search", "/prefix/./search", "/prefix/../search", "/%2e/search", "/%2E%2E/search",
            "/prefix%2fextra/search", "/prefix%2Fextra/search", "/prefix%252fextra/search", "/prefix%5c/search",
            "/%255c/search", "/prefix%00/search", "/prefix%0D/search", "/prefix%7f/search", "/prefix%257f/search",
            "/prefix/%73earch", "/pre%66ix/search", "/prefix;parameter/search", "/prefix@other/search",
            "/prefix:other/search", "/prefix+other/search", "/搜索/search", "/%E6%90%9C%E7%B4%A2/search"))
            invalidEndpoint { endpoint("https://search.example.org$path") }
        invalidEndpoint { endpoint("https://search.example.org/prefix\\other/search") }
    }
    @Test fun longBoundedPrefixesDoNotCauseRecursivePathValidation() {
        val url = "https://search.example.org/" + "a/".repeat(5000) + "search"
        assertEquals(url, endpoint(url).searchUrl)
        invalidEndpoint { endpoint("https://search.example.org/" + "a/".repeat(8200) + "search") }
    }
    @Test fun prefixedRequestsCannotEscapeToRootSiblingOrEncodedEquivalent() {
        val configured = endpoint("https://search.example.org/one/search")
        SearxngUrlPolicy.requireRequest(configured, URI(configured.searchUrl + "?q=preserved%2Fquery"), SearxngRequestKind.JSON_SEARCH)
        for (path in listOf("/search", "/two/search", "/one/elsewhere", "/one/search/", "/%6fne/search", "/one//search"))
            protocolFails(SearxngError.UNSAFE_URL) {
                SearxngUrlPolicy.requireRequest(configured, URI("https://search.example.org$path?q=x"), SearxngRequestKind.JSON_SEARCH)
            }
    }
    @Test fun networkApiIsTiedToSelectedAuthorityAndPath() {
        val endpoint = endpoint()
        SearxngUrlPolicy.requireApi(endpoint, URI(endpoint.searchUrl + "?q=test"))
        for (url in listOf("https://other.example.org/search?q=x", endpoint.searchUrl,
            "https://search.example.org/elsewhere?q=x", endpoint.searchUrl + "?q=%0d%0aHost:evil"))
            protocolFails(SearxngError.UNSAFE_URL) { SearxngUrlPolicy.requireApi(endpoint, URI(url)) }
    }
    @Test fun addressPolicyRejectsPrivateSpecialAndTransitionRangesWithoutResolving() {
        for (value in listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1",
            "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.2", "203.0.113.4", "224.0.0.1", "255.255.255.255",
            "::1", "fe80::1", "fc00::1", "2001:db8::1", "2002::1"))
            assertFalse(value, SearxngUrlPolicy.isPublicAddress(InetAddress.getByName(value)))
        assertTrue(SearxngUrlPolicy.isPublicAddress(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
    }
    @Test fun omittedAndUntrustedTotalCountsDoNotAffectCandidates() {
        val plain = parse(ONE)
        val bogus = parse(ONE.dropLast(1) + ",\"number_of_results\":{\"anything\":true}}")
        assertEquals(plain, bogus)
        val candidate = plain.single()
        assertEquals("https://videos.example.org/watch/one", candidate.pageUrl)
        assertEquals("A short film", candidate.sourceReported!!.title)
        assertEquals("videos.example.org", candidate.sourceReported!!.sourceName)
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, candidate.sourceReported!!.metadataLevel)
        assertNull(candidate.sourceReported!!.reportedDurationMs)
        assertNull(candidate.sourceReported!!.reportedPublishedAtEpochMillis)
    }
    @Test fun invalidUrlsAreNotCandidatesAndSafeDuplicatesAreCoalesced() {
        val rows = listOf("https://u:p@video.example.org/a", "file:///tmp/a", "javascript:alert(1)", "https://127.0.0.1/a",
            "https://video.local/a", "https://video.example.org:8443/a", "https://video.example.org/a", "https://video.example.org/a")
        val json = "{\"results\":[" + rows.joinToString(",") { "{\"url\":\"$it\"}" } + "]}"
        assertEquals(listOf("https://video.example.org/a"), parse(json).map { it.pageUrl })
    }
    @Test fun oversizedResultsTextAndUrlsAreBounded() {
        protocolFails(SearxngError.TOO_MANY_CANDIDATES) { parse("{\"results\":[" + "{}".repeatRows(101) + "]}") }
        val value = parse("{\"results\":[{\"url\":\"https://videos.example.org/a\",\"title\":\"${"x".repeat(2048)}\",\"content\":\"${"d".repeat(4096)}\"}]}").single()
        assertEquals(512, value.sourceReported!!.title!!.length)
        assertEquals(2048, value.sourceReported!!.description!!.length)
        assertTrue(parse("{\"results\":[{\"url\":\"https://videos.example.org/${"x".repeat(4096)}\"}]}").isEmpty())
    }
    @Test fun malformedDuplicateDeepAndNonUtf8JsonFailClosed() {
        for (json in listOf("{}", "[]", "{\"results\":null}", "{\"results\":[],\"results\":[]}",
            "{\"results\":[],}", "{\"results\":[]} trailing", "{\"results\":[null]}",
            "{\"results\":[],\"x\":01}", "{\"results\":[],\"x\":1e}", "{\"results\":[],\"x\":\"\\ud800\"}",
            "{\"results\":[],\"x\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}"))
            protocolFails { parse(json) }
        protocolFails { StrictJson.read(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        protocolFails(SearxngError.RESPONSE_TOO_LARGE) { StrictJson.read(ByteArray(2 * 1024 * 1024 + 1)) }
    }
    @Test fun parsingChecksCancellationInsideLargeWhitespaceAndText() {
        for (json in listOf(" ".repeat(4096) + "{}", "{\"x\":\"" + "x".repeat(4096) + "\"}",
            "{\"x\":\"" + "\\u0061".repeat(4096) + "\"}")) {
            var checks = 0
            protocolFails(SearxngError.CANCELLED) { StrictJson.read(json.toByteArray()) {
                if (++checks > 4) throw SearxngFailure(SearxngError.CANCELLED)
            } }
        }
        protocolFails { StrictJson.read(("{\"x\":" + "1".repeat(1024) + "}").toByteArray()) }
    }
    @Test fun cursorBindsExactQueryLanguageEndpointVersionProfileAndPageLimits() {
        val endpoint = endpoint(); val limits = SearxngLimits()
        fun key(query: String = "Film", language: String = "en", versionEndpoint: SearxngEndpoint = endpoint,
            profile: String = "profile") = SearxngCursor.key(versionEndpoint, query, language, profile, limits)
        val token = SearxngCursor.encode(2, key(), 301_000)
        assertEquals(2, SearxngCursor.decode(token, key(), 1000, 20) {})
        for (key in listOf(key(query = "film"), key(language = "fr"), key(profile = "other"),
            key(versionEndpoint = endpoint("https://other.example.org/search")),
            key(versionEndpoint = endpoint("https://search.example.org/prefix/search")),
            key(versionEndpoint = endpoint(id = "searxng-4")), key(versionEndpoint = endpoint(id = "searxng-${Long.MAX_VALUE}"))))
            protocolFails(SearxngError.INVALID_CURSOR) { SearxngCursor.decode(token, key, 1000, 20) {} }
        protocolFails(SearxngError.INVALID_CURSOR) { SearxngCursor.decode(token, key(), 301_000, 20) {} }
        protocolFails(SearxngError.INVALID_CURSOR) { SearxngCursor.decode(token, key(), 0, 20) {} }
        for (page in listOf(0, 1, 21, Int.MAX_VALUE)) protocolFails(SearxngError.INVALID_CURSOR) {
            SearxngCursor.decode(SearxngCursor.encode(page, key(), 2000), key(), 1000, 20) {}
        }
        val duplicate = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"v\":1,\"v\":1}".toByteArray())
        protocolFails(SearxngError.INVALID_CURSOR) { SearxngCursor.decode(duplicate, key(), 1000, 20) {} }
    }
    @Test fun retryAfterIsOverflowSafeAndNeverMovesBackwards() {
        assertEquals(2000L, SearxngRetryAfter.retryAt("0", 1000))
        assertEquals(31000L, SearxngRetryAfter.retryAt(null, 1000))
        assertEquals(Long.MAX_VALUE, SearxngRetryAfter.retryAt("999999999999999999999", 1000))
        assertEquals(Long.MAX_VALUE, SearxngRetryAfter.retryAt("100", Long.MAX_VALUE - 50))
    }
    private fun parse(json: String) = SearxngResponseParser.parse(json.toByteArray(), 100) {}
    private fun String.repeatRows(count: Int): String = List(count) { this }.joinToString(",")
    private fun invalidEndpoint(action: () -> Unit) {
        try { action(); fail("Expected invalid endpoint") } catch (_: IllegalArgumentException) { }
    }
}
