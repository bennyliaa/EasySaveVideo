package com.easysavevideo.app.discovery.thumbnail

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** Executes the production bounded reader. No network, Android decoder or image loader. */
class CommonsThumbnailPolicyTest {
    private class Body(
        private val bytes: ByteArray,
        private val chunkSize: Int = 8192,
        private val afterRead: (Int) -> Unit = {},
    ) : InputStream() {
        var reads = 0
        var consumed = 0
        var eofReads = 0
        val requests = mutableListOf<Int>()
        override fun read(): Int = throw AssertionError("Reader must use bounded bulk reads")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(length > 0)
            reads++
            requests += length
            if (consumed == bytes.size) {
                eofReads++
                afterRead(-1)
                return -1
            }
            val count = minOf(length, chunkSize, bytes.size - consumed)
            bytes.copyInto(target, offset, consumed, consumed + count)
            consumed += count
            afterRead(count)
            return count
        }
    }

    private fun read(body: InputStream, length: Long? = null): ByteArray =
        CommonsThumbnailPolicy.readBody(body, length) { false }

    @Test fun exactCapRequiresAnAdditionalTrueEofProbeWithAndWithoutDeclaredLength() {
        val bytes = ByteArray(CommonsThumbnailPolicy.MAX_ENCODED) { (it % 251).toByte() }
        for (declared in listOf(null, bytes.size.toLong())) {
            val body = Body(bytes)
            assertArrayEquals(bytes, read(body, declared))
            assertEquals(bytes.size, body.consumed)
            assertEquals(1, body.eofReads)
            assertEquals(1, body.requests.last())
            assertTrue(body.requests.all { it in 1..8192 })
        }
    }

    @Test fun capPlusOneIsRejectedEvenWhenLengthClaimsExactlyTheCap() {
        val cap = CommonsThumbnailPolicy.MAX_ENCODED
        for (declared in listOf(null, cap.toLong())) {
            val body = Body(ByteArray(cap + 1))
            assertThrows(IllegalStateException::class.java) { read(body, declared) }
            assertEquals(cap + 1, body.consumed)
            assertEquals(0, body.eofReads)
            assertEquals(1, body.requests.last())
        }
    }

    @Test fun chunkedUnknownLengthBodyIsConsumedThroughEofWithoutAssumingFullReads() {
        val bytes = ByteArray(20_017) { (it % 127).toByte() }
        for (chunkSize in listOf(1, 7, 8192)) {
            val body = Body(bytes, chunkSize)
            assertArrayEquals(bytes, read(body))
            assertEquals(1, body.eofReads)
            assertEquals(bytes.size, body.consumed)
        }
    }

    @Test fun validDeclaredLengthStillRequiresTrueEof() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val body = Body(bytes, 2)
        assertArrayEquals(bytes, read(body, 5))
        assertEquals(1, body.eofReads)
        assertEquals(4, body.reads)
    }

    @Test fun emptyBodyHasNoSpecialEarlySuccessBeforeEof() {
        for (declared in listOf(null, 0L)) {
            val body = Body(byteArrayOf())
            assertArrayEquals(byteArrayOf(), read(body, declared))
            assertEquals(1, body.reads)
            assertEquals(1, body.eofReads)
        }
    }

    @Test fun declaredShortLongAndZeroLengthsFailAfterConsumingTrueEof() {
        for (declared in listOf(0L, 3L, 5L)) {
            val body = Body(byteArrayOf(1, 2, 3, 4), 2)
            assertThrows(IllegalStateException::class.java) { read(body, declared) }
            assertEquals(4, body.consumed)
            assertEquals(1, body.eofReads)
        }
    }

    @Test fun negativeAndOverCapDeclaredLengthsFailBeforeAnyRead() {
        for (declared in listOf(-1L, Long.MIN_VALUE,
            CommonsThumbnailPolicy.MAX_ENCODED.toLong() + 1, Long.MAX_VALUE)) {
            val body = Body(byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) { read(body, declared) }
            assertEquals(0, body.reads)
        }
    }

    @Test fun zeroProgressFailsImmediatelyInsteadOfSpinningOrTreatingItAsEof() {
        var reads = 0
        val body = object : InputStream() {
            override fun read(): Int = throw AssertionError("No unbounded fallback read")
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                reads++
                check(reads == 1) { "Zero progress was retried" }
                return 0
            }
        }
        assertThrows(IllegalStateException::class.java) { read(body) }
        assertEquals(1, reads)
    }

    @Test fun zeroProgressAfterAPrefixNeverReturnsPartialBytes() {
        var reads = 0
        val body = object : InputStream() {
            override fun read(): Int = throw AssertionError("No unbounded fallback read")
            override fun read(target: ByteArray, offset: Int, length: Int): Int = when (++reads) {
                1 -> { target[offset] = 1; 1 }
                2 -> 0
                else -> throw AssertionError("Zero progress was retried")
            }
        }
        assertThrows(IllegalStateException::class.java) { read(body, 1) }
        assertEquals(2, reads)
    }

    @Test fun cancellationBeforeReadDoesNotTouchTheBody() {
        val body = Body(byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) {
            CommonsThumbnailPolicy.readBody(body, null) { true }
        }
        assertEquals(0, body.reads)
    }

    @Test fun cancellationDuringReadRejectsTheJustReadBytesWithoutAnotherRead() {
        var cancelled = false
        val body = Body(ByteArray(20), 3) { cancelled = true }
        assertThrows(IllegalStateException::class.java) {
            CommonsThumbnailPolicy.readBody(body, null) { cancelled }
        }
        assertEquals(1, body.reads)
        assertEquals(3, body.consumed)
        assertEquals(0, body.eofReads)
    }

    @Test fun cancellationAtTheFinalEofProbeStillRejectsTheResult() {
        var cancelled = false
        val body = Body(byteArrayOf(1, 2, 3), afterRead = { if (it == -1) cancelled = true })
        assertThrows(IllegalStateException::class.java) {
            CommonsThumbnailPolicy.readBody(body, 3) { cancelled }
        }
        assertEquals(3, body.consumed)
        assertEquals(1, body.eofReads)
    }

    @Test fun inputFailureIsNotConvertedIntoASuccessfulPartialBody() {
        val failure = IOException("OWNED_TEST_FAILURE")
        val body = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(target: ByteArray, offset: Int, length: Int): Int = throw failure
        }
        assertSame(failure, assertThrows(IOException::class.java) { read(body) })
    }

    @Test fun sourceDimensionsUseBothPerAxisAndLongPixelBudgets() {
        for ((width, height) in listOf(1 to 1, 8192 to 2048, 2048 to 8192, 4096 to 4096))
            assertTrue("$width x $height", CommonsThumbnailPolicy.sourceDimensions(width, height))
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1,
            8193 to 1, 1 to 8193, 8192 to 2049, 2049 to 8192, 4097 to 4096,
            Int.MAX_VALUE to Int.MAX_VALUE, Int.MIN_VALUE to Int.MIN_VALUE))
            assertFalse("$width x $height", CommonsThumbnailPolicy.sourceDimensions(width, height))
    }

    @Test fun decodedOutputRequiresSoftwarePositiveDimensionsAndLongAllocationBudget() {
        assertEquals(147_456L, CommonsThumbnailPolicy.MAX_FRAME)
        assertTrue(CommonsThumbnailPolicy.output(256, 144, CommonsThumbnailPolicy.MAX_FRAME, true))
        assertTrue(CommonsThumbnailPolicy.output(1, 1, 1, true))
        assertFalse(CommonsThumbnailPolicy.output(256, 144, CommonsThumbnailPolicy.MAX_FRAME, false))
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1,
            257 to 144, 256 to 145, Int.MAX_VALUE to Int.MAX_VALUE))
            assertFalse("$width x $height", CommonsThumbnailPolicy.output(width, height, 4, true))
        for (bytes in listOf(0L, -1L, Long.MIN_VALUE, CommonsThumbnailPolicy.MAX_FRAME + 1, Long.MAX_VALUE))
            assertFalse("bytes=$bytes", CommonsThumbnailPolicy.output(256, 144, bytes, true))
        assertEquals(2_359_296L, CommonsThumbnailPolicy.MAX_RETAINED)
        assertEquals(CommonsThumbnailPolicy.MAX_FRAME * CommonsThumbnailPolicy.MAX_VISIBLE,
            CommonsThumbnailPolicy.MAX_RETAINED)
    }
}
