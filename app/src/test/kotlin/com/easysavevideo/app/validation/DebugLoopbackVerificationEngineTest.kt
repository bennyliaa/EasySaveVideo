package com.easysavevideo.app.validation
import com.easysavevideo.discovery.*
import com.easysavevideo.core.model.OperationContext
import org.junit.Assert.*
import org.junit.Test
class DebugLoopbackVerificationEngineTest {
    private val page="https://fixture.example.org/static-00.html"
    private fun op():DiscoveryOperation {
        val cancel=DiscoveryCancellation()
        return DiscoveryOperation(OperationContext("owned-test",System.currentTimeMillis()+10000,cancel),cancel)
    }
    private fun request()=EngineRequest("a".repeat(32),"b".repeat(64),CandidateUrl(page),System.nanoTime()/1000000+1000,1,32768,true,true)
    @Test fun processConsentDefaultsOffAndRevokes() {
        val a=DebugLoopbackVerificationEngine(setOf(page))
        assertFalse(a.isAvailable());assertEquals(EngineOutcome.UNAVAILABLE,a.verify(request(),op()).outcome)
        a.setProcessConsent(true);assertTrue(a.isAvailable());a.setProcessConsent(false);assertFalse(a.isAvailable())
        assertTrue(a.isPhysicallyIdle())
    }
    @Test fun sourceScopeCannotExpandToPublicOrLocalPrivatePages() {
        for(url in listOf("https://example.org/page","http://127.0.0.1:8765/private","https://fixture.example.org/private.html")) {
            try {DebugLoopbackVerificationEngine(setOf(url));fail("SOURCE_SCOPE_EXPANDED")} catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun expiryAndBudgetStopBeforeAnyTransport() {
        val a=DebugLoopbackVerificationEngine(setOf(page));a.setProcessConsent(true)
        assertEquals(EngineOutcome.TIMEOUT,a.verify(request().copy(deadlineMonotonicMillis=0),op()).outcome)
        assertEquals(EngineOutcome.UNAVAILABLE,a.verify(request().copy(maxRequests=0),op()).outcome)
        assertEquals(EngineOutcome.UNAVAILABLE,a.verify(request().copy(remoteConsent=false),op()).outcome)
        val cancelled=op().apply {cancellation.cancel()}
        assertEquals(EngineOutcome.CANCELLED,a.verify(request(),cancelled).outcome)
        assertTrue(a.isPhysicallyIdle());a.setProcessConsent(false)
    }
}
