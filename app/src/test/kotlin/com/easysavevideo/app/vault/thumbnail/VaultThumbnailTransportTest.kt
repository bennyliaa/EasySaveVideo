package com.easysavevideo.app.vault.thumbnail

import com.easysavevideo.app.vault.VaultThumbnailReadCallback
import com.easysavevideo.app.vault.VaultThumbnailReadPort
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VaultThumbnailTransportTest {
    internal class Read(var extent: Long = 200_000) : VaultThumbnailReadPort {
        var current = true; var opened = 0; var closes = 0; var callbacks = 0; var active = 0
        var reads = 0; var returned = 0L; var budget = 8L * 1024 * 1024; var maxCallbacks = 512
        var resultOverride: Int? = null; var readAction: (() -> Unit)? = null
        override val authenticatedSize get() = extent
        override fun requireCurrent() { if (!current) throw IOException("fixed") }
        override fun transportOpened() { requireCurrent(); check(opened == 0); opened++ }
        override fun transportClosed() { opened--; closes++ }
        override fun beginCallback(byteRead: Boolean): VaultThumbnailReadCallback {
            requireCurrent(); if (callbacks >= maxCallbacks || budget <= 0 || byteRead && active != 0) throw IOException("fixed")
            callbacks++; active++
            return object : VaultThumbnailReadCallback {
                override val remainingBytes = budget
                var closed = false
                override fun close() { if (!closed) { closed = true; active-- } }
            }
        }
        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            requireCurrent(); reads++; readAction?.invoke()
            resultOverride?.let { return it }
            repeat(length) { destination[offset + it] = ((position + it) and 255).toByte() }
            returned += length; budget -= length
            return length
        }
    }
    private fun unavailable(action: () -> Unit) {
        try { action(); fail("expected fixed IOException") } catch (failure: IOException) {
            assertEquals("VAULT_THUMBNAIL_UNAVAILABLE", failure.message); assertNull(failure.cause)
        }
    }
    @Test fun randomSeeksAndLargeRequestsUseBoundedShortReads() {
        val read = Read(); val t = VaultThumbnailTransport(read); val output = ByteArray(100_000) { 7 }
        assertEquals(65_536, t.readAt(33, output, 2, 90_000))
        for (i in 0 until 65_536) assertEquals(((33 + i) and 255).toByte(), output[2 + i])
        assertEquals(7.toByte(), output[0]); assertEquals(7.toByte(), output[65_538])
        assertEquals(8, t.readAt(199_992, output, 1, 99_999)); assertEquals(2, read.reads)
        t.close(); assertEquals(0, read.active)
    }
    @Test fun exactBeyondEofAndZeroAreLiveAccountedCallbacks() {
        val read = Read(10); val t = VaultThumbnailTransport(read)
        assertEquals(10, t.size()); assertEquals(0, t.readAt(Long.MAX_VALUE, ByteArray(1), 0, 0))
        assertEquals(-1, t.readAt(10, ByteArray(1), 0, 1))
        assertEquals(-1, t.readAt(Long.MAX_VALUE, ByteArray(1), 0, 1))
        assertEquals(4, read.callbacks); assertEquals(0, read.reads); assertEquals(0, read.active)
    }
    @Test fun revokedSizeZeroAndEofNeverReturnSuccess() {
        val read = Read(10); val t = VaultThumbnailTransport(read); read.current = false
        unavailable { t.size() }; unavailable { t.readAt(10, ByteArray(1), 0, 1) }
        unavailable { t.readAt(0, ByteArray(1), 0, 0) }; assertEquals(0, read.reads)
    }
    @Test fun invalidRangesCannotOverflowOrMutateDestination() {
        val read = Read(); val t = VaultThumbnailTransport(read); val output = ByteArray(8) { 91 }
        for ((off, len) in listOf(-1 to 1, 0 to -1, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, 7 to 2))
            unavailable { t.readAt(0, output, off, len) }
        unavailable { t.readAt(-1, output, 0, 0) }
        assertTrue(output.all { it == 91.toByte() }); assertEquals(0, read.reads); assertEquals(0, read.active)
    }
    @Test fun noProgressTruncationAndOversizedReturnAreErrorsNotEof() {
        for (bad in listOf(-1, 0, 11)) {
            val read = Read(10).apply { resultOverride = bad }; val t = VaultThumbnailTransport(read)
            unavailable { t.readAt(0, ByteArray(10), 0, 10) }; assertEquals(0, read.active)
        }
    }
    @Test fun remainingOperationBudgetIsAnAdditionalShortReadLimit() {
        val read = Read().apply { budget = 17 }; val t = VaultThumbnailTransport(read)
        assertEquals(17, t.readAt(0, ByteArray(90), 0, 90)); assertEquals(17, read.returned)
        unavailable { t.readAt(17, ByteArray(1), 0, 1) }; unavailable { t.size() }
    }
    @Test fun repeatedGetSizeAndZeroConsumeTheSameFiniteCallbackBudget() {
        val read = Read().apply { maxCallbacks = 4 }; val t = VaultThumbnailTransport(read)
        repeat(2) { t.size(); t.readAt(0, ByteArray(0), 0, 0) }
        unavailable { t.size() }; assertEquals(4, read.callbacks); assertEquals(0, read.active)
    }
    @Test fun closeIsIdempotentTransportFactAndRejectsFurtherCallbacks() {
        val read = Read(); val t = VaultThumbnailTransport(read); t.close(); t.close()
        assertEquals(1, read.closes); assertEquals(0, read.opened)
        unavailable { t.size() }; unavailable { t.readAt(0, ByteArray(1), 0, 1) }
    }
    @Test fun callbackRemainsCountedDuringReadIncludingCloseRace() {
        val read = Read(); val t = VaultThumbnailTransport(read)
        read.readAction = { assertEquals(1, read.active); t.close(); assertEquals(1, read.active) }
        assertEquals(1, t.readAt(0, ByteArray(1), 0, 1)); assertEquals(0, read.active)
        unavailable { t.readAt(0, ByteArray(1), 0, 1) }
    }
    @Test fun illegalAuthenticatedExtentNeverReachesByteRead() {
        for (size in listOf(0L, -1L, Long.MAX_VALUE)) {
            val read = Read(size); val t = VaultThumbnailTransport(read)
            unavailable { t.size() }; unavailable { t.readAt(0, ByteArray(1), 0, 1) }; assertEquals(0, read.reads)
        }
    }
}
