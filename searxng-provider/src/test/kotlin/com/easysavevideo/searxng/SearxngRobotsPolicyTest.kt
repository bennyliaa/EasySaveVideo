package com.easysavevideo.searxng

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

/** Synthetic policies only; no service access or copied implementation. */
class SearxngRobotsPolicyTest {
    @Test fun explicitAllowEmptyDisallowAndNonmatchingRestrictionsPermitSearch() {
        allow("User-agent: *\nAllow: /search")
        allow("User-agent: *\nDisallow:")
        allow("User-agent: *\nDisallow: /private")
        deny("User-agent: *\nDisallow: /")
        deny("User-agent: *\nDisallow: /search")
    }

    @Test fun longestMatchingRuleAndEquivalentAllowTieWin() {
        allow("User-agent: *\nDisallow: /\nAllow: /search")
        deny("User-agent: *\nAllow: /\nDisallow: /search")
        allow("User-agent: *\nDisallow: /search\nAllow: /search")
        deny("User-agent: *\nAllow: /search\nDisallow: /search?q=blocked", "blocked")
        allow("User-agent: *\nAllow: /search\nDisallow: /search?q=blocked", "other")
    }

    @Test fun exactProductGroupsAreCaseInsensitiveCombinedAndPreferredToWildcard() {
        allow("User-agent: *\nDisallow: /\nUser-agent: eAsYsAvEvIdEo\nAllow: /search")
        deny("User-agent: *\nAllow: /\nUser-agent: EasySaveVideo\nDisallow: /search")
        deny("User-agent: EasySaveVideo\nDisallow: /search\nUser-agent: EasySaveVideo\nAllow: /private")
        allow("User-agent: EasySaveVideo\nDisallow: /search\nUser-agent: EasySaveVideo\nAllow: /search")
        deny("User-agent: EasySaveVideoExtra\nAllow: /\nUser-agent: *\nDisallow: /")
        deny("User-agent: EasySaveVideo/1.0\nAllow: /") // Unsupported non-RFC product token.
    }

    @Test fun consecutiveAgentsShareRulesAndBlankLinesDoNotCreateGroups() {
        deny("User-agent: OtherBot\n\nUser-agent: EasySaveVideo\nDisallow: /search")
        deny("User-agent: EasySaveVideo\nDisallow: /search\n\nAllow: /private")
        allow("User-agent: OtherBot\nDisallow: /search\nUser-agent: *\nAllow: /search")
    }

    @Test fun wildcardAndTerminalAnchorIncludeTheQuery() {
        deny("User-agent: *\nDisallow: /search?*q=blocked*", "blocked")
        allow("User-agent: *\nDisallow: /search$", "query")
        deny("User-agent: *\nDisallow: /search?q=query$", "query")
        allow("User-agent: *\nDisallow: /search?q=query$", "query-more")
        deny("User-agent: *\nDisallow: /s*ch?*blocked$", "blocked")
        allow("User-agent: *\nDisallow: /s*ch?*blocked$", "blocked-extra")
        deny("User-agent: *\nAllow: /search*\nDisallow: /search") // Conflicting equal specificity is conservative.
    }

    @Test fun queryWideDisallowOverridesRootAllowForSearch() {
        deny("User-agent: *\nAllow: /\nAllow: /about\nDisallow: /stats\nDisallow: /preferences\nDisallow: /*?*q=*", "lion")
    }

    @Test fun percentEscapesUnicodeAndLiteralWildcardSymbolsAreCanonicalized() {
        deny("User-agent: *\nDisallow: /%73earch?q=blocked", "blocked")
        deny("User-agent: *\nDisallow: /search?q=blocked", "%62locked")
        deny("User-agent: *\nDisallow: /search?q=影片", "%E5%BD%B1%E7%89%87")
        deny("User-agent: *\nDisallow: /search?q=%E5%BD%B1%E7%89%87", "%e5%bd%b1%e7%89%87")
        deny("User-agent: *\nDisallow: /search?q=%2A$", "*")
        deny("User-agent: *\nDisallow: /search?q=%24$", "$")
        allow("User-agent: *\nDisallow: /search?q=/private", "%2Fprivate")
        deny("User-agent: *\nDisallow: /search?q=%2Fprivate", "%2fprivate")
        for (pattern in listOf("/search%", "/search%2", "/search%GG", "/search\$tail")) deny("User-agent: *\nDisallow: $pattern")
    }

    @Test fun commentsCaseWhitespaceBomAndAllLineEndingsAreSupported() {
        allow("\ufeff# original fixture\r\n UsEr-AgEnT : *\r\nAlLoW:\t/search # comment\rSitemap: https://ignored.example.org/map\n")
        deny("User-agent: *\nDisallow: /search#fragment is a comment")
        allow("User-agent: *\nDisallow: /Search")
    }

    @Test fun sitemapNeverEndsAGroupOrCreatesPermission() {
        deny("User-agent: *\nSitemap: https://ignored.example.org/map\nDisallow: /search")
        deny("Sitemap: https://ignored.example.org/map")
        allow("User-agent: *\nAllow: /search\nSitemap: javascript:ignored")
    }

