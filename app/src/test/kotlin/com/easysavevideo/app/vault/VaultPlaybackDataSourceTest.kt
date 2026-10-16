package com.easysavevideo.app.vault

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Runs actual production transport/policy with exact Media3 constants, not Android Uri stubs. */
class VaultPlaybackDataSourceTest {
    private class Read(val size: Int = 10) : VaultPlaybackReadPort {
        val bytes = ByteArray(size) { (it and 255).toByte() }
        var current = true; var opened = 0; var opens = 0; var closes = 0
        val calls = mutableListOf<Pair<Long, Int>>()
        override val authenticatedSize get() = size.toLong()
        override fun requireCurrent() { if (!current) throw safePlaybackIo("REVOKED") }
        override fun transportOpened() { requireCurrent(); if (opened != 0) throw safePlaybackIo("TRANSPORT_BUSY"); opened++; opens++ }
        override fun transportClosed() { check(opened == 1); opened--; closes++ }
        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            requireCurrent(); calls += position to length
            val count = minOf(length, size - position.toInt()); bytes.copyInto(destination, offset, position.toInt(), position.toInt() + count); return count
        }
    }
    @Test fun allFrozenNumericVectorsUseActualProductionTransport() {
        // Exact equivalents of DESIGN R4 DATA_SOURCE_VECTORS.json, including signed Long maximum.
        data class V(val position: Long, val length: Long, val resolved: Long?, val reads: List<Int>)
        val vectors = listOf(
            V(0, -1, 10, listOf(4,4,2,-1)), V(3,-1,7,listOf(4,3,-1,-1)),
            V(2,3,3,listOf(3,-1,-1,-1)), V(8,8,8,listOf(2,-1,-1,-1)),
            V(10,-1,0,listOf(-1,-1,-1,-1)), V(10,5,5,listOf(-1,-1,-1,-1)),
            V(11,-1,null,emptyList()), V(8,Long.MAX_VALUE,Long.MAX_VALUE,listOf(2,-1,-1,-1)))
        for (v in vectors) {
            val port = Read(); val transport = VaultPlaybackTransport(port)
            if (v.resolved == null) {
                try { transport.open(v.position,v.length); fail() }
                catch (e: IOException) { assertTrue(DataSourceException.isCausedByPositionOutOfRange(e)) }
            } else {
                assertEquals(v.resolved, transport.open(v.position,v.length))
                v.reads.forEach { expected -> assertEquals(expected,transport.read(ByteArray(4),0,4)) }
            }
            transport.close(); transport.close(); assertEquals(0,port.opened)
        }
    }
    @Test fun rangeFailureNeverChangesDestinationOrOverflowAdds() {
        val port = Read(); val t = VaultPlaybackTransport(port)
        for ((pos,len) in listOf(-1L to -1L,0L to 0L,0L to -2L)) {
            try { t.open(pos,len); fail() } catch (_: IOException) { }
        }
        assertEquals(0,port.opens); t.open(8,Long.MAX_VALUE)
        val out = ByteArray(10) { 91 }
        for ((off,len) in listOf(-1 to 1,0 to -1,9 to 2,Int.MAX_VALUE to 1,1 to Int.MAX_VALUE)) {
            try { t.read(out,off,len); fail() } catch (_: IOException) { }
        }
        assertTrue(out.all { it == 91.toByte() }); assertEquals(0,t.read(out,0,0)); t.close()
    }
    @Test fun largeRequestsAreShortReadAtCapabilityBoundAndBytesMatch() {
        val port = Read(VaultMediaLimits.BUFFER_BYTES + 37); val t = VaultPlaybackTransport(port); t.open(0,-1)
        val out = ByteArray(port.size); val first = t.read(out,0,out.size)
        assertEquals(VaultMediaLimits.BUFFER_BYTES,first)
        assertEquals(37,t.read(out,first,37)); assertArrayEquals(port.bytes,out)
        assertTrue(port.calls.all { it.second <= VaultMediaLimits.BUFFER_BYTES }); t.close()
    }
    @Test fun closeAndReopenSeekUseSameCapabilityAndSingleConcurrentTransport() {
        val port = Read(); val a = VaultPlaybackTransport(port); val b = VaultPlaybackTransport(port)
        a.open(0,-1)
        try { b.open(8,-1); fail() } catch (_: IOException) { }
        b.close(); assertEquals(1,port.opened)
        a.read(ByteArray(2),0,2); a.close(); b.open(8,-1); b.read(ByteArray(2),0,2); b.close()
        a.open(4,2); a.read(ByteArray(2),0,2); a.close()
        assertEquals(listOf(0L,8L,4L),port.calls.map { it.first }); assertEquals(3,port.opens); assertEquals(3,port.closes)
    }
    @Test fun revocationRejectsEofZeroCachedAndReopenedReads() {
        val port = Read(); val t = VaultPlaybackTransport(port); t.open(0,-1); t.read(ByteArray(2),0,2); port.current = false
        for (len in listOf(0,2)) try { t.read(ByteArray(2),0,len); fail() } catch (_: IOException) { }
        t.close(); try { t.open(0,-1); fail() } catch (_: IOException) { }
        assertEquals(1,port.calls.size)
    }
    @Test fun allowsOnlyPinnedProgressiveTechnicalHeadersAndFlags() {
        VaultPlaybackDataSpecPolicy.requireAllowed(true,1,false,false,false,0,emptyMap(),0)
        VaultPlaybackDataSpecPolicy.requireAllowed(true,1,false,false,false,0,mapOf("Icy-MetaData" to "1"),6)
        for (flags in listOf(1,2,4,7,8,Int.MAX_VALUE)) {
            try { VaultPlaybackDataSpecPolicy.requireAllowed(true,1,false,false,false,0,emptyMap(),flags); fail() } catch (_: IOException) { }
        }
    }
    @Test fun rejectsEveryAddressMetadataAndCustomHttpChannel() {
        data class S(val empty: Boolean = true,val method: Int = 1,val body: Boolean = false,val key: Boolean = false,
            val custom: Boolean = false,val offset: Long = 0,val headers: Map<String,String> = emptyMap(),val flags: Int = 0)
        for (s in listOf(S(empty=false),S(method=2),S(method=3),S(body=true),S(key=true),S(custom=true),S(offset=1),
            S(headers=mapOf("Authorization" to "secret")),S(headers=mapOf("Icy-MetaData" to "0"),flags=6),S(headers=mapOf("Icy-MetaData" to "1")))) {
            try { VaultPlaybackDataSpecPolicy.requireAllowed(s.empty,s.method,s.body,s.key,s.custom,s.offset,s.headers,s.flags); fail() }
            catch (e: IOException) { assertEquals("VAULT_SPEC_REJECTED",e.message); assertNull(e.cause) }
        }
    }
    @Test fun unsafeCauseAndMessageCannotEscapeErrorMapper() {
        val secret = IllegalStateException("https://private.example/name /secret/video.mp4 object_99")
        val safe = safePlaybackError(secret); assertEquals("VAULT_READ_FAILED",safe.message); assertNull(safe.cause)
        val range = safePlaybackError(DataSourceException("secret",secret,PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(range)); assertEquals("VAULT_POSITION_OUT_OF_RANGE",range.message); assertNull(range.cause)
    }
    @Test fun productionReadOwnerIsSharedAcrossLegalMultiOpenSeek() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val t = VaultPlaybackTransport(f.port)
        for (position in listOf(0L,500L,200L)) { t.open(position,-1); assertEquals(5,t.read(ByteArray(5),0,5)); t.close() }
        assertEquals(1,f.files); assertEquals(1,f.readers); assertEquals(0,f.channelCloses); assertEquals(0,f.fileCloses)
        f.runtime.close(f.ticket); f.pump(); f.completePlatformDrain()
        assertEquals(1,f.channelCloses); assertEquals(1,f.fileCloses); assertTrue(f.runtime.cleanupComplete())
    }
}
