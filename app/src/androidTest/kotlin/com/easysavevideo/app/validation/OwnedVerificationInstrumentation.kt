package com.easysavevideo.app.validation

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.easysavevideo.app.discovery.AndroidLocalVerificationRenderer
import com.easysavevideo.verification.local.LocalVerificationEngine
import com.easysavevideo.discovery.*
import com.easysavevideo.core.model.OperationContext
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray

/** No external test dependencies. Default b/ab use authored pages; opus-technical is isolated media diagnostics. */
class OwnedVerificationInstrumentation:Instrumentation() {
    private var phase="b"
    override fun onCreate(args:Bundle?) {super.onCreate(args);phase=args?.getString("phase")?:"b";require(phase in setOf("b","ab","opus-technical"));start()}
    override fun onStart() {
        if (phase == "opus-technical") { OpusTechnicalValidation.run(this); return }
        val report=JSONObject();val rows=JSONArray();val failures=JSONArray()
        val cpuStart=android.os.Process.getElapsedCpuTime();val start=System.nanoTime()
        val rxStart=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val txStart=android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));val running=AtomicBoolean(true)
        val manifest=JSONObject(context.assets.open("owned/manifest.json").bufferedReader().readText())
        val cases=manifest.getJSONArray("cases")
        val bodies=mutableMapOf<String,Pair<Int,ByteArray>>()
        for(i in 0 until cases.length()) {val c=cases.getJSONObject(i);val path=c.getString("path")
            val body=context.assets.open("owned"+path).readBytes()
            check(MessageDigest.getInstance("SHA-256").digest(body).joinToString(""){"%02x".format(it)}==c.getString("sha256"))
            bodies[path]=c.getInt("expected_status") to body
        }
        val serverThread=Thread {
            while(running.get()) try {server.accept().use {socket ->
                val input=socket.getInputStream().bufferedReader();val path=input.readLine()?.split(' ')?.getOrNull(1)
                while(input.readLine()?.isNotEmpty()==true) { }
                if(path=="/slow.html") Thread.sleep(500)
                val pair=bodies[path]?:bodies.values.first()
                val (code,body)=pair;val header="HTTP/1.1 $code Fixture\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(header.toByteArray());socket.getOutputStream().write(body)
            }} catch(_:Exception) { }
        }.apply {isDaemon=true;start()}
        val origin="http://127.0.0.1:${server.localPort}"
        fun operation(c:DiscoveryCancellation=DiscoveryCancellation())=DiscoveryOperation(OperationContext("owned-device",System.currentTimeMillis()+20000,c),c)
        fun request(path:String,dynamic:Boolean=true,budget:Long=10000)=EngineRequest("a".repeat(32),"b".repeat(64),CandidateUrl(origin+path),System.nanoTime()/1_000_000+budget,3,1048576,dynamic,true,EngineNetworkConstraints(setOf(origin),false))
        fun recordFailure(name:String){failures.put(name)}
        val admitted=(0 until cases.length()).map {"https://fixture.example.org"+cases.getJSONObject(it).getString("path")}.toSet()+"https://fixture.example.org/fallback-owned.html"
        val a=DebugLoopbackVerificationEngine(admitted)
        var activity:OwnedFixtureActivity?=null
        try {
            check(android.os.Build.VERSION.SDK_INT==31)
            if(phase=="ab")a.setProcessConsent(true)
            for(i in 0 until cases.length()) {
                val c=cases.getJSONObject(i);val path=c.getString("path");val id=c.getString("id")
                val renderer=AndroidLocalVerificationRenderer(targetContext)
                val b=LocalVerificationEngine(renderer,true)
                val op=operation();val q=request(path);val begin=System.nanoTime()
                val result=b.verify(q,op)
                rows.put(JSONObject().put("id",id).put("engine","B").put("outcome",result.outcome.name).put("evidence",result.evidence.name).put("elapsed_ms",(System.nanoTime()-begin)/1000000).put("body_hash",c.getString("sha256")))
                if(c.getString("label")=="negative" && result.outcome==EngineOutcome.PAGE_EVIDENCE)recordFailure("B_NEGATIVE_PROMOTED")
                if(c.getString("group") !in setOf("static","dynamic") && result.outcome==EngineOutcome.PAGE_EVIDENCE)recordFailure("B_UNKNOWN_PROMOTED_"+id)
                if(c.getString("group") in setOf("static","dynamic") && result.outcome!=EngineOutcome.PAGE_EVIDENCE)recordFailure("B_EXPECTED_PRIMARY_"+id)
                b.close()
                if(phase=="ab") {
                    val aq=q.copy(candidate=CandidateUrl("https://fixture.example.org"+path))
                    val ar=a.verify(aq,operation())
                    rows.put(JSONObject().put("id",id).put("engine","A").put("outcome",ar.outcome.name).put("evidence",ar.evidence.name).put("body_hash",c.getString("sha256")))
                    if(c.getString("group") in setOf("static","dynamic") && ar.outcome!=EngineOutcome.PAGE_EVIDENCE)recordFailure("A_EXPECTED_PRIMARY_"+id)
                    if(c.getString("label")=="negative" && ar.outcome==EngineOutcome.PAGE_EVIDENCE)recordFailure("A_NEGATIVE_PROMOTED")
                    if(c.getString("group") !in setOf("static","dynamic") && ar.outcome==EngineOutcome.PAGE_EVIDENCE)recordFailure("A_UNKNOWN_PROMOTED_"+id)
                }
                if(i%20==19)sendStatus(0,Bundle().apply {putString("stream","Owned cases completed ${i+1}\n")})
            }
            val b=LocalVerificationEngine(AndroidLocalVerificationRenderer(targetContext),true)
            val cancelled=DiscoveryCancellation().apply {cancel()}
            if(b.verify(request("/static-00.html"),operation(cancelled)).outcome!=EngineOutcome.CANCELLED)recordFailure("INITIAL_CANCEL")
            if(b.verify(request("/slow.html",budget=100),operation()).outcome!=EngineOutcome.TIMEOUT)recordFailure("HTTP_TIMEOUT")
            Thread.sleep(600)
            if(!b.isPhysicallyIdle())recordFailure("HTTP_CLEANUP")
            val op=operation();val slow=request("/slow.html")
            val t=Thread {b.verify(slow,op)};t.start();Thread.sleep(100)
            if(b.verify(request("/static-00.html"),operation()).outcome==EngineOutcome.PAGE_EVIDENCE)recordFailure("CONCURRENT_OVERLAP")
            op.cancellation.cancel();t.join(2000)
            if(t.isAlive || !b.isPhysicallyIdle())recordFailure("CANCEL_CLEANUP")
            b.close()
            activity=startActivitySync(Intent(targetContext,OwnedFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwnedFixtureActivity
            val old=activity!!;val monitor=addMonitor(OwnedFixtureActivity::class.java.name,null,false)
            val lifecycleB=LocalVerificationEngine(AndroidLocalVerificationRenderer(targetContext),true)
            val stale=java.util.concurrent.atomic.AtomicReference<EngineResult?>()
            val lifecycleThread=Thread {stale.set(lifecycleB.verify(request("/slow.html"),operation(old.cancellation)))}
            lifecycleThread.start();Thread.sleep(100)
            runOnMainSync {old.recreate()}
            activity=monitor.waitForActivityWithTimeout(5000) as? OwnedFixtureActivity
            lifecycleThread.join(2000)
            if(activity==null || !old.cancellation.isCancelled)recordFailure("ACTIVITY_RECREATE")
            if(stale.get()?.outcome!=EngineOutcome.CANCELLED || stale.get()?.raw!=null || lifecycleThread.isAlive || !lifecycleB.isPhysicallyIdle())recordFailure("ACTIVITY_STALE_PUBLICATION")
            lifecycleB.close()
            activity?.let {next->runOnMainSync {next.finish()}};waitForIdleSync()
            if(activity?.cancellation?.isCancelled!=true)recordFailure("ACTIVITY_CLOSE")
            val switched=AtomicBoolean(true);val switchOp=operation()
            val switchB=LocalVerificationEngine(fixtureLoopbackOnly=true)
            val switchResult=java.util.concurrent.atomic.AtomicReference<EngineResult?>()
            val switchThread=Thread {switchResult.set(VerificationEngineScheduler().verify(request("/slow.html"),switchOp,VerificationEngineRegistry().apply { register(switchB,EngineAccessBoundary.OWNED_LOOPBACK_FIXTURE) }.all(),isCurrent={switched.get()}))}
            switchThread.start();Thread.sleep(100);switched.set(false);switchOp.cancellation.cancel();switchThread.join(2000)
            if(switchThread.isAlive || switchResult.get()?.outcome!=EngineOutcome.CANCELLED || switchResult.get()?.raw!=null)recordFailure("SOURCE_SWITCH_STALE_PUBLICATION")
            switchB.close()
            if(phase=="ab") {
                val mapper=object:VerificationEngine {
                    override val id="owned-local-mapper";override val capabilities=setOf(EngineCapability.STATIC_PAGE)
                    override val sendsDataToAnotherProcess=false;override fun isAvailable()=true;override fun isPhysicallyIdle()=true
                    override fun verify(q:EngineRequest,o:DiscoveryOperation):EngineResult {
                        val local=LocalVerificationEngine(fixtureLoopbackOnly=true)
                        try {val x=local.verify(q.copy(candidate=CandidateUrl(origin+"/static-00.html"),network=EngineNetworkConstraints(setOf(origin),false)),o)
                            return x.copy(engineId=id,raw=x.raw?.copy(pageUrl=q.candidate.pageUrl))
                        } finally {local.close()}
                    }
                }
                val q=request("/static-00.html").copy(candidate=CandidateUrl("https://fixture.example.org/fallback-owned.html"), network=EngineNetworkConstraints(setOf("https://fixture.example.org:443")))
                val fallback=VerificationEngineScheduler().verify(q,operation(),VerificationEngineRegistry().apply { register(a,EngineAccessBoundary.REMOTE_SERVICE);register(mapper,EngineAccessBoundary.LOCAL_TARGET) }.all(),EngineSelection(listOf(a.id)))
                if(fallback.outcome!=EngineOutcome.PAGE_EVIDENCE || fallback.engineId!=mapper.id)recordFailure("A_UNSUPPORTED_B_FALLBACK")
                report.put("fallback_outcome",fallback.outcome.name)
            }
        } catch(e:Throwable) {recordFailure("TECHNICAL_"+e.javaClass.simpleName)}
        finally {a.setProcessConsent(false);activity?.let {runOnMainSync {it.finish()}};running.set(false);server.close();serverThread.join(1000)}
        report.put("phase",phase).put("rows",rows).put("failures",failures).put("elapsed_ms",(System.nanoTime()-start)/1000000)
            .put("cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStart).put("pss_kib_snapshot",android.os.Debug.getPss())
            .put("uid_rx_bytes",android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())-rxStart)
            .put("uid_tx_bytes",android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())-txStart)
            .put("uid_traffic_counter_available",rxStart>=0 && txStart>=0)
            .put("energy","NOT_MEASURED").put("scope","emulator diagnostics only")
        val outputRoot=java.io.File(targetContext.cacheDir,"esv-owned-validation-20261010").apply {mkdirs()}
        val output=java.io.File(outputRoot,"owned-$phase-${System.currentTimeMillis()}-report.json")
        check(!output.exists());output.writeText(report.toString(2))
        finish(if(failures.length()==0)-1 else 0,Bundle().apply {putString("stream","Owned validation failures=${failures.length()} rows=${rows.length()}\n")})
    }
}
