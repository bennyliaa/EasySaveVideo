package com.easysavevideo.library

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MediaLeaseRegistryTest {
    @Test fun multipleReadersCoexistButWriterIsExclusive() {
        val registry = MediaLeaseRegistry()
        val first = registry.tryAcquireRead("item")!!
        val second = registry.tryAcquireRead("item")!!
        assertEquals(MediaLeaseSnapshot(2, false, 0), registry.snapshot("item"))
        assertNull(registry.tryAcquireWrite("item", "delete"))
        first.close()
        assertNull(registry.tryAcquireWrite("item", "delete"))
        second.close()
        val writer = registry.tryAcquireWrite("item", "delete")!!
        assertNull(registry.tryAcquireRead("item"))
        assertEquals(MediaLeaseSnapshot(0, true, 0), registry.snapshot("item"))
        writer.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun blockedWriterIntentFencesNewReadersAndSurvivesRetry() {
        val registry = MediaLeaseRegistry()
        val reader = registry.tryAcquireRead("item")!!
        repeat(10) { assertNull(registry.tryAcquireWrite("item", "delete")) }
        assertEquals(1, registry.snapshot("item").pendingWriters)
        assertNull(registry.tryAcquireRead("item"))
        reader.close()
        assertEquals(MediaLeaseSnapshot(0, false, 1), registry.snapshot("item"))
        assertNull(registry.tryAcquireRead("item"))
        registry.tryAcquireWrite("item", "delete")!!.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun writersAreFifoAndSameOperationCannotAcquireTwice() {
        val registry = MediaLeaseRegistry()
        val reader = registry.tryAcquireRead("item")!!
        assertNull(registry.tryAcquireWrite("item", "first"))
        assertNull(registry.tryAcquireWrite("item", "second"))
        reader.close()
        assertNull(registry.tryAcquireWrite("item", "second"))
        val first = registry.tryAcquireWrite("item", "first")!!
        assertNull(registry.tryAcquireWrite("item", "first"))
        assertNull(registry.tryAcquireWrite("item", "second"))
        assertEquals(MediaLeaseSnapshot(0, true, 1), registry.snapshot("item"))
        first.close()
        registry.tryAcquireWrite("item", "second")!!.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun writerOnOneItemDoesNotBlockAnotherItem() {
        val registry = MediaLeaseRegistry()
        val writer = registry.tryAcquireWrite("first", "delete")!!
        val reader = registry.tryAcquireRead("second")!!
        assertNull(registry.tryAcquireRead("first"))
        assertEquals(1, registry.snapshot("second").readers)
        reader.close(); writer.close()
    }

    @Test fun cancellationCanRemovePendingIntentButNeverActiveOwnership() {
        val registry = MediaLeaseRegistry()
        val reader = registry.tryAcquireRead("item")!!
        assertNull(registry.tryAcquireWrite("item", "cancelled"))
        assertTrue(registry.cancelWriteIntent("item", "cancelled"))
        assertFalse(registry.cancelWriteIntent("item", "cancelled"))
        registry.tryAcquireRead("item")!!.close()
        reader.close()
        val writer = registry.tryAcquireWrite("item", "active")!!
        assertFalse(registry.cancelWriteIntent("item", "active"))
        assertNull(registry.tryAcquireRead("item"))
        writer.close()
    }

    @Test fun cancellingHeadAllowsNextPendingWriterToProceed() {
        val registry = MediaLeaseRegistry()
        val reader = registry.tryAcquireRead("item")!!
        registry.tryAcquireWrite("item", "first")
        registry.tryAcquireWrite("item", "second")
        reader.close()
        assertTrue(registry.cancelWriteIntent("item", "first"))
        registry.tryAcquireWrite("item", "second")!!.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun closeIsIdempotentAndOldCloseCannotReleaseNewOwner() {
        val registry = MediaLeaseRegistry()
        val first = registry.tryAcquireRead("item")!!
        first.close(); first.close()
        assertTrue(first.isClosed)
        val second = registry.tryAcquireRead("item")!!
        repeat(10) { first.close() }
        assertEquals(1, registry.snapshot("item").readers)
        second.close()
        val writer = registry.tryAcquireWrite("item", "first")!!
        writer.close(); writer.close()
        val nextWriter = registry.tryAcquireWrite("item", "second")!!
        repeat(10) { writer.close() }
        assertTrue(registry.snapshot("item").writerActive)
        nextWriter.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun abandonedReaderRemainsFencedWithoutTimeoutRevocation() {
        val registry = MediaLeaseRegistry()
        val uncertainNativeReader = registry.tryAcquireRead("item")!!
        repeat(100) { assertNull(registry.tryAcquireWrite("item", "delete")) }
        assertEquals(MediaLeaseSnapshot(1, false, 1), registry.snapshot("item"))
        assertNull(registry.tryAcquireRead("item"))
        uncertainNativeReader.close()
        registry.tryAcquireWrite("item", "delete")!!.close()
    }

    @Test fun availabilityHookRunsOnlyAfterSafeReleaseAndOutsideRegistryMonitor() {
        val executor = Executors.newSingleThreadExecutor()
        val observed = AtomicInteger()
        lateinit var registry: MediaLeaseRegistry
        registry = MediaLeaseRegistry { id ->
            val snapshot = executor.submit(Callable { registry.snapshot(id) }).get(2, TimeUnit.SECONDS)
            assertEquals(0, snapshot.readers)
            assertFalse(snapshot.writerActive)
            observed.incrementAndGet()
        }
        try {
            val a = registry.tryAcquireRead("item")!!
            val b = registry.tryAcquireRead("item")!!
            a.close(); assertEquals(0, observed.get())
            b.close(); assertEquals(1, observed.get())
            b.close(); assertEquals(1, observed.get())
            registry.tryAcquireWrite("item", "delete")!!.close()
            assertEquals(2, observed.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun callbackFailureCannotCorruptReleaseOrMakeCloseRetryable() {
        val registry = MediaLeaseRegistry { throw IllegalStateException("TECHNICAL_SCHEDULER_FAILURE") }
        val reader = registry.tryAcquireRead("item")!!
        reader.close(); reader.close()
        assertTrue(registry.snapshot("item").idle)
        val writer = registry.tryAcquireWrite("item", "delete")!!
        writer.close(); writer.close()
        assertTrue(registry.snapshot("item").idle)
    }

    @Test fun concurrentDuplicateCloseCannotUnderflowReaderOrWriterCount() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val registry = MediaLeaseRegistry()
            val reader = registry.tryAcquireRead("item")!!
            val readerCloses = (1..64).map { executor.submit { repeat(50) { reader.close() } } }
            readerCloses.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(registry.snapshot("item").idle)
            val writer = registry.tryAcquireWrite("item", "delete")!!
            val writerCloses = (1..64).map { executor.submit { repeat(50) { writer.close() } } }
            writerCloses.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(registry.snapshot("item").idle)
        } finally { executor.shutdownNow() }
    }

    @Test fun concurrentReadersAllHoldOwnershipUntilTheirOwnClose() {
        val executor = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(16)
        val release = CountDownLatch(1)
        val registry = MediaLeaseRegistry()
        try {
            val readers = (1..16).map {
                executor.submit {
                    val reader = registry.tryAcquireRead("item")!!
                    ready.countDown()
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)) } finally { reader.close() }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(16, registry.snapshot("item").readers)
            assertNull(registry.tryAcquireWrite("item", "delete"))
            assertNull(registry.tryAcquireRead("item"))
            release.countDown()
            readers.forEach { it.get(5, TimeUnit.SECONDS) }
            registry.tryAcquireWrite("item", "delete")!!.close()
            assertTrue(registry.snapshot("item").idle)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun concurrentReadWriteStressMaintainsMutualExclusion() {
        val registry = MediaLeaseRegistry()
        val activeReaders = AtomicInteger()
        val activeWriters = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val workers = (1..8).map { worker ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(200) { iteration ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        if ((worker + iteration) % 3 == 0) {
                            var lease: MediaWriteLease? = null
                            while (lease == null) {
                                check(System.nanoTime() < deadline) { "WRITER_DEADLINE" }
                                lease = registry.tryAcquireWrite("item", "operation_${worker}_$iteration")
                                if (lease == null) Thread.yield()
                            }
                            try {
                                assertEquals(1, activeWriters.incrementAndGet())
                                assertEquals(0, activeReaders.get())
                                Thread.yield()
                                assertEquals(0, activeReaders.get())
                            } finally { activeWriters.decrementAndGet(); lease.close() }
                        } else {
                            var lease: MediaReadLease? = null
                            while (lease == null) {
                                check(System.nanoTime() < deadline) { "READER_DEADLINE" }
                                lease = registry.tryAcquireRead("item")
                                if (lease == null) Thread.yield()
                            }
                            try {
                                activeReaders.incrementAndGet()
                                assertEquals(0, activeWriters.get())
                                Thread.yield()
                                assertEquals(0, activeWriters.get())
                            } finally { activeReaders.decrementAndGet(); lease.close() }
                        }
                    }
                }
            }
            start.countDown()
            workers.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(0, activeReaders.get()); assertEquals(0, activeWriters.get())
            assertTrue(registry.snapshot("item").idle)
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun leaseDiagnosticsRedactItemAndOperationIds() {
        val registry = MediaLeaseRegistry()
        val reader = registry.tryAcquireRead("privateItem")!!
        assertFalse(reader.toString().contains("privateItem"))
        reader.close()
        val writer = registry.tryAcquireWrite("privateItem", "privateOperation")!!
        assertFalse(writer.toString().contains("privateItem"))
        assertFalse(writer.toString().contains("privateOperation"))
        writer.close()
    }
}
