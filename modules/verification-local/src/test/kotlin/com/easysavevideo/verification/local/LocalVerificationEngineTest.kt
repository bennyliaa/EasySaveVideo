package com.easysavevideo.verification.local

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LocalVerificationEngineTest {
    private val html="<main><h1>Owned</h1><video data-esv-primary='true' src='/owned.mp4'></video></main>"
    private fun operation():DiscoveryOperation {
        val c=DiscoveryCancellation();return DiscoveryOperation(OperationContext("owned",System.currentTimeMillis()+5000,c),c)
    }
    private fun request(url:String,cap:Long=262144,budget:Long=1500,allowed:Boolean=true,dynamic:Boolean=false)=EngineRequest(
        "0123456789abcdef0123456789abcdef","generation",CandidateUrl(url),System.nanoTime()/1_000_000+budget,1,cap,dynamic,false,
        EngineNetworkConstraints(if(allowed)setOf(url.substringBeforeLast('/')) else emptySet(),false))
    private fun fixture(code:Int=200,body:String=html,action:(String,AtomicInteger)->Unit) {
        val calls=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/page") {x ->calls.incrementAndGet();x.responseHeaders.add("Content-Type","text/html; charset=utf-8")
            if(code==302)x.responseHeaders.add("Location","http://127.0.0.1:1/forbidden")
            val bytes=body.toByteArray();x.sendResponseHeaders(code,bytes.size.toLong());x.responseBody.use {it.write(bytes)}}
        server.start();try {action("http://127.0.0.1:${server.address.port}/page",calls)} finally {server.stop(0)}
    }
    @Test fun actualOkHttpJsoupPositiveAndBudgetReservation()=fixture {url,calls ->
        val e=LocalVerificationEngine(fixtureLoopbackOnly=true);val result=e.verify(request(url),operation())
        assertEquals(EngineOutcome.PAGE_EVIDENCE,result.outcome);assertEquals(EngineEvidence.HTML_VIDEO,result.evidence)
        assertEquals(url,result.raw?.pageUrl);assertEquals(1,calls.get());assertEquals(262144L,result.bytesConsumed);assertTrue(e.isPhysicallyIdle())
    }
    @Test fun zeroAdmissionDoesNoIo()=fixture {url,calls ->
        assertEquals(EngineOutcome.DENIED,LocalVerificationEngine(fixtureLoopbackOnly=true).verify(request(url,allowed=false),operation()).outcome)
        assertEquals(0,calls.get())
    }
    @Test fun publicEngineCannotFetchLoopback()=fixture {url,calls ->
        assertNotEquals(EngineOutcome.PAGE_EVIDENCE,LocalVerificationEngine().verify(request(url),operation()).outcome);assertEquals(0,calls.get())
    }
    @Test fun rateLimitDenialAndRedirectNeverConfirm() {
        for(code in listOf(429,403,302))fixture(code) {url,calls ->
            val result=LocalVerificationEngine(fixtureLoopbackOnly=true).verify(request(url),operation())
            assertEquals(if(code==429)EngineOutcome.RATE_LIMITED else EngineOutcome.DENIED,result.outcome)
            assertNull(result.raw);assertEquals(1,calls.get())
        }
    }
    @Test fun refusalStopsSameSourceWithoutAnotherRequest()=fixture(429) {url,calls ->
        val e=LocalVerificationEngine(fixtureLoopbackOnly=true)
        assertEquals(EngineOutcome.RATE_LIMITED,e.verify(request(url),operation()).outcome)
        assertEquals(EngineOutcome.DENIED,e.verify(request(url),operation()).outcome)
        assertEquals(1,calls.get());e.close()
    }
    @Test fun closedEngineCannotRestart()=fixture {url,calls ->
        val e=LocalVerificationEngine(fixtureLoopbackOnly=true);e.close()
        assertFalse(e.isAvailable());assertEquals(EngineOutcome.UNAVAILABLE,e.verify(request(url),operation()).outcome);assertEquals(0,calls.get())
    }
    @Test fun captchaStopsSourceAndNeverPromotesVideo()=fixture(body=html+"<p>Verify you are human</p>") {url,calls ->
        val e=LocalVerificationEngine(fixtureLoopbackOnly=true)
        assertEquals(EngineOutcome.DENIED,e.verify(request(url),operation()).outcome)
        assertEquals(EngineOutcome.DENIED,e.verify(request(url),operation()).outcome)
        assertEquals(1,calls.get());e.close()
    }
    @Test fun bodyLimitNeverConfirms()=fixture(body=html+"x".repeat(4096)) {url,_ ->
        assertEquals(EngineOutcome.UNKNOWN,LocalVerificationEngine(fixtureLoopbackOnly=true).verify(request(url,cap=64),operation()).outcome)
    }
    @Test fun cancelAndExpiredDoNoIo()=fixture {url,calls ->
        val op=operation();op.cancellation.cancel()
        val e=LocalVerificationEngine(fixtureLoopbackOnly=true)
        assertEquals(EngineOutcome.CANCELLED,e.verify(request(url),op).outcome)
        assertEquals(EngineOutcome.TIMEOUT,e.verify(request(url,budget=-1),operation()).outcome);assertEquals(0,calls.get())
    }
    @Test fun unknownCannotBecomeVideo()=fixture(body="<main><p>No evidence</p></main>") {url,_ ->
        val result=LocalVerificationEngine(fixtureLoopbackOnly=true).verify(request(url),operation())
        assertEquals(EngineVerdict.UNKNOWN,result.verdict);assertNull(result.raw)
    }
    @Test fun dynamicInjectionRequiresPermission()=fixture(body="<main><script>/* own fixture */</script></main>") {url,_ ->
        val calls=AtomicInteger();val renderer=object:LocalDynamicRenderer {
            override fun isPhysicallyIdle()=true
            override fun render(html:ByteArray,request:EngineRequest,operation:DiscoveryOperation):ByteArray {calls.incrementAndGet();return this@LocalVerificationEngineTest.html.toByteArray()}
        }
        val e=LocalVerificationEngine(renderer,true)
        assertEquals(EngineOutcome.UNKNOWN,e.verify(request(url),operation()).outcome);assertEquals(0,calls.get())
        assertEquals(EngineEvidence.DYNAMIC_VIDEO,e.verify(request(url,dynamic=true),operation()).evidence);assertEquals(1,calls.get())
    }
}
