package com.easysavevideo.searxng

import com.easysavevideo.discovery.CandidateMetadataLevel
import org.junit.Assert.*
import org.junit.Test

/** All documents below are small original synthetic fixtures; no public query or upstream copy. */
class SearxngHtmlResponseParserTest {
    @Test fun supportedRowsYieldOnlySourceReportedPageCandidates() {
        val candidate = parse(page(row())).candidates.single()
        assertEquals("https://videos.example.org/watch/one", candidate.pageUrl)
        val metadata = candidate.sourceReported!!
        assertEquals("Synthetic film", metadata.title)
        assertEquals("A short original description.", metadata.description)
        assertEquals("videos.example.org", metadata.sourceName)
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, metadata.metadataLevel)
        assertNull(metadata.reportedDurationMs)
        assertNull(metadata.reportedPublishedAtEpochMillis)
        assertFalse(parse(page(row())).hasNext)
        assertFalse(candidate.toString().contains("Synthetic film"))
        assertFalse(parse(page(row())).toString().contains("example.org"))
    }

    @Test fun legacyDirectAndCurrentInnerRowsAndSupportedGroupsAreRecognized() {
        val direct = row(inner = false).replace("result-videos", "result-default")
        val grouped = "<div class='template_group_default'>$direct</div>" +
            "<div class='template_group_videos'>${row(url = "https://videos.example.org/two")}</div>"
        assertEquals(2, parse(page(grouped)).candidates.size)
    }

    @Test fun entitiesAreDecodedOnceAndMarkupBecomesOnlyPlainText() {
        val html = page(row(url = "https://videos.example.org/watch?a=1&amp;b=2",
            title = "A &amp; B <em>film</em> &#x1f3ac; &lt;literal&gt;",
            description = "Alpha&nbsp;<b>beta</b><br>gamma &amp;lt;delta&amp;gt;"))
        val candidate = parse(html).candidates.single()
        assertEquals("https://videos.example.org/watch?a=1&b=2", candidate.pageUrl)
        assertEquals("A & B film 🎬 <literal>", candidate.sourceReported!!.title)
        assertEquals("Alpha beta gamma &lt;delta&gt;", candidate.sourceReported!!.description)
    }

    @Test fun scriptsImagesIframesAndOtherLinksNeverBecomeCandidatesOrMetadata() {
        val extra = "<a class='thumbnail_link' href='https://tracking.example.org/click'><img src='https://images.example.org/a'></a>" +
            "<a class='url_header' href='https://tracking.example.org/another'>tracking</a>" +
            "<p class='altlink'><a href='https://video.example.org/embed'>play hint</a></p>" +
            "<iframe src='https://video.example.org/embed'></iframe>" +
            "<script>fetch('https://tracking.example.org/script')</script>"
        val body = row(description = "visible <span hidden>secret</span><script>secret</script>text")
            .replace("</article>", "$extra</article>")
        val candidate = parse(page(body, outside = "<nav><a href='https://tracking.example.org/nav'>nav</a></nav>")).candidates.single()
        assertEquals("https://videos.example.org/watch/one", candidate.pageUrl)
        assertEquals("visible text", candidate.sourceReported!!.description)
    }

    @Test fun duplicatesAreExactStableAndDoNotBorrowMetadata() {
        val rows = row(title = "first") + row(title = "second") + row(url = "https://videos.example.org/watch/two")
        val candidates = parse(page(rows)).candidates
        assertEquals(2, candidates.size)
        assertEquals("first", candidates.first().sourceReported!!.title)
        assertEquals("https://videos.example.org/watch/two", candidates.last().pageUrl)
    }

    @Test fun safeHttpAndHttpsRemainPageLinksWithoutVideoAuthority() {
        val candidates = parse(page(row(url = "http://pages.example.org/article") + row(url = "https://files.example.org/movie.mp4"))).candidates
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.sourceReported!!.metadataLevel == CandidateMetadataLevel.SOURCE_REPORTED })
    }

    @Test fun unsafeRelativePrivateAndAmbiguousUrlsAreDiscarded() {
        val urls = listOf("javascript:alert(1)", "data:video/mp4;base64,AAAA", "file:///tmp/x", "content://local/a",
            "//videos.example.org/a", "/watch/one", "https://u:p@videos.example.org/a", "https://127.0.0.1/a",
            "https://[::1]/a", "https://2130706433/a", "https://0x7f.0.0.1/a", "https://localhost/a",
            "https://localhost.example.org/a", "https://video.local/a", "https://video.internal/a", "https://video.onion/a",
            "https://video.example/a", "https://videos.example.org:443/a", "https://videos.example.org./a",
            "https://videos.example.org/a#b", "https://videos.example.org/a/../b", "https://videos.example.org/%2e%2e/b",
            "https://videos.example.org/%0d%0aheader", "https://videos.example.org/%5cprivate", "https://videos.example.org/${"x".repeat(4096)}")
        assertTrue(parse(page(urls.joinToString("") { row(url = it) })).candidates.isEmpty())
        assertTrue(parse(page(row(url = "https://videos.example.org/&#xA;bad"))).candidates.isEmpty())
    }

    @Test fun knownEmptyStateIsDifferentFromUnsupportedOrServiceError() {
        assertTrue(parse(page(empty())).candidates.isEmpty())
        assertFalse(parse(page(empty())).hasNext)
        assertTrue(parse(page(empty("There are no more results.")), currentPage = 2).candidates.isEmpty())
        for (rows in listOf("", "<p>No results were found.</p>", empty("Service unavailable."), empty("Unknown translated empty state.")))
            fails { parse(page(rows)) }
        fails { parse(page(row(), outside = "<div class='dialog-error' role='alert'>Service error</div>")) }
        fails { parse(page(empty(), next = next())) }
    }

    @Test fun resultCountIncludesDuplicatesAndInvalidUrlsBeforeDeduplication() {
        assertEquals(100, parse(page((1..100).joinToString("") { row(url = "https://videos.example.org/$it") })).candidates.size)
        fails(SearxngError.TOO_MANY_CANDIDATES) { parse(page(row().repeat(101))) }
        fails(SearxngError.TOO_MANY_CANDIDATES) { parse(page(row(url = "file:///x").repeat(2)), maxCandidates = 1) }
        assertEquals(1, parse(page(row()), maxCandidates = 1).candidates.size)
    }

    @Test fun malformedTruncatedDuplicateAndRepairedMarkupIsRejected() {
        val good = page(row())
        for (html in listOf("", "{\"results\":[]}", "<h3><a href='https://videos.example.org/a'>x</a></h3>",
            good.substringBeforeLast("</html>"), good.substringBeforeLast("</body>"),
            good.replace("</article>", ""), good.replace("</h3>", "</h2>"), good.replace("</a></h3>", "</h3></a>"),
            good.replace("href='https://videos.example.org/watch/one'", "href='https://videos.example.org/a' href='https://videos.example.org/b'"),
            good.replace("Synthetic film", "bad\u0000text"), good + "trailing data", good + "<!-- unfinished",
            good.replace("<p class='content'>", "<p class='content'"))) fails { parse(html) }
    }

    @Test fun ambiguousNestedAnchorsRowsAndHeadingsAreRejected() {
        val good = row()
        for (rows in listOf(good.replace("Synthetic film", "<a href='https://evil.example.org/a'>nested</a>"),
            good.replace("</h3>", "<a href='https://evil.example.org/a'>second</a></h3>"),
            good.replace("</h3>", "</h3><h3><a href='https://evil.example.org/a'>second</a></h3>"),
            good.replace("</article>", "${row()}</article>"), good.replace("</h3>", "outside</h3>"),
            "<nav>$good</nav>", good.replace("result-videos", "result-images"),
            good.replace("<p class='content'>", "<p class='content extra'>").replace("</p>", "</p><p class='content'>duplicate</p>")))
            fails { parse(page(rows)) }
    }

    @Test fun unsupportedTemplatesChallengesLoginsAndErrorDocumentsFailClosed() {
        val good = page(row())
        for (html in listOf(good.replace("searxng/synthetic", "other/synthetic"), good.replace("search_endpoint", "login_endpoint"),
            good.replace("main_results", "main_index"), good.replace("id='urls'", "id='different'"),
            good.replace("</main>", "<div id='urls'></div></main>"),
            good.replace("</head>", "<meta http-equiv='refresh' content='0;url=https://other.example.org'></head>"),
            good.replace("</head>", "<base href='https://evil.example.org/'></head>"))) fails { parse(html) }
        for (outside in listOf("<form id='login'><input type='password'></form>",
            "<div id='challenge-form'>Please verify</div>", "<div class='g-recaptcha'></div>",
            "<div id='anubis'></div>", "<div class='error-page'>Error</div>", "<template>${row()}</template>"))
            fails { parse(page(row(), outside = outside)) }
        fails { parse(page(row(), outside = row(url = "https://evil.example.org/nav"))) }
    }

    @Test fun nextPageIsOnlyABoundedSameEndpointHintAndReturnedStateIsIgnored() {
        assertTrue(parse(page(row(), next = next())).hasNext)
        assertTrue(parse(page(row(), next = next(page = "3", action = "https://search.example.org/search", fields =
            "<input type='hidden' name='q' value='attacker query'><input type='hidden' name='engine_data-secret' value='untrusted'>")), currentPage = 2).hasNext)
        assertFalse(parse(page(row(), next = "<a href='https://evil.example.org/?pageno=2'>next</a>")).hasNext)
        assertFalse(parse(page(row(), next = "<form class='previous_page'><input type='hidden' name='pageno' value='1'></form>"), currentPage = 2).hasNext)
    }

    @Test fun prefixedContinuationAcceptsOnlyExactSameEndpointActions() {
        val configured = htmlEndpoint("/tenant/searxng/search")
        for (action in listOf("", "search", "/tenant/searxng/search", configured.searchUrl))
            assertTrue(parse(page(row(), next = next(action = action)), endpoint = configured).hasNext)
        assertTrue(parse(page(row(), next = next().replace(" action='/search'", "")), endpoint = configured).hasNext)
        for (action in listOf("/search", "/other/search", "/tenant/searxng/search/", "../search", "./search",
            "/tenant/../searxng/search", "/tenant//searxng/search", "/tenant/%73earxng/search", "/tenant%2fsearxng/search",
            "https://evil.example.org/tenant/searxng/search", "//search.example.org/tenant/searxng/search",
            "http://search.example.org/tenant/searxng/search", "https://u:p@search.example.org/tenant/searxng/search",
            "https://search.example.org:443/tenant/searxng/search", "/tenant/searxng/search?q=rewritten",
            "?q=rewritten", "?", "#fragment", " /tenant/searxng/search", "/tenant/searxng/search&#xA;"))
            fails { parse(page(row(), next = next(action = action)), endpoint = configured) }
        fails { parse(page(row(), next = next(action = "https://evil.example.org/search"))) }
        fails(SearxngError.INVALID_REQUEST) { parse(page(row()), endpoint = endpoint()) }
    }

    @Test fun invalidCurrentPageLimitsAndPaginationFailClosedWithoutOverflow() {
        for (current in listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE))
            fails(SearxngError.INVALID_REQUEST) { parse(page(row()), currentPage = current) }
        for (maximum in listOf(0, -1, 101, Int.MAX_VALUE))
            fails(SearxngError.INVALID_REQUEST) { parse(page(row()), maxCandidates = maximum) }
        for (number in listOf("0", "1", "3", "02", "+2", "-2", "2.0", "2e0", " 2", "٢", "2147483647", "999999999999999999999"))
            fails { parse(page(row(), next = next(page = number))) }
        fails { parse(page(row(), next = next(page = "101")), currentPage = 100) }
        assertFalse(parse(page(row()), currentPage = 100).hasNext)
        for (invalidNext in listOf(next() + next(), next(fields = "<input type='hidden' name='pageno' value='2'>"),
            next().replace("type='hidden'", "type='text'"), next().replace("method='get'", "method='delete'"),
            next().replace("name='pageno'", "name='other'"), "<div>${next()}</div>"))
            fails { parse(page(row(), next = invalidNext)) }
    }

    @Test fun strictUtf8AndResponseSizeAreBounded() {
        for (invalid in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())))
            fails { SearxngHtmlResponseParser.parse(invalid, htmlEndpoint(), 1, 100) {} }
        fails(SearxngError.RESPONSE_TOO_LARGE) { SearxngHtmlResponseParser.parse(ByteArray(2 * 1024 * 1024 + 1), htmlEndpoint(), 1, 100) {} }
    }

    @Test fun textLimitsPreserveUnicodeAndRemoveControlsWithoutInventingMetadata() {
        val title = "a".repeat(511) + "🎬tail"
        val candidate = parse(page(row(title = title, description = "d".repeat(4096)))).candidates.single()
        assertEquals(511, candidate.sourceReported!!.title!!.length)
        assertEquals(2048, candidate.sourceReported!!.description!!.length)
        val controlled = parse(page(row(title = "A\tB&#x202e;C", description = "<span aria-hidden='true'>hidden</span>"))).candidates.single()
        assertEquals("A B C", controlled.sourceReported!!.title)
        assertNull(controlled.sourceReported!!.description)
        assertNull(parse(page(row().replace("class='content'", "class='content empty_element'"))).candidates.single().sourceReported!!.description)
    }

    @Test fun lexicalDepthTagAndDomNodeLimitsApplyBeforeExtraction() {
        fails { parse(page(row(), outside = "<div>".repeat(65) + "x" + "</div>".repeat(65))) }
        fails { parse(page(row(), outside = "<span></span>".repeat(20_001))) }
        fails { parse(page(row(), outside = "<i data-x='${"x".repeat(16_384)}'></i>")) }
        fails { parse(page(row(title = "x".repeat(65_537)))) }
        fails { parse(page(row(), outside = "<div ${List(65) { "data-x$it='x'" }.joinToString(" ")}></div>")) }
    }

    @Test fun cancellationAndDeadlinePropagateUnchangedAtEveryObservedCheckpoint() {
        val bytes = page(row(title = "a".repeat(1024)), next = next()).toByteArray()
        var checks = 0
        SearxngHtmlResponseParser.parse(bytes, htmlEndpoint(), 1, 100) { checks++ }
        assertTrue(checks > 30)
        for (stop in 1..checks) {
            var current = 0
            val expected = SearxngFailure(if (stop % 2 == 0) SearxngError.CANCELLED else SearxngError.DEADLINE_EXCEEDED)
            try {
                SearxngHtmlResponseParser.parse(bytes, htmlEndpoint(), 1, 100) { if (++current == stop) throw expected }
                fail("Expected cancellation at checkpoint $stop")
            } catch (actual: SearxngFailure) { assertSame(expected, actual) }
        }
    }

    private fun htmlEndpoint(path: String = "/search") =
        SearxngEndpoint("searxng-1", "https://search.example.org$path", SearxngResultFormat.HTML_WEB)

    private fun parse(html: String, currentPage: Int = 1, maxCandidates: Int = 100,
        endpoint: SearxngEndpoint = htmlEndpoint()) =
        SearxngHtmlResponseParser.parse(html.toByteArray(Charsets.UTF_8), endpoint, currentPage, maxCandidates) {}

    private fun page(rows: String, next: String = "", outside: String = "") =
        "<!doctype html><html><head><meta charset='UTF-8'><meta name='generator' content='searxng/synthetic'><title>Fixture</title></head>" +
            "<body class='search_endpoint'><main id='main_results'>$outside<div id='results'><div id='urls' role='main'>$rows</div>" +
            (if (next.isEmpty()) "" else "<nav id='pagination'>$next</nav>") + "</div></main></body></html>"

    private fun row(url: String = "https://videos.example.org/watch/one", title: String = "Synthetic film",
        description: String = "A short original description.", inner: Boolean = true): String =
        "<article class='result result-videos category-videos'>" + (if (inner) "<div class='result_inner'>" else "") +
            "<h3><a href='$url'>$title</a></h3><p class='content'>$description</p>" + (if (inner) "</div>" else "") + "</article>"

    private fun empty(message: String = "No results were found.") =
        "<div class='dialog-error-block' role='alert'><p>Empty fixture</p><p>$message</p><ul><li>Try a different phrase.</li></ul></div>"

    private fun next(page: String = "2", action: String = "/search", fields: String = "") =
        "<form method='get' action='$action' class='next_page'><input type='hidden' name='pageno' value='$page'>$fields<button type='submit'>Continue</button></form>"

    private fun fails(code: SearxngError = SearxngError.INVALID_RESPONSE, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: SearxngFailure) { assertEquals(code, failure.code) }
    }
}
