package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IndependentPreviewAdversarialTest {
    private val session = RecordingSessionId("independent-review", 7)
    private val geometry = CaptureDisplayGeometry(1080, 1920, 0, CaptureInsets(0, 42, 0, 80))
    private fun transform() = floatArrayOf(1f,0f,0f,0f,0f,-1f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f)
    private fun snapshot() = RegionPreviewSnapshot(session, 4, 1,
        CaptureGeometryObservation(geometry, CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY,
            CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 1_100),
        720,1280,RegionPreviewTransform(transform()),1_200_000,1_300,60_001_300)
    private fun visible(): RegionPreviewGate = RegionPreviewGate(session,4,1_000).apply {
        assertTrue(markHostReady(1_150)); assertTrue(publish(snapshot(),1_400))
    }

    @Test fun pixelOrderRowOriginAndBufferSliceAreIndependentOfHostEndianness() {
        val colors = intArrayOf(0xff123456.toInt(),0xfffedcba.toInt(),0x80010203.toInt(),0x40102030)
        for (order in listOf(ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN)) {
            val bytes = ByteBuffer.allocate(24).order(order)
            bytes.position(4)
            for (argb in intArrayOf(colors[2],colors[3],colors[0],colors[1])) {
                bytes.put((argb ushr 16).toByte()); bytes.put((argb ushr 8).toByte())
                bytes.put(argb.toByte()); bytes.put((argb ushr 24).toByte())
            }
            bytes.limit(20); bytes.position(4)
            assertArrayEquals(colors,RegionPreviewRasterPolicy.topDownArgb(bytes,RegionPreviewRasterPolicy.Size(2,2)))
            assertEquals(4,bytes.position())
        }
    }
    @Test fun previewSizeBoundsHoldAcrossWideTallSquareAndRoundingExtents() {
        for (w in 128..4096 step 37) for(h in 128..4096 step 41) {
            val size=RegionPreviewRasterPolicy.size(w,h)
            assertTrue(size.width<=w && size.height<=h)
            assertTrue(size.pixels<=921_600 && size.width<=1280 && size.height<=1280)
            assertTrue(kotlin.math.abs(size.width.toLong()*h-size.height.toLong()*w)<=maxOf(w,h))
        }
    }
    @Test fun exactMetadataIsRequiredEvenWhenSessionAndGeometryMatch() {
        val gate=visible(); val token=snapshot()
        for (altered in listOf(token.copy(frameSequence=2),token.copy(rasterWidth=719),
            token.copy(sourceFrameTimestampNs=1_201_000),token.copy(expiresAtMonotonicUs=60_001_299),
            token.copy(sourceTransform=RegionPreviewTransform(transform().apply {this[0]=-1f;this[12]=1f})))) {
            assertFalse(gate.confirm(altered,RegionNormalizedRect(.1,.1,.9,.9),1_500))
        }
        assertTrue(gate.confirm(token,RegionNormalizedRect(.1,.1,.9,.9),1_600))
        assertNull(gate.takeReady(1_600))
        assertTrue(gate.retireUi(token,1_700)); assertNull(gate.takeReady(1_700))
        assertTrue(gate.markCaptureReady(token,1_800)); assertNotNull(gate.takeReady(1_900))
        assertNull(gate.takeReady(2_000))
    }
    @Test fun everyPreAdmissionInvalidationMakesEveryLaterCallbackInert() {
        for (reason in RegionPreviewInvalidation.entries) {
            val gate=visible(); val token=snapshot()
            assertTrue(gate.confirm(token,RegionNormalizedRect(.1,.1,.9,.9),1_500))
            gate.invalidate(reason)
            assertFalse(gate.markHostReady(1_600)); assertFalse(gate.publish(token,1_600))
            assertFalse(gate.confirm(token,RegionNormalizedRect.FULL,1_600))
            assertFalse(gate.retireUi(token,1_600)); assertFalse(gate.markCaptureReady(token,1_600))
            assertNull(gate.takeReady(1_600)); assertNull(gate.snapshot); assertNull(gate.confirmation)
        }
    }
    @Test fun confirmationRaceHasOneWinnerAndRemainsOneShot() {
        val gate=visible(); val token=snapshot(); val start=CountDownLatch(1); val done=CountDownLatch(8)
        val won=AtomicInteger(); val threads=(0 until 8).map { Thread {
            start.await(); if(gate.confirm(token,RegionNormalizedRect(.1,.1,.9,.9),1_500)) won.incrementAndGet()
            done.countDown()
        }.apply {start()} }
        start.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS)); threads.forEach {it.join()}
        assertEquals(1,won.get())
    }
    @Test fun expiredReadyConfirmationDoesNotBecomeEncodingAuthority() {
        val gate=visible(); val token=snapshot()
        assertTrue(gate.confirm(token,RegionNormalizedRect(.1,.1,.9,.9),1_500))
        assertTrue(gate.retireUi(token,1_600)); assertTrue(gate.markCaptureReady(token,1_700))
        assertNull(gate.takeReady(token.expiresAtMonotonicUs))
        assertTrue(gate.isInvalidated)
    }
    @Test fun releasingOwnerCannotRevokeAnAcquiredPixelReaderOrCreateNewReaders() {
        val marker=Any(); val owner=RegionPreviewPixelLease(marker); val reader=owner.acquire()!!
        owner.close(); owner.close(); assertNull(owner.acquire()); assertSame(marker,reader.value)
        assertTrue(owner.retainedByOwner()); reader.close(); reader.close(); assertFalse(owner.retainedByOwner())
    }
}
