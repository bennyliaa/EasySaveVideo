package com.easysavevideo.commons.rules

import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.ProviderDescriptor
import org.junit.Assert.*
import org.junit.Test

class DeclarativeHtmlProjectionTest {
    private val endpoint = HttpRuleFixtures.endpoint(RuleResponseFormat.HTML, "html-fixture.invalid")
    private fun plan(response: String = HttpRuleFixtures.htmlResponse) =
        BoundedHttpRule.compile(HttpRuleFixtures.bytes(endpoint, response), endpoint)
    private fun page(html: String, plan: BoundedHttpRule = plan(), limits: DiscoveryLimits = DiscoveryLimits(maxCandidatesPerResponse = 5),
        shape: () -> Unit = {}) = HttpRuleFixtures.project(plan, endpoint, html.toByteArray(), limits, shape)
    private fun row(url: String = "/one", title: String = "One", extra: String = "", attributes: String = "") =
        """<article class="result" $attributes><a class="page" href="$url">Page</a><h2>$title</h2>$extra</article>"""
    private fun list(rows: String = row(), next: String = "") = """<main id="results">$rows$next</main>"""
    private fun fails(html: String, code: DiscoveryError = DiscoveryError.INVALID_RESULT, plan: BoundedHttpRule = plan()) =
        RuleFixtures.fails(code) { page(html, plan) }

    @Test fun relativeLinksMetadataAndCursorRemainOnlySourceReportedCandidates() {
        val html = list(row("/watch?v=one&amp;kind=video", "One &amp; Two", "<p>Short <span>description</span></p>",
            "data-duration-ms=1234 data-published-at-ms=0"), "<a id=next data-cursor='https://other.invalid/path?x=1&amp;y=2'>Next</a>")
        val result = page(html)
        assertTrue(result.results.isEmpty())
        assertEquals("https://html-fixture.invalid/watch?v=one&kind=video", result.candidates.single().pageUrl)
        val metadata = result.candidates.single().sourceReported!!
        assertEquals("One & Two", metadata.title)
        assertEquals("Short description", metadata.description)
        assertEquals("Fixture HTTP", metadata.sourceName)
        assertEquals(1234L, metadata.reportedDurationMs)
        assertEquals(0L, metadata.reportedPublishedAtEpochMillis)
        assertEquals("https://other.invalid/path?x=1&y=2", result.nextCursor)
        assertFalse(result.exhausted)
        assertNull(result.cursorExpiresAtEpochMillis)
        assertEquals(html.toByteArray().size.toLong(), result.responseBytes)
    }

    @Test fun hostileBaseScriptIframeAndSubresourceUrlsCannotChangeProjectionBase() {
        val html = """<base href="https://attacker.invalid/"><script src="https://attacker.invalid/script.js"></script><iframe src="https://attacker.invalid/frame"></iframe>""" +
            list(row("relative", "One<script>secret()</script><style>hidden</style>"))
        val result = page(html)
        assertEquals("https://html-fixture.invalid/relative", result.candidates.single().pageUrl)
        assertEquals("One", result.candidates.single().sourceReported!!.title)
    }

    @Test fun unsafeMissingOrBlankUrlsAreSkippedWithoutChangingMetadataAuthority() {
        for (url in listOf("", " ", "javascript:alert(1)", "file:///private", "data:text/html,a", "https://u:p@example.org/x",
            "//u@example.org/x", "https://example.org/x#fragment", "/x#", "https://example.org/\\x", "/x&#10;y")) {
            assertTrue(page(list(row(url))).candidates.isEmpty())
        }
        assertTrue(page(list("<article class=result><h2>No URL</h2></article>")).candidates.isEmpty())
        assertEquals("https://example.org/one", page(list(row("//EXAMPLE.org:443/one"))).candidates.single().pageUrl)
    }

