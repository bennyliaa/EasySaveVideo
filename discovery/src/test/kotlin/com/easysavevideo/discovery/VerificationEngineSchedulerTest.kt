package com.easysavevideo.discovery

import com.easysavevideo.core.model.OperationContext
import org.junit.Assert.*
import org.junit.Test

class VerificationEngineSchedulerTest {
    private fun operation(): DiscoveryOperation {
        val cancellation=DiscoveryCancellation()
        return DiscoveryOperation(OperationContext("test",10000,cancellation),cancellation)
    }
    private fun request()=EngineRequest("request","generation",CandidateUrl("https://fixture.example.org/page"),1000,3,1000,false,false)
    private class Engine(override val id: String, override val sendsDataToAnotherProcess: Boolean=false,
        val action: (EngineRequest)->EngineResult) : VerificationEngine {
        override val capabilities=setOf(EngineCapability.STATIC_PAGE)
        var calls=0; var idle=true
        override fun isAvailable()=true
        override fun isPhysicallyIdle()=idle
        override fun verify(request: EngineRequest,operation: DiscoveryOperation):EngineResult {calls++;return action(request)}
    }
    private fun result(r:EngineRequest,id:String,outcome:EngineOutcome=EngineOutcome.UNKNOWN)=
        EngineResult(r.requestId,r.generation,id,outcome)
    @Test fun thirdEngineAndRemoteConsent() {
        val a=Engine("remote",true) {result(it,"remote")}
        val c=Engine("future-c") {result(it,"future-c")}
        val registry=VerificationEngineRegistry().apply {register(a);register(c)}
        val value=VerificationEngineScheduler {0}.verify(request(),operation(),registry.all())
        assertEquals("future-c",value.engineId);assertEquals(0,a.calls)
    }
    @Test fun sharedRemainingBudgetAndDeadline() {
        val a=Engine("a") {result(it,"a",EngineOutcome.UNAVAILABLE).copy(requestsConsumed=1,bytesConsumed=300)}
        val b=Engine("b") {
            assertEquals(2,it.maxRequests);assertEquals(700L,it.maxBytes);assertEquals(1000L,it.deadlineMonotonicMillis)
            result(it,"b").copy(requestsConsumed=1,bytesConsumed=200)
        }
        val value=VerificationEngineScheduler {0}.verify(request(),operation(),listOf(a,b))
        assertEquals(2,value.requestsConsumed);assertEquals(500L,value.bytesConsumed)
    }
    @Test fun denialStopsFallback() {
        val a=Engine("a") {result(it,"a",EngineOutcome.DENIED)}
        val b=Engine("b") {result(it,"b")}
        assertEquals(EngineOutcome.DENIED,VerificationEngineScheduler {0}.verify(request(),operation(),listOf(a,b)).outcome)
        assertEquals(0,b.calls)
    }
    @Test fun staleAndFalseConfirmationRejected() {
        for (corrupt in listOf<(EngineRequest)->EngineResult>(
            {result(it,"a").copy(generation="stale")},
            {result(it,"a",EngineOutcome.ERROR).copy(evidence=EngineEvidence.HTML_VIDEO,raw=RawVideoResult(it.candidate.pageUrl,true))})) {
            val a=Engine("a",action=corrupt)
            assertEquals(EngineOutcome.ERROR,VerificationEngineScheduler {0}.verify(request(),operation(),listOf(a)).outcome)
        }
    }
    @Test fun cancellationAndDeadlineNoPublication() {
        val op=operation().also {it.cancellation.cancel()}
        assertEquals(EngineOutcome.CANCELLED,VerificationEngineScheduler {0}.verify(request(),op,emptyList()).outcome)
        assertEquals(EngineOutcome.TIMEOUT,VerificationEngineScheduler {1000}.verify(request(),operation(),emptyList()).outcome)
    }
    @Test fun cleanupDrainBlocksFallbackAndFutureWorkUntilProvenIdle() {
        lateinit var a:Engine
        a=Engine("a") {a.idle=false;result(it,"a",EngineOutcome.UNAVAILABLE)}
        val b=Engine("b") {result(it,"b")}
        val scheduler=VerificationEngineScheduler {0}
        scheduler.verify(request(),operation(),listOf(a,b))
        assertEquals(0,b.calls)
        scheduler.verify(request(),operation(),listOf(b));assertEquals(0,b.calls)
        a.idle=true
        scheduler.verify(request(),operation(),listOf(b));assertEquals(1,b.calls)
    }
    @Test fun exceptionAndUnknownConsumptionDoNotStartFallback() {
        val b=Engine("b") {result(it,"b")}
        for (a in listOf(Engine("throws") {error("test")},Engine("unknown") {result(it,"unknown").copy(consumptionKnown=false)})) {
            assertEquals(EngineOutcome.ERROR,VerificationEngineScheduler {0}.verify(request(),operation(),listOf(a,b)).outcome)
        }
        assertEquals(0,b.calls)
    }
    @Test fun duplicateRegistrationRejected() {
        val registry=VerificationEngineRegistry();val a=Engine("a") {result(it,"a")}
        registry.register(a)
        assertThrows(IllegalArgumentException::class.java) {registry.register(a)}
    }
}
