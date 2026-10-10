package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class VerificationSourceAdmissionTest {
    private fun selected() = CompiledVerificationSources(mapOf("owned" to setOf("https://owned.example.org")))
        .configure(setOf("owned"), true)!!
    @Test fun defaultHasNoAdmissionAndNoArbitraryStringCanConfigureIt() {
        val catalogue = CompiledVerificationSources()
        assertEquals(SourceAdmissionStatus.UNCONFIGURED, catalogue.configure(emptySet(), false)!!.status)
        assertNull(catalogue.configure(setOf("https://owned.example.org"), true))
        assertNull(catalogue.configure(setOf("unknown-id"), true))
    }
    @Test fun selectionNeedsAcknowledgementAndDefensiveCopies() {
        val origins = mutableSetOf("https://owned.example.org")
        val catalogue = CompiledVerificationSources(mapOf("owned" to origins))
        origins += "https://evil.example.org"
        assertNull(catalogue.configure(setOf("owned"), false))
        val ids = mutableSetOf("owned")
        val value = catalogue.configure(ids, true)!!; ids.clear()
        assertEquals(setOf("owned"), value.ids)
        assertEquals(SourceAdmissionStatus.DENIED, value.status(CandidateUrl("https://evil.example.org/page")))
    }
    @Test fun exactHostPortNoSubdomainOrSuffixGrant() {
        val selection = selected()
        assertEquals(SourceAdmissionStatus.READY, selection.status(CandidateUrl("https://OWNED.example.org:443/page?q=1")))
        for (url in listOf("https://sub.owned.example.org/page", "https://owned.example.org.evil.org/page", "https://owned.example.org:8443/page"))
            assertEquals(SourceAdmissionStatus.DENIED, selection.status(CandidateUrl(url)))
        assertEquals("https://owned.example.org:443", selection.network(CandidateUrl("https://owned.example.org/page")).allowedOrigins.single())
    }
    @Test fun schemeUserinfoPortAndMalformedHostAreUnsupported() {
        for (url in listOf("http://owned.example.org", "file:///page", "https://user:pass@owned.example.org",
            "https://owned.example.org:0", "https://owned.example.org:65536", "https://owned.example.org:bad",
            "https://owned.example.org.", "https://own%65d.example.org", "https://owned.example.org/#fragment",
            "https://127.0.0.1", "https://127.1", "https://2130706433", "https://[::1]", "https://a.localhost", "https://a.local"))
            assertNull(url, VerificationOriginPolicy.origin(url))
    }
    @Test fun idnAndAsciiFormsNormalizeToSameExactOrigin() {
        assertEquals("https://xn--bcher-kva.example:443", VerificationOriginPolicy.origin("https://bücher.example/page"))
        val value = CompiledVerificationSources(mapOf("owned" to setOf("https://bücher.example"))).configure(setOf("owned"), true)!!
        assertEquals(SourceAdmissionStatus.READY, value.status(CandidateUrl("https://xn--bcher-kva.example/page")))
        assertEquals(SourceAdmissionStatus.DENIED, value.status(CandidateUrl("https://shop.bücher.example/page")))
    }
    @Test fun privateLoopbackAndNonGlobalAddressBytesFailClosedWithoutDns() {
        fun v4(vararg parts: Int) = parts.map(Int::toByte).toByteArray()
        for (address in listOf(v4(127,0,0,1),v4(10,0,0,1),v4(172,16,0,1),v4(192,168,1,1),v4(169,254,1,1),
            v4(100,64,0,1),v4(0,0,0,0),v4(224,1,1,1),v4(198,18,0,1),ByteArray(16),ByteArray(16).also { it[15]=1 },
            ByteArray(16).also { it[0]=0xfc.toByte() },ByteArray(16).also { it[0]=0xfe.toByte();it[1]=0x80.toByte() }))
            assertFalse(VerificationOriginPolicy.publicAddress(address))
        assertTrue(VerificationOriginPolicy.publicAddress(v4(8,8,8,8)))
        assertTrue(VerificationOriginPolicy.publicAddress(ByteArray(16).also { it[0]=0x26;it[1]=6 }))
    }
    @Test fun redirectTargetMustBeReadmittedAndNoRedirectIsImplicitlyAllowed() {
        val value = selected()
        assertEquals(0,value.network(CandidateUrl("https://owned.example.org/page")).maxRedirects)
        assertTrue(value.network(CandidateUrl("https://other.example.org/redirected")).allowedOrigins.isEmpty())
        assertTrue(value.network(CandidateUrl("http://owned.example.org/redirected")).allowedOrigins.isEmpty())
    }
    @Test fun unadmittedSourceCannotFallThroughToAnyEngine() {
        var calls=0
        val permissive=object : VerificationEngine {
            override val id="third-engine"
            override val capabilities=setOf(EngineCapability.STATIC_PAGE)
            override val sendsDataToAnotherProcess=false
            override fun isAvailable()=true
            override fun isPhysicallyIdle()=true
            override fun verify(request: EngineRequest,operation:DiscoveryOperation):EngineResult {
                calls++;return EngineResult(request.requestId,request.generation,id,EngineOutcome.UNKNOWN)
            }
        }
        val candidate=CandidateUrl("https://unapproved.example.org/page")
        val request=EngineRequest("request","generation",candidate,1000,3,1000,false,true,selected().network(candidate))
        assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(request,operation(),listOf(permissive)).outcome)
        assertEquals(0,calls)
    }
    @Test fun fallbackKeepsExactAdmissionAndRemainingBudget() {
        val c = CandidateUrl("https://owned.example.org/page")
        val request = EngineRequest("request","generation",c,1000,3,1000,false,true,selected().network(c))
        fun engine(id: String, action: (EngineRequest) -> EngineResult) = object : VerificationEngine {
            override val id = id
            override val capabilities = setOf(EngineCapability.STATIC_PAGE)
            override val sendsDataToAnotherProcess = false
            override fun isAvailable() = true
            override fun isPhysicallyIdle() = true
            override fun verify(request: EngineRequest, operation: DiscoveryOperation) = action(request)
        }
        val a = engine("first") { EngineResult(it.requestId,it.generation,"first",EngineOutcome.UNAVAILABLE,requestsConsumed=1,bytesConsumed=200) }
        val b = engine("third") {
            assertTrue(it.network.sourceAdmissionRequired)
            assertEquals(setOf("https://owned.example.org:443"),it.network.allowedOrigins)
            assertEquals(2,it.maxRequests);assertEquals(800L,it.maxBytes)
            EngineResult(it.requestId,it.generation,"third",EngineOutcome.UNKNOWN)
        }
        assertEquals("third",VerificationEngineScheduler {0}.verify(request,operation(),listOf(a,b)).engineId)
    }
    @Test fun compiledCatalogueRejectsOriginPathsQueriesAndPlainHttp() {
        for (origin in listOf("http://owned.example.org", "https://owned.example.org/path", "https://owned.example.org/?q=1",
            "https://127.0.0.1", "https://[fc00::1]")) {
            assertThrows(IllegalArgumentException::class.java) { CompiledVerificationSources(mapOf("owned" to setOf(origin))) }
        }
    }
    @Test fun configuredAdmissionStillRespectsCancellationAndDeadline() {
        val c=CandidateUrl("https://owned.example.org/page")
        val request=EngineRequest("request","generation",c,1000,3,1000,false,false,selected().network(c))
        val op=operation();op.cancellation.cancel()
        assertEquals(EngineOutcome.CANCELLED,VerificationEngineScheduler {0}.verify(request,op,emptyList()).outcome)
        assertEquals(EngineOutcome.TIMEOUT,VerificationEngineScheduler {1000}.verify(request,operation(),emptyList()).outcome)
    }
}
