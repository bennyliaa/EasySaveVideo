package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class VerificationAccessBoundaryTest {
    private class Engine(override val id: String, override val sendsDataToAnotherProcess: Boolean,
        val outcome: EngineOutcome) : VerificationEngine {
        var calls = 0
        var received: EngineRequest? = null
        override val capabilities = setOf(EngineCapability.STATIC_PAGE)
        override fun isAvailable() = true
        override fun isPhysicallyIdle() = true
        override fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult {
            calls++; received = request
            return EngineResult(request.requestId,request.generation,id,outcome,requestsConsumed=1,bytesConsumed=200)
        }
    }
    private val candidate=CandidateUrl("https://owned.example.org/page")
    private fun request(configured: Boolean = false, consent: Boolean = true): EngineRequest {
        val catalog=CompiledVerificationSources(if(configured) mapOf("owned" to setOf("https://owned.example.org")) else emptyMap())
        val selection=catalog.configure(if(configured) setOf("owned") else emptySet(),true)!!
        return EngineRequest("request","generation",candidate,1000,3,1000,false,consent,selection.network(candidate))
    }
    private fun trusted(a: Engine,b: Engine) = VerificationEngineRegistry().apply {
        register(a,EngineAccessBoundary.REMOTE_SERVICE); register(b,EngineAccessBoundary.LOCAL_TARGET)
    }.all()
    @Test fun consentedRemoteServiceRunsWhenLocalCatalogueEmpty() {
        val a=Engine("service",true,EngineOutcome.DENIED);val b=Engine("local",false,EngineOutcome.UNKNOWN)
        assertEquals("service",VerificationEngineScheduler {0}.verify(request(),operation(),trusted(a,b)).engineId)
        assertEquals(1,a.calls);assertEquals(0,b.calls)
    }
    @Test fun serviceUnavailableDoesNotStartUnadmittedLocalEngine() {
        val a=Engine("service",true,EngineOutcome.UNAVAILABLE);val b=Engine("local",false,EngineOutcome.UNKNOWN)
        assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(request(),operation(),trusted(a,b)).outcome)
        assertEquals(1,a.calls);assertEquals(0,b.calls)
    }
    @Test fun serviceRefusalOr429NeverFallsBackEvenWhenLocalAdmitted() {
        for(outcome in listOf(EngineOutcome.DENIED,EngineOutcome.RATE_LIMITED)) {
            val a=Engine("service",true,outcome);val b=Engine("local",false,EngineOutcome.UNKNOWN)
            assertEquals(outcome,VerificationEngineScheduler {0}.verify(request(true),operation(),trusted(a,b)).outcome)
            assertEquals(1,a.calls);assertEquals(0,b.calls)
        }
    }
    @Test fun admittedLocalFallbackRetainsOriginAndCumulativeBudget() {
        val a=Engine("service",true,EngineOutcome.UNAVAILABLE);val b=Engine("future-c",false,EngineOutcome.UNKNOWN)
        val value=VerificationEngineScheduler {0}.verify(request(true),operation(),trusted(a,b))
        assertEquals("future-c",value.engineId);assertEquals(1,b.calls)
        assertEquals(setOf("https://owned.example.org:443"),b.received!!.network.allowedOrigins)
        assertEquals(2,b.received!!.maxRequests);assertEquals(800L,b.received!!.maxBytes)
        assertEquals(2,value.requestsConsumed);assertEquals(400L,value.bytesConsumed)
    }
    @Test fun selfClaimedRemoteOrUnknownRegistrationCannotGrantServiceAccess() {
        for(asRegistry in listOf(false,true)) {
            val claimed=Engine("untrusted-third",true,EngineOutcome.UNKNOWN)
            val engines=if(asRegistry) VerificationEngineRegistry().apply {register(claimed)}.all() else listOf(claimed)
            assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(request(),operation(),engines).outcome)
            assertEquals(0,claimed.calls)
        }
    }
    @Test fun explicitOwnedFixtureScopeCannotAuthorizePublicOrOtherLoopbackTargets() {
        val fixture=Engine("fixture",false,EngineOutcome.UNKNOWN)
        val registered=VerificationEngineRegistry().apply {register(fixture,EngineAccessBoundary.OWNED_LOOPBACK_FIXTURE)}.all()
        val local=request().copy(candidate=CandidateUrl("http://127.0.0.1:8765/page"),
            network=EngineNetworkConstraints(setOf("http://127.0.0.1:8765"),false))
        assertEquals(EngineOutcome.UNKNOWN,VerificationEngineScheduler {0}.verify(local,operation(),registered).outcome)
        for(url in listOf("http://127.0.0.2:8765/page","http://localhost:8765/page","https://owned.example.org/page")) {
            assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(local.copy(candidate=CandidateUrl(url)),operation(),registered).outcome)
        }
        assertEquals(1,fixture.calls)
    }
    @Test fun localBoundaryRequiresAdmissionEvenWhenLegacyFlagIsFalse() {
        val local=Engine("local",false,EngineOutcome.UNKNOWN)
        val registered=VerificationEngineRegistry().apply {register(local,EngineAccessBoundary.LOCAL_TARGET)}.all()
        assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(request().copy(network=EngineNetworkConstraints()),operation(),registered).outcome)
        assertEquals(0,local.calls)
    }
    @Test fun localBoundaryCannotSelfClaimRemoteAndNoConsentCannotCallService() {
        val claimed=Engine("third",true,EngineOutcome.UNKNOWN)
        assertThrows(IllegalArgumentException::class.java) { VerificationEngineRegistry().register(claimed,EngineAccessBoundary.LOCAL_TARGET) }
        val a=Engine("service",true,EngineOutcome.UNAVAILABLE);val b=Engine("local",false,EngineOutcome.UNKNOWN)
        VerificationEngineScheduler {0}.verify(request(consent=false),operation(),trusted(a,b))
        assertEquals(0,a.calls);assertEquals(0,b.calls)
    }
}
