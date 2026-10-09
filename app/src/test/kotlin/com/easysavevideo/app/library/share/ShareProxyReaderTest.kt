package com.easysavevideo.app.library.share

import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ShareProxyReaderTest {
    private val bytes = ByteArray(180_123) { (it % 251).toByte() }
    @Test fun actualFilePrefixMiddleTailAndEof() {
        val file = Files.createTempFile("share-proxy-policy", ".bin").toFile()
        try {
            file.writeBytes(bytes)
            val input = FileInputStream(file)
            val reader = ShareProxyReader(bytes.size.toLong(),
                { data, offset, start, count -> input.channel.read(ByteBuffer.wrap(data, start, count), offset) },
                { input.close(); !input.fd.valid() }, { true })
            listOf(0, 32_771, 160_019, bytes.size).forEach { offset ->
                val output = ByteArray(20_104)
                val count = reader.read(offset.toLong(), output.size, output)
                assertEquals(minOf(output.size, bytes.size - offset), count)
                assertArrayEquals(bytes.copyOfRange(offset, offset + count), output.copyOf(count))
            }
            assertEquals(0, reader.read(Long.MAX_VALUE, 1, ByteArray(1)))
            assertTrue(reader.release()); assertFalse(input.fd.valid())
            fails { reader.read(0, 1, ByteArray(1)) }
        } finally { file.delete() }
    }
    @Test fun full128KiBRequestFilledDespiteShortUnderlyingReads() {
        var calls = 0
        val reader = fake { data, offset, start, count ->
            calls++
            val amount = minOf(count, 7_000, bytes.size - offset.toInt())
            bytes.copyInto(data, start, offset.toInt(), offset.toInt() + amount)
            amount
        }
        val output = ByteArray(SharePolicy.MAX_PROXY_REQUEST_BYTES)
        assertEquals(output.size, reader.read(0, output.size, output))
        assertArrayEquals(bytes.copyOf(output.size), output)
        assertTrue(calls > 2)
    }
    @Test fun negativeMalformedAndOversizeReadRejected() {
        val reader = fake()
        fails { reader.read(-1, 1, ByteArray(1)) }
        fails { reader.read(0, 2, ByteArray(1)) }
        fails { reader.read(0, SharePolicy.MAX_PROXY_REQUEST_BYTES + 1, ByteArray(SharePolicy.MAX_PROXY_REQUEST_BYTES + 1)) }
    }
    @Test fun zeroTruncatedAndImpossibleChannelReturnsFail() {
        fails { fake { _, _, _, _ -> 0 }.read(0, 1, ByteArray(1)) }
        fails { fake { _, _, _, _ -> -1 }.read(0, 1, ByteArray(1)) }
        fails { fake { _, _, _, count -> count + 1 }.read(0, 1, ByteArray(1)) }
    }
    @Test fun revokedBeforeOrDuringReadNeverReturnsSuccessfulBytes() {
        var allowed = false
        val reader = ShareProxyReader(bytes.size.toLong(), { _, _, _, _ -> 1 }, { true }, { allowed })
        fails { reader.read(0, 1, ByteArray(1)) }
        allowed = true
        val during = ShareProxyReader(bytes.size.toLong(), { data, _, _, _ -> data[0] = 1; allowed = false; 1 }, { true }, { allowed })
        fails { during.read(0, 1, ByteArray(1)) }
    }
    @Test fun closeUncertaintyRetainedAndNoAutomaticRetry() {
        var closeCalls = 0
        val reader = ShareProxyReader(1, { _, _, _, _ -> 1 }, { closeCalls++; false }, { true })
        assertFalse(reader.release()); assertFalse(reader.release()); assertEquals(1, closeCalls)
        fails { reader.read(0, 1, ByteArray(1)) }
        assertFalse(ShareProxyReader(1, { _, _, _, _ -> 1 }, { throw IOException("TECHNICAL") }, { true }).release())
    }
    @Test fun independentRepeatedDescriptorsHaveSeparateBoundedBudgets() {
        repeat(SharePolicy.MAX_OPENS) {
            val reader = ShareProxyReader(10, { data, _, start, count -> repeat(count) { data[start + it] = 3 }; count }, { true }, { true })
            repeat(8) { assertEquals(10, reader.read(0, 10, ByteArray(10))) }
            fails { reader.read(0, 1, ByteArray(1)) }
            assertTrue(reader.release())
        }
    }
    @Test fun releaseWaitsForActualReadExit() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1); val closed = AtomicBoolean(false)
        val reader = ShareProxyReader(1, { _, _, _, _ -> entered.countDown(); assertTrue(exit.await(2, TimeUnit.SECONDS)); 1 }, { closed.set(true); true }, { true })
        val failure = AtomicReference<Throwable?>(null)
        val reading = Thread { try { reader.read(0, 1, ByteArray(1)) } catch (problem: Throwable) { failure.set(problem) } }
        val closing = Thread { try { reader.release() } catch (problem: Throwable) { failure.set(problem) } }
        reading.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); closing.start()
        assertFalse(closed.get()); exit.countDown()
        reading.join(2000); closing.join(2000)
        assertFalse(reading.isAlive); assertFalse(closing.isAlive); assertTrue(closed.get()); assertNull(failure.get())
    }
    private fun fake(read: ((ByteArray, Long, Int, Int) -> Int)? = null): ShareProxyReader = ShareProxyReader(bytes.size.toLong(), read ?: { data, offset, start, count ->
        bytes.copyInto(data, start, offset.toInt(), offset.toInt() + count); count
    }, { true }, { true })
    private fun fails(block: () -> Unit) { try { block(); fail("Expected bounded proxy failure") } catch (_: IOException) { } }
}
