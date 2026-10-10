package com.easysavevideo.verification.local

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class CommonsStaticFixturePolicyTest {
    private val page = "https://commons.wikimedia.org/wiki/File:Owned_video.webm"
    private val policy = DevelopmentCommonsStaticFixturePolicy(offlineSelected = true)
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/commons-static-development/$name.html")).use { it.readBytes() }
    private fun outcome(name: String) = policy.evaluateOffline(page, 200, fixture(name)).outcome
    @Test fun disabledDefaultAndOfflineSelectionNeverAuthorizeLiveB() {
        val disabled = DevelopmentCommonsStaticFixturePolicy()
        assertEquals(SourceAdmissionStatus.UNCONFIGURED, disabled.offlineSelection().status)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.DISABLED, disabled.evaluateOffline(page,200,fixture("video")).outcome)
        assertEquals(SourceAdmissionStatus.READY, policy.offlineSelection().status)
        for (p in listOf(disabled,policy)) {
            val network=p.network(CandidateUrl(page))
            assertTrue(network.allowedOrigins.isEmpty()); assertTrue(network.requireTls)
            assertEquals(0,network.maxRedirects); assertFalse(network.cookiesAllowed); assertFalse(network.downloadsAllowed)
        }
        assertTrue(CompiledVerificationSources().availableIds.isEmpty())
    }
    @Test fun canonicalExactHttpsOriginAndFiniteEncodingSubset() {
        for (url in listOf(page, page.replace(".org/", ".org:443/"),
            "https://commons.wikimedia.org/wiki/File:%E6%B5%8B%E8%AF%95.webm")) assertTrue(url,policy.canonicalFilePage(url))
        for (url in listOf(
            page.replace("https:","http:"),page.replace("https:","HTTPS:"),
            page.replace("commons.wikimedia.org","COMMONS.WIKIMEDIA.ORG"),
            page.replace("commons.wikimedia.org","commons.wikimedia.org."),
            page.replace("commons.wikimedia.org","commons.wikimedia.org.evil.example"),
            page.replace("commons.wikimedia.org","cоmmons.wikimedia.org"), // Cyrillic o
            page.replace("commons.wikimedia.org","xn--cmmons-9jg.wikimedia.org"),
            page.replace("commons.wikimedia.org","upload.wikimedia.org"),
            page.replace("commons.wikimedia.org","thumb.wikimedia.org"),
            page.replace(".org/", ".org:444/"),page.replace(".org/", ".org:0443/"),
            page.replace(".org/", ".org:443@evil.example/"), page.replace("https://","https://user@"),
            "$page?", "$page?oldid=1", "$page#", "$page#file",page.replace("/wiki/","/w/"),
            page.replace("File:","file:"),page.replace("File:","File%3A"),
            page.replace("Owned", "%4Fwned"),page.replace("Owned", "%254Fwned"),
            page.replace("Owned", "%2Fwned"),page.replace("Owned", "%5Cwned"),
            page.replace("Owned", "%2ewned"),page.replace("Owned", "%GGwned"),
            "https://commons.wikimedia.org/wiki/File:","https://commons.wikimedia.org/wiki/File:..",
            "https://commons.wikimedia.org/wiki/File:../Owned.webm",
            "https://commons.wikimedia.org/wiki/../wiki/File:Owned.webm",
            "https://commons.wikimedia.org/wiki/File:Owned%20video.webm",
            "https://commons.wikimedia.org/wiki/File:Owned%00video.webm",
            "https://commons.wikimedia.org/wiki/File:%e6%b5%8b.webm",
            "https://commons.wikimedia.org/wiki/File:%C3%28.webm",
            "https://commons.wikimedia.org/wiki/File:e%CC%81.webm"
        )) assertFalse(url,policy.canonicalFilePage(url))
    }
    @Test fun staticVideoAndRevisedClassesOnlyYieldPageEvidence() {
        for (name in listOf("video","revised")) {
            val result = policy.evaluateOffline(page,200,fixture(name))
            assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.PAGE_EVIDENCE,result.outcome)
            assertEquals(EngineEvidence.HTML_VIDEO,result.evidence)
            assertFalse(result.playbackVerified);assertFalse(result.savingAuthorized);assertFalse(result.resourceExtractionVerified)
            assertFalse(result.toString().contains("wikimedia"));assertFalse(result.toString().contains("webm"))
        }
    }
    @Test fun audioWebmPosterScriptAndAbsentEvidenceNeverConfirm() {
        for (name in listOf("audio-webm","poster-only","no-video","script-only","ambiguous","hidden"))
            assertEquals(name,DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN,outcome(name))
    }
    @Test fun videoContainerFilenameWithoutVisualCodecEvidenceRemainsUnknown() {
        val body=fixture("video").toString(Charsets.UTF_8)
        for (changed in listOf(body.replace("vp9, opus","opus"),body.replace("video/webm","audio/webm"),
            body.replace("width=\"640\"","width=\"0\""),body.replace("height=\"360\"",""),
            body.replace("upload.wikimedia.org","thumb.wikimedia.org"),body.replace(".webm\"", ".webm?x=1\"")))
            assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN,policy.evaluateOffline(page,200,changed.toByteArray()).outcome)
    }
    @Test fun responseRefusalRedirectAndServiceStatusStopClassification() {
        val bytes=fixture("video")
        for (status in listOf(301,302,307,308,401,403)) assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.DENIED,policy.evaluateOffline(page,status,bytes).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.RATE_LIMITED,policy.evaluateOffline(page,429,bytes).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.SERVICE_STOP,policy.evaluateOffline(page,503,bytes).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN,policy.evaluateOffline(page,404,bytes).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.DENIED,policy.evaluateOffline(page,200,"captcha".toByteArray()).outcome)
    }
    @Test fun cancellationDeadlineDecodedSizeAndRequestBudgetDoNotConfirm() {
        val bytes=fixture("video")
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.CANCELLED,policy.evaluateOffline(page,200,bytes,cancelled=true).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.TIMEOUT,policy.evaluateOffline(page,200,bytes,elapsedMillis=20_000).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.OVER_BUDGET,policy.evaluateOffline(page,200,bytes,requestsUsed=13).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.OVER_BUDGET,policy.evaluateOffline(page,200,ByteArray(2*1024*1024+1)).outcome)
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN,policy.evaluateOffline(page,200,byteArrayOf(0xc3.toByte(),0x28)).outcome)
        val exact=bytes+ByteArray(2*1024*1024-bytes.size){32}
        assertEquals(DevelopmentCommonsStaticFixturePolicy.Outcome.PAGE_EVIDENCE,policy.evaluateOffline(page,200,exact,requestsUsed=12,elapsedMillis=19_999).outcome)
    }
}
