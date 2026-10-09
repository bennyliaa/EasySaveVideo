package com.easysavevideo.app.recording.nativeregion

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RegionPreviewPixelLeaseTest {
    @Test fun closingOwnerRevokesNewAcquisitionWithoutInvalidatingDrawingReference() {
        val pixels = Any()
        val owner = RegionPreviewPixelLease(pixels)
        val reader = requireNotNull(owner.acquire())
        owner.close(); owner.close()
        assertNull(owner.acquire())
        assertTrue(owner.retainedByOwner())
        assertSame(pixels, reader.value)
        reader.close(); reader.close()
        assertFalse(owner.retainedByOwner())
    }

    @Test fun readerSlotsAreBoundedAndAnOldRecompositionCanRetireAfterNewReader() {
        val owner = RegionPreviewPixelLease(Any())
        val old = requireNotNull(owner.acquire())
        val next = requireNotNull(owner.acquire())
        assertNull(owner.acquire())
        old.close()
        val replacement = requireNotNull(owner.acquire())
        owner.close()
        next.close()
        assertTrue(owner.retainedByOwner())
        replacement.close()
        assertFalse(owner.retainedByOwner())
    }

    @Test fun lateOwnerCloseAndConcurrentReaderCloseAreIdempotent() {
        val owner = RegionPreviewPixelLease(Any())
        val reader = requireNotNull(owner.acquire())
        val begin = CountDownLatch(1)
        val errors = AtomicInteger()
        val threads = (1..8).map { index -> Thread {
            begin.await()
            try { repeat(100) { if (index % 2 == 0) owner.close() else reader.close() } }
            catch (_: RuntimeException) { errors.incrementAndGet() }
        }.apply { start() } }
        begin.countDown(); threads.forEach { it.join() }
        assertEquals(0, errors.get())
        assertFalse(owner.retainedByOwner())
        assertNull(owner.acquire())
    }

    @Test(expected = IllegalStateException::class)
    fun closedReaderCannotStartAnotherDraw() {
        val reader = requireNotNull(RegionPreviewPixelLease(Any()).acquire())
        reader.close(); reader.value
    }
}
