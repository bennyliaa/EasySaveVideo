package com.easysavevideo.verification.local

import com.easysavevideo.discovery.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Inline-only rendering: external script/subresource traffic is never authorized here. */
interface LocalDynamicRenderer {
    fun isPhysicallyIdle(): Boolean
    fun render(html: ByteArray, request: EngineRequest, operation: DiscoveryOperation): ByteArray?
}

/** Original bounded B implementation; source admission is supplied by the composition root. */
class LocalVerificationEngine(private val renderer: LocalDynamicRenderer? = null,
    private val fixtureLoopbackOnly: Boolean = false,
    private val monotonicMillis: () -> Long = { System.nanoTime()/1_000_000 }) : VerificationEngine, AutoCloseable {
    override val id = "android-local"
    override val capabilities = setOf(EngineCapability.STATIC_PAGE, EngineCapability.LOCAL_MATCHING) +
        if (renderer != null) setOf(EngineCapability.DYNAMIC_PAGE) else emptySet()
    override val sendsDataToAnotherProcess = false
    private val busy = AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val activeCall=java.util.concurrent.atomic.AtomicReference<Call?>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r,"ESV-local-verification").apply { isDaemon=true } }
    private val parser = LocalHtmlEvidenceParser()
    private val refusedOrigins=ConcurrentHashMap.newKeySet<String>()
    override fun isAvailable() = !closed.get()
    override fun close() {closed.set(true);activeCall.get()?.cancel();executor.shutdownNow()}
    override fun isPhysicallyIdle() = !busy.get() && (renderer?.isPhysicallyIdle() != false)
    private fun addressDenied(a:InetAddress)= if(fixtureLoopbackOnly) !a.isLoopbackAddress else
        !VerificationOriginPolicy.publicAddress(a.address)

    override fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult {
        fun result(outcome: EngineOutcome, bytes:Long=0, calls:Int=0, known:Boolean=true,
            evidence:EngineEvidence=EngineEvidence.NONE, raw:RawVideoResult?=null) =
            EngineResult(request.requestId,request.generation,id,outcome,evidence,raw,calls,bytes,known)
        val url = try { request.candidate.pageUrl.toHttpUrl() } catch (_: Exception) { return result(EngineOutcome.DENIED) }
        val origin = "${url.scheme}://${url.host}:${url.port}"
        if (origin !in request.network.allowedOrigins || origin in refusedOrigins || refusedOrigins.size>=16 ||
            ((request.network.requireTls || !fixtureLoopbackOnly) && !url.isHttps) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            (fixtureLoopbackOnly && url.host != "127.0.0.1")) return result(EngineOutcome.DENIED)
        // OkHttp bypasses Dns.lookup for numeric literals: check them independently before scheduling.
        if ((url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) &&
            addressDenied(InetAddress.getByName(url.host))) return result(EngineOutcome.DENIED)
        val cap=minOf(request.maxBytes,262144L)
        val remaining=request.deadlineMonotonicMillis-monotonicMillis()
        if(closed.get()) return result(EngineOutcome.UNAVAILABLE)
        if (operation.cancellation.isCancelled) return result(EngineOutcome.CANCELLED)
        if (remaining<=0) return result(EngineOutcome.TIMEOUT)
        if (request.maxRequests<1 || cap<1 || !isPhysicallyIdle() || !busy.compareAndSet(false,true)) return result(EngineOutcome.UNAVAILABLE)
        val dns=object : Dns { override fun lookup(hostname:String):List<InetAddress> {
            val addresses=InetAddress.getAllByName(hostname).toList()
            if (addresses.isEmpty() || addresses.any(::addressDenied)) throw java.net.UnknownHostException("ESV_ADDRESS_DENIED")
            return listOf(addresses.first())
        } }
        val client=OkHttpClient.Builder().dns(dns).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0,1,TimeUnit.MILLISECONDS))
            .callTimeout(remaining,TimeUnit.MILLISECONDS).build()
        val call=client.newCall(Request.Builder().url(url).header("Accept","text/html")
            .header("Accept-Encoding","identity").header("User-Agent","ESV-local-validation/1").get().build())
        activeCall.set(call)
        val future=try {executor.submit<EngineResult> {
            try {
                call.execute().use { response ->
                    val headers=response.headers.toString().toByteArray(Charsets.UTF_8).size
                    if (headers>request.network.maxHeaderBytes) return@submit result(EngineOutcome.DENIED,cap,1)
                    if (response.code==429) {refusedOrigins.add(origin);return@submit result(EngineOutcome.RATE_LIMITED,cap,1)}
                    if (response.code in setOf(401,403) || response.code in 300..399) {
                        refusedOrigins.add(origin);return@submit result(EngineOutcome.DENIED,cap,1)
                    }
                    if (!response.isSuccessful || !response.header("Content-Type","")!!.startsWith("text/html",true) ||
                        response.header("Content-Encoding")?.let {it != "identity"} == true)
                        return@submit result(EngineOutcome.UNKNOWN,cap,1)
                    val body=response.body ?: return@submit result(EngineOutcome.UNKNOWN,cap,1)
                    if (body.contentLength()>cap) return@submit result(EngineOutcome.UNKNOWN,cap,1)
                    val buffer=java.io.ByteArrayOutputStream()
                    body.byteStream().use { stream ->
                        val chunk=ByteArray(4096)
                        while (true) {
                            if (operation.cancellation.isCancelled || monotonicMillis()>=request.deadlineMonotonicMillis)
                                return@submit result(EngineOutcome.CANCELLED,cap,1)
                            val n=stream.read(chunk,0,minOf(chunk.size,(cap-buffer.size()+1).toInt()))
                            if (n<0) break
                            if (buffer.size()+n>cap) return@submit result(EngineOutcome.UNKNOWN,cap,1)
                            buffer.write(chunk,0,n)
                        }
                    }
                    val bytes=buffer.toByteArray()
                    // Finite compiled challenge markers cause source shutdown, never evasive retries.
                    val lower=bytes.toString(Charsets.UTF_8).lowercase(java.util.Locale.ROOT)
                    if(listOf("g-recaptcha","h-captcha","cf-turnstile","verify you are human","captcha").any(lower::contains)) {
                        refusedOrigins.add(origin);return@submit result(EngineOutcome.DENIED,cap,1)
                    }
                    val static=parser.parse(bytes,request.candidate.pageUrl)
                    if (static!=null) return@submit result(EngineOutcome.PAGE_EVIDENCE,cap,1,true,EngineEvidence.HTML_VIDEO,static.raw)
                    if (request.dynamicAllowed && renderer!=null && remaining>=250) {
                        val rendered=renderer.render(bytes,request,operation)
                        val dynamic=rendered?.let {parser.parse(it,request.candidate.pageUrl)}
                        if (dynamic!=null) return@submit result(EngineOutcome.PAGE_EVIDENCE,cap,1,true,EngineEvidence.DYNAMIC_VIDEO,dynamic.raw)
                    }
                    result(EngineOutcome.UNKNOWN,cap,1)
                }
            } catch (_: Exception) {
                result(if(operation.cancellation.isCancelled) EngineOutcome.CANCELLED else EngineOutcome.ERROR,cap,1)
            } finally {
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                activeCall.compareAndSet(call,null)
                busy.set(false)
            }
        }} catch(_:RejectedExecutionException) {
            activeCall.compareAndSet(call,null);busy.set(false);call.cancel()
            return result(EngineOutcome.UNAVAILABLE)
        }
        while (true) {
            if (operation.cancellation.isCancelled || Thread.currentThread().isInterrupted || monotonicMillis()>=request.deadlineMonotonicMillis) {
                call.cancel()
                return result(if(operation.cancellation.isCancelled || Thread.currentThread().isInterrupted) EngineOutcome.CANCELLED else EngineOutcome.TIMEOUT,cap,1,known=false)
            }
            try { return future.get(10,TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (_: InterruptedException) {call.cancel();Thread.currentThread().interrupt();return result(EngineOutcome.CANCELLED,cap,1,false)}
            catch (_: Exception) {call.cancel();return result(EngineOutcome.ERROR,cap,1,false)}
        }
    }
}
