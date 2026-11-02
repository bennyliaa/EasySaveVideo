package com.easysavevideo.commons.rules

import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RuleHttpEndpointTest {
    private fun endpoint(locales: Set<String> = setOf("en", "und"), literal: Set<String> = setOf("5"),
        format: RuleResponseFormat = RuleResponseFormat.JSON, host: String = "json-fixture.invalid", path: String = "/search") =
        RuleHttpEndpoint.https(host, path, format, locales, linkedMapOf(
            "q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY), "lang" to RuleQueryAdmission.Input(RuleInputSlot.LOCALE),
            "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR), "limit" to RuleQueryAdmission.Literals(literal)))
    private fun template() = listOf(RuleHttpQueryValue("q", RuleInputSlot.QUERY), RuleHttpQueryValue("lang", RuleInputSlot.LOCALE),
        RuleHttpQueryValue("after", RuleInputSlot.CURSOR), RuleHttpQueryValue("limit", literal = "5"))
    private fun bind(e: RuleHttpEndpoint = endpoint(), query: String = "owned", locale: String = "und", cursor: String? = null) =
        e.bind(e.endpointId, "/search", template(), query, locale, cursor)
    private fun rejected(code: DiscoveryError? = null, action: () -> Unit) {
        try { action(); fail("expected bounded refusal") }
        catch (failure: DiscoveryFailure) { if (code != null) assertEquals(code, failure.code) }
    }

    @Test fun catalogFrameMatchesIndependentlyFrozenGolden() {
        val endpoint = endpoint()
        assertEquals(224, endpoint.canonicalFrame().size)
        assertEquals("7766210cad6417c5a104190aac51445559db32c499641740ae3c30f1da08d9d9", endpoint.endpointId)
    }
    @Test fun catalogOrderDoesNotChangeIdentityAndInputCollectionsAreDetached() {
        val locales = linkedSetOf("und", "en"); val literals = linkedSetOf("5")
        val policies = linkedMapOf<String, RuleQueryAdmission>("limit" to RuleQueryAdmission.Literals(literals),
            "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR), "lang" to RuleQueryAdmission.Input(RuleInputSlot.LOCALE),
            "q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY))
        val endpoint = RuleHttpEndpoint.https("json-fixture.invalid", "/search", RuleResponseFormat.JSON, locales, policies)
        val id = endpoint.endpointId
        locales += "xx"; literals += "10"; policies.clear()
        assertEquals(endpoint().endpointId, id)
        assertEquals("owned", bind(endpoint).url.toHttpUrl().queryParameter("q"))
        assertFalse("xx" in endpoint.allowedLocales)
    }
    @Test fun everyHostPermissionSemanticChangesCatalogIdentity() {
        val original = endpoint().endpointId
        assertNotEquals(original, endpoint(locales = setOf("en", "und", "zh-CN")).endpointId)
        assertNotEquals(original, endpoint(literal = setOf("5", "10")).endpointId)
        assertNotEquals(original, endpoint(format = RuleResponseFormat.HTML).endpointId)
        assertNotEquals(original, endpoint(host = "other-fixture.invalid").endpointId)
        assertNotEquals(original, endpoint(path = "/other").endpointId)
    }
    @Test fun queryAndCursorAreIndependentlyEncodedScalarValues() {
        val binding = bind(query = "own &=?%#+中", cursor = "https://else.invalid/?x=1&y=%#+")
        val url = binding.url.toHttpUrl()
        assertEquals("json-fixture.invalid", url.host); assertEquals("/search", url.encodedPath)
        assertEquals(4, url.querySize)
        assertEquals("own &=?%#+中", url.queryParameter("q"))
        assertEquals("https://else.invalid/?x=1&y=%#+", url.queryParameter("after"))
        assertNull(url.fragment)
        assertEquals(url.encodedPath + "?" + url.encodedQuery, binding.encodedPathAndQuery)
    }
    @Test fun missingCursorOmitsOnlyItsParameterAndEmptyCursorFails() {
        val url = bind().url.toHttpUrl()
        assertEquals(setOf("q", "lang", "limit"), url.queryParameterNames)
        rejected(DiscoveryError.INVALID_RESULT) { bind(cursor = "") }
        rejected(DiscoveryError.INVALID_RESULT) { bind(cursor = " ") }
    }
    @Test fun endpointPathAndQueryScopeMismatchFailBeforeBinding() {
        val e = endpoint()
        rejected { e.validateTemplate("0".repeat(64), "/search", template()) }
        rejected { e.validateTemplate(e.endpointId, "/other", template()) }
        for (values in listOf(emptyList(), template() + template().first(), listOf(RuleHttpQueryValue("secret", literal = "5")),
            listOf(RuleHttpQueryValue("q", RuleInputSlot.CURSOR)), listOf(RuleHttpQueryValue("limit", literal = "10")))) {
            rejected { e.validateTemplate(e.endpointId, "/search", values) }
        }
    }
    @Test fun emptyLiteralAndAmbiguousTypedBindingsCannotBeConstructed() {
        for (value in listOf("", " ")) try { RuleQueryAdmission.Literals(setOf(value)); fail("empty literal") } catch (_: IllegalArgumentException) { }
        try { RuleHttpQueryValue("q"); fail("missing binding") } catch (_: IllegalArgumentException) { }
        try { RuleHttpQueryValue("q", RuleInputSlot.QUERY, "extra"); fail("ambiguous binding") } catch (_: IllegalArgumentException) { }
    }
    @Test fun malformedOrOverBudgetRuntimeInputsAreRejected() {
        for (query in listOf("", " ", "a\n", "\ud800", "x".repeat(513)))
            rejected(DiscoveryError.INVALID_QUERY) { bind(query = query) }
        rejected(DiscoveryError.INVALID_QUERY) { bind(locale = "xx") }
        for (cursor in listOf("\ud800", "x\r", "x".repeat(2049), "中".repeat(700)))
            rejected(DiscoveryError.INVALID_RESULT) { bind(cursor = cursor) }
    }
    @Test fun staticCatalogRejectsNumericAndNonliteralOriginsAndPaths() {
        for (host in listOf("127.0.0.1", "[::1]", "0x7f000001", "0x7f.0.0.1", "0x7f.0x0.0x0.0x1", "0177.0.0.1", "2130706433", "a.invalid:8443", "a.invalid/other", "a.invalid@b.invalid", "A.invalid", "a.invalid.")) {
            try { endpoint(host = host); fail("expected host refusal") } catch (_: IllegalArgumentException) { }
        }
        for (path in listOf("search", "/../search", "/a/./b", "/%2f", "/search?q=x", "/x#y", "/x\\y")) {
            try { endpoint(path = path); fail("expected path refusal") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun cancellationIsCheckedWithoutIoAndDiagnosticsAreRedacted() {
        val endpoint = endpoint(); var checks = 0
        try {
            endpoint.bind(endpoint.endpointId, "/search", template(), "secret-search", "und", null) {
                if (++checks == 3) throw DiscoveryFailure(DiscoveryError.CANCELLED)
            }; fail("expected cancellation")
        } catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, failure.code) }
        assertFalse(endpoint.toString().contains("json-fixture"))
        assertFalse(bind(query = "secret-search").toString().contains("secret-search"))
    }
}
