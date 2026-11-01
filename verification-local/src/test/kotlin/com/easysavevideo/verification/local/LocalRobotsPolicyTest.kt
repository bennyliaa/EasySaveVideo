package com.easysavevideo.verification.local

import org.junit.Assert.*
import org.junit.Test

class LocalRobotsPolicyTest {
    private fun decide(text: String, path: String = "/watch") = LocalRobotsPolicy.evaluate(text.toByteArray(), path)
    @Test fun explicitAllowEmptyDisallowAndLongestRule() {
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide("User-agent: *\nDisallow:\n"))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide("User-agent: *\nDisallow: /\n"))
        val policy = "User-agent: *\nDisallow: /\nAllow: /watch\nDisallow: /watch/private\n"
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide(policy))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide(policy, "/watch/private/one"))
    }
    @Test fun mostSpecificAgentAndMergedGroupsWinOverWildcard() {
        val policy = "User-agent: *\nDisallow: /\nUser-agent: ESV-local-validation\nDisallow: /watch\n" +
            "User-agent: ESV-local-validation/1\nAllow: /watch\nUser-agent: esv-local-validation/1\nDisallow: /watch/private\n"
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide(policy))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide(policy, "/watch/private"))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: another-bot\nAllow: /\n"))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN,
            decide("User-agent: esv\nDisallow: /\nUser-agent: *\nAllow: /\n"))
    }
    @Test fun wildcardFinalAnchorQueryAndAllowTieAreBounded() {
        val policy = "User-agent: *\nDisallow: /*?secret=*\nDisallow: /*.mp4$\nAllow: /public/*.mp4$\n"
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide(policy, "/watch?secret=yes"))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide(policy, "/watch.mp4"))
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide(policy, "/watch.mp4?x=1"))
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide(policy, "/public/one.mp4"))
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide("User-agent: *\nAllow: /watch\nDisallow: /watch\n"))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\nAllow: /watch*\nDisallow: /*watch\n", "/watchwatch"))
    }
    @Test fun escapedUnreservedAndReservedOctetsHaveDifferentSemantics() {
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide("User-agent: *\nDisallow: /%77atch\n"))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide("User-agent: *\nDisallow: /a%2fb\n", "/a%2Fb"))
        assertEquals(LocalRobotsPolicy.Decision.ALLOW, decide("User-agent: *\nDisallow: /a%2Fb\n", "/a/b"))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide("User-agent: *\nDisallow: /watch?tag=%2A$\n", "/watch?tag=*"))
        assertEquals(LocalRobotsPolicy.Decision.DENY, decide("User-agent: *\nDisallow: /watch?tag=%24$\n", "/watch?tag=$"))
    }
    @Test fun unsupportedAmbiguousUnreadableAndMissingGroupsNeverGrant() {
        for (text in listOf("", "# no policy", "<html>allow</html>", "Allow: /", "User-agent: *", "User-agent: *\nAllow: /\nCrawl-delay: 10",
            "User-agent: *\nDisallow: watch",
            "User-agent: *\nAllow: /bad%GG", "User-agent: *\nDisallow: /a\$b", "User-agent: *\nAllow: /\u0000",
            "User-agent: *other\nAllow: /", "User-agent: *\nAllow: /\nUser-agent: other")) {
            assertEquals(text, LocalRobotsPolicy.Decision.UNKNOWN, decide(text))
        }
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, LocalRobotsPolicy.evaluate(byteArrayOf(0xc3.toByte(), 0x28), "/watch"))
    }
    @Test fun sitemapMetadataNeverGrantsAccessOrOverridesRestrictions() {
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("Sitemap: https://ignored.example.org/map.xml"))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\nSitemap: https://ignored.example.org/map.xml"))
        assertEquals(LocalRobotsPolicy.Decision.ALLOW,
            decide("User-agent: *\nAllow: /\nSitemap: https://ignored.example.org/map.xml"))
        assertEquals(LocalRobotsPolicy.Decision.DENY,
            decide("User-agent: *\nSitemap: https://ignored.example.org/map.xml\nDisallow: /watch"))
    }

    @Test fun finiteBodyLineRuleAndPathBounds() {
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\n" + "Allow: /\n".repeat(257)))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\nAllow: /\n" + "#x\n".repeat(513)))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\nAllow: /" + "x".repeat(2048)))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, LocalRobotsPolicy.evaluate(ByteArray(32769) { 32 }, "/"))
        assertEquals(LocalRobotsPolicy.Decision.UNKNOWN, decide("User-agent: *\nAllow: /", "/" + "x".repeat(4096)))
    }
}
