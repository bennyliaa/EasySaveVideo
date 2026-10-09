package com.easysavevideo.app.discovery

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class RightsPresentationPolicyTest {
    private fun snapshot() = RightsSnapshot("wikimedia-commons", "Wikimedia Commons", "wikimedia-commons", "123",
        "https://commons.wikimedia.org/wiki/File:Test.webm", 456, 1000,
        author = "<script>author</script>", credit = "credit text", customAttribution = "custom text",
        licenseName = "CC BY 3.0", licenseUrl = "https://creativecommons.org/licenses/by/3.0/",
        usageTerms = "terms text", copyrighted = true, attributionRequired = true, nonFree = false,
        restrictions = listOf("trademark"), deletionReason = "review text", review = RightsReview.FLAGGED_REQUIRES_REVIEW,
        provenance = listOf(RightsFieldProvenance("Artist", "commons-desc-page", htmlConvertedToText = true, truncated = true)),
        metadataTermsUrl = "https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia")
    @Test fun summaryRetainsAuthorCustomAttributionAndUnknownStates() {
        val summary = RightsPresentationPolicy.summary(ResultRights(listOf(snapshot()))).joinToString("\n")
        assertTrue(summary.contains("<script>author</script>")); assertTrue(summary.contains("custom text"))
        assertTrue(summary.contains("CC BY 3.0")); assertTrue(summary.contains("需核对"))
        assertEquals(listOf("作者未知", "许可资料未知，需核对"), RightsPresentationPolicy.summary(ResultRights()))
    }
    @Test fun detailRetainsFullProvenanceAndRestrictionsAsPlainStrings() {
        val details = RightsPresentationPolicy.details(snapshot()).joinToString("\n")
        listOf("Wikimedia Commons", "123", "456", "credit text", "custom text", "terms text", "trademark", "review text",
            "Artist", "commons-desc-page", "1970-01-01T00:00:01Z", "非结构化", "已截断 是").forEach { assertTrue(it, details.contains(it)) }
    }
    @Test fun explicitLinksAreHttpsOnlyWithoutCredentialsOrCustomPorts() {
        listOf("javascript:alert(1)", "http://example.com/", "https://name:pass@example.com/", "https://example.com:443/", "data:text/html,hello").forEach {
            assertNull(RightsPresentationPolicy.safeLink(it))
        }
        assertEquals(snapshot().licenseUrl, RightsPresentationPolicy.safeLink(snapshot().licenseUrl))
        assertNull(RightsPresentationPolicy.safeLink(null))
    }
    @Test fun sourceDisabledRegistryIsEmptyAndConfiguredBuildMatchesRealRegistry() {
        assertTrue(BuildDiscovery.registry(false, false).all().isEmpty())
        assertEquals(BuildConfig.COMMONS_ENABLED && BuildConfig.COMMONS_CONTACT.isNotEmpty(), CommonsSourceRegistration.configured)
        assertEquals(if (CommonsSourceRegistration.configured && CommonsSourceRegistration.maintenanceAvailable) 1 else 0, BuildDiscovery.registry(false, true).all().size)
    }
    @Test fun queryTruncationDoesNotSplitSurrogatesAndMalformedInputIsRejected() {
        assertEquals(511, DiscoveryPresentationPolicy.boundedQuery("a".repeat(511) + "😃").length)
        assertTrue(DiscoveryPresentationPolicy.validQuery("猫😃"))
        assertFalse(DiscoveryPresentationPolicy.validQuery("bad\uD800input"))
        assertFalse(DiscoveryPresentationPolicy.validQuery("bad\uDC00"))
    }

}