    @Test fun unknownPoliciesAndUnsupportedApplicableInstructionsDeny() {
        for (policy in listOf("", "# no policy", "<html>not robots</html>", "Allow: /search", "User-agent: *",
            "User-agent: OtherBot\nAllow: /", "User-agent: *\nAllow: /\nCrawl-delay: 1",
            "User-agent: *\nAllow: /\nRequest-rate: 1/60", "Host: other.example.org\nUser-agent: *\nAllow: /",
            "User-agent: *\nUnknown: value\nAllow: /", "User-agent: *\nDisallow: relative", "User-agent: *\nDisallow: /search query")) deny(policy)
        allow("User-agent: OtherBot\nCrawl-delay: 10\nUser-agent: *\nAllow: /search")
        allow("User-agent: *\nCrawl-delay: 10\nUser-agent: EasySaveVideo\nAllow: /search")
    }

    @Test fun malformedUtf8ControlsOversizeAndRuleLimitsDeny() {
        denied { SearxngRobotsPolicy.requireAllowed(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), htmlEndpoint(), request()) {} }
        denied { SearxngRobotsPolicy.requireAllowed(ByteArray(32_769), htmlEndpoint(), request()) {} }
        deny("User-agent: *\nAllow: /\u0000")
        deny("User-agent: *\nAllow: /${"a".repeat(2048)}")
        deny("User-agent: *\n" + "Allow: /\n".repeat(257))
        deny("User-agent: *\n" + "\n".repeat(1025))
        deny((1..65).joinToString("") { "User-agent: OtherBot\nDisallow: /\n" } + "User-agent: *\nAllow: /")
    }

    @Test fun invalidRequestTargetsCannotBorrowRobotsPermission() {
        for (uri in listOf("http://search.example.org/search?q=x", "https://search.example.org/private?q=x",
            "https://search.example.org/search", "https://search.example.org/search?q=x#fragment", "https://127.0.0.1/search?q=x",
            "https://search.example.org:443/search?q=x", "https://u:p@search.example.org/search?q=x", "https://search.example.org/search?q=%0A"))
            denied { SearxngRobotsPolicy.requireAllowed("User-agent: *\nAllow: /".toByteArray(), htmlEndpoint(), URI(uri)) {} }
    }

    @Test fun deploymentPrefixAndQueryAreCheckedAgainstTheOriginPolicyExactly() {
        val configured = htmlEndpoint("/tenant/searxng/search")
        val target = URI(configured.searchUrl + "?q=blocked&format=html")
        fun check(policy: String, request: URI = target, endpoint: SearxngEndpoint = configured) =
            SearxngRobotsPolicy.requireAllowed(policy.toByteArray(), endpoint, request) {}
        check("User-agent: *\nDisallow: /\nAllow: /tenant/searxng/search")
        check("User-agent: *\nDisallow: /search")
        denied { check("User-agent: *\nDisallow: /\nAllow: /search") }
        denied { check("User-agent: *\nAllow: /\nDisallow: /tenant/searxng/search") }
        denied { check("User-agent: *\nAllow: /\nDisallow: /tenant/searxng/search?*q=blocked*") }
        denied { check("User-agent: *\nAllow: /\nDisallow: /*?*q=*") }
        denied { check("User-agent: *\nAllow: /", request()) }
        denied { check("User-agent: *\nAllow: /", URI("https://search.example.org/other/search?q=blocked")) }
        denied { check("User-agent: *\nAllow: /", endpoint = endpoint(configured.searchUrl)) }
    }

    @Test fun adversarialWildcardMatchingHasAHardWorkBudget() {
        val policy = "User-agent: *\nDisallow: /search?q=*${"a".repeat(1500)}b"
        deny(policy, "a".repeat(3000))
    }

    @Test fun cancellationPropagatesAtEveryObservedCheckpoint() {
        val policy = "User-agent: *\nDisallow: /private\nAllow: /search".toByteArray()
        var checks = 0
        SearxngRobotsPolicy.requireAllowed(policy, htmlEndpoint(), request()) { checks++ }
        for (stop in 1..checks) {
            var current = 0
            val expected = SearxngFailure(SearxngError.CANCELLED)
            try {
                SearxngRobotsPolicy.requireAllowed(policy, htmlEndpoint(), request()) { if (++current == stop) throw expected }
                fail("Expected cancellation at checkpoint $stop")
            } catch (actual: SearxngFailure) { assertSame(expected, actual) }
        }
    }

    private fun htmlEndpoint(path: String = "/search") =
        SearxngEndpoint("searxng-1", "https://search.example.org$path", SearxngResultFormat.HTML_WEB)
    private fun request(query: String = "query") = URI("https://search.example.org/search?q=$query")
    private fun allow(policy: String, query: String = "query") = SearxngRobotsPolicy.requireAllowed(policy.toByteArray(Charsets.UTF_8), htmlEndpoint(), request(query)) {}
    private fun deny(policy: String, query: String = "query") = denied { allow(policy, query) }
    private fun denied(action: () -> Unit) {
        try { action(); fail("Expected denial") } catch (failure: SearxngFailure) { assertEquals(SearxngError.SERVICE_DENIED, failure.code) }
    }
}
