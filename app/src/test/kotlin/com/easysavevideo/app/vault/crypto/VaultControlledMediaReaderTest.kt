package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deterministic fake channel/gate. No Tink, random material, files, auth or devices execute. */
class VaultControlledMediaReaderTest {
    private class Gate : VaultMediaPublicationGate {
        private val lock = Any()
        var live = true
        var inflight = 0
        var publications = 0
        override fun requireCurrent() = synchronized(lock) { if (!live) throw VaultOperationException(VaultFailure.CANCELLED) }
        override fun admit(): VaultMediaIoPermit = synchronized(lock) {
            requireCurrent(); inflight++
            object : VaultMediaIoPermit {
                var closed = false
                override fun publish(scratch: ByteArray, sourceOffset: Int, destination: ByteArray, destinationOffset: Int, length: Int) = synchronized(lock) {
                    requireCurrent(); check(!closed); require(length in 0..VaultMediaLimits.BUFFER_BYTES)
                    System.arraycopy(scratch, sourceOffset, destination, destinationOffset, length); publications++; Unit
                }
                override fun close() = synchronized(lock) { if (!closed) { closed = true; inflight-- }; Unit }
            }
        }
        fun revoke() = synchronized(lock) { live = false }
    }
    private class Channel(val bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) : SeekableByteChannel {
        var open = true; var position = 0; var reads = 0; var seeks = 0; var closes = 0
        var zeros = false; var corrupt = false; var closeFails = false; var sizeDelta = 0L
        var entered: CountDownLatch? = null; var release: CountDownLatch? = null
        override fun read(dst: ByteBuffer): Int {
            reads++; entered?.countDown(); release?.await(5, TimeUnit.SECONDS)
            if (zeros) return 0
            if (corrupt) { dst.put(99); throw IOException("public-fixture") }
            if (position == bytes.size) return -1
            val count = minOf(dst.remaining(), bytes.size - position)
            dst.put(bytes, position, count); position += count; return count
        }
        override fun position() = position.toLong()
        override fun position(newPosition: Long): SeekableByteChannel { seeks++; position = newPosition.toInt(); return this }
        override fun size() = bytes.size.toLong() + sizeDelta
        override fun isOpen() = open
        override fun close() { closes++; if (closeFails) throw IOException("public-close"); open = false }
        override fun write(src: ByteBuffer): Int = error("no-write")
        override fun truncate(size: Long): SeekableByteChannel = error("no-truncate")
    }
    private fun context() = VaultOperationContext(1000, VaultMonotonicClock { 0 })
    private fun failure(body: () -> Unit) { try { body(); fail("Expected failure") } catch (_: VaultOperationException) { } catch (_: IOException) { } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    private fun reader(channel: Channel, gate: Gate, context: VaultOperationContext, owner: () -> Unit = {}, released: () -> Unit = {}) =
        VaultControlledMediaReader(channel.bytes.size.toLong(), channel, gate, context, owner, released)

    @Test fun authenticatesLastByteAndEofThenReadsBoundedRange() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); var released = false
        val reader = reader(ch, gate, ctx, released = { released = true })
        reader.authenticateFinalSegment(ctx)
        assertEquals(2, ch.reads)
        val out = ByteArray(7) { 9 }
        assertEquals(3, reader.readAt(1, out, 2, 5, ctx))
        assertArrayEquals(byteArrayOf(9, 9, 2, 3, 4, 9, 9), out)
        assertEquals(-1, reader.readAt(4, out, 0, 1, ctx)); assertEquals(0, gate.inflight)
        assertEquals(ExportRelease.RELEASED, reader.closeAndObserve()); assertTrue(released)
    }
    @Test fun finalSegmentRejectsWrongSizeMissingFinalByteAndZeroProgress() {
        val wrong = Channel().also { it.sizeDelta = 1 }
        val wrongContext = context()
        failure { reader(wrong, Gate(), wrongContext).authenticateFinalSegment(wrongContext) }
        val zero = Channel().also { it.zeros = true }; val ctx = context(); val gate = Gate()
        failure { reader(zero, gate, ctx).authenticateFinalSegment(ctx) }
        assertEquals(VaultMediaLimits.MAX_ZERO_PROGRESS + 1, zero.reads); assertEquals(0, gate.inflight)
    }
    @Test fun corruptionDoesNotChangeCallerAndRetiresScratchAndPermit() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); val reader = reader(ch, gate, ctx)
        reader.authenticateFinalSegment(ctx); ch.corrupt = true
        val out = ByteArray(4) { 8 }; failure { reader.readAt(0, out, 0, 4, ctx) }
        assertArrayEquals(ByteArray(4) { 8 }, out); assertEquals(0, gate.publications); assertEquals(0, gate.inflight)
        assertEquals(ExportRelease.RELEASED, reader.closeAndObserve())
        val field = reader.javaClass.getDeclaredField("scratch").also { it.isAccessible = true }
        assertTrue((field.get(reader) as ByteArray).all { it == 0.toByte() })
    }
    @Test fun queuedAfterLockDoesNotSeekOrReadEvenIfChannelCachesBytes() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); val reader = reader(ch, gate, ctx)
        reader.authenticateFinalSegment(ctx); val reads = ch.reads; val seeks = ch.seeks
        gate.revoke(); failure { reader.readAt(0, ByteArray(4), 0, 4, ctx) }
        assertEquals(reads, ch.reads); assertEquals(seeks, ch.seeks)
        assertEquals(ExportRelease.RELEASED, reader.closeAndObserve())
        failure { reader.readAt(0, ByteArray(4), 0, 4, ctx) }
        assertEquals(reads, ch.reads)
    }
    @Test fun blockedReadAfterLockCannotPublishAndCloseWaitsForActualIo() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); var released = false
        val reader = reader(ch, gate, ctx, released = { released = true })
        reader.authenticateFinalSegment(ctx)
        ch.entered = CountDownLatch(1); ch.release = CountDownLatch(1)
        val out = ByteArray(4) { 7 }; val result = AtomicReference<Throwable?>()
        val read = Thread { try { reader.readAt(0, out, 0, 4, ctx) } catch (e: Throwable) { result.set(e) } }
        read.start(); assertTrue(ch.entered!!.await(2, TimeUnit.SECONDS)); assertEquals(1, gate.inflight)
        gate.revoke(); reader.revoke()
        val closed = CountDownLatch(1)
        val closer = Thread { reader.closeAndObserve(); closed.countDown() }
        closer.start(); assertFalse(closed.await(50, TimeUnit.MILLISECONDS)); assertFalse(released)
        ch.release!!.countDown(); read.join(2000); closer.join(2000)
        assertNotNull(result.get()); assertArrayEquals(ByteArray(4) { 7 }, out)
        assertTrue(released); assertEquals(0, gate.inflight); assertEquals(0, gate.publications)
    }
    @Test fun unknownCloseRetainsOwnerAndNeverBlindlyRepeatsUnderlyingClose() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); var released = 0
        val reader = reader(ch, gate, ctx, released = { released++ })
        reader.authenticateFinalSegment(ctx); ch.closeFails = true
        assertEquals(ExportRelease.UNCONFIRMED, reader.closeAndObserve()); assertEquals(0, released)
        failure { reader.readAt(0, ByteArray(4), 0, 4, ctx) }
        ch.closeFails = false
        assertEquals(ExportRelease.UNCONFIRMED, reader.closeAndObserve()); assertEquals(0, released)
        assertEquals(ExportRelease.UNCONFIRMED, reader.closeAndObserve()); assertEquals(0, released)
        assertEquals(1, ch.closes)
    }
    @Test fun invalidRangeForeignContextAndOwnerRevocationDoNoIo() {
        val ch = Channel(); val gate = Gate(); val ctx = context(); var live = true
        val reader = reader(ch, gate, ctx, owner = { if (!live) throw VaultOperationException(VaultFailure.CANCELLED) })
        reader.authenticateFinalSegment(ctx); val reads = ch.reads
        failure { reader.readAt(Long.MAX_VALUE, ByteArray(4), 0, 4, ctx) }
        failure { reader.readAt(0, ByteArray(4), Int.MAX_VALUE, 4, ctx) }
        failure { reader.readAt(0, ByteArray(4), 0, 4, context()) }
        failure { reader.readAt(0, ByteArray(65537), 0, 65537, ctx) }
        live = false; failure { reader.readAt(0, ByteArray(4), 0, 4, ctx) }
        assertEquals(reads, ch.reads); assertEquals(0, gate.inflight)
    }
    @Test fun ciphertextAdapterBoundsUnderlyingReadsAndDoesNotCloseStoreAnchor() {
        var largest = 0; var anchorCloses = 0
        val file = object : VaultOwnedCiphertext {
            override val operationId = "public-operation"
            override val identity = VaultMediaObjectIdentity("public-object", 1)
            override fun append(bytes: ByteArray, offset: Int, length: Int) = error("no-write")
            override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int {
                largest = maxOf(largest, length); bytes.fill(1, offset, offset + length); return length
            }
            override fun size() = 1024L * 1024L
            override fun forceAndCloseWrite() = ExportRelease.RELEASED
            override fun closeAndObserve(): ExportRelease { anchorCloses++; return ExportRelease.RELEASED }
        }
        val ctx = context(); val gate = Gate()
        val channel = VaultCiphertextReadChannel(file, file.size(), gate, ctx) {}
        assertEquals(VaultMediaLimits.BUFFER_BYTES, channel.read(ByteBuffer.allocate(1024 * 1024)))
        assertEquals(VaultMediaLimits.BUFFER_BYTES, largest); assertEquals(0, gate.inflight)
        channel.close(); assertEquals(0, anchorCloses)
        failure { channel.position(Long.MAX_VALUE) }
    }
    @Test fun ciphertextAdapterBoundsZeroProgressBeforeCallingTink() {
        var reads = 0
        val file = object : VaultOwnedCiphertext {
            override val operationId = "public-operation"
            override val identity = VaultMediaObjectIdentity("public-object", 1)
            override fun append(bytes: ByteArray, offset: Int, length: Int) = error("no-write")
            override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
            override fun size() = 100L
            override fun forceAndCloseWrite() = ExportRelease.RELEASED
            override fun closeAndObserve() = ExportRelease.RELEASED
        }
        val ctx = context(); val gate = Gate()
        val channel = VaultCiphertextReadChannel(file, 100, gate, ctx) {}
        failure { channel.read(ByteBuffer.allocate(100)) }
        assertEquals(VaultMediaLimits.MAX_ZERO_PROGRESS + 1, reads); assertEquals(0, gate.inflight)
    }
    @Test fun lateAcceptanceUnknownReaderCloseIsVisibleFailure() {
        val result = object : VaultMediaReadCapability {
            override val authenticatedSize = 1L
            var revoked = false
            override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int, context: VaultOperationContext) = -1
            override fun revoke() { revoked = true }
            override fun closeAndObserve() = ExportRelease.UNCONFIRMED
        }
        val expired = VaultOperationContext(1, VaultMonotonicClock { 1 })
        try { finishVaultCryptoResult(result, expired) {}; fail("Expected unknown release") }
        catch (e: VaultOperationException) { assertEquals(VaultFailure.RELEASE_UNKNOWN, e.category) }
        assertTrue(result.revoked)
    }
}
