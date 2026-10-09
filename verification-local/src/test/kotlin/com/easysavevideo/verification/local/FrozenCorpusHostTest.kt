package com.easysavevideo.verification.local

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Frozen authored corpus only. Reports host static-B evidence; never Android dynamic/energy acceptance. */
class FrozenCorpusHostTest {
    @Test fun same100FrozenBodiesActualStaticB() {
        val directory=System.getenv("ESV_OWNED_CORPUS")
        org.junit.Assume.assumeNotNull(directory)
        val root=Path.of(directory!!).toRealPath()
        val pages=Files.list(root).use {it.filter {p->p.fileName.toString().matches(Regex("(static|metadata|dynamic|embedded|negative|unknown)-[0-9]{2}\\.html"))}.sorted().toList()}
        assertEquals(100,pages.size)
        val calls=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        for(path in pages)server.createContext("/"+path.fileName) {x ->
            calls.incrementAndGet();val bytes=Files.readAllBytes(path);x.responseHeaders.add("Content-Type","text/html; charset=utf-8")
            val code=if(path.fileName.toString().startsWith("unknown-"))403 else 200
            x.sendResponseHeaders(code,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
        }
        val rows=mutableListOf<String>();val origin="http://127.0.0.1:${server.address.port}"
        server.start()
        try {
            // Frozen fixture authors supply labels, independently of engine results.
            for(path in pages) {
                val id=path.fileName.toString().removeSuffix(".html");val group=id.substringBefore('-')
                val label=when(group){"negative"->"NON_VIDEO";"unknown"->"UNKNOWN";else->"VIDEO"}
                val body=Files.readAllBytes(path);val hash=MessageDigest.getInstance("SHA-256").digest(body).joinToString(""){"%02x".format(it)}
                val cancellation=DiscoveryCancellation();val op=DiscoveryOperation(OperationContext("frozen",System.currentTimeMillis()+5000,cancellation),cancellation)
                val request=EngineRequest("0123456789abcdef0123456789abcdef","generation",CandidateUrl("$origin/${path.fileName}"),System.nanoTime()/1_000_000+1500,1,262144,false,false,EngineNetworkConstraints(setOf(origin),false))
                // New fixture-only instance gives independent cases: source refusal is tested separately.
                val engine=LocalVerificationEngine(fixtureLoopbackOnly=true)
                val start=System.nanoTime();val result=engine.verify(request,op);val elapsed=(System.nanoTime()-start)/1_000_000
                assertTrue(engine.isPhysicallyIdle());assertNotEquals(EngineOutcome.ERROR,result.outcome)
                engine.close()
                rows+="$id\t$label\t${result.verdict}\t${result.outcome}\t${result.evidence}\t$hash\t$elapsed"
            }
        } finally {server.stop(0)}
        val output=Path.of("build/reports/frozen-static-b-host.tsv");Files.createDirectories(output.parent)
        Files.writeString(output,"id\tindependent_label\tverdict\toutcome\tevidence\tbody_sha256\thost_elapsed_ms\n"+rows.joinToString("\n")+"\n")
        assertEquals(100,rows.size);assertEquals(100,calls.get())
    }
}