    @Test fun exactlyOneContainerSeparatesEmptyPageFromLoginAndWrongShape() {
        var shape = 0
        val empty = page(list(""), shape = { shape++ })
        assertTrue(empty.results.isEmpty()); assertTrue(empty.candidates.isEmpty()); assertTrue(empty.exhausted)
        assertEquals(1, shape)
        for (html in listOf("", "<html><body>Login</body></html>", "<main id=other></main>", list("") + list(""))) {
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) { page(html, shape = { shape++ }) }
        }
        assertEquals(1, shape)
    }

    @Test fun repeatedFieldsAreAmbiguousAndMissingOptionalFieldsAreNull() {
        fails(list(row(extra = "<h2>Other</h2>")))
        fails(list(row(extra = "<a class=page href='/two'>Other</a>")))
        fails(list(row(extra = "<p>One</p><p>Two</p>")))
        val result = page(list("<article class=result><a class=page href='/one'>One</a></article>"))
        val metadata = result.candidates.single().sourceReported!!
        assertNull(metadata.title); assertNull(metadata.description); assertNull(metadata.reportedDurationMs)
        assertNull(metadata.reportedPublishedAtEpochMillis)
    }

    @Test fun nextCursorIsOnlyOneOpaqueDataAttributeWithStrictValueBounds() {
        for (next in listOf("<a id=next data-cursor=''></a>", "<a id=next data-cursor='  '></a>",
            "<a id=next data-cursor='a&#10;b'></a>", "<a id=next data-cursor='${"x".repeat(2049)}'></a>",
            "<a id=next data-cursor='${"中".repeat(683)}'></a>",
            "<a id=next data-cursor=one></a><a id=next data-cursor=two></a>")) fails(list(next = next))
        assertTrue(page(list(next = "<a id=next href='https://other.invalid/next'>Next</a>")).exhausted)
        val raw = "https://other.invalid/next?q=1&amp;x=2#section"
        assertEquals("https://other.invalid/next?q=1&x=2#section", page(list(next = "<a id=next data-cursor='$raw'></a>")).nextCursor)
    }

    @Test fun countIsEnforcedBeforeUnsafeSkippingOrDeduplication() {
        val duplicate = row() + row() + row()
        assertEquals(1, page(list(duplicate)).candidates.size)
        RuleFixtures.fails(DiscoveryError.TOO_MANY_CANDIDATES) {
            page(list(duplicate), limits = DiscoveryLimits(maxCandidatesPerResponse = 2))
        }
        fails(list(List(6) { row("javascript:bad()") }.joinToString("")), DiscoveryError.TOO_MANY_CANDIDATES)
    }

    @Test fun stableCanonicalDeduplicationKeepsFirstMetadataAndDocumentOrder() {
        val result = page(list(row("https://EXAMPLE.org:443/one", "First") + row("https://example.org/one", "Ignored") + row("/two", "Second")))
        assertEquals(listOf("https://example.org/one", "https://html-fixture.invalid/two"), result.candidates.map { it.pageUrl })
        assertEquals(listOf("First", "Second"), result.candidates.map { it.sourceReported!!.title })
    }

    @Test fun nestedTextDomainsAndSelectedDescendantsStaySuppressed() {
        val untrusted = listOf("script", "style", "template", "iframe", "object", "svg", "math", "noscript")
        for (tag in untrusted) {
            val result = page(list(row(title = "Before<$tag>private text</$tag>After")))
            assertFalse(result.candidates.single().sourceReported!!.title.orEmpty().contains("private"))
        }
        val edited = HttpRuleFixtures.changeResponse(endpoint) {
            val fields = (it.getValue("fields") as Json.Obj).fields.toMutableMap()
            fields["title"] = StrictJson.read("""{"path":[{"axis":"descendant","tag":"span","class":"hidden"}],"text":true}""".toByteArray())
            it["fields"] = Json.Obj(fields)
        }
        val plan = BoundedHttpRule.compile(edited, endpoint)
        for (tag in listOf("template", "svg", "math", "object")) {
            val result = page(list(row(extra = "<$tag><span class=hidden>private text</span></$tag>")), plan)
            assertNull(result.candidates.single().sourceReported!!.title)
        }
    }

    @Test fun textIsPlainSingleDecodedAndNormalizesControlAndBidiDisplayCharacters() {
        val result = page(list(row(title = "A&nbsp;&lt;b&gt;B&lt;/b&gt;\u202e\nC", extra = "<p> &amp;amp; </p>")))
        assertEquals("A <b>B</b> C", result.candidates.single().sourceReported!!.title)
        assertEquals("&amp;", result.candidates.single().sourceReported!!.description)
    }

    @Test fun textAndAttributeFieldLimitsRejectRatherThanTruncate() {
        assertEquals(512, page(list(row(title = "x".repeat(512)))).candidates.single().sourceReported!!.title!!.length)
        fails(list(row(title = "x".repeat(513))))
        assertEquals(2048, page(list(row(extra = "<p>${"x".repeat(2048)}</p>"))).candidates.single().sourceReported!!.description!!.length)
        fails(list(row(extra = "<p>${"x".repeat(2049)}</p>")))
        fails(list(row(attributes = "data-duration-ms='${"1".repeat(20)}'")))
        val response = HttpRuleFixtures.htmlResponse.replace("\"path\":[{\"axis\":\"child\",\"tag\":\"h2\"}],\"text\":true",
            "\"path\":[],\"attribute\":\"data-title\"")
        val attributePlan = plan(response)
        assertEquals("Attribute title", page(list(row(attributes = "data-title=' Attribute  title '")), attributePlan).candidates.single().sourceReported!!.title)
        fails(list(row(attributes = "data-title='${"x".repeat(513)}'")), plan = attributePlan)
    }

    @Test fun numericAttributesRequireAsciiDigitsRangeAndCorrectSign() {
        for (value in listOf("", "0", "-1", "+1", "1.0", "1e3", " 1", "١", "9223372036854775808")) {
            fails(list(row(attributes = "data-duration-ms='$value'")))
        }
        assertEquals(Long.MAX_VALUE, page(list(row(attributes = "data-duration-ms='9223372036854775807'")))
            .candidates.single().sourceReported!!.reportedDurationMs)
        assertEquals(0L, page(list(row(attributes = "data-published-at-ms='0'")))
            .candidates.single().sourceReported!!.reportedPublishedAtEpochMillis)
        fails(list(row(attributes = "data-published-at-ms='-1'")))
    }

    @Test fun treeDepthNodeCountAttributesAndAttributeValuesHaveIndependentBounds() {
        fails(list("<div>".repeat(65) + row() + "</div>".repeat(65)))
        fails(list("<span></span>".repeat(4096)))
        fails(list(row(attributes = (1..17).joinToString(" ") { "data-$it=x" })))
        fails(list(row(attributes = "data-extra='${"x".repeat(4097)}'")))
        assertEquals(1, page(list(row(attributes = "data-extra='${"x".repeat(4096)}'"))).candidates.size)
    }

    @Test fun descendantUnionIsDeduplicatedAndSortedInDocumentOrder() {
        val response = HttpRuleFixtures.htmlResponse.replace("[{\"axis\":\"child\",\"tag\":\"article\",\"class\":\"result\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"section\"},{\"axis\":\"descendant\",\"tag\":\"article\"}]")
        val html = list("<section><section>${row("/one")}</section>${row("/two")}</section><section>${row("/three")}</section>")
        assertEquals(listOf("/one", "/two", "/three"), page(html, plan(response)).candidates.map { java.net.URI(it.pageUrl).path })
    }

    @Test fun exactAttributePredicateAndClassTokensHaveNoSubstringOrRegexSemantics() {
        val response = HttpRuleFixtures.htmlResponse.replace("\"class\":\"result\"", "\"class\":\"result\",\"attribute\":{\"name\":\"data-title\",\"value\":\"literal.*\"}")
        val result = page(list(row("/one", attributes = "data-title='literal.*'") +
            row("/two", attributes = "data-title='literalX'") + row("/three", attributes = "data-title='literal.*'").replace("class=\"result\"", "class=\"result-extra\"")), plan(response))
        assertEquals(1, result.candidates.size)
        assertEquals("https://html-fixture.invalid/one", result.candidates.single().pageUrl)
    }

    @Test fun projectionWorkBudgetIsSharedAcrossOverlappingDescendantSteps() {
        val response = HttpRuleFixtures.htmlResponse.replace("[{\"axis\":\"child\",\"tag\":\"article\",\"class\":\"result\"}]",
            "[{\"axis\":\"descendant\",\"tag\":\"div\"},{\"axis\":\"descendant\",\"tag\":\"div\"},{\"axis\":\"descendant\",\"tag\":\"span\"}]")
        val html = list("<div>".repeat(50) + "<span>x</span>".repeat(1500) + "</div>".repeat(50))
        fails(html, plan = plan(response))
    }

    @Test fun strictUtf8AndAllThreeBodyCapsAreAppliedBeforeParsing() {
        val rule = plan()
        RuleFixtures.fails(DiscoveryError.INVALID_RESULT) { HttpRuleFixtures.project(rule, endpoint, byteArrayOf(0xc3.toByte(), 0x28)) }
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) { HttpRuleFixtures.project(rule, endpoint, ByteArray(65_537)) }
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) { page(list(), limits = DiscoveryLimits(maxResponseBytes = 1)) }
        val executor = executor(rule)
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) { executor.execute(list().toByteArray(), RuleFixtures.operation(maxBytes = 1)) }
        val empty = list("").toByteArray()
        assertTrue(executor.execute(empty + ByteArray(65_536 - empty.size) { 32 }, RuleFixtures.operation()).exhausted)
    }

    @Test fun cancellationAndDeadlineChecksCoverBeforeParseAndProjectionWork() {
        val rule = plan()
        val cancellation = DiscoveryCancellation().apply { cancel() }
        RuleFixtures.fails(DiscoveryError.CANCELLED) { executor(rule).execute(list().toByteArray(), RuleFixtures.operation(cancellation = cancellation)) }
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) { executor(rule).execute(list().toByteArray(), RuleFixtures.operation(deadline = 1000)) }
        var checks = 0
        val cancelDuring = executor(rule, EpochClock {
            if (++checks == 35) throw DiscoveryFailure(DiscoveryError.CANCELLED)
            1000
        })
        RuleFixtures.fails(DiscoveryError.CANCELLED) { cancelDuring.execute(list().toByteArray(), RuleFixtures.operation()) }
        assertEquals(35, checks)
        try {
            Thread.currentThread().interrupt()
            RuleFixtures.fails(DiscoveryError.CANCELLED) { executor(rule).execute(list().toByteArray(), RuleFixtures.operation()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun shapeObservationOccursOnlyAfterContainerAndBoundedItemsAndBeforeFieldFailure() {
        var shape = 0
        fails("<p>Wrong shape</p>")
        RuleFixtures.fails(DiscoveryError.TOO_MANY_CANDIDATES) { page(list(List(6) { row() }.joinToString("")), shape = { shape++ }) }
        assertEquals(0, shape)
        RuleFixtures.fails(DiscoveryError.INVALID_RESULT) { page(list(row(extra = "<h2>Other</h2>")), shape = { shape++ }) }
        assertEquals(1, shape)
    }

    private fun executor(rule: BoundedHttpRule, clock: EpochClock = EpochClock { 1000 }) =
        DeclarativeHtmlProjectionExecutor((rule.response as BoundedHttpRule.Response.Html).projection,
            ProviderDescriptor("fixture-http", "1"), "Fixture HTTP", DiscoveryLimits(maxCandidatesPerResponse = 5), clock,
            "https://html-fixture.invalid/search?q=fixture")
}
