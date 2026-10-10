package com.easysavevideo.app.validation

import com.easysavevideo.discovery.*
import com.easysavevideo.verification.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug only, fixed LOCAL HTTP endpoint; no TLS claim and no production settings integration. */
class DebugLoopbackVerificationEngine(admittedCandidates:Set<String>):VerificationEngine {
    private val admittedCandidates=admittedCandidates.toSet()
    init {
        require(admittedCandidates.size<=101 && admittedCandidates.all {
            it.matches(Regex("https://fixture\\.example\\.org/((static|dynamic|metadata|embedded|negative|unknown)-[0-9]{2}\\.html|fallback-owned\\.html)"))
        })
    }
    override val id="debug-loopback-scrapling"
    override val capabilities=setOf(EngineCapability.STATIC_PAGE,EngineCapability.DYNAMIC_PAGE)
    override val sendsDataToAnotherProcess=true
    private val consent=AtomicBoolean(false)
    private val busy=AtomicBoolean(false)
    private val activeCall=java.util.concurrent.atomic.AtomicReference<Call?>()
    fun setProcessConsent(value:Boolean) {consent.set(value);if(!value)activeCall.get()?.cancel()}
    override fun isAvailable()=consent.get()
    override fun isPhysicallyIdle()=!busy.get()
    override fun verify(request:EngineRequest,operation:DiscoveryOperation):EngineResult {
        fun result(outcome:EngineOutcome,calls:Int=0,bytes:Long=0)=EngineResult(request.requestId,request.generation,id,outcome,requestsConsumed=calls,bytesConsumed=bytes)
        if(!consent.get() || !request.remoteConsent || request.candidate.pageUrl !in admittedCandidates) return result(EngineOutcome.UNAVAILABLE)
        if(operation.cancellation.isCancelled) return result(EngineOutcome.CANCELLED)
        val remaining=request.deadlineMonotonicMillis-System.nanoTime()/1_000_000
        if(remaining<=0) return result(EngineOutcome.TIMEOUT)
        if(request.maxRequests<1 || request.maxBytes<32768 || !busy.compareAndSet(false,true)) return result(EngineOutcome.UNAVAILABLE)
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(remaining,TimeUnit.MILLISECONDS).build()
        var call:Call?=null
        var cancellation:CancellationHandle?=null
        try {
            val body=VerificationWireCodec.request(request.requestId,request.generation,request.candidate.pageUrl,minOf(10000L,remaining),request.dynamicAllowed)
            call=client.newCall(Request.Builder().url("http://127.0.0.1:8765/v1/verify")
                .header("Accept-Encoding","identity").header("Connection","close")
                .post(body.toRequestBody("application/json".toMediaType())).build())
            val active=call
            activeCall.set(active)
            cancellation=operation.cancellation.onCancel {active.cancel()}
            if(!consent.get() || operation.cancellation.isCancelled) return result(EngineOutcome.CANCELLED)
            call.execute().use {response ->
                if(response.code!=200 || response.header("Set-Cookie")!=null ||
                    response.header("Content-Type")?.substringBefore(';')!="application/json" ||
                    response.header("Content-Encoding")?.lowercase()?.let {it!="identity"}==true) return result(EngineOutcome.UNKNOWN,1,32768)
                val input=response.body?.byteStream() ?: return result(EngineOutcome.UNKNOWN,1,32768)
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(output.size()<=32768) {
                    val count=input.read(buffer,0,minOf(buffer.size,32769-output.size()))
                    if(count<0) break
                    if(count==0) return result(EngineOutcome.UNKNOWN,1,32768)
                    output.write(buffer,0,count)
                }
                val bytes=output.toByteArray()
                if(bytes.size>32768) return result(EngineOutcome.UNKNOWN,1,32768)
                val parsed=VerificationWireCodec.response(bytes,request.requestId,request.generation,request.candidate.pageUrl,request.dynamicAllowed) {
                    check(consent.get() && !operation.cancellation.isCancelled && System.nanoTime()/1_000_000<request.deadlineMonotonicMillis)
                }
                val outcome=when(parsed.outcome) {
                    VerificationOutcome.PAGE_EVIDENCE->EngineOutcome.PAGE_EVIDENCE
                    VerificationOutcome.REJECTED->EngineOutcome.DENIED
                    VerificationOutcome.CANCELLED->EngineOutcome.CANCELLED
                    VerificationOutcome.TIMEOUT->EngineOutcome.TIMEOUT
                    VerificationOutcome.INSUFFICIENT->EngineOutcome.UNKNOWN
                    else->EngineOutcome.UNAVAILABLE
                }
                val evidence=when(parsed.evidence) {
                    VerificationEvidence.HTML_VIDEO->EngineEvidence.HTML_VIDEO
                    VerificationEvidence.DYNAMIC_VIDEO_DOM,VerificationEvidence.DYNAMIC_VIDEO_XHR->EngineEvidence.DYNAMIC_VIDEO
                    else->EngineEvidence.NONE
                }
                return EngineResult(request.requestId,request.generation,id,outcome,evidence,parsed.toRaw(),1,32768)
            }
        } catch(_:Exception) {
            return result(if(operation.cancellation.isCancelled) EngineOutcome.CANCELLED else
                if(System.nanoTime()/1_000_000>=request.deadlineMonotonicMillis) EngineOutcome.TIMEOUT else EngineOutcome.UNAVAILABLE,1,32768)
        } finally {cancellation?.cancel();call?.cancel();activeCall.set(null);client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();busy.set(false)}
    }
}
